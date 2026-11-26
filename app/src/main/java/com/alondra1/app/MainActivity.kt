package com.alondra1.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alondra1.app.navigation.Alondra1Root
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {
    private val newIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as Alondra1App).container
        if (savedInstanceState == null) shared(intent)?.let { intent = it }
        setContent {
            // Dev builds carry a DEV badge in the screen header (design/Components.kt AppHeader), so test data is never
            // mistaken for real data without the badge covering anything.
            Alondra1Root(container, newIntents)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        newIntents.tryEmit(shared(intent) ?: intent)
    }

    /** Text or pictures shared to Alondra1 become a note, which then opens (as alondra1://note/{id}). */
    private fun shared(intent: Intent?): Intent? {
        if (intent?.action != Intent.ACTION_SEND && intent?.action != Intent.ACTION_SEND_MULTIPLE) return null
        val container = (application as Alondra1App).container
        val id = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.alondra1.app.features.notes.ShareToNote.create(container, contentResolver, intent) }.getOrNull()
        } ?: return null
        return Intent(Intent.ACTION_VIEW, android.net.Uri.parse("alondra1://note/$id"), this, MainActivity::class.java)
    }
}
