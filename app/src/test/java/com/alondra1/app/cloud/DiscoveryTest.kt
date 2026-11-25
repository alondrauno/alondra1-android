package com.alondra1.app.cloud

import com.alondra1.app.cloud.discovery.BuildEnvironment
import com.alondra1.app.cloud.discovery.DiscoveryCache
import com.alondra1.app.cloud.discovery.DiscoveryClient
import com.alondra1.app.cloud.discovery.DiscoveryEndpoint
import com.alondra1.app.cloud.discovery.DiscoveryException
import com.alondra1.app.cloud.discovery.DiscoveryManifest
import com.alondra1.app.cloud.discovery.Ed25519
import com.alondra1.app.cloud.discovery.EndpointSelector
import com.alondra1.app.cloud.discovery.EndpointSource
import com.alondra1.app.cloud.discovery.FailoverInterceptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

/** Signed cloud discovery: the shared fixture cases (same as Go, Windows and Web), Ed25519, sources, cache, failover. */
class DiscoveryTest {
    private fun fixture(path: String): ByteArray = javaClass.classLoader!!.getResource("discovery-v1/$path")!!.readBytes()
    private val trust = DiscoveryManifest.parseTrust(fixture("trust.json").decodeToString())
    private val fixtureNow = Instant.parse("2026-12-01T00:00:00Z")

    @Test fun ed25519Rfc8032Vectors() {
        listOf(
            Triple("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
            Triple("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
        ).forEach { (pub, msg, sig) ->
            val signature = Ed25519.hex(sig)
            assertTrue(Ed25519.verify(Ed25519.hex(pub), Ed25519.hex(msg), signature))
            signature[5] = (signature[5].toInt() xor 1).toByte()
            assertFalse(Ed25519.verify(Ed25519.hex(pub), Ed25519.hex(msg), signature))
        }
    }

    @Test fun everySharedFixtureCaseGivesTheSameResult() {
        val cases = Json.parseToJsonElement(fixture("cases.json").decodeToString()).jsonObject["cases"] as JsonArray
        assertTrue(cases.size >= 25)
        cases.forEach { e ->
            val c = e.jsonObject
            val name = c["name"]!!.jsonPrimitive.content
            val cached = (c["cached"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { fixture(it) }?.let { DiscoveryManifest.peekVersion(it) to it }
            val expect = c["expect"]!!.jsonObject
            try {
                val result = DiscoveryManifest.verify(fixture(c["manifest"]!!.jsonPrimitive.content), fixture(c["signature"]!!.jsonPrimitive.content),
                    c["environment"]!!.jsonPrimitive.content, trust, Instant.parse(c["now"]!!.jsonPrimitive.content), cached)
                assertTrue("$name: expected $expect", expect["ok"]!!.jsonPrimitive.content == "true")
                assertEquals(name, (expect["endpoint_ids"] as JsonArray).map { it.jsonPrimitive.content }, result.endpoints.map { it.id })
            } catch (x: DiscoveryException) {
                assertEquals("$name: ${x.message}", expect["error"]?.jsonPrimitive?.content, x.code)
            }
        }
    }

    @Test fun environmentsNeverMix() {
        assertTrue(DiscoveryManifest.hostAllowed("prod", "eu1.api.alondra1.com"))
        assertFalse(DiscoveryManifest.hostAllowed("prod", "api.alondra1.dev"))
        assertFalse(DiscoveryManifest.hostAllowed("dev", "api.alondra1.com"))
        assertTrue(BuildEnvironment.allowsAddress("http://10.0.2.2:8080", "prod"))
        assertFalse(BuildEnvironment.allowsAddress("https://api.alondra1.dev", "prod"))
        assertFalse(BuildEnvironment.allowsAddress("https://api.alondra1.com", "dev"))
        assertFalse(BuildEnvironment.allowsAddress("http://api.alondra1.com", "prod"))
    }

    private class MemoryCache : DiscoveryCache {
        var entry: DiscoveryCache.Entry? = null
        override suspend fun load() = entry
        override suspend fun save(entry: DiscoveryCache.Entry) { this.entry = entry }
    }

    private fun server(handler: (RecordedRequest) -> MockResponse) = MockWebServer().apply {
        dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }
        start()
    }

    private fun client(primary: MockWebServer, mirror: MockWebServer, cache: DiscoveryCache, bootstrapUntil: Instant = fixtureNow.plusSeconds(86_400)) =
        DiscoveryClient("prod", trust, listOf(primary.url("/manifest.json").toString(), mirror.url("/manifest.json").toString()), cache,
            listOf(DiscoveryEndpoint("bootstrap", "https://api.alondra1.com", "eu1", 0)), bootstrapUntil, { fixtureNow })

    private fun serve(case: String): (RecordedRequest) -> MockResponse = { r ->
        MockResponse().setBody(okio.Buffer().write(fixture(if (r.path!!.endsWith(".sig")) "$case/manifest.json.sig" else "$case/manifest.json")))
    }

    @Test(timeout = 30_000) fun mirrorWhenPrimaryFailsThenCache() = runBlocking<Unit> {
        val primary = server { MockResponse().setResponseCode(500) }
        val mirror = server(serve("valid-prod"))
        val cache = MemoryCache()
        val problems = mutableListOf<String>()
        val set = client(primary, mirror, cache).resolve(problems)!!
        assertEquals(EndpointSource.MIRROR, set.source)
        assertEquals(listOf("eu1", "api"), set.endpoints.map { it.id })
        primary.shutdown(); mirror.shutdown()
        assertEquals(EndpointSource.CACHE, client(primary, mirror, cache).offline()!!.source)
    }

    @Test(timeout = 30_000) fun forgedAndRolledBackManifestsDoNotReplaceTheCache() = runBlocking<Unit> {
        val cache = MemoryCache()
        client(server(serve("rollback")), server(serve("rollback")), cache).resolve(mutableListOf()) // version 5, valid
        val primary = server(serve("forged-content"))
        val mirror = server(serve("valid-prod")) // version 3: a rollback against the cached 5
        val problems = mutableListOf<String>()
        val set = client(primary, mirror, cache).resolve(problems)!!
        assertEquals(EndpointSource.CACHE, set.source)
        assertEquals(5L, set.version)
        assertTrue(problems.toString(), problems.contains("PRIMARY: BAD_SIGNATURE") && problems.contains("MIRROR: ROLLBACK"))
    }

    @Test(timeout = 30_000) fun noRedirectsAndBootstrapExpires() = runBlocking<Unit> {
        val target = server(serve("valid-prod"))
        val redirect = server { MockResponse().setResponseCode(302).setHeader("Location", target.url("/manifest.json")) }
        val set = client(redirect, redirect, MemoryCache()).resolve(mutableListOf())!!
        assertEquals("a redirect is never followed", EndpointSource.BOOTSTRAP, set.source)
        assertEquals(0, target.requestCount)
        assertNull(client(redirect, redirect, MemoryCache(), fixtureNow.minusSeconds(1)).offline())
    }

    /** Two test servers as endpoints e0, e1. The interceptor builds https URLs; a second interceptor makes them http. */
    private fun failover(handler: (RecordedRequest, Int) -> MockResponse): Triple<OkHttpClient, List<MockWebServer>, EndpointSelector> {
        val servers = (0..1).map { i -> server { handler(it, i) } }
        val selector = EndpointSelector((0..1).map { i -> DiscoveryEndpoint("e$i", "https://e$i.test", "eu1", i) })
        val http = OkHttpClient.Builder().addInterceptor(FailoverInterceptor(selector))
            .addInterceptor { chain ->
                val url = chain.request().url
                val target = servers[url.host.removePrefix("e").removeSuffix(".test").toInt()]
                chain.proceed(chain.request().newBuilder().url(url.newBuilder().scheme("http").host(target.hostName).port(target.port).build()).build())
            }
            .build()
        return Triple(http, servers, selector)
    }

    @Test(timeout = 30_000) fun failsOverOnTransportErrorsWithTheSameBody() {
        val (http, servers, selector) = failover { _, i -> if (i == 0) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else MockResponse().setBody("{}") }
        val body = """{"mutations":[{"mutation_id":"m1"}]}"""
        http.newCall(Request.Builder().url("https://placeholder/v1/sync/push").post(body.toRequestBody()).build()).execute().use { assertEquals(200, it.code) }
        val received = servers[1].takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("the second endpoint got the request", received != null)
        assertEquals(body, received!!.body.readUtf8())
        assertEquals("e1", selector.candidates().first().id)
    }

    @Test(timeout = 30_000) fun noFailoverForClientErrors() {
        listOf(401, 403, 404, 429).forEach { code ->
            val (http, servers, _) = failover { _, _ -> MockResponse().setResponseCode(code) }
            http.newCall(Request.Builder().url("https://placeholder/v1/me").build()).execute().use { assertEquals(code, it.code) }
            assertEquals(0, servers[1].requestCount)
        }
    }

    @Test(timeout = 30_000) fun gatewayAnswersFailOverAndRefreshIsNeverReplayed() {
        val (http, servers, _) = failover { _, i -> if (i == 0) MockResponse().setResponseCode(503).setHeader("Retry-After", "600") else MockResponse().setBody("{}") }
        http.newCall(Request.Builder().url("https://placeholder/v1/sync/changes").build()).execute().use { assertEquals(200, it.code) }
        assertEquals(1, servers[1].requestCount)

        val (refreshHttp, refreshServers, _) = failover { _, _ -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        try {
            refreshHttp.newCall(Request.Builder().url("https://placeholder/v1/auth/refresh").post("{}".toRequestBody()).build()).execute().close()
        } catch (_: IOException) { }
        assertEquals("no second endpoint for a refresh", 0, refreshServers[1].requestCount)
    }
}
