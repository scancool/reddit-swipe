package com.redditswipe.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.redditswipe.ReadingSettings
import com.redditswipe.data.RedditPost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** Fraction of the card width a slow drag must cover to count as a swipe. */
private const val SWIPE_THRESHOLD = 0.3f

/** A fast flick only needs to move this fraction of the width, so it can't fire from a stray tap. */
private const val FLING_MIN_DISTANCE = 0.08f

/** Fraction of the card height the card must be pushed up to move on to the next story. */
private const val UP_THRESHOLD = 0.12f

/** Tilt in degrees when the card has been dragged one full width. */
private const val MAX_ROTATION = 14f

/** Scale of the card waiting underneath the top one. */
const val BACK_SCALE = 0.94f

val CardShape = RoundedCornerShape(24.dp)

// Slightly underdamped, so a card that didn't make it springs back with a little life.
private val SnapBackSpring = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)

// Posts this short read like one-liners (shower thoughts) and get larger, centered type.
private const val SHORT_POST_CHARS = 280

/**
 * Position of the top card, shared by the drag gestures and the buttons. Offsets are plain snapshot
 * state written straight from pointer events and read only inside graphicsLayer, so dragging redraws
 * the card without recomposing it.
 */
@Stable
class CardSwipeState(
    val widthPx: Float,
    val heightPx: Float,
    private val flingVelocityPx: Float,
    initialScale: Float,
) {
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    /** +1 when grabbed in the top half, -1 in the bottom half, so the card pivots around the finger. */
    var tiltDirection by mutableFloatStateOf(1f)

    /** Grows from the back-card size to full size when this card reaches the top of the deck. */
    val scale = Animatable(initialScale)

    private var settleJob: Job? = null

    private val threshold get() = widthPx * SWIPE_THRESHOLD

    /** -1..1, how far the card has been dragged toward skip (-1) or save (+1). */
    val progress: Float get() = (offsetX / threshold).coerceIn(-1f, 1f)

    /** 0..1, how far the card has been pushed toward "next". */
    val upProgress: Float get() = (-offsetY / (heightPx * UP_THRESHOLD)).coerceIn(0f, 1f)

    val rotation: Float get() = (offsetX / widthPx) * MAX_ROTATION * tiltDirection

    /** True when the drag has gone far enough that letting go now would swipe. */
    val isPastThreshold: Boolean get() = abs(offsetX) > threshold

    val isPastUpThreshold: Boolean get() = upProgress >= 1f

    /** Stops a running snap-back so a new drag picks the card up where it is. */
    fun grab() {
        settleJob?.cancel()
    }

    fun dragBy(dx: Float) {
        offsetX += dx
    }

    /** Moves the card up with the finger; it never goes below its resting place. */
    fun dragUpBy(dy: Float) {
        offsetY = (offsetY + dy).coerceAtMost(0f)
    }

    /**
     * Decides a horizontal release: true for save, false for skip, null to snap back. A quick flick
     * counts even if it's short; a slow drag must pass the threshold; and flicking back toward the
     * middle cancels a drag that had passed it.
     */
    fun decide(velocityX: Float): Boolean? {
        val minFling = widthPx * FLING_MIN_DISTANCE
        return when {
            velocityX > flingVelocityPx && offsetX > minFling -> true
            velocityX < -flingVelocityPx && offsetX < -minFling -> false
            offsetX > threshold && velocityX > -flingVelocityPx -> true
            offsetX < -threshold && velocityX < flingVelocityPx -> false
            else -> null
        }
    }

    /** Decides an upward release: true to move on to the next story. */
    fun decideUp(velocityY: Float): Boolean =
        (velocityY < -flingVelocityPx && -offsetY > heightPx * FLING_MIN_DISTANCE) ||
            (isPastUpThreshold && velocityY < flingVelocityPx)

    fun snapBack(scope: CoroutineScope) {
        settleJob?.cancel()
        settleJob = scope.launch {
            launch { animate(offsetX, 0f, animationSpec = SnapBackSpring) { v, _ -> offsetX = v } }
            launch { animate(offsetY, 0f, animationSpec = SnapBackSpring) { v, _ -> offsetY = v } }
        }
    }
}

/** Scale of the card underneath, which grows as the top card is dragged away. */
fun backScale(dragProgress: Float): Float = BACK_SCALE + (1f - BACK_SCALE) * dragProgress.coerceIn(0f, 1f)

/**
 * One story card. It is purely visual: position, tilt, scale and shadow come from [transform], which
 * runs in the card's single graphics layer, and [decision] (-1..1) fades in the SAVE/SKIP stamps.
 * Both are read only while drawing, so a moving card never recomposes or re-lays-out its text.
 */
@Composable
fun SwipeCard(
    post: RedditPost,
    reading: ReadingSettings,
    modifier: Modifier = Modifier,
    decision: (() -> Float)? = null,
    transform: GraphicsLayerScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val isShort = post.body.length < SHORT_POST_CHARS
    val fontFamily = if (reading.serif) FontFamily.Serif else FontFamily.Default
    val typography = MaterialTheme.typography
    val titleStyle = remember(isShort, post.title.length, reading, typography) {
        // Short titles get bigger type; a long title stays at titleLarge so the story still fits.
        val base = when {
            isShort -> typography.headlineMedium
            post.title.length <= 70 -> typography.headlineSmall
            else -> typography.titleLarge
        }
        base.copy(
            fontSize = base.fontSize * reading.textScale,
            lineHeight = 1.28.em,
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
        )
    }
    val bodyStyle = remember(isShort, reading, typography) {
        val base = if (isShort) typography.titleLarge else typography.bodyLarge
        base.copy(
            fontSize = base.fontSize * reading.textScale,
            lineHeight = 1.55.em,
            fontFamily = fontFamily,
            hyphens = Hyphens.Auto,
            lineBreak = LineBreak.Paragraph,
        )
    }
    val header = remember(post.subreddit, post.isNsfw, colors) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = colors.primary)) { append("r/${post.subreddit}") }
            if (post.isNsfw) {
                append("   ")
                withStyle(SpanStyle(color = colors.error, fontWeight = FontWeight.Bold)) { append("NSFW") }
            }
        }
    }
    val scroll = rememberScrollState()
    val container = colors.surfaceContainerHigh

    // Box (for the stamps) > Column (header, text, footer): the card itself is just modifiers.
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                transform()
                shape = CardShape
                clip = true
            }
            .background(container),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(header, style = typography.labelLarge)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .fadeBottomEdge(scroll, container),
                contentAlignment = if (isShort) Alignment.Center else Alignment.TopStart,
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                    Text(post.title, style = titleStyle)
                    if (post.body.isNotBlank()) {
                        Text(post.body, style = bodyStyle, modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
            Text(
                "${post.readMinutes} min read  ·  ▲ ${post.score}  ·  ${post.numComments} comments",
                style = typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
        if (decision != null) DecisionStamps(decision)
    }
}

/**
 * A color wash from the edge the card is heading toward, plus SAVE (top left, while dragging right)
 * and SKIP (top right, while dragging left) stamps. All of it sits in its own layer, so redrawing it
 * every drag frame doesn't re-record the story text underneath.
 */
@Composable
private fun DecisionStamps(decision: () -> Float) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val save = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
    val skip = if (dark) Color(0xFFE57373) else Color(0xFFC62828)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer()
            .drawBehind { drawDecisionTint(decision(), save, skip) }
            .padding(28.dp),
    ) {
        Stamp("SAVE", save, -12f, Modifier.align(Alignment.TopStart)) { decision().coerceAtLeast(0f) }
        Stamp("SKIP", skip, 12f, Modifier.align(Alignment.TopEnd)) { (-decision()).coerceAtLeast(0f) }
    }
}

@Composable
private fun Stamp(text: String, color: Color, tilt: Float, modifier: Modifier, amount: () -> Float) {
    Text(
        text,
        modifier = modifier
            .graphicsLayer {
                val a = amount()
                // Stays invisible through the first bit of a drag, then grows in as it nears the threshold.
                alpha = ((a - 0.1f) / 0.7f).coerceIn(0f, 1f)
                val s = 0.85f + 0.15f * a
                scaleX = s
                scaleY = s
                rotationZ = tilt
            }
            .border(3.dp, color, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = color,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Black,
    )
}

private fun DrawScope.drawDecisionTint(d: Float, save: Color, skip: Color) {
    val a = abs(d)
    if (a < 0.02f) return
    val color = (if (d > 0) save else skip).copy(alpha = 0.16f * a)
    val (from, to) = if (d > 0) 0f to size.width * 0.6f else size.width to size.width * 0.4f
    drawRect(Brush.horizontalGradient(listOf(color, Color.Transparent), startX = from, endX = to))
}

/** Fades the text out at the bottom while more of the story is below, hinting that it scrolls. */
private fun Modifier.fadeBottomEdge(scroll: ScrollState, background: Color) = drawWithContent {
    drawContent()
    if (scroll.canScrollForward) {
        val h = max(32.dp.toPx(), size.height * 0.08f)
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, background), startY = size.height - h, endY = size.height),
            topLeft = Offset(0f, size.height - h),
            size = Size(size.width, h),
        )
    }
}
