package com.baer.handtype.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Modern stagger-strip reveal: bitmap divided into N horizontal bands, each fades in
 * with cascading delay. Outer subtle scale gives a "rising into focus" feel.
 * Combined effect is layered, structured, distinctly non-wipe.
 */
fun Modifier.stripReveal(progress: Float, strips: Int = 6): Modifier {
    val p = progress.coerceIn(0f, 1f)
    val scaleP = (p / 0.4f).coerceIn(0f, 1f)
    return this
        .graphicsLayer {
            scaleX = 0.96f + 0.04f * scaleP
            scaleY = 0.96f + 0.04f * scaleP
        }
        .drawWithContent {
            this@drawWithContent.drawContent()
            val stripHeight = size.height / strips
            val stagger = 0.07f
            val stripDuration = 1f - stagger * (strips - 1)
            for (i in 0 until strips) {
                val startAt = i * stagger
                val localP = ((p - startAt) / stripDuration).coerceIn(0f, 1f)
                if (localP >= 1f) continue
                drawRect(
                    color = Color(0xFFFFFFFF).copy(alpha = 1f - localP),
                    topLeft = Offset(0f, i * stripHeight),
                    size = Size(size.width, stripHeight),
                )
            }
        }
}

/**
 * A more organic ink-spread reveal that scales from center with fade.
 * Alternative to linear inkReveal for a "paper absorbing ink" feel.
 */
fun Modifier.inkSpreadReveal(
    progress: Float,
): Modifier = graphicsLayer {
    val p = progress.coerceIn(0f, 1f)
    alpha = p
    scaleX = 0.96f + (0.04f * p)
    scaleY = 0.96f + (0.04f * p)
}

/**
 * Animated pen nib that traces a looping signature-like path.
 * Perfect for the "Generating your note…" loading state.
 */
@Composable
fun PenWritingAnimation(
    modifier: Modifier = Modifier,
    inkColor: Color = Color(0xFF1A1410),
    traceColor: Color = Color(0xFF23443A),
    penSize: Float = 24f,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pen")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "trace",
    )

    val density = LocalDensity.current
    val penPx = with(density) { penSize.dp.toPx() }

    Box(modifier = modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Signature-like path: a cursive "ht" shape
            val path = Path().apply {
                moveTo(w * 0.15f, h * 0.55f)
                // h stem
                cubicTo(w * 0.15f, h * 0.25f, w * 0.18f, h * 0.25f, w * 0.20f, h * 0.45f)
                cubicTo(w * 0.22f, h * 0.60f, w * 0.25f, h * 0.65f, w * 0.28f, h * 0.55f)
                // h arch
                cubicTo(w * 0.30f, h * 0.40f, w * 0.35f, h * 0.35f, w * 0.40f, h * 0.45f)
                cubicTo(w * 0.42f, h * 0.50f, w * 0.42f, h * 0.60f, w * 0.40f, h * 0.70f)
                // connect to t
                cubicTo(w * 0.38f, h * 0.80f, w * 0.45f, h * 0.75f, w * 0.50f, h * 0.55f)
                // t stem
                cubicTo(w * 0.52f, h * 0.40f, w * 0.54f, h * 0.30f, w * 0.55f, h * 0.25f)
                cubicTo(w * 0.56f, h * 0.20f, w * 0.58f, h * 0.20f, w * 0.58f, h * 0.30f)
                cubicTo(w * 0.58f, h * 0.45f, w * 0.58f, h * 0.60f, w * 0.58f, h * 0.75f)
                // t cross
                moveTo(w * 0.48f, h * 0.38f)
                cubicTo(w * 0.52f, h * 0.36f, w * 0.60f, h * 0.34f, w * 0.68f, h * 0.36f)
                // flourish underline
                moveTo(w * 0.20f, h * 0.82f)
                cubicTo(w * 0.35f, h * 0.78f, w * 0.55f, h * 0.80f, w * 0.75f, h * 0.84f)
            }

            val measure = PathMeasure()
            measure.setPath(path, false)
            val totalLen = measure.length

            // Draw completed trace
            val completedLen = totalLen * progress
            val tracePath = Path()
            measure.getSegment(0f, completedLen, tracePath, true)
            drawPath(
                path = tracePath,
                color = traceColor.copy(alpha = 0.6f),
                style = Stroke(width = 3.5f),
            )

            // Draw pen nib at current position
            if (progress < 1f) {
                val pos = measure.getPosition(completedLen)
                val tan = measure.getTangent(completedLen)
                if (pos != null && tan != null) {
                    val angle = kotlin.math.atan2(tan.y, tan.x)
                    drawPenNib(
                        center = pos,
                        angle = angle,
                        size = penPx,
                        inkColor = inkColor,
                        traceColor = traceColor,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawPenNib(
    center: Offset,
    angle: Float,
    size: Float,
    inkColor: Color,
    traceColor: Color,
) {
    val s = size / 2f
    val ca = cos(angle)
    val sa = sin(angle)

    // Nib body (rotated rectangle)
    val bodyPath = Path().apply {
        moveTo(center.x + (-s * 0.3f * ca - s * 0.8f * sa), center.y + (-s * 0.3f * sa + s * 0.8f * ca))
        lineTo(center.x + (s * 0.3f * ca - s * 0.8f * sa), center.y + (s * 0.3f * sa + s * 0.8f * ca))
        lineTo(center.x + (s * 0.5f * ca + s * 0.4f * sa), center.y + (s * 0.5f * sa - s * 0.4f * ca))
        lineTo(center.x + (-s * 0.5f * ca + s * 0.4f * sa), center.y + (-s * 0.5f * sa - s * 0.4f * ca))
        close()
    }
    drawPath(path = bodyPath, color = inkColor.copy(alpha = 0.9f))

    // Ink tip
    val tip = Offset(
        center.x + (s * 0.6f * ca),
        center.y + (s * 0.6f * sa),
    )
    drawCircle(
        color = traceColor.copy(alpha = 0.85f),
        radius = s * 0.18f,
        center = tip,
    )
}

/**
 * Staggered fade + scale entrance for lazy grid items.
 */
@Composable
fun staggeredItemAnimation(
    index: Int,
    content: @Composable (Modifier) -> Unit,
) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 35L)
        anim.animateTo(
            targetValue = 1f,
            animationSpec = tween(250, easing = FastOutSlowInEasing),
        )
    }
    content(
        Modifier.graphicsLayer {
            alpha = anim.value
            scaleX = 0.92f + (0.08f * anim.value)
            scaleY = 0.92f + (0.08f * anim.value)
        }
    )
}

/**
 * Pager page parallax: scale + fade so adjacent cards clearly recede.
 */
fun Modifier.pagerParallax(pageOffset: Float): Modifier = graphicsLayer {
    val absOffset = pageOffset.coerceIn(-1f, 1f)
    val scale = 1f - (absOffset * 0.18f)
    scaleX = scale
    scaleY = scale
    alpha = 1f - (absOffset * 0.55f)
}

/**
 * Smooth scale for selection states (background picker, buttons).
 */
@Composable
fun selectionBounceScale(selected: Boolean): Float {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "selectionScale",
    )
    return scale
}

/**
 * Shimmer effect for paper texture highlights.
 */
@Composable
fun paperShimmerOffset(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val offset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer",
    )
    return offset
}

@Composable
fun Modifier.pressScale(): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "pressScale",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
}
