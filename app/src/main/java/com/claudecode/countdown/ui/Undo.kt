package com.claudecode.countdown.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * App-wide "Undo" offers after destructive actions. The activity shows them in one shared
 * snackbar, so an offer made on a screen that closes right away (task details) still appears
 * on the screen below it.
 */
class UndoBus(private val scope: CoroutineScope) {
    private class Offer(val message: String, val undo: suspend () -> Unit)

    private val offers = MutableSharedFlow<Offer>(extraBufferCapacity = 8)

    fun offer(message: String, undo: suspend () -> Unit) {
        offers.tryEmit(Offer(message, undo))
    }

    /** Shows offers one at a time; a newer offer replaces the one on screen. */
    suspend fun showIn(host: SnackbarHostState) {
        offers.collectLatest { offer ->
            val result = host.showSnackbar(
                message = offer.message,
                actionLabel = "Отменить",
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            // The app scope outlives the screen, so undo completes even if the user navigates away.
            if (result == SnackbarResult.ActionPerformed) scope.launch { offer.undo() }
        }
    }
}

val LocalSnackbarHost = staticCompositionLocalOf { SnackbarHostState() }

/** Put into every screen's Scaffold so the shared snackbar sits above that screen's FAB and bars. */
@Composable
fun AppSnackbarHost() = SnackbarHost(LocalSnackbarHost.current)
