package com.alondra1.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.alondra1.app.navigation.Alondra1Root
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {
    private val newIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as Alondra1App).container
        if (savedInstanceState == null) shared(intent)?.let { intent = it }
        setContent {
            androidx.compose.foundation.layout.Box {
                Alondra1Root(container, newIntents)
                // Dev builds carry a visible badge so test data is never mistaken for real data.
                if (com.alondra1.app.cloud.discovery.BuildEnvironment.isDev) DevBadge()
            }
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

@androidx.compose.runtime.Composable
private fun androidx.compose.foundation.layout.BoxScope.DevBadge() {
    androidx.compose.material3.Surface(
        color = androidx.compose.ui.graphics.Color(0xFFC2410C),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
        modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter)
            .padding(top = 28.dp).semantics { contentDescription = "Development build" },
    ) {
        androidx.compose.material3.Text("DEV", color = androidx.compose.ui.graphics.Color.White,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            modifier = androidx.compose.ui.Modifier.padding(horizontal = 8.dp, vertical = 1.dp))
    }
}
