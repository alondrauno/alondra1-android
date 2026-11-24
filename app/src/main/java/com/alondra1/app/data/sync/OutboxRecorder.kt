package com.alondra1.app.data.sync

import com.alondra1.app.auth.SessionStore

/**
 * The ChangeRecorder used once Cloud Sync has been set up on this device (also while paused, so nothing is missed).
 * Before that — free, signed out, or never enabled — nothing is recorded and nothing can be uploaded.
 */
class OutboxRecorder(
    private val session: SessionStore,
    private val engine: () -> SyncEngine,
    private val requestSync: () -> Unit,
) : ChangeRecorder {
    override suspend fun recorded(entityType: EntityType, entityId: String) {
        val s = session.state.value
        if (s.boundUser == null || s.tenantId == null) return
        engine().enqueue(entityType, entityId)
        if (s.syncEnabled) requestSync()
    }
}
