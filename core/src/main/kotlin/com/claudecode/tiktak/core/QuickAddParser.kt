package com.claudecode.tiktak.core

import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

data class QuickAddResult(
    val title: String,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    /** 0..3, null when not mentioned. */
    val priority: Int? = null,
    val tags: List<String> = emptyList(),
    val listName: String? = null,
    val repeat: RepeatRule? = null,
    /** Recognized fragments, in the order they were found, for highlighting. */
    val recognized: List<String> = emptyList(),
)

/**
 * Pulls dates, times, repeats, priority, #tags and ~list out of a Russian or English one-liner,
 * e.g. "завтра в 10 позвонить !высокий #работа". Whatever is left becomes the title.
 */
class QuickAddParser(private val today: LocalDate, private val now: LocalTime) {

    private class Scan(var text: String) {
        val recognized = mutableListOf<String>()

        /** Removes the first match that [accept]s and returns it. */
        fun take(regex: Regex, accept: (MatchResult) -> Boolean = { true }): MatchResult? {
            for (m in regex.findAll(text)) {
                if (!accept(m)) continue
                recognized += m.value.trim()
                text = text.replaceRange(m.range, " ")
                return m
            }
            return null
        }
    }

    fun parse(input: String): QuickAddResult {
        val s = Scan(" $input ")
        var date: LocalDate? = null
        var time: LocalTime? = null

        val tags = mutableListOf<String>()
        while (true) {
            val m = s.take(TAG) ?: break
            tags += m.groupValues[1]
        }
        val listName = s.take(LIST)?.groupValues?.get(1)
        val priority = s.take(PRIORITY)?.let { priorityOf(it.groupValues[1]) }

        val repeat = parseRepeat(s)

        s.take(RELATIVE_NUM) { m -> m.groupValues[1].toInt() in 1..999 }?.let { m ->
            val (d, t) = relative(m.groupValues[1].toLong(), m.groupValues[2])
            date = d; time = t
        } ?: s.take(RELATIVE_WORD)?.let { m ->
            val (d, t) = relative(1, m.groupValues[1])
            date = d; time = t
        }

        if (date == null) date = parseDate(s)
        if (time == null) time = parseTime(s)

        val repeatStart = repeat?.let { firstOccurrence(it) }
        val finalDate = when {
            date != null -> date
            repeatStart != null -> repeatStart
            time != null -> if (time!! > now) today else today.plusDays(1)
            else -> null
        }

        val title = s.text
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(',', ';', '-', '—', ':')
            .trim()

        return QuickAddResult(
            title = title,
            date = finalDate,
            time = time,
            priority = priority,
            tags = tags,
            listName = listName,
            repeat = repeat,
            recognized = s.recognized,
        )
    }

    // --- Repeat ---

    private fun parseRepeat(s: Scan): RepeatRule? {
        s.take(REPEAT_WEEKDAYS)?.let { return RepeatRule(Freq.WEEKLY, byDay = RepeatRule.WEEKDAYS.map { WeekdayNum(it) }) }
        s.take(REPEAT_WEEKENDS)?.let { return RepeatRule(Freq.WEEKLY, byDay = listOf(WeekdayNum(SATURDAY), WeekdayNum(SUNDAY))) }
        s.take(REPEAT_ON_DAYS)?.let { m ->
            val days = dayNamesIn(m.groupValues[1])
            if (days.isNotEmpty()) return RepeatRule(Freq.WEEKLY, byDay = days.sorted().map { WeekdayNum(it) })
        }
        s.take(REPEAT_EVERY_N) { it.groupValues[1].toInt() in 1..99 }?.let { m ->
            return RepeatRule(freqOf(m.groupValues[2]), interval = m.groupValues[1].toInt())
        }
        s.take(REPEAT_SIMPLE)?.let { m -> return RepeatRule(freqOf(m.groupValues[1].ifEmpty { m.groupValues[2] })) }
        return null
    }

    private fun freqOf(word: String): Freq {
        val w = word.lowercase()
        return when {
            w.startsWith("д") || w.startsWith("ежедн") || w.startsWith("day") || w == "daily" -> Freq.DAILY
            w.startsWith("нед") || w.startsWith("еженед") || w.startsWith("week") -> Freq.WEEKLY
            w.startsWith("мес") || w.startsWith("ежемес") || w.startsWith("month") -> Freq.MONTHLY
            else -> Freq.YEARLY
        }
    }

    private fun firstOccurrence(rule: RepeatRule): LocalDate {
        val days = rule.byDay.map { it.day }
        if (rule.freq == Freq.WEEKLY && days.isNotEmpty()) {
            return days.map { today.with(TemporalAdjusters.nextOrSame(it)) }.min()
        }
        return today
    }

    // --- Relative "через 2 часа" / "in 3 days" ---

    private fun relative(n: Long, unitWord: String): Pair<LocalDate, LocalTime?> {
        val u = unitWord.lowercase()
        val at = LocalDateTime.of(today, now)
        fun dt(x: LocalDateTime) = x.toLocalDate() to x.toLocalTime().withSecond(0).withNano(0)
        return when {
            u.startsWith("полчас") || u.startsWith("half") -> dt(at.plusMinutes(30))
            u.startsWith("мин") || u.startsWith("min") -> dt(at.plusMinutes(n))
            u.startsWith("ч") || u.startsWith("hour") || u.startsWith("hr") -> dt(at.plusHours(n))
            u.startsWith("д") || u.startsWith("day") -> today.plusDays(n) to null
            u.startsWith("нед") || u.startsWith("week") -> today.plusWeeks(n) to null
            u.startsWith("мес") || u.startsWith("month") -> today.plusMonths(n) to null
            else -> today.plusYears(n) to null
        }
    }

    // --- Dates ---

    private fun parseDate(s: Scan): LocalDate? {
        s.take(DAY_AFTER_TOMORROW)?.let { return today.plusDays(2) }
        s.take(TOMORROW)?.let { return today.plusDays(1) }
        s.take(TODAY)?.let { return today }
        s.take(NEXT_WEEK)?.let { return today.with(TemporalAdjusters.next(MONDAY)) }
        s.take(WEEKEND)?.let { return today.with(TemporalAdjusters.nextOrSame(SATURDAY)) }
        s.take(NEXT_WEEKDAY)?.let { m ->
            dayNamesIn(m.groupValues[1]).firstOrNull()?.let { return today.with(TemporalAdjusters.next(it)) }
        }
        s.take(WEEKDAY) { dayNamesIn(it.value).isNotEmpty() }?.let { m ->
            return today.with(TemporalAdjusters.nextOrSame(dayNamesIn(m.value).first()))
        }
        s.take(DAY_MONTH) { validDate(it.groupValues[1].toInt(), monthOf(it.groupValues[2]), it.groupValues[3]) != null }?.let { m ->
            return validDate(m.groupValues[1].toInt(), monthOf(m.groupValues[2]), m.groupValues[3])
        }
        s.take(MONTH_DAY) { validDate(it.groupValues[2].toInt(), monthOf(it.groupValues[1]), it.groupValues[3]) != null }?.let { m ->
            return validDate(m.groupValues[2].toInt(), monthOf(m.groupValues[1]), m.groupValues[3])
        }
        s.take(NUMERIC_DATE) { validDate(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3]) != null }?.let { m ->
            return validDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3])
        }
        return null
    }

    /** Date without a year means the next such date, today included. */
    private fun validDate(day: Int, month: Int, yearText: String): LocalDate? {
        if (month !in 1..12) return null
        val explicitYear = yearText.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
        val year = explicitYear ?: today.year
        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return if (explicitYear == null && date < today) runCatching { LocalDate.of(year + 1, month, day) }.getOrNull() else date
    }

    // --- Times ---

    private fun parseTime(s: Scan): LocalTime? {
        s.take(NOON)?.let { return LocalTime.NOON }
        s.take(TIME_WITH_PREP) { timeOf(it) != null }?.let { return timeOf(it) }
        s.take(TIME_COLON) { timeOf(it) != null }?.let { return timeOf(it) }
        s.take(TIME_SUFFIX) { timeOf(it) != null }?.let { return timeOf(it) }
        s.take(PART_OF_DAY)?.let { m ->
            val w = m.groupValues[1].lowercase()
            return when {
                w.startsWith("утр") || w.startsWith("morning") -> LocalTime.of(9, 0)
                w.startsWith("дн") || w.startsWith("afternoon") -> LocalTime.of(14, 0)
                w.startsWith("ноч") || w.contains("night") && !w.startsWith("to") -> LocalTime.of(22, 0)
                else -> LocalTime.of(19, 0)
            }
        }
        return null
    }

    /** Groups: 1 = hour, 2 = minutes (optional), 3 = am/pm/утра/вечера (optional). */
    private fun timeOf(m: MatchResult): LocalTime? {
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: 0
        val suffix = m.groupValues[3].lowercase().replace(".", "")
        if (min !in 0..59) return null
        when (suffix) {
            "pm", "вечера", "дня" -> { if (h !in 1..12) return null; if (h < 12) h += 12 }
            "am", "утра" -> { if (h !in 1..12) return null; if (h == 12) h = 0 }
            "ночи" -> { if (h !in 0..12) return null; if (h == 12) h = 0 }
        }
        if (h !in 0..23) return null
        return LocalTime.of(h, min)
    }

    private fun priorityOf(raw: String): Int = when (raw.lowercase()) {
        "!!!", "!высокий", "!высокая", "!high", "!3" -> 3
        "!!", "!средний", "!средняя", "!medium", "!med", "!2" -> 2
        else -> 1
    }

    companion object {
        private const val L = "(?<![\\p{L}\\p{N}])"
        private const val R = "(?![\\p{L}\\p{N}])"
        private fun rx(p: String) = Regex("(?iu)$p")

        private val DAY_FORMS: Map<DayOfWeek, List<String>> = mapOf(
            MONDAY to listOf("понедельник", "понедельникам", "monday", "mondays", "mon"),
            TUESDAY to listOf("вторник", "вторникам", "tuesday", "tuesdays", "tue", "tues"),
            WEDNESDAY to listOf("среда", "среду", "средам", "wednesday", "wednesdays", "wed"),
            THURSDAY to listOf("четверг", "четвергам", "thursday", "thursdays", "thu", "thur", "thurs"),
            FRIDAY to listOf("пятница", "пятницу", "пятницам", "friday", "fridays", "fri"),
            SATURDAY to listOf("суббота", "субботу", "субботам", "saturday", "saturdays", "sat"),
            SUNDAY to listOf("воскресенье", "воскресеньям", "sunday", "sundays", "sun"),
        )
        private val SHORT_RU = mapOf(
            "пн" to MONDAY, "вт" to TUESDAY, "ср" to WEDNESDAY, "чт" to THURSDAY, "пт" to FRIDAY, "сб" to SATURDAY, "вс" to SUNDAY,
        )
        private val ALL_DAY_WORDS: Map<String, DayOfWeek> =
            DAY_FORMS.flatMap { (d, forms) -> forms.map { it to d } }.toMap() + SHORT_RU
        private val DAY_ALT = ALL_DAY_WORDS.keys.sortedByDescending { it.length }.joinToString("|")
        private val FULL_DAY_ALT = DAY_FORMS.values.flatten().filter { it.length > 3 }.sortedByDescending { it.length }.joinToString("|")

        private fun dayNamesIn(text: String): List<DayOfWeek> =
            Regex("(?iu)$L($DAY_ALT)$R").findAll(text).mapNotNull { ALL_DAY_WORDS[it.value.lowercase()] }.distinct().toList()

        private val MONTHS: List<List<String>> = listOf(
            listOf("января", "январь", "янв", "january", "jan"),
            listOf("февраля", "февраль", "фев", "february", "feb"),
            listOf("марта", "март", "мар", "march", "mar"),
            listOf("апреля", "апрель", "апр", "april", "apr"),
            listOf("мая", "май", "may"),
            listOf("июня", "июнь", "июн", "june", "jun"),
            listOf("июля", "июль", "июл", "july", "jul"),
            listOf("августа", "август", "авг", "august", "aug"),
            listOf("сентября", "сентябрь", "сен", "сент", "september", "sept", "sep"),
            listOf("октября", "октябрь", "окт", "october", "oct"),
            listOf("ноября", "ноябрь", "ноя", "november", "nov"),
            listOf("декабря", "декабрь", "дек", "december", "dec"),
        )
        private val MONTH_ALT = MONTHS.flatten().sortedByDescending { it.length }.joinToString("|")
        private fun monthOf(word: String): Int = MONTHS.indexOfFirst { word.lowercase().removeSuffix(".") in it } + 1

        private val TAG = rx("(?<=\\s)#([\\p{L}\\p{N}_][\\p{L}\\p{N}_\\-/]*)")
        private val LIST = rx("(?<=\\s)~([\\p{L}\\p{N}_][\\p{L}\\p{N}_\\-]*)")
        private val PRIORITY = rx("(?<=\\s)(!!!|!!|!(?:высокий|высокая|средний|средняя|низкий|низкая|high|medium|med|low|[1-3])?)(?=\\s)")

        private val REPEAT_WEEKDAYS = rx("$L(по будням|каждый будний день|по рабочим дням|every weekday|weekdays)$R")
        private val REPEAT_WEEKENDS = rx("$L(по выходным|every weekend|weekends)$R")
        private val REPEAT_ON_DAYS = rx("$L(?:каждый|каждую|каждое|по|every)\\s+((?:$DAY_ALT)(?:\\s*(?:,|и|and)\\s*(?:$DAY_ALT))*)$R")
        private val REPEAT_EVERY_N = rx("$L(?:каждые|каждый|каждую|every)\\s+(\\d{1,2})\\s+(дн(?:я|ей)|день|недел(?:и|ь|ю)|месяц(?:а|ев)?|год(?:а)?|лет|days?|weeks?|months?|years?)$R")
        private val REPEAT_SIMPLE = rx("$L(?:(?:каждый|каждую|every)\\s+(день|неделю|месяц|год|day|week|month|year)|(ежедневно|еженедельно|ежемесячно|ежегодно|daily|weekly|monthly|yearly|annually))$R")

        private val RELATIVE_NUM = rx("$L(?:через|in)\\s+(\\d{1,3})\\s+(минут[уы]?|мин|час(?:а|ов)?|ч|дн(?:я|ей)|день|недел(?:ю|и|ь)|месяц(?:а|ев)?|год(?:а)?|лет|minutes?|mins?|hours?|hrs?|days?|weeks?|months?|years?)$R")
        private val RELATIVE_WORD = rx("$L(?:через|in)\\s+(?:an?\\s+|one\\s+)?(полчаса|минуту|час|день|неделю|месяц|год|half an hour|minute|hour|day|week|month|year)$R")

        private val DAY_AFTER_TOMORROW = rx("$L(послезавтра|day after tomorrow)$R")
        private val TOMORROW = rx("$L(завтра|tomorrow|tmrw|tmr)$R")
        private val TODAY = rx("$L(сегодня|today)$R")
        private val NEXT_WEEK = rx("$L(на следующей неделе|next week)$R")
        private val WEEKEND = rx("$L(в выходные|на выходных|this weekend|on the weekend)$R")
        private val NEXT_WEEKDAY = rx("$L(?:в\\s+|во\\s+)?(?:следующий|следующую|следующее|next)\\s+($DAY_ALT)$R")
        private val WEEKDAY = rx("$L(?:(?:в|во|on|this)\\s+($DAY_ALT)|($FULL_DAY_ALT))$R")
        private val DAY_MONTH = rx("$L(\\d{1,2})\\s+($MONTH_ALT)\\.?(?:\\s+(\\d{4}))?$R")
        private val MONTH_DAY = rx("$L($MONTH_ALT)\\.?\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?$R")
        private val NUMERIC_DATE = rx("$L(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{4}|\\d{2}))?(?![\\p{L}\\p{N}]|[.:/]\\d)")

        private val NOON = rx("$L(в полдень|at noon|noon)$R")
        private val TIME_WITH_PREP = rx("$L(?:в|к|at|@)\\s*(\\d{1,2})(?:[:.](\\d{2}))?(?:\\s*(утра|дня|вечера|ночи|am|pm|a\\.m\\.|p\\.m\\.))?$R")
        private val TIME_COLON = rx("$L(\\d{1,2}):(\\d{2})(?:\\s*(am|pm))?$R")
        private val TIME_SUFFIX = rx("$L(\\d{1,2})()\\s*(утра|дня|вечера|ночи|am|pm)$R")
        private val PART_OF_DAY = rx("$L(утром|днём|днем|вечером|ночью|in the morning|morning|in the afternoon|afternoon|in the evening|evening|tonight|at night)$R")
    }
}
