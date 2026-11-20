package dev.aetherx86.silica

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleTest {
    private val highLife = Rule.parse("B36/S23")
    private val seeds = Rule.parse("B2/S")
    private val dayAndNight = Rule.parse("B3678/S34678")
    private val lifeWithoutDeath = Rule.parse("B3/S012345678")
    private val named = listOf(Rule.CONWAY, highLife, seeds, dayAndNight, lifeWithoutDeath, Rule.parse("B1/S1"))

    @Test
    fun notationIsCanonicalAndRoundTrips() {
        assertEquals("B3/S23", Rule.CONWAY.notation)
        assertEquals("B36/S23", Rule.parse("b63/s32").notation)
        assertEquals("B/S", Rule(0, 0).notation)
        val random = Random(7)
        repeat(2000) {
            val rule = Rule(random.nextInt(512), random.nextInt(512))
            assertEquals(rule, Rule.parse(rule.notation))
        }
    }

    @Test
    fun theOlderSurviveSlashBirthFormParses() {
        assertEquals(Rule.CONWAY, Rule.parse("23/3"))
        assertEquals(highLife, Rule.parse("23/36"))
        assertEquals(seeds, Rule.parse("/2"))
        // Explicit letters must still come in birth-then-survival order.
        assertFailsWith<IllegalArgumentException> { Rule.parse("S23/B3") }
    }

    @Test
    fun masksOutsideNineBitsAreRejected() {
        assertFailsWith<IllegalArgumentException> { Rule(1 shl 9, 0) }
        assertFailsWith<IllegalArgumentException> { Rule(0, -1) }
        assertTrue(Rule.CONWAY.born(3) && !Rule.CONWAY.born(2))
        assertTrue(Rule.CONWAY.survives(2) && Rule.CONWAY.survives(3) && !Rule.CONWAY.survives(4))
    }

    private fun randomRules(count: Int, seed: Long, allowB0: Boolean): List<Rule> {
        val random = Random(seed)
        return List(count) {
            var birth = random.nextInt(512)
            if (!allowB0) birth = birth and 1.inv()
            Rule(birth, random.nextInt(512))
        }
    }

    /** The bit-sliced generic path must match the plain reference for every rule and shape. */
    @Test
    fun bitLifeMatchesTheReferenceForArbitraryRules() {
        val rules = named + Rule.parse("B0/S8") + Rule.parse("B0123478/S01234678") + randomRules(12, 99, allowB0 = true)
        val sizes = listOf(1 to 1, 5 to 3, 63 to 9, 64 to 9, 65 to 9, 129 to 7)
        for (rule in rules) {
            for (edge in Edge.entries) {
                for ((w, h) in sizes) {
                    val reference = LifeGrid(w, h, edge, rule)
                    val fast = BitLife(w, h, edge, rule)
                    val random = Random((w * 131 + h + rule.birth * 7 + rule.survive).toLong())
                    for (y in 0 until h) {
                        for (x in 0 until w) {
                            if (random.nextDouble() < 0.4) {
                                reference[x, y] = true
                                fast[x, y] = true
                            }
                        }
                    }
                    repeat(12) { gen ->
                        reference.step()
                        fast.step()
                        for (y in 0 until h) {
                            for (x in 0 until w) {
                                assertEquals(
                                    reference[x, y], fast[x, y],
                                    "${rule.notation} $edge ${w}x$h generation ${gen + 1} at ($x, $y)",
                                )
                            }
                        }
                        assertEquals(reference.population().toLong(), fast.population(), "${rule.notation} $edge ${w}x$h")
                    }
                }
            }
        }
    }

    @Test
    fun hashLifeMatchesBitLifeForRulesWithoutB0() {
        val rules = named + randomRules(8, 5, allowB0 = false)
        for (rule in rules) {
            val size = 32
            val offset = 80
            val reference = BitLife(size + 2 * offset, size + 2 * offset, Edge.DEAD, rule)
            val hash = HashLife(rule)
            val random = Random(rule.birth * 1000L + rule.survive)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    if (random.nextDouble() < 0.3) {
                        reference[x + offset, y + offset] = true
                        hash[x.toLong(), y.toLong()] = true
                    }
                }
            }
            for (steps in listOf(1L, 1L, 2L, 3L, 4L, 7L, 8L, 13L, 16L)) {
                hash.advance(steps)
                repeat(steps.toInt()) { reference.step() }
                assertEquals(reference.population(), hash.population, "${rule.notation} gen ${reference.generation}")
                for (y in 0 until reference.height) {
                    for (x in 0 until reference.width) {
                        assertEquals(
                            reference[x, y],
                            hash[(x - offset).toLong(), (y - offset).toLong()],
                            "${rule.notation} gen ${reference.generation} at ($x, $y)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun hashLifeRejectsRulesThatBirthFromNothing() {
        assertFailsWith<IllegalArgumentException> { HashLife(Rule.parse("B0/S8")) }
        assertEquals(Rule.CONWAY, HashLife().rule)
    }

    /** Day & Night is symmetric under swapping live and dead cells, which a torus preserves. */
    @Test
    fun dayAndNightCommutesWithInversion() {
        val w = 70
        val h = 40
        val normal = BitLife(w, h, Edge.TORUS, dayAndNight)
        val inverted = BitLife(w, h, Edge.TORUS, dayAndNight)
        val random = Random(11)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val alive = random.nextDouble() < 0.45
                normal[x, y] = alive
                inverted[x, y] = !alive
            }
        }
        repeat(50) { gen ->
            normal.step()
            inverted.step()
            for (y in 0 until h) {
                for (x in 0 until w) assertEquals(normal[x, y], !inverted[x, y], "generation ${gen + 1} at ($x, $y)")
            }
        }
    }

    @Test
    fun seedsKillsEveryLiveCellAndLifeWithoutDeathNeverKillsAny() {
        val random = Random(17)
        val s = BitLife(90, 60, Edge.TORUS, seeds)
        val l = BitLife(90, 60, Edge.TORUS, lifeWithoutDeath)
        for (y in 0 until 60) for (x in 0 until 90) if (random.nextDouble() < 0.1) {
            s[x, y] = true
            l[x, y] = true
        }
        repeat(25) {
            val liveSeeds = buildList { for (y in 0 until 60) for (x in 0 until 90) if (s[x, y]) add(x to y) }
            val liveLwod = buildList { for (y in 0 until 60) for (x in 0 until 90) if (l[x, y]) add(x to y) }
            s.step()
            l.step()
            for ((x, y) in liveSeeds) assertFalse(s[x, y], "a seeds cell survived at ($x, $y)")
            for ((x, y) in liveLwod) assertTrue(l[x, y], "a cell died under life without death at ($x, $y)")
        }
    }
}
