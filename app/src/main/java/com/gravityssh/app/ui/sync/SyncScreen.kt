package com.gravityssh.app.ui.sync

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gravityssh.app.model.SyncFlowStage
import com.gravityssh.app.ui.AppViewModel
import com.gravityssh.app.ui.applySyncPendingPreview
import com.gravityssh.app.ui.cancelSyncFlow
import com.gravityssh.app.ui.clearLastSyncSummary
import com.gravityssh.app.ui.clearSyncPendingPreview
import com.gravityssh.app.ui.components.AgBadge
import com.gravityssh.app.ui.components.AgStatusBanner
import com.gravityssh.app.ui.refreshSyncOverview
import com.gravityssh.app.ui.startSyncInboxWatch
import com.gravityssh.app.ui.stopSyncInboxWatch
import com.gravityssh.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.syncUiState.collectAsStateWithLifecycle()
    val actionBusy by viewModel.syncActionBusy.collectAsStateWithLifecycle()
    val lastSummary by viewModel.lastSyncSummary.collectAsStateWithLifecycle()

    // Default to Mode 0 (Paired Devices) when pairedDevices is non-empty, else Mode 1 (One-Shot Send)
    var userTabChoice by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedTab = userTabChoice ?: if (state.pairedDevices.isNotEmpty() || state.inboxItems.isNotEmpty()) 0 else 1

    DisposableEffect(lifecycleOwner, viewModel, context) {
        viewModel.refreshSyncOverview()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            viewModel.startSyncInboxWatch()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.refreshSyncOverview()
                    viewModel.startSyncInboxWatch()
                }
                Lifecycle.Event.ON_STOP -> {
                    viewModel.stopSyncInboxWatch()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopSyncInboxWatch()
            val changingConfig = context.findSyncActivity()?.isChangingConfigurations == true
            if (!changingConfig) {
                val curStage = viewModel.syncUiState.value.flowStage
                if (curStage == SyncFlowStage.WAITING_PEER ||
                    curStage == SyncFlowStage.AWAITING_SAS_INPUT ||
                    curStage == SyncFlowStage.AWAITING_SAS_CONFIRM
                ) {
                    viewModel.cancelSyncFlow()
                }
            }
        }
    }

    Scaffold(
        containerColor = AgBg,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回", tint = AgText)
                    }
                },
                title = { Text("多端同步", style = MaterialTheme.typography.titleLarge, color = AgText) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AgSurface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = AgSurface,
                contentColor = AgAccent
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { userTabChoice = 0 },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("已配对设备", color = if (selectedTab == 0) AgText else AgMuted)
                            if (state.inboxItems.isNotEmpty()) {
                                AgBadge(text = "${state.inboxItems.size}", color = AgCyan)
                            }
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { userTabChoice = 1 },
                    text = {
                        Text("发送", color = if (selectedTab == 1) AgText else AgMuted)
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { userTabChoice = 2 },
                    text = {
                        Text("接收", color = if (selectedTab == 2) AgText else AgMuted)
                    }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SyncRelayCard(viewModel = viewModel, state = state)

                state.errorMessage?.takeIf { it.isNotBlank() && state.flowStage != SyncFlowStage.AWAITING_SAS_INPUT }?.let { err ->
                    AgStatusBanner(message = err.substringBefore('#'), color = AgDanger)
                }

                if (state.flowStage == SyncFlowStage.COMPLETED && state.statusMessage.isNotBlank()) {
                    AgStatusBanner(
                        message = state.statusMessage,
                        color = AgSuccess,
                        icon = Icons.Rounded.CheckCircle
                    )
                }

                lastSummary?.let { summary ->
                    SyncSummaryBanner(
                        summary = summary,
                        onDismiss = { viewModel.clearLastSyncSummary() }
                    )
                }

                val preview = state.pendingPreview
                if (state.flowStage == SyncFlowStage.READY_PREVIEW && preview != null) {
                    SyncPreviewCard(
                        preview = preview,
                        busy = actionBusy,
                        onMerge = { viewModel.applySyncPendingPreview("merge") },
                        onOverwrite = { viewModel.applySyncPendingPreview("overwrite") },
                        onDiscard = { viewModel.clearSyncPendingPreview() }
                    )
                }

                when (selectedTab) {
                    0 -> SyncPairedCard(viewModel = viewModel, state = state, actionBusy = actionBusy)
                    1 -> SyncSendCard(viewModel = viewModel, state = state)
                    else -> SyncReceiveCard(viewModel = viewModel, state = state)
                }
            }
        }
    }

    SyncSasDialogs(state = state, viewModel = viewModel)
}

private tailrec fun Context.findSyncActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findSyncActivity()
    else -> null
}
