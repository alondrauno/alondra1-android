package com.alondra1.app.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** How the user resolves a note conflict (Notes spec §10.2: user selection or manual merge). Same choices on Windows. */
enum class NoteConflictChoice {
    /** Keep the other device's version (the note); the conflict copy goes to Trash. */
    KEEP_OTHER,
    /** Keep this device's version: it replaces the note's content; the conflict copy goes to Trash. */
    KEEP_MINE,
    /** Put both versions into the note (shared parts once), to finish by hand; the conflict copy goes to Trash. */
    COMBINE,
    /** Keep both as separate notes. */
    KEEP_BOTH,
}

enum class DiffSide { BOTH, ONLY_OTHER, ONLY_MINE }

data class DiffLine(val text: String, val side: DiffSide)

/**
 * Comparing and combining two versions of a note. Blocks and text lines are aligned by their longest common
 * sequence: shared parts appear once; where the versions differ, the other device's part comes first, then this
 * device's. Nothing is dropped. Same rules as the Windows app (NoteMerge.cs).
 */
object NoteMerge {
    const val CONFLICT_SUFFIX = " (conflict copy)"
    private const val MAX_CELLS = 4_000_000L

    fun originalTitle(copyTitle: String) = copyTitle.removeSuffix(CONFLICT_SUFFIX)

    fun diffLines(other: String, mine: String): List<DiffLine> = align(lines(other), lines(mine)).map { (text, side) -> DiffLine(text, side) }

    fun combineText(other: String, mine: String) = diffLines(other, mine).joinToString("\n") { it.text }

    /** A document with every block of both versions; plain-text notes (no document) count as paragraphs. */
    fun combineDocuments(other: JsonObject?, otherText: String, mine: JsonObject?, mineText: String): JsonObject {
        val merged = align(blocks(other, otherText), blocks(mine, mineText)).map { it.first }
        val base = other ?: mine ?: JsonObject(emptyMap())
        return JsonObject(base + mapOf(
            "schemaVersion" to JsonPrimitive(maxOf(versionOf(other), versionOf(mine))),
            "type" to JsonPrimitive("doc"),
            "content" to JsonArray(merged),
        ))
    }

    fun paragraph(text: String, italic: Boolean = false): JsonObject = buildJsonObject {
        put("type", "paragraph")
        if (text.isNotEmpty()) put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text"); put("text", text)
                if (italic) put("marks", buildJsonArray { add(buildJsonObject { put("type", "italic") }) })
            })
        })
    }

    fun paragraphDocument(text: String): JsonObject = buildJsonObject {
        put("schemaVersion", 1); put("type", "doc"); put("content", JsonArray(lines(text).map { paragraph(it) }))
    }

    private fun versionOf(doc: JsonObject?) = (doc?.get("schemaVersion") as? JsonPrimitive)?.intOrNull ?: 1
    private fun lines(text: String) = if (text.isEmpty()) emptyList() else text.replace("\r\n", "\n").split('\n')
    private fun blocks(doc: JsonObject?, text: String): List<JsonElement> =
        (doc?.get("content") as? JsonArray)?.toList() ?: lines(text).map { paragraph(it) }

    /** Longest-common-subsequence alignment (common prefix and suffix are matched first). */
    fun <T> align(a: List<T>, b: List<T>): List<Pair<T, DiffSide>> {
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
        val result = ArrayList<Pair<T, DiffSide>>()
        for (i in 0 until start) result += a[i] to DiffSide.BOTH
        val n = endA - start
        val m = endB - start
        if (n.toLong() * m > MAX_CELLS) {
            for (i in start until endA) result += a[i] to DiffSide.ONLY_OTHER
            for (j in start until endB) result += b[j] to DiffSide.ONLY_MINE
        } else {
            val lcs = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                lcs[i][j] = if (a[start + i] == b[start + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
            val pendingA = ArrayList<T>()
            val pendingB = ArrayList<T>()
            fun flush() {
                pendingA.forEach { result += it to DiffSide.ONLY_OTHER }
                pendingB.forEach { result += it to DiffSide.ONLY_MINE }
                pendingA.clear(); pendingB.clear()
            }
            var x = 0
            var y = 0
            while (x < n && y < m) {
                when {
                    a[start + x] == b[start + y] -> { flush(); result += a[start + x] to DiffSide.BOTH; x++; y++ }
                    lcs[x + 1][y] >= lcs[x][y + 1] -> pendingA += a[start + x++]
                    else -> pendingB += b[start + y++]
                }
            }
            while (x < n) pendingA += a[start + x++]
            while (y < m) pendingB += b[start + y++]
            flush()
        }
        for (i in endA until a.size) result += a[i] to DiffSide.BOTH
        return result
    }
}

/** The note "Meeting notes" creates for a calendar event (Notes spec §11). Same template as the Windows app. */
object MeetingNotes {
    fun title(subject: String) = subject.trim().ifEmpty { "Meeting" } + " – meeting notes"

    /** [when] is the event's date and time, already formatted for the user. Returns the document JSON and its text. */
    fun document(`when`: String, description: String? = null): Pair<JsonObject, String> {
        val text = mutableListOf(`when`)
        val content = mutableListOf<JsonElement>(NoteMerge.paragraph(`when`, italic = true))
        description.orEmpty().replace("\r\n", "\n").split('\n').filter { it.isNotBlank() }.take(200).forEach {
            content += NoteMerge.paragraph(it); text += it
        }
        fun heading(t: String) = buildJsonObject {
            put("type", "heading"); put("attrs", buildJsonObject { put("level", 2) })
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", t) }) })
        }
        val empty = buildJsonObject { put("type", "paragraph") }
        content += heading("Agenda")
        content += buildJsonObject { put("type", "bulletList"); put("content", buildJsonArray { add(buildJsonObject { put("type", "listItem"); put("content", buildJsonArray { add(empty) }) }) }) }
        content += heading("Notes")
        content += empty
        content += heading("Action items")
        content += buildJsonObject {
            put("type", "taskList")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "taskItem"); put("attrs", buildJsonObject { put("checked", false) }); put("content", buildJsonArray { add(empty) }) }) })
        }
        text += listOf("Agenda", "", "Notes", "", "Action items", "")
        return buildJsonObject { put("schemaVersion", 1); put("type", "doc"); put("content", JsonArray(content)) } to text.joinToString("\n")
    }
}
