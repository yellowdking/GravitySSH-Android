package com.gravityssh.app.ssh

import com.gravityssh.app.model.HostMetrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import java.util.concurrent.atomic.AtomicBoolean

data class CpuSample(val total: Long, val idle: Long)
data class NetSample(val rx: Long, val tx: Long)

class HostMetricsSampler(private val sessionId: String) {
    private val inFlight = AtomicBoolean(false)
    @Volatile private var prevCpu: CpuSample? = null
    @Volatile private var prevNet: NetSample? = null
    @Volatile private var prevSampleMs: Long = 0L
    @Volatile private var consecutiveFailures = 0

    fun reset() {
        prevCpu = null
        prevNet = null
        prevSampleMs = 0L
        consecutiveFailures = 0
    }

    fun recommendedDelayMs(): Long = when {
        consecutiveFailures == 0 -> 4000L
        consecutiveFailures == 1 -> 8000L
        else -> 15000L
    }

    suspend fun sample(client: SSHClient): HostMetrics? = withContext(Dispatchers.IO) {
        if (!inFlight.compareAndSet(false, true)) return@withContext null
        try {
            val start = System.currentTimeMillis()
            val res = SshExecHelper.execCommand(client, LINUX_METRICS_CMD, timeoutSec = 8L, maxOutputBytes = 64 * 1024)
            val raw = res.stdout
            val rtt = (System.currentTimeMillis() - start).coerceAtLeast(1)
            val nowMs = System.currentTimeMillis()
            val (metrics, curCpu, curNet) = HostMetricsParser.parse(
                sessionId, raw, rtt, prevCpu, prevNet, prevSampleMs, nowMs
            )
            if (curCpu != null) prevCpu = curCpu
            if (curNet != null) prevNet = curNet
            prevSampleMs = nowMs
            consecutiveFailures = 0
            metrics
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            consecutiveFailures++
            null
        } finally {
            inFlight.set(false)
        }
    }

    companion object {
        private val LINUX_METRICS_CMD = """
            LC_ALL=C sh -c '
            [ -r /proc/stat ] || { echo "UNSUPPORTED"; exit 0; }
            echo "---CPU---"; head -n1 /proc/stat
            echo "---MEM---"; awk "/^(MemTotal|MemAvailable|MemFree|Buffers|Cached):/{print \$1,\$2}" /proc/meminfo
            echo "---DISK---"; (df -Pkl -x squashfs 2>/dev/null || df -Pkl 2>/dev/null || df -Pk 2>/dev/null) | awk "NR>1 && \$1 ~ /^\// && \$1 !~ /^\/dev\/loop/ {pct=\$5; m=\$6; for(i=7;i<=NF;i++) m=m \" \" \${'$'}i; if(m !~ /^\/snap(\/|\$)/) print pct, m}"
            echo "---NET---"; awk "NR>2 && \$1 !~ /^lo:/ {gsub(/:/,\" \",\$0); rx+=\$2; tx+=\$10} END{printf \"%.0f %.0f\n\",rx,tx}" /proc/net/dev
            echo "---LOAD---"; cat /proc/loadavg; nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo
            echo "---GPU---"; command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi --query-gpu=utilization.gpu,name --format=csv,noheader,nounits 2>/dev/null | head -n1 || true
            '
        """.trimIndent()

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
