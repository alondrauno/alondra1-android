package com.alondra1.app.features.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.alondra1.app.AppContainer
import com.alondra1.app.data.repository.SearchResults
import com.alondra1.app.domain.NoteRules
import com.alondra1.app.ui.EmptyState
import com.alondra1.app.ui.Format
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchViewModel(private val container: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val results: StateFlow<SearchResults> = query.debounce(150).mapLatest { container.search.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults.EMPTY)
    fun zone() = container.time.zone()
    fun today() = container.time.today()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel, onBack: () -> Unit, onTask: (String) -> Unit, onNote: (String) -> Unit, onEvent: (String) -> Unit) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            title = {
                TextField(
                    value = query, onValueChange = { viewModel.query.value = it }, singleLine = true,
                    placeholder = { Text("Search tasks, notes, events") },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { viewModel.query.value = "" }) { Icon(Icons.Filled.Close, "Clear") } },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("searchField"),
                )
            },
        )
        when {
            query.isBlank() -> EmptyState(Icons.Filled.Search, "Search everything", "Tasks, notes and events on this device. Works offline.")
            results.isEmpty -> EmptyState(Icons.Filled.Search, "No matches", "Nothing found for \"${query.trim()}\".")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.testTag("searchResults")) {
                if (results.tasks.isNotEmpty()) {
                    item { Header("Tasks") }
                    items(results.tasks, key = { "t" + it.id }) { task ->
                        ListItem(
                            leadingContent = { Icon(if (task.completed) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null) },
                            headlineContent = { Text(task.title) },
                            supportingContent = task.dueDate?.let { { Text("Due " + Format.relativeDate(it, viewModel.today())) } },
                            modifier = Modifier.clickable { onTask(task.id) }.testTag("result:task:${task.title}"),
                        )
                    }
                }
                if (results.notes.isNotEmpty()) {
                    item { Header("Notes") }
                    items(results.notes, key = { "n" + it.id }) { note ->
                        ListItem(
                            leadingContent = { Icon(Icons.Outlined.StickyNote2, null) },
                            headlineContent = { Text(NoteRules.displayTitle(note)) },
                            supportingContent = NoteRules.preview(note).takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 1) } },
                            modifier = Modifier.clickable { onNote(note.id) }.testTag("result:note:${NoteRules.displayTitle(note)}"),
                        )
                    }
                }
                if (results.events.isNotEmpty()) {
                    item { Header("Events") }
                    items(results.events, key = { "e" + it.id }) { event ->
                        val date = event.startDate ?: event.startUtc?.atZone(viewModel.zone())?.toLocalDate()
                        ListItem(
                            leadingContent = { Icon(Icons.Filled.Event, null) },
                            headlineContent = { Text(event.title) },
                            supportingContent = date?.let { { Text(Format.relativeDate(it, viewModel.today())) } },
                            modifier = Modifier.clickable { onEvent(event.id) }.testTag("result:event:${event.title}"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}
