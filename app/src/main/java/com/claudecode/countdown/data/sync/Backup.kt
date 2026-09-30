package com.claudecode.countdown.data.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * A backup is one JSON file with every synced table. Restoring merges it into the current data
 * (the newer version of each record wins), so nothing made after the backup is lost.
 */
object Backup {
    private const val FORMAT = "tiktak-backup"
    private const val VERSION = 1

    fun export(store: RowStore, appVersion: String, at: Long = System.currentTimeMillis()): String {
        val tables = JSONObject()
        for (t in SyncTable.entries) tables.put(t.table, JSONArray(store.all(t)))
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("app", appVersion)
            .put("exportedAt", at)
            .put("tables", tables)
            .toString()
    }

    class NotABackup : Exception("Это не файл резервной копии Tik Tak")

    /** Returns how many records were added or updated. */
    fun import(store: RowStore, text: String): Int {
        val root = runCatching { JSONObject(text) }.getOrNull()
        if (root?.optString("format") != FORMAT) throw NotABackup()
        val tables = root.getJSONObject("tables")
        var changed = 0
        // Parents first, so a list exists before its tasks (the app copes either way).
        for (t in SyncTable.entries) {
            val rows = tables.optJSONArray(t.table) ?: continue
            changed += store.merge(t, List(rows.length()) { rows.getJSONObject(it) }).changed
        }
        return changed
    }
}
