package com.alondra1.app.data.settings

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.max

/**
 * The user's own background picture. A chosen picture is checked by content, scaled to at most [STORED_LONG_EDGE]
 * pixels and stored as an app-private JPEG copy, so it survives the original being moved or deleted. Only files inside
 * [dir] are ever deleted.
 */
class BackgroundStore(private val dir: File) {

    class Problem(message: String) : Exception(message)

    /** Stores a copy of the picture at [uri] and returns its path; deletes [previous]. Throws [Problem] for the user. */
    suspend fun import(resolver: ContentResolver, uri: Uri, previous: String?): String = withContext(Dispatchers.IO) {
        val bytes = try {
            resolver.openInputStream(uri)?.use { input -> readLimited(input) } ?: throw Problem("The picture could not be opened.")
        } catch (e: IOException) {
            throw Problem("The picture could not be read: ${e.message}")
        } catch (e: SecurityException) {
            throw Problem("Alondra1 was not allowed to read this picture.")
        }
        if (bytes.isEmpty()) throw Problem("The file is empty.")
        if (BackgroundImageFile.detect(bytes.copyOfRange(0, minOf(16, bytes.size))) == BackgroundImageFile.Kind.UNKNOWN) {
            throw Problem("Choose a PNG, JPEG, or WebP picture.")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 2 || bounds.outHeight < 2) throw Problem("The picture could not be read, or it is too small.")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= STORED_LONG_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw Problem("The picture could not be read.")
        val scale = minOf(1f, STORED_LONG_EDGE.toFloat() / max(decoded.width, decoded.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(2), (decoded.height * scale).toInt().coerceAtLeast(2), true) else decoded
        dir.mkdirs()
        val target = File(dir, "custom-${UUID.randomUUID().toString().replace("-", "")}.jpg")
        target.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        delete(previous)
        target.path
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path).canonicalFile
        val root = dir.canonicalFile
        if (file.parentFile == root && file.isFile) file.delete()
    }

    /** Deletes every stored picture (Delete local data). */
    fun deleteAll() {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > BackgroundImageFile.MAX_BYTES) throw Problem("The picture is larger than ${BackgroundImageFile.MAX_BYTES / (1024 * 1024)} MB.")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val STORED_LONG_EDGE = 2560
    }
}
