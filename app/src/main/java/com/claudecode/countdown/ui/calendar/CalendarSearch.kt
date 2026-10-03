package com.claudecode.countdown.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecode.countdown.container
import com.claudecode.countdown.data.db.CalendarLayer
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.CalendarQuery
import com.claudecode.countdown.domain.SearchKind
import com.claudecode.countdown.domain.SearchPeriod
import com.claudecode.countdown.domain.dueDay
import com.claudecode.countdown.domain.searchCalendar
import com.claudecode.countdown.ui.formatEventSpan
import com.claudecode.countdown.ui.formatDue
import com.claudecode.countdown.ui.formatFullDate
import com.claudecode.countdown.ui.tasks.Snapshot
import kotlinx.coroutines.android.awaitFrame

/**
 * Search through events and tasks as in Google Calendar: results while typing, matches
 * highlighted, grouped by date, with filters (kind, calendars, dates, words to leave out) and
 * recent searches.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalendarSearchScreen(snapshot: Snapshot, onOpenTask: (String) -> Unit, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val app = container.settings
    val settings by app.state.collectAsStateWithLifecycle()
    val calendars by container.tasks.observeCalendars().collectAsStateWithLifecycle(emptyList())
    var text by remember { mutableStateOf("") }
    var without by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(SearchKind.ALL) }
    var period by remember { mutableStateOf(SearchPeriod.ANY) }
    var only by remember { mutableStateOf<Set<String>?>(null) }
    var filters by remember { mutableStateOf(false) }
    var periodMenu by remember { mutableStateOf(false) }
    val query = CalendarQuery(text, without, kind, only, period)
    val results = remember(snapshot.tasks, query) { searchCalendar(snapshot.tasks, query, snapshot.today) }
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        awaitFrame()
        runCatching { focus.requestFocus() }
    }
    fun open(t: Task) {
        app.addRecentSearch(text)
        onOpenTask(t.id)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                title = {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = { Text("Поиск в календаре") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { app.addRecentSearch(text) }),
                        trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Close, "Очистить") } },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = { IconButton(onClick = { filters = !filters }) { Icon(Icons.Outlined.Tune, "Фильтры", tint = if (filters) scheme.primary else scheme.onSurfaceVariant) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in SearchKind.entries) FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) })
                Box {
                    FilterChip(selected = period != SearchPeriod.ANY, onClick = { periodMenu = true }, label = { Text(period.label) })
                    DropdownMenu(periodMenu, { periodMenu = false }) {
                        for (p in SearchPeriod.entries) DropdownMenuItem(text = { Text(p.label) }, onClick = { period = p; periodMenu = false })
                    }
                }
            }
            if (filters) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = without,
                        onValueChange = { without = it },
                        label = { Text("Не содержит слов") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Календари", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (c in calendars) {
                            val on = only == null || c.id in only!!
                            FilterChip(
                                selected = on,
                                onClick = {
                                    val current = only ?: calendars.map { it.id }.toSet()
                                    val next = if (on) current - c.id else current + c.id
                                    only = if (next.size == calendars.size) null else next
                                },
                                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color))) },
                                label = { Text(c.name) },
                            )
                        }
                    }
                }
            }
            if (text.isBlank()) {
                if (settings.recentSearches.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Недавние", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = app::clearRecentSearches) { Text("Очистить") }
                    }
                    for (r in settings.recentSearches) {
                        Row(
                            Modifier.fillMaxWidth().clickable { text = r }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.History, null, tint = scheme.onSurfaceVariant)
                            Spacer(Modifier.width(16.dp))
                            Text(r, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    Text(
                        "Ищет по названию, описанию и месту событий и задач.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                return@Column
            }
            if (results.isEmpty()) {
                Text("Ничего не найдено", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                return@Column
            }
            val groups = remember(results) { results.groupBy { it.dueDay() } }
            val words = remember(text) { text.lowercase().split(' ').filter { it.isNotBlank() } }
            val colors = remember(calendars) { calendars.associate { it.id to it.color } }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                for ((day, list) in groups) {
                    item(key = "d:$day") {
                        Text(
                            day?.let { formatFullDate(it) } ?: "Без даты",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (day == snapshot.today) scheme.primary else scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.id }) { t ->
                        val color = Color(t.color ?: if (t.isEvent) colors[t.calendarId ?: CalendarLayer.PERSONAL_ID] ?: scheme.primary.hashCode() else snapshot.colorOf(t) ?: 0xFF9E9E9E.toInt())
                        Row(
                            Modifier.fillMaxWidth().clickable { open(t) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(highlight(t.title, words, scheme.primary), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val time = if (t.isEvent) formatEventSpan(t, snapshot.today) else formatDue(t, snapshot.today)
                                val place = t.location?.let { " · $it" }.orEmpty()
                                Text(highlight((time ?: "") + place, words, scheme.primary), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                                if (t.content.isNotBlank() && words.any { it in t.content.lowercase() }) {
                                    Text(highlight(t.content.lineSequence().first(), words, scheme.primary), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** [text] with every occurrence of [words] in bold accent colour. */
private fun highlight(text: String, words: List<String>, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val lower = text.lowercase()
    for (w in words) {
        var at = lower.indexOf(w)
        while (at >= 0) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color), at, at + w.length)
            at = lower.indexOf(w, at + w.length)
        }
    }
}
