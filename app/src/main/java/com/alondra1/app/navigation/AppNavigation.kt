package com.alondra1.app.navigation

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.alondra1.app.AppContainer
import com.alondra1.app.auth.CloudAccountSection
import com.alondra1.app.auth.LocalOnlyAccountSection
import com.alondra1.app.data.settings.Settings
import com.alondra1.app.data.settings.Tab
import com.alondra1.app.design.Alondra1Theme
import com.alondra1.app.domain.SmartView
import com.alondra1.app.features.calendar.CalendarScreen
import com.alondra1.app.features.calendar.CalendarViewModel
import com.alondra1.app.features.calendar.EventEditorScreen
import com.alondra1.app.features.calendar.EventEditorViewModel
import com.alondra1.app.features.notes.NEW_NOTE
import com.alondra1.app.features.notes.NoteEditorScreen
import com.alondra1.app.features.notes.NoteEditorViewModel
import com.alondra1.app.features.notes.NotesScreen
import com.alondra1.app.features.notes.NotesViewModel
import com.alondra1.app.features.onboarding.WelcomeScreen
import com.alondra1.app.features.search.SearchScreen
import com.alondra1.app.features.search.SearchViewModel
import com.alondra1.app.features.settings.SettingsScreen
import com.alondra1.app.features.todo.TaskDetailScreen
import com.alondra1.app.features.todo.TaskDetailViewModel
import com.alondra1.app.features.todo.TodoScreen
import com.alondra1.app.features.todo.TodoSelection
import com.alondra1.app.features.todo.TodoViewModel
import com.alondra1.app.ui.LocalContainer
import com.alondra1.app.ui.LocalSnackbar
import com.alondra1.app.ui.LocalTimeFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

object Routes {
    const val TODO = "todo"
    const val NOTES = "notes"
    const val CALENDAR = "calendar"
    const val TASK = "task/{id}"
    const val NOTE = "note/{id}"
    const val EVENT = "event/{id}"
    const val NEW_EVENT = "event-new?date={date}"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val NOTE_CONFLICT = "note-conflict/{issue}"
    fun noteConflict(issueId: Long) = "note-conflict/$issueId"
    fun task(id: String) = "task/$id"
    fun note(id: String) = "note/$id"
    fun event(id: String) = "event/$id"
    fun newEvent(date: LocalDate) = "event-new?date=$date"
}

private data class TopLevel(val tab: Tab, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TOP_LEVEL = listOf(
    TopLevel(Tab.TODO, "To-do", Icons.Outlined.TaskAlt, Icons.Filled.CheckCircle),
    TopLevel(Tab.NOTES, "Notes", Icons.Outlined.StickyNote2, Icons.Outlined.StickyNote2),
    TopLevel(Tab.CALENDAR, "Calendar", Icons.Filled.CalendarMonth, Icons.Filled.CalendarMonth),
)

/** Root composable: theme, first run, adaptive navigation. */
@Composable
fun Alondra1Root(
    container: AppContainer,
    newIntents: Flow<Intent> = emptyFlow(),
    accountSection: @Composable () -> Unit = { if (container.cloud != null) CloudAccountSection(container) else LocalOnlyAccountSection() },
) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val loaded = settings ?: return
    Alondra1Theme(loaded.theme) {
        val snackbar = remember { SnackbarHostState() }
        CompositionLocalProvider(LocalContainer provides container, LocalSnackbar provides snackbar, LocalTimeFormat provides loaded.timeFormat) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                if (!loaded.onboardingComplete) {
                    val scope = rememberCoroutineScope()
                    WelcomeScreen { examples ->
                        scope.launch {
                            container.todo.ensureDefaultList()
                            if (examples) container.todo.addExamples()
                            container.settings.setOnboardingComplete()
                        }
                    }
                } else {
                    AppScaffold(container, loaded, snackbar, newIntents, accountSection)
                }
            }
        }
    }
}

@Composable
private fun AppScaffold(
    container: AppContainer,
    settings: Settings,
    snackbar: SnackbarHostState,
    newIntents: Flow<Intent>,
    accountSection: @Composable () -> Unit,
) {
    val nav = rememberNavController()
    val width = LocalConfiguration.current.screenWidthDp
    val useRail = width >= 600
    val twoPane = width >= 840
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val topLevel = route in setOf(Routes.TODO, Routes.NOTES, Routes.CALENDAR)
    val scope = rememberCoroutineScope()
    val startTab = rememberSaveable { settings.lastTab.route }

    LaunchedEffect(newIntents) { newIntents.collect { nav.handleDeepLink(it) } }

    fun go(tab: Tab) {
        scope.launch { container.settings.setLastTab(tab) }
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = if (topLevel && !useRail) WindowInsets(0) else WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        bottomBar = {
            if (topLevel && !useRail) {
                NavigationBar(Modifier.testTag("bottomNav")) {
                    TOP_LEVEL.forEach { item ->
                        val selected = route == item.tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { go(item.tab) },
                            icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                            modifier = Modifier.testTag("tab:${item.label}"),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (useRail) {
                NavigationRail(Modifier.testTag("navRail")) {
                    TOP_LEVEL.forEach { item ->
                        NavigationRailItem(
                            selected = route == item.tab.route || (route != null && belongsTo(route) == item.tab),
                            onClick = { go(item.tab) },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(item.label) },
                            modifier = Modifier.testTag("tab:${item.label}"),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                AppNavHost(nav, container, startTab, twoPane, accountSection)
            }
        }
    }
}

private fun belongsTo(route: String): Tab? = when {
    route.startsWith("task") -> Tab.TODO
    route.startsWith("note") -> Tab.NOTES
    route.startsWith("event") -> Tab.CALENDAR
    else -> null
}

@Composable
private fun AppNavHost(nav: NavHostController, container: AppContainer, start: String, twoPane: Boolean, accountSection: @Composable () -> Unit) {
    CompositionLocalProvider(com.alondra1.app.features.notes.LocalOpenNoteConflict provides { nav.navigate(Routes.noteConflict(it)) }) {
    NavHost(navController = nav, startDestination = start) {
        composable(Routes.TODO) {
            val vm: TodoViewModel = viewModel { TodoViewModel(container, TodoSelection.View(SmartView.MY_DAY)) }
            if (twoPane) {
                var selected by rememberSaveable { mutableStateOf<String?>(null) }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        TodoScreen(vm, onOpenTask = { selected = it }, onSearch = { nav.navigate(Routes.SEARCH) },
                            onSettings = { nav.navigate(Routes.SETTINGS) }, selectedTaskId = selected)
                    }
                    VerticalDivider()
                    Box(Modifier.width(420.dp).fillMaxHeight().testTag("detailPane")) {
                        selected?.let { id ->
                            val detail: TaskDetailViewModel = viewModel(key = "task-$id") { TaskDetailViewModel(container, id) }
                            TaskDetailScreen(detail, onClose = { selected = null }, showBack = false)
                        } ?: com.alondra1.app.ui.EmptyState(Icons.Outlined.TaskAlt, "No task selected", "Choose a task to see its details.")
                    }
                }
            } else {
                TodoScreen(vm, onOpenTask = { nav.navigate(Routes.task(it)) }, onSearch = { nav.navigate(Routes.SEARCH) }, onSettings = { nav.navigate(Routes.SETTINGS) })
            }
        }
        composable(Routes.TASK, arguments = listOf(navArgument("id") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "alondra1://task/{id}" })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm: TaskDetailViewModel = viewModel { TaskDetailViewModel(container, id) }
            TaskDetailScreen(vm, onClose = { nav.popBackStack() })
        }
        composable(Routes.NOTES) {
            val vm: NotesViewModel = viewModel { NotesViewModel(container) }
            if (twoPane) {
                var selected by rememberSaveable { mutableStateOf<String?>(null) }
                var generation by rememberSaveable { mutableStateOf(0) }
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        NotesScreen(vm, onOpen = { selected = it; generation++ }, onSearch = { nav.navigate(Routes.SEARCH) },
                            onSettings = { nav.navigate(Routes.SETTINGS) }, selectedId = selected)
                    }
                    VerticalDivider()
                    Box(Modifier.width(460.dp).fillMaxHeight().testTag("detailPane")) {
                        selected?.let { id ->
                            val editor: NoteEditorViewModel = viewModel(key = "note-$id-$generation") { NoteEditorViewModel(container, createSavedStateHandle(), id) }
                            NoteEditorScreen(editor, onClose = { selected = null }, showBack = false,
                                onOpenTask = { nav.navigate(Routes.task(it)) }, onOpenEvent = { nav.navigate(Routes.event(it)) },
                                // Close the pane first so it cannot show (or save) the version from before the choice.
                                onCompare = { selected = null; nav.navigate(Routes.noteConflict(it)) })
                        } ?: com.alondra1.app.ui.EmptyState(Icons.Outlined.StickyNote2, "No note selected", "Choose a note or start a new one.")
                    }
                }
            } else {
                NotesScreen(vm, onOpen = { nav.navigate(Routes.note(it)) }, onSearch = { nav.navigate(Routes.SEARCH) }, onSettings = { nav.navigate(Routes.SETTINGS) })
            }
        }
        composable(Routes.NOTE, arguments = listOf(navArgument("id") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "alondra1://note/{id}" })) { entry ->
            val id = entry.arguments?.getString("id") ?: NEW_NOTE
            val vm: NoteEditorViewModel = viewModel { NoteEditorViewModel(container, createSavedStateHandle(), id) }
            NoteEditorScreen(vm, onClose = { nav.popBackStack() }, onOpenTask = { nav.navigate(Routes.task(it)) }, onOpenEvent = { nav.navigate(Routes.event(it)) },
                onCompare = { nav.navigate(Routes.noteConflict(it)) })
        }
        composable(Routes.CALENDAR) {
            val vm: CalendarViewModel = viewModel { CalendarViewModel(container) }
            CalendarScreen(
                vm,
                onOpenEvent = { nav.navigate(Routes.event(it)) },
                onNewEvent = { nav.navigate(Routes.newEvent(it)) },
                onOpenTask = { nav.navigate(Routes.task(it)) },
                onSearch = { nav.navigate(Routes.SEARCH) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.EVENT, arguments = listOf(navArgument("id") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "alondra1://event/{id}" })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm: EventEditorViewModel = viewModel { EventEditorViewModel(container, id, null) }
            EventEditorScreen(vm, onClose = { nav.popBackStack() }, onMeetingNotes = { nav.navigate(Routes.note(it)) })
        }
        composable(Routes.NEW_EVENT, arguments = listOf(navArgument("date") { type = NavType.StringType; nullable = true })) { entry ->
            val date = entry.arguments?.getString("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val vm: EventEditorViewModel = viewModel { EventEditorViewModel(container, null, date) }
            EventEditorScreen(vm, onClose = { nav.popBackStack() })
        }
        composable(Routes.SEARCH) {
            val vm: SearchViewModel = viewModel { SearchViewModel(container) }
            SearchScreen(vm, onBack = { nav.popBackStack() }, onTask = { nav.navigate(Routes.task(it)) },
                onNote = { nav.navigate(Routes.note(it)) }, onEvent = { nav.navigate(Routes.event(it)) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, accountSection = accountSection)
        }
        composable(Routes.NOTE_CONFLICT, arguments = listOf(navArgument("issue") { type = NavType.LongType })) { entry ->
            com.alondra1.app.features.notes.NoteConflictScreen(container, entry.arguments?.getLong("issue") ?: 0L, onClose = { nav.popBackStack() },
                onResolved = { id ->
                    // Leave the compare screen and the note opened before it, then show the result fresh.
                    nav.popBackStack()
                    if (nav.currentDestination?.route == Routes.NOTE) nav.popBackStack()
                    if (id != null) nav.navigate(Routes.note(id))
                })
        }
    }
    }
}
