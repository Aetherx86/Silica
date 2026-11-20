package dev.aetherx86.silica

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RleTest {
    @Test
    fun theHeaderNameAndCommentsAreRead() {
        val pattern = Rle.read(
            """
            #N Glider
            #C The smallest spaceship.
            #c Second comment.
            #O someone
            x = 3, y = 3, rule = B36/S23
            bob${'$'}2bo${'$'}3o!
            """.trimIndent(),
        )
        assertEquals("Glider", pattern.name)
        assertEquals(listOf("The smallest spaceship.", "Second comment."), pattern.comments)
        assertEquals(3, pattern.width)
        assertEquals(3, pattern.height)
        assertEquals(Rule.parse("B36/S23"), pattern.rule)
        assertEquals(Rle.parse(Patterns.GLIDER), pattern.cells)
    }

    @Test
    fun ruleSuffixesOlderNotationAndWhitespaceAreAccepted() {
        val pattern = Rle.read("x=2,y=1,rule=23/3:T64,64\n2o !\n trailing text is ignored")
        assertEquals(Rule.CONWAY, pattern.rule)
        assertEquals(listOf(0 to 0, 1 to 0), pattern.cells)
        val spaced = Rle.read("x = 3, y = 1\n o b o !")
        assertNull(spaced.rule)
        assertEquals(listOf(0 to 0, 2 to 0), spaced.cells)
    }

    @Test
    fun aMissingHeaderFallsBackToMeasuredSize() {
        val pattern = Rle.read("bo$3o!")
        assertEquals(3, pattern.width)
        assertEquals(2, pattern.height)
        assertNull(pattern.rule)
    }

    @Test
    fun parseIgnoresHeadersItCannotInterpretButReadDoesNot() {
        val text = "x = 1, y = 1, rule = LifeHistory\no!"
        assertEquals(listOf(0 to 0), Rle.parse(text))
        assertFailsWith<IllegalArgumentException> { Rle.read(text) }
        assertFailsWith<IllegalArgumentException> { Rle.read("x = -1, y = 1\no!") }
        assertFailsWith<IllegalArgumentException> { Rle.read("x = 3, y\no!") }
    }

    @Test
    fun hostileInputFailsCleanly() {
        val wide = "999999b".repeat(2000) + "o!"
        assertFailsWith<IllegalArgumentException> { Rle.parse(wide) }
        val tall = "999999$".repeat(2000) + "o!"
        assertFailsWith<IllegalArgumentException> { Rle.parse(tall) }
        assertFailsWith<IllegalArgumentException> { Rle.parse("100000o$".repeat(20), maxCells = 500_000) }
        assertFailsWith<IllegalArgumentException> { Rle.parse("1000001o!") }
        assertFailsWith<IllegalArgumentException> { Rle.parse("3x!") }
        assertEquals(4, Rle.parse("4o!", maxCells = 4).size)
        assertFailsWith<IllegalArgumentException> { Rle.parse("5o!", maxCells = 4) }
    }

    @Test
    fun writingProducesTheCanonicalForm() {
        assertEquals("x = 3, y = 3, rule = B3/S23\nbo\$2bo\$3o!\n", Rle.write(Rle.parse(Patterns.GLIDER)))
        assertEquals("x = 1, y = 4, rule = B3/S23\no3\$o!\n", Rle.write(listOf(5 to 7, 5 to 10)))
        assertEquals(
            "#C two\n#C lines\nx = 2, y = 1, rule = B36/S23\n2o!\n",
            Rle.write(listOf(-4 to -4, -3 to -4, -3 to -4), Rule.parse("B36/S23"), listOf("two\nlines")),
        )
        val empty = Rle.write(emptyList())
        assertEquals("x = 0, y = 0, rule = B3/S23\n!\n", empty)
        assertEquals(emptyList(), Rle.read(empty).cells)
    }

    /** Writing then reading returns the same cells, translated to the origin, for random patterns. */
    @Test
    fun randomPatternsRoundTrip() {
        val random = Random(42)
        repeat(300) { trial ->
            val w = 1 + random.nextInt(200)
            val h = 1 + random.nextInt(40)
            val density = random.nextDouble()
            val cells = buildSet {
                for (y in 0 until h) for (x in 0 until w) if (random.nextDouble() < density) add(x - 50 to y + 9)
            }
            if (cells.isEmpty()) return@repeat
            val rule = Rule(random.nextInt(512), random.nextInt(512))
            val text = Rle.write(cells, rule)
            for (line in text.lines()) assertTrue(line.length <= Rle.LINE_LENGTH, "trial $trial: '$line'")
            val back = Rle.read(text)
            val minX = cells.minOf { it.first }
            val minY = cells.minOf { it.second }
            assertEquals(cells.map { (x, y) -> x - minX to y - minY }.toSet(), back.cells.toSet(), "trial $trial")
            assertEquals(cells.size, back.cells.size)
            assertEquals(cells.maxOf { it.first } - minX + 1, back.width)
            assertEquals(cells.maxOf { it.second } - minY + 1, back.height)
            assertEquals(rule, back.rule)
        }
    }

    @Test
    fun builtInPatternsRoundTrip() {
        for (rle in listOf(Patterns.GLIDER, Patterns.PULSAR, Patterns.GOSPER_GUN, Patterns.ACORN, Patterns.DIEHARD)) {
            val cells = Rle.parse(rle)
            assertEquals(cells.toSet(), Rle.parse(Rle.write(cells)).toSet())
        }
    }
}
