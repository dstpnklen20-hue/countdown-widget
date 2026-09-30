package com.claudecode.countdown.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.claudecode.countdown.container
import com.claudecode.countdown.data.AppSettings
import com.claudecode.countdown.data.Tool
import com.claudecode.countdown.data.barLayout
import com.claudecode.countdown.ui.AppBottomBar
import com.claudecode.countdown.ui.CalendarDayIcon
import com.claudecode.countdown.ui.icon

/**
 * Which sections sit on the bottom bar, in what order, and how many fit before "More" appears.
 * Changes show at once in a preview of the bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarSettingsScreen(today: Int, onBack: () -> Unit) {
    val appSettings = LocalContext.current.container.settings
    val settings by appSettings.state.collectAsStateWithLifecycle()
    val layout = remember(settings.tools, settings.barLimit) { barLayout(settings.tools, settings.barLimit) }
    val unpinned = Tool.entries.filter { it !in settings.tools }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Панель инструментов") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item(key = "preview") {
                Label("Так будет выглядеть панель")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    AppBottomBar(layout, Tool.TASKS, today, onSelect = {}, onMore = {})
                }
                Spacer(Modifier.height(16.dp))
                Label("Вкладок на панели")
                val limits = AppSettings.BAR_LIMITS.toList()
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    limits.forEachIndexed { i, n ->
                        SegmentedButton(
                            selected = settings.barLimit == n,
                            onClick = { appSettings.setBarLimit(n) },
                            shape = SegmentedButtonDefaults.itemShape(i, limits.size),
                            icon = {},
                        ) { Text("$n") }
                    }
                }
                Text(
                    "Если закреплённых разделов больше, последняя вкладка становится кнопкой «Ещё» с остальными. " +
                        "На планшете боковая панель вмещает все разделы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
                Label("На панели")
            }
            items(settings.tools, key = { "pinned:${it.name}" }) { tool ->
                val index = settings.tools.indexOf(tool)
                if (index == layout.visible.size && layout.more.isNotEmpty()) {
                    Text(
                        "В меню «Ещё»",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
                    )
                }
                ToolRow(tool, today, Modifier.animateItem()) {
                    if (tool.fixed) {
                        Icon(Icons.Filled.Lock, "Закреплён всегда", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(12.dp).size(18.dp))
                    } else {
                        IconButton(onClick = { appSettings.setPinned(tool, false) }) {
                            Icon(Icons.Filled.RemoveCircle, "Убрать с панели", tint = Color(0xFFE53935))
                        }
                    }
                    IconButton(onClick = { appSettings.moveTool(tool, -1) }, enabled = index > 1) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Выше")
                    }
                    IconButton(onClick = { appSettings.moveTool(tool, 1) }, enabled = index in 1 until settings.tools.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Ниже")
                    }
                }
            }
            if (unpinned.isNotEmpty()) {
                item(key = "others") {
                    Spacer(Modifier.height(16.dp))
                    Label("Другие разделы")
                }
                items(unpinned, key = { "other:${it.name}" }) { tool ->
                    ToolRow(tool, today, Modifier.animateItem()) {
                        IconButton(onClick = { appSettings.setPinned(tool, true) }) {
                            Icon(Icons.Filled.AddCircle, "Добавить на панель", tint = Color(0xFF43A047))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun ToolRow(tool: Tool, today: Int, modifier: Modifier, actions: @Composable () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                val icon = tool.icon
                if (icon != null) Icon(icon, null) else CalendarDayIcon(today, null)
            }
        }
        Text(tool.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 16.dp, top = 14.dp, bottom = 14.dp))
        actions()
    }
}
