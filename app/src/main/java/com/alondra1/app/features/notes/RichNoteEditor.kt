package com.alondra1.app.features.notes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.alondra1.app.data.repository.NoteAssets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream

/**
 * Hosts the Alondra1 Notes editor (assets/notes-editor, built from alondra1-notes-editor: the same bundle as the
 * Windows app, so documents round-trip exactly). The page is served from the APK at https://editor.alondra1.invalid
 * and pictures from files/notes-assets at https://assets.alondra1.invalid; nothing else can load or navigate.
 */
class NoteEditorBridge(context: Context, private val assets: NoteAssets, private val onMessage: (JsonObject) -> Unit, private val onMissingAsset: (String) -> Unit) {
    val webView: WebView
    private val main = Handler(Looper.getMainLooper())
    private val queued = mutableListOf<String>()
    private var ready = false
    private val json = Json { ignoreUnknownKeys = true }

    init {
        val loader = WebViewAssetLoader.Builder()
            .setDomain(EDITOR_HOST)
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
        webView = WebView(context).apply {
            // Fill the space Compose gives it; with wrap-content the page's 100% height collapses to nothing.
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            configure(this)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url
                    if (url.host == ASSET_HOST) return servePicture(url.lastPathSegment.orEmpty())
                    if (url.host == EDITOR_HOST) return loader.shouldInterceptRequest(url)
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            addJavascriptInterface(HostInterface(), "AlondraHost")
            loadUrl(EDITOR_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: WebView) = with(view.settings) {
        javaScriptEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        domStorageEnabled = false
        databaseEnabled = false
        setGeolocationEnabled(false)
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        builtInZoomControls = false
        mediaPlaybackRequiresUserGesture = true
    }

    private fun servePicture(sha: String): WebResourceResponse {
        val found = runBlocking { assets.read(sha) }
        if (found == null) {
            main.post { onMissingAsset(sha) }
            return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        return WebResourceResponse(found.first.mimeType, null, 200, "OK",
            mapOf("Cache-Control" to "max-age=31536000, immutable", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(found.second))
    }

    private inner class HostInterface {
        @JavascriptInterface
        fun postMessage(text: String) {
            val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            main.post {
                if ((message["type"] as? JsonPrimitive)?.content == "ready") {
                    ready = true
                    queued.forEach { webView.evaluateJavascript(it, null) }
                    queued.clear()
                } else {
                    if (com.alondra1.app.BuildConfig.DEBUG && (message["type"] as? JsonPrimitive)?.content in setOf("loaded", "error")) android.util.Log.d("AlondraEditor", message.toString())
                    onMessage(message)
                }
            }
        }
    }

    /** Calls alondra.<method>(argument). Objects are passed as JSON text; strings as a JS string. */
    fun call(method: String, argument: Any? = null) {
        val literal = when (argument) {
            null -> ""
            is String -> JsonPrimitive(argument).toString()
            is JsonObject -> JsonPrimitive(argument.toString()).toString()
            else -> JsonPrimitive(argument.toString()).toString()
        }
        val script = "window.alondra && window.alondra.$method($literal)"
        // WebView must only be touched on the main thread; callers may resume elsewhere after database work.
        main.post { if (ready) webView.evaluateJavascript(script, null) else queued += script }
    }

    /** The current document (with unsaved edits), or null when the editor is not ready. */
    suspend fun flush(): JsonObject? {
        if (!ready) return null
        val result = CompletableDeferred<String?>()
        main.post { webView.evaluateJavascript("window.alondra ? window.alondra.flush() : null") { result.complete(it) } }
        val raw = result.await() ?: return null
        if (raw == "null") return null
        val text = runCatching { json.parseToJsonElement(raw) as JsonPrimitive }.getOrNull()?.content ?: return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    fun destroy() {
        webView.removeJavascriptInterface("AlondraHost")
        webView.destroy()
    }

    companion object {
        const val EDITOR_HOST = "editor.alondra1.invalid"
        const val ASSET_HOST = "assets.alondra1.invalid"
        const val EDITOR_URL = "https://$EDITOR_HOST/notes-editor/editor.html"

        /** False on devices without a working system WebView (and in JVM tests): notes then use the plain editor. */
        fun available(context: Context): Boolean = runCatching { android.webkit.WebView.getCurrentWebViewPackage() != null }.getOrDefault(false) &&
            runCatching { context.assets.open("notes-editor/editor.html").close(); true }.getOrDefault(false)

        /** A plain-text note (or text shared into the app) as a document of paragraphs. */
        fun plainDocument(text: String): String {
            val paragraphs = text.replace("\r\n", "\n").split('\n').joinToString(",") { line ->
                if (line.isEmpty()) """{"type":"paragraph"}""" else """{"type":"paragraph","content":[{"type":"text","text":${JsonPrimitive(line)}}]}"""
            }
            return """{"schemaVersion":1,"type":"doc","content":[$paragraphs]}"""
        }

        fun picturePath(uri: Uri) = uri.toString()
    }
}
