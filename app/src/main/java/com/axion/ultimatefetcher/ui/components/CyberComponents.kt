package com.axion.ultimatefetcher.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.axion.ultimatefetcher.domain.models.TrafficSample
import com.axion.ultimatefetcher.ui.theme.JetBrainsMonoFamily
import com.axion.ultimatefetcher.ui.theme.LocalCyberColors
import com.axion.ultimatefetcher.utils.FormatUtils

@Composable
fun CyberGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    accentGlow: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cyber = LocalCyberColors.current
    val borderColor = accentGlow?.copy(alpha = 0.55f) ?: cyber.cardBorder
    val shape = RoundedCornerShape(cornerRadius)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = shape,
        color = cyber.glassSurface,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            content = content
        )
    }
}

@Composable
fun CyberSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = cs.onBackground,
            fontWeight = FontWeight.Bold
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
fun InfoCalloutBanner(
    title: String,
    message: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = accentColor.copy(alpha = 0.11f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.42f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = accentColor,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.weight(0.4f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (valueColor == Color.Unspecified) cs.onSurface else valueColor,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
fun LivePulseIndicator(
    isActive: Boolean,
    activeLabel: String,
    inactiveLabel: String,
    modifier: Modifier = Modifier
) {
    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme
    val color = if (isActive) cyber.success else cs.onSurfaceVariant
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isActive) color.copy(alpha = alpha) else color.copy(alpha = 0.5f))
        )
        Text(
            text = if (isActive) activeLabel else inactiveLabel,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    CyberGlassCard(modifier = modifier, cornerRadius = 16.dp, accentGlow = accentColor.copy(alpha = 0.45f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(icon, contentDescription = label, tint = accentColor, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
            color = accentColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LiveTrafficChart(
    samples: List<TrafficSample>,
    modifier: Modifier = Modifier
) {
    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme
    val dlColor = cyber.cyan
    val ulColor = cs.primary
    val gridColor = cyber.cardBorder.copy(alpha = 0.65f)

    val maxBps = (samples.maxOfOrNull { maxOf(it.downloadBps, it.uploadBps) } ?: 0L).coerceAtLeast(1024L)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("● Download", style = MaterialTheme.typography.labelSmall, color = dlColor)
                Text("● Upload", style = MaterialTheme.typography.labelSmall, color = ulColor)
            }
            Text("Peak: ${FormatUtils.formatSpeed(maxBps)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cs.background.copy(alpha = 0.6f))
                .padding(8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                for (i in 0..3) {
                    val y = h * (i / 3f)
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                }

                if (samples.size < 2) return@Canvas
                val stepX = w / (samples.size - 1).coerceAtLeast(1)

                val dlPath = Path()
                val dlFill = Path()
                val ulPath = Path()

                samples.forEachIndexed { idx, s ->
                    val x = idx * stepX
                    val yDl = h - (s.downloadBps.toFloat() / maxBps.toFloat()).coerceIn(0f, 1f) * (h - 6f) - 3f
                    val yUl = h - (s.uploadBps.toFloat() / maxBps.toFloat()).coerceIn(0f, 1f) * (h - 6f) - 3f
                    if (idx == 0) {
                        dlPath.moveTo(x, yDl)
                        dlFill.moveTo(x, h)
                        dlFill.lineTo(x, yDl)
                        ulPath.moveTo(x, yUl)
                    } else {
                        dlPath.lineTo(x, yDl)
                        dlFill.lineTo(x, yDl)
                        ulPath.lineTo(x, yUl)
                    }
                    if (idx == samples.lastIndex) {
                        dlFill.lineTo(x, h)
                        dlFill.close()
                    }
                }

                drawPath(
                    path = dlFill,
                    brush = Brush.verticalGradient(listOf(dlColor.copy(alpha = 0.28f), Color.Transparent))
                )
                drawPath(
                    path = dlPath,
                    color = dlColor,
                    style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = ulPath,
                    color = ulColor,
                    style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    CyberGlassCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = title, tint = cs.onSurfaceVariant, modifier = Modifier.size(34.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
fun MethodBadge(method: String, modifier: Modifier = Modifier) {
    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme
    val color = when (method.uppercase()) {
        "GET" -> cyber.cyan
        "POST" -> cyber.success
        "PUT", "PATCH" -> cyber.warning
        "DELETE" -> cs.error
        else -> cs.primary
    }
    BadgePill(method.uppercase(), color, modifier)
}

@Composable
fun StatusCodeBadge(statusCode: Int, modifier: Modifier = Modifier) {
    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme
    val color = when (statusCode) {
        in 200..299 -> cyber.success
        in 300..399 -> cyber.cyan
        in 400..499 -> cyber.warning
        in 500..599 -> cs.error
        else -> cs.onSurfaceVariant
    }
    BadgePill(if (statusCode > 0) "HTTP $statusCode" else "ERR", color, modifier)
}

@Composable
fun ProtocolBadge(protocol: String, isBlocked: Boolean = false, modifier: Modifier = Modifier) {
    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme
    val color = when {
        isBlocked -> cs.error
        protocol.startsWith("TCP", true) -> cs.primary
        protocol.startsWith("UDP", true) -> cyber.cyan
        protocol.startsWith("QUIC", true) -> cyber.warning
        protocol.startsWith("DNS", true) -> cyber.success
        protocol.startsWith("TLS", true) -> cyber.violet
        else -> cs.secondary
    }
    val text = if (isBlocked) "$protocol • BLOCKED" else protocol
    BadgePill(text, color, modifier)
}

@Composable
private fun BadgePill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun highlightJson(json: String): AnnotatedString {
    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme
    val keyColor = cyber.cyan
    val stringColor = cyber.success
    val numberColor = cyber.warning
    val boolNullColor = cyber.violet
    val punctColor = cs.onSurfaceVariant

    val regex = Regex(""""(\\.|[^"\\])*"\s*:|"(\\.|[^"\\])*"|\b(true|false|null)\b|-?\d+(\.\d+)?([eE][+-]?\d+)?""")
    return buildAnnotatedString {
        var lastEnd = 0
        for (match in regex.findAll(json)) {
            if (match.range.first > lastEnd) {
                withStyle(SpanStyle(color = punctColor)) {
                    append(json.substring(lastEnd, match.range.first))
                }
            }
            val token = match.value
            val color = when {
                token.endsWith(":") -> keyColor
                token.startsWith("\"") -> stringColor
                token == "true" || token == "false" || token == "null" -> boolNullColor
                else -> numberColor
            }
            withStyle(SpanStyle(color = color)) {
                append(token)
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < json.length) {
            withStyle(SpanStyle(color = punctColor)) {
                append(json.substring(lastEnd))
            }
        }
    }
}

@Composable
fun VpnDiagnosticsPanel(
    vpnState: com.axion.ultimatefetcher.domain.models.VpnLiveState,
    modifier: Modifier = Modifier
) {
    val cyber = LocalCyberColors.current
    val cs = MaterialTheme.colorScheme

    @Composable
    fun stageColor(status: com.axion.ultimatefetcher.domain.models.PipelineStageStatus): Color = when (status) {
        com.axion.ultimatefetcher.domain.models.PipelineStageStatus.ACTIVE -> cyber.success
        com.axion.ultimatefetcher.domain.models.PipelineStageStatus.WAITING -> cyber.warning
        com.axion.ultimatefetcher.domain.models.PipelineStageStatus.ERROR -> cs.error
        com.axion.ultimatefetcher.domain.models.PipelineStageStatus.IDLE -> cs.onSurfaceVariant
    }

    CyberGlassCard(
        modifier = modifier,
        accentGlow = when {
            vpnState.lastError != null || vpnState.dbInsertErrors > 0L -> cs.error
            vpnState.isActivelyCapturing -> cyber.success
            vpnState.vpnInterfaceEstablished -> cyber.warning
            else -> null
        }
    ) {
        CyberSectionHeader(
            title = "Live Capture Diagnostics",
            subtitle = vpnState.statusMessage
        )
        Spacer(Modifier.height(10.dp))

        // 4 Separate Pipeline Stage States
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PipelineStageBadge("VPN Conn", vpnState.vpnConnectedState.label, stageColor(vpnState.vpnConnectedState), Modifier.weight(1f))
            PipelineStageBadge("Reading", vpnState.packetReadingState.label, stageColor(vpnState.packetReadingState), Modifier.weight(1f))
            PipelineStageBadge("Processing", vpnState.packetProcessingState.label, stageColor(vpnState.packetProcessingState), Modifier.weight(1f))
            PipelineStageBadge("DB Saving", vpnState.databaseSavingState.label, stageColor(vpnState.databaseSavingState), Modifier.weight(1f))
        }

        if (vpnState.lastError != null) {
            Spacer(Modifier.height(8.dp))
            InfoCalloutBanner(
                title = "VPN Configuration / Runtime Warning",
                message = vpnState.lastError,
                accentColor = cs.error
            )
        }

        if (vpnState.lastDbError != null) {
            Spacer(Modifier.height(8.dp))
            InfoCalloutBanner(
                title = "Database Insert Error",
                message = vpnState.lastDbError,
                accentColor = cs.error
            )
        }

        Spacer(Modifier.height(10.dp))
        KeyValueRow(
            "VPN Interface Established",
            if (vpnState.vpnInterfaceEstablished) "Yes (IPv4 + IPv6 TUN)" else "No",
            valueColor = if (vpnState.vpnInterfaceEstablished) cyber.success else cs.onSurfaceVariant
        )
        KeyValueRow("Selected App Scope", vpnState.selectedAppsSummary)
        KeyValueRow("Packets Read", vpnState.packetsRead.toString(), valueColor = cyber.cyan)
        KeyValueRow("Packets Parsed", vpnState.packetsParsed.toString(), valueColor = cs.onSurface)
        KeyValueRow("TCP / UDP / DNS Packets", "${vpnState.tcpPackets} / ${vpnState.udpPackets} / ${vpnState.dnsPackets}")
        KeyValueRow(
            "Packets Saved to Database",
            vpnState.packetsSavedToDb.toString(),
            valueColor = if (vpnState.packetsSavedToDb > 0L) cyber.success else cs.onSurfaceVariant
        )
        KeyValueRow(
            "Packets Rejected (Malformed)",
            vpnState.packetsRejected.toString(),
            valueColor = if (vpnState.packetsRejected > 0L) cyber.warning else cs.onSurfaceVariant
        )
        KeyValueRow(
            "Last Packet Timestamp",
            if (vpnState.lastPacketTimestamp > 0L) FormatUtils.formatDateTime(vpnState.lastPacketTimestamp) else "None yet"
        )
        KeyValueRow(
            "Database Insert Errors",
            vpnState.dbInsertErrors.toString(),
            valueColor = if (vpnState.dbInsertErrors > 0L) cs.error else cyber.success
        )
    }
}

@Composable
private fun PipelineStageBadge(
    title: String,
    status: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = color,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

