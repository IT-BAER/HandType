package com.baer.handtype.feature.capture.stylus

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.provider.Settings
import androidx.annotation.StringRes
import com.baer.handtype.R
import com.baer.handtype.feature.capture.PracticeSheetGenerator
import com.baer.handtype.testing.HandTypeTestTags
import kotlin.random.Random
import kotlinx.coroutines.launch

/** Strokes written for every sheet character plus the first character of the current page; accepted = before [index]. */
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

private const val PARTICLE_PEAK_ALPHA = 0.7f

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
    // Cells per page; set from the measured grid (3 x 3, or 3 x 2 on short screens).
    var pageSize by remember { mutableIntStateOf(StylusPages.COLUMNS * StylusPages.MAX_ROWS) }
    val pageEnd = StylusPages.pageEnd(index, pageSize, total)
    val livePoints = remember { mutableStateListOf<InkPoint>() }
    var liveCell by remember { mutableIntStateOf(-1) }
    // Absolute cell index of every stroke in writing order; lost after process death.
    val strokeOrder = remember { mutableListOf<Int>() }
    var hint by remember { mutableStateOf<Pair<Int, PrecheckResult>?>(null) }
    var failed by remember { mutableStateOf(emptySet<Int>()) }
    // Cells that passed unchanged, and cells whose recognition mismatch the next check keeps.
    val verified = remember { mutableSetOf<Int>() }
    val armed = remember { mutableSetOf<Int>() }
    var shapeOnly by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val fly = remember { Animatable(0f) }
    val ghostAlpha = remember { Animatable(1f) }
    val shake = remember { Animatable(0f) }
    val stripState = rememberLazyListState()
    val canAct = !checking && !isSaving
    // Cell width, cell height and gap in px, written by the grid layout and read by touch input.
    val cellPx = remember { IntArray(3) }

    // Display-only specks; they never touch livePoints or InkStroke data.
    val context = LocalContext.current
    val particlesEnabled = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val density = LocalDensity.current.density
    val particles = remember(density) { InkParticleField(Random(System.nanoTime()), density) }
    var particlesActive by remember { mutableStateOf(false) }
    var particleFrame by remember { mutableIntStateOf(0) }
    val particleColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error

    LaunchedEffect(particlesActive) {
        if (particlesActive) {
            var lastFrame = 0L
            while (particles.count > 0) {
                withFrameMillis { now ->
                    if (lastFrame != 0L) particles.step((now - lastFrame).coerceAtMost(50L))
                    lastFrame = now
                    particleFrame++
                }
            }
            particlesActive = false
        }
    }

    LaunchedEffect(recognizer) { recognizer.prepare() }
    LaunchedEffect(index) { if (index > 0) stripState.animateScrollToItem(index - 1) }

    fun cellChanged(i: Int) {
        hint = null
        failed = failed - i
        verified -= i
        armed -= i
    }

    /** Absolute index of the page cell under grid position ([x], [y]) in px, or null for a gap. */
    fun cellAt(x: Float, y: Float): Int? {
        val w = cellPx[0]
        val h = cellPx[1]
        val gap = cellPx[2]
        if (w <= 0 || h <= 0 || x < 0f || y < 0f) return null
        val column = (x / (w + gap)).toInt()
        val row = (y / (h + gap)).toInt()
        if (column >= StylusPages.COLUMNS || row >= pageSize / StylusPages.COLUMNS) return null
        if (x - column * (w + gap) > w || y - row * (h + gap) > h) return null
        val i = session.index + StylusPages.slot(row, column)
        return if (i < StylusPages.pageEnd(session.index, pageSize, session.characters.size)) i else null
    }

    /** Grid px to fractions of cell [i]; values outside 0..1 mean the pen left the cell. */
    fun toCell(i: Int, x: Float, y: Float, time: Long): InkPoint {
        val slot = i - session.index
        val w = cellPx[0].coerceAtLeast(1)
        val h = cellPx[1].coerceAtLeast(1)
        val gap = cellPx[2]
        return InkPoint(
            (x - StylusPages.column(slot) * (w + gap)) / w,
            (y - StylusPages.row(slot) * (h + gap)) / h,
            time,
        )
    }

    fun startStroke(x: Float, y: Float, time: Long) {
        livePoints.clear()
        val cell = cellAt(x, y)
        liveCell = cell ?: -1
        if (cell != null) livePoints.add(toCell(cell, x, y, time))
    }

    fun addPoint(x: Float, y: Float, time: Long) {
        val cell = liveCell
        if (cell < 0) return
        val point = toCell(cell, x, y, time)
        val last = livePoints.lastOrNull()
        if (last != null) {
            val dx = (point.x - last.x) * geometry.aspectRatio
            val dy = point.y - last.y
            if (dx * dx + dy * dy < MIN_POINT_STEP * MIN_POINT_STEP) return
            if (particlesEnabled) {
                val pxX = (point.x - last.x) * cellPx[0]
                val pxY = (point.y - last.y) * cellPx[1]
                particles.emit(x, y, kotlin.math.sqrt(pxX * pxX + pxY * pxY))
                if (particles.count > 0) particlesActive = true
            }
        }
        livePoints.add(point)
    }

    fun cancelStroke() {
        livePoints.clear()
        liveCell = -1
    }

    fun endStroke() {
        val i = liveCell
        if (i >= 0 && livePoints.isNotEmpty()) {
            session.glyphs[i] = session.glyphs[i] + InkStroke(livePoints.toList())
            strokeOrder += i
            cellChanged(i)
        }
        cancelStroke()
    }

    fun lastWrittenCell(): Int? = StylusPages.lastWritten(
        strokeOrder,
        session.index,
        StylusPages.pageEnd(session.index, pageSize, session.characters.size),
    ) { session.glyphs[it].size }

    fun next() {
        if (!canAct) return
        checking = true
        scope.launch {
            try {
                val start = session.index
                val end = StylusPages.pageEnd(start, pageSize, session.characters.size)
                val check = StylusPages.checkPage(
                    characters = session.characters,
                    glyphs = session.glyphs.toList(),
                    start = start,
                    end = end,
                    verified = verified.toSet(),
                    armed = armed.toSet(),
                ) { strokes -> recognizer.recognize(strokes, geometry.aspectRatio) }
                check.recognizerUnavailable?.let { shapeOnly = it }
                val review = StylusPages.review(check.results)
                verified += review.passed
                armed -= check.results.keys
                armed += review.armed
                if (review.failing.isEmpty()) {
                    hint = null
                    failed = emptySet()
                    fly.animateTo(1f, tween(durationMillis = 250))
                    if (end >= session.characters.size) {
                        fly.snapTo(0f)
                        currentOnFinished()
                    } else {
                        ghostAlpha.snapTo(0f)
                        session.index = end
                        fly.snapTo(0f)
                        ghostAlpha.animateTo(1f, tween(durationMillis = 150))
                    }
                } else {
                    val first = review.failing.first()
                    hint = first to check.results.getValue(first)
                    failed = review.failing.toSet()
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

    val hintText = hint?.let { (cell, result) ->
        val letter = session.characters[cell].toString()
        when (result) {
            PrecheckResult.Pass -> null
            PrecheckResult.Empty -> cellHint(letter, R.string.stylus_hint_empty)
            PrecheckResult.OutsideBox -> cellHint(letter, R.string.stylus_hint_outside)
            PrecheckResult.TooSmall -> cellHint(letter, R.string.stylus_hint_too_small)
            PrecheckResult.TooLarge -> cellHint(letter, R.string.stylus_hint_too_large)
            PrecheckResult.ReachUp -> cellHint(letter, R.string.stylus_hint_reach_up)
            is PrecheckResult.WrongLetter -> if (result.seen.isNullOrEmpty()) {
                stringResource(R.string.stylus_hint_unrecognized, letter)
            } else {
                stringResource(R.string.stylus_hint_wrong, result.seen, letter)
            }
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
    val pageHasInk = (index until pageEnd).any { session.glyphs[it].isNotEmpty() }
    val textMeasurer = rememberTextMeasurer()
    val penFraction = InkRasterizer.PEN_WIDTH_PX / geometry.cellHeightPx

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
                            val cell = lastWrittenCell() ?: return@OutlinedButton
                            session.glyphs[cell] = session.glyphs[cell].dropLast(1)
                            val k = strokeOrder.lastIndexOf(cell)
                            if (k >= 0) strokeOrder.removeAt(k)
                            cellChanged(cell)
                        },
                        enabled = canAct && pageHasInk,
                        modifier = Modifier.weight(1f).testTag(HandTypeTestTags.STYLUS_UNDO),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.stylus_undo), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            val cell = lastWrittenCell() ?: return@OutlinedButton
                            session.glyphs[cell] = emptyList()
                            strokeOrder.removeAll { it == cell }
                            cellChanged(cell)
                        },
                        enabled = canAct && pageHasInk,
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
                            cancelStroke()
                            hint = null
                            failed = emptySet()
                            session.index = StylusPages.previousStart(session.index, pageSize)
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
                                    if (StylusPages.isLastPage(index, pageSize, total)) {
                                        R.string.stylus_finish
                                    } else {
                                        R.string.stylus_next
                                    },
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
                            penFraction = penFraction,
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
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val grid = StylusPages.grid(maxWidth.value, maxHeight.value, geometry.aspectRatio)
                SideEffect { pageSize = grid.pageSize }
                val cellHeight = grid.cellHeightDp.dp
                val cellWidth = cellHeight * geometry.aspectRatio
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            val f = fly.value
                            translationY = -size.height * 0.7f * f
                            scaleX = 1f - 0.85f * f
                            scaleY = 1f - 0.85f * f
                            alpha = 1f - 0.6f * f
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        .testTag(HandTypeTestTags.STYLUS_CANVAS)
                        .inkInput(
                            enabled = canAct,
                            onStart = { x, y, time -> startStroke(x, y, time) },
                            onMove = { x, y, time -> addPoint(x, y, time) },
                            onEnd = { endStroke() },
                            onCancel = { cancelStroke() },
                        ),
                ) {
                    CellGrid(
                        cellWidth = cellWidth,
                        cellHeight = cellHeight,
                        rows = grid.rows,
                        cellPx = cellPx,
                    ) {
                        for (i in index until StylusPages.pageEnd(index, grid.pageSize, total)) {
                            val isFailed = i in failed
                            StylusCell(
                                index = i,
                                character = session.characters[i],
                                strokes = session.glyphs[i],
                                livePoints = if (liveCell == i) livePoints else null,
                                failed = isFailed,
                                borderColor = if (isFailed) errorColor else BorderColor,
                                shake = { if (isFailed) shake.value else 0f },
                                ghostAlpha = { ghostAlpha.value },
                                penFraction = penFraction,
                                textMeasurer = textMeasurer,
                            )
                        }
                    }
                    Canvas(modifier = Modifier.matchParentSize()) {
                        if (particleFrame >= 0) {
                            for (i in 0 until particles.count) {
                                drawCircle(
                                    particleColor,
                                    radius = particles.size(i),
                                    center = Offset(particles.x(i), particles.y(i)),
                                    alpha = particles.alpha(i) * PARTICLE_PEAK_ALPHA,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun cellHint(letter: String, @StringRes hint: Int): String =
    stringResource(R.string.stylus_hint_cell, letter, stringResource(hint))

/** Places page cells left to right, top to bottom; the px sizes it uses go to [cellPx] for touch mapping. */
@Composable
private fun CellGrid(
    cellWidth: Dp,
    cellHeight: Dp,
    rows: Int,
    cellPx: IntArray,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, _ ->
        val w = cellWidth.toPx().toInt()
        val h = cellHeight.toPx().toInt()
        val gap = StylusPages.GAP_DP.dp.roundToPx()
        cellPx[0] = w
        cellPx[1] = h
        cellPx[2] = gap
        val placeables = measurables.map { it.measure(Constraints.fixed(w, h)) }
        val columns = StylusPages.COLUMNS
        layout(w * columns + gap * (columns - 1), h * rows + gap * (rows - 1)) {
            placeables.forEachIndexed { slot, placeable ->
                placeable.place(StylusPages.column(slot) * (w + gap), StylusPages.row(slot) * (h + gap))
            }
        }
    }
}

@Composable
private fun StylusCell(
    index: Int,
    character: Char,
    strokes: List<InkStroke>,
    livePoints: List<InkPoint>?,
    failed: Boolean,
    borderColor: Color,
    shake: () -> Float,
    ghostAlpha: () -> Float,
    penFraction: Float,
    textMeasurer: TextMeasurer,
) {
    val text = character.toString()
    val description = stringResource(R.string.cd_stylus_canvas, text)
    Canvas(
        modifier = Modifier
            .graphicsLayer { translationX = shake().dp.toPx() }
            .clip(RoundedCornerShape(6.dp))
            .testTag(HandTypeTestTags.stylusCell(index))
            .semantics { contentDescription = description },
    ) {
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
            text = text,
            style = TextStyle(fontSize = (size.height * gap * 2f).toSp(), color = InkColor),
        )
        drawText(
            textLayoutResult = ghost,
            topLeft = Offset(
                (size.width - ghost.size.width) / 2f,
                size.height * PracticeSheetGenerator.GUIDE_BASELINE_RATIO - ghost.firstBaseline,
            ),
            alpha = 0.14f * ghostAlpha(),
        )
        val penWidth = size.height * penFraction
        strokes.forEach { drawInk(it.points, penWidth) }
        livePoints?.let { drawInk(it, penWidth) }
        drawRect(borderColor, style = Stroke(width = (if (failed) 2.dp else 1.dp).toPx()))
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
 * Pen input in px of the grid. A stylus wins over touch: a stylus going down cancels a touch
 * stroke in progress, and touch pointers are ignored while the stylus is down (palm rejection).
 * One input for the whole grid, so a palm resting on another cell is rejected too.
 */
@OptIn(ExperimentalComposeUiApi::class) // historical samples keep fast stylus strokes smooth
private fun Modifier.inkInput(
    enabled: Boolean,
    onStart: (x: Float, y: Float, time: Long) -> Unit,
    onMove: (x: Float, y: Float, time: Long) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier = pointerInput(enabled) {
    if (!enabled) return@pointerInput
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
                onStart(stylus.position.x, stylus.position.y, stylus.uptimeMillis)
            } else if (activeId == null) {
                val down = event.changes.firstOrNull {
                    it.pressed && !it.previousPressed &&
                        (it.type == PointerType.Touch || it.type == PointerType.Mouse)
                }
                if (down != null) {
                    activeId = down.id
                    activeIsStylus = false
                    onStart(down.position.x, down.position.y, down.uptimeMillis)
                }
            } else {
                val change = event.changes.firstOrNull { it.id == activeId }
                if (change == null || !change.pressed) {
                    change?.let { onMove(it.position.x, it.position.y, it.uptimeMillis) }
                    onEnd()
                    activeId = null
                    activeIsStylus = false
                } else {
                    change.historical.forEach { onMove(it.position.x, it.position.y, it.uptimeMillis) }
                    onMove(change.position.x, change.position.y, change.uptimeMillis)
                }
            }
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        if (activeId != null) onEnd()
    }
}
