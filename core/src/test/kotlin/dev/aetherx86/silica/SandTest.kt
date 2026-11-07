package dev.aetherx86.silica

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SandTest {
    private fun SandWorld.type(x: Int, y: Int) = this[x, y].type

    private fun SandWorld.fillRow(y: Int, type: Int) {
        for (x in 0 until width) paint(x, y, type)
    }

    @Test
    fun cellPackingRoundTripsEveryField() {
        val cell = Cell(Material.FIRE, 200, 40000)
        assertEquals(Material.FIRE, cell.type)
        assertEquals(200, cell.variation)
        assertEquals(40000, cell.life)
        assertEquals(39999, cell.withLife(39999).life)
        assertEquals(Material.FIRE, cell.withLife(39999).type)
        assertEquals(200, cell.withLife(39999).variation)
        assertEquals(0xFFFF, Cell(Material.SAND, 0xFF, 0xFFFF).life)
    }

    /** The teleportation bug: a top-to-bottom scan carries a grain to the floor in one tick. */
    @Test
    fun aGrainFallsExactlyOneCellPerTick() {
        val world = SandWorld(8, 64)
        world.paint(4, 0, Material.SAND)
        for (expected in 1..63) {
            world.step()
            assertEquals(Material.SAND, world.type(4, expected), "after tick $expected")
            assertEquals(Material.EMPTY, world.type(4, expected - 1))
            assertEquals(1, world.count(Material.SAND))
        }
        world.step()
        assertEquals(Material.SAND, world.type(4, 63), "it rests on the floor")
    }

    /**
     * Scanning from the bottom up moves the lowest grain first, which frees the cell above it in
     * time for the next grain, so a column falls as one solid block. A top-down scan would see
     * every grain below still in place, and the column would smear out.
     */
    @Test
    fun aColumnFallsTogetherAsASolidBlock() {
        val world = SandWorld(8, 64)
        for (y in 0 until 5) world.paint(4, y, Material.SAND)
        for (tick in 1..20) {
            world.step()
            for (y in 0 until 64) {
                val expected = if (y in tick until tick + 5) Material.SAND else Material.EMPTY
                assertEquals(expected, world.type(4, y), "row $y after tick $tick")
            }
        }
    }

    @Test
    fun gasRisesExactlyOneCellPerTick() {
        // A scan from the bottom up meets a rising particle again on the next row, which is
        // the same bug in the other direction. The frame stamp prevents it.
        val world = SandWorld(8, 64)
        world.paint(4, 63, Material.SMOKE)
        for (expected in 62 downTo 40) {
            world.step()
            assertEquals(Material.SMOKE, world.type(4, expected), "at row $expected")
        }
    }

    @Test
    fun noParticleMovesTwiceInOneTick() {
        val random = Random(9)
        val world = SandWorld(64, 64, seed = 3)
        repeat(400) {
            world.paint(random.nextInt(64), random.nextInt(32), Material.SAND)
        }
        repeat(100) {
            val before = world.snapshot()
            world.step()
            val after = world.snapshot()
            // Every grain now in the world must have been at most one cell away a tick ago.
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    if (Cell(after[y * 64 + x]).type != Material.SAND) continue
                    var found = false
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until 64 && ny in 0 until 64 &&
                                Cell(before[ny * 64 + nx]).type == Material.SAND
                            ) {
                                found = true
                            }
                        }
                    }
                    assertTrue(found, "a grain at ($x, $y) appeared from further than one cell")
                }
            }
        }
    }

    @Test
    fun sandPilesIntoASlopeInsteadOfAColumn() {
        val world = SandWorld(41, 41, seed = 5)
        repeat(200) {
            world.paint(20, 0, Material.SAND)
            world.step()
        }
        repeat(100) { world.step() }
        val column = (0 until 41).count { world.type(20, it) == Material.SAND }
        val floor = (0 until 41).count { world.type(it, 40) == Material.SAND }
        assertTrue(floor > column, "pile should spread: floor $floor, column $column")
    }

    @Test
    fun sandDoesNotDriftToOneSide() {
        var left = 0
        var right = 0
        val world = SandWorld(101, 101, seed = 7)
        repeat(1500) {
            if (it % 2 == 0) world.paint(50, 0, Material.SAND)
            world.step()
        }
        for (y in 0 until 101) {
            for (x in 0 until 101) {
                if (world.type(x, y) == Material.SAND) {
                    if (x < 50) left++
                    if (x > 50) right++
                }
            }
        }
        val total = left + right
        assertTrue(total > 500, "the pile should have spread out")
        assertTrue(kotlin.math.abs(left - right) < total / 8, "lopsided: $left left vs $right right")
    }

    @Test
    fun sandSinksThroughWater() {
        val world = SandWorld(1, 8)
        for (y in 4 until 8) world.paint(0, y, Material.WATER)
        world.paint(0, 0, Material.SAND)
        repeat(20) { world.step() }
        assertEquals(Material.SAND, world.type(0, 7), "sand reaches the bottom")
        assertEquals(4, world.count(Material.WATER), "displaced water is conserved")
    }

    @Test
    fun oilFloatsOnWater() {
        val world = SandWorld(1, 8)
        for (y in 0 until 4) world.paint(0, y, Material.WATER)
        for (y in 4 until 8) world.paint(0, y, Material.OIL)
        repeat(40) { world.step() }
        for (y in 4 until 8) assertEquals(Material.WATER, world.type(0, y), "water settles at $y")
        for (y in 0 until 4) assertEquals(Material.OIL, world.type(0, y), "oil rises to $y")
    }

    /** A one-cell-wide column cannot show sideways churn, which is why this needs a real basin. */
    @Test
    fun oilRisesThroughWaterInAWideBasin() {
        val width = 48
        val height = 24
        val world = SandWorld(width, height, seed = 17)
        for (y in 8 until 16) for (x in 0 until width) world.paint(x, y, Material.WATER)
        for (y in 16 until 24) for (x in 0 until width) world.paint(x, y, Material.OIL)
        repeat(4000) { world.step() }

        var lowestOil = -1
        var highestWater = height
        for (y in 0 until height) {
            for (x in 0 until width) {
                when (world.type(x, y)) {
                    Material.OIL -> lowestOil = maxOf(lowestOil, y)
                    Material.WATER -> highestWater = minOf(highestWater, y)
                }
            }
        }
        assertEquals(8 * width, world.count(Material.OIL))
        assertEquals(8 * width, world.count(Material.WATER))
        assertTrue(lowestOil < highestWater, "oil reaches row $lowestOil, water reaches row $highestWater")
    }

    @Test
    fun liquidsDoNotChurnOnceSettled() {
        val width = 32
        val world = SandWorld(width, 16, seed = 23)
        for (y in 0 until 8) for (x in 0 until width) world.paint(x, y + 8, Material.WATER)
        for (y in 0 until 8) for (x in 0 until width) world.paint(x, y, Material.OIL)
        val start = world.snapshot()
        repeat(10) { world.step() }
        assertContentEquals(start, world.snapshot(), "two full layers leave nothing to move")
        assertEquals(0L, world.cellsVisited, "with nothing moving, every chunk should be asleep")
    }

    @Test
    fun waterSpreadsToFillABasin() {
        val world = SandWorld(32, 8, seed = 2)
        for (y in 0 until 7) world.paint(16, y, Material.WATER)
        repeat(200) { world.step() }
        assertEquals(7, world.count(Material.WATER))
        val onFloor = (0 until 32).count { world.type(it, 7) == Material.WATER }
        assertEquals(7, onFloor, "water should flatten into a single layer")
    }

    @Test
    fun solidsNeverMove() {
        val world = SandWorld(16, 16)
        world.paint(8, 2, Material.STONE)
        world.paint(9, 2, Material.WOOD)
        world.paint(8, 0, Material.SAND)
        repeat(30) { world.step() }
        assertEquals(Material.STONE, world.type(8, 2))
        assertEquals(Material.WOOD, world.type(9, 2))
    }

    @Test
    fun passiveMaterialsConserveMatterForever() {
        val random = Random(11)
        val world = SandWorld(96, 96, seed = 13)
        val placed = intArrayOf(0, 0, 0, 0)
        repeat(3000) {
            val x = random.nextInt(96)
            val y = random.nextInt(96)
            if (world.type(x, y) == Material.EMPTY) {
                val type = intArrayOf(Material.SAND, Material.WATER, Material.OIL)[random.nextInt(3)]
                world.paint(x, y, type)
                placed[type]++
            }
        }
        repeat(500) {
            world.step()
            assertEquals(placed[Material.SAND], world.count(Material.SAND), "sand at frame $it")
            assertEquals(placed[Material.WATER], world.count(Material.WATER), "water at frame $it")
            assertEquals(placed[Material.OIL], world.count(Material.OIL), "oil at frame $it")
        }
    }

    @Test
    fun fireBurnsWoodAndEventuallyBurnsOut() {
        val world = SandWorld(32, 16, seed = 21)
        world.fillRow(15, Material.WOOD)
        world.paint(0, 14, Material.FIRE)
        repeat(4000) { world.step() }
        assertEquals(0, world.count(Material.WOOD), "the whole plank should burn")
        assertEquals(0, world.count(Material.FIRE), "the fire should burn out")
    }

    @Test
    fun smokeAndSteamDissipate() {
        val world = SandWorld(32, 32, seed = 4)
        world.paint(16, 31, Material.SMOKE)
        world.paint(10, 31, Material.STEAM)
        repeat(400) { world.step() }
        assertEquals(0, world.count(Material.SMOKE))
        assertEquals(0, world.count(Material.STEAM))
    }

    @Test
    fun waterQuenchesFire() {
        val world = SandWorld(8, 8, seed = 1)
        // A wall keeps the water in place, so it is still next to the fire when the fire updates.
        world.paint(2, 7, Material.STONE)
        world.paint(3, 7, Material.WATER)
        world.paint(4, 7, Material.FIRE)
        world.step()
        assertEquals(0, world.count(Material.FIRE))
        assertEquals(1, world.count(Material.WATER), "the water survives")
        assertEquals(1, world.count(Material.STEAM), "the fire became steam")
    }

    @Test
    fun acidIsConsumedAsItDissolvesThings() {
        val world = SandWorld(8, 16, seed = 6)
        world.fillRow(15, Material.STONE)
        world.paint(4, 0, Material.ACID)
        repeat(600) { world.step() }
        assertEquals(0, world.count(Material.ACID), "acid is used up")
        assertEquals(7, world.count(Material.STONE), "one drop dissolves one cell of stone")
    }

    private fun randomScene(world: SandWorld, seed: Long, fill: Double) {
        val random = Random(seed)
        val types = intArrayOf(
            Material.SAND, Material.SAND, Material.WATER, Material.OIL, Material.STONE,
            Material.WOOD, Material.FIRE, Material.ACID, Material.SMOKE, Material.STEAM,
        )
        for (y in 0 until world.height) {
            for (x in 0 until world.width) {
                if (random.nextDouble() < fill) world.paint(x, y, types[random.nextInt(types.size)])
            }
        }
    }

    /**
     * Chunk sleeping is purely an optimization, so a world with it on must match a world with it
     * off, cell for cell, on every frame. This holds across chunk sizes that land on, inside and
     * larger than the world's dimensions.
     */
    @Test
    fun chunkSleepingNeverChangesTheOutcome() {
        for (seed in 1L..12L) {
            for (chunk in listOf(4, 16, 32, 200)) {
                val awake = SandWorld(90, 70, seed = seed, chunkSize = chunk, sleeping = false)
                val asleep = SandWorld(90, 70, seed = seed, chunkSize = chunk, sleeping = true)
                randomScene(awake, seed, 0.12)
                randomScene(asleep, seed, 0.12)
                repeat(200) { frame ->
                    // Paints mid-run, since waking from an outside change has to be exact too.
                    if (frame % 60 == 30) {
                        for (x in 0 until 30) {
                            awake.paint(x * 3, 0, Material.SAND)
                            asleep.paint(x * 3, 0, Material.SAND)
                        }
                    }
                    awake.step()
                    asleep.step()
                    assertContentEquals(
                        awake.snapshot(), asleep.snapshot(),
                        "seed $seed chunk $chunk frame $frame",
                    )
                }
            }
        }
    }

    @Test
    fun aSettledWorldDoesAlmostNoWork() {
        val world = SandWorld(256, 256, seed = 8, sleeping = true)
        for (x in 100 until 150) world.paint(x, 0, Material.SAND)
        repeat(800) { world.step() }
        val settled = world.snapshot()
        world.step()
        assertContentEquals(settled, world.snapshot(), "the pile should have come to rest")
        assertEquals(0L, world.cellsVisited, "every chunk should be asleep")

        // Disturbing one corner wakes only that corner.
        world.paint(0, 0, Material.SAND)
        world.step()
        assertTrue(world.cellsVisited < 256L * 256L / 10, "visited ${world.cellsVisited}")
    }

    @Test
    fun sleepingSavesWorkOnASparseWorld() {
        val build = { sleeping: Boolean ->
            SandWorld(1024, 1024, seed = 3, sleeping = sleeping).apply {
                for (x in 0 until 40) paint(10 + x, 10, Material.SAND)
            }
        }
        val awake = build(false)
        val asleep = build(true)
        repeat(50) {
            awake.step()
            asleep.step()
        }
        assertContentEquals(awake.snapshot(), asleep.snapshot())
        assertTrue(
            asleep.totalCellsVisited * 20 < awake.totalCellsVisited,
            "sleeping visited ${asleep.totalCellsVisited}, awake ${awake.totalCellsVisited}",
        )
    }

    @Test
    fun theSameSeedReproducesTheSameWorld() {
        val run = { seed: Long ->
            SandWorld(64, 64, seed = seed).also {
                randomScene(it, 99, 0.2)
                repeat(100) { _ -> it.step() }
            }.snapshot()
        }
        assertContentEquals(run(5), run(5))
        assertTrue(!run(5).contentEquals(run(6)), "different seeds should differ")
    }

    @Test
    fun paintingOutOfBoundsIsIgnored() {
        val world = SandWorld(4, 4)
        world.paint(-1, 0, Material.SAND)
        world.paint(0, -1, Material.SAND)
        world.paint(4, 0, Material.SAND)
        world.paint(0, 4, Material.SAND)
        assertEquals(0, world.count(Material.SAND))
    }
}
