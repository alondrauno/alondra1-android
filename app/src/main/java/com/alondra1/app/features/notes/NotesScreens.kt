package com.alondra1.app.features.notes

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.alondra1.app.AppContainer
import com.alondra1.app.core.Ids
import com.alondra1.app.data.local.NoteEntity
import com.alondra1.app.data.local.NoteFolderEntity
import com.alondra1.app.data.local.NoteSummary
import com.alondra1.app.data.repository.InvalidTitleException
import com.alondra1.app.data.repository.NoteAssetException
import com.alondra1.app.data.repository.NoteLink
import com.alondra1.app.data.repository.NoteSort
import com.alondra1.app.data.repository.NoteStorageException
import com.alondra1.app.data.repository.NoteView
import com.alondra1.app.data.repository.NotesRepository
import com.alondra1.app.domain.NoteRules
import com.alondra1.app.domain.TaskRules
import com.alondra1.app.ui.EmptyState
import com.alondra1.app.ui.LocalSnackbar
import com.alondra1.app.ui.showUndo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// ------------------------------------------------------------------------------------------------ Notes list

data class NotesState(
    val loading: Boolean = true,
    val notes: List<NoteSummary> = emptyList(),
    val folders: List<NoteFolderEntity> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    val query: String = "",
    val view: NoteView = NoteView.ALL,
    val folderId: String? = null,
    val sort: NoteSort = NoteSort.MODIFIED,
) {
    val heading: String get() = when (view) {
        NoteView.ALL -> "Notes"
        NoteView.FAVORITES -> "Favorites"
        NoteView.ARCHIVE -> "Archive"
        NoteView.TRASH -> "Trash"
        NoteView.FOLDER -> folders.firstOrNull { it.id == folderId }?.name ?: "Folder"
    }
}

class NotesViewModel(private val container: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    private val selection = MutableStateFlow(Triple(NoteView.ALL, null as String?, NoteSort.MODIFIED))
    private val matches = MutableStateFlow<Set<String>?>(null)

    val state: StateFlow<NotesState> = combine(container.notes.observeSummaries(), container.notes.observeFolders(), query, selection, matches) { all, folders, q, sel, found ->
        val (view, folderId, sort) = sel
        val counts = buildMap {
            put("all", all.count { NotesRepository.inView(it, NoteView.ALL, null) })
            put("favorites", all.count { NotesRepository.inView(it, NoteView.FAVORITES, null) })
            put("archive", all.count { NotesRepository.inView(it, NoteView.ARCHIVE, null) })
            put("trash", all.count { NotesRepository.inView(it, NoteView.TRASH, null) })
            folders.forEach { f -> put("f:${f.id}", all.count { NotesRepository.inView(it, NoteView.FOLDER, f.id) }) }
        }
        NotesState(false, NotesRepository.query(all, view, folderId, sort, sort != NoteSort.TITLE, if (q.isBlank()) null else found.orEmpty()),
            folders, counts, q, view, folderId, sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesState())

    private var searchJob: Job? = null
    fun search(text: String) {
        query.value = text
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(150); matches.value = if (text.isBlank()) null else container.notes.search(text) }
    }

    fun show(view: NoteView, folderId: String? = null) { selection.value = Triple(view, folderId, selection.value.third) }
    fun sort(sort: NoteSort) { selection.value = selection.value.copy(third = sort) }
    fun favorite(n: NoteSummary) = launch { container.notes.setFavorite(listOf(n.id), !n.favorite) }
    fun pin(n: NoteSummary) = launch { container.notes.setPinned(n.id, !n.pinned) }
    fun archive(n: NoteSummary) = launch { container.notes.archive(listOf(n.id), n.archivedUtc == null) }
    fun move(n: NoteSummary, folderId: String?) = launch { container.notes.move(listOf(n.id), folderId) }
    fun trash(n: NoteSummary) = launch { container.notes.trash(listOf(n.id)) }
    fun restore(n: NoteSummary) = launch { container.notes.restoreFromTrash(listOf(n.id)) }
    fun deleteForever(n: NoteSummary) = launch { container.notes.deleteForever(listOf(n.id)) }
    fun emptyTrash() = launch { state.value.notes.filter { it.trashedUtc != null }.forEach { container.notes.delete(it.id) } }
    fun addFolder(name: String) = launch { container.notes.addFolder(name) }
    fun renameFolder(id: String, name: String) = launch { container.notes.renameFolder(id, name) }
    fun deleteFolder(id: String) = launch { container.notes.deleteFolder(id); if (selection.value.second == id) show(NoteView.ALL) }
    fun zone() = container.time.zone()
    fun thumbnail(sha: String?): java.io.File? = sha?.takeIf { container.noteAssets.hasFile(it) }?.let(container.noteAssets::fileOf)

    private fun launch(block: suspend () -> Unit) { viewModelScope.launch { runCatching { block() } } }
}

/** "[ ] item" lines show as check boxes in previews. */
private fun previewText(s: NoteSummary): String {
    val lines = s.preview.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val rest = if (s.title.isBlank()) lines.drop(1) else lines.dropWhile { it == s.title.trim() }
    return rest.joinToString("  ") { it.replace(Regex("^\\[x] "), "☑ ").replace(Regex("^\\[ ] "), "☐ ") }.take(160)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(viewModel: NotesViewModel, onOpen: (String) -> Unit, onSearch: () -> Unit, onSettings: () -> Unit, selectedId: String? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var chooser by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<NoteSummary?>(null) }
    val fmt = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent, // the workspace background shows through
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            // Shared header (Android UI spec §6): the title opens views and folders; sort and Empty trash are secondary.
            com.alondra1.app.design.AppHeader(
                title = state.heading,
                icon = Icons.Outlined.StickyNote2,
                onSearch = onSearch,
                onAccount = onSettings,
                menu = buildList {
                    add(com.alondra1.app.design.MenuAction("Views and folders…") { chooser = true })
                    listOf(NoteSort.MODIFIED to "Last edited", NoteSort.CREATED to "Date created", NoteSort.TITLE to "Title").forEach { (s, label) ->
                        add(com.alondra1.app.design.MenuAction("Sort by " + label.lowercase(), checked = s == state.sort) { viewModel.sort(s) })
                    }
                    if (state.view == NoteView.TRASH && state.notes.isNotEmpty()) add(com.alondra1.app.design.MenuAction("Empty trash", destructive = true) { confirmEmpty = true })
                },
                titleModifier = Modifier.clickable(onClickLabel = "Choose a view or folder") { chooser = true }.testTag("noteViews"),
            )
        },
        floatingActionButton = {
            if (state.view != NoteView.TRASH && state.view != NoteView.ARCHIVE)
                ExtendedFloatingActionButton(onClick = { onOpen(newNoteRoute(state)) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New note") },
                    modifier = Modifier.testTag("newNote"))
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TextField(
                value = state.query,
                onValueChange = viewModel::search,
                placeholder = { Text("Search notes") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.search("") }) { Icon(Icons.Filled.Close, "Clear search") } },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("noteSearch"),
            )
            if (!state.loading && state.notes.isEmpty()) {
                when {
                    state.query.isNotBlank() -> EmptyState(Icons.Filled.Search, "No matching notes", "Try another word.")
                    state.view == NoteView.TRASH -> EmptyState(Icons.Filled.Delete, "Trash is empty", "Deleted notes stay here for 30 days.")
                    state.view == NoteView.ARCHIVE -> EmptyState(Icons.Outlined.Archive, "No archived notes", "Archive notes you want to keep out of the way.")
                    state.view == NoteView.FAVORITES -> EmptyState(Icons.Outlined.StarOutline, "No favorites", "Star a note to find it here.")
                    else -> EmptyState(Icons.Outlined.StickyNote2, "No notes yet", "Tap New note to write something down. Notes save as you type.")
                }
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.notes, key = { it.id }) { note ->
                    val title = NoteRules.displayTitle(note.title, note.preview)
                    Box {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (note.id == selectedId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { onOpen(note.id) }, onLongClick = { menuFor = note.id }, onClickLabel = "Open note")
                                .testTag("note:$title"),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (note.pinned) Icon(Icons.Filled.PushPin, contentDescription = "Pinned", modifier = Modifier.size(16.dp).padding(end = 4.dp), tint = MaterialTheme.colorScheme.primary)
                                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        if (note.favorite) Icon(Icons.Filled.Star, contentDescription = "Favorite", modifier = Modifier.size(16.dp).padding(start = 4.dp), tint = Color(0xFFE0A100))
                                    }
                                    val preview = previewText(note)
                                    if (preview.isNotEmpty()) Text(preview, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(note.modifiedUtc.atZone(viewModel.zone()).format(fmt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Thumbnail(viewModel.thumbnail(NotesRepository.decodeIds(note.assetsJson).firstOrNull()))
                                IconButton(onClick = { menuFor = note.id }) { Icon(Icons.Filled.MoreVert, contentDescription = "Note options") }
                            }
                        }
                        DropdownMenu(expanded = menuFor == note.id, onDismissRequest = { menuFor = null }) {
                            if (note.trashedUtc != null) {
                                DropdownMenuItem(text = { Text("Restore") }, onClick = { menuFor = null; viewModel.restore(note) })
                                DropdownMenuItem(text = { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }, onClick = { menuFor = null; confirmDelete = note })
                            } else {
                                DropdownMenuItem(text = { Text(if (note.favorite) "Remove from favorites" else "Add to favorites") }, onClick = { menuFor = null; viewModel.favorite(note) })
                                DropdownMenuItem(text = { Text(if (note.pinned) "Unpin" else "Pin") }, onClick = { menuFor = null; viewModel.pin(note) })
                                DropdownMenuItem(text = { Text("Move to: No folder") }, onClick = { menuFor = null; viewModel.move(note, null) })
                                state.folders.forEach { f -> DropdownMenuItem(text = { Text("Move to: ${f.name}") }, onClick = { menuFor = null; viewModel.move(note, f.id) }) }
                                DropdownMenuItem(text = { Text(if (note.archivedUtc != null) "Unarchive" else "Archive") }, onClick = { menuFor = null; viewModel.archive(note) })
                                DropdownMenuItem(text = { Text("Move to Trash") }, onClick = {
                                    menuFor = null
                                    viewModel.trash(note)
                                    scope.launch { snackbar.showUndo("Note moved to Trash") { viewModel.restore(note) } }
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    if (chooser) {
        ModalBottomSheet(onDismissRequest = { chooser = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                @Composable
                fun Row(label: String, key: String, icon: androidx.compose.ui.graphics.vector.ImageVector, folder: NoteFolderEntity? = null, onClick: () -> Unit) {
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { chooser = false; onClick() },
                        onLongClick = { if (folder != null) { chooser = false; folderDialog = folder.id to folder.name } })
                        .padding(horizontal = 24.dp, vertical = 14.dp).testTag("noteView:$label"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, Modifier.padding(start = 16.dp).weight(1f))
                        Text(state.counts[key]?.takeIf { it > 0 }?.toString().orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row("All notes", "all", Icons.Outlined.StickyNote2) { viewModel.show(NoteView.ALL) }
                Row("Favorites", "favorites", Icons.Outlined.StarOutline) { viewModel.show(NoteView.FAVORITES) }
                state.folders.forEach { f -> Row(f.name, "f:${f.id}", Icons.Outlined.Folder, f) { viewModel.show(NoteView.FOLDER, f.id) } }
                Row("Archive", "archive", Icons.Outlined.Archive) { viewModel.show(NoteView.ARCHIVE) }
                Row("Trash", "trash", Icons.Filled.Delete) { viewModel.show(NoteView.TRASH) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                TextButton(onClick = { chooser = false; folderDialog = null to "" }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("New folder") }
                Text("Touch and hold a folder to rename or delete it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
    }

    folderDialog?.let { (id, initial) ->
        var name by rememberSaveable { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { folderDialog = null },
            title = { Text(if (id == null) "New folder" else "Folder") },
            text = { OutlinedTextField(name, { name = it.take(200) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.testTag("folderName")) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { folderDialog = null; if (id == null) viewModel.addFolder(name) else viewModel.renameFolder(id, name) }) { Text("Save") } },
            dismissButton = {
                Row {
                    if (id != null) TextButton(onClick = { folderDialog = null; viewModel.deleteFolder(id) }) { Text("Delete folder", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { folderDialog = null }) { Text("Cancel") }
                }
            },
        )
    }

    confirmDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete note permanently?") },
            text = { Text("\"${note.title.ifBlank { "Untitled" }}\" is deleted permanently. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = null; viewModel.deleteForever(note) }, modifier = Modifier.testTag("confirmDeleteNote")) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty trash?") },
            text = { Text("Every note in Trash is deleted permanently. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmEmpty = false; viewModel.emptyTrash() }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
}

/** New notes start in the folder (or as a favorite) of the view they are created from. */
private fun newNoteRoute(state: NotesState) = when (state.view) {
    NoteView.FOLDER -> "$NEW_NOTE:${state.folderId}"
    else -> NEW_NOTE
}

@Composable
private fun Thumbnail(file: java.io.File?) {
    if (file == null) return
    val bitmap = remember(file.path) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 128 }
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    } ?: return
    Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.padding(start = 8.dp).size(48.dp).clip(MaterialTheme.shapes.small))
}

const val NEW_NOTE = "new"

// ------------------------------------------------------------------------------------------------ Note editor

enum class SaveStatus { IDLE, SAVING, SAVED, FAILED }

/**
 * One note. The rich editor (WebView with the shared Alondra1 editor) saves documents; the plain editor is a safety
 * fallback for devices without a working WebView and opens rich notes read-only, so their formatting is never lost.
 * Autosave: each edit is stored after a short pause, and immediately when the screen stops (app to background,
 * navigation away). Plain drafts also live in SavedStateHandle, so they survive process death.
 */
class NoteEditorViewModel(private val container: AppContainer, private val handle: SavedStateHandle, requestedId: String) : ViewModel() {
    private val newFolder: String? = requestedId.removePrefix(NEW_NOTE).removePrefix(":").takeIf { requestedId.startsWith("$NEW_NOTE:") && it.isNotEmpty() }
    val noteId: String = handle["noteId"] ?: (if (requestedId.startsWith(NEW_NOTE)) Ids.newId() else requestedId).also { handle["noteId"] = it }
    var title by mutableStateOf(handle.get<String>("title") ?: "")
        private set
    var body by mutableStateOf(TextFieldValue(handle.get<String>("body") ?: ""))
        private set
    var pinned by mutableStateOf(false)
        private set
    var favorite by mutableStateOf(false)
        private set
    var status by mutableStateOf(SaveStatus.IDLE)
        private set
    var loaded by mutableStateOf(handle.get<Boolean>("loaded") == true)
        private set
    var note by mutableStateOf<NoteEntity?>(null)
        private set
    var links by mutableStateOf(emptyList<NoteLink>())
        private set
    val isRich: Boolean get() = note?.docJson != null
    private var saveJob: Job? = null

    val assets get() = container.noteAssets

    /** The unresolved sync conflict this note is part of (as the note or its conflict copy), or null. */
    val conflict = container.db.sync().observeNoteConflict(noteId)

    init {
        viewModelScope.launch {
            val stored = container.notes.getNote(noteId)?.takeIf { it.deletedUtc == null }
            note = stored
            pinned = stored?.pinned ?: false
            favorite = stored?.favorite ?: false
            links = stored?.let { NotesRepository.decodeLinks(it.linksJson) }.orEmpty()
            if (!loaded) {
                stored?.let { title = it.title; body = TextFieldValue(it.body); handle["title"] = it.title; handle["body"] = it.body }
                loaded = true; handle["loaded"] = true
            }
        }
    }

    fun onTitle(value: String) { if (isRich) return; title = value; handle["title"] = value; scheduleSave() }
    fun onBody(value: TextFieldValue) {
        if (isRich) return
        val changed = value.text != body.text
        body = value
        if (changed) { handle["body"] = value.text; scheduleSave() }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(600); save() }
    }

    /** Stores the current plain text now (also used on stop/back). Safe to call repeatedly. */
    fun flush() {
        saveJob?.cancel()
        viewModelScope.launch { withContext(NonCancellable) { save() } }
    }

    private suspend fun save() {
        if (!loaded || isRich) return
        status = SaveStatus.SAVING
        status = try {
            container.notes.save(noteId, title, body.text)
            SaveStatus.SAVED
        } catch (e: Exception) {
            SaveStatus.FAILED
        }
    }

    /** Autosave from the rich editor: {title, doc, text, assetIds}. */
    suspend fun saveRich(snapshot: JsonObject): Boolean {
        val doc = snapshot["doc"] as? JsonObject ?: return false
        return try {
            status = SaveStatus.SAVING
            val saved = container.notes.saveContent(noteId, snapshot["title"]?.jsonPrimitive?.contentOrNull.orEmpty(), doc.toString(),
                snapshot["text"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                (snapshot["assetIds"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }, newFolder)
            if (saved != null) note = saved
            status = SaveStatus.SAVED
            true
        } catch (e: NoteStorageException) {
            status = SaveStatus.FAILED
            false
        }
    }

    /** What the rich editor loads: the document, or the plain text turned into paragraphs. */
    fun documentForEditor(): String? = note?.docJson ?: note?.body?.takeIf { it.isNotEmpty() }?.let(NoteEditorBridge::plainDocument)

    fun togglePin() { viewModelScope.launch { save(); container.notes.setPinned(noteId, !pinned); pinned = !pinned } }
    fun toggleFavorite() { viewModelScope.launch { container.notes.setFavorite(listOf(noteId), !favorite); favorite = !favorite } }
    suspend fun delete(): NoteEntity? { saveJob?.cancel(); save(); return container.notes.delete(noteId) }
    suspend fun trash() { saveJob?.cancel(); save(); container.notes.trash(listOf(noteId)) }
    suspend fun restore(note: NoteEntity) = container.notes.restore(note)
    suspend fun restoreFromTrash() = container.notes.restoreFromTrash(listOf(noteId))

    suspend fun addLink(link: NoteLink) { container.notes.addLink(noteId, link); reloadLinks() }
    suspend fun removeLink(link: NoteLink) { container.notes.removeLink(noteId, link); reloadLinks() }
    private suspend fun reloadLinks() { links = container.notes.getNote(noteId)?.let { NotesRepository.decodeLinks(it.linksJson) }.orEmpty() }
    suspend fun tasksForLinking() = container.db.tasks().all().filter { it.deletedUtc == null }.sortedBy { it.completed }.take(500)
    suspend fun eventsForLinking() = container.db.events().all().filter { it.deletedUtc == null }.sortedBy { it.startUtc ?: java.time.Instant.EPOCH }.take(500)

    /** A picture from the system picker or another app: stored as an asset, then inserted by the editor. */
    suspend fun addPicture(bytes: ByteArray): Pair<String, Int?> {
        val current = NotesRepository.decodeIds(container.notes.getNote(noteId)?.assetsJson ?: "[]")
        return container.noteAssets.add(bytes, current).let { it.sha256 to it.width }
    }

    /** A checklist item becomes a task; the note keeps the item and links to the task. */
    suspend fun checklistToTask(text: String, done: Boolean): String {
        val task = container.todo.addTask(text.take(TaskRules.TITLE_MAX), sourceNoteId = noteId)
        if (done) container.todo.setCompleted(task.id, true)
        container.notes.addLink(noteId, NoteLink(NoteLink.TASK, task.id, task.title))
        reloadLinks()
        return task.id
    }

    fun suggestedTaskTitle(): String {
        val selection = body.selection.takeIf { !it.collapsed }?.let { body.text.substring(it.min, it.max) }
        val n = NoteEntity(id = noteId, title = title, body = body.text, createdUtc = java.time.Instant.EPOCH, modifiedUtc = java.time.Instant.EPOCH)
        return NoteRules.taskTitleFrom(n, selection)
    }

    /** Creates a task linked to this note; the note itself stays unchanged. */
    suspend fun createTask(taskTitle: String): String? = try {
        save()
        container.todo.addTask(taskTitle, sourceNoteId = noteId)
        null
    } catch (e: InvalidTitleException) {
        e.message
    }

    override fun onCleared() {
        saveJob?.cancel()
        val id = noteId; val t = title; val b = body.text
        if (loaded && !isRich) kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { container.notes.save(id, t, b) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(viewModel: NoteEditorViewModel, onClose: () -> Unit, showBack: Boolean = true, onOpenTask: (String) -> Unit = {}, onOpenEvent: (String) -> Unit = {},
                     onCompare: (Long) -> Unit = {}) {
    val context = LocalContext.current
    val rich = remember { NoteEditorBridge.available(context) }
    if (!viewModel.loaded) return
    if (rich) RichEditorScreen(viewModel, onClose, showBack, onOpenTask, onOpenEvent, onCompare) else PlainEditorScreen(viewModel, onClose, showBack, onCompare)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RichEditorScreen(viewModel: NoteEditorViewModel, onClose: () -> Unit, showBack: Boolean, onOpenTask: (String) -> Unit, onOpenEvent: (String) -> Unit, onCompare: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val dark = MaterialTheme.colorScheme.surface.let { (it.red * 299 + it.green * 587 + it.blue * 114) / 1000 < 0.5f }
    val colors = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    var linkPicker by remember { mutableStateOf(false) }
    val trashed = viewModel.note?.trashedUtc != null

    val bridge = remember {
        lateinit var self: NoteEditorBridge
        self = NoteEditorBridge(context, viewModel.assets, onMessage = { m -> scope.launch { handleEditorMessage(m, viewModel, { self }, onOpenTask, onOpenEvent, context) } },
            onMissingAsset = { })
        self
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        scope.launch {
            uris.forEach { uri ->
                try {
                    val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: return@forEach
                    bridge.flush()?.let { viewModel.saveRich(it) } // the per-note limit counts pictures already inserted
                    val (sha, width) = viewModel.addPicture(bytes)
                    bridge.call("insertAsset", buildJsonObject { put("assetId", sha); width?.let { put("width", it) } })
                } catch (e: NoteAssetException) {
                    snackbar.showSnackbar(e.message ?: "The picture could not be added.")
                }
            }
        }
    }
    pickPictures = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    LaunchedEffect(viewModel.noteId) {
        bridge.call("load", buildJsonObject {
            put("title", viewModel.note?.title ?: "")
            viewModel.documentForEditor()?.let { put("doc", kotlinx.serialization.json.Json.parseToJsonElement(it)) }
            put("readOnly", trashed)
            put("mode", "touch")
            put("focus", if (viewModel.note == null) "title" else "")
            put("limits", buildJsonObject { put("imageBytes", viewModel.assets.maxBytes) })
            put("theme", buildJsonObject {
                put("dark", dark)
                put("vars", buildJsonObject {
                    fun hex(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)
                    put("--a1-bg", hex(colors.surface)); put("--a1-subtle", hex(colors.surfaceContainer)); put("--a1-text", hex(colors.onSurface))
                    put("--a1-muted", hex(colors.onSurfaceVariant)); put("--a1-line", hex(colors.outlineVariant)); put("--a1-hover", hex(colors.surfaceContainerHigh))
                    put("--a1-selected", hex(colors.secondaryContainer)); put("--a1-accent", hex(colors.primary)); put("--a1-brand", hex(colors.primary))
                })
            })
        })
    }
    suspend fun flushNow() { bridge.flush()?.takeIf { it["dirty"]?.jsonPrimitive?.booleanOrNull == true }?.let { viewModel.saveRich(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { scope.launch { withContext(NonCancellable) { flushNow() } } }
    DisposableEffect(Unit) { onDispose { bridge.destroy() } }
    BackHandler { scope.launch(Dispatchers.Main) { flushNow(); withContext(Dispatchers.Main) { onClose() } } }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Text(when (viewModel.status) {
                    SaveStatus.SAVING -> "Saving…"; SaveStatus.SAVED -> "Saved"; SaveStatus.FAILED -> "Not saved — keep this screen open"; SaveStatus.IDLE -> if (trashed) "In Trash" else "Note"
                }, style = MaterialTheme.typography.titleSmall, color = if (viewModel.status == SaveStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("saveStatus"))
            },
            navigationIcon = {
                IconButton(onClick = { scope.launch(Dispatchers.Main) { flushNow(); withContext(Dispatchers.Main) { onClose() } } }) {
                    Icon(if (showBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, contentDescription = if (showBack) "Back" else "Close")
                }
            },
            actions = {
                if (!trashed) {
                    IconButton(onClick = { viewModel.toggleFavorite() }) {
                        Icon(if (viewModel.favorite) Icons.Filled.Star else Icons.Outlined.StarOutline, contentDescription = if (viewModel.favorite) "Remove from favorites" else "Add to favorites")
                    }
                    IconButton(onClick = { viewModel.togglePin() }) {
                        Icon(if (viewModel.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = if (viewModel.pinned) "Unpin" else "Pin")
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (trashed) {
                            DropdownMenuItem(text = { Text("Restore") }, onClick = { menu = false; scope.launch(Dispatchers.Main) { viewModel.restoreFromTrash(); withContext(Dispatchers.Main) { onClose() } } })
                        } else {
                            DropdownMenuItem(text = { Text("Link a task or event") }, leadingIcon = { Icon(Icons.Filled.AddTask, null) }, onClick = { menu = false; linkPicker = true })
                            DropdownMenuItem(text = { Text("Share as text") }, onClick = {
                                menu = false
                                scope.launch {
                                    flushNow()
                                    val n = viewModel.note ?: return@launch
                                    context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_SUBJECT, n.title); putExtra(android.content.Intent.EXTRA_TEXT, n.body)
                                    }, "Share note"))
                                }
                            })
                            DropdownMenuItem(text = { Text("Move to Trash") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = {
                                menu = false
                                scope.launch(Dispatchers.Main) { flushNow(); viewModel.trash(); withContext(Dispatchers.Main) { onClose() }; snackbar.showUndo("Note moved to Trash") { viewModel.restoreFromTrash() } }
                            })
                        }
                    }
                }
            },
        )
        val conflict by viewModel.conflict.collectAsStateWithLifecycle(initialValue = null)
        conflict?.let { issue -> ConflictBanner { scope.launch(Dispatchers.Main) { flushNow(); withContext(Dispatchers.Main) { onCompare(issue.id) } } } }
        if (viewModel.links.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.links.forEach { link ->
                    AssistChip(onClick = { if (link.type == NoteLink.TASK) onOpenTask(link.id) else if (link.type == NoteLink.EVENT) onOpenEvent(link.id) }, modifier = Modifier.testTag("link:${link.type}:${link.id}"), label = { Text((if (link.type == NoteLink.TASK) "✓ " else "📅 ") + (link.label ?: link.id), maxLines = 1) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove link", Modifier.size(16.dp).clickable { scope.launch { viewModel.removeLink(link) } }) })
                }
            }
        }
        AndroidView(factory = { bridge.webView }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("richEditor"))
    }

    if (linkPicker) LinkPickerDialog(viewModel, onDismiss = { linkPicker = false })
}

/** Set by the rich editor screen: opens the system photo picker. */
private var pickPictures: () -> Unit = {}

private suspend fun handleEditorMessage(m: JsonObject, vm: NoteEditorViewModel, bridge: () -> NoteEditorBridge, onOpenTask: (String) -> Unit, onOpenEvent: (String) -> Unit, context: android.content.Context) {
    when ((m["type"] as? JsonPrimitive)?.contentOrNull) {
        "change" -> if (vm.saveRich(m)) bridge().call("saved", "Saved on this device")
        "saveNow" -> bridge().flush()?.let { vm.saveRich(it) }
        "pickImage" -> pickPictures()
        "storeImage" -> {
            val requestId = m["requestId"]?.jsonPrimitive?.contentOrNull ?: return
            try {
                val bytes = android.util.Base64.decode(m["data"]?.jsonPrimitive?.contentOrNull.orEmpty(), android.util.Base64.DEFAULT)
                bridge().flush()?.let { vm.saveRich(it) } // the per-note limit counts pictures already inserted
                val (sha, width) = vm.addPicture(bytes)
                bridge().call("imageStored", buildJsonObject { put("requestId", requestId); put("assetId", sha); width?.let { put("width", it) } })
            } catch (e: Exception) {
                bridge().call("imageFailed", buildJsonObject { put("requestId", requestId); put("message", (e as? NoteAssetException)?.message ?: "The picture could not be added.") })
            }
        }
        "checklistToTask" -> {
            val requestId = m["requestId"]?.jsonPrimitive?.contentOrNull ?: return
            val taskId = vm.checklistToTask(m["text"]?.jsonPrimitive?.contentOrNull.orEmpty(), m["checked"]?.jsonPrimitive?.booleanOrNull == true)
            bridge().call("taskCreated", buildJsonObject { put("requestId", requestId); put("taskId", taskId) })
        }
        "openLink" -> {
            val href = m["href"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (href.startsWith("alondra://task/")) onOpenTask(href.removePrefix("alondra://task/"))
            else if (href.startsWith("alondra://event/")) onOpenEvent(href.removePrefix("alondra://event/"))
            else if (Regex("^(https?|mailto|tel):").containsMatchIn(href)) runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(href)))
            }
        }
        "fetchImage" -> bridge().call("imageFailed", buildJsonObject {
            put("requestId", m["requestId"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put("message", "Save the picture to your phone, then add it with the picture button.")
        })
    }
}

@Composable
private fun LinkPickerDialog(viewModel: NoteEditorViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<NoteLink>>(emptyList()) }
    LaunchedEffect(Unit) {
        choices = viewModel.tasksForLinking().map { NoteLink(NoteLink.TASK, it.id, it.title) } +
            viewModel.eventsForLinking().map { NoteLink(NoteLink.EVENT, it.id, it.title) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link a task or event") },
        text = {
            if (choices.isEmpty()) Text("No tasks or events yet.")
            else LazyColumn(Modifier.fillMaxWidth()) {
                items(choices, key = { it.type + it.id }) { link ->
                    Text((if (link.type == NoteLink.TASK) "✓ " else "📅 ") + (link.label ?: ""), Modifier.fillMaxWidth()
                        .clickable { scope.launch { viewModel.addLink(link); onDismiss() } }.padding(vertical = 12.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlainEditorScreen(viewModel: NoteEditorViewModel, onClose: () -> Unit, showBack: Boolean, onCompare: (Long) -> Unit) {
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var taskDialog by remember { mutableStateOf<String?>(null) }
    val readOnly = viewModel.isRich

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.flush() }
    BackHandler { viewModel.flush(); onClose() }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Text(
                    when {
                        readOnly -> "Read-only on this device"
                        viewModel.status == SaveStatus.SAVING -> "Saving…"
                        viewModel.status == SaveStatus.SAVED -> "Saved"
                        viewModel.status == SaveStatus.FAILED -> "Couldn't save — keep this screen open"
                        else -> "Note"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (viewModel.status == SaveStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("saveStatus"),
                )
            },
            navigationIcon = {
                IconButton(onClick = { viewModel.flush(); onClose() }) {
                    Icon(if (showBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, contentDescription = if (showBack) "Back" else "Close")
                }
            },
            actions = {
                IconButton(onClick = { viewModel.togglePin() }) {
                    Icon(if (viewModel.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = if (viewModel.pinned) "Unpin" else "Pin")
                }
                IconButton(onClick = { taskDialog = viewModel.suggestedTaskTitle() }) { Icon(Icons.Filled.AddTask, contentDescription = "Create task from note") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete note") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; confirmDelete = true })
                    }
                }
            },
        )
        val conflict by viewModel.conflict.collectAsStateWithLifecycle(initialValue = null)
        conflict?.let { issue -> ConflictBanner { viewModel.flush(); onCompare(issue.id) } }
        if (readOnly) Text("This note has formatting that needs the full editor, which is not available on this device. It is shown as text and kept unchanged.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        TextField(
            value = viewModel.title,
            onValueChange = viewModel::onTitle,
            readOnly = readOnly,
            placeholder = { Text("Title") },
            textStyle = MaterialTheme.typography.titleLarge,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().testTag("noteTitle"),
        )
        TextField(
            value = viewModel.body,
            onValueChange = viewModel::onBody,
            readOnly = readOnly,
            placeholder = { Text("Start writing") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("noteBody"),
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete note?") },
            text = { Text("You can undo right after.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch(Dispatchers.Main) {
                        val snap = viewModel.delete()
                        onClose()
                        if (snap != null) snackbar.showUndo("Note deleted") { viewModel.restore(snap) }
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    taskDialog?.let { initial ->
        CreateTaskDialog(initial, onDismiss = { taskDialog = null }) { title ->
            scope.launch {
                val error = viewModel.createTask(title)
                if (error == null) { taskDialog = null; snackbar.showSnackbar("Task added to Tasks") }
            }
        }
    }
}

@Composable
private fun CreateTaskDialog(initial: String, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create task") },
        text = {
            Column {
                Text("The note stays as it is. Edit the task title if you like.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = value, onValueChange = { value = it.take(TaskRules.TITLE_MAX) },
                    label = { Text("Task title") }, singleLine = true, modifier = Modifier.padding(top = 12.dp).testTag("noteTaskTitle"))
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(value) }, enabled = value.isNotBlank(), modifier = Modifier.testTag("confirmCreateTask")) { Text("Create task") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Suppress("unused")
private val unusedSpacer: @Composable () -> Unit = { Spacer(Modifier.width(0.dp)) }
