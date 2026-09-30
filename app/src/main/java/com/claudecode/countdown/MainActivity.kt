package com.claudecode.countdown

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.claudecode.countdown.domain.TaskFilter
import com.claudecode.countdown.ui.AppBottomBar
import com.claudecode.countdown.ui.AppRail
import com.claudecode.countdown.ui.Motion
import com.claudecode.countdown.ui.MoreToolsSheet
import com.claudecode.countdown.ui.icon
import com.claudecode.countdown.ui.LocalSnackbarHost
import com.claudecode.countdown.ui.TikTakTheme
import com.claudecode.countdown.data.Tool
import com.claudecode.countdown.data.barLayout
import com.claudecode.countdown.ui.calendar.CalendarScreen
import com.claudecode.countdown.ui.detail.TaskDetailScreen
import com.claudecode.countdown.ui.detail.TaskDetailViewModel
import com.claudecode.countdown.ui.focus.FocusScreen
import com.claudecode.countdown.ui.habits.HabitsScreen
import com.claudecode.countdown.ui.matrix.MatrixScreen
import com.claudecode.countdown.ui.settings.SettingsScreen
import com.claudecode.countdown.ui.settings.ToolbarSettingsScreen
import com.claudecode.countdown.ui.tasks.DrawerSection
import com.claudecode.countdown.ui.tasks.SearchScreen
import com.claudecode.countdown.ui.tasks.Snapshot
import com.claudecode.countdown.ui.tasks.TaskListScreen
import com.claudecode.countdown.ui.tasks.TasksViewModel
import com.claudecode.countdown.ui.tasks.TrashScreen

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
                val tasksVm: TasksViewModel = viewModel { TasksViewModel(container.tasks, container.undo) }
                val nav = rememberNavController()
                LaunchedEffect(pendingTaskId) {
                    pendingTaskId?.let { id ->
                        nav.navigate("task/$id")
                        pendingTaskId = null
                    }
                }
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { container.undo.showIn(snackbar) }
                CompositionLocalProvider(LocalSnackbarHost provides snackbar) {
                    AppNavHost(nav, tasksVm)
                }
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
        // The system dark mode may have changed while the app was in the background.
        onThemeChanged()
    }

    private fun onThemeChanged() {
        // May recreate the activity when the effective day/night mode changes; screen state survives that.
        ThemeManager.applyNightMode(this)
        ThemeManager.apply(this)
        palette = ThemeManager.palette(this)
    }

    /** A theme option changed in the settings: repaint the app, and the widgets when they follow it. */
    private fun onThemeSettingsChanged() {
        onThemeChanged()
        container.refreshAllWidgets()
    }

    @Composable
    private fun AppNavHost(nav: NavHostController, tasksVm: TasksViewModel) {
        val snapshot by tasksVm.snapshot.collectAsStateWithLifecycle()
        val settings by container.settings.state.collectAsStateWithLifecycle()
        var filterKey by rememberSaveable { mutableStateOf(container.settings.current.startFilterKey) }
        var tab by rememberSaveable { mutableStateOf(Tool.TASKS) }
        var moreTools by remember { mutableStateOf<List<Tool>?>(null) }
        val openTask: (String) -> Unit = { nav.navigate("task/$it") }
        val openTrash = { nav.navigate("trash") }
        val openToolbar = { nav.navigate("toolbar") }

        // Phones get a bottom bar; wider screens a side rail, and from ~720dp the lists panel stays open.
        val width = LocalConfiguration.current.screenWidthDp
        val wide = width >= 600
        val bar = barLayout(settings.tools, settings.barLimit)
        val searchOnBar = !wide && Tool.SEARCH in bar.visible
        // A section opened from the ☰ menu (not pinned) returns to the tasks with Back.
        BackHandler(enabled = !wide && tab != Tool.TASKS && tab !in settings.tools) { tab = Tool.TASKS }
        val sections = listOf(Tool.MATRIX, Tool.FOCUS, Tool.HABITS).map { t ->
            DrawerSection(t.label, t.icon!!) { tab = t }
        }
        val selectFilter: (TaskFilter) -> Unit = {
            filterKey = it.key
            container.settings.rememberFilter(it.key)
        }

        NavHost(
            nav,
            startDestination = "home",
            enterTransition = Motion.push,
            exitTransition = Motion.pushExit,
            popEnterTransition = Motion.popEnter,
            popExitTransition = Motion.pop,
        ) {
            composable("home") {
                val body = @Composable {
                    AnimatedContent(tab, transitionSpec = { Motion.sectionChange() }, label = "section") { current ->
                        Section(current, tasksVm, snapshot, filterKey, selectFilter, openTask, openTrash, openToolbar, sections, wide, width, searchOnBar) { tab = it }
                    }
                }
                if (wide) {
                    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        AppRail(settings.tools, tab, snapshot.today.dayOfMonth, onSelect = { tab = it }, onMore = { moreTools = it })
                        Box(Modifier.weight(1f)) { body() }
                    }
                } else {
                    Scaffold(
                        bottomBar = {
                            // Search opens full screen, like TickTick, unless it is pinned to the bar.
                            if (tab != Tool.SEARCH || searchOnBar) {
                                AppBottomBar(bar, tab, snapshot.today.dayOfMonth, onSelect = { tab = it }, onMore = { moreTools = bar.more })
                            }
                        },
                    ) { padding ->
                        Box(Modifier.padding(padding)) { body() }
                    }
                }
                moreTools?.let { tools ->
                    MoreToolsSheet(
                        tools = tools,
                        selected = tab,
                        today = snapshot.today.dayOfMonth,
                        onSelect = { tab = it; moreTools = null },
                        onEdit = { moreTools = null; openToolbar() },
                        onDismiss = { moreTools = null },
                    )
                }
            }
            composable("task/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                val vm: TaskDetailViewModel = viewModel {
                    TaskDetailViewModel(id, container.tasks, container.appScope, container.undo)
                }
                TaskDetailScreen(
                    vm = vm,
                    onBack = { if (!nav.popBackStack()) finish() },
                    onOpenTask = { nav.navigate("task/$it") },
                )
            }
            composable("trash") {
                TrashScreen(tasksVm, snapshot, onBack = { nav.popBackStack() })
            }
            composable("toolbar") {
                ToolbarSettingsScreen(today = snapshot.today.dayOfMonth, onBack = { nav.popBackStack() })
            }
        }
    }

    @Composable
    private fun Section(
        tool: Tool,
        tasksVm: TasksViewModel,
        snapshot: Snapshot,
        filterKey: String,
        onFilterChange: (TaskFilter) -> Unit,
        openTask: (String) -> Unit,
        openTrash: () -> Unit,
        openToolbar: () -> Unit,
        sections: List<DrawerSection>,
        wide: Boolean,
        width: Int,
        searchOnBar: Boolean,
        onTab: (Tool) -> Unit,
    ) {
        when (tool) {
            Tool.TASKS, Tool.COUNTDOWNS -> TaskListScreen(
                vm = tasksVm,
                snapshot = snapshot,
                filter = if (tool == Tool.COUNTDOWNS) TaskFilter.Countdowns else TaskFilter.parse(filterKey),
                onFilterChange = {
                    onFilterChange(it)
                    // "Countdowns" is a section of its own; another list chosen there opens in Tasks.
                    if (tool == Tool.COUNTDOWNS) onTab(Tool.TASKS)
                },
                onOpenTask = openTask,
                onOpenTrash = openTrash,
                sections = sections,
                permanentDrawer = width >= 720,
                onOpenSearch = if (wide || searchOnBar) null else ({ onTab(Tool.SEARCH) }),
            )
            Tool.CALENDAR -> CalendarScreen(tasksVm, snapshot, openTask)
            Tool.MATRIX -> MatrixScreen(tasksVm, snapshot, openTask)
            Tool.FOCUS -> FocusScreen(snapshot)
            Tool.HABITS -> HabitsScreen(snapshot.today)
            Tool.SEARCH -> SearchScreen(tasksVm, snapshot, openTask, onBack = if (wide || searchOnBar) null else ({ onTab(Tool.TASKS) }))
            Tool.SETTINGS -> SettingsScreen(
                onBack = null,
                onOpenTrash = openTrash,
                onOpenToolbar = openToolbar,
                onThemeChanged = ::onThemeSettingsChanged,
            )
        }
    }
}
