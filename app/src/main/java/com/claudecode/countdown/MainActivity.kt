package com.claudecode.countdown

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.ui.TikTakTheme
import com.claudecode.countdown.ui.detail.TaskDetailScreen
import com.claudecode.countdown.ui.detail.TaskDetailViewModel
import com.claudecode.countdown.ui.tasks.TaskListScreen
import com.claudecode.countdown.ui.tasks.TasksViewModel

// Keeps its historical name: launchers pin shortcuts to this class.
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TASK_ID = "task_id"

        fun openTaskIntent(context: Context, taskId: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_TASK_ID, taskId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private var palette by mutableStateOf<ThemeManager.Palette?>(null)
    private var pendingTaskId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = ThemeManager.palette(this)
        if (savedInstanceState == null) pendingTaskId = intent.getStringExtra(EXTRA_TASK_ID)

        setContent {
            val p = palette ?: return@setContent
            TikTakTheme(p) {
                val tasksVm: TasksViewModel = viewModel { TasksViewModel(container.tasks) }
                val nav = rememberNavController()
                LaunchedEffect(pendingTaskId) {
                    pendingTaskId?.let { id ->
                        nav.navigate("task/$id")
                        pendingTaskId = null
                    }
                }
                AppNavHost(nav, tasksVm)
            }
        }

        if (savedInstanceState == null) Updater.checkForUpdates(this, manual = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TASK_ID)?.let { pendingTaskId = it }
    }

    override fun onResume() {
        super.onResume()
        // Theme may have changed in SettingsActivity.
        ThemeManager.apply(this)
        palette = ThemeManager.palette(this)
    }

    @androidx.compose.runtime.Composable
    private fun AppNavHost(nav: NavHostController, tasksVm: TasksViewModel) {
        val snapshot by tasksVm.snapshot.collectAsStateWithLifecycle()
        var filterKey by rememberSaveable { mutableStateOf(TaskFilter.Inbox.key) }

        NavHost(nav, startDestination = "home") {
            composable("home") {
                TaskListScreen(
                    vm = tasksVm,
                    snapshot = snapshot,
                    filter = TaskFilter.parse(filterKey),
                    onFilterChange = { filterKey = it.key },
                    onOpenTask = { nav.navigate("task/$it") },
                    onOpenSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                )
            }
            composable("task/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val vm: TaskDetailViewModel = viewModel { TaskDetailViewModel(id, container.tasks, container.appScope) }
                TaskDetailScreen(
                    vm = vm,
                    onBack = { if (!nav.popBackStack()) finish() },
                    onOpenTask = { nav.navigate("task/$it") },
                )
            }
        }
    }
}
