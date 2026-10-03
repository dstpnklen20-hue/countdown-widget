package com.claudecode.countdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A colour of the calendar palette with its name, as Google Calendar names them. */
data class NamedColor(val name: String, val argb: Int)

/** 24 calendar colours: Google Calendar's event colours first, then its calendar colours. */
val CALENDAR_COLORS: List<NamedColor> = listOf(
    "Томат" to 0xFFD50000, "Фламинго" to 0xFFE67C73, "Мандарин" to 0xFFF4511E, "Банан" to 0xFFF6BF26,
    "Шалфей" to 0xFF33B679, "Базилик" to 0xFF0B8043, "Павлин" to 0xFF039BE5, "Черника" to 0xFF3F51B5,
    "Лаванда" to 0xFF7986CB, "Виноград" to 0xFF8E24AA, "Графит" to 0xFF616161, "Радиккио" to 0xFFAD1457,
    "Сакура" to 0xFFD81B60, "Тыква" to 0xFFEF6C00, "Манго" to 0xFFF09300, "Цитрон" to 0xFFE4C441,
    "Авокадо" to 0xFFC0CA33, "Фисташка" to 0xFF7CB342, "Эвкалипт" to 0xFF009688, "Кобальт" to 0xFF4285F4,
    "Аметист" to 0xFF9E69AF, "Сирень" to 0xFFB39DDB, "Какао" to 0xFF795548, "Берёза" to 0xFFA79B8E,
).map { (name, argb) -> NamedColor(name, argb.toInt()) }

fun colorName(argb: Int?): String? = CALENDAR_COLORS.firstOrNull { it.argb == argb }?.name

/** All palette colours as circles; [noneLabel] adds a first "no colour of its own" choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaletteGrid(selected: Int?, noneLabel: String?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (noneLabel != null) Swatch(null, selected == null, noneLabel) { onSelect(null) }
            for (c in CALENDAR_COLORS) Swatch(c.argb, selected == c.argb, c.name) { onSelect(c.argb) }
        }
        val name = if (selected == null) noneLabel else colorName(selected)
        if (name != null) {
            Text(name, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Swatch(argb: Int?, selected: Boolean, description: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(argb?.let { Color(it) } ?: scheme.surfaceContainerHighest)
            .then(if (argb == null) Modifier.border(1.dp, scheme.outline, CircleShape) else Modifier)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, description, tint = if (argb == null) scheme.onSurface else Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun PaletteDialog(title: String, selected: Int?, noneLabel: String?, onSelect: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { PaletteGrid(selected, noneLabel, { onSelect(it); onDismiss() }, Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/** A colour circle with a name next to it, for rows like "Цвет: Павлин". */
@Composable
fun ColorLabel(argb: Int?, fallback: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(argb?.let { Color(it) } ?: fallback))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
