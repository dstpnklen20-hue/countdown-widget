package com.claudecode.countdown.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WEEK_DAYS = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))

/**
 * One measure per day as thin bars in the accent colour. Tapping a bar shows its day and value
 * above the chart (the chart's only label, so nothing crowds it); today's weekday is highlighted.
 * [valueText] turns a value into words, e.g. "5 задач".
 */
@Composable
fun DayBarChart(values: List<Int>, days: List<LocalDate>, today: LocalDate, valueText: (Int) -> String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var picked by remember(days.firstOrNull(), days.size) { mutableStateOf<Int?>(null) }
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val shown = picked ?: days.indexOf(today).takeIf { it >= 0 }
    Column(modifier) {
        // The label line: the picked day, else today; empty-looking days still read "0 …".
        Text(
            shown?.let { "${days[it].format(dayMonth)}: ${valueText(values[it])}" } ?: " ",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        val bar = scheme.primary
        val barPicked = scheme.primary
        val muted = scheme.primary.copy(alpha = 0.45f)
        val empty = scheme.outlineVariant
        val baseline = scheme.outlineVariant
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = days.indices.joinToString { "${days[it].format(dayMonth)} ${valueText(values[it])}" } }
                .pointerInput(days, values) {
                    detectTapGestures { offset ->
                        val i = (offset.x / (size.width / values.size)).toInt().coerceIn(values.indices)
                        picked = if (picked == i) null else i
                    }
                },
        ) {
            val slot = size.width / values.size
            // Wide bars for a week, thin ones for a month; always a gap between neighbours.
            val barWidth = (slot * if (values.size <= 7) 0.5f else 0.7f).coerceAtMost(28.dp.toPx())
            val radius = 4.dp.toPx().coerceAtMost(barWidth / 2)
            drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            values.forEachIndexed { i, v ->
                val left = slot * i + (slot - barWidth) / 2
                if (v == 0) {
                    drawRoundRect(empty, Offset(left, size.height - 3.dp.toPx()), Size(barWidth, 3.dp.toPx()), CornerRadius(radius))
                } else {
                    val h = (size.height * v / max).coerceAtLeast(4.dp.toPx())
                    // Only a bar the user tapped dims the others; the default (today) label doesn't.
                    val color = when {
                        picked == null -> bar
                        i == picked -> barPicked
                        else -> muted
                    }
                    topRoundedBar(color, left, size.height - h, barWidth, h, radius)
                }
            }
        }
        val measurer = rememberTextMeasurer()
        val labelStyle = MaterialTheme.typography.labelSmall
        val labelColor = scheme.onSurfaceVariant
        val accent = scheme.primary
        Canvas(Modifier.fillMaxWidth().height(18.dp).padding(top = 4.dp)) {
            val slot = size.width / days.size
            days.forEachIndexed { i, day ->
                // A month has no room for 30 captions: label Mondays only, centred under their bar.
                val label = when {
                    days.size <= 7 -> WEEK_DAYS[day.dayOfWeek.value - 1]
                    day.dayOfWeek.value == 1 -> "${day.dayOfMonth}"
                    else -> return@forEachIndexed
                }
                val color = if (day == today || i == picked) accent else labelColor
                val layout = measurer.measure(label, labelStyle.copy(color = color))
                val x = (slot * i + slot / 2 - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
                drawText(layout, topLeft = Offset(x, 0f))
            }
        }
    }
}

/** A bar rounded at its data end only; the base sits flat on the axis. */
private fun DrawScope.topRoundedBar(color: androidx.compose.ui.graphics.Color, left: Float, top: Float, width: Float, height: Float, r: Float) {
    val path = Path().apply {
        moveTo(left, top + height)
        lineTo(left, top + r)
        quadraticTo(left, top, left + r, top)
        lineTo(left + width - r, top)
        quadraticTo(left + width, top, left + width, top + r)
        lineTo(left + width, top + height)
        close()
    }
    drawPath(path, color)
}
