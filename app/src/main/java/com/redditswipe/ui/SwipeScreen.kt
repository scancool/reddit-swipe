package com.redditswipe.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.redditswipe.ReadMode
import com.redditswipe.ReadingSettings
import com.redditswipe.SwipeUiState
import com.redditswipe.data.RedditPost
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Cards wider than this make lines too long to read comfortably (tablets, landscape). */
private val MaxCardWidth = 560.dp

/** Release speed that counts as a deliberate flick. */
private val FlingVelocity = 900.dp

private enum class Exit { Left, Right }

/** A swiped card playing its exit animation; the deck has already moved on underneath it. */
private class LeavingCard(
    val id: Long,
    val post: RedditPost,
    val exit: Exit,
    val startX: Float,
    val startRotation: Float,
    val velocity: Float,
)

@Composable
fun SwipeScreen(
    state: SwipeUiState,
    onSwipeLeft: (RedditPost) -> Unit,
    onSwipeRight: (RedditPost) -> Unit,
    onRetry: () -> Unit,
    onOpenSubreddits: () -> Unit,
    onOpenNews: () -> Unit,
    onTextScale: (Float) -> Unit,
    onReadMode: (ReadMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current
    val deck = state.visibleDeck
    val top = deck.firstOrNull()
    val next = deck.getOrNull(1)

    var slotSize by remember { mutableStateOf(IntSize.Zero) }
    val maxCardWidthPx = with(density) { MaxCardWidth.toPx() }
    val cardWidthPx = minOf(slotSize.width.toFloat(), maxCardWidthPx)
    val flingPx = with(density) { FlingVelocity.toPx() }

    val leaving = remember { mutableStateListOf<LeavingCard>() }
    var nextLeavingId by remember { mutableStateOf(0L) }
    // How big the card underneath was when the top one left, so it grows on from there instead of popping.
    var handoffScale by remember { mutableFloatStateOf(BACK_SCALE) }

    // One swipe state per top card, rebuilt when a new card reaches the top.
    val swipeState = remember(top?.id, cardWidthPx) {
        // The width is 0 until the first layout; 1px keeps the progress math finite until then.
        CardSwipeState(cardWidthPx.coerceAtLeast(1f), flingPx, handoffScale)
    }
    val dragAmount = { abs(swipeState.progress) }
    val topTransform: GraphicsLayerScope.() -> Unit = {
        translationX = swipeState.offsetX
        rotationZ = swipeState.rotation
        scaleX = swipeState.scale.value
        scaleY = swipeState.scale.value
        // Lifts off the deck as it's dragged.
        shadowElevation = lerp(4f, 14f, dragAmount()).dp.toPx()
    }
    val backTransform: GraphicsLayerScope.() -> Unit = {
        // Grows toward full size as the top card is dragged away.
        val s = backScale(dragAmount())
        scaleX = s
        scaleY = s
        shadowElevation = 1.dp.toPx()
    }

    // Hands the card to the leaving layer and updates the deck at once, so the next card is live
    // immediately and rapid swipes never wait for an animation.
    fun commit(post: RedditPost, exit: Exit, velocity: Float) {
        // A second tap can land before the deck recomposes; don't swipe (and count, or save) the same post twice.
        if (leaving.any { it.post.id == post.id }) return
        handoffScale = backScale(dragAmount())
        leaving += LeavingCard(
            id = nextLeavingId++,
            post = post,
            exit = exit,
            startX = swipeState.offsetX,
            startRotation = swipeState.rotation,
            velocity = velocity,
        )
        when (exit) {
            Exit.Left -> onSwipeLeft(post)
            Exit.Right -> onSwipeRight(post)
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        ReadModeSwitch(
            mode = state.readMode,
            storyMode = state.storyMode,
            onChange = onReadMode,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
        )
        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { slotSize = it },
            contentAlignment = Alignment.Center,
        ) {
            val cardModifier = Modifier.widthIn(max = MaxCardWidth).fillMaxSize()

            // Under the last card sits a loading placeholder or the end-of-feed card, so the final swipe
            // reveals what comes next rather than a blank screen.
            if (next == null) {
                FeedEndCard(state, onRetry, onOpenSubreddits, onOpenNews, cardModifier)
            }

            // Back card and top card from one keyed loop: when the back card moves up it keeps its
            // composition and laid-out text instead of being rebuilt.
            listOfNotNull(next, top).forEach { post ->
                key(post.id) {
                    val isTop = post.id == top?.id
                    if (isTop) {
                        LaunchedEffect(swipeState) {
                            swipeState.scale.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                    // A single call site for both roles, so the card isn't rebuilt when it moves up.
                    SwipeCard(
                        post = post,
                        reading = state.reading,
                        decision = if (isTop) ({ swipeState.progress }) else null,
                        modifier = if (!isTop) cardModifier else cardModifier
                            .horizontalSwipe(swipeState, view) { right, v ->
                                commit(post, if (right) Exit.Right else Exit.Left, v)
                            }
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction("Save") { commit(post, Exit.Right, 0f); true },
                                    CustomAccessibilityAction("Skip") { commit(post, Exit.Left, 0f); true },
                                )
                            },
                        transform = if (isTop) topTransform else backTransform,
                        onTextScale = if (isTop) onTextScale else null,
                        onOpen = if (isTop && post.isNews) ({ uriHandler.openUri(post.url) }) else null,
                    )
                }
            }

            leaving.forEach { card ->
                key("leaving-${card.id}") {
                    LeavingCardView(card, state.reading, cardModifier, cardWidthPx) {
                        leaving -= card
                    }
                }
            }
        }
        // Until the first swipe, says what each direction does; the SAVE/SKIP stamps only show mid-drag.
        if (state.showSwipeHint && top != null) {
            Row(
                Modifier.widthIn(max = MaxCardWidth).fillMaxWidth().align(Alignment.CenterHorizontally).padding(top = 12.dp),
            ) {
                Text("← Swipe left to skip", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("Swipe right to save →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * News | Stories, centered above the deck and no wider than a card. Under Stories, chips pick the
 * length (All, Quick, Long); Stories reopens on whichever length was picked last.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadModeSwitch(
    mode: ReadMode,
    storyMode: ReadMode,
    onChange: (ReadMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isNews = mode == ReadMode.News
    Column(modifier.widthIn(max = MaxCardWidth).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(true to "News", false to "Stories").forEachIndexed { index, (news, label) ->
                SegmentedButton(
                    selected = news == isNews,
                    onClick = { onChange(if (news) ReadMode.News else storyMode) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                    // No checkmark: the fill already shows the choice.
                    icon = {},
                    label = { Text(label, maxLines = 1) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (!isNews) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadMode.STORY_MODES.forEach { length ->
                    FilterChip(
                        selected = length == mode,
                        onClick = { onChange(length) },
                        label = { Text(length.label) },
                    )
                }
            }
        }
    }
}

/**
 * Drags the card sideways and decides on release using distance and speed (see [CardSwipeState.decide]).
 * Gives a tick of haptic feedback each time the drag crosses the point of no return, either way.
 */
private fun Modifier.horizontalSwipe(
    swipeState: CardSwipeState,
    view: View,
    onSwiped: (right: Boolean, velocity: Float) -> Unit,
): Modifier = pointerInput(swipeState) {
    val tracker = VelocityTracker()
    var wasPast = false
    coroutineScope {
        val scope = this
        detectHorizontalDragGestures(
            onDragStart = { start ->
                swipeState.grab()
                tracker.resetTracking()
                swipeState.tiltDirection = if (start.y < size.height / 2f) 1f else -1f
                wasPast = swipeState.isPastThreshold
            },
            onHorizontalDrag = { change, dx ->
                change.consume()
                // This modifier sits outside the card's graphics layer, so positions aren't skewed by the tilt.
                tracker.addPosition(change.uptimeMillis, change.position)
                swipeState.dragBy(dx)
                val past = swipeState.isPastThreshold
                if (past != wasPast) view.thresholdHaptic()
                wasPast = past
            },
            onDragEnd = {
                val v = tracker.calculateVelocity().x
                when (val right = swipeState.decide(v)) {
                    null -> swipeState.snapBack(scope)
                    else -> onSwiped(right, v)
                }
            },
            onDragCancel = { swipeState.snapBack(scope) },
        )
    }
}

/** Flies a committed card off screen from wherever the finger let go, then removes itself. */
@Composable
private fun LeavingCardView(
    card: LeavingCard,
    reading: ReadingSettings,
    modifier: Modifier,
    widthPx: Float,
    onGone: () -> Unit,
) {
    val x = remember { Animatable(card.startX) }
    val rotation = remember { Animatable(card.startRotation) }
    val decision = if (card.exit == Exit.Right) 1f else -1f
    LaunchedEffect(card) {
        val targetX = widthPx * 1.5f * decision
        val distance = abs(targetX - card.startX)
        // A flung card keeps the finger's speed; an accessibility Save/Skip accelerates away from rest.
        val flung = abs(card.velocity) > 1f
        val duration = if (flung) (distance / abs(card.velocity) * 1000f).toInt().coerceIn(120, 300) else 260
        val spec = tween<Float>(duration, easing = if (flung) LinearEasing else FastOutLinearInEasing)
        coroutineScope {
            launch { x.animateTo(targetX, spec) }
            launch { rotation.animateTo(decision * 22f, spec) }
        }
        onGone()
    }
    SwipeCard(
        post = card.post,
        reading = reading,
        decision = { decision },
        modifier = modifier,
        transform = {
            translationX = x.value
            rotationZ = rotation.value
            shadowElevation = 14.dp.toPx()
        },
    )
}

private fun View.thresholdHaptic() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        else HapticFeedbackConstants.CLOCK_TICK,
    )
}
