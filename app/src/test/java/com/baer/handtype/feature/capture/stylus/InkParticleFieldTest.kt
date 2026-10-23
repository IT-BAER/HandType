package com.baer.handtype.feature.capture.stylus

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InkParticleFieldTest {

    private fun field(seed: Int = 1, max: Int = 60) = InkParticleField(Random(seed), 1f, max)

    private fun snapshot(f: InkParticleField) =
        (0 until f.count).map { listOf(f.x(it), f.y(it), f.size(it), f.alpha(it)) }

    @Test
    fun movementSpawnsParticles() {
        val f = field()
        f.emit(10f, 10f, distance = 40f)
        assertTrue(f.count > 0)
    }

    @Test
    fun restSpawnsNothing() {
        val f = field()
        repeat(100) { f.emit(10f, 10f, distance = 0f) }
        assertEquals(0, f.count)
    }

    @Test
    fun particlesDecayAndAreRemoved() {
        val f = field()
        f.emit(10f, 10f, distance = 40f)
        val before = f.count
        f.step((InkParticleField.LIFETIME_MIN_MS / 2).toLong())
        assertEquals(before, f.count)
        assertTrue((0 until f.count).all { f.alpha(it) in 0f..1f })
        f.step(InkParticleField.LIFETIME_MAX_MS.toLong())
        assertEquals(0, f.count)
    }

    @Test
    fun capIsRespected() {
        val f = field(max = 60)
        repeat(50) { f.emit(10f, 10f, distance = 40f) }
        assertEquals(60, f.count)
    }

    @Test
    fun sameSeedIsDeterministic() {
        val a = field(seed = 7)
        val b = field(seed = 7)
        for (f in listOf(a, b)) {
            f.emit(5f, 6f, 20f)
            f.step(100)
            f.emit(8f, 9f, 15f)
            f.step(50)
        }
        assertEquals(snapshot(a), snapshot(b))
        assertTrue(a.count > 0)
    }
}
