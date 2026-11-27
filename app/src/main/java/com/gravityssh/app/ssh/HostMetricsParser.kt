package com.gravityssh.app.ssh

import com.gravityssh.app.model.HostMetrics
import java.time.Instant

internal data class ParsedHostMetrics(
    val metrics: HostMetrics,
    val cpu: CpuSample?,
    val net: NetSample?,
    val parsedCores: Int
)

internal object HostMetricsParser {

    private val WS_RE = Regex("\\s+")

    fun parse(
        sessionId: String,
        raw: String,
        latencyMs: Long,
        prevCpu: CpuSample?,
        prevNet: NetSample?,
        prevSampleMs: Long,
        nowMs: Long,
        fallbackCores: Int = 1
    ): Triple<HostMetrics, CpuSample?, NetSample?> {
        val detailed = parseDetailed(sessionId, raw, latencyMs, prevCpu, prevNet, prevSampleMs, nowMs, fallbackCores)
        return Triple(detailed.metrics, detailed.cpu, detailed.net)
    }

    fun parseDetailed(
        sessionId: String,
        raw: String,
        latencyMs: Long,
        prevCpu: CpuSample?,
        prevNet: NetSample?,
        prevSampleMs: Long,
        nowMs: Long,
        fallbackCores: Int = 1
    ): ParsedHostMetrics {
        if (raw.contains("UNSUPPORTED")) {
            return ParsedHostMetrics(
                HostMetrics(sessionId = sessionId, supported = false, latencyMs = latencyMs, updatedAt = Instant.ofEpochMilli(nowMs).toString()),
                null, null, 0
            )
        }
        var section = ""
        var curCpu: CpuSample? = null
        var curNet: NetSample? = null
        val memMap = HashMap<String, Long>()
        var supported = false
        var diskPct = 0.0
        var diskMount = "/"
        var maxDisk = -1.0
        var load1 = 0.0
        var cores = 0
        var hasGpu = false
        var gpuUtil = 0.0
        var gpuName = ""

        for (rawLine in raw.replace("\r\n", "\n").split('\n')) {
            val t = rawLine.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("---") && t.endsWith("---")) { section = t.trim('-'); continue }
            when (section) {
                "CPU" -> {
                    val f = t.split(WS_RE)
                    if (f.size >= 5 && f[0] == "cpu") {
                        var total = 0L; var idle = 0L
                        for (i in 1 until f.size) {
                            val v = f[i].toLongOrNull() ?: 0L
                            total += v; if (i == 4 || i == 5) idle += v
                        }
                        curCpu = CpuSample(total, idle); supported = true
                    }
                }
                "MEM" -> {
                    val f = t.split(WS_RE)
                    if (f.size >= 2) memMap[f[0].removeSuffix(":")] = f[1].toLongOrNull() ?: 0L
                }
                "DISK" -> {
                    val parts = t.split(WS_RE, limit = 2)
                    if (parts.size == 2) {
                        val pct = parts[0].removeSuffix("%").toDoubleOrNull()
                        val mount = parts[1].trim()
                        if (pct != null && mount.isNotEmpty() && pct >= maxDisk) {
                            maxDisk = pct; diskPct = pct; diskMount = mount
                        }
                    }
                }
                "NET" -> {
                    val f = t.split(WS_RE)
                    if (f.size >= 2) curNet = NetSample(f[0].toLongOrNull() ?: 0L, f[1].toLongOrNull() ?: 0L)
                }
                "LOAD" -> {
                    val f = t.split(WS_RE)
                    if (f.size >= 3 && load1 == 0.0) load1 = f[0].toDoubleOrNull() ?: 0.0
                    else if (f.size == 1 && cores == 0) cores = f[0].toIntOrNull() ?: 0
                }
                "GPU" -> {
                    val parts = t.split(',', limit = 2)
                    val u = parts.getOrNull(0)?.trim()?.toDoubleOrNull()
                    if (parts.size == 2 && u != null) { hasGpu = true; gpuUtil = u; gpuName = parts[1].trim() }
                }
            }
        }

        val cpuPct = if (curCpu != null && prevCpu != null && curCpu.total > prevCpu.total) {
            ((1.0 - (curCpu.idle - prevCpu.idle).toDouble() / (curCpu.total - prevCpu.total).toDouble()) * 100.0)
                .coerceIn(0.0, 100.0)
        } else 0.0

        var memTotalMb = 0L; var memUsedMb = 0L; var memPct = 0.0
        val totalKb = memMap["MemTotal"] ?: 0L
        if (totalKb > 0) {
            val availKb = (memMap["MemAvailable"] ?: 0L).takeIf { it > 0 }
                ?: ((memMap["MemFree"] ?: 0L) + (memMap["Buffers"] ?: 0L) + (memMap["Cached"] ?: 0L))
            val usedKb = (totalKb - availKb).coerceAtLeast(0L)
            memTotalMb = totalKb / 1024L; memUsedMb = usedKb / 1024L
            memPct = (usedKb.toDouble() / totalKb.toDouble()) * 100.0
            supported = true
        }

        var rxKbps = 0.0; var txKbps = 0.0
        if (curNet != null && prevNet != null && prevSampleMs > 0L) {
            val dtSec = (nowMs - prevSampleMs) / 1000.0
            if (dtSec > 0.2) {
                if (curNet.rx >= prevNet.rx) rxKbps = (curNet.rx - prevNet.rx) / 1024.0 / dtSec
                if (curNet.tx >= prevNet.tx) txKbps = (curNet.tx - prevNet.tx) / 1024.0 / dtSec
            }
        }

        val effectiveCores = if (cores > 0) cores else fallbackCores.coerceAtLeast(1)
        return ParsedHostMetrics(
            metrics = HostMetrics(
                sessionId = sessionId, supported = supported, latencyMs = latencyMs,
                cpuPercent = cpuPct, memUsedMb = memUsedMb, memTotalMb = memTotalMb, memPercent = memPct,
                diskMount = diskMount, diskPercent = diskPct, netRxKbps = rxKbps, netTxKbps = txKbps,
                load1 = load1, cores = effectiveCores,
                hasGpu = hasGpu, gpuUtil = gpuUtil, gpuName = gpuName,
                updatedAt = Instant.ofEpochMilli(nowMs).toString()
            ),
            cpu = curCpu,
            net = curNet,
            parsedCores = cores
        )
    }
}
