package dev.aetherx86.silica

import java.math.BigInteger
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HashLifeQueryTest {
    private fun HashLife.place(rle: String, ox: Long = 0, oy: Long = 0) {
        for ((x, y) in Rle.parse(rle)) this[x + ox, y + oy] = true
    }

    private fun big(n: Long) = BigInteger.valueOf(n)

    /** Evolves the same soup in both engines and returns them, with BitLife offset by [offset]. */
    private fun evolvedPair(seed: Long, generations: Int, offset: Int = 100): Pair<HashLife, BitLife> {
        val size = 40
        val reference = BitLife(size + 2 * offset, size + 2 * offset)
        val hash = HashLife()
        val random = Random(seed)
        for (y in 0 until size) for (x in 0 until size) if (random.nextDouble() < 0.35) {
            reference[x + offset, y + offset] = true
            hash[x - 17L, y - 23L] = true
        }
        repeat(generations) { reference.step() }
        hash.advance(generations.toLong())
        return hash to reference
    }

    @Test
    fun regionPopulationsMatchADirectCount() {
        val offset = 100
        for (seed in 1L..4L) {
            val (hash, reference) = evolvedPair(seed, 37, offset)
            val random = Random(seed * 77)
            repeat(60) {
                val x0 = random.nextInt(reference.width)
                val y0 = random.nextInt(reference.height)
                val x1 = x0 + random.nextInt(reference.width - x0)
                val y1 = y0 + random.nextInt(reference.height - y0)
                var expected = 0L
                for (y in y0..y1) for (x in x0..x1) if (reference[x, y]) expected++
                val actual = hash.populationIn(
                    x0 - offset - 17L, y0 - offset - 23L, x1 - offset - 17L, y1 - offset - 23L,
                )
                assertEquals(big(expected), actual, "seed $seed rectangle ($x0, $y0)-($x1, $y1)")
            }
            assertEquals(BigInteger.ZERO, hash.populationIn(5, 5, 4, 4), "an inverted rectangle is empty")
            assertEquals(hash.exactPopulation, hash.populationIn(Long.MIN_VALUE, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
        }
    }

    @Test
    fun boundingBoxAndCellsMatchADirectScan() {
        val offset = 100
        for (seed in 5L..8L) {
            val (hash, reference) = evolvedPair(seed, 29, offset)
            val live = buildList {
                for (y in 0 until reference.height) for (x in 0 until reference.width) {
                    if (reference[x, y]) add(x - offset - 17L to y - offset - 23L)
                }
            }
            val box = assertNotNull(hash.boundingBox())
            assertEquals(big(live.minOf { it.first }), box.minX)
            assertEquals(big(live.minOf { it.second }), box.minY)
            assertEquals(big(live.maxOf { it.first }), box.maxX)
            assertEquals(big(live.maxOf { it.second }), box.maxY)
            assertEquals(live, hash.cells(), "row-major order, like the scan")
        }
    }

    @Test
    fun anEmptyPlaneHasNoBoundsAndNoCells() {
        val hash = HashLife()
        assertNull(hash.boundingBox())
        assertEquals(emptyList(), hash.cells())
        assertEquals("x = 0, y = 0, rule = B3/S23\n!\n", hash.toRle())
        hash.place(Patterns.DIEHARD)
        hash.advance(130)
        assertNull(hash.boundingBox())
    }

    @Test
    fun exportingRespectsTheCellLimit() {
        val hash = HashLife().apply { place(Patterns.GOSPER_GUN) }
        assertEquals(36, hash.cells(36).size)
        assertFailsWith<IllegalStateException> { hash.cells(35) }
        assertFailsWith<IllegalStateException> { hash.toRle(35) }
    }

    @Test
    fun toRleRoundTripsThroughAnotherEngineAndCarriesTheRule() {
        val highLife = Rule.parse("B36/S23")
        // The HighLife replicator copies itself, so the pattern keeps growing.
        val hash = HashLife(highLife).apply { place("2b3o\$bo2bo\$o3bo\$o2bo\$3o!", -500, 300) }
        hash.advance(96)
        assertTrue(hash.population > 12)
        val text = hash.toRle()
        val pattern = Rle.read(text)
        assertEquals(highLife, pattern.rule)
        val box = assertNotNull(hash.boundingBox())
        assertEquals(box.width.toInt(), pattern.width)
        assertEquals(box.height.toInt(), pattern.height)
        val copy = HashLife(highLife)
        for ((x, y) in pattern.cells) copy[x + box.minX.toLong(), y + box.minY.toLong()] = true
        assertEquals(hash.cells(), copy.cells())
    }

    /**
     * A glider moves one cell diagonally every four generations, forever. Advancing by 4 * 2^40
     * generations through the arbitrary-precision path must move it exactly 2^40 cells.
     */
    @Test
    fun aGliderTravelsExactlyAsFarAsItsSpeedSays() {
        val hash = HashLife().apply { place(Patterns.GLIDER) }
        val start = assertNotNull(hash.boundingBox())
        val before = hash.toRle()
        val distance = BigInteger.ONE.shiftLeft(40)
        hash.advance(distance.shiftLeft(2))
        val end = assertNotNull(hash.boundingBox())
        assertEquals(start.minX.add(distance), end.minX)
        assertEquals(start.minY.add(distance), end.minY)
        assertEquals(before, hash.toRle(), "the same shape, in the same phase")
        assertEquals(distance.shiftLeft(2), hash.generation)
    }

    @Test
    fun bigIntegerAdvanceAgreesWithPowerOfTwoSteps() {
        val a = HashLife().apply { place(Patterns.GOSPER_GUN) }
        val b = HashLife().apply { place(Patterns.GOSPER_GUN) }
        a.advance(BigInteger.ONE.shiftLeft(100))
        b.advancePowerOfTwo(100)
        assertEquals(b.exactPopulation, a.exactPopulation)
        assertEquals(b.boundingBox(), a.boundingBox())

        val c = HashLife().apply { place(Patterns.GOSPER_GUN) }
        val target = BigInteger.TEN.pow(30)
        c.advance(target)
        assertEquals(target, c.generation)
        assertFailsWith<IllegalArgumentException> { c.advance(BigInteger.ONE.negate()) }
        assertFailsWith<IllegalArgumentException> { c.advance(BigInteger.ONE.shiftLeft(5000)) }
    }

    /** After 2^100 generations the glider stream is far beyond 64-bit coordinates. */
    @Test
    fun queriesStayExactBeyondSixtyFourBits() {
        val hash = HashLife().apply { place(Patterns.GOSPER_GUN) }
        hash.advancePowerOfTwo(100)
        val box = assertNotNull(hash.boundingBox())
        assertTrue(box.width.bitLength() > 64, "width ${box.width}")
        assertFailsWith<IllegalStateException> { hash.cells() }
        assertEquals(hash.exactPopulation, hash.populationIn(box.minX, box.minY, box.maxX, box.maxY))
        // The gun stays where it started. Its own box at 2^100 must match an early run in the same
        // phase of its 30-generation period.
        val phase = BigInteger.ONE.shiftLeft(100).mod(big(30)).toLong()
        val early = HashLife().apply { place(Patterns.GOSPER_GUN) }
        early.advance(300 + phase)
        assertEquals(early.populationIn(0, 0, 35, 8), hash.populationIn(0, 0, 35, 8))
    }
}
