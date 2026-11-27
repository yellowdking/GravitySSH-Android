package com.gravityssh.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.gravityssh.app.model.SessionStatus
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.resetCorruptedVault
import com.gravityssh.app.ui.unlockVault
import com.gravityssh.app.ui.unlockVaultWithBiometric
import com.gravityssh.app.ui.keys.KeyVaultScreen
import com.gravityssh.app.ui.lock.VaultLockScreen
import com.gravityssh.app.ui.servers.ServersScreen
import com.gravityssh.app.ui.settings.SettingsScreen
import com.gravityssh.app.ui.sync.SyncScreen
import com.gravityssh.app.ui.terminal.TerminalWorkspaceScreen
import com.gravityssh.app.ui.theme.AgAccent
import com.gravityssh.app.ui.theme.AgBg
import com.gravityssh.app.ui.theme.AgElevated
import com.gravityssh.app.ui.theme.AgText
import com.gravityssh.app.ui.tools.ToolsScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

object Routes {
    const val SERVERS = "servers"
    const val TERMINAL = "terminal"
    const val KEYS = "keys"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"
    const val SYNC = "sync"
}

@Composable
fun GravitySshRoot(viewModel: AppViewModel) {
    val lockState by remember(viewModel) {
        viewModel.vaultState.map { it.lockState }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = viewModel.vaultState.value.lockState)
    val activeCount by remember(viewModel) {
        viewModel.sessionTabs
            .map { tabs ->
                tabs.count { it.status == SessionStatus.CONNECTED || it.status == SessionStatus.CONNECTING }
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = 0)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg -> snackbarHostState.showSnackbar(msg) }
    }

    if (lockState.locked) {
        VaultLockScreen(
            lockState = lockState,
            onUnlockPassword = { pw, done -> viewModel.unlockVault(pw, done) },
            onUnlockBiometric = { done -> viewModel.unlockVaultWithBiometric(done) },
            onResetCorruptedVault = { done -> viewModel.resetCorruptedVault(done) }
        )
        return
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Routes.SERVERS
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val imeVisible by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    val hideBottomBar = currentRoute == Routes.SYNC || (currentRoute == Routes.TERMINAL && imeVisible)

    fun navigateTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = AgBg,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data, containerColor = AgElevated, contentColor = AgText, actionColor = AgAccent)
            }
        },
        bottomBar = {
            if (!hideBottomBar) {
                GravityBottomBar(currentRoute = currentRoute, activeSessionCount = activeCount, onSelect = ::navigateTab)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AgBg)
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            NavHost(navController = navController, startDestination = Routes.SERVERS) {
                composable(Routes.SERVERS) {
                    ServersScreen(
                        viewModel = viewModel,
                        onConnectAndOpenTerminal = { server ->
                            viewModel.openServerSession(server)
                            navigateTab(Routes.TERMINAL)
                        }
                    )
                }
                composable(Routes.TERMINAL) {
                    TerminalWorkspaceScreen(viewModel = viewModel, onJumpToServers = { navigateTab(Routes.SERVERS) })
                }
                composable(Routes.KEYS) { KeyVaultScreen(viewModel = viewModel) }
                composable(Routes.TOOLS) {
                    ToolsScreen(viewModel = viewModel, onJumpToTerminal = { navigateTab(Routes.TERMINAL) })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(viewModel = viewModel, onOpenSync = { navController.navigate(Routes.SYNC) })
                }
                composable(Routes.SYNC) {
                    SyncScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
