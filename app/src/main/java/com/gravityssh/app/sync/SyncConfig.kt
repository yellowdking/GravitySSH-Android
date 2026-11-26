package com.gravityssh.app.sync

object SyncConfig {
    const val DEFAULT_RELAY_URL = "https://107.172.98.169:54843"
    const val CLIENT_HEADER = "GravitySSH/1"
    const val DEFAULT_TTL_SECONDS = 300
    const val PBKDF2_ITERATIONS = 200_000
    const val CHANNEL_PREFIX = "gssh-sync-channel-v1:"
    const val QR_SCHEME_PREFIX = "gssh-sync://v1?code="

    // Live credentials from relay-ops deployment on 107.172.98.169:54843
    const val PINNED_SPKI_SHA256 = "8d02a67e23e9f5af5922a8e700a6309dfa4ce335baca7125452fe22f4e5e29ba"
    const val APP_GATE_KEY = "fb6f39e339dffe1141537e5e8154491cce0044c7bc2ecb26b17828571b9d5aae"
}
