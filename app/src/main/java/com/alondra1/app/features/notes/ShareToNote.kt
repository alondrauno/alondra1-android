package com.alondra1.app.features.notes

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.alondra1.app.AppContainer
import com.alondra1.app.data.repository.NoteAssetException
import kotlinx.serialization.json.JsonPrimitive

/**
 * Android share sheet → Alondra1 (Notes spec §6.1, §12): shared text becomes a note; shared pictures become a note with
 * those pictures (stored as assets, checked from their bytes). Nothing is fetched from the web.
 */
object ShareToNote {
    suspend fun create(container: AppContainer, resolver: ContentResolver, intent: Intent): String? {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return null
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().take(500)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val uris: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) streams(intent) else listOfNotNull(stream(intent))
        val nodes = mutableListOf<String>()
        val assets = mutableListOf<String>()
        text.replace("\r\n", "\n").split('\n').filter { text.isNotEmpty() }.forEach { line ->
            nodes += if (line.isEmpty()) """{"type":"paragraph"}""" else """{"type":"paragraph","content":[{"type":"text","text":${JsonPrimitive(line)}}]}"""
        }
        for (uri in uris.take(20)) {
            val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: continue
            try {
                val asset = container.noteAssets.add(bytes)
                assets += asset.sha256
                nodes += """{"type":"image","attrs":{"assetId":"${asset.sha256}"${if ((asset.width ?: 0) > 600) ",\"width\":600" else ""}}}"""
            } catch (e: NoteAssetException) {
                nodes += """{"type":"paragraph","content":[{"type":"text","text":${JsonPrimitive("[Not added: ${e.message}]")}}]}"""
            }
        }
        if (nodes.isEmpty()) return null
        val doc = """{"schemaVersion":1,"type":"doc","content":[${nodes.joinToString(",")}]}"""
        return container.notes.create(title = subject, docJson = doc, text = text, assets = assets).id
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}
