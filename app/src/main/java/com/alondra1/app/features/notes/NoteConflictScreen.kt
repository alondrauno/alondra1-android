package com.alondra1.app.features.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alondra1.app.AppContainer
import com.alondra1.app.data.local.NoteEntity
import com.alondra1.app.data.local.SyncIssueEntity
import com.alondra1.app.data.repository.NoteStorageException
import com.alondra1.app.data.repository.NotesRepository
import com.alondra1.app.domain.DiffLine
import com.alondra1.app.domain.DiffSide
import com.alondra1.app.domain.NoteConflictChoice
import com.alondra1.app.domain.NoteMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Opens the compare screen for a sync issue (provided by the navigation host; used by Settings → Sync issues). */
val LocalOpenNoteConflict = staticCompositionLocalOf<(Long) -> Unit> { {} }

private data class Versions(val issue: SyncIssueEntity?, val other: NoteEntity?, val mine: NoteEntity?)

/**
 * Notes spec §10.2: the two versions of a note that conflicted during sync — the other device's (the note) and this
 * device's (its conflict copy) — with the lines only one of them has marked. The user keeps one, combines both into
 * the note to finish by hand, or keeps both as separate notes. Same choices as the Windows app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteConflictScreen(container: AppContainer, issueId: Long, onClose: () -> Unit, onResolved: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<Versions?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(issueId) {
        val issue = container.db.sync().issue(issueId)
        versions = Versions(
            issue,
            issue?.let { container.notes.getNote(it.entityId)?.takeIf { n -> n.deletedUtc == null } },
            issue?.copyId?.let { container.notes.getNote(it)?.takeIf { n -> n.deletedUtc == null } },
        )
    }

    fun choose(choice: NoteConflictChoice) {
        val v = versions ?: return
        val issue = v.issue ?: return
        val copyId = issue.copyId ?: return
        // Navigation must run on the main thread; database calls may resume elsewhere.
        scope.launch(Dispatchers.Main) {
            busy = true
            try {
                val show = container.notes.resolveConflict(issue.entityId, copyId, choice)
                container.db.sync().dismissIssue(issue.id)
                withContext(Dispatchers.Main) { onResolved(show) }
            } catch (e: NoteStorageException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Compare versions") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
        val v = versions
        if (v == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        if (v.issue == null || v.mine == null) {
            // Already resolved, or the conflict copy was deleted: nothing left to compare.
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This device's version is no longer there (it was deleted or already resolved).")
                Button(onClick = { scope.launch(Dispatchers.Main) { v.issue?.let { container.db.sync().dismissIssue(it.id) }; withContext(Dispatchers.Main) { onClose() } } }) { Text("OK") }
            }
            return@Column
        }
        val lines = remember(v) { NoteMerge.diffLines(v.other?.body.orEmpty(), v.mine.body) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (v.other == null) "The note was deleted on another device while you edited it here. Your version was kept."
                else "The note was changed on another device and on this one before they synced. Nothing was lost. Lines only in one version are marked.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Version("Other device", v.other, lines, DiffSide.ONLY_OTHER, Modifier.testTag("version:other"))
            Version("This device", v.mine, lines, DiffSide.ONLY_MINE, Modifier.testTag("version:mine"))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (v.other != null) {
                Button(onClick = { choose(NoteConflictChoice.COMBINE) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("conflict:combine")) { Text("Combine both") }
                OutlinedButton(onClick = { choose(NoteConflictChoice.KEEP_MINE) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("conflict:mine")) { Text("Keep this device's version") }
                OutlinedButton(onClick = { choose(NoteConflictChoice.KEEP_OTHER) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("conflict:other")) { Text("Keep other device's version") }
            } else {
                Button(onClick = { choose(NoteConflictChoice.KEEP_MINE) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("conflict:mine")) { Text("Keep my version") }
                OutlinedButton(onClick = { choose(NoteConflictChoice.KEEP_OTHER) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("conflict:other")) { Text("Delete my version") }
            }
            TextButton(onClick = { choose(NoteConflictChoice.KEEP_BOTH) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("conflict:both")) { Text("Keep both as separate notes") }
        }
    }
}

@Composable
private fun Version(heading: String, note: NoteEntity?, lines: List<DiffLine>, own: DiffSide, modifier: Modifier) {
    val fmt = remember { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(heading, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (note == null) {
                Text("Deleted on that device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(NoteMerge.originalTitle(note.title).ifBlank { "Untitled" }, fontWeight = FontWeight.SemiBold)
            val pictures = NotesRepository.decodeIds(note.assetsJson).size
            val links = NotesRepository.decodeLinks(note.linksJson).size
            Text(buildString {
                append("Edited ").append(note.modifiedUtc.atZone(ZoneId.systemDefault()).format(fmt))
                if (pictures > 0) append(" · $pictures picture${if (pictures == 1) "" else "s"}")
                if (links > 0) append(" · $links link${if (links == 1) "" else "s"}")
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lines.filter { it.side == DiffSide.BOTH || it.side == own }.take(2000).forEach { line ->
                if (line.side == own) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(MaterialTheme.colorScheme.secondaryContainer).semantics { contentDescription = "Only in this version: ${line.text}" }) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.tertiary))
                        Text(line.text.ifEmpty { " " }, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                } else {
                    Text(line.text.ifEmpty { " " }, Modifier.padding(start = 9.dp, top = 1.dp, bottom = 1.dp))
                }
            }
        }
    }
}

/** Shown above an open note that is part of an unresolved sync conflict. */
@Composable
fun ConflictBanner(onCompare: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("conflictBanner")) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Changed on another device too. Both versions were kept.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = onCompare, modifier = Modifier.testTag("compareVersions")) { Text("Compare") }
        }
    }
}
