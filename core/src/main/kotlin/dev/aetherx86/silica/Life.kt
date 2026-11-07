package dev.aetherx86.silica

/** How a grid treats cells beyond its edge. */
enum class Edge { DEAD, TORUS }

/**
 * A life-like rule such as `B3/S23`.
 *
 * Bit `n` of [birth] means a dead cell with `n` live neighbors is born, and bit `n` of
 * [survive] means a live cell with `n` live neighbors stays alive.
 */
data class Rule(val birth: Int, val survive: Int) {
    companion object {
        val CONWAY = Rule(1 shl 3, (1 shl 2) or (1 shl 3))

        fun parse(text: String): Rule {
            val match = Regex("""^[Bb]([0-8]*)/[Ss]([0-8]*)$""").matchEntire(text.trim())
                ?: throw IllegalArgumentException("not a life-like rule: $text")
            val mask = { digits: String -> digits.fold(0) { acc, c -> acc or (1 shl (c - '0')) } }
            return Rule(mask(match.groupValues[1]), mask(match.groupValues[2]))
        }
    }
}

/**
 * Synchronous cellular automaton with double buffering.
 *
 * Every cell updates at once, so writing in place would corrupt the neighbor counts of cells
 * visited later in the same tick. Generations are computed into a second array, and the two
 * references swap at the end of the frame, so a step allocates nothing.
 */
class LifeGrid(
    val width: Int,
    val height: Int,
    val edge: Edge = Edge.DEAD,
    val rule: Rule = Rule.CONWAY,
) {
    init {
        require(width > 0 && height > 0) { "grid dimensions must be positive" }
    }

    private var curr = ByteArray(width * height)
    private var next = ByteArray(width * height)

    var generation = 0L
        private set

    operator fun get(x: Int, y: Int): Boolean = curr[y * width + x] != 0.toByte()

    operator fun set(x: Int, y: Int, alive: Boolean) {
        curr[y * width + x] = if (alive) 1 else 0
    }

    fun population(): Int = curr.sumOf { it.toInt() }

    private fun cell(x: Int, y: Int): Int {
        if (edge == Edge.TORUS) {
            return curr[Math.floorMod(y, height) * width + Math.floorMod(x, width)].toInt()
        }
        if (x < 0 || y < 0 || x >= width || y >= height) return 0
        return curr[y * width + x].toInt()
    }

    fun step() {
        val output = next
        for (y in 0 until height) {
            for (x in 0 until width) {
                val neighbors = cell(x - 1, y - 1) + cell(x, y - 1) + cell(x + 1, y - 1) +
                    cell(x - 1, y) + cell(x + 1, y) +
                    cell(x - 1, y + 1) + cell(x, y + 1) + cell(x + 1, y + 1)
                val mask = if (curr[y * width + x] != 0.toByte()) rule.survive else rule.birth
                output[y * width + x] = ((mask ushr neighbors) and 1).toByte()
            }
        }
        next = curr
        curr = output
        generation++
    }
}

/**
 * Conway's Life on packed 64-bit words, evaluating 64 cells per operation.
 *
 * Neighbor counts are computed with a bit-sliced carry-save adder across the eight neighbor
 * masks, so each word costs a fixed few dozen logic operations, whatever it contains.
 */
class BitLife(val width: Int, val height: Int, val edge: Edge = Edge.DEAD) {
    init {
        require(width > 0 && height > 0) { "grid dimensions must be positive" }
    }

    private val words = (width + 63) / 64
    private val lastWord = words - 1
    private val lastBit = (width - 1) % 64
    private val lastMask = if (width % 64 == 0) -1L else (1L shl (width % 64)) - 1
    private var curr = LongArray(words * height)
    private var next = LongArray(words * height)

    var generation = 0L
        private set

    operator fun get(x: Int, y: Int): Boolean =
        (curr[y * words + x / 64] ushr (x % 64)) and 1L != 0L

    operator fun set(x: Int, y: Int, alive: Boolean) {
        val i = y * words + x / 64
        val bit = 1L shl (x % 64)
        curr[i] = if (alive) curr[i] or bit else curr[i] and bit.inv()
    }

    fun population(): Long = curr.sumOf { java.lang.Long.bitCount(it).toLong() }

    fun step() {
        val src = curr
        val dst = next
        val torus = edge == Edge.TORUS
        for (y in 0 until height) {
            val above = when {
                y > 0 -> (y - 1) * words
                torus -> (height - 1) * words
                else -> -1
            }
            val here = y * words
            val below = when {
                y < height - 1 -> (y + 1) * words
                torus -> 0
                else -> -1
            }
            for (w in 0 until words) {
                val nc = if (above >= 0) src[above + w] else 0L
                val nl = if (above >= 0) leftOf(src, above, w, torus) else 0L
                val nr = if (above >= 0) rightOf(src, above, w, torus) else 0L
                val cc = src[here + w]
                val cl = leftOf(src, here, w, torus)
                val cr = rightOf(src, here, w, torus)
                val sc = if (below >= 0) src[below + w] else 0L
                val sl = if (below >= 0) leftOf(src, below, w, torus) else 0L
                val sr = if (below >= 0) rightOf(src, below, w, torus) else 0L

                // A bit-sliced adder over the eight neighbor masks.
                val s1 = nl xor nc
                val c1 = nl and nc
                val s2 = nr xor cl
                val c2 = nr and cl
                val s3 = cr xor sl
                val c3 = cr and sl
                val s4 = sc xor sr
                val c4 = sc and sr
                val s5 = s1 xor s2
                val c5 = s1 and s2
                val s6 = s3 xor s4
                val c6 = s3 and s4
                val ones = s5 xor s6
                val c7 = s5 and s6

                // The count is `ones + 2 * (set carries)`. Life needs a count of 2 or 3, which is
                // exactly one set carry, with `ones` choosing between survival and birth.
                var once = c1
                var twice = once and c2
                once = once or c2
                twice = twice or (once and c3)
                once = once or c3
                twice = twice or (once and c4)
                once = once or c4
                twice = twice or (once and c5)
                once = once or c5
                twice = twice or (once and c6)
                once = once or c6
                twice = twice or (once and c7)
                once = once or c7

                val mask = if (w == lastWord) lastMask else -1L
                dst[here + w] = once and twice.inv() and (ones or cc) and mask
            }
        }
        next = src
        curr = dst
        generation++
    }

    /** Word whose bit `i` holds the cell at `x - 1`. */
    private fun leftOf(src: LongArray, rowStart: Int, w: Int, torus: Boolean): Long {
        val carry = when {
            w > 0 -> src[rowStart + w - 1] ushr 63
            torus -> (src[rowStart + lastWord] ushr lastBit) and 1L
            else -> 0L
        }
        return (src[rowStart + w] shl 1) or carry
    }

    /** Word whose bit `i` holds the cell at `x + 1`. */
    private fun rightOf(src: LongArray, rowStart: Int, w: Int, torus: Boolean): Long {
        val carry = when {
            w < lastWord -> src[rowStart + w + 1] shl 63
            torus -> (src[rowStart] and 1L) shl lastBit
            else -> 0L
        }
        return (src[rowStart + w] ushr 1) or carry
    }
}

/**
 * Hashlife: memoized, hash-consed quadtree evolution of Conway's Life on an unbounded plane.
 *
 * Identical regions share one node, and a node's future is computed once. For repetitive
 * patterns this advances astronomically many generations, since the work tracks the number of
 * distinct regions rather than the number of cells or generations.
 */
class HashLife {
    class Node internal constructor(
        val level: Int,
        val nw: Node?,
        val ne: Node?,
        val sw: Node?,
        val se: Node?,
        /** Live cells, saturating at `Long.MAX_VALUE`. See [exactPopulation] for the true count. */
        val population: Long,
        internal val id: Int,
    ) {
        internal var fullResult: Node? = null

        /** Tracked separately, because emptiness decisions must never depend on a saturated count. */
        internal val isEmpty: Boolean get() = population == 0L
    }

    private data class Key(val nw: Int, val ne: Int, val sw: Int, val se: Int)
    private data class SlowKey(val node: Int, val exponent: Int)

    private var nextId = 2
    private val dead = Node(0, null, null, null, null, 0, 0)
    private val alive = Node(0, null, null, null, null, 1, 1)
    private val canonical = HashMap<Key, Node>()
    private val slowResults = HashMap<SlowKey, Node>()
    private val emptyByLevel = ArrayList<Node>().apply { add(dead) }

    private var root: Node = dead
    var generation: java.math.BigInteger = java.math.BigInteger.ZERO
        private set

    init {
        clear()
    }

    /** Removes all cells and resets the generation counter. */
    fun clear() {
        root = empty(3)
        generation = java.math.BigInteger.ZERO
    }

    private fun make(nw: Node, ne: Node, sw: Node, se: Node): Node {
        val key = Key(nw.id, ne.id, sw.id, se.id)
        return canonical.getOrPut(key) {
            Node(
                nw.level + 1, nw, ne, sw, se,
                saturatingAdd(
                    saturatingAdd(nw.population, ne.population),
                    saturatingAdd(sw.population, se.population),
                ),
                nextId++,
            )
        }
    }

    private fun saturatingAdd(a: Long, b: Long): Long {
        val sum = a + b
        return if (sum < 0) Long.MAX_VALUE else sum
    }

    private fun empty(level: Int): Node {
        while (emptyByLevel.size <= level) {
            val e = emptyByLevel.last()
            emptyByLevel.add(make(e, e, e, e))
        }
        return emptyByLevel[level]
    }

    /** Wraps a node in an empty border, doubling its size around the same center. */
    private fun expand(n: Node): Node {
        val e = empty(n.level - 1)
        return make(
            make(e, e, e, n.nw!!),
            make(e, e, n.ne!!, e),
            make(e, n.sw!!, e, e),
            make(n.se!!, e, e, e),
        )
    }

    /** Live cells, saturating at `Long.MAX_VALUE`. Large patterns can pass that within seconds. */
    val population: Long get() = root.population

    /** The exact number of live cells, however large. */
    val exactPopulation: java.math.BigInteger
        get() = exactCount(root, HashMap())

    private fun exactCount(n: Node, seen: HashMap<Int, java.math.BigInteger>): java.math.BigInteger {
        if (n.population != Long.MAX_VALUE) return java.math.BigInteger.valueOf(n.population)
        return seen.getOrPut(n.id) {
            exactCount(n.nw!!, seen)
                .add(exactCount(n.ne!!, seen))
                .add(exactCount(n.sw!!, seen))
                .add(exactCount(n.se!!, seen))
        }
    }

    /** Number of nodes held by the memo table, which is the true size of the computation. */
    val nodeCount: Int get() = canonical.size

    private fun halfWidth(n: Node): Long = 1L shl (n.level - 1)

    operator fun get(x: Long, y: Long): Boolean {
        // After a huge advance the root can outgrow 64-bit coordinates. Everything a Long can
        // address lies inside the center, so the outer levels can be skipped.
        var n = root
        while (n.level > MAX_ADDRESSABLE_LEVEL) n = center(n)
        var cx = x
        var cy = y
        val half = halfWidth(n)
        if (cx < -half || cy < -half || cx >= half || cy >= half) return false
        cx += half
        cy += half
        while (n.level > 0) {
            val mid = 1L shl (n.level - 1)
            val east = cx >= mid
            val south = cy >= mid
            n = if (south) { if (east) n.se!! else n.sw!! } else { if (east) n.ne!! else n.nw!! }
            if (east) cx -= mid
            if (south) cy -= mid
        }
        return n === alive
    }

    operator fun set(x: Long, y: Long, value: Boolean) {
        check(root.level <= MAX_ADDRESSABLE_LEVEL) {
            "the universe has grown beyond 64-bit coordinates and can no longer be edited"
        }
        val magnitude = maxOf(if (x < 0) -x else x + 1, if (y < 0) -y else y + 1)
        while (halfWidth(root) < magnitude) root = expand(root)
        val half = halfWidth(root)
        root = setIn(root, x + half, y + half, value)
    }

    private fun setIn(n: Node, x: Long, y: Long, value: Boolean): Node {
        if (n.level == 0) return if (value) alive else dead
        val mid = 1L shl (n.level - 1)
        val east = x >= mid
        val south = y >= mid
        val nx = if (east) x - mid else x
        val ny = if (south) y - mid else y
        return when {
            !east && !south -> make(setIn(n.nw!!, nx, ny, value), n.ne!!, n.sw!!, n.se!!)
            east && !south -> make(n.nw!!, setIn(n.ne!!, nx, ny, value), n.sw!!, n.se!!)
            !east -> make(n.nw!!, n.ne!!, setIn(n.sw!!, nx, ny, value), n.se!!)
            else -> make(n.nw!!, n.ne!!, n.sw!!, setIn(n.se!!, nx, ny, value))
        }
    }

    /** The central quarter of a node, with no time passing. */
    private fun center(n: Node): Node = make(n.nw!!.se!!, n.ne!!.sw!!, n.sw!!.ne!!, n.se!!.nw!!)

    private fun baseStep(n: Node): Node {
        // A level-2 node is 4x4. This computes its central 2x2 one generation ahead.
        val grid = IntArray(16)
        fun put(q: Node, ox: Int, oy: Int) {
            grid[oy * 4 + ox] = q.nw!!.population.toInt()
            grid[oy * 4 + ox + 1] = q.ne!!.population.toInt()
            grid[(oy + 1) * 4 + ox] = q.sw!!.population.toInt()
            grid[(oy + 1) * 4 + ox + 1] = q.se!!.population.toInt()
        }
        put(n.nw!!, 0, 0)
        put(n.ne!!, 2, 0)
        put(n.sw!!, 0, 2)
        put(n.se!!, 2, 2)
        fun cell(x: Int, y: Int): Node {
            var neighbors = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx != 0 || dy != 0) neighbors += grid[(y + dy) * 4 + x + dx]
                }
            }
            val live = grid[y * 4 + x] == 1
            return if (neighbors == 3 || (live && neighbors == 2)) alive else dead
        }
        return make(cell(1, 1), cell(2, 1), cell(1, 2), cell(2, 2))
    }

    /**
     * Returns the center of [n], advanced by `2^min(level - 2, exponent)` generations.
     *
     * Divides the node into nine overlapping children. In the full-speed case each child runs
     * forward once, and the combined results run forward again, for two half-steps in total.
     * Below full speed the first half-step only takes the children's centers.
     */
    private fun step(n: Node, exponent: Int): Node {
        val full = exponent >= n.level - 2
        if (full) {
            n.fullResult?.let { return it }
        } else {
            slowResults[SlowKey(n.id, exponent)]?.let { return it }
        }
        val result = if (n.isEmpty) {
            empty(n.level - 1)
        } else if (n.level == 2) {
            baseStep(n)
        } else {
            val nw = n.nw!!
            val ne = n.ne!!
            val sw = n.sw!!
            val se = n.se!!
            val advance: (Node) -> Node = { c -> if (full) step(c, exponent) else center(c) }
            val n00 = advance(nw)
            val n01 = advance(make(nw.ne!!, ne.nw!!, nw.se!!, ne.sw!!))
            val n02 = advance(ne)
            val n10 = advance(make(nw.sw!!, nw.se!!, sw.nw!!, sw.ne!!))
            val n11 = advance(make(nw.se!!, ne.sw!!, sw.ne!!, se.nw!!))
            val n12 = advance(make(ne.sw!!, ne.se!!, se.nw!!, se.ne!!))
            val n20 = advance(sw)
            val n21 = advance(make(sw.ne!!, se.nw!!, sw.se!!, se.sw!!))
            val n22 = advance(se)
            make(
                step(make(n00, n01, n10, n11), exponent),
                step(make(n01, n02, n11, n12), exponent),
                step(make(n10, n11, n20, n21), exponent),
                step(make(n11, n12, n21, n22), exponent),
            )
        }
        if (full) {
            n.fullResult = result
        } else {
            slowResults[SlowKey(n.id, exponent)] = result
        }
        return result
    }

    /** Advances by exactly `2^exponent` generations. */
    fun advancePowerOfTwo(exponent: Int) {
        require(exponent in 0..MAX_EXPONENT) { "exponent out of range" }
        // The border must be wide enough that nothing can leave the result, and the root must
        // be large enough to step this far in one call.
        while (root.level < exponent + 3 || !bordersEmpty(root)) root = expand(root)
        root = expand(root)
        root = step(root, exponent)
        generation = generation.add(java.math.BigInteger.ONE.shiftLeft(exponent))
    }

    /** Advances by an arbitrary number of generations, one set bit at a time. */
    fun advance(generations: Long) {
        require(generations >= 0) { "generations must not be negative" }
        var remaining = generations
        var exponent = 0
        while (remaining != 0L) {
            if (remaining and 1L != 0L) advancePowerOfTwo(exponent)
            remaining = remaining ushr 1
            exponent++
        }
    }

    /** Whether all live cells lie in the central quarter of a node. */
    private fun bordersEmpty(n: Node): Boolean {
        if (n.level < 3) return false
        val nw = n.nw!!
        val ne = n.ne!!
        val sw = n.sw!!
        val se = n.se!!
        return nw.nw!!.isEmpty && nw.ne!!.isEmpty && nw.sw!!.isEmpty &&
            ne.nw!!.isEmpty && ne.ne!!.isEmpty && ne.se!!.isEmpty &&
            sw.nw!!.isEmpty && sw.sw!!.isEmpty && sw.se!!.isEmpty &&
            se.ne!!.isEmpty && se.sw!!.isEmpty && se.se!!.isEmpty
    }

    /** Drops every memoized result not reachable from the current pattern. */
    fun compact() {
        val old = root
        canonical.clear()
        slowResults.clear()
        emptyByLevel.clear()
        emptyByLevel.add(dead)
        root = rebuild(old, HashMap())
    }

    private fun rebuild(n: Node, seen: HashMap<Int, Node>): Node {
        if (n.level == 0) return n
        return seen.getOrPut(n.id) {
            make(
                rebuild(n.nw!!, seen),
                rebuild(n.ne!!, seen),
                rebuild(n.sw!!, seen),
                rebuild(n.se!!, seen),
            )
        }
    }

    private companion object {
        const val MAX_ADDRESSABLE_LEVEL = 62

        /** Recursion depth tracks the root level, so this bounds stack use. */
        const val MAX_EXPONENT = 1000
    }
}

/** Reads the standard RLE pattern format, returning the coordinates of live cells. */
object Rle {
    fun parse(text: String): List<Pair<Int, Int>> {
        val body = text.lineSequence()
            .map { it.trim() }
            .filterNot { it.startsWith("#") || it.startsWith("x") }
            .joinToString("")
        val cells = ArrayList<Pair<Int, Int>>()
        var x = 0
        var y = 0
        var run = 0
        for (c in body) {
            when (c) {
                in '0'..'9' -> {
                    run = run * 10 + (c - '0')
                    require(run <= 1_000_000) { "run length is too large" }
                }
                'b', '.' -> {
                    x += maxOf(run, 1)
                    run = 0
                }
                'o', 'A' -> {
                    repeat(maxOf(run, 1)) { cells.add(x++ to y) }
                    run = 0
                }
                '$' -> {
                    y += maxOf(run, 1)
                    x = 0
                    run = 0
                }
                '!' -> return cells
                else -> throw IllegalArgumentException("unexpected RLE character '$c'")
            }
        }
        return cells
    }
}

/** Well-known patterns. */
object Patterns {
    const val GLIDER = "bob$2bo$3o!"
    const val BLINKER = "3o!"
    const val BLOCK = "2o$2o!"
    const val R_PENTOMINO = "b2o\$2o\$bo!"
    const val PULSAR = "2b3o3b3o2\$o4bobo4bo\$o4bobo4bo\$o4bobo4bo\$2b3o3b3o2\$2b3o3b3o\$" +
        "o4bobo4bo\$o4bobo4bo\$o4bobo4bo2\$2b3o3b3o!"
    const val GOSPER_GUN = "24bo\$22bobo\$12b2o6b2o12b2o\$11bo3bo4b2o12b2o\$2o8bo5bo3b2o\$" +
        "2o8bo3bob2o4bobo\$10bo5bo7bo\$11bo3bo\$12b2o!"
}
