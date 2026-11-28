package com.alondra1.app.features.tags

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alondra1.app.data.local.TagEntity
import com.alondra1.app.data.local.TagLinkEntity
import com.alondra1.app.domain.NoteRules
import com.alondra1.app.domain.TagException
import com.alondra1.app.domain.TagRules
import com.alondra1.app.ui.LocalContainer
import com.alondra1.app.ui.LocalSnackbar
import kotlinx.coroutines.launch

/** Tags and assigned links of this device, live. */
@Composable
fun rememberTags(): Pair<List<TagEntity>, List<TagLinkEntity>> {
    val container = LocalContainer.current
    // The flows are remembered: a new flow on every recomposition would restart collection endlessly.
    val tags by remember(container) { container.tags.observeTags() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val links by remember(container) { container.tags.observeLinks() }.collectAsStateWithLifecycle(initialValue = emptyList())
    return tags to links
}

fun tagsOn(tags: List<TagEntity>, links: List<TagLinkEntity>, type: String, ref: String): List<TagEntity> {
    val ids = links.filter { it.objectType == type && it.objectRef == ref }.map { it.tagId }.toSet()
    return tags.filter { it.id in ids }
}

/** The color hint of a tag (the name is always shown; color is never the only signal). */
@Composable
fun tagColor(color: String?): Color = when (color) {
    "gray" -> Color(0xFF6B7280); "red" -> Color(0xFFC62828); "orange" -> Color(0xFFC25400); "yellow" -> Color(0xFF9A7400)
    "green" -> Color(0xFF2E7D32); "teal" -> Color(0xFF00796B); "blue" -> Color(0xFF2F6FDE); "purple" -> Color(0xFF7B3FC4); "pink" -> Color(0xFFC2185B)
    else -> MaterialTheme.colorScheme.outline
}

private val COLOR_NAMES = mapOf("gray" to "Gray", "red" to "Red", "orange" to "Orange", "yellow" to "Yellow", "green" to "Green", "teal" to "Teal",
    "blue" to "Blue", "purple" to "Purple", "pink" to "Pink")

/**
 * The tags of one item: a chip per tag (tap × to remove) and "Tag", which opens the picker. [scroll] puts everything
 * in one scrolling row (the note editor, where height is scarce).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagSection(type: String, itemId: String, modifier: Modifier = Modifier, scroll: Boolean = false) {
    val container = LocalContainer.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val (tags, links) = rememberTags()
    val mine = tagsOn(tags, links, type, itemId)
    var picking by remember { mutableStateOf(false) }
    fun run(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: TagException) { snackbar.showSnackbar(e.message ?: "Could not change the tags.") }
    }

    val content: @Composable () -> Unit = {
        mine.forEach { tag ->
            InputChip(
                selected = false, onClick = { run { container.tags.setTag(type, listOf(itemId), tag.id, false) } },
                label = { Text(tag.name, maxLines = 1) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove tag ${tag.name}", Modifier.size(16.dp)) },
                border = BorderStroke(1.dp, tagColor(tag.color)),
                modifier = Modifier.testTag("tag:${tag.name}"),
            )
        }
        AssistChip(onClick = { picking = true }, label = { Text(if (mine.isEmpty()) "Add tag" else "Tag") },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp)) }, modifier = Modifier.testTag("addTag"))
    }
    if (scroll) {
        Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    } else {
        Column(modifier.fillMaxWidth()) {
            Text("Tags", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
    if (picking) {
        TagPickerDialog(tags, mine.map { it.id }.toSet(), onDismiss = { picking = false },
            onToggle = { tag, on -> run { container.tags.setTag(type, listOf(itemId), tag.id, on) } },
            onCreate = { name -> run { container.tags.setTag(type, listOf(itemId), container.tags.findOrCreate(name).id, true) } })
    }
}

/** Search the tags, check or uncheck them, or create one from what was typed. */
@Composable
fun TagPickerDialog(tags: List<TagEntity>, selected: Set<String>, onDismiss: () -> Unit, onToggle: (TagEntity, Boolean) -> Unit, onCreate: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val key = TagRules.nameKey(query)
    val shown = tags.filter { query.isBlank() || it.nameKey.contains(key) }
    val exact = tags.any { it.nameKey == key }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it.take(TagRules.MAX_NAME) }, singleLine = true, label = { Text("Find or create a tag") },
                    modifier = Modifier.fillMaxWidth().testTag("tagQuery"))
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (query.isNotBlank() && !exact) {
                        Text("Create “${query.trim()}”", color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clickable { onCreate(query); query = "" }.padding(vertical = 12.dp).testTag("createTag"))
                    }
                    shown.forEach { tag ->
                        val on = tag.id in selected
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onToggle(tag, !on) }.padding(vertical = 4.dp).testTag("pick:${tag.name}")) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Text(tag.name, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                    if (tags.isEmpty() && query.isBlank()) {
                        Text("No tags yet. Type a name to create one.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Live items per tag and type: links to deleted items are not counted (the cloud removes them with the item). */
@Composable
private fun rememberUsage(): Triple<List<TagEntity>, Map<String, Map<String, Int>>, LiveItems> {
    val container = LocalContainer.current
    val (tags, links) = rememberTags()
    val tasks by remember(container) { container.todo.observeTasks() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val notes by remember(container) { container.notes.observeNotes() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val events by remember(container) { container.calendar.observeEvents() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val live = LiveItems(tasks.associate { it.id to it.title }, notes.associate { it.id to NoteRules.displayTitle(it) }, events.associate { it.id to it.title }, links)
    val counts = links.filter { live.exists(it) }.groupBy { it.tagId }.mapValues { (_, l) -> l.groupingBy { it.objectType }.eachCount() }
    return Triple(tags, counts, live)
}

private class LiveItems(val tasks: Map<String, String>, val notes: Map<String, String>, val events: Map<String, String>, val links: List<TagLinkEntity>) {
    fun exists(l: TagLinkEntity) = when (l.objectType) {
        TagRules.TASK -> l.objectRef in tasks; TagRules.NOTE -> l.objectRef in notes; TagRules.EVENT -> l.objectRef in events; else -> true
    }
}

/** Settings → Tags: add, rename, color, delete (items are kept), and Show items. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(onBack: () -> Unit, onShowItems: (String) -> Unit) {
    val container = LocalContainer.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val (tags, counts, _) = rememberUsage()
    var name by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<TagEntity?>(null) }
    var deleting by remember { mutableStateOf<TagEntity?>(null) }
    fun run(block: suspend () -> Unit) = scope.launch { try { block() } catch (e: TagException) { snackbar.showSnackbar(e.message ?: "Could not change the tag.") } }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Tags") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Tags label tasks, notes and events across modules (and mail in the Windows app). They sync with Cloud Sync.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(TagRules.MAX_NAME) }, singleLine = true, label = { Text("New tag") },
                    modifier = Modifier.weight(1f).testTag("newTagName"))
                OutlinedButton(enabled = name.isNotBlank(), onClick = { run { container.tags.create(name); name = "" } }, modifier = Modifier.testTag("addTagButton")) { Text("Add") }
            }
            if (tags.isEmpty()) Text("No tags yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column {
                    tags.forEachIndexed { i, tag ->
                        if (i > 0) HorizontalDivider()
                        var menu by remember { mutableStateOf(false) }
                        ListItem(
                            headlineContent = { Text(tag.name) },
                            supportingContent = { Text(TagRules.describeUsage(counts[tag.id].orEmpty()) + (tag.color?.let { " · ${COLOR_NAMES[it]}" } ?: "")) },
                            leadingContent = { Surface(shape = MaterialTheme.shapes.small, color = tagColor(tag.color), modifier = Modifier.size(12.dp)) {} },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = { onShowItems(tag.id) }) { Text("Show items") }
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${tag.name}") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = tag })
                                        DropdownMenuItem(text = { Text("No color") }, onClick = { menu = false; run { container.tags.setColor(tag.id, null) } })
                                        TagRules.COLORS.forEach { c ->
                                            DropdownMenuItem(text = { Text(COLOR_NAMES.getValue(c)) },
                                                leadingIcon = { Surface(shape = MaterialTheme.shapes.small, color = tagColor(c), modifier = Modifier.size(12.dp)) {} },
                                                onClick = { menu = false; run { container.tags.setColor(tag.id, c) } })
                                        }
                                        DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = tag })
                                    }
                                }
                            },
                            modifier = Modifier.testTag("tagRow:${tag.name}"),
                        )
                    }
                }
            }
        }
    }
    renaming?.let { tag ->
        var text by remember(tag.id) { mutableStateOf(tag.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename tag") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(TagRules.MAX_NAME) }, singleLine = true, modifier = Modifier.testTag("renameTag")) },
            confirmButton = { TextButton(onClick = { renaming = null; run { container.tags.rename(tag.id, text) } }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { tag ->
        val usage = TagRules.describeUsage(counts[tag.id].orEmpty())
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete “${tag.name}”?") },
            text = { Text((if (counts[tag.id].isNullOrEmpty()) "It isn't used." else "It is removed from $usage.") + " The items themselves are kept.") },
            confirmButton = { TextButton(onClick = { deleting = null; run { container.tags.delete(tag.id) } }, modifier = Modifier.testTag("confirmDeleteTag")) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

/** Everything with one tag, across To-do, Notes and Calendar; mail tagged on Windows is counted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaggedItemsScreen(tagId: String, onBack: () -> Unit, onTask: (String) -> Unit, onNote: (String) -> Unit, onEvent: (String) -> Unit) {
    val (tags, counts, live) = rememberUsage()
    val tag = tags.firstOrNull { it.id == tagId }
    val mine = live.links.filter { it.tagId == tagId && live.exists(it) }
    fun of(type: String, names: Map<String, String>) = mine.filter { it.objectType == type }.mapNotNull { l -> names[l.objectRef]?.let { l.objectRef to it } }
    val sections = listOf(Triple("Tasks", of(TagRules.TASK, live.tasks), onTask), Triple("Notes", of(TagRules.NOTE, live.notes), onNote), Triple("Events", of(TagRules.EVENT, live.events), onEvent))
    val mail = counts[tagId]?.get(TagRules.MAIL) ?: 0

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(tag?.let { "#${it.name}" } ?: "Tag") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            if (tag == null) { Text("This tag no longer exists. It may have been deleted on another device."); return@Column }
            if (sections.all { it.second.isEmpty() } && mail == 0) Text("Nothing has this tag yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            sections.filter { it.second.isNotEmpty() }.forEach { (title, items, open) ->
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column { items.forEach { (id, label) -> ListItem(headlineContent = { Text(label) }, modifier = Modifier.clickable { open(id) }.testTag("tagged:$label")) } }
                }
            }
            if (mail > 0) Text(if (mail == 1) "1 message with this tag is in the Windows app (mail stays on that PC)." else "$mail messages with this tag are in the Windows app (mail stays on that PC).",
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        }
    }
}
