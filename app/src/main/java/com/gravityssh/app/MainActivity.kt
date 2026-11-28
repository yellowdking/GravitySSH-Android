package com.gravityssh.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.AppViewModelFactory
import com.gravityssh.app.ui.navigation.GravitySshRoot
import com.gravityssh.app.ui.theme.GravitySshTheme

class MainActivity : FragmentActivity() {

    private var appContainer: AppContainer? = null
    private var backgroundedAtElapsedMs: Long = 0L

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional notification permission for SSH keepalive service */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backgroundedAtElapsedMs = savedInstanceState?.getLong(KEY_BACKGROUNDED_AT_ELAPSED_MS, 0L) ?: 0L
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF0B0D12.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF10131A.toInt())
        )
        requestNotificationPermissionOnceIfNeeded()

        val container = GravitySshApp.from(this)
        appContainer = container
        val viewModel = ViewModelProvider(
            this,
            AppViewModelFactory(container)
        )[AppViewModel::class.java]

        setContent {
            GravitySshTheme {
                GravitySshRoot(viewModel = viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val container = appContainer
        container?.sshSessionManager?.setAppForeground(true)
        val bgAt = backgroundedAtElapsedMs
        backgroundedAtElapsedMs = 0L
        if (container != null && bgAt > 0L) {
            val now = SystemClock.elapsedRealtime()
            if (container.vaultRepository.shouldAutoLock(bgAt, now)) {
                // Synchronous memory wipe + locked=true emission (<0.1ms) prevents 1-frame plaintext UI flash;
                // dirty vault snapshot (if any) flushes asynchronously on VaultRepository's ioScope.
                container.vaultRepository.lockNow()
            }
        }
    }

    override fun onStop() {
        val container = appContainer
        container?.sshSessionManager?.setAppForeground(false)
        if (!isChangingConfigurations) {
            backgroundedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        container?.vaultRepository?.flushPendingHistoryAsync()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isChangingConfigurations && backgroundedAtElapsedMs > 0L) {
            outState.putLong(KEY_BACKGROUNDED_AT_ELAPSED_MS, backgroundedAtElapsedMs)
        }
    }

    private fun requestNotificationPermissionOnceIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) return
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_NOTIF_PERM_ASKED, false)) {
                prefs.edit().putBoolean(KEY_NOTIF_PERM_ASKED, true).apply()
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "gravityssh_prefs"
        private const val KEY_NOTIF_PERM_ASKED = "post_notifications_requested"
        private const val KEY_BACKGROUNDED_AT_ELAPSED_MS = "backgrounded_at_elapsed_ms"
    }
}
