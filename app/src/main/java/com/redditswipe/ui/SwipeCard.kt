package com.redditswipe.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import com.redditswipe.ReadingSettings
import com.redditswipe.data.RedditPost
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Offset of the top card, shared by the drag gestures and the buttons. */
class CardSwipeState(private val widthPx: Float, private val heightPx: Float) {
    val offsetX = Animatable(0f)
    val offsetY = Animatable(0f)
    private val threshold get() = widthPx * 0.3f
    private val upThreshold get() = heightPx * 0.12f

    /** -1..1, how far the card has been dragged toward a decision. */
    val progress: Float get() = (offsetX.value / threshold).coerceIn(-1f, 1f)

    suspend fun flyOut(right: Boolean) {
        offsetX.animateTo(if (right) widthPx * 1.5f else -widthPx * 1.5f, tween(250))
    }

    suspend fun flyUp() {
        offsetY.animateTo(-heightPx * 1.2f, tween(250))
    }

    /** Moves the card up with the finger; it never goes below its resting place. */
    suspend fun dragUp(dy: Float) {
        offsetY.snapTo((offsetY.value + dy).coerceAtMost(0f))
    }

    /** Returns true for right, false for left, null if the card snapped back. */
    suspend fun settle(): Boolean? {
        return if (abs(offsetX.value) > threshold) {
            val right = offsetX.value > 0
            flyOut(right)
            right
        } else {
            offsetX.animateTo(0f)
            null
        }
    }

    /** Returns true if the card was pushed up far enough to move on to the next story. */
    suspend fun settleUp(): Boolean {
        return if (-offsetY.value > upThreshold) {
            flyUp()
            true
        } else {
            offsetY.animateTo(0f)
            false
        }
    }
}

@Composable
fun SwipeCard(
    post: RedditPost,
    swipeState: CardSwipeState?,
    onSwiped: (right: Boolean) -> Unit,
    reading: ReadingSettings,
    modifier: Modifier = Modifier,
) {
    val fontFamily = if (reading.serif) FontFamily.Serif else FontFamily.Default
    val titleStyle = MaterialTheme.typography.titleLarge.let {
        it.copy(fontSize = it.fontSize * reading.textScale, lineHeight = it.lineHeight * reading.textScale, fontFamily = fontFamily)
    }
    val bodyStyle = MaterialTheme.typography.bodyLarge.let {
        it.copy(fontSize = it.fontSize * reading.textScale, lineHeight = it.lineHeight * reading.textScale, fontFamily = fontFamily)
    }
    val scope = rememberCoroutineScope()
    var cardModifier = modifier
    if (swipeState != null) {
        cardModifier = cardModifier
            .offset { IntOffset(swipeState.offsetX.value.roundToInt(), swipeState.offsetY.value.roundToInt()) }
            .graphicsLayer { rotationZ = swipeState.offsetX.value / 40f }
            .pointerInput(post.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch { swipeState.settle()?.let(onSwiped) }
                    },
                    onDragCancel = {
                        scope.launch { swipeState.offsetX.animateTo(0f) }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { swipeState.offsetX.snapTo(swipeState.offsetX.value + dragAmount) }
                    },
                )
            }
    }

    Card(
        modifier = cardModifier.fillMaxSize(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "r/${post.subreddit}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (post.isNsfw) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "NSFW",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(post.title, style = titleStyle, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (post.body.isNotBlank()) {
                        Text(post.body, style = bodyStyle)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "${post.readMinutes} min read  ·  ▲ ${post.score}  ·  ${post.numComments} comments",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (swipeState != null) {
                val p = swipeState.progress
                if (p > 0.05f) {
                    DecisionLabel("SAVE", Color(0xFF2E7D32), p, Modifier.align(Alignment.TopStart))
                } else if (p < -0.05f) {
                    DecisionLabel("SKIP", Color(0xFFC62828), -p, Modifier.align(Alignment.TopEnd))
                }
            }
        }
    }
}

@Composable
private fun DecisionLabel(text: String, color: Color, alpha: Float, modifier: Modifier) {
    Text(
        text,
        modifier = modifier
            .padding(24.dp)
            .graphicsLayer { this.alpha = alpha },
        color = color,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
    )
}
