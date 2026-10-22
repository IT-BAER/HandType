package com.baer.handtype.feature.capture.stylus

/** One pen sample. [x] and [y] are fractions of the writing cell (0..1 inside the cell). */
data class InkPoint(val x: Float, val y: Float, val timeMillis: Long)

data class InkStroke(val points: List<InkPoint>)

/**
 * Flat [FloatArray] form of one glyph's strokes for saved instance state:
 * `[strokeCount, (pointCount, x, y, t) ...]`, with `t` relative to the glyph's first point.
 */
object InkStrokeCodec {

    fun encode(strokes: List<InkStroke>): FloatArray {
        val base = strokes.firstNotNullOfOrNull { it.points.firstOrNull() }?.timeMillis ?: 0L
        val out = ArrayList<Float>(1 + strokes.sumOf { 1 + it.points.size * 3 })
        out += strokes.size.toFloat()
        for (stroke in strokes) {
            out += stroke.points.size.toFloat()
            for (p in stroke.points) {
                out += p.x
                out += p.y
                out += (p.timeMillis - base).toFloat()
            }
        }
        return out.toFloatArray()
    }

    /** Returns an empty list for malformed data rather than a partial glyph. */
    fun decode(data: FloatArray): List<InkStroke> {
        if (data.isEmpty()) return emptyList()
        var i = 0
        val strokeCount = data[i++].toInt()
        if (strokeCount < 0) return emptyList()
        val strokes = ArrayList<InkStroke>(strokeCount)
        repeat(strokeCount) {
            if (i >= data.size) return emptyList()
            val pointCount = data[i++].toInt()
            if (pointCount < 0 || i + pointCount * 3 > data.size) return emptyList()
            val points = ArrayList<InkPoint>(pointCount)
            repeat(pointCount) {
                points += InkPoint(data[i], data[i + 1], data[i + 2].toLong())
                i += 3
            }
            strokes += InkStroke(points)
        }
        return strokes
    }
}
