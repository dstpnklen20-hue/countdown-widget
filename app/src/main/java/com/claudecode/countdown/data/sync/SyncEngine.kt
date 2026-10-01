package com.claudecode.countdown.data.sync

import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** One record on the server. [serverAt] is when the server stored it; pulls go in that order. */
data class RemoteRecord(
    val kind: String,
    val id: String,
    val updatedAt: Long,
    val deleted: Boolean,
    val data: JSONObject,
    val serverAt: Instant? = null,
)

/** The server side of sync; [SupabaseApi] talks to Supabase, tests use an in-memory fake. */
interface SyncApi {
    /** Stores records; the server keeps an existing record when it is as new or newer. */
    suspend fun upsert(records: List<RemoteRecord>)

    /** Removes records for good (tasks purged from the trash, check-ins that lost a merge). */
    suspend fun delete(kind: String, ids: List<String>)

    /** Records stored after [since] (all when null), oldest first, at most [limit]. */
    suspend fun pull(since: Instant?, limit: Int): List<RemoteRecord>
}

/** What a device remembers between runs. */
interface SyncMarks {
    /** Device time before the last successful push: rows written since then go next time. */
    var pushedUpTo: Long
    /** Server time of the newest record pulled so far. */
    var pulledUpTo: Instant?
    /** "table|id" of records to remove from the server. */
    var pendingDeletes: Set<String>
}

/**
 * One sync run: push local changes, then pull everyone else's and merge them (the newer version of
 * a record wins). Safe to repeat: every step is idempotent.
 */
class SyncEngine(
    private val store: RowStore,
    private val api: SyncApi,
    private val marks: SyncMarks,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val BATCH = 500
        const val PAGE = 1000
        private const val PUSH_MARGIN_MS = 10_000L
        /** Records committed slightly out of order are caught by re-reading this much. */
        private val OVERLAP: Duration = Duration.ofSeconds(60)
    }

    /**
     * Returns how many records changed on this device. Copies of one thing made on two devices
     * are merged after the pull (see [Deduplicator]); [dedupeAll] merges every such group, not
     * only those with a newly arrived row.
     */
    suspend fun run(dedupeAll: Boolean = false): Int {
        push()
        val pulled = pull()
        if (pulled.superseded.isNotEmpty()) {
            marks.pendingDeletes = marks.pendingDeletes + pulled.superseded.map { "${it.table.table}|${it.id}" }
            pushDeletes()
        }
        val merged = store.dedupe(if (dedupeAll) null else pulled.arrived, clock)
        // The merge is a local edit: send it now rather than on the next run.
        if (merged > 0) push()
        return pulled.changed + merged
    }

    fun forget(table: SyncTable, ids: Collection<String>) {
        if (ids.isNotEmpty()) marks.pendingDeletes = marks.pendingDeletes + ids.map { "${table.table}|$it" }
    }

    private suspend fun push() {
        // Captured before reading, so a row edited during the push goes again next time.
        val mark = clock()
        val since = marks.pushedUpTo
        for (table in SyncTable.entries) {
            val records = store.changedSince(table, since).map { row ->
                RemoteRecord(
                    kind = table.table,
                    id = table.idOf(row),
                    updatedAt = row.optLong("updatedAt"),
                    deleted = row.optLong("deleted") != 0L,
                    data = row,
                )
            }
            for (chunk in records.chunked(BATCH)) api.upsert(chunk)
        }
        pushDeletes()
        // A write stamped just before the mark may commit just after the read above: the margin
        // sends such rows again next time (the server ignores versions it already has).
        marks.pushedUpTo = mark - PUSH_MARGIN_MS
    }

    private suspend fun pushDeletes() {
        val pending = marks.pendingDeletes
        if (pending.isEmpty()) return
        for ((kind, keys) in pending.groupBy { it.substringBefore('|') }) {
            for (chunk in keys.map { it.substringAfter('|') }.chunked(100)) api.delete(kind, chunk)
        }
        marks.pendingDeletes = marks.pendingDeletes - pending
    }

    private class Pulled(val changed: Int, val superseded: List<Superseded>, val arrived: Map<SyncTable, Set<String>>)

    private suspend fun pull(): Pulled {
        var since = marks.pulledUpTo?.minus(OVERLAP)
        var newest = marks.pulledUpTo
        var changed = 0
        val superseded = mutableListOf<Superseded>()
        val arrived = HashMap<SyncTable, MutableSet<String>>()
        while (true) {
            val page = api.pull(since, PAGE)
            val byTable = page.groupBy { SyncTable.byTable(it.kind) }
            // Parents before children, in the order the tables are declared.
            for (table in SyncTable.entries) {
                val rows = byTable[table] ?: continue
                val result = store.merge(table, rows.map { it.data })
                changed += result.changed
                superseded += result.superseded
                arrived.getOrPut(table) { HashSet() } += result.arrived
            }
            page.lastOrNull()?.serverAt?.let { last ->
                since = last
                if (newest == null || last > newest) newest = last
            }
            if (page.size < PAGE) break
        }
        marks.pulledUpTo = newest
        return Pulled(changed, superseded, arrived)
    }
}
