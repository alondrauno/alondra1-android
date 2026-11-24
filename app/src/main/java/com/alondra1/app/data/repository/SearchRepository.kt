package com.alondra1.app.data.repository

import com.alondra1.app.data.local.AppDatabase
import com.alondra1.app.data.local.EventEntity
import com.alondra1.app.data.local.NoteEntity
import com.alondra1.app.data.local.TaskEntity

data class SearchResults(val tasks: List<TaskEntity>, val notes: List<NoteEntity>, val events: List<EventEntity>) {
    val isEmpty get() = tasks.isEmpty() && notes.isEmpty() && events.isEmpty()
    companion object { val EMPTY = SearchResults(emptyList(), emptyList(), emptyList()) }
}

/** Offline search across modules; deleted items never appear. */
class SearchRepository(private val db: AppDatabase) {
    suspend fun search(query: String, limitPerModule: Int = 50): SearchResults {
        val q = query.trim()
        if (q.isEmpty()) return SearchResults.EMPTY
        val pattern = "%" + escapeLike(q) + "%"
        return SearchResults(
            tasks = db.tasks().search(pattern, limitPerModule),
            notes = db.notes().search(pattern, limitPerModule),
            events = db.events().search(pattern, limitPerModule),
        )
    }

    companion object {
        /** Treats % _ and \ in the user's text literally. */
        fun escapeLike(text: String): String = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
