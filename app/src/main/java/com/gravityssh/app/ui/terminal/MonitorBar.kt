package com.gravityssh.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravityssh.app.model.HostMetrics
import com.gravityssh.app.ui.theme.AgCyan
import com.gravityssh.app.ui.theme.AgDanger
import com.gravityssh.app.ui.theme.AgElevated
import com.gravityssh.app.ui.theme.AgMuted
import com.gravityssh.app.ui.theme.AgPanel
import com.gravityssh.app.ui.theme.AgSuccess
import com.gravityssh.app.ui.theme.AgViolet
import com.gravityssh.app.ui.theme.AgWarning
import com.gravityssh.app.ui.theme.MonoFontFamily
import java.util.Locale

@Composable
fun MonitorBar(
    metrics: HostMetrics,
    modifier: Modifier = Modifier
) {
    if (!metrics.supported) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AgPanel)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetricPill("RTT", "${metrics.latencyMs}ms", latencyColor(metrics.latencyMs))
        MetricPill("CPU", formatPct(metrics.cpuPercent), pctColor(metrics.cpuPercent))
        MetricPill(
            "RAM",
            "${formatPct(metrics.memPercent)} (${metrics.memUsedMb}/${metrics.memTotalMb}M)",
            pctColor(metrics.memPercent)
        )
        MetricPill("DISK", formatPct(metrics.diskPercent), pctColor(metrics.diskPercent))
        MetricPill(
            "NET",
            "↓${formatRate(metrics.netRxKbps)} ↑${formatRate(metrics.netTxKbps)}",
            AgCyan
        )
        MetricPill(
            "LOAD",
            String.format(Locale.US, "%.2f/%dc", metrics.load1, metrics.cores),
            if (metrics.load1 > metrics.cores) AgWarning else AgMuted
        )
        if (metrics.hasGpu) {
            MetricPill("GPU", formatPct(metrics.gpuUtil), AgViolet)
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, valueColor: Color) {
    Row(
        modifier = Modifier
            .background(AgElevated, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = AgMuted, fontFamily = MonoFontFamily, fontSize = 10.sp)
        Text(text = value, color = valueColor, fontFamily = MonoFontFamily, fontSize = 10.sp)
    }
}

private fun pctColor(pct: Double): Color = when {
    pct >= 85.0 -> AgDanger
    pct >= 65.0 -> AgWarning
    else -> AgSuccess
}

private fun latencyColor(ms: Long): Color = when {
    ms >= 250 -> AgDanger
    ms >= 100 -> AgWarning
    else -> AgSuccess
}

private fun formatPct(pct: Double): String = String.format(Locale.US, "%.0f%%", pct.coerceIn(0.0, 100.0))

private fun formatRate(kbps: Double): String =
    if (kbps >= 1024.0) String.format(Locale.US, "%.1fM", kbps / 1024.0)
    else String.format(Locale.US, "%.0fK", kbps.coerceAtLeast(0.0))
