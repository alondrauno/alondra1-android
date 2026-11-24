package com.alondra1.app.data.sync

/**
 * Hook for the optional paid Cloud Sync. Repositories report every local change here after the local write
 * commits. The free build uses [LocalOnly]: nothing is queued and nothing leaves the device.
 */
interface ChangeRecorder {
    suspend fun recorded(entityType: EntityType, entityId: String)

    object LocalOnly : ChangeRecorder {
        override suspend fun recorded(entityType: EntityType, entityId: String) = Unit
    }
}

enum class EntityType(val wire: String) { TASK("task"), TASK_LIST("list"), NOTE_FOLDER("note_folder"), NOTE("note"), EVENT("event") }
