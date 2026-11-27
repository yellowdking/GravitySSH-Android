package com.gravityssh.app

import android.app.Application
import android.content.Context
import android.util.Log
import com.gravityssh.app.ssh.KeyDeployer
import com.gravityssh.app.ssh.SftpService
import com.gravityssh.app.ssh.SshConnector
import com.gravityssh.app.ssh.SshSessionManager
import com.gravityssh.app.sync.SyncRelayClient
import com.gravityssh.app.sync.SyncService
import com.gravityssh.app.terminal.UpdateChecker
import com.gravityssh.app.vault.KnownHostsManager
import com.gravityssh.app.vault.VaultCrypto
import com.gravityssh.app.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security
import java.util.concurrent.atomic.AtomicBoolean

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val configDir = File(appContext.filesDir, "config")
    private val initScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val vaultRepository = VaultRepository(File(configDir, "vault.gsv"), VaultCrypto())
    val knownHostsManager = KnownHostsManager(File(configDir, "known_hosts.json"), autoLoad = false)
    val sshConnector = SshConnector(knownHostsManager)
    val keyDeployer = KeyDeployer(sshConnector)
    val sshSessionManager = SshSessionManager(appContext, vaultRepository, sshConnector)
    val sftpService = SftpService(sshSessionManager)
    val syncRelayClient = SyncRelayClient()
    val syncService = SyncService(vaultRepository, syncRelayClient)
    val updateChecker = UpdateChecker(BuildConfig.APP_VERSION)

    init {
        initScope.launch {
            runCatching { vaultRepository.open() }
                .onFailure { e -> runCatching { Log.e("AppContainer", "Unexpected vault open failure", e) } }
        }
        initScope.launch {
            runCatching { knownHostsManager.load() }
                .onFailure { e -> runCatching { Log.e("AppContainer", "Unexpected known_hosts load failure", e) } }
        }
    }
}

class GravitySshApp : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { ensureBouncyCastleRegistered() }
        container = AppContainer(this)
    }

    companion object {
        private val bcRegistered = AtomicBoolean(false)
        private val bcLock = Any()

        fun ensureBouncyCastleRegistered() {
            if (bcRegistered.get()) return
            synchronized(bcLock) {
                if (bcRegistered.get()) return
                runCatching {
                    val provider = BouncyCastleProvider()
                    Security.removeProvider("BC")
                    Security.addProvider(provider)
                    bcRegistered.set(true)
                }.onFailure { e ->
                    runCatching { Log.e("GravitySshApp", "Failed to register BouncyCastle provider", e) }
                }
            }
        }

        fun from(context: Context): AppContainer =
            (context.applicationContext as GravitySshApp).container
    }
}
