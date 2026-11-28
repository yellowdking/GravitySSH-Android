package com.gravityssh.app.sync

import com.gravityssh.app.model.SyncFlowStage
import com.gravityssh.app.model.SyncImportMode
import com.gravityssh.app.model.SyncImportSummary
import com.gravityssh.app.model.SyncPairedDeviceUi
import com.gravityssh.app.model.SyncReceivePreview
import com.gravityssh.app.model.SyncUiState
import com.yellowdking.synctool.client.PairOptions
import com.yellowdking.synctool.client.ReceiveOptions
import com.yellowdking.synctool.client.SyncClient
import com.yellowdking.synctool.client.SyncClientConfig
import com.yellowdking.synctool.crypto.Handshake
import com.yellowdking.synctool.envelope.SyncUri
import com.yellowdking.synctool.envelope.TransferEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal suspend fun SyncService.applyInboxItemInternal(
    itemId: String,
    importMode: String
): Result<SyncImportSummary> = withContext(Dispatchers.IO) {
    val epoch = lockEpoch.get()
    runCatching {
        requireUnlocked("导入收件箱配置")
        val parsedMode = SyncImportMode.fromWire(importMode)
        val cleanItemId = itemId.trim()
        require(cleanItemId.isNotEmpty()) { "收件箱项目 ID 不能为空" }

        _uiState.update { cur ->
            cur.copy(
                pendingInboxItemId = cleanItemId,
                bytesDone = 0L,
                bytesTotal = 0L,
                errorMessage = null,
                statusMessage = "正在拉取并校验加密配置包..."
            )
        }

        val holder = getOrCreateClientHolder()
        val received = holder.client.fetchParcel(
            itemId = cleanItemId,
            appId = SyncConfig.APP_ID,
            autoAck = false,
            onProgress = { ev ->
                if (lockEpoch.get() == epoch && !vaultRepository.state.value.lockState.locked) {
                    _uiState.update { cur ->
                        cur.copy(bytesDone = ev.bytesDone, bytesTotal = ev.bytesTotal)
                    }
                }
            }
        )

        if (lockEpoch.get() != epoch || vaultRepository.state.value.lockState.locked) {
            for (b in received.blobs) runCatching { b.safeFile.delete() }
            throw SecurityException("保险库已锁定，已中止导入")
        }

        val bundle = SyncParcelCodec.decodeVaultParcel(received)
        val summary = vaultRepository.importSyncBundle(bundle, parsedMode)

        // Physically burn spooled ciphertext on relay strictly AFTER local vault import succeeds
        runCatching { holder.client.ackItem(cleanItemId) }

        if (lockEpoch.get() == epoch && !vaultRepository.state.value.lockState.locked) {
            _uiState.update { cur ->
                cur.copy(
                    inboxItems = cur.inboxItems.filterNot { it.itemId == cleanItemId },
                    pendingInboxItemId = null,
                    bytesDone = 0L,
                    bytesTotal = 0L,
                    errorMessage = null,
                    statusMessage = "已成功导入并销毁云端密文"
                )
            }
        }
        summary
    }.recoverCatching { e ->
        if (e is CancellationException) throw e
        val friendly = SyncConfig.toFriendlyException(e)
        if (lockEpoch.get() == epoch) {
            _uiState.update { cur ->
                cur.copy(
                    pendingInboxItemId = null,
                    errorMessage = friendly.message,
                    statusMessage = ""
                )
            }
        }
        throw friendly
    }
}

internal suspend fun SyncService.dismissInboxItemInternal(itemId: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        val epoch = lockEpoch.get()
        runCatching {
            requireUnlocked("销毁收件箱项目")
            val cleanItemId = itemId.trim()
            require(cleanItemId.isNotEmpty()) { "收件箱项目 ID 不能为空" }
            val holder = getOrCreateClientHolder()
            holder.client.ackItem(cleanItemId)
            if (lockEpoch.get() == epoch && !vaultRepository.state.value.lockState.locked) {
                _uiState.update { cur ->
                    cur.copy(
                        inboxItems = cur.inboxItems.filterNot { it.itemId == cleanItemId },
                        pendingInboxItemId = cur.pendingInboxItemId?.takeUnless { it == cleanItemId },
                        errorMessage = null,
                        statusMessage = "已销毁该待收配置"
                    )
                }
            }
        }.recoverCatching { e ->
            val friendly = SyncConfig.toFriendlyException(e)
            _uiState.update { it.copy(errorMessage = friendly.message) }
            throw friendly
        }
    }

internal suspend fun SyncService.revokePairedDeviceInternal(deviceId: String): Result<Unit> =
    withContext(Dispatchers.IO) {
        val epoch = lockEpoch.get()
        runCatching {
            requireUnlocked("吊销配对设备")
            val cleanId = deviceId.trim().lowercase()
            require(cleanId.isNotEmpty()) { "设备 ID 不能为空" }
            val holder = getOrCreateClientHolder()
            try {
                holder.client.revokeDevice(cleanId)
            } finally {
                if (lockEpoch.get() == epoch && !vaultRepository.state.value.lockState.locked) {
                    val remaining = runCatching {
                        holder.client.keyStore.listDevices().map { card ->
                            SyncPairedDeviceUi(card.deviceId, card.label, card.platform, card.relayId, card.issuedAt)
                        }
                    }.getOrNull()
                    if (remaining != null) {
                        _uiState.update { it.copy(pairedDevices = remaining) }
                    }
                }
            }
            if (lockEpoch.get() == epoch && !vaultRepository.state.value.lockState.locked) {
                _uiState.update { cur ->
                    cur.copy(
                        suggestPipeDeviceId = cur.suggestPipeDeviceId?.takeUnless { it.lowercase() == cleanId },
                        errorMessage = null,
                        statusMessage = "已吊销该配对设备"
                    )
                }
            }
        }.recoverCatching { e ->
            val friendly = SyncConfig.toFriendlyException(e)
            _uiState.update { it.copy(errorMessage = friendly.message) }
            throw friendly
        }
    }

internal fun SyncService.launchDeviceBindOffer(mode: Int, includeRelay: Boolean) {
    if (failIfLocked()) return
    val effectiveMode = if (mode == Handshake.MODE_COMMIT_SAS) Handshake.MODE_COMMIT_SAS else Handshake.MODE_QR_OOB
    val epoch = lockEpoch.get()
    val myGen = beginNewFlowLocked()

    val job = serviceScope.launch {
        runCatching {
            val holder = getOrCreateClientHolder()
            updateFlowStateIfCurrent(epoch, myGen) { cur ->
                cur.copy(
                    flowStage = SyncFlowStage.WAITING_PEER,
                    flowPurpose = Handshake.PURPOSE_PAIR,
                    flowRole = "initiator",
                    flowMode = effectiveMode,
                    pairUri = "",
                    roomCode = "",
                    expiresAtEpochSec = 0L,
                    sasCode = "",
                    sasAttemptsRemaining = 3,
                    errorMessage = null,
                    statusMessage = "正在创建设备绑定房间..."
                )
            }

            var sasAttemptIdx = 0
            val pairedCard = holder.client.initiateDeviceBind(
                PairOptions(
                    mode = effectiveMode,
                    label = holder.deviceLabel,
                    includeRelay = includeRelay && holder.isCustom,
                    ttlSec = SyncConfig.DEFAULT_ROOM_TTL_SEC,
                    onOfferReady = { offer ->
                        updateFlowStateIfCurrent(epoch, myGen) { cur ->
                            cur.copy(
                                flowStage = SyncFlowStage.WAITING_PEER,
                                pairUri = offer.pairUri,
                                roomCode = offer.displayCode,
                                expiresAtEpochSec = offer.expiresAt,
                                statusMessage = if (effectiveMode == Handshake.MODE_QR_OOB) {
                                    "请使用待绑定设备扫描二维码"
                                } else {
                                    "请在待绑定设备输入 8 位房间码"
                                }
                            )
                        }
                    },
                    onSASInput = {
                        val remaining = (3 - sasAttemptIdx).coerceAtLeast(1)
                        val retryErr = if (sasAttemptIdx > 0) "安全码不匹配，请重新核对（剩余 $remaining 次机会）" else null
                        sasAttemptIdx++
                        awaitInitiatorSasInput(epoch, myGen, remaining, retryErr)
                    }
                )
            )

            refreshOverviewInternal()
            updateFlowStateIfCurrent(epoch, myGen) { cur ->
                cur.copy(
                    flowStage = SyncFlowStage.COMPLETED,
                    pairUri = "",
                    roomCode = "",
                    expiresAtEpochSec = 0L,
                    sasCode = "",
                    errorMessage = null,
                    statusMessage = "已成功绑定设备：${pairedCard.label}"
                )
            }
        }.onFailure { e -> handleAsyncFlowFailure(epoch, myGen, e) }
    }
    registerActiveFlowJob(myGen, job)
}

internal fun SyncService.launchAcceptDeviceBind(codeOrUri: String) {
    if (failIfLocked()) return
    val trimmed = codeOrUri.trim()
    if (trimmed.isEmpty()) {
        _uiState.update { it.copy(flowStage = SyncFlowStage.ERROR, errorMessage = "请输入 8 位房间码或扫描配对二维码") }
        return
    }
    val epoch = lockEpoch.get()
    val myGen = beginNewFlowLocked()

    val job = serviceScope.launch {
        runCatching {
            adoptRelayFromPairUriIfPresent(trimmed)
            val holder = getOrCreateClientHolder()
            val detectedMode = detectPairMode(trimmed)

            updateFlowStateIfCurrent(epoch, myGen) { cur ->
                cur.copy(
                    flowStage = SyncFlowStage.WAITING_PEER,
                    flowPurpose = Handshake.PURPOSE_PAIR,
                    flowRole = "responder",
                    flowMode = detectedMode,
                    pairUri = "",
                    roomCode = "",
                    sasCode = "",
                    errorMessage = null,
                    statusMessage = "正在加入配对房间并执行密钥协商..."
                )
            }

            val pairedCard = holder.client.acceptDeviceBind(
                pairInput = trimmed,
                options = PairOptions(
                    label = holder.deviceLabel,
                    onSASConfirm = { sas -> awaitResponderSasConfirmation(epoch, myGen, sas) }
                )
            )

            refreshOverviewInternal()
            updateFlowStateIfCurrent(epoch, myGen) { cur ->
                cur.copy(
                    flowStage = SyncFlowStage.COMPLETED,
                    sasCode = "",
                    errorMessage = null,
                    statusMessage = "已成功绑定设备：${pairedCard.label}"
                )
            }
        }.onFailure { e -> handleAsyncFlowFailure(epoch, myGen, e) }
    }
    registerActiveFlowJob(myGen, job)
}

internal fun SyncService.launchOneShotSend(mode: Int, includeRelay: Boolean) {
    if (failIfLocked()) return
    val effectiveMode = if (mode == Handshake.MODE_COMMIT_SAS) Handshake.MODE_COMMIT_SAS else Handshake.MODE_QR_OOB
    val epoch = lockEpoch.get()
    val myGen = beginNewFlowLocked()

    val job = serviceScope.launch {
        runCatching {
            val holder = getOrCreateClientHolder()
            val parcel = SyncParcelCodec.encodeVaultParcel(vaultRepository.exportSyncBundle(holder.deviceLabel))

            updateFlowStateIfCurrent(epoch, myGen) { cur ->
                cur.copy(
                    flowStage = SyncFlowStage.WAITING_PEER,
                    flowPurpose = Handshake.PURPOSE_ONESHOT,
                    flowRole = "sender",
                    flowMode = effectiveMode,
                    pairUri = "",
                    roomCode = "",
                    expiresAtEpochSec = 0L,
                    sasCode = "",
                    sasAttemptsRemaining = 3,
                    bytesDone = 0L,
                    bytesTotal = 0L,
                    errorMessage = null,
                    statusMessage = "正在创建一次性加密传输房间..."
                )
            }

            var sasAttemptIdx = 0
            holder.client.startOneShotSend(
                parcel = parcel,
                options = PairOptions(
                    mode = effectiveMode,
                    label = holder.deviceLabel,
                    includeRelay = includeRelay && holder.isCustom,
                    ttlSec = SyncConfig.DEFAULT_ROOM_TTL_SEC,
                    onOfferReady = { offer ->
                        updateFlowStateIfCurrent(epoch, myGen) { cur ->
                            cur.copy(
                                flowStage = SyncFlowStage.WAITING_PEER,
                                pairUri = offer.pairUri,
                                roomCode = offer.displayCode,
                                expiresAtEpochSec = offer.expiresAt,
                                statusMessage = if (effectiveMode == Handshake.MODE_QR_OOB) {
                                    "请使用接收端扫描二维码立即接收"
                                } else {
                                    "请在接收端输入 8 位房间码"
                                }
                            )
                        }
                    },
                    onSASInput = {
                        val remaining = (3 - sasAttemptIdx).coerceAtLeast(1)
                        val retryErr = if (sasAttemptIdx > 0) "安全码不匹配，请重新核对（剩余 $remaining 次机会）" else null
                        sasAttemptIdx++
                        awaitInitiatorSasInput(epoch, myGen, remaining, retryErr)
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
                    sasCode = "",
                    errorMessage = null,
                    statusMessage = "配置已端到端加密传输完成"
                )
            }
        }.onFailure { e -> handleAsyncFlowFailure(epoch, myGen, e) }
    }
    registerActiveFlowJob(myGen, job)
}

internal fun SyncService.launchStartReceive(codeOrUri: String) {
    if (failIfLocked()) return
    val trimmed = codeOrUri.trim()
    if (trimmed.isEmpty()) {
        _uiState.update { it.copy(flowStage = SyncFlowStage.ERROR, errorMessage = "请扫描配对二维码或输入 8 位房间码") }
        return
    }
    val epoch = lockEpoch.get()
    val myGen = beginNewFlowLocked()

    val job = serviceScope.launch {
        runCatching {
            adoptRelayFromPairUriIfPresent(trimmed)
            val holder = getOrCreateClientHolder()
            val detectedMode = detectPairMode(trimmed)

            updateFlowStateIfCurrent(epoch, myGen) { cur ->
                cur.copy(
                    flowStage = SyncFlowStage.WAITING_PEER,
                    flowPurpose = Handshake.PURPOSE_ONESHOT,
                    flowRole = "receiver",
                    flowMode = detectedMode,
                    pairUri = "",
                    roomCode = "",
                    sasCode = "",
                    bytesDone = 0L,
                    bytesTotal = 0L,
                    pendingPreview = null,
                    errorMessage = null,
                    statusMessage = "正在加入房间并建立端到端加密通道..."
                )
            }

            val received = holder.client.receiveFromRoom(
                pairInput = trimmed,
                options = ReceiveOptions(
                    appId = SyncConfig.APP_ID,
                    onSASConfirm = { sas -> awaitResponderSasConfirmation(epoch, myGen, sas) },
                    onProgress = { ev -> onTransferProgress(epoch, myGen, ev) }
                )
            )

            if (lockEpoch.get() != epoch || vaultRepository.state.value.lockState.locked) {
                for (b in received.blobs) runCatching { b.safeFile.delete() }
                throw SecurityException("保险库已锁定，已中止接收")
            }

            val bundle = SyncParcelCodec.decodeVaultParcel(received)
            val preview = SyncReceivePreview(
                previewId = UUID.randomUUID().toString(),
                sourcePlatform = bundle.sourcePlatform,
                sourceDevice = bundle.sourceDevice,
                exportedAt = bundle.exportedAt,
                serverCount = bundle.servers.size,
                keyCount = bundle.sshKeys.size,
                shortcutCount = bundle.shortcuts.size,
                templateCount = bundle.templates.size
            )
            synchronized(stateLock) {
                if (lockEpoch.get() != epoch || flowGeneration.get() != myGen) {
                    throw CancellationException("接收会话已取消")
                }
                pendingPreviewBundle = preview to bundle
            }

            updateFlowStateIfCurrent(epoch, myGen) { cur ->
                cur.copy(
                    flowStage = SyncFlowStage.READY_PREVIEW,
                    sasCode = "",
                    pendingPreview = preview,
                    errorMessage = null,
                    statusMessage = "已解密并校验配置，请选择合并或全量覆盖"
                )
            }
        }.onFailure { e -> handleAsyncFlowFailure(epoch, myGen, e) }
    }
    registerActiveFlowJob(myGen, job)
}

internal fun SyncService.requireUnlocked(actionDesc: String) {
    if (vaultRepository.state.value.lockState.locked) {
        throw SecurityException("保险库已锁定，请先解锁后再$actionDesc")
    }
}

internal fun SyncService.failIfLocked(): Boolean {
    if (!vaultRepository.state.value.lockState.locked) return false
    _uiState.update { it.copy(flowStage = SyncFlowStage.ERROR, errorMessage = "保险库已锁定，请先解锁") }
    return true
}

internal fun SyncService.beginNewFlowLocked(): Long {
    val prev = synchronized(stateLock) {
        val myGen = flowGeneration.incrementAndGet()
        val old = Triple(sasInputDeferred, sasConfirmDeferred, activeFlowJob)
        sasInputDeferred = null
        sasConfirmDeferred = null
        activeFlowJob = null
        myGen to old
    }
    prev.second.first?.cancel(CancellationException("已被新同步会话替换"))
    prev.second.second?.cancel(CancellationException("已被新同步会话替换"))
    prev.second.third?.cancel(CancellationException("已被新同步会话替换"))
    return prev.first
}

internal fun SyncService.registerActiveFlowJob(gen: Long, job: Job) {
    val cancelImmediately = synchronized(stateLock) {
        if (flowGeneration.get() == gen && !vaultRepository.state.value.lockState.locked) {
            activeFlowJob = job
            false
        } else {
            true
        }
    }
    if (cancelImmediately) {
        job.cancel(CancellationException("同步会话已取消"))
    }
}

internal suspend fun SyncService.awaitInitiatorSasInput(
    epoch: Long,
    myGen: Long,
    attemptsRemaining: Int,
    retryError: String?
): String {
    val deferred = CompletableDeferred<String>()
    synchronized(stateLock) {
        if (lockEpoch.get() != epoch || flowGeneration.get() != myGen) {
            throw CancellationException("同步会话已取消")
        }
        sasInputDeferred = deferred
    }
    updateFlowStateIfCurrent(epoch, myGen) { cur ->
        cur.copy(
            flowStage = SyncFlowStage.AWAITING_SAS_INPUT,
            sasAttemptsRemaining = attemptsRemaining,
            errorMessage = retryError,
            statusMessage = "请输入接收端屏幕显示的 6 位安全码"
        )
    }
    return try {
        val entered = deferred.await()
        updateFlowStateIfCurrent(epoch, myGen) { cur ->
            cur.copy(
                flowStage = SyncFlowStage.TRANSFERRING,
                errorMessage = null,
                statusMessage = "正在核验安全码并传输加密数据..."
            )
        }
        entered
    } finally {
        synchronized(stateLock) {
            if (sasInputDeferred === deferred) sasInputDeferred = null
        }
    }
}

internal suspend fun SyncService.awaitResponderSasConfirmation(
    epoch: Long,
    myGen: Long,
    sas: String
): Boolean {
    val deferred = CompletableDeferred<Boolean>()
    synchronized(stateLock) {
        if (lockEpoch.get() != epoch || flowGeneration.get() != myGen) {
            throw CancellationException("同步会话已取消")
        }
        sasConfirmDeferred = deferred
    }
    updateFlowStateIfCurrent(epoch, myGen) { cur ->
        cur.copy(
            flowStage = SyncFlowStage.AWAITING_SAS_CONFIRM,
            flowMode = Handshake.MODE_COMMIT_SAS,
            sasCode = sas,
            errorMessage = null,
            statusMessage = "请与发送端当面或语音核对 6 位安全码是否完全一致"
        )
    }
    return try {
        val approved = deferred.await()
        if (approved) {
            updateFlowStateIfCurrent(epoch, myGen) { cur ->
                cur.copy(
                    flowStage = SyncFlowStage.TRANSFERRING,
                    errorMessage = null,
                    statusMessage = "安全码已核验一致，正在等待对端加密数据..."
                )
            }
        }
        approved
    } finally {
        synchronized(stateLock) {
            if (sasConfirmDeferred === deferred) sasConfirmDeferred = null
        }
    }
}

internal fun SyncService.onTransferProgress(epoch: Long, myGen: Long, ev: TransferEvent) {
    updateFlowStateIfCurrent(epoch, myGen) { cur ->
        cur.copy(
            flowStage = SyncFlowStage.TRANSFERRING,
            bytesDone = ev.bytesDone,
            bytesTotal = ev.bytesTotal
        )
    }
}

internal fun SyncService.handleAsyncFlowFailure(epoch: Long, myGen: Long, error: Throwable) {
    if (error is CancellationException) return
    val friendly = SyncConfig.toFriendlyException(error)
    updateFlowStateIfCurrent(epoch, myGen) { cur ->
        cur.copy(
            flowStage = SyncFlowStage.ERROR,
            pairUri = "",
            roomCode = "",
            sasCode = "",
            errorMessage = friendly.message,
            statusMessage = ""
        )
    }
}

internal inline fun SyncService.updateFlowStateIfCurrent(
    epoch: Long,
    myGen: Long,
    crossinline transform: (SyncUiState) -> SyncUiState
) {
    val isCurrent = synchronized(stateLock) {
        lockEpoch.get() == epoch &&
            flowGeneration.get() == myGen &&
            !vaultRepository.state.value.lockState.locked
    }
    if (isCurrent) {
        _uiState.update(transform)
    }
}

internal fun SyncService.adoptRelayFromPairUriIfPresent(codeOrUri: String) {
    val embedded = runCatching { SyncConfig.parseRelayFromPairURI(codeOrUri) }.getOrNull() ?: return
    val formatted = SyncUri.formatRelayUri(embedded)
    val currentCustom = vaultRepository.readSyncState().customRelayUri.trim()
    if (currentCustom != formatted) {
        vaultRepository.updateSyncState { it.copy(customRelayUri = formatted) }
        invalidateClientHolder()
        _uiState.update { it.copy(relay = SyncConfig.toRelayStatusUi(embedded, isCustom = true)) }
    }
}

internal fun SyncService.detectPairMode(codeOrUri: String): Int {
    if (!codeOrUri.startsWith("synctool://", ignoreCase = true)) {
        return Handshake.MODE_COMMIT_SAS
    }
    return runCatching {
        val offer = SyncUri.parsePairUri(codeOrUri)
        val m = offer.mode
        offer.zeroSecrets()
        m
    }.getOrDefault(Handshake.MODE_QR_OOB)
}

internal fun SyncService.invalidateClientHolder() {
    val old = synchronized(stateLock) { activeClientHolder.also { activeClientHolder = null } }
    old?.let { cleanupScope.launch { runCatching { it.client.close() } } }
}

internal fun SyncService.getOrCreateClientHolder(): SyncService.CachedClientHolder {
    val syncSnap = vaultRepository.readSyncState()
    val label = syncSnap.localDeviceLabel.trim().ifBlank { defaultDeviceLabelProvider() }.take(64)
    val (profile, isCustom) = SyncConfig.resolveRelayProfile(syncSnap.customRelayUri, defaultRelayUriOverride)
    val uriKey = SyncUri.formatRelayUri(profile)

    var staleToClose: SyncClient? = null
    val holder = synchronized(stateLock) {
        requireUnlocked("使用同步服务")
        val existing = activeClientHolder
        if (existing != null && existing.relayUriKey == uriKey && existing.deviceLabel == label) {
            existing
        } else {
            staleToClose = existing?.client
            val createdClient = SyncClient(
                SyncClientConfig(
                    relay = profile,
                    keyStore = keyStore,
                    appId = SyncConfig.APP_ID,
                    deviceLabel = label,
                    platform = "android",
                    tempDir = cacheBaseDirProvider(),
                    onCertExpiring = ::onRelayCertExpiring
                )
            )
            SyncService.CachedClientHolder(
                relayUriKey = uriKey,
                deviceLabel = label,
                profile = profile,
                isCustom = isCustom,
                client = createdClient
            ).also { activeClientHolder = it }
        }
    }
    staleToClose?.let { cleanupScope.launch { runCatching { it.close() } } }
    return holder
}
