package com.redditswipe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.redditswipe.data.NEWS_FEEDS
import com.redditswipe.data.NewsCategory

/**
 * News sites grouped by category. A category's switch turns all of its sites on or off; the chips
 * underneath pick individual sites.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewsSourcesScreen(
    newsFeeds: List<String>,
    onSetFeeds: (names: Collection<String>, on: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byCategory = NEWS_FEEDS.groupBy { it.category }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(
                "Pick the kinds of news you want. Sites marked \"full\" send whole articles; the rest send a " +
                    "headline and summary with a link to the story. Political leanings follow common " +
                    "media-bias ratings and are only a rough guide.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(NewsCategory.entries, key = { it.name }) { category ->
            val feeds = byCategory[category].orEmpty()
            val onCount = feeds.count { it.name in newsFeeds }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(category.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (onCount == 0) "Off" else "$onCount of ${feeds.size} sites",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = onCount > 0,
                        onCheckedChange = { on -> onSetFeeds(feeds.map { it.name }, on) },
                        modifier = Modifier.semantics { contentDescription = "${category.label} news" },
                    )
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    feeds.forEach { feed ->
                        val on = feed.name in newsFeeds
                        FilterChip(
                            selected = on,
                            onClick = { onSetFeeds(listOf(feed.name), !on) },
                            label = { Text(if (feed.fullText) "${feed.name} · full" else feed.name) },
                            leadingIcon = if (on) {
                                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
