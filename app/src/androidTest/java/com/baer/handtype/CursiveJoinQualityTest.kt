package com.baer.handtype

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.handtype.template.CursiveJoins
import com.baer.handtype.template.HandwritingBitmapRenderer
import com.baer.handtype.template.HandwritingRenderConfig
import com.baer.handtype.template.HandwritingTemplate
import com.baer.handtype.template.NoteBackgroundCatalog
import com.baer.handtype.template.TemplateDescriptor
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Joins of real captured cursive hands read as one pen stroke: the skeleton of the ink around a
 * join has no loose end (stub, gap or blob), no sharp kink, and no darker or lighter patch, and
 * no letter loses more than the tail and lead-in the join replaces.
 */
@RunWith(AndroidJUnit4::class)
class CursiveJoinQualityTest {

    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val out = InstrumentationRegistry.getInstrumentation().targetContext
        .getExternalFilesDir(null)!!.resolve("handtype_test/joins").apply { mkdirs() }

    private fun glyphs(hand: String): Map<Char, List<Bitmap>> = assets.list("joins/$hand")!!.associate { name ->
        name.substring(1, 5).toInt(16).toChar() to listOf(assets.open("joins/$hand/$name").use(BitmapFactory::decodeStream))
    }

    private class Measure(val looseEnds: Int, val connected: Boolean, val turnDeg: Float, val tone: Float) {
        override fun toString() = "looseEnds=$looseEnds connected=$connected turn=${turnDeg.roundToInt()} tone=${"%.2f".format(tone)}"
    }

    @Test
    fun joinsOfCapturedCursiveHandsReadAsOneStroke() {
        val failures = mutableListOf<String>()
        var joinCount = 0
        for (hand in listOf("hw_04", "hw_07", "bank_04")) {
            val template = HandwritingTemplate(TemplateDescriptor("user_u1", "Test", "", "", premium = false), glyphs = glyphs(hand))
            for (word in listOf("Hello", "small", "jump", "foxes")) {
                val config = HandwritingRenderConfig(maxWidthPx = 1000, targetLineHeightPx = 200, noteBackground = NoteBackgroundCatalog.transparentPreset)
                val result = HandwritingBitmapRenderer.render(word, template, config, seed = 7)
                val note = result.bitmap
                val alpha = alphaOf(note)
                val pen = CursiveJoins.penWidth(alpha, note.width)
                // A join cuts the tail beyond its exit point and the lead-in below its entry; the tail
                // leaves the exit up and to the right. Ink lost above an exit point and left of a line
                // 79 degrees steep through it, up to the previous join's entry, is the letter's body.
                val unjoined = alphaOf(HandwritingBitmapRenderer.render(word, template, config.copy(cursiveJoins = false), seed = 7).bitmap)
                val bodyLost = result.joins.indices.maxOfOrNull { k ->
                    val from = result.joins[k].first
                    val letterLeft = result.joins.getOrNull(k - 1)?.second?.first?.plus(pen) ?: 0f
                    // Unjoined, the letter before join k sits left by the shifts of the joins before it; so does the
                    // tail an earlier join erased, which can reach over it: only ink near the cut counts.
                    val moved = result.joinShifts.take(k).sum()
                    alpha.indices.count { i ->
                        val x = (i % note.width).toFloat()
                        val y = (i / note.width).toFloat()
                        val before = (i % note.width - moved).takeIf { it in 0 until note.width }?.let { unjoined[i - moved] } ?: 0
                        before > INK && alpha[i] <= INK && x >= letterLeft && y < from.second - pen &&
                            hypot(x - from.first, y - from.second) <= pen * 8 &&
                            x - from.first < (from.second - y) * 0.2f
                    }
                } ?: 0
                Log.i(TAG, "$hand $word letter body lost: ${"%.1f".format(bodyLost / (pen * pen))} pen areas")
                if (bodyLost > pen * pen * MAX_BODY_LOST_PEN_AREAS) failures += "$hand $word: a join erased $bodyLost px of a letter's body"
                save("${hand}_${word}_lost", onPaper(note).also { b ->
                    alpha.indices.filter { unjoined[it] > INK && alpha[it] <= INK }.forEach { b.setPixel(it % note.width, it / note.width, 0xFFFF0000.toInt()) }
                })
                save("${hand}_${word}_clean", onPaper(note))
                val debug = onPaper(note)
                result.joins.forEachIndexed { i, (from, to) ->
                    joinCount++
                    val m = measure(alpha, note.width, note.height, from, to, pen, debug)
                    Log.i(TAG, "$hand $word join $i pen=${"%.1f".format(pen)} $m from=${from.first.roundToInt()},${from.second.roundToInt()} to=${to.first.roundToInt()},${to.second.roundToInt()}")
                    if (m.looseEnds > 0 || !m.connected || m.turnDeg > MAX_TURN_DEG || m.tone > MAX_TONE) failures += "$hand $word join $i: $m"
                }
                save("${hand}_$word", debug)
                result.joins.forEachIndexed { i, (from, to) ->
                    val r = (pen * 6).roundToInt()
                    val cx = ((from.first + to.first) / 2).roundToInt()
                    val cy = ((from.second + to.second) / 2).roundToInt()
                    val left = (cx - r).coerceIn(0, note.width - 2 * r)
                    val top = (cy - r).coerceIn(0, note.height - 2 * r)
                    val crop = Bitmap.createBitmap(debug, left, top, 2 * r, 2 * r)
                    save("${hand}_${word}_j$i", Bitmap.createScaledBitmap(crop, 8 * r, 8 * r, false))
                }
            }
        }
        assertTrue("no joins rendered", joinCount > 0)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun measure(
        alpha: IntArray, width: Int, height: Int,
        from: Pair<Float, Float>, to: Pair<Float, Float>, pen: Float, debug: Bitmap,
    ): Measure {
        // A stroke cut off by a window corner thins away entirely; the margin keeps the skeleton near the join whole.
        val pad = (pen * 5).roundToInt()
        val x0 = (minOf(from.first, to.first).toInt() - pad).coerceAtLeast(0)
        val y0 = (minOf(from.second, to.second).toInt() - pad).coerceAtLeast(0)
        val x1 = (maxOf(from.first, to.first).toInt() + pad).coerceAtMost(width - 1)
        val y1 = (maxOf(from.second, to.second).toInt() + pad).coerceAtMost(height - 1)
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val skeleton = BooleanArray(w * h) { alpha[(y0 + it / w) * width + x0 + it % w] > INK }
        thin(skeleton, w, h)
        fun on(x: Int, y: Int) = x in 0 until w && y in 0 until h && skeleton[y * w + x]
        fun neighbours(i: Int): List<Int> = buildList {
            for (dy in -1..1) for (dx in -1..1) if ((dx != 0 || dy != 0) && on(i % w + dx, i / w + dy)) add(i + dy * w + dx)
        }
        val ring = listOf(-1 to -1, 0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0)
        fun branches(i: Int): Int = ring.indices.count { k ->
            val (ax, ay) = ring[k]
            val (bx, by) = ring[(k + 1) % 8]
            !on(i % w + ax, i / w + ay) && on(i % w + bx, i / w + by)
        }
        val reach = pen.roundToInt().coerceAtLeast(2)

        // The stroke from the exit to the entry, extended along the skeleton on both sides.
        fun nearest(p: Pair<Float, Float>): Int? = skeleton.indices.filter { skeleton[it] }
            .minByOrNull { hypot(x0 + it % w - p.first, y0 + it / w - p.second) }
            ?.takeIf { hypot(x0 + it % w - p.first, y0 + it / w - p.second) <= pen * 2 }
        val start = nearest(from)
        val end = nearest(to)
        val path = if (start == null || end == null) null else shortestPath(start, end, ::neighbours)
        val leading = path?.let { extend(it.reversed(), reach * 2, w, ::neighbours).reversed() }.orEmpty()
        val full = path?.let { extend(leading, reach * 2, w, ::neighbours) }.orEmpty()
        val joinPart = (leading.size - path.orEmpty().size) until leading.size
        full.forEach { debug.setPixel(x0 + it % w, y0 + it / w, 0xFF2060FF.toInt()) }
        val onStroke = full.toHashSet()
        fun nearStroke(i: Int, distance: Float) = full.any { hypot((it % w - i % w).toFloat(), (it / w - i / w).toFloat()) <= distance }

        // A loose end of the join: a stub or overshoot hanging off the stroke, or a speck or gap end
        // beside it. Spurs shorter than a pen width are thinning noise; ends at the window edge are cuts.
        var looseEnds = 0
        for (i in skeleton.indices) {
            if (!skeleton[i] || branches(i) != 1) continue
            // A stroke cut slantwise by the window edge thins back up to a pen width or more from it.
            if (minOf(i % w, i / w, w - 1 - i % w, h - 1 - i / w) < reach * 2) continue
            val seen = hashSetOf(i)
            var current = i
            var steps = 0
            var spur = false
            var attached = i in onStroke
            while (steps < reach * 3 && !attached) {
                val next = neighbours(current).filter { it !in seen }
                if (next.isEmpty()) {
                    attached = nearStroke(i, pen * 2)
                    break
                }
                current = next.first()
                seen += current
                steps++
                if (current in onStroke || neighbours(current).any { it in onStroke }) attached = true
                if (branches(current) >= 3 && steps < reach) { spur = true; break }
            }
            val nearJoin = joinPart.any { k -> hypot((full[k] % w - i % w).toFloat(), (full[k] / w - i / w).toFloat()) <= pen * 2 }
            if (!spur && attached && nearJoin) {
                looseEnds++
                for (dy in -1..1) for (dx in -1..1) debug.setPixel((x0 + i % w + dx).coerceIn(0, width - 1), (y0 + i / w + dy).coerceIn(0, height - 1), 0xFFFF0000.toInt())
            }
        }
        if (path == null) return Measure(looseEnds, connected = false, turnDeg = 180f, tone = 0f)

        fun turnAt(k: Int): Float {
            val (ax, ay) = (full[k] % w - full[k - reach] % w).toFloat() to (full[k] / w - full[k - reach] / w).toFloat()
            val (bx, by) = (full[k + reach] % w - full[k] % w).toFloat() to (full[k + reach] / w - full[k] / w).toFloat()
            val cos = ((ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cos).toDouble()).toFloat()
        }
        // Turns of the letters' own curves next to the join are not the join's: only windows reaching into it count.
        val windows = reach until full.size - reach
        val turn = windows.filter { it in joinPart.first - reach + 1 until joinPart.last + reach }.maxOfOrNull(::turnAt) ?: 0f
        val ownTurn = windows.filter { it !in joinPart.first - reach + 1 until joinPart.last + reach }.maxOfOrNull(::turnAt) ?: 0f
        Log.i(TAG, "own stroke turn next to the join: ${ownTurn.roundToInt()}")
        // Stroke darkness at each point (the darkest pixel around it): the join against the strokes on either side.
        val dark = full.map { i ->
            (-1..1).maxOf { dy -> (-1..1).maxOf { dx -> alpha[(y0 + i / w + dy).coerceIn(0, height - 1) * width + (x0 + i % w + dx).coerceIn(0, width - 1)] } }
        }
        fun median(values: List<Int>) = values.sorted()[values.size / 2].toFloat().coerceAtLeast(1f)
        val join = median(joinPart.map { dark[it] })
        val sides = dark.indices.filter { it !in joinPart }.map { dark[it] }.let { if (it.isEmpty()) join else median(it) }
        return Measure(looseEnds, connected = true, turnDeg = turn, tone = maxOf(join / sides, sides / join))
    }

    private fun shortestPath(start: Int, end: Int, neighbours: (Int) -> List<Int>): List<Int>? {
        val parent = hashMapOf(start to start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (i == end) return generateSequence(end) { p -> parent[p]!!.takeIf { it != p } }.toList().reversed()
            neighbours(i).filter { it !in parent }.forEach { parent[it] = i; queue.add(it) }
        }
        return null
    }

    /** [path] continued from its last point for up to [steps] skeleton pixels, straightest neighbour first. */
    private fun extend(path: List<Int>, steps: Int, w: Int, neighbours: (Int) -> List<Int>): List<Int> {
        val result = path.toMutableList()
        val seen = path.toHashSet()
        repeat(steps) {
            val current = result.last()
            val back = result[(result.size - 4).coerceAtLeast(0)]
            val dx = current % w - back % w
            val dy = current / w - back / w
            val next = neighbours(current).filter { it !in seen }
                .maxByOrNull { n -> (n % w - current % w) * dx + (n / w - current / w) * dy } ?: return result
            result += next
            seen += next
        }
        return result
    }

    /** Zhang-Suen thinning in place. */
    private fun thin(img: BooleanArray, w: Int, h: Int) {
        fun p(x: Int, y: Int) = x in 0 until w && y in 0 until h && img[y * w + x]
        var changed = true
        while (changed) {
            changed = false
            for (pass in 0..1) {
                val remove = mutableListOf<Int>()
                for (y in 0 until h) for (x in 0 until w) {
                    if (!img[y * w + x]) continue
                    val n = listOf(p(x, y - 1), p(x + 1, y - 1), p(x + 1, y), p(x + 1, y + 1), p(x, y + 1), p(x - 1, y + 1), p(x - 1, y), p(x - 1, y - 1))
                    val b = n.count { it }
                    val a = (0 until 8).count { !n[it] && n[(it + 1) % 8] }
                    if (b !in 2..6 || a != 1) continue
                    val ok = if (pass == 0) !(n[0] && n[2] && n[4]) && !(n[2] && n[4] && n[6]) else !(n[0] && n[2] && n[6]) && !(n[0] && n[4] && n[6])
                    if (ok) remove += y * w + x
                }
                remove.forEach { img[it] = false }
                if (remove.isNotEmpty()) changed = true
            }
        }
    }

    private fun alphaOf(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height)
        .also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        .map { it ushr 24 }.toIntArray()

    private fun onPaper(note: Bitmap): Bitmap = Bitmap.createBitmap(note.width, note.height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply { drawColor(0xFFF8F1E4.toInt()); drawBitmap(note, 0f, 0f, null) }
    }

    private fun save(name: String, bitmap: Bitmap) {
        out.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val TAG = "CursiveJoinQuality"
        const val INK = 100
        // The hands' own strokes next to a join turn up to 39 degrees at this scale.
        const val MAX_TURN_DEG = 40f
        const val MAX_TONE = 1.3f
        // Measured: an erased s bowl 3.3 and 5.1 pen areas, cut tails and lead-ins at most 0.3.
        const val MAX_BODY_LOST_PEN_AREAS = 1.5f
    }
}
