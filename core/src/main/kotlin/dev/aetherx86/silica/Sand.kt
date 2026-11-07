package dev.aetherx86.silica

/** Materials, stored in the low bits of a packed cell. */
object Material {
    const val EMPTY = 0
    const val SAND = 1
    const val WATER = 2
    const val OIL = 3
    const val ACID = 4
    const val STONE = 5
    const val WOOD = 6
    const val FIRE = 7
    const val SMOKE = 8
    const val STEAM = 9
    const val COUNT = 10

    /** Heavier materials sink through lighter fluids. Solids never move. */
    private val DENSITY = intArrayOf(0, 8, 4, 3, 5, 100, 100, 1, 1, 1)

    fun density(type: Int): Int = DENSITY[type]

    fun isLiquid(type: Int): Boolean = type == WATER || type == OIL || type == ACID

    fun isGas(type: Int): Boolean = type == SMOKE || type == STEAM

    fun isSolid(type: Int): Boolean = type == STONE || type == WOOD

    fun isFlammable(type: Int): Boolean = type == WOOD || type == OIL

    fun name(type: Int): String = when (type) {
        EMPTY -> "empty"
        SAND -> "sand"
        WATER -> "water"
        OIL -> "oil"
        ACID -> "acid"
        STONE -> "stone"
        WOOD -> "wood"
        FIRE -> "fire"
        SMOKE -> "smoke"
        STEAM -> "steam"
        else -> "unknown"
    }
}

/**
 * A cell packed into one `Int`, with no boxing: 8 bits of type, 8 bits of color variation and
 * 16 bits of remaining lifetime.
 */
@JvmInline
value class Cell(val packed: Int) {
    constructor(type: Int, variation: Int, life: Int = 0) : this(
        (type and 0xFF) or ((variation and 0xFF) shl 8) or ((life and 0xFFFF) shl 16),
    )

    val type: Int get() = packed and 0xFF
    val variation: Int get() = (packed ushr 8) and 0xFF
    val life: Int get() = packed ushr 16

    fun withLife(newLife: Int): Cell = Cell(type, variation, newLife)
}

/**
 * Falling-sand simulation. Particles move in place, so matter is conserved except where a
 * reaction explicitly creates or destroys it.
 *
 * - The grid is scanned bottom to top, so a falling particle moves once per tick instead of
 *   being carried to the floor by the scan, and the horizontal direction alternates by frame
 *   so that nothing drifts to one side.
 * - A per-cell frame stamp records what has already moved this tick. A one-bit parity clock
 *   would break under chunk sleeping, since a sleeping cell keeps a stale parity.
 * - Randomness is a pure function of position and frame rather than a stream. Sleeping chunks
 *   therefore consume no randomness, which makes a run with sleeping produce exactly the same
 *   world as a run without it.
 * - Fire, acid, smoke and steam change on their own, so they keep their chunk awake. Everything
 *   else only moves when it has somewhere to go, so a region of it with no recent change has
 *   nothing left to do.
 */
class SandWorld(
    val width: Int,
    val height: Int,
    private val seed: Long = 1L,
    private val chunkSize: Int = 32,
    private val sleeping: Boolean = true,
) {
    init {
        require(width > 0 && height > 0) { "world dimensions must be positive" }
        require(chunkSize > 0) { "chunk size must be positive" }
    }

    private companion object {
        const val DOWN = 0
        const val UP = 1
        const val SIDE = 2
    }

    private val cells = IntArray(width * height)
    private val stamps = IntArray(width * height)
    private val chunksWide = (width + chunkSize - 1) / chunkSize
    private val chunksHigh = (height + chunkSize - 1) / chunkSize
    private var awakeNow = BooleanArray(chunksWide * chunksHigh) { true }
    private var awakeNext = BooleanArray(chunksWide * chunksHigh)

    var frame = 0
        private set

    /** Cells visited during the most recent frame, for measuring what sleeping saves. */
    var cellsVisited = 0L
        private set

    /** Total cells visited since the world was created. */
    var totalCellsVisited = 0L
        private set

    operator fun get(x: Int, y: Int): Cell = Cell(cells[y * width + x])

    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Places a material. This is the only way to change the world from outside a frame. */
    fun paint(x: Int, y: Int, type: Int) {
        if (!inBounds(x, y)) return
        val variation = (random(x, y, frame, 7).toInt() and 0xFF)
        val life = when (type) {
            Material.FIRE -> 30 + (random(x, y, frame, 8) and 31).toInt()
            Material.SMOKE, Material.STEAM -> 60 + (random(x, y, frame, 8) and 63).toInt()
            else -> 0
        }
        cells[y * width + x] = Cell(type, variation, life).packed
        wake(x, y)
    }

    fun count(type: Int): Int = cells.count { Cell(it).type == type }

    /** A copy of the raw cells, for comparing worlds. */
    fun snapshot(): IntArray = cells.copyOf()

    /**
     * SplitMix64-style hash of the position, frame and purpose. The result depends on nothing
     * else, which is the property chunk sleeping relies on.
     */
    private fun random(x: Int, y: Int, f: Int, salt: Int): Long {
        var z = seed + (x.toLong() * 0x9E3779B97F4A7C15uL.toLong()) +
            (y.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong()) +
            (f.toLong() * 0x165667B19E3779F9uL.toLong()) +
            (salt.toLong() * 0x27D4EB2F165667C5uL.toLong())
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9uL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBuL.toLong()
        return z xor (z ushr 31)
    }

    private fun chance(x: Int, y: Int, salt: Int, percent: Int): Boolean =
        java.lang.Long.remainderUnsigned(random(x, y, frame, salt), 100L) < percent

    /**
     * Marks the chunk holding this cell and its neighbors for processing. It sets both flags:
     * `awakeNow` so a neighboring chunk scanned later this same frame reacts immediately, as it
     * would with sleeping off, and `awakeNext` so the change is seen next frame.
     */
    private fun wake(x: Int, y: Int) {
        val cx = x / chunkSize
        val cy = y / chunkSize
        for (j in maxOf(cy - 1, 0)..minOf(cy + 1, chunksHigh - 1)) {
            for (i in maxOf(cx - 1, 0)..minOf(cx + 1, chunksWide - 1)) {
                val index = j * chunksWide + i
                awakeNow[index] = true
                awakeNext[index] = true
            }
        }
    }

    private fun swap(from: Int, to: Int, fx: Int, fy: Int, tx: Int, ty: Int) {
        val moved = cells[from]
        cells[from] = cells[to]
        cells[to] = moved
        stamps[to] = frame + 1
        // A displaced particle has moved too. An emptied cell has not, and stamping it would
        // stop the grain above from following into the gap, smearing a falling column.
        if (Cell(cells[from]).type != Material.EMPTY) stamps[from] = frame + 1
        wake(fx, fy)
        wake(tx, ty)
    }

    /** Whether a particle of `type` can move in direction [dir] into the target cell. */
    private fun canEnter(type: Int, tx: Int, ty: Int, dir: Int): Boolean {
        if (!inBounds(tx, ty)) return false
        val index = ty * width + tx
        // Whatever already moved this frame stays put until the next one.
        if (stamps[index] == frame + 1) return false
        val other = Cell(cells[index]).type
        if (other == Material.EMPTY) return true
        if (Material.isSolid(other) || Material.isSolid(type)) return false
        return when (dir) {
            DOWN -> Material.density(type) > Material.density(other)
            UP -> Material.density(type) < Material.density(other)
            // Sideways is only for spreading into free space. If fluids of different densities
            // could swap sideways, they would churn constantly and the stamps from that churn
            // would stop the heavier one from ever sinking.
            else -> Material.isLiquid(type) && Material.isGas(other)
        }
    }

    /** Attempts a move, and returns whether it happened. */
    private fun tryMove(x: Int, y: Int, tx: Int, ty: Int, type: Int, dir: Int): Boolean {
        if (!canEnter(type, tx, ty, dir)) return false
        swap(y * width + x, ty * width + tx, x, y, tx, ty)
        return true
    }

    private fun updateFalling(x: Int, y: Int, type: Int) {
        if (tryMove(x, y, x, y + 1, type, DOWN)) return
        val first = if (random(x, y, frame, 1) and 1L == 0L) -1 else 1
        if (tryMove(x, y, x + first, y + 1, type, DOWN)) return
        if (tryMove(x, y, x - first, y + 1, type, DOWN)) return
        if (Material.isLiquid(type)) {
            if (tryMove(x, y, x + first, y, type, SIDE)) return
            tryMove(x, y, x - first, y, type, SIDE)
        }
    }

    private fun updateGas(x: Int, y: Int, type: Int) {
        val index = y * width + x
        val cell = Cell(cells[index])
        if (cell.life <= 1) {
            cells[index] = 0
            wake(x, y)
            return
        }
        cells[index] = cell.withLife(cell.life - 1).packed
        wake(x, y)
        if (tryMove(x, y, x, y - 1, type, UP)) return
        val first = if (random(x, y, frame, 1) and 1L == 0L) -1 else 1
        if (tryMove(x, y, x + first, y - 1, type, UP)) return
        if (tryMove(x, y, x - first, y - 1, type, UP)) return
        if (tryMove(x, y, x + first, y, type, SIDE)) return
        tryMove(x, y, x - first, y, type, SIDE)
    }

    private fun updateFire(x: Int, y: Int) {
        val index = y * width + x
        val cell = Cell(cells[index])
        wake(x, y)
        var quenched = false
        forEachNeighbor(x, y) { nx, ny ->
            val n = ny * width + nx
            val other = Cell(cells[n]).type
            if (other == Material.WATER) {
                quenched = true
            } else if (Material.isFlammable(other) && chance(nx, ny, 3, 25)) {
                paintDuringFrame(nx, ny, Material.FIRE)
            }
        }
        if (quenched) {
            paintDuringFrame(x, y, Material.STEAM)
            return
        }
        if (cell.life <= 1) {
            if (chance(x, y, 4, 40)) {
                paintDuringFrame(x, y, Material.SMOKE)
            } else {
                cells[index] = 0
            }
            return
        }
        cells[index] = cell.withLife(cell.life - 1).packed
    }

    private fun updateAcid(x: Int, y: Int) {
        wake(x, y)
        var dissolved = false
        forEachNeighbor(x, y) { nx, ny ->
            if (!dissolved) {
                val other = Cell(cells[ny * width + nx]).type
                val soluble = other == Material.STONE || other == Material.WOOD || other == Material.SAND
                if (soluble && chance(nx, ny, 5, 8)) {
                    cells[ny * width + nx] = 0
                    wake(nx, ny)
                    dissolved = true
                }
            }
        }
        if (dissolved) {
            // Each reaction uses up the acid, so a drop cannot dissolve the whole world.
            cells[y * width + x] = 0
            return
        }
        updateFalling(x, y, Material.ACID)
    }

    private inline fun forEachNeighbor(x: Int, y: Int, action: (Int, Int) -> Unit) {
        if (y > 0) action(x, y - 1)
        if (y < height - 1) action(x, y + 1)
        if (x > 0) action(x - 1, y)
        if (x < width - 1) action(x + 1, y)
    }

    private fun paintDuringFrame(x: Int, y: Int, type: Int) {
        paint(x, y, type)
        stamps[y * width + x] = frame + 1
    }

    /** Advances the world by one tick. */
    fun step() {
        var visited = 0L
        // A world with sleeping off is simply one where every chunk is always awake.
        if (!sleeping) awakeNow.fill(true)
        val leftToRight = frame and 1 == 0
        for (y in height - 1 downTo 0) {
            val chunkRow = y / chunkSize * chunksWide
            val range = if (leftToRight) 0 until width else (width - 1 downTo 0)
            for (x in range) {
                if (!awakeNow[chunkRow + x / chunkSize]) continue
                visited++
                val index = y * width + x
                if (stamps[index] == frame + 1) continue
                val cell = Cell(cells[index])
                when (cell.type) {
                    Material.EMPTY, Material.STONE, Material.WOOD -> {}
                    Material.SAND, Material.WATER, Material.OIL -> updateFalling(x, y, cell.type)
                    Material.ACID -> updateAcid(x, y)
                    Material.FIRE -> updateFire(x, y)
                    Material.SMOKE, Material.STEAM -> updateGas(x, y, cell.type)
                }
            }
        }
        frame++
        cellsVisited = visited
        totalCellsVisited += visited
        val finished = awakeNow
        awakeNow = awakeNext
        awakeNext = finished
        awakeNext.fill(false)
    }
}
