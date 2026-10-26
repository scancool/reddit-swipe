package com.redditswipe.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.redditswipe.ReadMode
import com.redditswipe.SwipeUiState

/**
 * What sits at the bottom of the deck: a pulsing placeholder while stories load, or a card explaining
 * why there's nothing more (no subreddits, a network error, or genuinely the end of the feed).
 */
@Composable
fun FeedEndCard(
    state: SwipeUiState,
    onRetry: () -> Unit,
    onOpenSubreddits: () -> Unit,
    onOpenNews: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.modeSubreddits.isEmpty() && state.modeNewsFeeds.isEmpty() -> MessageCard(
            icon = Icons.Filled.Forum,
            title = when {
                state.readMode == ReadMode.News -> "No news sources"
                state.subreddits.isEmpty() && state.newsFeeds.isEmpty() -> "Pick some sources"
                state.readMode == ReadMode.Quick -> "No quick-read subreddits"
                else -> "No story subreddits"
            },
            detail = when {
                state.readMode == ReadMode.News -> "Switch on a category like Center or Tech, or a site like BBC News."
                state.subreddits.isEmpty() && state.newsFeeds.isEmpty() ->
                    "Stories come from the subreddits and news sites you choose."
                state.readMode == ReadMode.Quick -> "Add one like r/Showerthoughts from the Quick reads list."
                else -> "Add a story subreddit like r/tifu, or switch to Mixed."
            },
            action = if (state.readMode == ReadMode.News) "Choose news sources" else "Choose subreddits",
            onAction = if (state.readMode == ReadMode.News) onOpenNews else onOpenSubreddits,
            modifier = modifier,
        )
        !state.isRestored || state.isLoading -> PlaceholderCard(modifier)
        state.error != null -> MessageCard(
            icon = Icons.Filled.CloudOff,
            title = "Couldn't load stories",
            detail = state.error,
            action = "Try again",
            onAction = onRetry,
            modifier = modifier,
            urgent = true,
        )
        else -> MessageCard(
            icon = Icons.Filled.DoneAll,
            title = "You're all caught up",
            detail = if (state.isExhausted) "No new stories right now. Check back later, or add more sources."
            else "That's everything loaded for now.",
            action = "Check for new stories",
            onAction = onRetry,
            modifier = modifier,
        )
    }
}

@Composable
private fun MessageCard(
    icon: ImageVector,
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier,
    urgent: Boolean = false,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, CardShape)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        if (urgent) {
            Button(onClick = onAction) { Text(action) }
        } else {
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

/** A skeleton of a story card whose bars pulse gently; the pulse is read only in the draw phase. */
@Composable
private fun PlaceholderCard(modifier: Modifier) {
    val pulse by rememberInfiniteTransition(label = "placeholder").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    val bar = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val shape = RoundedCornerShape(6.dp)
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CardShape)
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .semantics { contentDescription = "Loading stories" },
    ) {
        Column(
            Modifier.graphicsLayer { alpha = pulse },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.fillMaxWidth(0.3f).height(14.dp).background(bar, shape))
            Spacer(Modifier.height(4.dp))
            Spacer(Modifier.fillMaxWidth(0.9f).height(26.dp).background(bar, shape))
            Spacer(Modifier.fillMaxWidth(0.6f).height(26.dp).background(bar, shape))
            Spacer(Modifier.height(8.dp))
            listOf(1f, 0.95f, 1f, 0.85f, 0.97f, 0.7f).forEach {
                Spacer(Modifier.fillMaxWidth(it).height(16.dp).background(bar, shape))
            }
        }
    }
}
