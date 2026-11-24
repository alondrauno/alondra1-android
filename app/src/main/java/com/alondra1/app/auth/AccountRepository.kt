package com.alondra1.app.auth

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class Space(val id: String, val type: String, val name: String, val role: String)

data class Entitlements(
    val plan: String,
    val planName: String,
    val state: String, // active, grace, read_only, inactive
    val sync: Boolean,
    val syncWrite: Boolean,
    val archive: Boolean,
    val effectiveUntil: String?,
    val manageBilling: Boolean,
) {
    companion object {
        fun from(o: JsonObject): Entitlements {
            val features = o["features"]?.jsonObject
            fun f(k: String) = features?.get(k)?.jsonPrimitive?.booleanOrNull == true
            return Entitlements(
                plan = o["plan"]?.jsonPrimitive?.contentOrNull ?: "free",
                planName = o["plan_name"]?.jsonPrimitive?.contentOrNull ?: "Free",
                state = o["entitlement_state"]?.jsonPrimitive?.contentOrNull ?: "inactive",
                sync = f("sync"), syncWrite = f("sync_write"), archive = f("mail_archive"),
                effectiveUntil = o["effective_until"]?.jsonPrimitive?.contentOrNull,
                manageBilling = o["entitlements"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "billing.manage" } == true,
            )
        }
    }
}

/** Optional cloud account: email one-time code sign-in, spaces, plan. Never needed for the local apps. */
class AccountRepository(private val api: ApiClient, private val session: SessionStore) {
    val configured get() = api.configured

    /** Returns the challenge id. The answer is the same whether or not the address has an account. */
    suspend fun start(email: String): String =
        api.anonymous("POST", "/v1/auth/email/start", buildJsonObject { put("email", email.trim()) })["challenge_id"]!!.jsonPrimitive.content

    suspend fun verify(email: String, challengeId: String, code: String, deviceName: String) {
        val out = api.anonymous("POST", "/v1/auth/email/verify", buildJsonObject {
            put("email", email.trim()); put("code", code.trim()); put("challenge_id", challengeId)
            putJsonObject("device") { put("name", deviceName); put("platform", "android") }
        })
        session.saveTokens(out)
    }

    suspend fun spaces(): List<Space> = (api.call("GET", "/v1/tenants")["tenants"] as? JsonArray).orEmpty().map {
        val o = it.jsonObject
        Space(o["id"]!!.jsonPrimitive.content, o["type"]!!.jsonPrimitive.content, o["name"]?.jsonPrimitive?.contentOrNull ?: "", o["role"]!!.jsonPrimitive.content)
    }

    /** The personal space, created on first use (a personal space has only its owner). */
    suspend fun personalSpace(): Space = spaces().firstOrNull { it.type == "personal" && it.role == "owner" }
        ?: api.call("POST", "/v1/tenants", buildJsonObject { put("type", "personal") }).let {
            Space(it["id"]!!.jsonPrimitive.content, "personal", it["name"]?.jsonPrimitive?.contentOrNull ?: "Personal", "owner")
        }

    suspend fun entitlements(tenantId: String) = Entitlements.from(api.call("GET", "/v1/me/entitlements", tenant = tenantId))

    /** Ends this device's cloud session. Local tasks, notes and events stay. */
    suspend fun logout() {
        runCatching { api.call("POST", "/v1/auth/logout") }
        session.signedOut()
    }
}
