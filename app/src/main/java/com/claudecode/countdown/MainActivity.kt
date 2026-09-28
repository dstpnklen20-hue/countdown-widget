package com.claudecode.countdown

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import com.claudecode.countdown.ui.calendar.CalendarScreen
import com.claudecode.countdown.ui.focus.FocusScreen
import com.claudecode.countdown.ui.habits.HabitsScreen
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.claudecode.countdown.ui.matrix.MatrixScreen
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

enum class HomeTab(val label: String, val icon: ImageVector) {
    TASKS("Задачи", Icons.Outlined.CheckCircle),
    CALENDAR("Календарь", Icons.Outlined.CalendarMonth),
    MATRIX("Матрица", Icons.Outlined.GridView),
    FOCUS("Фокус", Icons.Outlined.Timer),
    HABITS("Привычки", Icons.Outlined.Loop),
}

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
        var tab by rememberSaveable { mutableStateOf(HomeTab.TASKS) }
        val openTask: (String) -> Unit = { nav.navigate("task/$it") }

        NavHost(nav, startDestination = "home") {
            composable("home") {
                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            for (t in HomeTab.entries) {
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(t.icon, null) },
                                    label = { Text(t.label, maxLines = 1) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.padding(padding)) {
                        when (tab) {
                            HomeTab.TASKS -> TaskListScreen(
                                vm = tasksVm,
                                snapshot = snapshot,
                                filter = TaskFilter.parse(filterKey),
                                onFilterChange = { filterKey = it.key },
                                onOpenTask = openTask,
                                onOpenSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                            )
                            HomeTab.CALENDAR -> CalendarScreen(tasksVm, snapshot, openTask)
                            HomeTab.MATRIX -> MatrixScreen(tasksVm, snapshot, openTask)
                            HomeTab.FOCUS -> FocusScreen(snapshot)
                            HomeTab.HABITS -> HabitsScreen(snapshot.today)
                        }
                    }
                }
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
