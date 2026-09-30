package com.claudecode.countdown.data.sync

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.claudecode.countdown.container
import com.claudecode.countdown.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.TimeUnit

data class SyncStatus(
    /** Null when signed out. */
    val email: String? = null,
    val running: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null,
)

/**
 * Keeps the account session and runs [SyncEngine]: a few seconds after any local change, when the
 * app comes to the front, and hourly in the background. [onRemoteChanges] refreshes widgets and
 * reminders after records arrived from other devices.
 */
class SyncManager(
    private val context: Context,
    db: AppDatabase,
    private val store: RowStore,
    private val scope: CoroutineScope,
    private val onRemoteChanges: suspend () -> Unit,
) {
    companion object {
        private const val PREFS = "sync"
        private const val WORK = "tiktak-sync"
        private const val DEBOUNCE_MS = 4_000L

        fun isSignedIn(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("refreshToken")
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var pending: Job? = null

    private val _status = MutableStateFlow(SyncStatus(email = loadSession()?.email, lastSyncAt = prefs.getLong("lastSyncAt", 0).takeIf { it > 0 }))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val marks = object : SyncMarks {
        override var pushedUpTo: Long
            get() = prefs.getLong("pushedUpTo", 0)
            set(v) { prefs.edit().putLong("pushedUpTo", v).apply() }
        override var pulledUpTo: Instant?
            get() = prefs.getString("pulledUpTo", null)?.let { runCatching { Instant.parse(it) }.getOrNull() }
            set(v) { prefs.edit().putString("pulledUpTo", v?.toString()).apply() }
        override var pendingDeletes: Set<String>
            get() = prefs.getStringSet("pendingDeletes", emptySet()).orEmpty().toSet()
            set(v) { prefs.edit().putStringSet("pendingDeletes", v).apply() }
    }

    private val engine = SyncEngine(store, SupabaseApi(::validSession), marks)

    init {
        // Any write to a synced table (from any screen, widget or receiver) schedules a push.
        db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(SyncTable.entries.map { it.table }.toTypedArray()) {
            override fun onInvalidated(tables: Set<String>) {
                if (status.value.email != null) requestSync()
            }
        })
        if (status.value.email != null) schedulePeriodic()
    }

    // --- Session ---

    private fun loadSession(): Session? {
        val refresh = prefs.getString("refreshToken", null) ?: return null
        return Session(
            accessToken = prefs.getString("accessToken", null).orEmpty(),
            refreshToken = refresh,
            expiresAt = prefs.getLong("expiresAt", 0),
            userId = prefs.getString("userId", null).orEmpty(),
            email = prefs.getString("email", null).orEmpty(),
        )
    }

    private fun saveSession(s: Session) {
        // commit(): a refresh token is single-use, losing the new one would sign the user out.
        prefs.edit()
            .putString("accessToken", s.accessToken)
            .putString("refreshToken", s.refreshToken)
            .putLong("expiresAt", s.expiresAt)
            .putString("userId", s.userId)
            .putString("email", s.email)
            .commit()
    }

    private val sessionLock = Mutex()

    /** A session whose access token is good for at least another minute. */
    private suspend fun validSession(forceRefresh: Boolean): Session = sessionLock.withLock {
        val s = loadSession() ?: throw SyncException("Вы не вошли в аккаунт.", authLost = true)
        if (!forceRefresh && s.expiresAt - 60 > System.currentTimeMillis() / 1000) return@withLock s
        SupabaseAuth.refresh(s).also(::saveSession)
    }

    suspend fun signIn(email: String, password: String) {
        startSession(SupabaseAuth.signIn(email, password))
    }

    /** Returns false when the account waits for an email confirmation. */
    suspend fun signUp(email: String, password: String): Boolean {
        val session = SupabaseAuth.signUp(email, password) ?: return false
        startSession(session)
        return true
    }

    private suspend fun startSession(session: Session) {
        // A fresh start: everything on this device goes up, everything in the account comes down.
        prefs.edit().clear().commit()
        saveSession(session)
        _status.value = SyncStatus(email = session.email)
        schedulePeriodic()
        syncNow()
    }

    /** Stops syncing; the data stays on the device. */
    suspend fun signOut() {
        pending?.cancel()
        loadSession()?.let { SupabaseAuth.signOut(it) }
        prefs.edit().clear().commit()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        _status.value = SyncStatus()
    }

    // --- Running ---

    /** Syncs after [delayMs]; calls in the meantime are folded into one run. */
    fun requestSync(delayMs: Long = DEBOUNCE_MS) {
        if (status.value.email == null) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    /** Records removed for good on this device (emptied trash) are removed from the account too. */
    fun forget(table: SyncTable, ids: Collection<String>) {
        if (status.value.email == null) return
        engine.forget(table, ids)
        requestSync()
    }

    /** Returns false when the run failed (the error is in [status]). */
    suspend fun syncNow(): Boolean = mutex.withLock {
        if (loadSession() == null) return@withLock false
        _status.update { it.copy(running = true, error = null) }
        try {
            // Database work may not run on the main thread, and this is called from the UI too.
            val changed = withContext(Dispatchers.IO) { engine.run() }
            val at = System.currentTimeMillis()
            prefs.edit().putLong("lastSyncAt", at).apply()
            _status.update { it.copy(running = false, lastSyncAt = at, error = null) }
            if (changed > 0) onRemoteChanges()
            true
        } catch (e: SyncException) {
            if (e.authLost && e.code != 401) {
                prefs.edit().clear().commit()
                _status.value = SyncStatus(error = e.message)
            } else {
                _status.update { it.copy(running = false, error = e.message) }
            }
            false
        } catch (e: Exception) {
            _status.update { it.copy(running = false, error = "Ошибка синхронизации: ${e.message}") }
            false
        }
    }

    private fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Hourly background sync, so reminders and widgets learn about changes made on other devices. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = applicationContext.container.sync
        return when {
            sync.status.value.email == null -> Result.success()
            sync.syncNow() -> Result.success()
            else -> Result.retry()
        }
    }
}
