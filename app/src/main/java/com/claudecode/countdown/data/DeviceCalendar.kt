package com.claudecode.countdown.data

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import com.claudecode.countdown.data.db.AppDatabase
import com.claudecode.countdown.domain.ExportedEvent
import com.claudecode.countdown.domain.exportedEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A calendar of the phone that entries can be written to (a Google account's, or one on the phone only). */
data class PhoneCalendar(val id: Long, val name: String, val account: String, val accountType: String, val color: Int) {
    val local: Boolean get() = accountType == CalendarContract.ACCOUNT_TYPE_LOCAL
}

/**
 * One-way export of Tik Tak's events and dated tasks into a calendar of the phone. A Google
 * calendar takes them to Google Calendar everywhere; the watch (Huawei Health and the like) reads
 * the phone's calendars, so they show there too. Every exported event is marked with this app's
 * package and the task's id ([Events.CUSTOM_APP_URI]), so it is found again, updated and removed;
 * events the user made in that calendar are left alone. Changes made in the calendar are
 * overwritten by the next export: Tik Tak is the source.
 */
class DeviceCalendar(
    private val context: Context,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val PREFS = "device_calendar"
        private const val KEY_CALENDAR = "calendarId"
        private const val KEY_LAST = "lastExport"
        private const val URI_PREFIX = "tiktak://task/"
        private const val LOCAL_ACCOUNT = "Tik Tak"
        private const val DEBOUNCE_MS = 3_000L
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val mutex = Mutex()
    private var pending: Job? = null

    private val _target = MutableStateFlow(prefs.getLong(KEY_CALENDAR, -1L).takeIf { it >= 0 })
    /** Id of the calendar entries go to; null when export is off. */
    val target: StateFlow<Long?> = _target.asStateFlow()

    private val _status = MutableStateFlow(prefs.getString(KEY_LAST, null))
    /** What the last export did, for the settings screen. */
    val status: StateFlow<String?> = _status.asStateFlow()

    fun allowed(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Calendars of the phone this app may write to, Google ones first. */
    fun calendars(): List<PhoneCalendar> {
        if (!allowed()) return emptyList()
        val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE, Calendars.CALENDAR_COLOR)
        return runCatching {
            context.contentResolver.query(
                Calendars.CONTENT_URI, projection,
                "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.SYNC_EVENTS} = 1",
                arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) add(PhoneCalendar(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "", c.getInt(4)))
                }
            }.orEmpty().sortedWith(compareBy({ it.local }, { it.account }, { it.name }))
        }.getOrDefault(emptyList())
    }

    /** A calendar "Tik Tak" on the phone only (not in any account): for the watch without Google. */
    fun createLocal(): Long? {
        if (!allowed()) return null
        calendars().firstOrNull { it.local && it.account == LOCAL_ACCOUNT }?.let { return it.id }
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, LOCAL_ACCOUNT)
            put(Calendars.CALENDAR_DISPLAY_NAME, LOCAL_ACCOUNT)
            put(Calendars.CALENDAR_COLOR, 0xFF4A72F5.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, java.util.TimeZone.getDefault().id)
        }
        return runCatching { context.contentResolver.insert(asLocalSyncAdapter(Calendars.CONTENT_URI), values)?.lastPathSegment?.toLong() }.getOrNull()
    }

    /** Starts sending entries to calendar [id], or stops (null). Entries sent to the previous calendar are taken back. */
    fun setTarget(id: Long?) {
        val old = _target.value
        prefs.edit().apply { if (id == null) remove(KEY_CALENDAR) else putLong(KEY_CALENDAR, id) }.apply()
        _target.value = id
        scope.launch {
            mutex.withLock { if (old != null && old != id) runCatching { removeAll(old) } }
            if (id != null) export()
        }
    }

    /** Exports a few seconds after the last change (a burst of edits writes once). */
    fun schedule() {
        if (_target.value == null) return
        pending?.cancel()
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            export()
        }
    }

    suspend fun export() = mutex.withLock {
        val calendar = _target.value ?: return@withLock
        if (!allowed()) return@withLock report("Нет разрешения на календарь")
        runCatching {
            val local = calendars().firstOrNull { it.id == calendar } ?: return@withLock report("Календарь не найден — выберите другой")
            val wanted = exportedEvents(db.taskDao().topLevel(), System.currentTimeMillis())
            val existing = ours(calendar)
            val ops = ArrayList<ContentProviderOperation>()
            val wrote = ArrayList<ExportedEvent>()
            var added = 0
            for (e in wanted) {
                val found = existing.remove(e.key)
                if (found == null) {
                    ops += ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(values(e, calendar)).build()
                    wrote += e
                    added++
                } else if (written(e.key) != e.fingerprint) {
                    ops += ContentProviderOperation.newUpdate(eventUri(found, local.local)).withValues(values(e, calendar)).build()
                    wrote += e
                }
            }
            // What Tik Tak no longer has (deleted, done, too old).
            for (id in existing.values) ops += ContentProviderOperation.newDelete(eventUri(id, local.local)).build()
            for (chunk in ops.chunked(200)) context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(chunk))
            prefs.edit().apply {
                for (e in wrote) putString("fp:${e.key}", e.fingerprint)
                for (key in existing.keys) remove("fp:$key")
            }.apply()
            val changed = wrote.size - added
            report(
                if (ops.isEmpty()) "В календаре «${local.name}»: ${entries(wanted.size)}, всё совпадает"
                else "В календаре «${local.name}»: ${entries(wanted.size)} (новых $added, изменено $changed, убрано ${existing.size})"
            )
        }.onFailure { report("Не удалось записать в календарь: ${it.message}") }
    }

    private fun entries(n: Int) = "$n " + com.claudecode.countdown.pluralRu(n.toLong(), "запись", "записи", "записей")

    private fun report(text: String) {
        val stamped = "$text · ${java.time.LocalTime.now().withNano(0).withSecond(0)}"
        prefs.edit().putString(KEY_LAST, stamped).apply()
        _status.value = stamped
    }

    /** Events this app put into [calendar]: task id → event id. */
    private fun ours(calendar: Long): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        context.contentResolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID, Events.CUSTOM_APP_URI),
            "${Events.CALENDAR_ID} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.DELETED} = 0",
            arrayOf(calendar.toString(), context.packageName), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val key = c.getString(1)?.removePrefix(URI_PREFIX) ?: continue
                // A second copy of one entry (left from an interrupted export) goes away.
                out.put(key, c.getLong(0))?.let { extra ->
                    context.contentResolver.delete(Events.CONTENT_URI.buildUpon().appendPath(extra.toString()).build(), null, null)
                }
            }
        }
        return out
    }

    /**
     * What was last written for each task, so unchanged entries are not written again. Kept in
     * this app's prefs: the sync columns of a Google calendar belong to Google.
     */
    private fun written(key: String): String? = prefs.getString("fp:$key", null)

    private fun values(e: ExportedEvent, calendar: Long) = ContentValues().apply {
        put(Events.CALENDAR_ID, calendar)
        put(Events.TITLE, e.title)
        put(Events.DESCRIPTION, e.description)
        put(Events.EVENT_LOCATION, e.location)
        put(Events.DTSTART, e.start)
        if (e.end != null) put(Events.DTEND, e.end) else putNull(Events.DTEND)
        if (e.duration != null) put(Events.DURATION, e.duration) else putNull(Events.DURATION)
        put(Events.ALL_DAY, if (e.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, e.timeZone)
        if (e.rrule != null) put(Events.RRULE, e.rrule) else putNull(Events.RRULE)
        if (e.exDate != null) put(Events.EXDATE, e.exDate) else putNull(Events.EXDATE)
        // Reminders come from Tik Tak itself; the copy must not ring a second time.
        put(Events.HAS_ALARM, 0)
        put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
        put(Events.CUSTOM_APP_PACKAGE, context.packageName)
        put(Events.CUSTOM_APP_URI, URI_PREFIX + e.key)
    }

    private fun eventUri(id: Long, local: Boolean): Uri {
        val uri = Events.CONTENT_URI.buildUpon().appendPath(id.toString()).build()
        // A phone-only calendar has no sync adapter to finish a deletion: delete as its adapter.
        return if (local) asLocalSyncAdapter(uri) else uri
    }

    private fun asLocalSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    private fun removeAll(calendar: Long) {
        val local = calendars().firstOrNull { it.id == calendar }?.local ?: false
        for ((key, id) in ours(calendar)) {
            context.contentResolver.delete(eventUri(id, local), null, null)
            prefs.edit().remove("fp:$key").apply()
        }
    }
}
