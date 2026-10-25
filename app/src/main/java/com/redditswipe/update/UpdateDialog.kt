package com.redditswipe.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun UpdateDialog(
    state: UpdateState,
    onUpdate: (AppRelease) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        UpdateState.None -> Unit
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Version ${state.release.version} is available") },
            text = {
                if (state.release.notes.isNotBlank()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(state.release.notes)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onUpdate(state.release) }) { Text("Update") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading ${state.release.version}…") },
            text = {
                Column {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update failed") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
    }
}
