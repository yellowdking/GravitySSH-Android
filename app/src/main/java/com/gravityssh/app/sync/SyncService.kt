package com.gravityssh.app.sync

import android.content.Context
import android.os.Build
import android.os.Looper
import com.gravityssh.app.model.SyncBundle
import com.gravityssh.app.model.SyncFlowStage
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncInboxItemUi
import com.gravityssh.app.model.SyncPairedDeviceUi
import com.gravityssh.app.model.SyncPingResultUi
import com.gravityssh.app.model.SyncReceivePreview
import com.gravityssh.app.model.SyncRelayStatusUi
import com.gravityssh.app.model.SyncUiState
import com.gravityssh.app.vault.VaultRepository
import com.gravityssh.app.vault.VaultSyncKeyStore
import com.yellowdking.synctool.client.DeliveryMode
import com.yellowdking.synctool.client.SendOptions
import com.yellowdking.synctool.client.SyncClient
import com.yellowdking.synctool.client.SyncClientConfig
import com.yellowdking.synctool.envelope.ErrorCode
import com.yellowdking.synctool.envelope.MemoryKeyStore
import com.yellowdking.synctool.envelope.RelayCertExpiringEvent
import com.yellowdking.synctool.envelope.RelayProfile
import com.yellowdking.synctool.envelope.SyncError
import com.yellowdking.synctool.envelope.SyncUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SyncService internal constructor(
    internal val vaultRepository: VaultRepository,
    internal val cacheBaseDirProvider: () -> File,
    internal val defaultDeviceLabelProvider: () -> String = ::resolveAndroidDeviceLabel,
    internal val defaultRelayUriOverride: (() -> String)? = null
) {
    constructor(
        context: Context,
        vaultRepository: VaultRepository
    ) : this(
        vaultRepository = vaultRepository,
        cacheBaseDirProvider = {
            val appCtx = context.applicationContext ?: context
            val base = appCtx.cacheDir ?: File(System.getProperty("java.io.tmpdir", "."))
            File(base, TEMP_DIR_NAME)
        }
    )

    internal data class CachedClientHolder(
        val relayUriKey: String,
        val deviceLabel: String,
        val profile: RelayProfile,
        val isCustom: Boolean,
        val client: SyncClient,
        val mailboxSynced: AtomicBoolean = AtomicBoolean(false)
    )

    internal val stateLock = Any()
    internal val keyStore = VaultSyncKeyStore(vaultRepository)
    internal val serviceJob = SupervisorJob()
    internal val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    internal val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val lockEpoch = AtomicLong(0L)
    internal val flowGeneration = AtomicLong(0L)

    internal var activeClientHolder: CachedClientHolder? = null
    internal var activeFlowJob: Job? = null
    internal var inboxWatchJob: Job? = null
    internal var sasInputDeferred: CompletableDeferred<String>? = null
    internal var sasConfirmDeferred: CompletableDeferred<Boolean>? = null
    internal var pendingPreviewBundle: Pair<SyncReceivePreview, SyncBundle>? = null
    internal var lastCertMetaByRelayId: Pair<String, Pair<Long, Boolean>>? = null

    internal val _uiState = MutableStateFlow(SyncUiState())
    val uiState: StateFlow<SyncUiState> = _uiState.asStateFlow()

    init {
        vaultRepository.addOnLockListener { onVaultLocked() }
        watcherScope.launch {
            vaultRepository.state
                .map { it.lockState.initialized to it.lockState.locked }
                .distinctUntilChanged()
                .collect { (initialized, locked) ->
                    if (locked) {
                        onVaultLocked()
                    } else if (initialized) {
                        refreshOverviewInternal()
                    }
                }
        }
    }

    fun refreshOverview() {
        if (vaultRepository.state.value.lockState.locked) {
            _uiState.value = SyncUiState()
            return
        }
        serviceScope.launch { refreshOverviewInternal() }
    }

    internal suspend fun refreshOverviewInternal() {
        val epoch = lockEpoch.get()
        val syncSnap = vaultRepository.readSyncStateOrNull() ?: return
        runCatching {
            val holder = getOrCreateClientHolder()
            val identity = holder.client.getIdentity()
            val devices = holder.client.keyStore.listDevices().map { card ->
                SyncPairedDeviceUi(card.deviceId, card.label, card.platform, card.relayId, card.issuedAt)
            }
            val certMeta = synchronized(stateLock) {
                lastCertMetaByRelayId?.takeIf { it.first == holder.profile.relayId }?.second
            }
            val relayUi = SyncConfig.toRelayStatusUi(
                profile = holder.profile,
                isCustom = holder.isCustom,
                certExpiresAtEpochSec = certMeta?.first ?: 0L,
                certRotateRecommended = certMeta?.second ?: false
            )
            if (lockEpoch.get() == epoch && !vaultRepository.state.value.lockState.locked) {
                _uiState.update { cur ->
                    cur.copy(
                        localDeviceId = identity.deviceId,
                        localDeviceLabel = identity.label,
                        relay = relayUi,
                        pairedDevices = devices
                    )
                }
            }
            if ((devices.isNotEmpty() || syncSnap.pendingTokenDeletes.isNotEmpty()) &&
                holder.mailboxSynced.compareAndSet(false, true)
            ) {
                serviceScope.launch { runCatching { holder.client.syncMailbox() } }
            }
        }.onFailure { e ->
            if (lockEpoch.get() == epoch && !vaultRepository.state.value.lockState.locked) {
                _uiState.update { it.copy(errorMessage = SyncConfig.toFriendlyException(e).message) }
            }
        }
    }

    suspend fun saveCustomRelayUri(rawUri: String): Result<SyncRelayStatusUi> = withContext(Dispatchers.IO) {
        runCatching {
            requireUnlocked("保存同步中继配置")
            val trimmed = rawUri.trim()
            val canonicalCustomUri = if (trimmed.isEmpty()) {
                ""
            } else {
                SyncUri.formatRelayUri(SyncUri.parseRelayUri(trimmed))
            }
            vaultRepository.updateSyncState { it.copy(customRelayUri = canonicalCustomUri) }
            invalidateClientHolder()

            val holder = getOrCreateClientHolder()
            val identity = holder.client.getIdentity()
            val devices = holder.client.keyStore.listDevices().map { card ->
                SyncPairedDeviceUi(card.deviceId, card.label, card.platform, card.relayId, card.issuedAt)
            }
            val relayUi = SyncConfig.toRelayStatusUi(holder.profile, holder.isCustom)
            _uiState.update { cur ->
                cur.copy(
                    localDeviceId = identity.deviceId,
                    localDeviceLabel = identity.label,
                    relay = relayUi,
                    pairedDevices = devices,
                    pingResult = null,
                    errorMessage = null,
                    statusMessage = if (holder.isCustom) "已保存自定义中继" else "已恢复默认官方中继"
                )
            }
            if (inboxWatchJob?.isActive == true) {
                stopForegroundInboxWatch()
                startForegroundInboxWatch()
            }
            relayUi
        }.recoverCatching { e ->
            val friendly = SyncConfig.toFriendlyException(e)
            _uiState.update { it.copy(errorMessage = friendly.message) }
            throw friendly
        }
    }

    suspend fun testRelay(rawUri: String = ""): Result<SyncPingResultUi> = withContext(Dispatchers.IO) {
        _uiState.update { it.copy(isPinging = true, errorMessage = null) }
        try {
            runCatching {
                val trimmed = rawUri.trim()
                val (pingRes, testedRelayId) = if (trimmed.isNotEmpty()) {
                    val profile = SyncUri.parseRelayUri(trimmed)
                    probeRelayPing(profile) to profile.relayId
                } else if (!vaultRepository.state.value.lockState.locked) {
                    val holder = getOrCreateClientHolder()
                    holder.client.ping() to holder.profile.relayId
                } else {
                    val (profile, _) = SyncConfig.resolveRelayProfile("", defaultRelayUriOverride)
                    probeRelayPing(profile) to profile.relayId
                }

                val certIso = if (pingRes.certExpiresAt > 0L) {
                    runCatching { Instant.ofEpochSecond(pingRes.certExpiresAt).toString() }.getOrDefault("")
                } else {
                    ""
                }
                val resultUi = SyncPingResultUi(
                    rttMs = pingRes.rttMs,
                    profile = pingRes.profile.ifBlank { "standard" },
                    spoolMode = pingRes.spoolBackend.ifBlank { "off" },
                    clockOffsetSec = pingRes.clockOffsetSec,
                    certExpiresAt = certIso,
                    certRotateRecommended = pingRes.certRotateRecommended
                )
                synchronized(stateLock) {
                    lastCertMetaByRelayId = testedRelayId to (pingRes.certExpiresAt to pingRes.certRotateRecommended)
                }
                _uiState.update { cur ->
                    val updatedRelay = if (cur.relay.relayId == testedRelayId) {
                        cur.relay.copy(certExpiresAt = certIso, certRotateRecommended = pingRes.certRotateRecommended)
                    } else {
                        cur.relay
                    }
                    cur.copy(relay = updatedRelay, pingResult = resultUi, errorMessage = null)
                }
                resultUi
            }.recoverCatching { e ->
                val friendly = SyncConfig.toFriendlyException(e)
                _uiState.update { it.copy(errorMessage = friendly.message) }
                throw friendly
            }
        } finally {
            _uiState.update { it.copy(isPinging = false) }
        }
    }

    suspend fun updateDeviceLabel(label: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireUnlocked("修改设备名称")
            val clean = label.trim().ifBlank { defaultDeviceLabelProvider() }.take(64)
            vaultRepository.updateSyncState { it.copy(localDeviceLabel = clean) }
            invalidateClientHolder()
            refreshOverviewInternal()
            _uiState.update { it.copy(localDeviceLabel = clean, errorMessage = null, statusMessage = "已更新本机设备名称") }
        }.recoverCatching { e ->
            val friendly = SyncConfig.toFriendlyException(e)
            _uiState.update { it.copy(errorMessage = friendly.message) }
            throw friendly
        }
    }

    fun startForegroundInboxWatch() {
        if (vaultRepository.state.value.lockState.locked) return
        val shouldLaunch = synchronized(stateLock) {
            if (inboxWatchJob?.isActive == true) {
                false
            } else {
                val epoch = lockEpoch.get()
                inboxWatchJob = serviceScope.launch {
                    runCatching {
                        val holder = getOrCreateClientHolder()
                        runCatching { holder.client.syncMailbox() }
                        refreshInbox()
                        holder.client.watchInbox { _ ->
                            if (lockEpoch.get() == epoch && !vaultRepository.state.value.lockState.locked) {
                                refreshInbox()
                            }
                        }
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        val se = e as? SyncError
                        if (se?.code == ErrorCode.ERR_SPOOL_DISABLED) return@onFailure
                        if (lockEpoch.get() == epoch && !vaultRepository.state.value.lockState.locked && se?.isSecurityAlert == true) {
                            _uiState.update { it.copy(errorMessage = SyncConfig.toFriendlyException(se).message) }
                        }
                    }
                }
                true
            }
        }
        if (!shouldLaunch) {
            serviceScope.launch { refreshInbox() }
        }
    }

    fun stopForegroundInboxWatch() {
        val job = synchronized(stateLock) { inboxWatchJob.also { inboxWatchJob = null } }
        job?.cancel()
    }

    suspend fun refreshInbox(): Result<List<SyncInboxItemUi>> = withContext(Dispatchers.IO) {
        val epoch = lockEpoch.get()
        if (vaultRepository.state.value.lockState.locked) {
            return@withContext Result.failure(SecurityException("保险库已锁定"))
        }
        _uiState.update { it.copy(isRefreshingInbox = true) }
        try {
            runCatching {
                val holder = getOrCreateClientHolder()
                val items = holder.client.listInbox(SyncConfig.APP_ID)
                    .map(SyncParcelCodec::toInboxItemUi)
                    .sortedByDescending { it.createdAt }
                if (lockEpoch.get() == epoch && !vaultRepository.state.value.lockState.locked) {
                    _uiState.update { it.copy(inboxItems = items) }
                }
                items
            }.recoverCatching { e -> throw SyncConfig.toFriendlyException(e) }
        } finally {
            if (lockEpoch.get() == epoch) {
                _uiState.update { it.copy(isRefreshingInbox = false) }
            }
        }
    }

    suspend fun sendToPairedDevice(deviceId: String, deliveryMode: String = "auto"): Result<Unit> =
        withContext(Dispatchers.IO) {
            val epoch = lockEpoch.get()
            val myGen = beginNewFlowLocked()
            val callerJob = currentCoroutineContext()[Job]
            synchronized(stateLock) { activeFlowJob = callerJob }
            runCatching {
                requireUnlocked("推送同步配置")
                val mode = deliveryMode.trim().lowercase().ifEmpty { DeliveryMode.AUTO }
                val holder = getOrCreateClientHolder()
                val parcel = SyncParcelCodec.encodeVaultParcel(vaultRepository.exportSyncBundle(holder.deviceLabel))

                updateFlowStateIfCurrent(epoch, myGen) { cur ->
                    cur.copy(
                        flowStage = if (mode == DeliveryMode.PIPE) SyncFlowStage.WAITING_PEER else SyncFlowStage.TRANSFERRING,
                        flowPurpose = "direct",
                        flowRole = "sender",
                        flowMode = 0,
                        pairUri = "",
                        roomCode = "",
                        expiresAtEpochSec = 0L,
                        bytesDone = 0L,
                        bytesTotal = 0L,
                        suggestPipeDeviceId = null,
                        errorMessage = null,
                        statusMessage = if (mode == DeliveryMode.PIPE) "正在准备实时直传..." else "正在推送..."
                    )
                }

                val sendRes = holder.client.sendToDevice(
                    targetDeviceId = deviceId,
                    parcel = parcel,
                    options = SendOptions(
                        deliveryMode = mode,
                        includeRelay = holder.isCustom,
                        onOfferReady = { offer ->
                            updateFlowStateIfCurrent(epoch, myGen) { cur ->
                                cur.copy(
                                    flowStage = SyncFlowStage.WAITING_PEER,
                                    flowPurpose = "direct",
                                    flowRole = "sender",
                                    flowMode = offer.mode,
                                    pairUri = offer.pairUri,
                                    roomCode = offer.displayCode,
                                    expiresAtEpochSec = offer.expiresAt,
                                    statusMessage = "请在对端「接收」页扫码或输入房间码"
                                )
                            }
                        },
                        onProgress = { ev -> onTransferProgress(epoch, myGen, ev) }
                    )
                )

                updateFlowStateIfCurrent(epoch, myGen) { cur ->
                    cur.copy(
                        flowStage = SyncFlowStage.COMPLETED,
                        pairUri = "",
                        roomCode = "",
                        expiresAtEpochSec = 0L,
                        suggestPipeDeviceId = null,
                        errorMessage = null,
                        statusMessage = if (sendRes.mode == DeliveryMode.PIPE) "实时直传完成" else "已推送至对端"
                    )
                }
            }.recoverCatching { e ->
                if (e is CancellationException) throw e
                val se = e as? SyncError
                val friendly = SyncConfig.toFriendlyException(e)
                updateFlowStateIfCurrent(epoch, myGen) { cur ->
                    cur.copy(
                        flowStage = SyncFlowStage.ERROR,
                        pairUri = "",
                        roomCode = "",
                        suggestPipeDeviceId = if (se?.suggestPipeMode == true) deviceId else null,
                        errorMessage = friendly.message,
                        statusMessage = ""
                    )
                }
                throw friendly
            }
        }

    suspend fun applyInboxItem(itemId: String, importMode: String): Result<SyncImportSummary> =
        applyInboxItemInternal(itemId, importMode)

    suspend fun dismissInboxItem(itemId: String): Result<Unit> =
        dismissInboxItemInternal(itemId)

    suspend fun revokePairedDevice(deviceId: String): Result<Unit> =
        revokePairedDeviceInternal(deviceId)

    fun startDeviceBindOffer(mode: Int, includeRelay: Boolean) =
        launchDeviceBindOffer(mode, includeRelay)

    fun acceptDeviceBind(codeOrUri: String) =
        launchAcceptDeviceBind(codeOrUri)

    fun startOneShotSend(mode: Int, includeRelay: Boolean) =
        launchOneShotSend(mode, includeRelay)

    fun startReceive(codeOrUri: String) =
        launchStartReceive(codeOrUri)

    fun submitSasInput(sasDigits: String) {
        val clean = sasDigits.filter { it.isDigit() }
        synchronized(stateLock) { sasInputDeferred }?.complete(clean)
    }

    fun confirmResponderSas(approved: Boolean) {
        synchronized(stateLock) { sasConfirmDeferred }?.complete(approved)
    }

    fun cancelActiveFlow() {
        val toCancel = synchronized(stateLock) {
            flowGeneration.incrementAndGet()
            Triple(sasInputDeferred, sasConfirmDeferred, activeFlowJob).also {
                sasInputDeferred = null
                sasConfirmDeferred = null
                activeFlowJob = null
            }
        }
        toCancel.first?.cancel(CancellationException("用户取消了安全码输入"))
        toCancel.second?.cancel(CancellationException("用户取消了安全码核验"))
        toCancel.third?.cancel(CancellationException("用户取消了同步操作"))

        _uiState.update { cur ->
            cur.copy(
                flowStage = SyncFlowStage.IDLE,
                flowPurpose = "",
                flowRole = "",
                pairUri = "",
                roomCode = "",
                expiresAtEpochSec = 0L,
                sasCode = "",
                sasAttemptsRemaining = 3,
                bytesDone = 0L,
                bytesTotal = 0L,
                suggestPipeDeviceId = null,
                errorMessage = null,
                statusMessage = ""
            )
        }
    }

    suspend fun applyPendingPreview(importMode: String): Result<SyncImportSummary> = withContext(Dispatchers.IO) {
        val epoch = lockEpoch.get()
        runCatching {
            requireUnlocked("导入同步配置")
            val parsedMode = SyncImportMode.fromWire(importMode)
            val pair = synchronized(stateLock) { pendingPreviewBundle }
                ?: throw IllegalStateException("同步预览不存在或已过期，请重新接收")

            val summary = vaultRepository.importSyncBundle(pair.second, parsedMode)
            synchronized(stateLock) {
                if (pendingPreviewBundle?.first?.previewId == pair.first.previewId) {
                    pendingPreviewBundle = null
                }
            }
            if (lockEpoch.get() == epoch && !vaultRepository.state.value.lockState.locked) {
                _uiState.update { cur ->
                    cur.copy(
                        flowStage = SyncFlowStage.COMPLETED,
                        pendingPreview = null,
                        errorMessage = null,
                        statusMessage = "配置导入完成"
                    )
                }
            }
            summary
        }.recoverCatching { e ->
            val friendly = SyncConfig.toFriendlyException(e)
            _uiState.update { it.copy(errorMessage = friendly.message) }
            throw friendly
        }
    }

    fun clearPendingPreview() {
        synchronized(stateLock) { pendingPreviewBundle = null }
        _uiState.update { cur ->
            cur.copy(
                pendingPreview = null,
                flowStage = if (cur.flowStage == SyncFlowStage.READY_PREVIEW) SyncFlowStage.IDLE else cur.flowStage,
                statusMessage = if (cur.flowStage == SyncFlowStage.READY_PREVIEW) "" else cur.statusMessage,
                errorMessage = null
            )
        }
    }

    fun onVaultLocked() {
        var oldHolder: CachedClientHolder? = null
        var watchJob: Job? = null
        var flowJob: Job? = null
        var sasIn: CompletableDeferred<String>? = null
        var sasConf: CompletableDeferred<Boolean>? = null

        synchronized(stateLock) {
            lockEpoch.incrementAndGet()
            flowGeneration.incrementAndGet()
            oldHolder = activeClientHolder; activeClientHolder = null
            watchJob = inboxWatchJob; inboxWatchJob = null
            flowJob = activeFlowJob; activeFlowJob = null
            sasIn = sasInputDeferred; sasInputDeferred = null
            sasConf = sasConfirmDeferred; sasConfirmDeferred = null
            pendingPreviewBundle = null
        }

        sasIn?.cancel(CancellationException("保险库已锁定"))
        sasConf?.cancel(CancellationException("保险库已锁定"))
        watchJob?.cancel(CancellationException("保险库已锁定"))
        flowJob?.cancel(CancellationException("保险库已锁定"))
        serviceJob.cancelChildren(CancellationException("保险库已锁定"))
        keyStore.clearVolatileCaches()
        _uiState.value = SyncUiState()

        val tempDir = runCatching { cacheBaseDirProvider() }.getOrNull()
        val isMain = runCatching { Looper.getMainLooper()?.thread === Thread.currentThread() }.getOrDefault(false)
        if (isMain) {
            cleanupScope.launch {
                runCatching { oldHolder?.client?.close() }
                runCatching { tempDir?.deleteRecursively() }
            }
        } else {
            runCatching { oldHolder?.client?.close() }
            runCatching { tempDir?.deleteRecursively() }
        }
    }

    internal suspend fun probeRelayPing(profile: RelayProfile) =
        SyncClient(
            SyncClientConfig(
                relay = profile,
                keyStore = MemoryKeyStore(),
                appId = SyncConfig.APP_ID,
                deviceLabel = "PingProbe",
                platform = "android",
                tempDir = cacheBaseDirProvider()
            )
        ).use { it.ping() }

    internal fun onRelayCertExpiring(ev: RelayCertExpiringEvent) {
        val certIso = if (ev.expiresAt > 0L) {
            runCatching { Instant.ofEpochSecond(ev.expiresAt).toString() }.getOrDefault("")
        } else {
            ""
        }
        synchronized(stateLock) { lastCertMetaByRelayId = ev.relayId to (ev.expiresAt to true) }
        _uiState.update { cur ->
            if (cur.relay.relayId == ev.relayId) {
                cur.copy(relay = cur.relay.copy(certExpiresAt = certIso, certRotateRecommended = true))
            } else {
                cur
            }
        }
    }

    companion object {
        internal const val TEMP_DIR_NAME = "synctool-tmp"

        internal fun resolveAndroidDeviceLabel(): String {
            val mfg = Build.MANUFACTURER.orEmpty().trim()
            val model = Build.MODEL.orEmpty().trim()
            return listOf(mfg, model).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "Android" }.take(64)
        }
    }
}
