package com.alondra1.app.features.todo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alondra1.app.data.local.TaskListEntity
import com.alondra1.app.design.LocalExtra
import com.alondra1.app.design.Radius
import com.alondra1.app.design.Space
import com.alondra1.app.domain.TaskAttachment
import com.alondra1.app.domain.TaskRules
import com.alondra1.app.ui.DatePickerDialogFor
import com.alondra1.app.ui.Format
import kotlinx.coroutines.launch
import java.time.LocalDate

/** What the composer adds: the chosen values are applied on top of the screen's own rules (My Day, the list). */
data class NewTask(val title: String, val due: LocalDate?, val important: Boolean, val listId: String?, val attachments: List<TaskAttachment>)

/**
 * The add-task composer (Android UI spec §5.4). Collapsed it is one rounded "+ Add a task" control above the bottom
 * navigation; expanded it holds the title, equal secondary actions (due date, list, importance, attachment) and one
 * clearly primary "Add task". It stays above the keyboard; the screen hides the bottom navigation while typing.
 * [onAdd] returns an error message, or null when the task was added (the composer then clears for the next one).
 */
@Composable
fun TaskComposer(
    hint: String,
    lists: List<TaskListEntity>,
    chooseList: Boolean,
    onAdd: suspend (NewTask) -> String?,
    modifier: Modifier = Modifier,
    startExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(startExpanded) }
    var text by rememberSaveable { mutableStateOf("") }
    var due by rememberSaveable { mutableStateOf<LocalDate?>(null) }
    var important by rememberSaveable { mutableStateOf(false) }
    var listId by rememberSaveable { mutableStateOf<String?>(null) }
    val staged = remember { mutableStateListOf<TaskAttachment>() }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val today = LocalDate.now()
    val attach = rememberAttachmentPicker({ staged.size }) { staged += it }

    fun reset() { text = ""; due = null; important = false; listId = null; staged.clear(); error = null }
    fun submit() {
        if (text.isBlank()) return
        scope.launch {
            error = onAdd(NewTask(text, due, important, listId, staged.toList()))
            if (error == null) reset()
        }
    }

    BackHandler(enabled = expanded) { expanded = false }

    Box(modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s)) {
        if (!expanded) {
            Surface(
                shape = Radius.composer,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clickable(role = Role.Button, onClickLabel = "Add a task") { expanded = true }
                    .semantics { contentDescription = hint }
                    .testTag("composer"),
            ) {
                Row(Modifier.padding(horizontal = Space.l), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(Space.m))
                    Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            return@Box
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Surface(
            shape = Radius.composer,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().testTag("composerExpanded"),
        ) {
            Column(Modifier.padding(start = Space.xs, end = Space.s, top = Space.xs, bottom = Space.s)) {
                TextField(
                    value = text,
                    onValueChange = { text = it.take(TaskRules.TITLE_MAX); error = null },
                    placeholder = { Text("Task title") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("quickAdd"),
                )
                // Chosen values as removable chips, so it is always visible what will be added.
                val chips = due != null || listId != null || staged.isNotEmpty()
                if (chips) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        due?.let { d ->
                            InputChip(selected = false, onClick = { due = null }, label = { Text("Due " + Format.relativeDate(d, today)) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove due date", modifier = Modifier.size(16.dp)) })
                        }
                        listId?.let { id ->
                            InputChip(selected = false, onClick = { listId = null }, label = { Text(lists.firstOrNull { it.id == id }?.name ?: "List") },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Use the default list", modifier = Modifier.size(16.dp)) })
                        }
                        staged.forEach { a ->
                            InputChip(selected = false, onClick = { staged.remove(a) }, label = { Text(a.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove ${a.fileName}", modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.testTag("staged:${a.fileName}"))
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Space.m)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ComposerAction("Due date") { IconButton(onClick = { picking = true }) { Icon(Icons.Outlined.CalendarToday, contentDescription = "Set a due date") } }
                    if (chooseList && lists.size > 1) {
                        Box {
                            ComposerAction("List") { IconButton(onClick = { listMenu = true }) { Icon(Icons.AutoMirrored.Outlined.List, contentDescription = "Choose a list") } }
                            DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                                lists.forEach { l -> DropdownMenuItem(text = { Text(l.name) }, onClick = { listId = l.id; listMenu = false }) }
                            }
                        }
                    }
                    ComposerAction("Important") {
                        IconToggleButton(checked = important, onCheckedChange = { important = it }) {
                            Icon(if (important) Icons.Filled.Star else Icons.Outlined.StarOutline, contentDescription = if (important) "Important, on" else "Important, off",
                                tint = if (important) LocalExtra.current.star else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ComposerAction("Attach files") { IconButton(onClick = attach, modifier = Modifier.testTag("composerAttach")) { Icon(Icons.Outlined.AttachFile, contentDescription = "Attach files") } }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { expanded = false }) { Icon(Icons.Filled.Close, contentDescription = "Close the composer") }
                    Button(onClick = { submit() }, enabled = text.isNotBlank(), modifier = Modifier.testTag("quickAddButton")) { Text("Add task") }
                }
            }
        }
    }
    if (picking) DatePickerDialogFor(due ?: today, onDismiss = { picking = false }, onPicked = { due = it })
}

/** A secondary composer action with a tooltip naming it (long-press). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ComposerAction(label: String, content: @Composable () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        content()
    }
}
