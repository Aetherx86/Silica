package dev.aetherx86.silica

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifeTest {
    private fun LifeGrid.place(rle: String, ox: Int = 0, oy: Int = 0) {
        for ((x, y) in Rle.parse(rle)) this[x + ox, y + oy] = true
    }

    private fun BitLife.place(rle: String, ox: Int = 0, oy: Int = 0) {
        for ((x, y) in Rle.parse(rle)) this[x + ox, y + oy] = true
    }

    private fun HashLife.place(rle: String, ox: Long = 0, oy: Long = 0) {
        for ((x, y) in Rle.parse(rle)) this[x + ox, y + oy] = true
    }

    private fun cells(grid: LifeGrid): Set<Pair<Int, Int>> = buildSet {
        for (y in 0 until grid.height) for (x in 0 until grid.width) if (grid[x, y]) add(x to y)
    }

    @Test
    fun underpopulationSurvivalOverpopulationAndReproductionFollowTheRules() {
        // A lone cell dies of underpopulation.
        LifeGrid(5, 5).apply {
            this[2, 2] = true
            step()
            assertEquals(0, population())
        }
        // A dead cell with exactly three neighbors is born, and each stays alive with two.
        LifeGrid(5, 5).apply {
            this[1, 1] = true
            this[2, 1] = true
            this[1, 2] = true
            step()
            assertTrue(this[2, 2], "reproduction")
            assertEquals(4, population())
        }
        // A center cell with four neighbors dies of overpopulation.
        LifeGrid(5, 5).apply {
            for ((x, y) in listOf(2 to 2, 1 to 2, 3 to 2, 2 to 1, 2 to 3)) this[x, y] = true
            step()
            assertFalse(this[2, 2], "overpopulation")
        }
    }

    @Test
    fun aStillLifeNeverChanges() {
        val grid = LifeGrid(8, 8).apply { place(Patterns.BLOCK, 3, 3) }
        val before = cells(grid)
        repeat(20) { grid.step() }
        assertEquals(before, cells(grid))
    }

    @Test
    fun aBlinkerHasPeriodTwo() {
        val grid = LifeGrid(7, 7).apply { place(Patterns.BLINKER, 2, 3) }
        val start = cells(grid)
        grid.step()
        assertEquals(setOf(3 to 2, 3 to 3, 3 to 4), cells(grid), "vertical phase")
        grid.step()
        assertEquals(start, cells(grid))
    }

    @Test
    fun aGliderTranslatesDiagonallyEveryFourGenerations() {
        val grid = LifeGrid(32, 32).apply { place(Patterns.GLIDER, 2, 2) }
        val start = cells(grid)
        repeat(4) { grid.step() }
        assertEquals(start.map { (x, y) -> x + 1 to y + 1 }.toSet(), cells(grid))
    }

    @Test
    fun aGliderWrapsOnATorusAndDiesAtADeadEdge() {
        val torus = LifeGrid(16, 16, Edge.TORUS).apply { place(Patterns.GLIDER, 2, 2) }
        val start = cells(torus)
        repeat(4 * 16) { torus.step() }
        assertEquals(start, cells(torus), "a full lap returns to the start")

        val walled = LifeGrid(16, 16, Edge.DEAD).apply { place(Patterns.GLIDER, 2, 2) }
        repeat(4 * 16) { walled.step() }
        assertTrue(walled.population() != 5 || cells(walled) != start, "no wrap at a dead edge")
    }

    @Test
    fun aPulsarHasPeriodThreeWithItsKnownPopulations() {
        val grid = LifeGrid(32, 32).apply { place(Patterns.PULSAR, 9, 9) }
        val seen = (0 until 6).map {
            val population = grid.population()
            grid.step()
            population
        }
        assertEquals(listOf(48, 56, 72, 48, 56, 72), seen)
    }

    /** A published fact about Life: the R-pentomino settles at generation 1103 with 116 cells. */
    @Test
    fun theRPentominoStabilizesAtItsKnownGenerationAndPopulation() {
        val size = 1024
        val grid = BitLife(size, size).apply { place(Patterns.R_PENTOMINO, size / 2, size / 2) }
        repeat(1103) { grid.step() }
        assertEquals(116L, grid.population())

        val hash = HashLife().apply { place(Patterns.R_PENTOMINO) }
        hash.advance(1103)
        assertEquals(116L, hash.population)
    }

    @Test
    fun rulesParseAndHighLifeDiffersFromLife() {
        assertEquals(Rule.CONWAY, Rule.parse("B3/S23"))
        assertEquals(Rule(1 shl 3 or (1 shl 6), 1 shl 2 or (1 shl 3)), Rule.parse("b36/s23"))
        assertEquals(Rule(0, 0), Rule.parse("B/S"))
        for (bad in listOf("", "B3", "B9/S23", "S23/B3", "B3/S2x")) {
            assertFailsWith<IllegalArgumentException>(bad) { Rule.parse(bad) }
        }
        // Six neighbors give a birth under HighLife, and nothing under Life.
        val make = { rule: Rule ->
            LifeGrid(7, 7, rule = rule).apply {
                for ((x, y) in listOf(1 to 1, 2 to 1, 3 to 1, 1 to 3, 2 to 3, 3 to 3)) this[x, y] = true
                step()
            }
        }
        assertFalse(make(Rule.CONWAY)[2, 2])
        assertTrue(make(Rule.parse("B36/S23"))[2, 2])
    }

    @Test
    fun rleHandlesRunsBlankLinesAndComments() {
        assertEquals(
            listOf(1 to 0, 2 to 1, 0 to 2, 1 to 2, 2 to 2),
            Rle.parse("#C a comment\nx = 3, y = 3, rule = B3/S23\nbob$2bo$3o!"),
        )
        assertEquals(listOf(0 to 0, 0 to 3), Rle.parse("o3\$o!"))
        assertFailsWith<IllegalArgumentException> { Rle.parse("zz!") }
        assertFailsWith<IllegalArgumentException> { Rle.parse("99999999o!") }
    }

    private fun soup(seed: Long, size: Int, density: Double): List<Pair<Int, Int>> {
        val random = Random(seed)
        return buildList {
            for (y in 0 until size) for (x in 0 until size) if (random.nextDouble() < density) add(x to y)
        }
    }

    /** The word-parallel engine must match the plain reference for every shape of grid. */
    @Test
    fun bitLifeMatchesTheReferenceOnRandomSoups() {
        val sizes = listOf(1 to 1, 3 to 7, 63 to 20, 64 to 20, 65 to 20, 128 to 16, 130 to 33, 200 to 64)
        for (edge in Edge.entries) {
            for ((w, h) in sizes) {
                val reference = LifeGrid(w, h, edge)
                val fast = BitLife(w, h, edge)
                val random = Random((w * 31 + h).toLong())
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        if (random.nextDouble() < 0.35) {
                            reference[x, y] = true
                            fast[x, y] = true
                        }
                    }
                }
                repeat(40) { gen ->
                    reference.step()
                    fast.step()
                    for (y in 0 until h) {
                        for (x in 0 until w) {
                            assertEquals(
                                reference[x, y], fast[x, y],
                                "$edge ${w}x$h generation ${gen + 1} at ($x, $y)",
                            )
                        }
                    }
                    assertEquals(reference.population().toLong(), fast.population())
                }
            }
        }
    }

    @Test
    fun hashLifeMatchesTheReferenceForEveryStepSize() {
        for (seed in 1L..6L) {
            val size = 48
            val offset = 160
            val reference = BitLife(size + 2 * offset, size + 2 * offset)
            val hash = HashLife()
            for ((x, y) in soup(seed, size, 0.3)) {
                reference[x + offset, y + offset] = true
                hash[x.toLong(), y.toLong()] = true
            }
            // Mixed step sizes exercise both full-speed and slowed-down recursion.
            for (steps in listOf(1L, 1L, 2L, 3L, 4L, 7L, 8L, 13L, 16L, 32L, 31L)) {
                hash.advance(steps)
                repeat(steps.toInt()) { reference.step() }
                assertEquals(reference.population(), hash.population, "seed $seed gen ${reference.generation}")
                for (y in 0 until reference.height) {
                    for (x in 0 until reference.width) {
                        assertEquals(
                            reference[x, y],
                            hash[(x - offset).toLong(), (y - offset).toLong()],
                            "seed $seed gen ${reference.generation} at ($x, $y)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun hashLifeHandlesNegativeCoordinatesAndClearing() {
        val hash = HashLife()
        for ((x, y) in Rle.parse(Patterns.GLIDER)) hash[x - 100L, y - 100L] = true
        assertEquals(5L, hash.population)
        assertTrue(hash[-99, -100])
        assertFalse(hash[1_000_000, 1_000_000], "far outside the root reads as dead")
        hash.advance(4)
        assertTrue(hash[-98, -99], "translated by (1, 1)")
        assertEquals(5L, hash.population)
        hash[-98, -99] = false
        assertEquals(4L, hash.population)
        hash.clear()
        assertEquals(0L, hash.population)
        assertEquals(java.math.BigInteger.ZERO, hash.generation)
    }

    @Test
    fun anEmptyPlaneStaysEmptyForAnyLengthOfTime() {
        val hash = HashLife()
        hash.advancePowerOfTwo(200)
        assertEquals(0L, hash.population)
    }

    /**
     * A Gosper gun emits one five-cell glider every thirty generations, so its population at
     * multiples of thirty is an exact function of time. This holds it to that function over a
     * span no cell-by-cell simulation could cover, which only works if memoization is correct.
     */
    @Test
    fun hashLifeAdvancesAGosperGunByAstronomicalTimeSpans() {
        val direct = BitLife(512, 512).apply { place(Patterns.GOSPER_GUN, 16, 16) }
        val initial = direct.population()
        assertEquals(36L, initial)
        // Measures emission over a window after the first gliders have formed.
        repeat(300) { direct.step() }
        val atThreeHundred = direct.population()
        repeat(300) { direct.step() }
        val perThreeHundred = direct.population() - atThreeHundred
        assertEquals(50L, perThreeHundred, "ten gliders of five cells each")

        val hash = HashLife().apply { place(Patterns.GOSPER_GUN) }
        hash.advance(300)
        assertEquals(atThreeHundred, hash.population)

        // Thirty times 2^40 generations: more than 3.2 * 10^13.
        val periods = 1L shl 40
        hash.advance(30L * periods - 300L)
        val expected = atThreeHundred + 5L * (periods - 10L)
        assertEquals(expected, hash.population)
        assertTrue(hash.nodeCount < 5_000_000, "memoization should keep this tractable: ${hash.nodeCount}")
    }

    /**
     * After 2^100 generations a Gosper gun has around 2^97 live cells, far beyond a Long. Glider
     * streams grow linearly and the gun itself repeats every thirty generations, so the exact
     * count at any time follows from a small early measurement with the same phase.
     */
    @Test
    fun populationsBeyondLongRangeStayExactAndTheEvolutionStaysCorrect() {
        val n = java.math.BigInteger.ONE.shiftLeft(100)
        val thirty = java.math.BigInteger.valueOf(30)
        val phase = n.mod(thirty).toLong()

        val early = HashLife().apply { place(Patterns.GOSPER_GUN) }
        early.advance(300 + phase)
        val earlyCount = early.exactPopulation

        val late = HashLife().apply { place(Patterns.GOSPER_GUN) }
        late.advancePowerOfTwo(100)
        val elapsedPeriods = n.subtract(java.math.BigInteger.valueOf(300 + phase)).divide(thirty)
        val expected = earlyCount.add(elapsedPeriods.multiply(java.math.BigInteger.valueOf(5)))

        assertEquals(expected, late.exactPopulation)
        assertEquals(Long.MAX_VALUE, late.population, "the Long view saturates, and never wraps")
        assertEquals(n, late.generation)
    }

    @Test
    fun exactPopulationMatchesTheLongCountWhenSmall() {
        val hash = HashLife().apply { place(Patterns.GOSPER_GUN) }
        hash.advance(1000)
        assertEquals(java.math.BigInteger.valueOf(hash.population), hash.exactPopulation)
    }

    @Test
    fun compactingPreservesThePattern() {
        val hash = HashLife().apply { place(Patterns.GOSPER_GUN) }
        hash.advance(1000)
        val population = hash.population
        val before = hash.nodeCount
        hash.compact()
        assertEquals(population, hash.population)
        assertTrue(hash.nodeCount <= before)
        // It keeps evolving correctly afterward.
        val control = HashLife().apply { place(Patterns.GOSPER_GUN) }
        control.advance(1300)
        hash.advance(300)
        assertEquals(control.population, hash.population)
    }
}
