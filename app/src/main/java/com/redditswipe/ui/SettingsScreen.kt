package com.redditswipe.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.redditswipe.BuildConfig
import com.redditswipe.ReadingSettings
import kotlin.math.roundToInt

private val TEXT_SCALES = listOf(0.85f, 1f, 1.15f, 1.3f, 1.5f)

@Composable
fun SettingsScreen(
    reading: ReadingSettings,
    showNsfw: Boolean,
    storiesReady: Int,
    isDownloading: Boolean,
    onTextScale: (Float) -> Unit,
    onSerif: (Boolean) -> Unit,
    onBodyZoom: (Float) -> Unit,
    onShowNsfw: (Boolean) -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionTitle("Reading")
        val index = TEXT_SCALES.indexOfFirst { it >= reading.textScale - 0.01f }.coerceAtLeast(0)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", Modifier.weight(1f))
            OutlinedButton(onClick = { onTextScale(TEXT_SCALES[index - 1]) }, enabled = index > 0) { Text("A−") }
            Text(
                "${(reading.textScale * 100).roundToInt()}%",
                Modifier.width(56.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            OutlinedButton(onClick = { onTextScale(TEXT_SCALES[index + 1]) }, enabled = index < TEXT_SCALES.lastIndex) { Text("A+") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Story text zoom: ${(reading.bodyZoom * 100).roundToInt()}%")
                Text(
                    "Pinch the story text on a card with two fingers to zoom it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { onBodyZoom(1f) }, enabled = reading.bodyZoom != 1f) { Text("Reset") }
        }
        SettingSwitch("Serif font", reading.serif, onSerif)
        Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val style = MaterialTheme.typography.bodyLarge.let {
                it.copy(
                    fontSize = it.fontSize * reading.textScale,
                    lineHeight = it.lineHeight * reading.textScale,
                    fontFamily = if (reading.serif) FontFamily.Serif else FontFamily.Default,
                )
            }
            Text(
                "So this happened last week, and I still can't believe it. Here's how it started…",
                style = style,
                modifier = Modifier.padding(16.dp),
            )
        }

        Divider()
        SectionTitle("Content")
        SettingSwitch("Show NSFW stories", showNsfw, onShowNsfw)

        Divider()
        SectionTitle("Offline")
        Text(
            "$storiesReady stories are saved on this phone and can be read without internet.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onDownload, enabled = !isDownloading) {
            if (isDownloading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Downloading…")
            } else {
                Text("Download more stories")
            }
        }

        Divider()
        Text(
            "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 20.dp))
}
