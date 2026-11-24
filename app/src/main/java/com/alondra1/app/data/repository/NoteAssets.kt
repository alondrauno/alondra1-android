package com.alondra1.app.data.repository

import com.alondra1.app.core.TimeSource
import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.local.NoteAssetEntity
import java.io.File
import java.security.MessageDigest

class NoteAssetException(message: String) : Exception(message)

/**
 * Pictures used in notes (Notes spec §6.3): checked from their bytes (PNG, JPEG, GIF, WebP; never from the file
 * name), size-limited, stored once per SHA-256 below files/notes-assets and referenced from documents by that hash.
 * Same rules as the Windows app, so both reference the same picture by the same id.
 */
class NoteAssets(private val db: AppDatabase, val directory: File, private val time: TimeSource, val maxBytes: Long = 20L shl 20,
                 val maxBytesPerNote: Long = 100L shl 20) {

    fun fileOf(sha: String) = File(File(directory, sha.take(2)), sha)
    fun hasFile(sha: String) = isId(sha) && fileOf(sha).isFile

    /**
     * Stores a picture (or returns the stored one with the same content). When [noteAssetIds] (the pictures the note
     * already uses) is given, the note's pictures may not total more than [maxBytesPerNote].
     */
    suspend fun add(data: ByteArray, noteAssetIds: Collection<String>? = null): NoteAssetEntity {
        if (data.size > maxBytes) throw NoteAssetException("The picture is larger than ${maxBytes shr 20} MB.")
        val mime = sniff(data) ?: throw NoteAssetException("Only PNG, JPEG, GIF and WebP pictures can be added.")
        val sha = hash(data)
        if (noteAssetIds != null && sha !in noteAssetIds) {
            val total = data.size.toLong() + noteAssetIds.distinct().sumOf { db.noteAssets().get(it)?.bytes ?: 0L }
            if (total > maxBytesPerNote) throw NoteAssetException("Pictures in one note can total at most ${maxBytesPerNote shr 20} MB.")
        }
        val existing = db.noteAssets().get(sha)
        val file = fileOf(sha)
        if (existing != null && existing.present && file.isFile) return existing
        file.parentFile!!.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.writeBytes(data)
        if (!temporary.renameTo(file)) { file.delete(); temporary.renameTo(file) }
        val (w, h) = dimensions(mime, data)
        val asset = NoteAssetEntity(sha, mime, data.size.toLong(), w, h, existing?.createdUtc ?: time.now(), uploaded = existing?.uploaded ?: false, present = true)
        db.noteAssets().put(asset)
        return asset
    }

    /** Bytes and type of a stored picture, or null when it is not on this device (yet). */
    suspend fun read(sha: String): Pair<NoteAssetEntity, ByteArray>? {
        if (!isId(sha)) return null
        val asset = db.noteAssets().get(sha) ?: return null
        val file = fileOf(sha)
        return if (asset.present && file.isFile) asset to file.readBytes() else null
    }

    /** Remembers pictures a note references that are not here yet (Cloud Sync downloads them). */
    suspend fun expect(ids: Collection<String>) {
        ids.filter { isId(it) && !hasFile(it) && db.noteAssets().get(it)?.present != true }.forEach {
            db.noteAssets().put(NoteAssetEntity(it, "", 0, createdUtc = time.now(), uploaded = true, present = false))
        }
    }

    /** A downloaded picture is kept only if its bytes match the hash and are a picture. */
    suspend fun addDownloaded(sha: String, data: ByteArray): Boolean {
        if (hash(data) != sha || sniff(data) == null) return false
        add(data)
        db.noteAssets().markUploaded(sha)
        return true
    }

    companion object {
        fun isId(s: String) = s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

        fun hash(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        fun sniff(d: ByteArray): String? = when {
            d.size >= 8 && d[0] == 0x89.toByte() && d[1] == 'P'.code.toByte() && d[2] == 'N'.code.toByte() && d[3] == 'G'.code.toByte() -> "image/png"
            d.size >= 3 && d[0] == 0xFF.toByte() && d[1] == 0xD8.toByte() && d[2] == 0xFF.toByte() -> "image/jpeg"
            d.size >= 6 && String(d, 0, 6, Charsets.US_ASCII).let { it == "GIF87a" || it == "GIF89a" } -> "image/gif"
            d.size >= 12 && String(d, 0, 4, Charsets.US_ASCII) == "RIFF" && String(d, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }

        private fun u16le(d: ByteArray, i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
        private fun u16be(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
        private fun u32be(d: ByteArray, i: Int) = (u16be(d, i) shl 16) or u16be(d, i + 2)

        /** Pixel size from the header (no decoding), or nulls. */
        fun dimensions(mime: String, d: ByteArray): Pair<Int?, Int?> = runCatching<Pair<Int?, Int?>> {
            when (mime) {
                "image/png" -> u32be(d, 16) to u32be(d, 20)
                "image/gif" -> u16le(d, 6) to u16le(d, 8)
                "image/webp" -> when (String(d, 12, 4, Charsets.US_ASCII)) {
                    "VP8X" -> (1 + ((d[24].toInt() and 0xFF) or ((d[25].toInt() and 0xFF) shl 8) or ((d[26].toInt() and 0xFF) shl 16))) to
                        (1 + ((d[27].toInt() and 0xFF) or ((d[28].toInt() and 0xFF) shl 8) or ((d[29].toInt() and 0xFF) shl 16)))
                    "VP8 " -> (u16le(d, 26) and 0x3fff) to (u16le(d, 28) and 0x3fff)
                    else -> null to null
                }
                "image/jpeg" -> {
                    var i = 2
                    var result: Pair<Int?, Int?> = null to null
                    while (i + 9 < d.size) {
                        if (d[i] != 0xFF.toByte()) { i++; continue }
                        val marker = d[i + 1].toInt() and 0xFF
                        if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) { result = u16be(d, i + 7) to u16be(d, i + 5); break }
                        i += if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) 2 else 2 + u16be(d, i + 2)
                    }
                    result
                }
                else -> null to null
            }
        }.getOrDefault(null to null)
    }
}
