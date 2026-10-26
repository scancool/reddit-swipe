package com.redditswipe.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import com.redditswipe.data.RedditPost

@Composable
fun SavedScreen(
    saved: List<RedditPost>,
    onRemove: (RedditPost) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (saved.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Swipe right on a card to like it")
        }
        return
    }
    val uriHandler = LocalUriHandler.current
    LazyColumn(modifier.fillMaxSize()) {
        items(saved.asReversed(), key = { it.id }) { post ->
            ListItem(
                modifier = Modifier.clickable { uriHandler.openUri(post.url) },
                overlineContent = { Text("${post.sourceLabel}  ·  ${post.readMinutes} min read") },
                headlineContent = { Text(post.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                supportingContent = if (post.body.isNotBlank()) {
                    { Text(post.body, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                } else {
                    null
                },
                trailingContent = {
                    IconButton(onClick = { onRemove(post) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove")
                    }
                },
            )
            HorizontalDivider()
        }
    }
}
