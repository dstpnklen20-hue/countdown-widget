package com.claudecode.countdown.ui

import android.icu.text.TimeZoneNames
import android.icu.util.ULocale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

/** Zones offered first: Russia's, west to east, then a few often needed abroad. */
private val COMMON_ZONES = listOf(
    "Europe/Kaliningrad", "Europe/Moscow", "Europe/Samara", "Asia/Yekaterinburg", "Asia/Omsk", "Asia/Novosibirsk",
    "Asia/Krasnoyarsk", "Asia/Irkutsk", "Asia/Yakutsk", "Asia/Vladivostok", "Asia/Magadan", "Asia/Kamchatka",
    "Europe/Minsk", "Europe/Istanbul", "Asia/Dubai", "Asia/Tbilisi", "Asia/Yerevan", "Asia/Baku", "Asia/Almaty",
    "Asia/Tashkent", "Asia/Bangkok", "Asia/Shanghai", "Asia/Tokyo", "Europe/London", "Europe/Berlin",
    "Europe/Paris", "America/New_York", "America/Los_Angeles",
)

private val names by lazy { runCatching { TimeZoneNames.getInstance(ULocale("ru")) }.getOrNull() }

/** "Москва" for Europe/Moscow: the city in Russian when known, else the last part of the id. */
fun zoneCity(id: String): String =
    runCatching { names?.getExemplarLocationName(id) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: id.substringAfterLast('/').replace('_', ' ')

/** "UTC+3", "UTC+5:30" right now. */
fun zoneOffset(id: String): String {
    val seconds = runCatching { ZoneId.of(id).rules.getOffset(Instant.now()).totalSeconds }.getOrDefault(0)
    val sign = if (seconds < 0) "−" else "+"
    val abs = kotlin.math.abs(seconds)
    val h = abs / 3600
    val m = abs % 3600 / 60
    return "UTC$sign$h" + if (m != 0) ":%02d".format(m) else ""
}

/** "Москва (UTC+3)". */
fun zoneLabel(id: String): String = "${zoneCity(id)} (${zoneOffset(id)})"

/** Every region zone (Continent/City), common ones first, then the rest by offset. */
private val allZones: List<String> by lazy {
    val rest = ZoneId.getAvailableZoneIds()
        .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") && it.first().isUpperCase() && it !in COMMON_ZONES }
        .sortedWith(compareBy({ ZoneId.of(it).rules.getOffset(Instant.now()).totalSeconds }, { it }))
    COMMON_ZONES + rest
}

/**
 * Picks a time zone, searchable by city ("моск", "dubai") or offset ("+4"). [followLabel], when
 * given, adds a first choice that returns null ("как на телефоне", "как в приложении").
 */
@Composable
fun ZonePickerDialog(title: String, selected: String?, followLabel: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allZones
        else allZones.filter { id -> zoneCity(id).lowercase().contains(q) || id.lowercase().contains(q) || zoneOffset(id).lowercase().contains(q) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Город или UTC+…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    if (followLabel != null && query.isBlank()) {
                        item {
                            ZoneRow(followLabel, selected == null) { onPick(null); onDismiss() }
                            HorizontalDivider()
                        }
                    }
                    items(shown, key = { it }) { id ->
                        ZoneRow(zoneLabel(id), selected == id) { onPick(id); onDismiss() }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ZoneRow(label: String, chosen: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = chosen, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
