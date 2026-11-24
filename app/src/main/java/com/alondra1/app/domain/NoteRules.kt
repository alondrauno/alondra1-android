package com.alondra1.app.domain

import com.alondra1.app.data.local.NoteEntity

object NoteRules {
    private const val DERIVED_MAX = 80

    /** The title, or the first non-empty line of the body, or "Untitled note". */
    fun displayTitle(title: String, body: String): String {
        title.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val line = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return "Untitled note"
        return if (line.length <= DERIVED_MAX) line else line.take(DERIVED_MAX - 1).trimEnd() + "…"
    }

    fun displayTitle(note: NoteEntity): String = displayTitle(note.title, note.body)

    fun isEmpty(title: String, body: String): Boolean = title.isBlank() && body.isBlank()

    /** Preview line under the title: the first body line that is not the derived title. */
    fun preview(note: NoteEntity): String {
        val lines = note.body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val rest = if (note.title.isBlank()) lines.drop(1) else lines
        return rest.firstOrNull().orEmpty().take(120)
    }

    /** Suggested task title from a selection or the note's first line. */
    fun taskTitleFrom(note: NoteEntity, selection: String?): String {
        val fromSelection = selection?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        val raw = fromSelection ?: displayTitle(note).takeIf { it != "Untitled note" }.orEmpty()
        return raw.take(TaskRules.TITLE_MAX)
    }
}
