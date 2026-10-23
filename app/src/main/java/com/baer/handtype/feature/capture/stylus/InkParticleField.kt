package com.baer.handtype.feature.capture.stylus

import kotlin.random.Random

/**
 * Display-only ink specks for the writing canvas. Positions are in pixels; size, speed and spawn
 * rate are tuned in dp via [pxPerDp]. Never sees [InkStroke] data. Fixed arrays: no allocation per step.
 */
class InkParticleField(
    private val random: Random,
    private val pxPerDp: Float,
    private val maxParticles: Int = MAX_PARTICLES,
) {
    private val px = FloatArray(maxParticles)
    private val py = FloatArray(maxParticles)
    private val vx = FloatArray(maxParticles)
    private val vy = FloatArray(maxParticles)
    private val sizes = FloatArray(maxParticles)
    private val age = FloatArray(maxParticles)
    private val life = FloatArray(maxParticles)
    private var spawnCarry = 0f

    var count = 0
        private set

    fun x(i: Int) = px[i]
    fun y(i: Int) = py[i]
    fun size(i: Int) = sizes[i]

    /** 1 at spawn, 0 at end of life. */
    fun alpha(i: Int) = 1f - age[i] / life[i]

    /** Spawns specks for a pen move of [distance] ending at ([x], [y]); no movement, no specks. */
    fun emit(x: Float, y: Float, distance: Float) {
        if (distance <= 0f) return
        spawnCarry += distance / pxPerDp * SPECKS_PER_DP
        while (spawnCarry >= 1f) {
            spawnCarry -= 1f
            if (count == maxParticles) {
                spawnCarry = 0f
                return
            }
            val i = count++
            val angle = random.nextFloat() * (2f * Math.PI.toFloat())
            val speed = (SPEED_MIN + random.nextFloat() * (SPEED_MAX - SPEED_MIN)) * pxPerDp
            px[i] = x + (random.nextFloat() - 0.5f) * SPREAD_DP * pxPerDp
            py[i] = y + (random.nextFloat() - 0.5f) * SPREAD_DP * pxPerDp
            vx[i] = kotlin.math.cos(angle) * speed
            vy[i] = kotlin.math.sin(angle) * speed
            sizes[i] = (SIZE_MIN + random.nextFloat() * (SIZE_MAX - SIZE_MIN)) * pxPerDp
            age[i] = 0f
            life[i] = LIFETIME_MIN_MS + random.nextFloat() * (LIFETIME_MAX_MS - LIFETIME_MIN_MS)
        }
    }

    /** Advances all specks by [dtMillis] and drops expired ones. */
    fun step(dtMillis: Long) {
        val dt = dtMillis.toFloat()
        var i = 0
        while (i < count) {
            age[i] += dt
            if (age[i] >= life[i]) {
                val last = --count
                px[i] = px[last]; py[i] = py[last]; vx[i] = vx[last]; vy[i] = vy[last]
                sizes[i] = sizes[last]; age[i] = age[last]; life[i] = life[last]
            } else {
                px[i] += vx[i] * dt
                py[i] += vy[i] * dt
                i++
            }
        }
    }

    companion object {
        const val MAX_PARTICLES = 60
        const val LIFETIME_MIN_MS = 300f
        const val LIFETIME_MAX_MS = 600f
        const val SPECKS_PER_DP = 0.15f
        private const val SPEED_MIN = 0.01f // dp per ms
        private const val SPEED_MAX = 0.04f
        private const val SPREAD_DP = 4f
        private const val SIZE_MIN = 1f
        private const val SIZE_MAX = 2.5f
    }
}
