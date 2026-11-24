package com.alondra1.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A failed API call with the server's stable error code (or NETWORK when the server could not be reached). */
class ApiException(val status: Int, val code: String, message: String, val body: JsonObject? = null) : Exception(message) {
    val isNetwork get() = code == "NETWORK"
}

/**
 * Minimal client for the Alondra1 API (contract/openapi.yaml). Bearer auth with one refresh at a time; every request
 * carries X-Request-ID. Response bodies are never logged.
 */
class ApiClient(
    private val baseUrl: String,
    private val session: SessionStore,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()
    val configured get() = baseUrl.isNotBlank()

    private fun request(method: String, path: String, body: JsonElement?, tenant: String?, auth: String?): Request {
        val builder = Request.Builder().url(baseUrl.trimEnd('/') + path)
            .header("X-Request-ID", UUID.randomUUID().toString().replace("-", "").take(24))
            .header("Accept", "application/json")
        auth?.let { builder.header("Authorization", "Bearer $it") }
        tenant?.let { builder.header("X-Tenant-ID", it) }
        val requestBody = body?.toString()?.toRequestBody("application/json".toMediaType())
            ?: if (method == "POST" || method == "PUT" || method == "PATCH") "".toRequestBody(null) else null
        return builder.method(method, requestBody).build()
    }

    private suspend fun execute(req: Request): Pair<Int, JsonObject?> = withContext(Dispatchers.IO) {
        try {
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val parsed = if (text.isBlank()) null else runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                res.code to parsed
            }
        } catch (e: IOException) {
            throw ApiException(0, "NETWORK", "The Alondra1 cloud could not be reached.")
        }
    }

    private fun failure(status: Int, body: JsonObject?): ApiException {
        val error = body?.get("error")?.jsonObject
        return ApiException(status, error?.get("code")?.jsonPrimitive?.contentOrNull ?: "HTTP_$status",
            error?.get("message")?.jsonPrimitive?.contentOrNull ?: "The request failed ($status).", error)
    }

    /** Calls without authentication (sign-in, catalog). */
    suspend fun anonymous(method: String, path: String, body: JsonElement? = null): JsonObject {
        val (status, out) = execute(request(method, path, body, null, null))
        if (status !in 200..299) throw failure(status, out)
        return out ?: JsonObject(emptyMap())
    }

    /** Authenticated call; refreshes the access token once on 401, then gives up (the session is gone). */
    suspend fun call(method: String, path: String, body: JsonElement? = null, tenant: String? = null): JsonObject {
        val token = session.accessToken() ?: refresh() ?: throw ApiException(401, "UNAUTHENTICATED", "Sign in again.")
        var (status, out) = execute(request(method, path, body, tenant, token))
        if (status == 401) {
            val fresh = refresh() ?: throw failure(status, out)
            val retried = execute(request(method, path, body, tenant, fresh))
            status = retried.first; out = retried.second
        }
        if (status !in 200..299) throw failure(status, out)
        return out ?: JsonObject(emptyMap())
    }

    /** Uploads raw bytes (note pictures). Refreshes once on 401 like [call]. */
    suspend fun putBytes(path: String, bytes: ByteArray, contentType: String, tenant: String) {
        suspend fun send(token: String): Pair<Int, JsonObject?> = execute(Request.Builder().url(baseUrl.trimEnd('/') + path)
            .header("X-Request-ID", UUID.randomUUID().toString().replace("-", "").take(24))
            .header("Authorization", "Bearer $token").header("X-Tenant-ID", tenant)
            .put(bytes.toRequestBody(contentType.toMediaType())).build())
        val token = session.accessToken() ?: refresh() ?: throw ApiException(401, "UNAUTHENTICATED", "Sign in again.")
        var (status, out) = send(token)
        if (status == 401) {
            val fresh = refresh() ?: throw failure(status, out)
            val retried = send(fresh); status = retried.first; out = retried.second
        }
        if (status !in 200..299) throw failure(status, out)
    }

    /** Downloads raw bytes; null when the server has no such object (404). */
    suspend fun getBytes(path: String, tenant: String): ByteArray? {
        suspend fun fetch(token: String): Pair<Int, ByteArray?> = withContext(Dispatchers.IO) {
            try {
                http.newCall(Request.Builder().url(baseUrl.trimEnd('/') + path).header("Authorization", "Bearer $token").header("X-Tenant-ID", tenant).get().build())
                    .execute().use { res -> res.code to (if (res.isSuccessful) res.body?.bytes() else null) }
            } catch (e: IOException) {
                throw ApiException(0, "NETWORK", "The Alondra1 cloud could not be reached.")
            }
        }
        val token = session.accessToken() ?: refresh() ?: throw ApiException(401, "UNAUTHENTICATED", "Sign in again.")
        var (status, bytes) = fetch(token)
        if (status == 401) {
            val fresh = refresh() ?: throw ApiException(401, "UNAUTHENTICATED", "Sign in again.")
            val retried = fetch(fresh); status = retried.first; bytes = retried.second
        }
        if (status == 404) return null
        if (status !in 200..299) throw ApiException(status, "HTTP_$status", "The download failed ($status).")
        return bytes
    }

    /** Rotates the refresh token (one at a time). Returns the new access token, or null when signed out. */
    suspend fun refresh(): String? = refreshLock.withLock {
        val refreshToken = session.refreshToken() ?: return null
        val (status, out) = execute(request("POST", "/v1/auth/refresh", buildJsonObject { put("refresh_token", refreshToken) }, null, null))
        if (status == 200 && out != null) {
            session.saveTokens(out)
            return session.accessToken()
        }
        if (status in 400..499) session.signedOut() // revoked, reused or expired: local data stays, cloud stops
        if (status == 0 || status >= 500) throw ApiException(status, "NETWORK", "The Alondra1 cloud could not be reached.")
        null
    }
}
