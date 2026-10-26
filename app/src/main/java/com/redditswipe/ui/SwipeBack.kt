package com.redditswipe.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Fraction of the width a slow drag must cover to go back. */
private const val BACK_THRESHOLD = 0.35f

/** A flick this fast (to the right) goes back however short it was. */
private val BackFlingVelocity = 800.dp

/**
 * Lets a menu screen be swiped away to the right, iOS-style, to go back: the page follows the finger and
 * either slides off (calling [onBack]) or springs back. Android's own back gesture moves it the same way.
 * Rows that scroll sideways inside the page keep their drags; only drags they leave alone move the page.
 */
@Composable
fun SwipeBackContainer(
    enabled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val flingPx = with(LocalDensity.current) { BackFlingVelocity.toPx() }

    suspend fun slideOffAndGoBack() {
        offset.animateTo(width.toFloat(), tween(180))
        onBack()
    }

    // The system back gesture (Android 14+ reports its progress; older versions just complete).
    PredictiveBackHandler(enabled) { progress ->
        try {
            progress.collect { event -> offset.snapTo(event.progress * width * BACK_THRESHOLD) }
            slideOffAndGoBack()
        } catch (e: CancellationException) {
            offset.animateTo(0f)
            throw e
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width }
            // What shows behind the page as it slides away.
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset.value
                    shadowElevation = if (offset.value > 0f) 8.dp.toPx() else 0f
                }
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    if (!enabled) Modifier
                    else Modifier.pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        detectHorizontalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                scope.launch { offset.snapTo((offset.value + dx).coerceAtLeast(0f)) }
                            },
                            onDragEnd = {
                                val velocity = tracker.calculateVelocity().x
                                val goBack = offset.value > width * BACK_THRESHOLD ||
                                    (velocity > flingPx && offset.value > 0f)
                                scope.launch { if (goBack) slideOffAndGoBack() else offset.animateTo(0f) }
                            },
                            onDragCancel = { scope.launch { offset.animateTo(0f) } },
                        )
                    },
                ),
        ) {
            content()
        }
    }
}
