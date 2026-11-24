package com.alondra1.app.features.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.alondra1.app.R

/** First run: start empty, or with a few example tasks (only if the user asks for them). */
@Composable
fun WelcomeScreen(onStart: (withExamples: Boolean) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.alondra1_logo), contentDescription = "Alondra1 bird logo", modifier = Modifier.size(120.dp))
        Spacer(Modifier.height(24.dp))
        Text("Your personal to-do companion", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "Tasks, notes and a calendar in one calm place. Everything stays on this phone; no account is needed.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = { onStart(false) }, modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).height(52.dp).testTag("startEmpty")) { Text("Start") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { onStart(true) }, modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).height(52.dp).testTag("startExamples")) {
            Text("Start with a few example tasks")
        }
    }
}
