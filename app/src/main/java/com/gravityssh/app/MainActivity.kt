package com.gravityssh.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* optional notification permission for SSH keepalive service */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF0B0D12.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF10131A.toInt())
        )
        requestNotificationPermissionIfNeeded()

        val container = GravitySshApp.from(this)
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

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
