package com.claudecode.countdown.ui

import androidx.compose.ui.graphics.Color
import com.claudecode.countdown.data.db.Priority
import com.claudecode.countdown.data.db.Task
import com.claudecode.countdown.domain.GroupKind
import com.claudecode.countdown.domain.TaskGroup
import com.claudecode.countdown.domain.dueDay
import com.claudecode.countdown.domain.localTimeOf
import com.claudecode.countdown.pluralRu
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

private val ru = Locale("ru")
private val dayMonth = DateTimeFormatter.ofPattern("d MMM", ru)
private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", ru)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", ru)

fun formatDay(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Сегодня"
    today.plusDays(1) -> "Завтра"
    today.minusDays(1) -> "Вчера"
    else -> when {
        day > today && day < today.plusDays(7) -> day.dayOfWeek.getDisplayName(TextStyle.FULL, ru).replaceFirstChar { it.uppercase() }
        day.year == today.year -> day.format(dayMonth)
        else -> day.format(dayMonthYear)
    }
}

fun formatDue(task: Task, today: LocalDate): String? {
    val day = task.dueDay() ?: return null
    val date = formatDay(day, today)
    return if (task.isAllDay) date else "$date, ${localTimeOf(task.dueAt!!).format(timeFmt)}"
}

fun formatTime(millis: Long): String = localTimeOf(millis).format(timeFmt)

fun formatFullDate(day: LocalDate): String =
    day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", ru)).replaceFirstChar { it.uppercase() }

fun groupTitle(group: TaskGroup, today: LocalDate): String = when (group.kind) {
    GroupKind.OVERDUE -> "Просрочено"
    GroupKind.TODAY -> "Сегодня"
    GroupKind.TOMORROW -> "Завтра"
    GroupKind.DAY -> group.date?.let { formatDay(it, today) } ?: ""
    GroupKind.LATER -> "Позже"
    GroupKind.NO_DATE -> "Без даты"
    GroupKind.DONE -> "Выполнено"
}

/** Short countdown like "3 дня 4 ч", "5 ч 12 мин" or "12:05" in the last hour. */
fun formatCountdown(remainingMs: Long, withSeconds: Boolean = false): String {
    if (remainingMs <= 0) return "Наступило"
    val days = TimeUnit.MILLISECONDS.toDays(remainingMs)
    val hours = TimeUnit.MILLISECONDS.toHours(remainingMs) % 24
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60
    return when {
        days > 0 -> "$days ${pluralRu(days, "день", "дня", "дней")} $hours ч"
        hours > 0 -> if (withSeconds) "%d:%02d:%02d".format(hours, minutes, seconds) else "$hours ч $minutes мин"
        else -> if (withSeconds) "%02d:%02d".format(minutes, seconds) else "$minutes мин"
    }
}

fun priorityColor(priority: Int, fallback: Color): Color = when (priority) {
    Priority.HIGH -> Color(0xFFE53935)
    Priority.MEDIUM -> Color(0xFFFB8C00)
    Priority.LOW -> Color(0xFF1E88E5)
    else -> fallback
}

fun priorityName(priority: Int): String = when (priority) {
    Priority.HIGH -> "Высокий"
    Priority.MEDIUM -> "Средний"
    Priority.LOW -> "Низкий"
    else -> "Без приоритета"
}

val LIST_COLORS: List<Int> = listOf(
    0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047, 0xFF00ACC1,
    0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60, 0xFF6D4C41, 0xFF757575,
).map { it.toInt() }
