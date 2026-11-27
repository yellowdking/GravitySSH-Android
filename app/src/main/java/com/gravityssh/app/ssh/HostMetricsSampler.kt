package com.gravityssh.app.ssh

import com.gravityssh.app.model.HostMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.ConnectionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

data class CpuSample(val total: Long, val idle: Long)
data class NetSample(val rx: Long, val tx: Long)

class HostMetricsSampler(private val sessionId: String) {
    private val inFlight = AtomicBoolean(false)
    @Volatile private var prevCpu: CpuSample? = null
    @Volatile private var prevNet: NetSample? = null
    @Volatile private var prevSampleMs: Long = 0L
    @Volatile private var consecutiveFailures = 0
    @Volatile private var cachedCores: Int? = null
    @Volatile private var gpuAvailable: Boolean? = null

    fun reset() {
        prevCpu = null
        prevNet = null
        prevSampleMs = 0L
        consecutiveFailures = 0
        cachedCores = null
        gpuAvailable = null
    }

    fun recommendedDelayMs(): Long = when {
        consecutiveFailures == 0 -> 4000L
        consecutiveFailures == 1 -> 8000L
        else -> 15000L
    }

    suspend fun sample(client: SSHClient): HostMetrics? = withContext(Dispatchers.IO) {
        if (!inFlight.compareAndSet(false, true)) return@withContext null
        try {
            val pingRtt = measureSingleRttMs(client)
            val needCores = cachedCores == null
            val probeGpu = gpuAvailable
            val cmd = buildMetricsCmd(needCores = needCores, gpuState = probeGpu)
            val execStartNs = System.nanoTime()
            val res = SshExecHelper.execCommand(client, cmd, timeoutSec = 8L, maxOutputBytes = 64 * 1024)
            val rtt = pingRtt ?: ((System.nanoTime() - execStartNs) / 1_000_000L).coerceAtLeast(1L)
            val nowMs = System.currentTimeMillis()
            val parsed = HostMetricsParser.parseDetailed(
                sessionId = sessionId,
                raw = res.stdout,
                latencyMs = rtt,
                prevCpu = prevCpu,
                prevNet = prevNet,
                prevSampleMs = prevSampleMs,
                nowMs = nowMs,
                fallbackCores = cachedCores ?: 1
            )
            if (needCores && parsed.parsedCores > 0) cachedCores = parsed.parsedCores
            if (probeGpu == null && parsed.metrics.supported) gpuAvailable = parsed.metrics.hasGpu
            if (parsed.cpu != null) prevCpu = parsed.cpu
            if (parsed.net != null) prevNet = parsed.net
            prevSampleMs = nowMs
            consecutiveFailures = 0
            parsed.metrics
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            consecutiveFailures++
            null
        } finally {
            inFlight.set(false)
        }
    }

    private fun measureSingleRttMs(client: SSHClient): Long? {
        val startNs = System.nanoTime()
        return try {
            val promise = client.connection.sendGlobalRequest("keepalive@openssh.com", true, ByteArray(0))
            try {
                promise.retrieve(RTT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: ConnectionException) {
                if (e.cause is TimeoutException || !client.isConnected) return null
                // OpenSSH replies SSH_MSG_REQUEST_FAILURE to keepalive@openssh.com, which proves round-trip completion
            }
            ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val RTT_TIMEOUT_MS = 5_000L

        private fun buildMetricsCmd(needCores: Boolean, gpuState: Boolean?): String {
            val coresSuffix = if (needCores) "; nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo" else ""
            val gpuLine = when (gpuState) {
                null -> "echo \"---GPU---\"; command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi --query-gpu=utilization.gpu,name --format=csv,noheader,nounits 2>/dev/null | head -n1 || true\n"
                true -> "echo \"---GPU---\"; nvidia-smi --query-gpu=utilization.gpu,name --format=csv,noheader,nounits 2>/dev/null | head -n1 || true\n"
                false -> ""
            }
            return """
                LC_ALL=C sh -c '
                [ -r /proc/stat ] || { echo "UNSUPPORTED"; exit 0; }
                echo "---CPU---"; head -n1 /proc/stat
                echo "---MEM---"; awk "/^(MemTotal|MemAvailable|MemFree|Buffers|Cached):/{print \$1,\$2}" /proc/meminfo
                echo "---DISK---"; (df -Pkl 2>/dev/null || df -Pk 2>/dev/null) | awk "NR>1 && \$1 ~ /^\// && \$1 !~ /^\/dev\/loop/ {pct=\$5; m=\$6; for(i=7;i<=NF;i++) m=m \" \" \${'$'}i; if(m !~ /^\/snap(\/|\$)/) print pct, m}"
                echo "---NET---"; awk "NR>2 && \$1 !~ /^lo:/ {gsub(/:/,\" \",\$0); rx+=\$2; tx+=\$10} END{printf \"%.0f %.0f\n\",rx,tx}" /proc/net/dev
                echo "---LOAD---"; cat /proc/loadavg$coresSuffix
                $gpuLine'
            """.trimIndent()
        }

        fun parseOutput(
            sessionId: String,
            raw: String,
            latencyMs: Long,
            prevCpu: CpuSample?,
            prevNet: NetSample?,
            prevSampleMs: Long,
            nowMs: Long
        ): Triple<HostMetrics, CpuSample?, NetSample?> =
            HostMetricsParser.parse(sessionId, raw, latencyMs, prevCpu, prevNet, prevSampleMs, nowMs)
    }
}
