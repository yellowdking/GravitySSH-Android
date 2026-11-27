package com.gravityssh.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
    var bottomBarHeightDp by remember { mutableStateOf(0.dp) }

    LaunchedEffect(viewModel) {
        viewModel.toastEvents.collect { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(lockState.initialized, lockState.locked) {
        if (!lockState.initialized || lockState.locked) {
            bottomBarHeightDp = 0.dp
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(AgBg)) {
        when {
            !lockState.initialized -> {
                VaultSplashLoadingScreen()
            }
            lockState.locked -> {
                VaultLockScreen(
                    lockState = lockState,
                    masterKeyProvider = viewModel.container.vaultRepository.masterKeyProvider,
                    onUnlockPassword = { pw, done -> viewModel.unlockVault(pw, done) },
                    onUnlockBiometric = { done -> viewModel.unlockVaultWithBiometric(done) },
                    onResetCorruptedVault = { done -> viewModel.resetCorruptedVault(done) }
                )
            }
            else -> {
                GravityUnlockedContent(
                    viewModel = viewModel,
                    activeCount = activeCount,
                    onBottomBarHeightChange = { bottomBarHeightDp = it }
                )
            }
        }

        val snackbarModifier = if (bottomBarHeightDp > 0.dp) {
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomBarHeightDp)
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = snackbarModifier
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = AgElevated,
                contentColor = AgText,
                actionColor = AgAccent
            )
        }
    }
}

@Composable
private fun VaultSplashLoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(AgBg),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 2.5.dp,
            color = AgAccent
        )
    }
}

@Composable
private fun GravityUnlockedContent(
    viewModel: AppViewModel,
    activeCount: Int,
    onBottomBarHeightChange: (Dp) -> Unit
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Routes.SERVERS
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    // Hide terminal bottom bar only once IME rises past the bottom bar height to avoid a downward jump.
    val imeCoversBottomBar by remember(density, imeInsets, navInsets) {
        val barHeightPx = with(density) { 80.dp.roundToPx() }
        derivedStateOf {
            val imeBottom = imeInsets.getBottom(density)
            val threshold = navInsets.getBottom(density) + barHeightPx
            imeBottom >= threshold
        }
    }
    val hideBottomBar = currentRoute == Routes.SYNC ||
        currentRoute == Routes.KEYS ||
        currentRoute == Routes.TOOLS ||
        (currentRoute == Routes.TERMINAL && imeCoversBottomBar)

    LaunchedEffect(hideBottomBar) {
        if (hideBottomBar) onBottomBarHeightChange(0.dp)
    }

    fun navigateTab(route: String) {
        if (currentRoute == route || navController.currentDestination?.route == route) return
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = AgBg,
        bottomBar = {
            if (!hideBottomBar) {
                Box(
                    modifier = Modifier.onSizeChanged { size ->
                        onBottomBarHeightChange(with(density) { size.height.toDp() })
                    }
                ) {
                    GravityBottomBar(
                        currentRoute = currentRoute,
                        activeSessionCount = activeCount,
                        onSelect = ::navigateTab
                    )
                }
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
                composable(Routes.KEYS) {
                    KeyVaultScreen(viewModel = viewModel, onBack = { navController.popBackStack(Routes.KEYS, inclusive = true) })
                }
                composable(Routes.TOOLS) {
                    ToolsScreen(
                        viewModel = viewModel,
                        onJumpToTerminal = {
                            navController.popBackStack(Routes.TOOLS, inclusive = true)
                            navigateTab(Routes.TERMINAL)
                        },
                        onBack = { navController.popBackStack(Routes.TOOLS, inclusive = true) }
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenSync = { navController.navigate(Routes.SYNC) { launchSingleTop = true } },
                        onOpenKeys = { navController.navigate(Routes.KEYS) { launchSingleTop = true } },
                        onOpenTools = { navController.navigate(Routes.TOOLS) { launchSingleTop = true } }
                    )
                }
                composable(Routes.SYNC) {
                    SyncScreen(viewModel = viewModel, onBack = { navController.popBackStack(Routes.SYNC, inclusive = true) })
                }
            }
        }
    }
}
