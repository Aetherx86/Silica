package dev.aetherx86.silica

/** How a grid treats cells beyond its edge. */
enum class Edge { DEAD, TORUS }

/**
 * A life-like rule such as `B3/S23`.
 *
 * Bit `n` of [birth] means a dead cell with `n` live neighbors is born, and bit `n` of
 * [survive] means a live cell with `n` live neighbors stays alive. Neighbor counts run from 0
 * to 8, so both masks fit in nine bits.
 */
data class Rule(val birth: Int, val survive: Int) {
    init {
        require(birth in 0..ALL_COUNTS && survive in 0..ALL_COUNTS) {
            "rule masks must only use bits 0 to 8"
        }
    }

    /** Canonical `B/S` notation with digits in ascending order, such as `B36/S23`. */
    val notation: String
        get() = "B" + digits(birth) + "/S" + digits(survive)

    /** Whether a dead cell with [neighbors] live neighbors is born. */
    fun born(neighbors: Int): Boolean = (birth ushr neighbors) and 1 != 0

    /** Whether a live cell with [neighbors] live neighbors survives. */
    fun survives(neighbors: Int): Boolean = (survive ushr neighbors) and 1 != 0

    companion object {
        private const val ALL_COUNTS = 0x1FF

        val CONWAY = Rule(1 shl 3, (1 shl 2) or (1 shl 3))

        private val BS = Regex("""^[Bb]([0-8]*)/[Ss]([0-8]*)$""")
        private val LEGACY = Regex("""^([0-8]*)/([0-8]*)$""")

        /**
         * Parses `B3/S23` notation in either case, or the older `S/B` form `23/3` that many RLE
         * files still use. Digits may appear in any order.
         */
        fun parse(text: String): Rule {
            val trimmed = text.trim()
            val mask = { digits: String -> digits.fold(0) { acc, c -> acc or (1 shl (c - '0')) } }
            BS.matchEntire(trimmed)?.let {
                return Rule(mask(it.groupValues[1]), mask(it.groupValues[2]))
            }
            LEGACY.matchEntire(trimmed)?.let {
                return Rule(mask(it.groupValues[2]), mask(it.groupValues[1]))
            }
            throw IllegalArgumentException("not a life-like rule: $text")
        }

        private fun digits(mask: Int): String = (0..8).filter { (mask ushr it) and 1 != 0 }.joinToString("")
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
 * Life on packed 64-bit words, evaluating 64 cells per operation.
 *
 * Neighbor counts are computed with a bit-sliced carry-save adder across the eight neighbor
 * masks, so each word costs a fixed few dozen logic operations, whatever it contains. Conway's
 * rule has a dedicated shortcut. Any other life-like [rule] gets a full four-bit count, from
 * which every neighbor count the rule names is selected as a mask.
 */
class BitLife @JvmOverloads constructor(
    val width: Int,
    val height: Int,
    val edge: Edge = Edge.DEAD,
    val rule: Rule = Rule.CONWAY,
) {
    init {
        require(width > 0 && height > 0) { "grid dimensions must be positive" }
    }

    private val conway = rule == Rule.CONWAY

    /** Neighbor counts that matter to the rule, so the generic path skips the rest. */
    private val activeCounts = (0..8).filter { rule.born(it) || rule.survives(it) }.toIntArray()

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

                val mask = if (w == lastWord) lastMask else -1L
                if (!conway) {
                    dst[here + w] = applyRule(nl, nc, nr, cl, cr, sl, sc, sr, cc) and mask
                    continue
                }

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

                dst[here + w] = once and twice.inv() and (ones or cc) and mask
            }
        }
        next = src
        curr = dst
        generation++
    }

    /**
     * Evaluates an arbitrary life-like rule for 64 cells. Full adders reduce the eight neighbor
     * masks to the four bit planes of the count, `b0 + 2 b1 + 4 b2 + 8 b3`, and each count the
     * rule mentions becomes a mask of the cells with exactly that many neighbors.
     */
    private fun applyRule(
        nl: Long, nc: Long, nr: Long,
        cl: Long, cr: Long,
        sl: Long, sc: Long, sr: Long,
        alive: Long,
    ): Long {
        val sTop = nl xor nc xor nr
        val kTop = (nl and nc) or (nr and (nl xor nc))
        val sMid = cl xor cr xor sl
        val kMid = (cl and cr) or (sl and (cl xor cr))
        val sLow = sc xor sr
        val kLow = sc and sr
        val b0 = sTop xor sMid xor sLow
        val k1 = (sTop and sMid) or (sLow and (sTop xor sMid))
        val t0 = kTop xor kMid xor kLow
        val t1 = (kTop and kMid) or (kLow and (kTop xor kMid))
        val b1 = t0 xor k1
        val t2 = t0 and k1
        val b2 = t1 xor t2
        val b3 = t1 and t2

        var born = 0L
        var kept = 0L
        for (n in activeCounts) {
            val exactly = (if (n and 1 != 0) b0 else b0.inv()) and
                (if (n and 2 != 0) b1 else b1.inv()) and
                (if (n and 4 != 0) b2 else b2.inv()) and
                (if (n and 8 != 0) b3 else b3.inv())
            if (rule.born(n)) born = born or exactly
            if (rule.survives(n)) kept = kept or exactly
        }
        return (born and alive.inv()) or (kept and alive)
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

/** An inclusive rectangle of cells, with coordinates that may exceed 64 bits. */
data class Bounds(
    val minX: java.math.BigInteger,
    val minY: java.math.BigInteger,
    val maxX: java.math.BigInteger,
    val maxY: java.math.BigInteger,
) {
    val width: java.math.BigInteger get() = maxX.subtract(minX).add(java.math.BigInteger.ONE)
    val height: java.math.BigInteger get() = maxY.subtract(minY).add(java.math.BigInteger.ONE)
}

/**
 * Hashlife: memoized, hash-consed quadtree evolution of a life-like [rule] on an unbounded plane.
 *
 * Identical regions share one node, and a node's future is computed once. For repetitive
 * patterns this advances astronomically many generations, since the work tracks the number of
 * distinct regions rather than the number of cells or generations.
 *
 * Rules with `B0` are rejected: under them the empty background itself comes alive, which an
 * unbounded plane of dead cells cannot represent.
 */
class HashLife @JvmOverloads constructor(val rule: Rule = Rule.CONWAY) {
    init {
        require(!rule.born(0)) { "B0 rules are not supported on an unbounded plane: ${rule.notation}" }
    }

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
            return if (if (live) rule.survives(neighbors) else rule.born(neighbors)) alive else dead
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

    /**
     * Advances by any number of generations, including counts far beyond 64 bits, one set bit
     * at a time.
     */
    fun advance(generations: java.math.BigInteger) {
        require(generations.signum() >= 0) { "generations must not be negative" }
        require(generations.bitLength() <= MAX_EXPONENT + 1) { "generations out of range" }
        for (exponent in 0 until generations.bitLength()) {
            if (generations.testBit(exponent)) advancePowerOfTwo(exponent)
        }
    }

    /** The smallest rectangle holding every live cell, or `null` when the plane is empty. */
    fun boundingBox(): Bounds? {
        if (root.isEmpty) return null
        val local = localBounds(root, HashMap()) ?: return null
        val half = java.math.BigInteger.ONE.shiftLeft(root.level - 1)
        return Bounds(
            local[0].subtract(half),
            local[1].subtract(half),
            local[2].subtract(half),
            local[3].subtract(half),
        )
    }

    /** Bounds of the live cells relative to a node's top-left corner, memoized per node. */
    private fun localBounds(
        n: Node,
        seen: HashMap<Int, Array<java.math.BigInteger>?>,
    ): Array<java.math.BigInteger>? {
        if (n.isEmpty) return null
        if (n.level == 0) return Array(4) { java.math.BigInteger.ZERO }
        if (seen.containsKey(n.id)) return seen[n.id]
        val h = java.math.BigInteger.ONE.shiftLeft(n.level - 1)
        var result: Array<java.math.BigInteger>? = null
        val quadrants = arrayOf(n.nw!!, n.ne!!, n.sw!!, n.se!!)
        for (q in 0 until 4) {
            val child = localBounds(quadrants[q], seen) ?: continue
            val dx = if (q and 1 != 0) h else java.math.BigInteger.ZERO
            val dy = if (q and 2 != 0) h else java.math.BigInteger.ZERO
            val shifted = arrayOf(child[0].add(dx), child[1].add(dy), child[2].add(dx), child[3].add(dy))
            result = if (result == null) {
                shifted
            } else {
                arrayOf(
                    result[0].min(shifted[0]),
                    result[1].min(shifted[1]),
                    result[2].max(shifted[2]),
                    result[3].max(shifted[3]),
                )
            }
        }
        seen[n.id] = result
        return result
    }

    /** The exact number of live cells in an inclusive rectangle. */
    fun populationIn(minX: Long, minY: Long, maxX: Long, maxY: Long): java.math.BigInteger =
        populationIn(
            java.math.BigInteger.valueOf(minX),
            java.math.BigInteger.valueOf(minY),
            java.math.BigInteger.valueOf(maxX),
            java.math.BigInteger.valueOf(maxY),
        )

    /**
     * The exact number of live cells in an inclusive rectangle of any size. Nodes wholly inside
     * the rectangle are counted from their stored population and empty nodes are skipped, so
     * the work follows the rectangle's edges rather than its area.
     */
    fun populationIn(
        minX: java.math.BigInteger,
        minY: java.math.BigInteger,
        maxX: java.math.BigInteger,
        maxY: java.math.BigInteger,
    ): java.math.BigInteger {
        if (minX > maxX || minY > maxY) return java.math.BigInteger.ZERO
        val half = java.math.BigInteger.ONE.shiftLeft(root.level - 1).negate()
        return countIn(root, half, half, Bounds(minX, minY, maxX, maxY), HashMap())
    }

    private fun countIn(
        n: Node,
        ox: java.math.BigInteger,
        oy: java.math.BigInteger,
        area: Bounds,
        seen: HashMap<Int, java.math.BigInteger>,
    ): java.math.BigInteger {
        if (n.isEmpty) return java.math.BigInteger.ZERO
        val last = java.math.BigInteger.ONE.shiftLeft(n.level).subtract(java.math.BigInteger.ONE)
        val ex = ox.add(last)
        val ey = oy.add(last)
        if (ex < area.minX || ox > area.maxX || ey < area.minY || oy > area.maxY) {
            return java.math.BigInteger.ZERO
        }
        if (ox >= area.minX && ex <= area.maxX && oy >= area.minY && ey <= area.maxY) {
            return exactCount(n, seen)
        }
        val h = java.math.BigInteger.ONE.shiftLeft(n.level - 1)
        val mx = ox.add(h)
        val my = oy.add(h)
        return countIn(n.nw!!, ox, oy, area, seen)
            .add(countIn(n.ne!!, mx, oy, area, seen))
            .add(countIn(n.sw!!, ox, my, area, seen))
            .add(countIn(n.se!!, mx, my, area, seen))
    }

    /**
     * Every live cell, in row-major order. Fails rather than exhausting memory when there are
     * more than [limit] cells, or when the pattern has spread beyond 64-bit coordinates.
     */
    @JvmOverloads
    fun cells(limit: Int = DEFAULT_CELL_LIMIT): List<Pair<Long, Long>> {
        val bounds = boundingBox() ?: return emptyList()
        check(fitsInLong(bounds)) { "the pattern extends beyond 64-bit coordinates" }
        val total = exactPopulation
        check(total <= java.math.BigInteger.valueOf(limit.toLong())) {
            "the pattern has $total live cells, more than the limit of $limit"
        }
        val out = ArrayList<Pair<Long, Long>>(total.toInt())
        val half = java.math.BigInteger.ONE.shiftLeft(root.level - 1).negate()
        collect(root, half, half, out)
        out.sortWith(compareBy<Pair<Long, Long>> { it.second }.thenBy { it.first })
        return out
    }

    private fun collect(n: Node, ox: java.math.BigInteger, oy: java.math.BigInteger, out: MutableList<Pair<Long, Long>>) {
        if (n.isEmpty) return
        if (n.level == 0) {
            out.add(ox.longValueExact() to oy.longValueExact())
            return
        }
        val h = java.math.BigInteger.ONE.shiftLeft(n.level - 1)
        collect(n.nw!!, ox, oy, out)
        collect(n.ne!!, ox.add(h), oy, out)
        collect(n.sw!!, ox, oy.add(h), out)
        collect(n.se!!, ox.add(h), oy.add(h), out)
    }

    private fun fitsInLong(b: Bounds): Boolean {
        val lo = java.math.BigInteger.valueOf(Long.MIN_VALUE)
        val hi = java.math.BigInteger.valueOf(Long.MAX_VALUE)
        return b.minX >= lo && b.minY >= lo && b.maxX <= hi && b.maxY <= hi
    }

    /**
     * The live cells as RLE, translated so the bounding box starts at the origin, with this
     * engine's rule in the header. See [cells] for the limits.
     */
    @JvmOverloads
    fun toRle(limit: Int = DEFAULT_CELL_LIMIT): String {
        val bounds = boundingBox() ?: return Rle.write(emptyList(), rule)
        val maxSide = java.math.BigInteger.valueOf(Rle.MAX_COORDINATE.toLong() + 1)
        check(bounds.width <= maxSide && bounds.height <= maxSide) { "the pattern is too large for RLE" }
        val live = cells(limit)
        val minX = bounds.minX.toLong()
        val minY = bounds.minY.toLong()
        return Rle.write(live.map { (x, y) -> (x - minX).toInt() to (y - minY).toInt() }, rule)
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

        const val DEFAULT_CELL_LIMIT = 1_000_000
    }
}

/** A pattern read from RLE: its live cells plus whatever the header and comments declared. */
data class RlePattern(
    val cells: List<Pair<Int, Int>>,
    /** Declared width from the `x =` header field, or the measured width when there is none. */
    val width: Int,
    /** Declared height from the `y =` header field, or the measured height when there is none. */
    val height: Int,
    /** The header's `rule =` field, or `null` when the file does not name one. */
    val rule: Rule?,
    /** The `#N` line, if any. */
    val name: String?,
    /** Text of the `#C` and `#c` lines, in order. */
    val comments: List<String>,
)

/** Reads and writes the standard RLE pattern format. */
object Rle {
    /** Largest coordinate a pattern may reach, which keeps every size an `Int`. */
    const val MAX_COORDINATE = 1 shl 30

    /** Default bound on live cells read from one file, so hostile input cannot exhaust memory. */
    const val DEFAULT_MAX_CELLS = 10_000_000

    /** Longest line [write] produces, as the format recommends. */
    const val LINE_LENGTH = 70

    /**
     * Reads only the coordinates of live cells, skipping the header without interpreting it.
     * See [read] for the header and comments.
     */
    @JvmOverloads
    fun parse(text: String, maxCells: Int = DEFAULT_MAX_CELLS): List<Pair<Int, Int>> =
        decode(text, maxCells, interpretHeader = false).cells

    /**
     * Reads a pattern with its header. Whitespace between tokens is ignored, a `:` suffix on the
     * rule (a bounded-grid topology) is ignored, and anything after `!` is ignored. Run lengths,
     * coordinates and the total number of live cells are bounded, so a hostile file fails with
     * an [IllegalArgumentException] rather than exhausting memory.
     */
    @JvmOverloads
    fun read(text: String, maxCells: Int = DEFAULT_MAX_CELLS): RlePattern = decode(text, maxCells, interpretHeader = true)

    private fun decode(text: String, maxCells: Int, interpretHeader: Boolean): RlePattern {
        require(maxCells >= 0) { "maxCells must not be negative" }
        var declaredWidth: Int? = null
        var declaredHeight: Int? = null
        var rule: Rule? = null
        var name: String? = null
        val comments = ArrayList<String>()
        val body = StringBuilder()
        var headerSeen = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#") -> {
                    val kind = line.getOrNull(1)
                    val rest = line.drop(2).trim()
                    when (kind) {
                        'N' -> name = rest
                        'C', 'c' -> comments.add(rest)
                    }
                }
                line.startsWith("x") -> {
                    if (headerSeen || !interpretHeader) continue
                    headerSeen = true
                    // A comma inside a field's value, as in `rule = B3/S23:T64,64`, continues that field.
                    val fields = ArrayList<String>()
                    for (piece in line.split(',')) {
                        if (piece.contains('=') || fields.isEmpty()) fields.add(piece) else fields[fields.size - 1] += ",$piece"
                    }
                    for (field in fields) {
                        val parts = field.split('=', limit = 2)
                        require(parts.size == 2) { "malformed RLE header field: ${field.trim()}" }
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        when (key) {
                            "x" -> declaredWidth = dimension(value, "x")
                            "y" -> declaredHeight = dimension(value, "y")
                            "rule" -> rule = Rule.parse(value.substringBefore(':'))
                        }
                    }
                }
                else -> body.append(line)
            }
        }

        val cells = ArrayList<Pair<Int, Int>>()
        var x = 0
        var y = 0
        var run = 0
        var maxX = -1
        for (c in body) {
            when (c) {
                in '0'..'9' -> {
                    run = run * 10 + (c - '0')
                    require(run <= 1_000_000) { "run length is too large" }
                }
                'b', '.' -> {
                    x += maxOf(run, 1)
                    require(x <= MAX_COORDINATE) { "pattern is wider than $MAX_COORDINATE cells" }
                    run = 0
                }
                'o', 'A' -> {
                    val count = maxOf(run, 1)
                    require(x + count <= MAX_COORDINATE) { "pattern is wider than $MAX_COORDINATE cells" }
                    require(cells.size.toLong() + count <= maxCells) { "pattern has more than $maxCells live cells" }
                    repeat(count) { cells.add(x++ to y) }
                    maxX = maxOf(maxX, x - 1)
                    run = 0
                }
                '$' -> {
                    y += maxOf(run, 1)
                    require(y <= MAX_COORDINATE) { "pattern is taller than $MAX_COORDINATE cells" }
                    x = 0
                    run = 0
                }
                ' ', '\t' -> {}
                '!' -> break
                else -> throw IllegalArgumentException("unexpected RLE character '$c'")
            }
        }
        val measuredHeight = if (cells.isEmpty()) 0 else cells.last().second + 1
        return RlePattern(
            cells,
            declaredWidth ?: (maxX + 1),
            declaredHeight ?: measuredHeight,
            rule,
            name,
            comments,
        )
    }

    private fun dimension(value: String, key: String): Int {
        val n = value.toIntOrNull()
        require(n != null && n in 0..MAX_COORDINATE) { "RLE header $key must be a size from 0 to $MAX_COORDINATE: $value" }
        return n
    }

    /**
     * Writes live cells as RLE. The pattern is translated so its bounding box starts at the
     * origin, duplicate cells are merged, runs of blank rows collapse into one `n$` token, and
     * lines wrap at [LINE_LENGTH] characters without splitting a token.
     */
    @JvmOverloads
    fun write(
        cells: Collection<Pair<Int, Int>>,
        rule: Rule = Rule.CONWAY,
        comments: List<String> = emptyList(),
    ): String {
        val out = StringBuilder()
        for (comment in comments) {
            for (line in comment.lines()) out.append("#C ").append(line).append('\n')
        }
        if (cells.isEmpty()) {
            out.append("x = 0, y = 0, rule = ").append(rule.notation).append("\n!\n")
            return out.toString()
        }
        val minX = cells.minOf { it.first }
        val minY = cells.minOf { it.second }
        val maxX = cells.maxOf { it.first }
        val maxY = cells.maxOf { it.second }
        require(maxX.toLong() - minX <= MAX_COORDINATE && maxY.toLong() - minY <= MAX_COORDINATE) {
            "pattern is larger than $MAX_COORDINATE cells across"
        }
        out.append("x = ").append(maxX - minX + 1)
            .append(", y = ").append(maxY - minY + 1)
            .append(", rule = ").append(rule.notation).append('\n')

        val sorted = cells.asSequence()
            .map { (x, y) -> (x - minX) to (y - minY) }
            .distinct()
            .sortedWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
            .toList()
        val line = StringBuilder()
        fun emit(count: Int, tag: Char) {
            val token = if (count == 1) tag.toString() else "$count$tag"
            if (line.length + token.length > LINE_LENGTH) {
                out.append(line).append('\n')
                line.setLength(0)
            }
            line.append(token)
        }
        var row = 0
        var column = 0
        var i = 0
        while (i < sorted.size) {
            val (x, y) = sorted[i]
            if (y > row) {
                emit(y - row, '$')
                row = y
                column = 0
            }
            if (x > column) emit(x - column, 'b')
            var length = 1
            while (i + length < sorted.size && sorted[i + length] == (x + length to y)) length++
            emit(length, 'o')
            column = x + length
            i += length
        }
        emit(1, '!')
        out.append(line).append('\n')
        return out.toString()
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
    /** A methuselah that settles at generation 5206 with 633 cells, 13 of them escaping gliders. */
    const val ACORN = "bo\$3bo\$2o2b3o!"

    /** A methuselah that vanishes entirely at generation 130. */
    const val DIEHARD = "6bo\$2o\$bo3b3o!"

    const val GOSPER_GUN = "24bo\$22bobo\$12b2o6b2o12b2o\$11bo3bo4b2o12b2o\$2o8bo5bo3b2o\$" +
        "2o8bo3bob2o4bobo\$10bo5bo7bo\$11bo3bo\$12b2o!"
}
