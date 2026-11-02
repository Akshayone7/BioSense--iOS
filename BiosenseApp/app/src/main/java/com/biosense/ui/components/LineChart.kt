package com.biosense.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A vertical marker on the chart. [xFraction] is 0..1 across the chart width;
 * the caller is responsible for time→fraction conversion so the chart stays
 * unit-agnostic (works for session ticks or wall-clock timestamps).
 */
data class ChartMarker(val xFraction: Float, val color: Color)

/**
 * Pure-Compose line chart. Auto-scales Y to the visible data.
 * Tap or drag anywhere along the chart to lock a crosshair to the nearest
 * sample; a value bubble above the chart shows the reading and (if provided)
 * the corresponding x-axis label.
 */
@Composable
fun LineChart(
    title: String,
    values: FloatArray,
    color: Color,
    modifier: Modifier = Modifier,
    heightDp: Int = 140,
    xLabels: List<String> = emptyList(),
    markers: List<ChartMarker> = emptyList(),
    valueFormatter: (Float) -> String = { "%.1f".format(it) },
) {
    var selectedIdx by remember(values) { mutableStateOf<Int?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            selectedIdx?.let { idx ->
                val v = values.getOrNull(idx) ?: return@let
                val xLabel = when {
                    xLabels.isEmpty() || values.size < 2 -> null
                    else -> {
                        val bucket = (idx.toFloat() / (values.size - 1) * (xLabels.size - 1))
                            .toInt()
                            .coerceIn(0, xLabels.lastIndex)
                        xLabels[bucket]
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        valueFormatter(v),
                        color = color,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (xLabel != null) {
                        Text(
                            xLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .pointerInput(values) {
                    if (values.size < 2) return@pointerInput
                    detectTapGestures(
                        onTap = { offset ->
                            val stepX = size.width / (values.size - 1)
                            selectedIdx = (offset.x / stepX).toInt().coerceIn(0, values.lastIndex)
                        },
                        onLongPress = { selectedIdx = null },
                    )
                }
                .pointerInput(values) {
                    if (values.size < 2) return@pointerInput
                    detectDragGestures { change, _ ->
                        val stepX = size.width / (values.size - 1)
                        selectedIdx = (change.position.x / stepX).toInt().coerceIn(0, values.lastIndex)
                    }
                }
        ) {
            if (values.size < 2) return@Canvas
            var minV = values[0]; var maxV = values[0]
            for (v in values) { if (v < minV) minV = v; if (v > maxV) maxV = v }
            if (maxV - minV < 1e-3f) { maxV = minV + 1e-3f }
            val range = maxV - minV

            val w = size.width; val h = size.height
            val stepX = w / (values.size - 1)

            // Grid
            val grid = Color(0x22FFFFFF)
            for (i in 1..3) {
                val y = h * i / 4
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }

            // Draw markers behind the line so the trend stays legible on top.
            for (m in markers) {
                val x = (m.xFraction.coerceIn(0f, 1f)) * w
                drawLine(m.color, Offset(x, 0f), Offset(x, h), strokeWidth = 2f)
            }

            val path = Path()
            for (i in values.indices) {
                val x = i * stepX
                val y = h - ((values[i] - minV) / range) * h
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path = path, color = color, style = Stroke(width = 3f))

            selectedIdx?.let { idx ->
                val x = idx * stepX
                val y = h - ((values[idx] - minV) / range) * h
                drawLine(Color(0x88FFFFFF), Offset(x, 0f), Offset(x, h), strokeWidth = 1.5f)
                drawCircle(color = color, radius = 6f, center = Offset(x, y))
                drawCircle(color = Color.White, radius = 3f, center = Offset(x, y))
            }
        }
        if (xLabels.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (lbl in xLabels) {
                    Text(
                        lbl,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
