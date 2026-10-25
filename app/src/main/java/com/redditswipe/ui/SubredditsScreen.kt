package com.redditswipe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.redditswipe.data.QUICK_READ_SUBREDDITS

@Composable
fun SubredditsScreen(
    subreddits: List<String>,
    isChecking: Boolean,
    error: String?,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    // Clear the field once a subreddit has been added.
    LaunchedEffect(subreddits.size) { input = "" }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Add subreddit") },
                prefix = { Text("r/") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) onAdd(input) }),
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(width = 72.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                if (isChecking) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else {
                    Button(onClick = { onAdd(input) }, enabled = input.isNotBlank()) { Text("Add") }
                }
            }
        }
        Text(
            "Stories come from these ${subreddits.size} subreddits. Only text posts of 300+ characters are " +
                "shown, except from quick-read subreddits, where short posts are the point.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        val quickReads = QUICK_READ_SUBREDDITS.filter { q -> subreddits.none { it.equals(q, ignoreCase = true) } }
        if (quickReads.isNotEmpty()) {
            Text(
                "Quick reads",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(quickReads, key = { it }) { name ->
                    AssistChip(
                        onClick = { onAdd(name) },
                        enabled = !isChecking,
                        label = { Text("r/$name") },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(subreddits, key = { it }) { name ->
                ListItem(
                    headlineContent = { Text("r/$name") },
                    trailingContent = {
                        IconButton(onClick = { onRemove(name) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove r/$name")
                        }
                    },
                )
                HorizontalDivider()
            }
        }
        TextButton(onClick = onRestoreDefaults, modifier = Modifier.padding(8.dp)) {
            Text("Restore default list")
        }
    }
}
