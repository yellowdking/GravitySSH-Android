package com.gravityssh.app.ssh

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gravityssh.app.GravitySshApp
import com.gravityssh.app.R

class SshKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startedAsFgs = intent?.getBooleanExtra(EXTRA_STARTED_AS_FGS, false) == true

        if (intent?.action == ACTION_DISCONNECT_ALL) {
            val needsFgs = synchronized(stateLock) {
                startPending = false
                latestActiveCount = 0
                !isRunning && startedAsFgs
            }
            if (needsFgs) {
                tryStartForeground(0)
            }
            runCatching { GravitySshApp.from(applicationContext).sshSessionManager.closeAllSessions() }
            stopAndClear(startId)
            return START_NOT_STICKY
        }

        val (effectiveCount, alreadyRunning) = synchronized(stateLock) {
            startPending = false
            val count = if (intent == null) 0 else latestActiveCount
            count to isRunning
        }
        if (intent == null || effectiveCount <= 0) {
            if (!alreadyRunning && startedAsFgs) {
                tryStartForeground(0)
            }
            stopAndClear(startId)
            return START_NOT_STICKY
        }

        ensureChannel(this)
        if (alreadyRunning) {
            val stillRunning = synchronized(stateLock) {
                if (isRunning && latestActiveCount > 0) {
                    notifyIfRunningLocked(this)
                    true
                } else {
                    false
                }
            }
            if (!stillRunning) {
                stopAndClear(startId)
            }
            return START_NOT_STICKY
        }

        if (tryStartForeground(effectiveCount)) {
            val shouldStopNow = synchronized(stateLock) {
                if (latestActiveCount <= 0) {
                    isRunning = false
                    true
                } else {
                    isRunning = true
                    if (latestActiveCount != effectiveCount) {
                        notifyIfRunningLocked(this)
                    }
                    false
                }
            }
            if (shouldStopNow) {
                stopAndClear(startId)
            }
        } else {
            stopAndClear(startId)
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopAndClear(startId)
    }

    override fun onDestroy() {
        synchronized(stateLock) {
            isRunning = false
            startPending = false
            cancelNotificationLocked(this)
        }
        super.onDestroy()
    }

    private fun stopAndClear(startId: Int) {
        synchronized(stateLock) {
            isRunning = false
            startPending = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            cancelNotificationLocked(this)
        }
        stopSelf(startId)
    }

    private fun tryStartForeground(count: Int): Boolean {
        ensureChannel(this)
        val notification = buildNotification(this, count)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            true
        } catch (_: SecurityException) {
            false
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                false
            } else {
                false
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        private const val CHANNEL_ID = "gravityssh_keepalive"
        private const val NOTIFICATION_ID = 4102
        private const val EXTRA_COUNT = "active_count"
        private const val EXTRA_STARTED_AS_FGS = "started_as_fgs"
        const val ACTION_DISCONNECT_ALL = "com.gravityssh.app.action.DISCONNECT_ALL"

        private val stateLock = Any()

        private var isRunning: Boolean = false
        private var startPending: Boolean = false
        private var latestActiveCount: Int = 0

        fun update(context: Context, activeCount: Int) {
            val appCtx = context.applicationContext
            val clamped = activeCount.coerceAtLeast(0)
            synchronized(stateLock) {
                latestActiveCount = clamped
                if (clamped > 0) {
                    if (isRunning) {
                        notifyIfRunningLocked(appCtx)
                    } else if (!startPending) {
                        startPending = true
                        val intent = Intent(appCtx, SshKeepAliveService::class.java).apply {
                            putExtra(EXTRA_COUNT, clamped)
                            putExtra(EXTRA_STARTED_AS_FGS, true)
                        }
                        val started = try {
                            ContextCompat.startForegroundService(appCtx, intent)
                            true
                        } catch (_: SecurityException) {
                            // Background FGS restriction on Android 14/15
                            false
                        } catch (e: IllegalStateException) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                                // App is not in a valid state to start FGS
                            }
                            false
                        } catch (_: RuntimeException) {
                            // Ignore OS-level FGS start rejection
                            false
                        }
                        if (!started) {
                            startPending = false
                        }
                    }
                } else if (isRunning) {
                    isRunning = false
                    val intent = Intent(appCtx, SshKeepAliveService::class.java)
                    runCatching { appCtx.stopService(intent) }
                    cancelNotificationLocked(appCtx)
                }
                // If clamped == 0 and startPending == true, do NOT call stopService() prematurely;
                // onStartCommand() will observe latestActiveCount <= 0, call tryStartForeground(0), then stopSelf().
            }
        }

        private fun notifyIfRunningLocked(context: Context) {
            if (!isRunning || latestActiveCount <= 0) return
            runCatching {
                ensureChannel(context)
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, buildNotification(context, latestActiveCount))
            }
        }

        private fun cancelNotificationLocked(context: Context) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(NOTIFICATION_ID)
            }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "SSH 后台保活",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持活跃 SSH 会话在后台不断开"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }

        private fun buildNotification(context: Context, activeCount: Int): Notification {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val openPending = if (launchIntent != null) {
                PendingIntent.getActivity(
                    context,
                    1,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            val disconnectIntent = Intent(context, SshKeepAliveService::class.java).apply {
                action = ACTION_DISCONNECT_ALL
            }
            val disconnectPending = PendingIntent.getService(
                context,
                2,
                disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val contentText = if (activeCount > 0) {
                "当前有 $activeCount 个活跃 SSH 会话保持后台连接"
            } else {
                "正在断开 SSH 会话..."
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("GravitySSH 运行中")
                .setContentText(contentText)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(0, "断开全部", disconnectPending)
            if (openPending != null) builder.setContentIntent(openPending)
            return builder.build()
        }
    }
}
