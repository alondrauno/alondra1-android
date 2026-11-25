package com.alondra1.app.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alondra1.app.data.settings.BackgroundCatalog
import com.alondra1.app.data.settings.BackgroundFit
import com.alondra1.app.data.settings.BackgroundPosition
import com.alondra1.app.data.settings.BackgroundSettings
import com.alondra1.app.data.settings.BackgroundStore
import com.alondra1.app.design.BackgroundPictures
import com.alondra1.app.design.blurSupported
import com.alondra1.app.ui.LocalContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Settings → Background: the same choices as Alondra1 for Windows. Changes apply at once. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BackgroundScreen(onBack: () -> Unit) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val bg = settings?.background?.normalized() ?: return
    var problem by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun save(value: BackgroundSettings) { scope.launch { container.settings.setBackground(value) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val path = container.backgrounds.import(context.contentResolver, uri, bg.customPath)
                container.settings.setBackground(bg.copy(enabled = true, image = BackgroundCatalog.CUSTOM, customPath = path))
            } catch (e: BackgroundStore.Problem) {
                problem = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Background") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.padding(top = 12.dp)) {
                ListItem(
                    headlineContent = { Text("Show a background behind the workspace") },
                    supportingContent = { Text("Pictures stay on this device.") },
                    trailingContent = { Switch(checked = bg.enabled, onCheckedChange = { save(bg.copy(enabled = it)) }, modifier = Modifier.testTag("backgroundEnabled")) },
                )
            }

            Section("Picture") {
                FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Tile(BackgroundCatalog.NONE, bg) { save(bg.copy(image = BackgroundCatalog.NONE)) }
                    BackgroundCatalog.builtIn.forEach { (id, _) -> Tile(id, bg) { save(bg.copy(enabled = true, image = id)) } }
                    if (bg.customPath != null) Tile(BackgroundCatalog.CUSTOM, bg) { save(bg.copy(enabled = true, image = BackgroundCatalog.CUSTOM)) }
                }
                HorizontalDivider()
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(if (busy) "Adding…" else "Choose picture…")
                    }
                    if (bg.customPath != null) {
                        TextButton(onClick = {
                            val old = bg.customPath
                            scope.launch {
                                container.settings.setBackground(bg.copy(image = if (bg.image == BackgroundCatalog.CUSTOM) BackgroundCatalog.DEFAULT else bg.image, customPath = null))
                                withContext(Dispatchers.IO) { container.backgrounds.delete(old) }
                            }
                        }) { Text("Remove my picture") }
                    }
                }
            }

            Section("Adjustments") {
                Adjust("Picture opacity", bg.opacity, 0f, BackgroundSettings.MAX_OPACITY, "${(bg.opacity * 100).roundToInt()}%") { save(bg.copy(opacity = it)) }
                Adjust("Blur", bg.blur, 0f, BackgroundSettings.MAX_BLUR, if (blurSupported) "${bg.blur.roundToInt()} px" else "Needs Android 12") { save(bg.copy(blur = it)) }
                Adjust("Desaturation", bg.desaturation, 0f, 1f, "${(bg.desaturation * 100).roundToInt()}%") { save(bg.copy(desaturation = it)) }
                Adjust("Brightness", bg.brightness, -0.5f, 0.5f, signedPercent(bg.brightness)) { save(bg.copy(brightness = it)) }
                Adjust("Overlay", bg.overlay, 0f, BackgroundSettings.MAX_OVERLAY, "${(bg.overlay * 100).roundToInt()}%") { save(bg.copy(overlay = it)) }
            }

            Section("Placement") {
                Options("Fit", BackgroundFit.entries.map { it.label to (it == bg.fit) }) { i -> save(bg.copy(fit = BackgroundFit.entries[i])) }
                HorizontalDivider()
                Options("Position", BackgroundPosition.entries.map { it.label to (it == bg.position) }) { i -> save(bg.copy(position = BackgroundPosition.entries[i])) }
            }

            TextButton(onClick = { save(bg.resetAdjustments()) }, modifier = Modifier.padding(top = 12.dp)) { Text("Reset adjustments to default") }
        }
    }

    problem?.let { message ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text("Background") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("OK") } },
        )
    }
}

private fun signedPercent(v: Float): String {
    val p = (v * 100).roundToInt()
    return if (p > 0) "+$p%" else "$p%"
}

@Composable
private fun Tile(id: String, current: BackgroundSettings, onPick: () -> Unit) {
    val context = LocalContext.current
    val selected = current.image == id && (current.enabled || id == BackgroundCatalog.NONE)
    val thumb by produceState<ImageBitmap?>(initialValue = null, id, current.customPath) {
        val decoded = if (id == BackgroundCatalog.NONE) null
        else withContext(Dispatchers.IO) { runCatching { BackgroundPictures.decode(context, id, current.customPath, 240)?.asImageBitmap() }.getOrNull() }
        value = decoded
    }
    Column(
        Modifier.width(92.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onPick).testTag("background:$id"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(92.dp, 60.dp).clip(RoundedCornerShape(8.dp))
                .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.background),
        ) {
            thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(BackgroundCatalog.name(id), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp).height(32.dp))
    }
}

@Composable
private fun Adjust(label: String, value: Float, min: Float, max: Float, shown: String, onChange: (Float) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row {
            Text(label, modifier = Modifier.weight(1f))
            Text(shown, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Saved when the finger lifts, so dragging doesn't write settings (and re-tone the picture) many times a second.
        Slider(
            value = dragging ?: value,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let(onChange); dragging = null },
            valueRange = min..max,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Options(label: String, options: List<Pair<String, Boolean>>, onPick: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            options.forEachIndexed { i, (text, on) ->
                androidx.compose.material3.FilterChip(selected = on, onClick = { onPick(i) }, label = { Text(text) })
            }
        }
    }
}
