package com.claudecode.countdown.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecode.countdown.data.OptionalTab

/** Top-level sections. The bottom bar shows some of them; the others open from the ☰ menu. */
enum class HomeTab(val label: String, val icon: ImageVector?) {
    TASKS("Задачи", Icons.Outlined.CheckBox),
    CALENDAR("Календарь", null), // drawn with today's date, see CalendarDayIcon
    MATRIX("Матрица", Icons.Outlined.GridView),
    FOCUS("Фокус", Icons.Outlined.Timer),
    HABITS("Привычки", Icons.Outlined.Loop),
    SEARCH("Поиск", Icons.Outlined.Search),
    SETTINGS("Настройки", Icons.Outlined.Settings),
}

fun OptionalTab.homeTab(): HomeTab = when (this) {
    OptionalTab.MATRIX -> HomeTab.MATRIX
    OptionalTab.FOCUS -> HomeTab.FOCUS
    OptionalTab.HABITS -> HomeTab.HABITS
}

/** Like TickTick: Tasks and Calendar first, the user's extra sections, Settings last. */
fun barTabs(optional: Set<OptionalTab>, withSearch: Boolean): List<HomeTab> = buildList {
    add(HomeTab.TASKS)
    add(HomeTab.CALENDAR)
    OptionalTab.entries.filter { it in optional }.forEach { add(it.homeTab()) }
    if (withSearch) add(HomeTab.SEARCH)
    add(HomeTab.SETTINGS)
}

/** Phones: icons without captions, as in TickTick. */
@Composable
fun AppBottomBar(tabs: List<HomeTab>, selected: HomeTab, today: Int, onSelect: (HomeTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        for (t in tabs) {
            NavigationBarItem(
                selected = selected == t,
                onClick = { onSelect(t) },
                icon = { TabIcon(t, today) },
            )
        }
    }
}

/** Tablets: a narrow column of icons on the left. */
@Composable
fun AppRail(tabs: List<HomeTab>, selected: HomeTab, today: Int, onSelect: (HomeTab) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Spacer(Modifier.height(8.dp))
        for (t in tabs) {
            NavigationRailItem(
                selected = selected == t,
                onClick = { onSelect(t) },
                icon = { TabIcon(t, today) },
            )
        }
    }
}

@Composable
private fun TabIcon(tab: HomeTab, today: Int) {
    val icon = tab.icon
    if (icon != null) Icon(icon, tab.label) else CalendarDayIcon(today, tab.label)
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
