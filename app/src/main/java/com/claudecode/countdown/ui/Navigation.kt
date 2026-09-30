package com.claudecode.countdown.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecode.countdown.data.BarLayout
import com.claudecode.countdown.data.Tool
import com.claudecode.countdown.data.barLayout

/** Null for the calendar: it is drawn with today's date, see [CalendarDayIcon]. */
val Tool.icon: ImageVector?
    get() = when (this) {
        Tool.TASKS -> Icons.Outlined.CheckBox
        Tool.CALENDAR -> null
        Tool.MATRIX -> Icons.Outlined.GridView
        Tool.FOCUS -> Icons.Outlined.Timer
        Tool.HABITS -> Icons.Outlined.Loop
        Tool.COUNTDOWNS -> Icons.Outlined.HourglassEmpty
        Tool.SEARCH -> Icons.Outlined.Search
        Tool.SETTINGS -> Icons.Outlined.Settings
    }

/**
 * The side rail of a tablet has room for far more than a phone's bar: it shows the pinned tools
 * first, then the rest; Settings sits at the bottom, as in TickTick.
 */
fun railTools(pinned: List<Tool>): List<Tool> =
    (pinned - Tool.SETTINGS) + (Tool.entries - pinned.toSet()) + Tool.SETTINGS

/** Phones: icons without captions, as in TickTick; tools that don't fit go under "More". */
@Composable
fun AppBottomBar(layout: BarLayout, selected: Tool, today: Int, onSelect: (Tool) -> Unit, onMore: () -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        for (t in layout.visible) {
            NavigationBarItem(
                selected = selected == t,
                onClick = { onSelect(t) },
                icon = { ToolIcon(t, today, selected == t) },
            )
        }
        if (layout.more.isNotEmpty()) {
            val inMore = selected in layout.more
            NavigationBarItem(
                selected = inMore,
                onClick = onMore,
                icon = { MoreIcon(inMore) },
            )
        }
    }
}

/** Tablets: a column of icons on the left, filled to the screen's height. */
@Composable
fun AppRail(pinned: List<Tool>, selected: Tool, today: Int, onSelect: (Tool) -> Unit, onMore: (List<Tool>) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxHeight()) {
        val tools = railTools(pinned)
        val slots = ((maxHeight - RAIL_RESERVED) / RAIL_ITEM).toInt()
        val layout = barLayout(tools, slots)
        val bottom = layout.visible.lastOrNull()?.takeIf { it == Tool.SETTINGS }
        NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Spacer(Modifier.height(8.dp))
            for (t in layout.visible) {
                if (t == bottom) continue
                NavigationRailItem(selected = selected == t, onClick = { onSelect(t) }, icon = { ToolIcon(t, today, selected == t) })
            }
            Spacer(Modifier.weight(1f))
            if (layout.more.isNotEmpty()) {
                val inMore = selected in layout.more
                NavigationRailItem(selected = inMore, onClick = { onMore(layout.more) }, icon = { MoreIcon(inMore) })
            }
            if (bottom != null) {
                NavigationRailItem(selected = selected == bottom, onClick = { onSelect(bottom) }, icon = { ToolIcon(bottom, today, selected == bottom) })
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private val RAIL_ITEM = 60.dp
private val RAIL_RESERVED = 32.dp

/** The tools that didn't fit on the bar, with a shortcut to the panel settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreToolsSheet(tools: List<Tool>, selected: Tool, today: Int, onSelect: (Tool) -> Unit, onEdit: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ещё", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("Изменить") }
            }
            Spacer(Modifier.height(8.dp))
            for (row in tools.chunked(4)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    for (t in row) {
                        val active = t == selected
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelect(t) }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
                                contentAlignment = Alignment.Center,
                            ) {
                                val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                CompositionLocalProvider(LocalContentColor provides tint) { ToolIcon(t, today, active) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(t.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** A little "pop" when a tab becomes selected. */
@Composable
private fun ToolIcon(tool: Tool, today: Int, selected: Boolean) {
    val scale by animateFloatAsState(if (selected) 1.08f else 1f, Motion.gentleSpring(), label = "tab")
    Box(Modifier.scale(scale)) {
        val icon = tool.icon
        if (icon != null) Icon(icon, tool.label) else CalendarDayIcon(today, tool.label)
    }
}

@Composable
private fun MoreIcon(selected: Boolean) {
    val scale by animateFloatAsState(if (selected) 1.08f else 1f, Motion.gentleSpring(), label = "more")
    Icon(Icons.Filled.MoreHoriz, "Ещё", Modifier.scale(scale))
}

/** A calendar page with today's number on it. */
@Composable
fun CalendarDayIcon(day: Int, description: String?) {
    val tint = LocalContentColor.current
    Box(
        Modifier
            .size(22.dp)
            .border(1.8.dp, tint, RoundedCornerShape(5.dp))
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(5.dp).align(Alignment.TopCenter).background(tint, RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)))
        Text(
            "$day",
            color = tint,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
