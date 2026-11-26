package com.alondra1.app.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.alondra1.app.R

/** An entry of the header's overflow menu (sorting, list options): secondary actions stay out of the title row. */
data class MenuAction(val label: String, val destructive: Boolean = false, val checked: Boolean = false, val onClick: () -> Unit)

/**
 * The header of the four primary screens and the list screens (Android UI spec §5.1): a compact brand row (logo,
 * name, the internal build badge, then search, a secondary-actions menu and the account entry), then the title with
 * its icon, a date or subtitle beneath and an unobtrusive count on the right. With [onBack] the brand row becomes a
 * back row. Status bar insets are handled here.
 */
@Composable
fun AppHeader(
    title: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    count: String? = null,
    onBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onAccount: (() -> Unit)? = null,
    menu: List<MenuAction> = emptyList(),
    titleModifier: Modifier = Modifier,
    devBadge: Boolean = com.alondra1.app.cloud.discovery.BuildEnvironment.isDev,
) {
    Column(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = if (onBack != null) Space.xs else Space.screen, end = Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = Space.touch)) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            } else {
                Image(painterResource(R.drawable.alondra1_logo), contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(Space.s))
                Text("Alondra1", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Internal builds only; inline so it never shifts the header (spec §3.3).
            if (devBadge) {
                Spacer(Modifier.width(Space.s))
                Surface(color = Color(0xFFC2410C), shape = RoundedCornerShape(6.dp), modifier = Modifier.semantics { contentDescription = "Development build" }) {
                    Text("DEV", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            if (onSearch != null) IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, contentDescription = "Search") }
            if (menu.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }, modifier = Modifier.testTag("headerMenu")) { Icon(Icons.Filled.MoreVert, contentDescription = "More options") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        menu.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.label, color = if (item.destructive) MaterialTheme.colorScheme.error else Color.Unspecified) },
                                trailingIcon = if (item.checked) ({ Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", modifier = Modifier.size(18.dp)) }) else null,
                                onClick = { open = false; item.onClick() },
                            )
                        }
                    }
                }
            }
            if (onAccount != null) IconButton(onClick = onAccount) { Icon(Icons.Outlined.AccountCircle, contentDescription = "Account and settings") }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().padding(start = if (onBack != null) Space.l else 0.dp, end = Space.l, top = Space.xs, bottom = Space.m),
        ) {
            Column(Modifier.weight(1f).then(titleModifier)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(Space.s))
                    }
                    Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                }
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (count != null) {
                Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Space.s, bottom = 2.dp))
            }
        }
    }
}

/** Metadata of a task card (spec §5.2): small, one line, joined with " · ". */
data class CardMeta(
    val parts: List<String> = emptyList(),
    val due: String? = null,
    val overdue: Boolean = false,
    val repeats: Boolean = false,
    val reminder: Boolean = false,
    val attachments: Int = 0,
    val stepsDone: Int = 0,
    val stepsTotal: Int = 0,
)

/**
 * A task card (spec §5.2): circular checkbox, title, one metadata line, star. The checkbox, the card and the star are
 * separate 48 dp targets so a tap on one never triggers another.
 */
@Composable
fun TaskCard(
    title: String,
    completed: Boolean,
    important: Boolean,
    meta: CardMeta,
    onOpen: () -> Unit,
    onToggleComplete: () -> Unit,
    onToggleImportant: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    val extra = LocalExtra.current
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = Radius.card,
        color = if (selected) colors.primaryContainer else colors.surfaceContainer,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClickLabel = "Open task", onClick = onOpen),
    ) {
        Column {
            Row(Modifier.padding(start = Space.xs, end = Space.xs, top = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleComplete, modifier = Modifier.semantics { stateDescription = if (completed) "Completed" else "Not completed" }) {
                    Icon(
                        if (completed) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                        contentDescription = if (completed) "Mark $title as not completed" else "Complete $title",
                        tint = if (completed) colors.primary else colors.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        textDecoration = if (completed) TextDecoration.LineThrough else null,
                        color = if (completed) colors.onSurfaceVariant else colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MetaLine(meta, extra)
                }
                IconButton(onClick = onToggleImportant, modifier = Modifier.semantics { stateDescription = if (important) "Important" else "Not important" }) {
                    Icon(
                        if (important) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (important) "Remove importance" else "Mark as important",
                        tint = if (important) extra.star else colors.onSurfaceVariant,
                    )
                }
            }
            // A restrained progress accent, only for tasks with steps (never a meaningless bar).
            if (meta.stepsTotal > 0 && !completed) {
                LinearProgressIndicator(
                    progress = { meta.stepsDone.toFloat() / meta.stepsTotal },
                    color = extra.progress,
                    trackColor = colors.outlineVariant,
                    drawStopIndicator = {},
                    modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = Space.l, bottom = Space.m).height(3.dp)
                        .clearAndSetSemantics { },
                )
            }
        }
    }
}

@Composable
private fun MetaLine(meta: CardMeta, extra: Extra) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodySmall
    val hasAny = meta.parts.isNotEmpty() || meta.due != null || meta.repeats || meta.reminder || meta.attachments > 0 || meta.stepsTotal > 0
    if (!hasAny) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 2.dp)) {
        if (meta.parts.isNotEmpty()) Text(meta.parts.joinToString(" · "), style = style, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        meta.due?.let { due ->
            val color = if (meta.overdue) extra.overdue else MaterialTheme.colorScheme.primary
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { if (meta.overdue) stateDescription = "Overdue" }) {
                Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(13.dp), tint = color)
                Spacer(Modifier.width(3.dp))
                Text(if (meta.overdue) "Overdue · $due" else due, style = style, color = color, maxLines = 1)
            }
        }
        if (meta.repeats) Icon(Icons.Outlined.Repeat, contentDescription = "Repeats", modifier = Modifier.size(13.dp), tint = muted)
        if (meta.reminder) Icon(Icons.Outlined.Notifications, contentDescription = "Has a reminder", modifier = Modifier.size(13.dp), tint = muted)
        if (meta.stepsTotal > 0) Text("${meta.stepsDone}/${meta.stepsTotal}", style = style, color = muted, modifier = Modifier.semantics { contentDescription = "${meta.stepsDone} of ${meta.stepsTotal} steps done" })
        if (meta.attachments > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = if (meta.attachments == 1) "1 attachment" else "${meta.attachments} attachments"
            }.testTag("attachmentCount")) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(13.dp), tint = muted)
                Text(meta.attachments.toString(), style = style, color = muted)
            }
        }
    }
}

/** A compact empty state (spec §5.3): small icon, one line and a hint, subordinate to the header and composer. */
@Composable
fun CompactEmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = Space.screen * 2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp)) }
        }
        Spacer(Modifier.height(Space.m))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.xs))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A section label in lists (Planned date groups, Lists sections). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = Space.m, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() })
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row of the Lists screen: icon, name, count; a 56 dp target. */
@Composable
fun ListRow(icon: ImageVector, label: String, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier, iconTint: Color = MaterialTheme.colorScheme.primary,
            trailing: (@Composable () -> Unit)? = null) {
    Surface(
        shape = Radius.control,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(start = Space.l, end = if (trailing != null) Space.xs else Space.l), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Space.l))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(vertical = Space.l), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count > 0) Text(count.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "$count open" })
            trailing?.invoke()
        }
    }
}

@Suppress("unused")
private val keep = PaddingValues(0.dp)
