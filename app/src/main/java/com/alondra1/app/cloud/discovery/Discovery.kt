package com.alondra1.app.cloud.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** One API entry point of a verified manifest. */
data class DiscoveryEndpoint(val id: String, val url: String, val region: String, val priority: Int)

/** A public key this build trusts for one environment. */
data class TrustedKey(val keyId: String, val environment: String, val publicKey: ByteArray)

class DiscoveryException(val code: String, detail: String) : Exception("$code: $detail")

data class VerifiedManifest(val version: Long, val expiresAt: Instant, val endpoints: List<DiscoveryEndpoint>, val bytes: ByteArray, val signature: ByteArray)

/**
 * Verification of the signed discovery manifest (alondra1-infra discovery/README.md). The rules, their order and the
 * error codes are normative and shared by every client; the cases in src/test/resources/discovery-v1 (from
 * alondra1-backend contract/fixtures) are run by DiscoveryTest. Nothing in the manifest is read before its signature
 * is verified.
 */
object DiscoveryManifest {
    const val SCHEMA = "alondra1.discovery/v1"
    const val MAX_BYTES = 64 * 1024
    const val SERVICE = "alondra1-api"
    val MAX_VALIDITY: Duration = Duration.ofDays(90)
    val CLOCK_SKEW: Duration = Duration.ofMinutes(5)

    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("^[a-z0-9-]{1,32}$")
    private val regionPattern = Regex("^[a-z0-9]{2,16}$")
    private val prodHost = Regex("""^(?:[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?\.)?api\.alondra1\.com$""")
    private val devHost = Regex("""^(?:[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?\.)?api\.dev\.alondra1\.com$""")
    private val base64Chars = Regex("^[A-Za-z0-9+/]*={0,2}$")

    fun hostAllowed(environment: String, host: String): Boolean = when (environment) {
        "prod" -> prodHost.matches(host)
        "dev" -> devHost.matches(host)
        else -> false
    }

    fun verify(manifest: ByteArray, signature: ByteArray, environment: String, trusted: List<TrustedKey>, now: Instant, cached: Pair<Long, ByteArray>?): VerifiedManifest {
        if (manifest.size > MAX_BYTES || signature.size > MAX_BYTES) throw DiscoveryException("TOO_LARGE", "too large")
        // 1. Signature over the exact bytes.
        val sig = runCatching { json.parseToJsonElement(signature.decodeToString()).jsonObject }.getOrNull()
            ?: throw DiscoveryException("BAD_SIGNATURE", "signature file is not valid")
        fun s(name: String) = (sig[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw DiscoveryException("BAD_SIGNATURE", "missing $name")
        if (s("alg") != "Ed25519") throw DiscoveryException("BAD_SIGNATURE", "unsupported algorithm")
        val keyId = s("key_id")
        val key = trusted.firstOrNull { it.keyId == keyId && it.environment == environment }
            ?: throw DiscoveryException("UNTRUSTED_KEY", "key $keyId is not trusted for $environment")
        val raw = strictBase64(s("signature"))
        if (raw == null || raw.size != 64) throw DiscoveryException("BAD_SIGNATURE", "signature is not a base64 Ed25519 signature")
        if (!Ed25519.verify(key.publicKey, manifest, raw)) throw DiscoveryException("BAD_SIGNATURE", "signature does not match")

        // 2. Structure.
        val root = runCatching { json.parseToJsonElement(manifest.decodeToString()) }.getOrElse { throw DiscoveryException("MALFORMED", "not JSON") }
            as? JsonObject ?: throw DiscoveryException("MALFORMED", "not an object")
        if (root.str("schema") != SCHEMA) throw DiscoveryException("UNSUPPORTED_SCHEMA", "schema")
        // 3. Environment.
        if (root.str("environment") != environment) throw DiscoveryException("WRONG_ENVIRONMENT", "manifest is for ${root.str("environment")}")
        // 4. Validity.
        val issued = time(root.str("issued_at"))
        val expires = time(root.str("expires_at"))
        if (issued == null || expires == null || !expires.isAfter(issued) || Duration.between(issued, expires) > MAX_VALIDITY)
            throw DiscoveryException("BAD_VALIDITY", "validity")
        if (issued.isAfter(now.plus(CLOCK_SKEW))) throw DiscoveryException("NOT_YET_VALID", "issued in the future")
        if (!now.isBefore(expires)) throw DiscoveryException("EXPIRED", "expired")
        // 5. Version.
        val version = (root["version"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (version == null || version < 1) throw DiscoveryException("MALFORMED", "version must be a positive integer")
        if (cached != null) {
            if (version < cached.first) throw DiscoveryException("ROLLBACK", "older than the cached manifest")
            if (version == cached.first && !manifest.contentEquals(cached.second)) throw DiscoveryException("ROLLBACK", "same version, different content")
        }
        // 6. Endpoints.
        val list = root["endpoints"] as? JsonArray ?: throw DiscoveryException("NO_ENDPOINTS", "no endpoints")
        val seen = mutableSetOf<String>()
        val usable = mutableListOf<DiscoveryEndpoint>()
        for (e in list) {
            val o = e as? JsonObject ?: throw DiscoveryException("BAD_ENDPOINT", "endpoint is not an object")
            val endpoint = checkEndpoint(environment, o)
            if (!seen.add(endpoint.id)) throw DiscoveryException("BAD_ENDPOINT", "duplicate endpoint id ${endpoint.id}")
            val enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull == true
            val v1 = (o["api_versions"] as? JsonArray)?.any { (it as? JsonPrimitive)?.contentOrNull == "v1" } == true
            if (enabled && v1) usable += endpoint
        }
        if (usable.isEmpty()) throw DiscoveryException("NO_ENDPOINTS", "no enabled endpoint supports API v1")
        return VerifiedManifest(version, expires, usable.sortedWith(compareBy({ it.priority }, { it.id })), manifest, signature)
    }

    private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun time(value: String?): Instant? = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

    private fun strictBase64(text: String): ByteArray? {
        if (text.length % 4 != 0 || !base64Chars.matches(text)) return null
        val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
        return bytes.takeIf { Base64.getEncoder().encodeToString(it) == text }
    }

    private fun checkEndpoint(environment: String, o: JsonObject): DiscoveryEndpoint {
        val id = o.str("id").orEmpty()
        if (!idPattern.matches(id)) throw DiscoveryException("BAD_ENDPOINT", "endpoint id $id")
        val region = o.str("region").orEmpty()
        if (!regionPattern.matches(region)) throw DiscoveryException("BAD_ENDPOINT", "endpoint $id: region")
        val priority = (o["priority"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: -1
        if (priority !in 0..1000) throw DiscoveryException("BAD_ENDPOINT", "endpoint $id: priority")
        if (o.str("service") != SERVICE) throw DiscoveryException("BAD_ENDPOINT", "endpoint $id: service")
        val url = o.str("url").orEmpty()
        if (!url.startsWith("https://", ignoreCase = true)) throw DiscoveryException("BAD_ENDPOINT", "endpoint $id: scheme must be https")
        val rest = url.substring("https://".length)
        val end = rest.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = if (end < 0) rest else rest.substring(0, end)
        val tail = if (end < 0) "" else rest.substring(end)
        if ('@' in authority) throw DiscoveryException("BAD_ENDPOINT", "endpoint $id: credentials in the url")
        if (':' in authority) throw DiscoveryException("BAD_ENDPOINT", "endpoint $id: port in the url")
        if (tail != "" && tail != "/") throw DiscoveryException("BAD_ENDPOINT", "endpoint $id: the url must be an origin")
        if (!hostAllowed(environment, authority)) throw DiscoveryException("BAD_ENDPOINT", "endpoint $id: host $authority is not allowed")
        return DiscoveryEndpoint(id, "https://$authority", region, priority)
    }

    fun parseTrust(text: String): List<TrustedKey> =
        (json.parseToJsonElement(text).jsonObject["keys"] as? JsonArray).orEmpty().map { e ->
            val o = e.jsonObject
            TrustedKey(o["key_id"]!!.jsonPrimitive.content, o["environment"]!!.jsonPrimitive.content, Base64.getDecoder().decode(o["public_key"]!!.jsonPrimitive.content))
        }

    fun peekVersion(manifest: ByteArray): Long =
        runCatching { (json.parseToJsonElement(manifest.decodeToString()).jsonObject["version"] as? JsonPrimitive)?.longOrNull }.getOrNull() ?: 0
}

/** Cache of the last verified manifest (DataStore in the app; memory in tests). Holds no secrets. */
interface DiscoveryCache {
    data class Entry(val manifest: ByteArray, val signature: ByteArray, val etag: String?, val source: String)
    suspend fun load(): Entry?
    suspend fun save(entry: Entry)
}

enum class EndpointSource { PRIMARY, MIRROR, CACHE, BOOTSTRAP }

data class EndpointSet(val endpoints: List<DiscoveryEndpoint>, val source: EndpointSource, val version: Long)

/**
 * Finds the API endpoints of this build's environment: primary, independent mirror, the last verified manifest (verified
 * again on every use), then the bundled bootstrap list while it is valid. Downloads follow no redirects and use
 * conditional GET. Only used once the user turned the cloud on: a local-only app never contacts discovery.
 */
class DiscoveryClient(
    private val environment: String,
    private val trust: List<TrustedKey>,
    private val sources: List<String>,
    private val cache: DiscoveryCache,
    private val bootstrap: List<DiscoveryEndpoint>,
    private val bootstrapValidUntil: Instant,
    private val now: () -> Instant = Instant::now,
    private val http: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).build(),
) {
    suspend fun offline(): EndpointSet? {
        cache.load()?.let { c ->
            runCatching { DiscoveryManifest.verify(c.manifest, c.signature, environment, trust, now(), null) }.getOrNull()
                ?.let { return EndpointSet(it.endpoints, EndpointSource.CACHE, it.version) }
        }
        return if (now().isBefore(bootstrapValidUntil)) EndpointSet(bootstrap, EndpointSource.BOOTSTRAP, 0) else null
    }

    /** Refreshes from the network (blocking I/O: call from a background dispatcher); falls back to [offline]. */
    suspend fun resolve(problems: MutableList<String>): EndpointSet? {
        if (trust.isEmpty()) {
            problems += "no discovery key trusted by this build"
            return offline()
        }
        val cached = cache.load()
        val cachedPair = cached?.let { DiscoveryManifest.peekVersion(it.manifest) to it.manifest }
        sources.forEachIndexed { i, url ->
            val source = if (i == 0) EndpointSource.PRIMARY else EndpointSource.MIRROR
            try {
                val request = Request.Builder().url(url).apply {
                    if (cached?.etag != null && cached.source == url) header("If-None-Match", cached.etag)
                }.build()
                http.newCall(request).execute().use { res ->
                    if (res.code == 304 && cached != null) {
                        val v = DiscoveryManifest.verify(cached.manifest, cached.signature, environment, trust, now(), null)
                        return EndpointSet(v.endpoints, source, v.version)
                    }
                    if (res.code != 200) { problems += "$source: HTTP ${res.code}"; return@forEachIndexed }
                    val manifest = bounded(res)
                    val sigBytes = http.newCall(Request.Builder().url("$url.sig").build()).execute().use { s ->
                        if (s.code != 200) { problems += "$source: signature HTTP ${s.code}"; return@forEachIndexed }
                        bounded(s)
                    }
                    val v = DiscoveryManifest.verify(manifest, sigBytes, environment, trust, now(), cachedPair)
                    cache.save(DiscoveryCache.Entry(manifest, sigBytes, res.header("ETag"), url))
                    return EndpointSet(v.endpoints, source, v.version)
                }
            } catch (e: DiscoveryException) {
                problems += "$source: ${e.code}"
            } catch (e: IOException) {
                problems += "$source: unreachable"
            }
        }
        return offline()
    }

    private fun bounded(res: Response): ByteArray {
        val body = res.body ?: return ByteArray(0)
        if (body.contentLength() > DiscoveryManifest.MAX_BYTES) throw DiscoveryException("TOO_LARGE", "response too large")
        val bytes = body.source().apply { request((DiscoveryManifest.MAX_BYTES + 1).toLong()) }.buffer.readByteArray()
        if (bytes.size > DiscoveryManifest.MAX_BYTES) throw DiscoveryException("TOO_LARGE", "response too large")
        return bytes
    }

    companion object {
        val REFRESH: Duration = Duration.ofMinutes(15)
        /** 15 minutes with ±20 % jitter. */
        fun nextRefresh(random: Random = Random.Default): Duration = Duration.ofMillis((REFRESH.toMillis() * (0.8 + random.nextDouble() * 0.4)).toLong())
    }
}

/** Endpoints in use and their recent health. Thread-safe; holds no secrets. */
class EndpointSelector(initial: List<DiscoveryEndpoint>, private val now: () -> Instant = Instant::now) {
    @Volatile private var endpoints = initial
    private val downUntil = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    init { require(initial.isNotEmpty()) { "At least one endpoint is needed." } }

    fun update(list: List<DiscoveryEndpoint>) { if (list.isNotEmpty()) endpoints = list }

    fun candidates(): List<DiscoveryEndpoint> {
        val t = now()
        val (healthy, paused) = endpoints.partition { e -> downUntil[e.id]?.let { !it.isAfter(t) } ?: true }
        return healthy + paused
    }

    fun markDown(e: DiscoveryEndpoint, pause: Duration) { downUntil[e.id] = now().plus(pause) }
    fun markUp(e: DiscoveryEndpoint) { downUntil.remove(e.id) }

    companion object { val DEFAULT_PAUSE: Duration = Duration.ofSeconds(30) }
}

/**
 * Sends each API call to the best endpoint and moves to the next only when that is safe: transport errors, timeouts
 * and 502/503/504 (Retry-After pauses that endpoint). 401/403/404/429 and other answers are returned as they are.
 * Requests are re-sent unchanged (same body and mutation ids). A token refresh is never sent to a second endpoint after
 * a transport failure: whether the first one rotated the token is unknown.
 */
class FailoverInterceptor(private val selector: EndpointSelector) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val refresh = original.url.encodedPath.endsWith("/v1/auth/refresh")
        val candidates = selector.candidates()
        var last: Response? = null
        for ((i, endpoint) in candidates.withIndex()) {
            val target = okhttp3.HttpUrl.Builder().scheme("https").host(endpoint.url.removePrefix("https://"))
                .encodedPath(original.url.encodedPath).encodedQuery(original.url.encodedQuery).build()
            val response = try {
                chain.proceed(original.newBuilder().url(target).build())
            } catch (e: IOException) {
                selector.markDown(endpoint, EndpointSelector.DEFAULT_PAUSE)
                if (refresh || i == candidates.lastIndex) throw e
                continue
            }
            if (response.code !in GATEWAY) { selector.markUp(endpoint); last?.close(); return response }
            selector.markDown(endpoint, retryAfter(response) ?: EndpointSelector.DEFAULT_PAUSE)
            if (refresh || i == candidates.lastIndex) { last?.close(); return response }
            last?.close(); last = response
        }
        return last ?: throw IOException("No cloud endpoint is available.")
    }

    private fun retryAfter(r: Response): Duration? = r.header("Retry-After")?.trim()?.toLongOrNull()?.let(Duration::ofSeconds)

    private companion object { val GATEWAY = setOf(502, 503, 504) }
}
