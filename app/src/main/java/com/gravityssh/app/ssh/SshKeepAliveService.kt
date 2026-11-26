package com.gravityssh.app.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gravityssh.app.GravitySshApp

class SshKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            runCatching { GravitySshApp.from(applicationContext).sshSessionManager.closeAllSessions() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val count = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        if (count <= 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification(count))
        return START_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
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

    private fun buildNotification(activeCount: Int): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPending = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                1,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val disconnectIntent = Intent(this, SshKeepAliveService::class.java).apply {
            action = ACTION_DISCONNECT_ALL
        }
        val disconnectPending = PendingIntent.getService(
            this,
            2,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("GravitySSH 运行中")
            .setContentText("当前有 $activeCount 个活跃 SSH 会话保持后台连接")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "断开全部", disconnectPending)
        if (openPending != null) builder.setContentIntent(openPending)
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "gravityssh_keepalive"
        private const val NOTIFICATION_ID = 4102
        private const val EXTRA_COUNT = "active_count"
        const val ACTION_DISCONNECT_ALL = "com.gravityssh.app.action.DISCONNECT_ALL"

        fun update(context: Context, activeCount: Int) {
            runCatching {
                val intent = Intent(context, SshKeepAliveService::class.java).apply {
                    putExtra(EXTRA_COUNT, activeCount)
                }
                if (activeCount > 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    context.stopService(intent)
                }
            }
        }
    }
}
