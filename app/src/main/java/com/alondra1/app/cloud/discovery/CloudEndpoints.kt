package com.alondra1.app.cloud.discovery

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alondra1.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The environment this build belongs to (product flavor `dev` or `prod`, spec v1.1 §8.3): its API origin, discovery
 * sources and trusted keys come from BuildConfig. A dev build talks only to dev hosts and a prod build never to a dev
 * host; a debug build may use a local development server (API_BASE_URL) instead of discovery.
 */
object BuildEnvironment {
    val name: String get() = BuildConfig.ENVIRONMENT
    val isDev: Boolean get() = name == "dev"

    /** The bundled endpoint list expires: an old build that never reached discovery stops trusting it. */
    val bootstrapValidUntil: Instant = Instant.parse("2027-12-31T00:00:00Z")

    fun bootstrap(): List<DiscoveryEndpoint> = listOf(DiscoveryEndpoint("bootstrap", BuildConfig.API_ORIGIN, "eu1", 0))

    fun trustedKeys(): List<TrustedKey> = DiscoveryManifest.parseTrust(BuildConfig.DISCOVERY_TRUST)

    /** A fixed address must be loopback/emulator host (development) or a host of this build's environment. */
    fun allowsAddress(address: String, environment: String = name): Boolean {
        val uri = runCatching { URI(address) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "127.0.0.1" || host == "localhost" || host == "10.0.2.2") return true
        if (uri.scheme != "https") return false
        val devHost = host == "alondra1.dev" || host.endsWith(".alondra1.dev")
        val prodHost = !devHost && (host == "alondra1.com" || host.endsWith(".alondra1.com"))
        return if (environment == "dev") !prodHost else !devHost
    }
}

private val Context.discoveryStore: DataStore<Preferences> by preferencesDataStore(name = "discovery")

/** The last verified manifest in DataStore (spec: Android DataStore). No secrets; verified again whenever used. */
class DataStoreDiscoveryCache(private val context: Context) : DiscoveryCache {
    private val manifest = stringPreferencesKey("manifest")
    private val signature = stringPreferencesKey("signature")
    private val etag = stringPreferencesKey("etag")
    private val source = stringPreferencesKey("source")

    override suspend fun load(): DiscoveryCache.Entry? {
        val p = context.discoveryStore.data.first()
        val m = p[manifest] ?: return null
        val s = p[signature] ?: return null
        return runCatching { DiscoveryCache.Entry(Base64.getDecoder().decode(m), Base64.getDecoder().decode(s), p[etag], p[source].orEmpty()) }.getOrNull()
    }

    override suspend fun save(entry: DiscoveryCache.Entry) {
        context.discoveryStore.edit { p ->
            p[manifest] = Base64.getEncoder().encodeToString(entry.manifest)
            p[signature] = Base64.getEncoder().encodeToString(entry.signature)
            if (entry.etag != null) p[etag] = entry.etag else p.remove(etag)
            p[source] = entry.source
        }
    }
}

/**
 * Where API calls go: a fixed address (debug builds with API_BASE_URL), or signed discovery with failover between
 * the environment's endpoints. [http] carries the failover interceptor; [refreshIfDue] runs before background syncs.
 */
class CloudEndpoints private constructor(
    val baseUrl: String,
    val http: OkHttpClient,
    private val discovery: DiscoveryClient?,
    private val selector: EndpointSelector?,
) {
    @Volatile private var nextRefresh: Instant = Instant.MIN

    suspend fun refreshIfDue(force: Boolean = false) {
        val client = discovery ?: return
        val sel = selector ?: return
        if (!force && Instant.now().isBefore(nextRefresh)) return
        nextRefresh = Instant.now().plus(DiscoveryClient.nextRefresh())
        val problems = mutableListOf<String>()
        val set = withContext(Dispatchers.IO) { runCatching { client.resolve(problems) }.getOrNull() }
        set?.let { sel.update(it.endpoints) }
    }

    companion object {
        private fun baseClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false)

        /** null when this build cannot use the cloud (a fixed address of the other environment). */
        fun create(context: Context): CloudEndpoints? {
            val fixed = BuildConfig.API_BASE_URL
            if (fixed.isNotBlank()) {
                if (!BuildEnvironment.allowsAddress(fixed)) return null
                return CloudEndpoints(fixed, baseClient().build(), null, null)
            }
            val bootstrap = BuildEnvironment.bootstrap()
            if (!Instant.now().isBefore(BuildEnvironment.bootstrapValidUntil)) return null
            val selector = EndpointSelector(bootstrap)
            val discovery = DiscoveryClient(
                BuildEnvironment.name, BuildEnvironment.trustedKeys(),
                listOf(BuildConfig.DISCOVERY_PRIMARY, BuildConfig.DISCOVERY_MIRROR),
                DataStoreDiscoveryCache(context.applicationContext), bootstrap, BuildEnvironment.bootstrapValidUntil,
            )
            val http = baseClient().addInterceptor(FailoverInterceptor(selector)).build()
            return CloudEndpoints(BuildConfig.API_ORIGIN, http, discovery, selector)
        }
    }
}
