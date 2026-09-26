package com.claudecode.countdown.data

import android.content.Context
import com.claudecode.countdown.model.Countdown
import java.util.UUID

object CountdownRepository {

    private const val PREFS_DATA = "countdowns_data"
    private const val PREFS_WIDGETS = "countdowns_widget_map"
    private const val KEY_IDS = "ids"

    private fun dataPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_DATA, Context.MODE_PRIVATE)

    private fun widgetPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_WIDGETS, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<Countdown> {
        val prefs = dataPrefs(context)
        val ids = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        return ids.mapNotNull { get(context, it) }.sortedBy { it.targetMillis }
    }

    fun get(context: Context, id: String): Countdown? {
        val prefs = dataPrefs(context)
        val title = prefs.getString("title_$id", null) ?: return null
        val target = prefs.getLong("target_$id", -1L)
        if (target < 0) return null
        return Countdown(id, title, target)
    }

    fun save(context: Context, id: String?, title: String, targetMillis: Long): String {
        val prefs = dataPrefs(context)
        val actualId = id ?: UUID.randomUUID().toString()
        val ids = (prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.add(actualId)
        prefs.edit()
            .putStringSet(KEY_IDS, ids)
            .putString("title_$actualId", title)
            .putLong("target_$actualId", targetMillis)
            .apply()
        return actualId
    }

    fun delete(context: Context, id: String) {
        val prefs = dataPrefs(context)
        val ids = (prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()).toMutableSet()
        ids.remove(id)
        prefs.edit()
            .putStringSet(KEY_IDS, ids)
            .remove("title_$id")
            .remove("target_$id")
            .apply()
    }

    fun setWidgetCountdown(context: Context, appWidgetId: Int, countdownId: String) {
        widgetPrefs(context).edit().putString("widget_$appWidgetId", countdownId).apply()
    }

    fun getWidgetCountdownId(context: Context, appWidgetId: Int): String? {
        return widgetPrefs(context).getString("widget_$appWidgetId", null)
    }

    fun removeWidgetMapping(context: Context, appWidgetId: Int) {
        widgetPrefs(context).edit().remove("widget_$appWidgetId").apply()
    }
}
