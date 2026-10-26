package com.redditswipe.ui

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
import kotlin.math.roundToInt

/** Fraction of the card width a slow drag must cover to count as a swipe. */
private const val SWIPE_THRESHOLD = 0.3f

/** A fast flick only needs to move this fraction of the width, so it can't fire from a stray tap. */
private const val FLING_MIN_DISTANCE = 0.08f

/** Tilt in degrees when the card has been dragged one full width. */
private const val MAX_ROTATION = 14f

/** Scale of the card waiting underneath the top one. */
const val BACK_SCALE = 0.94f

val CardShape = RoundedCornerShape(24.dp)

// Slightly underdamped, so a card that didn't make it springs back with a little life.
private val SnapBackSpring = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)

// Posts this short read like one-liners (shower thoughts) and get larger, centered type.
private const val SHORT_POST_CHARS = 280

/** Limits for pinching the story text, relative to the size set in Settings. */
private const val MIN_BODY_ZOOM = 0.7f
private const val MAX_BODY_ZOOM = 2.5f

/**
 * Position of the top card, shared by the drag gesture and the top bar's heart. Offsets are plain snapshot
 * state written straight from pointer events and read only inside graphicsLayer, so dragging redraws
 * the card without recomposing it.
 */
@Stable
class CardSwipeState(
    val widthPx: Float,
    private val flingVelocityPx: Float,
    initialScale: Float,
) {
    var offsetX by mutableFloatStateOf(0f)
        private set

    /** +1 when grabbed in the top half, -1 in the bottom half, so the card pivots around the finger. */
    var tiltDirection by mutableFloatStateOf(1f)

    /** Grows from the back-card size to full size when this card reaches the top of the deck. */
    val scale = Animatable(initialScale)

    private var settleJob: Job? = null

    private val threshold get() = widthPx * SWIPE_THRESHOLD

    /** -1..1, how far the card has been dragged toward skip (-1) or save (+1). */
    val progress: Float get() = (offsetX / threshold).coerceIn(-1f, 1f)

    val rotation: Float get() = (offsetX / widthPx) * MAX_ROTATION * tiltDirection

    /** True when the drag has gone far enough that letting go now would swipe. */
    val isPastThreshold: Boolean get() = abs(offsetX) > threshold

    /** Stops a running snap-back so a new drag picks the card up where it is. */
    fun grab() {
        settleJob?.cancel()
    }

    fun dragBy(dx: Float) {
        offsetX += dx
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

    fun snapBack(scope: CoroutineScope) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(offsetX, 0f, animationSpec = SnapBackSpring) { v, _ -> offsetX = v }
        }
    }
}

/** Scale of the card underneath, which grows as the top card is dragged away. */
fun backScale(dragProgress: Float): Float = BACK_SCALE + (1f - BACK_SCALE) * dragProgress.coerceIn(0f, 1f)

/**
 * One story card. It is purely visual: position, tilt, scale and shadow come from [transform], which
 * runs in the card's single graphics layer, and [decision] (-1..1) fades in the SAVE/SKIP stamps.
 * Both are read only while drawing, so a moving card never recomposes or re-lays-out its text.
 * With [onBodyZoom], pinching with two fingers resizes the story text (never the title) and reports
 * the new zoom when the fingers lift. [onOpen] adds a link to the full article under news cards.
 */
@Composable
fun SwipeCard(
    post: RedditPost,
    reading: ReadingSettings,
    modifier: Modifier = Modifier,
    decision: (() -> Float)? = null,
    transform: GraphicsLayerScope.() -> Unit = {},
    onBodyZoom: ((Float) -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
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
    // Live zoom while pinching; snapped to 5% steps so the text re-wraps a few times per pinch, not every frame.
    val zoom = remember(reading.bodyZoom) { mutableFloatStateOf(reading.bodyZoom) }
    val zoomStep by remember(zoom) { derivedStateOf { (zoom.floatValue * 20).roundToInt() / 20f } }
    var pinching by remember { mutableStateOf(false) }
    val bodyStyle = remember(isShort, reading, typography, zoomStep) {
        val base = if (isShort) typography.titleLarge else typography.bodyLarge
        base.copy(
            fontSize = base.fontSize * reading.textScale * zoomStep,
            lineHeight = 1.55.em,
            fontFamily = fontFamily,
            hyphens = Hyphens.Auto,
            lineBreak = LineBreak.Paragraph,
        )
    }
    val header = remember(post.sourceLabel, post.isNsfw, colors) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = colors.primary)) { append(post.sourceLabel) }
            if (post.isNsfw) {
                append("   ")
                withStyle(SpanStyle(color = colors.error, fontWeight = FontWeight.Bold)) { append("NSFW") }
            }
        }
    }
    val scroll = rememberScrollState()
    val container = colors.surfaceContainerHigh
    // Keeps the reader's place: as the text re-wraps, scroll to the same fraction of the story.
    var scrollFraction by remember { mutableFloatStateOf(0f) }
    if (pinching) {
        LaunchedEffect(scroll) {
            snapshotFlow { scroll.maxValue }.collect { max -> scroll.scrollTo((scrollFraction * max).roundToInt()) }
        }
    }

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
                    .fadeBottomEdge(scroll, container)
                    .then(
                        if (onBodyZoom == null || post.body.isBlank()) Modifier
                        else Modifier.pinchToZoom(
                            zoom = zoom,
                            onStart = {
                                scrollFraction = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
                                pinching = true
                            },
                            onEnd = {
                                pinching = false
                                onBodyZoom(it)
                            },
                        ),
                    ),
                contentAlignment = if (isShort) Alignment.Center else Alignment.TopStart,
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                    Text(post.title, style = titleStyle)
                    if (post.body.isNotBlank()) {
                        Text(post.body, style = bodyStyle, modifier = Modifier.padding(top = 14.dp))
                    }
                }
                if (pinching) {
                    Text(
                        "${(zoom.floatValue * 100).roundToInt()}%",
                        style = typography.labelLarge,
                        color = colors.inverseOnSurface,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .background(colors.inverseSurface.copy(alpha = 0.85f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    footerText(post, isShort),
                    style = typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (onOpen != null) {
                    Text(
                        "Full article ↗",
                        style = typography.labelLarge,
                        color = colors.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onOpen)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (decision != null) DecisionStamps(decision)
    }
}

private fun footerText(post: RedditPost, isShort: Boolean): String {
    val length = if (isShort) (if (post.isNews) "Headline" else "Quick read") else "${post.readMinutes} min read"
    if (!post.isNews) return "$length  ·  ▲ ${post.score}  ·  ${post.numComments} comments"
    if (post.publishedAt <= 0L) return length
    val age = DateUtils.getRelativeTimeSpanString(
        post.publishedAt,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )
    return "$length  ·  $age"
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

/**
 * Two-finger pinch that scales [zoom]. It listens in the Initial pass and consumes two-finger moves,
 * so the story doesn't scroll and the card doesn't swipe while pinching; one finger passes straight through.
 */
private fun Modifier.pinchToZoom(
    zoom: MutableFloatState,
    onStart: () -> Unit,
    onEnd: (Float) -> Unit,
) = pointerInput(zoom) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zooming = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                if (!zooming) {
                    zooming = true
                    onStart()
                }
                zoom.floatValue = (zoom.floatValue * event.calculateZoom()).coerceIn(MIN_BODY_ZOOM, MAX_BODY_ZOOM)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (zooming) onEnd(zoom.floatValue)
    }
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
