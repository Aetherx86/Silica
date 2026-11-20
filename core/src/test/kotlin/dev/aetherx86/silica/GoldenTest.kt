package dev.aetherx86.silica

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Published facts about well-known patterns, checked on the unbounded engine. */
class GoldenTest {
    private fun hashOf(rle: String) = HashLife().apply {
        for ((x, y) in Rle.parse(rle)) this[x.toLong(), y.toLong()] = true
    }

    /** The acorn settles at generation 5206 with 633 cells, counting its 13 escaping gliders. */
    @Test
    fun theAcornSettlesAtItsPublishedGenerationAndPopulation() {
        val hash = hashOf(Patterns.ACORN)
        assertEquals(7L, hash.population)
        hash.advance(5206)
        assertEquals(633L, hash.population)
        // Once settled, only blinkers oscillate and gliders fly away, so the count holds.
        val settled = hash.cells().toSet()
        hash.advance(2)
        assertEquals(633L, hash.population)
        hash.advance(998)
        assertEquals(633L, hash.population)
        assertNotEquals(settled, hash.cells().toSet(), "gliders keep moving")
    }

    @Test
    fun theAcornIsStillChangingJustBeforeIt() {
        val before = hashOf(Patterns.ACORN).apply { advance(5000) }
        val after = hashOf(Patterns.ACORN).apply { advance(5206) }
        assertNotEquals(before.population, after.population)
    }

    /** Diehard vanishes completely after 130 generations, and not one generation earlier. */
    @Test
    fun diehardDiesAtGeneration130() {
        val hash = hashOf(Patterns.DIEHARD)
        hash.advance(129)
        assertTrue(hash.population > 0)
        hash.advance(1)
        assertEquals(0L, hash.population)
        assertEquals(BigInteger.valueOf(130), hash.generation)
    }

    /** The Gosper gun repeats every 30 generations, so its own box looks the same each period. */
    @Test
    fun theGosperGunRegionRepeatsEveryThirtyGenerations() {
        val counts = listOf(10L, 11L, 1000L, 1L shl 30).map { periods ->
            hashOf(Patterns.GOSPER_GUN).apply { advance(30L * periods) }.populationIn(0, 0, 35, 8)
        }
        assertEquals(1, counts.toSet().size, "counts $counts")
        val cells = listOf(10L, 1L shl 10).map { periods ->
            hashOf(Patterns.GOSPER_GUN).apply { advance(30L * periods) }.cells()
                .filter { (x, y) -> x in 0..35 && y in 0..8 }
        }
        assertEquals(cells[0], cells[1])
    }

    /** The same pattern advanced through different step sequences ends in the same place. */
    @Test
    fun hashLifeIsDeterministicAcrossStepSchedules() {
        val target = 4096L + 333L
        val single = hashOf(Patterns.R_PENTOMINO).apply { advance(target) }
        val ones = hashOf(Patterns.R_PENTOMINO).apply { repeat(333) { advance(1) }; advance(4096) }
        val compacted = hashOf(Patterns.R_PENTOMINO).apply {
            advance(1000)
            compact()
            advance(target - 1000)
        }
        assertEquals(single.cells(), ones.cells())
        assertEquals(single.cells(), compacted.cells())
    }
}
