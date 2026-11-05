package com.claudecode.countdown.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ListIconTest {
    @Test
    fun anEmojiAtTheStartIsTheIcon() {
        assertEquals(ListName("🏠", "Дом"), splitListName("🏠 Дом"))
        assertEquals(ListName("🏠", "Дом"), splitListName("🏠Дом"))
        assertEquals(ListName("⭐", "Важное"), splitListName("  ⭐ Важное "))
    }

    @Test
    fun wholeSequencesStayTogether() {
        assertEquals(ListName("❤️", "Семья"), splitListName("❤️ Семья"))
        assertEquals(ListName("👨‍💻", "Работа"), splitListName("👨‍💻 Работа"))
        assertEquals(ListName("🏃🏽", "Спорт"), splitListName("🏃🏽 Спорт"))
        assertEquals(ListName("🇷🇺", "Поездка"), splitListName("🇷🇺 Поездка"))
    }

    @Test
    fun aSecondEmojiBelongsToTheName() {
        assertEquals(ListName("🏠", "🏠 Дом"), splitListName("🏠🏠 Дом"))
    }

    @Test
    fun otherNamesStayAsTheyAre() {
        assertEquals(ListName(null, "Дом"), splitListName("Дом"))
        assertEquals(ListName(null, "Дом 🏠"), splitListName("Дом 🏠"))
        assertEquals(ListName(null, "1 курс"), splitListName("1 курс"))
        assertEquals(ListName(null, "🏠"), splitListName("🏠"))
        assertEquals(ListName(null, ""), splitListName(""))
    }
}
