package com.alondra1.app.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Three-way merge of a task's attachments after a sync conflict (Dev-only spec BE-03, review U4; the shared rule is
 * alondra1-backend contract/fixtures/todo-v1/merge-attachments.json). Start from the server's current list, drop what
 * this device removed since the last server copy it saw, add what this device added since then unless the server
 * already lists it (same id or same SHA-256). Removals on either side are never reversed; unknown fields are kept.
 */
object AttachmentMerge {
    fun merge(base: JsonArray?, local: JsonArray?, remote: JsonArray?): JsonArray {
        val baseIds = items(base).mapNotNull(::id).toSet()
        val localItems = items(local)
        val localIds = localItems.mapNotNull(::id).toSet()
        val removedHere = baseIds - localIds
        val result = items(remote).filter { r -> id(r)?.let { it !in removedHere } ?: true }.toMutableList()
        for (added in localItems.filter { l -> id(l)?.let { it !in baseIds } == true }) {
            if (result.any { r -> id(r) == id(added) || (sha(r) != null && sha(r) == sha(added)) }) continue
            result += added
        }
        return JsonArray(result)
    }

    fun sameIds(a: JsonArray?, b: JsonArray?): Boolean = items(a).map(::id) == items(b).map(::id)

    private fun items(list: JsonArray?): List<JsonObject> = list?.filterIsInstance<JsonObject>().orEmpty()
    private fun id(o: JsonObject): String? = (o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun sha(o: JsonObject): String? = (o["sha256"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
