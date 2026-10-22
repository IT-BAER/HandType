package com.baer.handtype.feature.capture.stylus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.baer.handtype.R
import com.baer.handtype.feature.capture.PracticeSheetGenerator
import com.baer.handtype.testing.HandTypeTestTags
import kotlinx.coroutines.launch

/** Strokes written for every sheet character plus the current position; accepted = before [index]. */
@Stable
class StylusSession(
    val characters: List<Char>,
    initialIndex: Int = 0,
    initialGlyphs: List<List<InkStroke>> = emptyList(),
) {
    private val restored = initialGlyphs.size == characters.size

    var index by mutableIntStateOf(if (restored) initialIndex.coerceIn(0, characters.lastIndex) else 0)

    val glyphs: SnapshotStateList<List<InkStroke>> = mutableStateListOf<List<InkStroke>>().apply {
        addAll(if (restored) initialGlyphs else List(characters.size) { emptyList() })
    }

    fun reset() {
        index = 0
        for (i in glyphs.indices) glyphs[i] = emptyList()
    }

    companion object {
        /** The printed sheet's [cells] without its blank filler cells. */
        fun sheetCharacters(cells: List<Char> = PracticeSheetGenerator.cellCharacters()): List<Char> =
            cells.filter { it != ' ' }

        fun saver(characters: List<Char>): Saver<StylusSession, Any> = listSaver(
            save = { session -> listOf<Any>(session.index) + session.glyphs.map { InkStrokeCodec.encode(it) } },
            restore = { saved ->
                StylusSession(
                    characters = characters,
                    initialIndex = saved.firstOrNull() as? Int ?: 0,
                    initialGlyphs = saved.drop(1).map { (it as? FloatArray)?.let(InkStrokeCodec::decode).orEmpty() },
                )
            },
        )
    }
}

private val PaperColor = Color(0xFFFFFCF5)
private val InkColor = Color(0xFF1A1410)
private val GuideColor = Color(0xFFCEC6B4)
private val BorderColor = Color(0xFFCCC4B4)

// Points closer than this (in cell heights) to the previous sample are dropped to keep saved state small.
private const val MIN_POINT_STEP = 0.003f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StylusCaptureScreen(
    session: StylusSession,
    recognizer: InkRecognizer,
    onFinished: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    isSaving: Boolean = false,
    errorText: String? = null,
    geometry: CellGeometry = remember { CellGeometry.printed() },
) {
    val scope = rememberCoroutineScope()
    val currentOnFinished by rememberUpdatedState(onFinished)
    val total = session.characters.size
    val index = session.index
    val expected = session.characters[index]
    val strokes = session.glyphs[index]
    val livePoints = remember { mutableStateListOf<InkPoint>() }
    var hint by remember { mutableStateOf<PrecheckResult?>(null) }
    // After a recognition mismatch a second Next keeps the letter, so unusual handwriting never blocks.
    var overrideArmed by remember { mutableStateOf(false) }
    var shapeOnly by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val fly = remember { Animatable(0f) }
    val ghostAlpha = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    val stripState = rememberLazyListState()
    val canAct = !checking && !isSaving

    LaunchedEffect(recognizer) { recognizer.prepare() }
    LaunchedEffect(index) { if (index > 0) stripState.animateScrollToItem(index - 1) }

    fun strokesChanged() {
        hint = null
        overrideArmed = false
    }

    fun addPoint(point: InkPoint) {
        val last = livePoints.lastOrNull()
        if (last != null) {
            val dx = (point.x - last.x) * geometry.aspectRatio
            val dy = point.y - last.y
            if (dx * dx + dy * dy < MIN_POINT_STEP * MIN_POINT_STEP) return
        }
        livePoints.add(point)
    }

    fun endStroke() {
        if (livePoints.isEmpty()) return
        val i = session.index
        session.glyphs[i] = session.glyphs[i] + InkStroke(livePoints.toList())
        livePoints.clear()
        strokesChanged()
    }

    fun next() {
        if (!canAct) return
        checking = true
        scope.launch {
            try {
                val i = session.index
                val character = session.characters[i]
                val current = session.glyphs[i]
                val shape = LetterPrecheck.shapeCheck(character, current)
                val result = when {
                    shape != PrecheckResult.Pass -> shape
                    overrideArmed -> PrecheckResult.Pass
                    else -> {
                        val candidates = recognizer.recognize(current, geometry.aspectRatio)
                        shapeOnly = candidates == null
                        LetterPrecheck.evaluate(character, current, candidates)
                    }
                }
                if (result == PrecheckResult.Pass) {
                    strokesChanged()
                    fly.animateTo(1f, tween(durationMillis = 250))
                    if (i == session.characters.lastIndex) {
                        fly.snapTo(0f)
                        currentOnFinished()
                    } else {
                        ghostAlpha.snapTo(0f)
                        session.index = i + 1
                        fly.snapTo(0f)
                        ghostAlpha.animateTo(1f, tween(durationMillis = 150))
                    }
                } else {
                    hint = result
                    overrideArmed = result is PrecheckResult.WrongLetter
                    shake.snapTo(0f)
                    shake.animateTo(
                        0f,
                        keyframes {
                            durationMillis = 300
                            -14f at 50
                            14f at 100
                            -9f at 160
                            9f at 220
                        },
                    )
                }
            } finally {
                checking = false
            }
        }
    }

    val expectedText = expected.toString()
    val hintText = when (val h = hint) {
        null -> null
        PrecheckResult.Pass -> null
        PrecheckResult.Empty -> stringResource(R.string.stylus_hint_empty)
        PrecheckResult.OutsideBox -> stringResource(R.string.stylus_hint_outside)
        PrecheckResult.TooSmall -> stringResource(R.string.stylus_hint_too_small)
        PrecheckResult.TooLarge -> stringResource(R.string.stylus_hint_too_large)
        PrecheckResult.ReachUp -> stringResource(R.string.stylus_hint_reach_up)
        is PrecheckResult.WrongLetter -> if (h.seen.isNullOrEmpty()) {
            stringResource(R.string.stylus_hint_unrecognized, expectedText)
        } else {
            stringResource(R.string.stylus_hint_wrong, h.seen, expectedText)
        }
    }
    val isWarning = hintText != null || errorText != null
    val messageText = when {
        isSaving -> stringResource(R.string.stylus_saving)
        hintText != null -> hintText
        errorText != null -> errorText
        shapeOnly -> stringResource(R.string.stylus_note_shape_only)
        else -> stringResource(R.string.stylus_instruction)
    }
    val canvasDescription = stringResource(R.string.cd_stylus_canvas, expectedText)
    val textMeasurer = rememberTextMeasurer()

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.stylus_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val i = session.index
                            session.glyphs[i] = session.glyphs[i].dropLast(1)
                            strokesChanged()
                        },
                        enabled = canAct && strokes.isNotEmpty(),
                        modifier = Modifier.weight(1f).testTag(HandTypeTestTags.STYLUS_UNDO),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.stylus_undo), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            session.glyphs[session.index] = emptyList()
                            strokesChanged()
                        },
                        enabled = canAct && strokes.isNotEmpty(),
                        modifier = Modifier.weight(1f).testTag(HandTypeTestTags.STYLUS_CLEAR),
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.stylus_clear), maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            livePoints.clear()
                            strokesChanged()
                            session.index = session.index - 1
                        },
                        enabled = canAct && index > 0,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .testTag(HandTypeTestTags.STYLUS_BACK),
                    ) {
                        Text(stringResource(R.string.stylus_previous), maxLines = 1)
                    }
                    Button(
                        onClick = { next() },
                        enabled = canAct,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .testTag(HandTypeTestTags.STYLUS_NEXT),
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                text = stringResource(
                                    if (index == total - 1) R.string.stylus_finish else R.string.stylus_next,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.stylus_progress, index, total),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(HandTypeTestTags.STYLUS_PROGRESS),
                )
                LazyRow(
                    state = stripState,
                    modifier = Modifier.weight(1f).height(44.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(count = index) { i ->
                        GlyphThumbnail(
                            strokes = session.glyphs[i],
                            aspectRatio = geometry.aspectRatio,
                            penFraction = InkRasterizer.PEN_WIDTH_PX / geometry.cellHeightPx,
                        )
                    }
                }
            }
            Text(
                text = messageText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .testTag(HandTypeTestTags.STYLUS_HINT),
            )
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(geometry.aspectRatio, matchHeightConstraintsFirst = true)
                        .graphicsLayer {
                            val f = fly.value
                            translationX = shake.value.dp.toPx()
                            translationY = -size.height * 0.7f * f
                            scaleX = 1f - 0.85f * f
                            scaleY = 1f - 0.85f * f
                            alpha = 1f - 0.6f * f
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        .clip(RoundedCornerShape(6.dp))
                        .testTag(HandTypeTestTags.STYLUS_CANVAS)
                        .semantics { contentDescription = canvasDescription }
                        .inkInput(
                            enabled = canAct,
                            onStart = { livePoints.clear(); livePoints.add(it) },
                            onMove = { addPoint(it) },
                            onEnd = { endStroke() },
                            onCancel = { livePoints.clear() },
                        ),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(PaperColor)
                        val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))
                        val guideWidth = 1.5.dp.toPx()
                        for (ratio in floatArrayOf(
                            PracticeSheetGenerator.GUIDE_UPPER_RATIO,
                            PracticeSheetGenerator.GUIDE_BASELINE_RATIO,
                        )) {
                            val y = size.height * ratio
                            drawLine(GuideColor, Offset(0f, y), Offset(size.width, y), guideWidth, pathEffect = dash)
                        }
                        // A typical font's x-height is about half its em, so this em puts the ghost's
                        // x-height on the guide gap and its baseline on the lower guide.
                        val gap = PracticeSheetGenerator.GUIDE_BASELINE_RATIO - PracticeSheetGenerator.GUIDE_UPPER_RATIO
                        val ghost = textMeasurer.measure(
                            text = expectedText,
                            style = TextStyle(fontSize = (size.height * gap * 2f).toSp(), color = InkColor),
                        )
                        drawText(
                            textLayoutResult = ghost,
                            topLeft = Offset(
                                (size.width - ghost.size.width) / 2f,
                                size.height * PracticeSheetGenerator.GUIDE_BASELINE_RATIO - ghost.firstBaseline,
                            ),
                            alpha = 0.14f * ghostAlpha.value,
                        )
                        val penWidth = size.height * InkRasterizer.PEN_WIDTH_PX / geometry.cellHeightPx
                        strokes.forEach { drawInk(it.points, penWidth) }
                        drawInk(livePoints, penWidth)
                        drawRect(BorderColor, style = Stroke(width = 1.dp.toPx()))
                    }
                }
            }
        }
    }
}

@Composable
private fun GlyphThumbnail(strokes: List<InkStroke>, aspectRatio: Float, penFraction: Float) {
    Canvas(
        modifier = Modifier
            .height(44.dp)
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(4.dp))
            .background(PaperColor),
    ) {
        val penWidth = (size.height * penFraction).coerceAtLeast(1f)
        strokes.forEach { drawInk(it.points, penWidth) }
    }
}

private fun DrawScope.drawInk(points: List<InkPoint>, penWidth: Float) {
    if (points.isEmpty()) return
    val first = points[0]
    if (points.size == 1) {
        drawCircle(InkColor, radius = penWidth / 2f, center = Offset(first.x * size.width, first.y * size.height))
        return
    }
    val path = Path().apply {
        moveTo(first.x * size.width, first.y * size.height)
        for (i in 1 until points.size) lineTo(points[i].x * size.width, points[i].y * size.height)
    }
    drawPath(path, InkColor, style = Stroke(width = penWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/**
 * Pen input in cell fractions. A stylus wins over touch: a stylus going down cancels a touch
 * stroke in progress, and touch pointers are ignored while the stylus is down (palm rejection).
 */
@OptIn(ExperimentalComposeUiApi::class) // historical samples keep fast stylus strokes smooth
private fun Modifier.inkInput(
    enabled: Boolean,
    onStart: (InkPoint) -> Unit,
    onMove: (InkPoint) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
    fun point(x: Float, y: Float, time: Long) =
        InkPoint(x / size.width.coerceAtLeast(1), y / size.height.coerceAtLeast(1), time)
    awaitEachGesture {
        var activeId: PointerId? = null
        var activeIsStylus = false
        do {
            val event = awaitPointerEvent()
            val stylus = event.changes.firstOrNull { it.type == PointerType.Stylus && it.pressed }
            if (stylus != null && !activeIsStylus) {
                if (activeId != null) onCancel()
                activeId = stylus.id
                activeIsStylus = true
                onStart(point(stylus.position.x, stylus.position.y, stylus.uptimeMillis))
            } else if (activeId == null) {
                val down = event.changes.firstOrNull {
                    it.pressed && !it.previousPressed &&
                        (it.type == PointerType.Touch || it.type == PointerType.Mouse)
                }
                if (down != null) {
                    activeId = down.id
                    activeIsStylus = false
                    onStart(point(down.position.x, down.position.y, down.uptimeMillis))
                }
            } else {
                val change = event.changes.firstOrNull { it.id == activeId }
                if (change == null || !change.pressed) {
                    change?.let { onMove(point(it.position.x, it.position.y, it.uptimeMillis)) }
                    onEnd()
                    activeId = null
                    activeIsStylus = false
                } else {
                    change.historical.forEach { onMove(point(it.position.x, it.position.y, it.uptimeMillis)) }
                    onMove(point(change.position.x, change.position.y, change.uptimeMillis))
                }
            }
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        if (activeId != null) onEnd()
    }
}
