package com.claudecode.countdown.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.claudecode.countdown.ui.EMPTY_ARTS
import com.claudecode.countdown.ui.emptyArtIndex
import com.claudecode.countdown.ui.railTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ToolbarTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun overflowGoesUnderMoreOnlyWhenToolsDoNotFit() {
        val five = listOf(Tool.TASKS, Tool.CALENDAR, Tool.MATRIX, Tool.FOCUS, Tool.SETTINGS)
        assertEquals(BarLayout(five, emptyList()), barLayout(five, 5))

        val seven = AppSettings.DEFAULT_TOOLS
        val layout = barLayout(seven, 5)
        // Four tools plus the "More" slot, like TickTick.
        assertEquals(listOf(Tool.TASKS, Tool.CALENDAR, Tool.MATRIX, Tool.FOCUS), layout.visible)
        assertEquals(listOf(Tool.HABITS, Tool.COUNTDOWNS, Tool.SETTINGS), layout.more)
    }

    @Test
    fun tasksStayFirstAndSettingsAlwaysPinned() {
        assertEquals(
            listOf(Tool.TASKS, Tool.MATRIX, Tool.SETTINGS),
            normalizeTools(listOf(Tool.MATRIX, Tool.TASKS, Tool.MATRIX)),
        )
    }

    @Test
    fun railShowsEveryToolWithSettingsLast() {
        val rail = railTools(listOf(Tool.TASKS, Tool.FOCUS, Tool.SETTINGS))
        assertEquals(Tool.entries.toSet(), rail.toSet())
        assertEquals(listOf(Tool.TASKS, Tool.FOCUS), rail.take(2))
        assertEquals(Tool.SETTINGS, rail.last())
    }

    @Test
    fun oldBottomBarChoiceIsKeptOnUpdate() {
        // Version 2.1 stored the optional tabs as a set.
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
            .putStringSet("tabs", setOf("FOCUS", "MATRIX")).commit()
        val settings = AppSettings(context)
        assertEquals(listOf(Tool.TASKS, Tool.CALENDAR, Tool.MATRIX, Tool.FOCUS, Tool.SETTINGS), settings.current.tools)
        // Nothing overflows, so the bar looks exactly as before.
        assertTrue(barLayout(settings.current.tools, settings.current.barLimit).more.isEmpty())
    }

    @Test
    fun pinReorderAndUnpin() {
        val settings = AppSettings(context)
        settings.setPinned(Tool.SEARCH, true)
        assertEquals(Tool.SEARCH, settings.current.tools.last())
        settings.moveTool(Tool.SEARCH, -1)
        assertEquals(Tool.SEARCH, settings.current.tools[settings.current.tools.lastIndex - 1])
        settings.moveTool(Tool.CALENDAR, -1) // cannot pass Tasks
        assertEquals(Tool.TASKS, settings.current.tools.first())
        settings.setPinned(Tool.SETTINGS, false) // fixed
        assertTrue(Tool.SETTINGS in settings.current.tools)
        settings.setPinned(Tool.SEARCH, false)
        assertTrue(Tool.SEARCH !in AppSettings(context).current.tools)
    }

    @Test
    fun emptyPictureIsFixedWhenChosenAndVariesOtherwise() {
        val day = LocalDate.of(2026, 9, 30)
        assertEquals(2, emptyArtIndex(2, "today", day))
        val daily = (0 until 10).map { emptyArtIndex(AppSettings.EMPTY_ART_DAILY, "today", day.plusDays(it.toLong())) }
        assertTrue(daily.all { it in EMPTY_ARTS.indices })
        assertTrue(daily.toSet().size > 1)
    }
}
