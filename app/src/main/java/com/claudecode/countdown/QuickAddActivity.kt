package com.claudecode.countdown

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.ui.TikTakTheme
import com.claudecode.countdown.ui.tasks.QuickAddSheet
import kotlinx.coroutines.launch

/** Translucent quick-add sheet shown over the home screen by widgets. */
class QuickAddActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_FILTER = "filter"

        fun intent(context: Context, filter: TaskFilter = TaskFilter.Inbox): Intent =
            Intent(context, QuickAddActivity::class.java)
                .putExtra(EXTRA_FILTER, filter.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = TaskFilter.parse(intent.getStringExtra(EXTRA_FILTER) ?: TaskFilter.Inbox.key)
        val palette = ThemeManager.palette(this)
        setContent {
            TikTakTheme(palette) {
                QuickAddSheet(
                    onDismiss = { finish() },
                    onAdd = { text, due, priority ->
                        // App scope: the save must outlive this short-lived activity.
                        container.appScope.launch { container.tasks.quickAdd(text, filter, due, priority) }
                    },
                )
            }
        }
    }
}
