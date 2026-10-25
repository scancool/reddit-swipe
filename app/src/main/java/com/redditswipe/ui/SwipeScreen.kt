package com.redditswipe.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.redditswipe.SwipeUiState
import com.redditswipe.data.RedditPost
import kotlinx.coroutines.launch

@Composable
fun SwipeScreen(
    state: SwipeUiState,
    onSwipeLeft: (RedditPost) -> Unit,
    onSwipeRight: (RedditPost) -> Unit,
    onNext: (RedditPost) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val widthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
    val heightPx = with(LocalDensity.current) { configuration.screenHeightDp.dp.toPx() }
    val scope = rememberCoroutineScope()
    val top = state.deck.firstOrNull()
    val next = state.deck.getOrNull(1)
    // One swipe state per top card, shared by the drag gesture and the buttons.
    val swipeState = remember(top?.id) { CardSwipeState(widthPx, heightPx) }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                top != null -> {
                    if (next != null) {
                        key(next.id) {
                            SwipeCard(
                                post = next,
                                swipeState = null,
                                onSwiped = {},
                                reading = state.reading,
                                modifier = Modifier.graphicsLayer { scaleX = 0.95f; scaleY = 0.95f },
                            )
                        }
                    }
                    key(top.id) {
                        SwipeCard(
                            post = top,
                            swipeState = swipeState,
                            onSwiped = { right -> if (right) onSwipeRight(top) else onSwipeLeft(top) },
                            reading = state.reading,
                        )
                    }
                }
                state.isLoading -> CircularProgressIndicator()
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        state.error ?: "You're all caught up",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry) { Text("Load more") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = {
                    val post = top ?: return@FilledTonalIconButton
                    scope.launch { swipeState.flyOut(right = false); onSwipeLeft(post) }
                },
                enabled = top != null,
                modifier = Modifier.size(64.dp),
            ) { Icon(Icons.Filled.Close, contentDescription = "Skip") }

            // Swipe up (or tap) between the buttons to move on to the next story.
            Column(
                Modifier
                    .weight(1f)
                    .height(80.dp)
                    .pointerInput(top?.id) {
                        val post = top ?: return@pointerInput
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch { if (swipeState.settleUp()) onNext(post) }
                            },
                            onDragCancel = {
                                scope.launch { swipeState.offsetY.animateTo(0f) }
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                scope.launch { swipeState.dragUp(dragAmount) }
                            },
                        )
                    }
                    .pointerInput(top?.id) {
                        val post = top ?: return@pointerInput
                        detectTapGestures { scope.launch { swipeState.flyUp(); onNext(post) } }
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (top != null) 0.8f else 0.3f)
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Next story", tint = hintColor)
                Text("Next", style = MaterialTheme.typography.labelMedium, color = hintColor)
            }

            FilledTonalIconButton(
                onClick = {
                    val post = top ?: return@FilledTonalIconButton
                    scope.launch { swipeState.flyOut(right = true); onSwipeRight(post) }
                },
                enabled = top != null,
                modifier = Modifier.size(64.dp),
            ) { Icon(Icons.Filled.Favorite, contentDescription = "Save") }
        }
    }
}
