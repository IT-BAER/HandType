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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.baer.handtype.template.InkRow
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Write-on of a rendered note drawn Fit-centred: the [paper] shows at once and each ink row is
 * wiped in left to right on the [NoteWriteOn] schedule, counted from the result's appearance.
 */
fun Modifier.lineWriteOn(
    paper: ImageBitmap?,
    bitmapWidth: Int,
    bitmapHeight: Int,
    rows: List<InkRow>,
    elapsedMillis: Long,
): Modifier = drawWithContent {
    if (paper == null || rows.isEmpty() || elapsedMillis >= NoteWriteOn.totalMillis(rows.size)) {
        drawContent()
        return@drawWithContent
    }
    val scale = minOf(size.width / bitmapWidth, size.height / bitmapHeight)
    val dx = (size.width - bitmapWidth * scale) / 2f
    val dy = (size.height - bitmapHeight * scale) / 2f
    drawImage(
        image = paper,
        dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()),
        dstSize = IntSize((bitmapWidth * scale).roundToInt(), (bitmapHeight * scale).roundToInt()),
    )
    NoteWriteOn.revealBoxes(rows, elapsedMillis).forEach { box ->
        clipRect(
            left = dx + box.left * scale,
            top = dy + box.top * scale,
            right = dx + box.right * scale,
            bottom = dy + box.bottom * scale,
        ) { this@drawWithContent.drawContent() }
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
 * Pen nib tracing a cursive "ht" with a t-cross and an underline, as in the promo: every stroke
 * shows its first [progress] share of the total path length at once, the nib rides the total path.
 */
@Composable
fun PenWritingAnimation(
    modifier: Modifier = Modifier,
    inkColor: Color = Color(0xFF1A1410),
    traceColor: Color = Color(0xFF23443A),
    progress: (elapsedMillis: Long) -> Float = { t -> FastOutSlowInEasing.transform((t % 2200) / 2200f) },
) {
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { elapsedMillis = it - start }
    }

    Box(modifier = modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Spacer(modifier = Modifier.fillMaxSize().drawWithCache {
            val w = size.width
            val h = size.height
            val strokes = penTracePath(w, h).toStrokes()
            val totalLen = strokes.sumOf { it.length.toDouble() }.toFloat()
            val traceStroke = Stroke(width = w * 1.6f / 120f, cap = StrokeCap.Round)
            val segment = android.graphics.Path()
            onDrawBehind {
                val done = totalLen * progress(elapsedMillis).coerceIn(0f, 1f)
                strokes.forEach { stroke ->
                    segment.reset()
                    stroke.measure.getSegment(0f, minOf(done, stroke.length), segment, true)
                    drawPath(segment.asComposePath(), traceColor.copy(alpha = 0.6f), style = traceStroke)
                }
                var along = done
                val nibStroke = strokes.firstOrNull { s -> (along <= s.length).also { if (!it) along -= s.length } }
                    ?: return@onDrawBehind
                val pos = FloatArray(2)
                val tan = FloatArray(2)
                if (nibStroke.measure.getPosTan(along, pos, tan)) {
                    drawPenNib(
                        center = Offset(pos[0], pos[1]),
                        angle = kotlin.math.atan2(tan[1], tan[0]),
                        size = w * 2f * 5.2f / 120f,
                        inkColor = inkColor,
                    )
                }
            }
        })
    }
}

private class PenStroke(val measure: android.graphics.PathMeasure, val length: Float)

/** One measure per contour; Compose's PathMeasure only sees the first contour of a path. */
private fun Path.toStrokes(): List<PenStroke> {
    val all = android.graphics.PathMeasure(asAndroidPath(), false)
    val strokes = mutableListOf<PenStroke>()
    do {
        val contour = android.graphics.Path()
        all.getSegment(0f, all.length, contour, true)
        strokes += PenStroke(android.graphics.PathMeasure(contour, false), all.length)
    } while (all.nextContour())
    return strokes
}

private fun penTracePath(w: Float, h: Float): Path {
    // Signature-like path: a cursive "ht" shape
    return Path().apply {
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
}

private fun DrawScope.drawPenNib(
    center: Offset,
    angle: Float,
    size: Float,
    inkColor: Color,
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
