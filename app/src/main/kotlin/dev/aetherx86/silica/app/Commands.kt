package dev.aetherx86.silica.app

import dev.aetherx86.silica.BitLife
import dev.aetherx86.silica.Edge
import dev.aetherx86.silica.HashLife
import dev.aetherx86.silica.LifeGrid
import dev.aetherx86.silica.Patterns
import dev.aetherx86.silica.Rle
import dev.aetherx86.silica.RlePattern
import dev.aetherx86.silica.Rule
import dev.aetherx86.silica.SandWorld
import java.awt.image.BufferedImage
import java.io.File
import java.io.PrintStream
import java.math.BigInteger
import javax.imageio.ImageIO

/** Parsed `--key value` options and bare `--flag` switches, checked against what a command accepts. */
internal class Options private constructor(
    private val values: Map<String, String>,
    private val flags: Set<String>,
) {
    operator fun get(key: String): String? = values[key]

    fun flag(key: String): Boolean = key in flags

    fun int(key: String, default: Int, range: IntRange = 1..Int.MAX_VALUE): Int {
        val text = values[key] ?: return default
        val n = text.toIntOrNull()
        require(n != null && n in range) { "--$key must be a whole number from ${range.first} to ${range.last}: $text" }
        return n
    }

    fun bigInteger(key: String): BigInteger? {
        val text = values[key] ?: return null
        val n = text.toBigIntegerOrNull()
        require(n != null && n.signum() >= 0) { "--$key must be a non-negative whole number: $text" }
        return n
    }

    companion object {
        fun parse(args: List<String>, valued: Set<String>, switches: Set<String> = emptySet()): Options {
            val values = HashMap<String, String>()
            val flags = HashSet<String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                require(arg.startsWith("--")) { "expected an option, got $arg" }
                val key = arg.removePrefix("--")
                when (key) {
                    in switches -> {
                        flags.add(key)
                        i += 1
                    }
                    in valued -> {
                        require(i + 1 < args.size) { "--$key needs a value" }
                        values[key] = args[i + 1]
                        i += 2
                    }
                    else -> throw IllegalArgumentException("unknown option --$key")
                }
            }
            return Options(values, flags)
        }
    }
}

internal fun patternNamed(name: String): String = when (name) {
    "glider" -> Patterns.GLIDER
    "pulsar" -> Patterns.PULSAR
    "gosper" -> Patterns.GOSPER_GUN
    "rpentomino" -> Patterns.R_PENTOMINO
    "acorn" -> Patterns.ACORN
    "diehard" -> Patterns.DIEHARD
    else -> throw IllegalArgumentException("unknown pattern: $name")
}

/** Largest RLE file the command line will read. */
private const val MAX_RLE_BYTES = 64L shl 20

/** Loads `--rle FILE` or `--pattern NAME`, defaulting to the Gosper gun. */
internal fun loadPattern(options: Options): RlePattern {
    val path = options["rle"]
    val name = options["pattern"]
    require(path == null || name == null) { "use either --pattern or --rle, not both" }
    if (path == null) return Rle.read(patternNamed(name ?: "gosper"))
    val file = File(path)
    require(file.isFile) { "no such file: $path" }
    require(file.length() <= MAX_RLE_BYTES) { "RLE file is larger than $MAX_RLE_BYTES bytes: $path" }
    return Rle.read(file.readText())
}

/** `--rule` wins over the file's own rule, which wins over Conway's. */
internal fun ruleFor(options: Options, pattern: RlePattern): Rule =
    options["rule"]?.let(Rule::parse) ?: pattern.rule ?: Rule.CONWAY

private fun writeText(path: String, text: String) {
    File(path).writeText(text)
}

internal fun runSand(options: Options, out: PrintStream) {
    val frames = options.int("frames", 1000, 0..Int.MAX_VALUE)
    val world = SandWorld(512, 288, seed = 1)
    demoScene(world)
    val started = System.nanoTime()
    repeat(frames) { world.step() }
    val elapsed = (System.nanoTime() - started) / 1e9
    val possible = frames.toLong() * world.width * world.height
    options["png"]?.let { path ->
        val image = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_RGB)
        render(world, image)
        ImageIO.write(image, "png", File(path))
    }
    if (options.flag("json")) {
        out.println(
            Json.write(
                linkedMapOf(
                    "command" to "sand",
                    "width" to world.width,
                    "height" to world.height,
                    "frames" to frames,
                    "seconds" to elapsed,
                    "framesPerSecond" to frames / elapsed,
                    "cellsVisited" to world.totalCellsVisited,
                    "cellsPossible" to possible,
                    "png" to options["png"],
                ),
            ),
        )
        return
    }
    out.println("%d frames in %.3f s (%.0f frames/s)".format(frames, elapsed, frames / elapsed))
    out.println("cells visited: %d of %d".format(world.totalCellsVisited, possible))
    options["png"]?.let { out.println("wrote $it") }
}

internal fun runLife(options: Options, out: PrintStream) {
    val size = options.int("size", 1024)
    val generations = options.int("generations", 2000, 0..Int.MAX_VALUE)
    val edge = when (val name = options["edge"] ?: "torus") {
        "torus" -> Edge.TORUS
        "dead" -> Edge.DEAD
        else -> throw IllegalArgumentException("--edge must be torus or dead: $name")
    }
    val pattern = loadPattern(options)
    val rule = ruleFor(options, pattern)
    val offset = size / 4
    val needed = maxOf(
        pattern.cells.maxOfOrNull { it.first } ?: 0,
        pattern.cells.maxOfOrNull { it.second } ?: 0,
    ).toLong() + offset
    require(needed < size) { "the pattern does not fit in a $size x $size grid; raise --size" }
    val grid = BitLife(size, size, edge, rule)
    for ((x, y) in pattern.cells) grid[x + offset, y + offset] = true
    val started = System.nanoTime()
    repeat(generations) { grid.step() }
    val elapsed = (System.nanoTime() - started) / 1e9
    val cellsPerSecond = generations.toDouble() * size * size / elapsed
    options["png"]?.let { path ->
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) for (x in 0 until size) image.setRGB(x, y, if (grid[x, y]) 0xE8E8F0 else 0x0E0E14)
        ImageIO.write(image, "png", File(path))
    }
    options["out"]?.let { path ->
        val live = ArrayList<Pair<Int, Int>>()
        for (y in 0 until size) for (x in 0 until size) if (grid[x, y]) live.add(x to y)
        writeText(path, Rle.write(live, rule))
    }
    if (options.flag("json")) {
        out.println(
            Json.write(
                linkedMapOf(
                    "command" to "life",
                    "rule" to rule.notation,
                    "size" to size,
                    "edge" to edge.name.lowercase(),
                    "generations" to generations,
                    "seconds" to elapsed,
                    "cellsPerSecond" to cellsPerSecond,
                    "population" to grid.population(),
                    "png" to options["png"],
                    "out" to options["out"],
                ),
            ),
        )
        return
    }
    out.println("rule: ${rule.notation}")
    out.println("%d generations in %.3f s (%.2e cells/s)".format(generations, elapsed, cellsPerSecond))
    out.println("population: ${grid.population()}")
    options["png"]?.let { out.println("wrote $it") }
    options["out"]?.let { out.println("wrote $it") }
}

/** Upper bound on `--every` samples, so a tiny interval cannot flood the output. */
internal const val MAX_SAMPLES = 10_000

internal fun runHashLife(options: Options, out: PrintStream) {
    val exponentText = options["exponent"]
    val requested = options.bigInteger("generations")
    require(exponentText == null || requested == null) { "use either --exponent or --generations, not both" }
    val exponent = if (requested == null) options.int("exponent", 100, 0..1000) else null
    val total = requested ?: BigInteger.ONE.shiftLeft(exponent!!)
    require(total.bitLength() <= 1001) { "--generations is too large" }
    val every = options.bigInteger("every")
    if (every != null) {
        require(every.signum() > 0) { "--every must be positive" }
        val samples = total.add(every).subtract(BigInteger.ONE).divide(every)
        require(samples <= BigInteger.valueOf(MAX_SAMPLES.toLong())) {
            "--every would take $samples samples; the limit is $MAX_SAMPLES"
        }
    }

    val pattern = loadPattern(options)
    val rule = ruleFor(options, pattern)
    val hash = HashLife(rule)
    for ((x, y) in pattern.cells) hash[x.toLong(), y.toLong()] = true

    val samples = ArrayList<Pair<BigInteger, BigInteger>>()
    val started = System.nanoTime()
    if (every == null) {
        if (exponent != null) hash.advancePowerOfTwo(exponent) else hash.advance(total)
    } else {
        var done = BigInteger.ZERO
        while (done < total) {
            val step = every.min(total.subtract(done))
            hash.advance(step)
            done = done.add(step)
            samples.add(hash.generation to hash.exactPopulation)
        }
    }
    val elapsed = (System.nanoTime() - started) / 1e9
    val box = hash.boundingBox()
    options["out"]?.let { path -> writeText(path, hash.toRle()) }

    if (options.flag("json")) {
        // Generations and populations are strings, since they routinely exceed 2^53.
        out.println(
            Json.write(
                linkedMapOf(
                    "command" to "hashlife",
                    "rule" to rule.notation,
                    "generation" to hash.generation.toString(),
                    "population" to hash.exactPopulation.toString(),
                    "nodes" to hash.nodeCount,
                    "seconds" to elapsed,
                    "boundingBox" to box?.let {
                        linkedMapOf(
                            "minX" to it.minX.toString(),
                            "minY" to it.minY.toString(),
                            "maxX" to it.maxX.toString(),
                            "maxY" to it.maxY.toString(),
                        )
                    },
                    "samples" to samples.map { (g, p) -> linkedMapOf("generation" to g.toString(), "population" to p.toString()) },
                    "out" to options["out"],
                ),
            ),
        )
        return
    }
    out.println("rule: ${rule.notation}")
    for ((g, p) in samples) out.println("generation $g: population $p")
    val what = if (exponent != null) "2^$exponent" else total.toString()
    out.println("advanced $what generations in %.3f s".format(elapsed))
    out.println("generation: ${hash.generation}")
    out.println("population: ${hash.exactPopulation}")
    out.println("distinct nodes: ${hash.nodeCount}")
    out.println(
        if (box == null) "bounding box: empty" else "bounding box: (${box.minX}, ${box.minY}) to (${box.maxX}, ${box.maxY})",
    )
    options["out"]?.let { out.println("wrote $it") }
}

/** Prints a pattern as canonical RLE, optionally under a different rule. */
internal fun runRle(options: Options, out: PrintStream) {
    val pattern = loadPattern(options)
    val comments = listOfNotNull(pattern.name) + pattern.comments
    out.print(Rle.write(pattern.cells, ruleFor(options, pattern), comments))
}

internal data class BenchResult(val name: String, val work: String, val seconds: Double, val rate: Double, val unit: String)

/** Times a fixed set of workloads, one per engine, after one untimed warm-up pass of each. */
internal fun benchmark(quick: Boolean): List<BenchResult> {
    val lifeSize = if (quick) 128 else 1024
    val lifeGenerations = if (quick) 40 else 500
    val referenceSize = if (quick) 64 else 256
    val referenceGenerations = if (quick) 20 else 100
    val hashExponent = if (quick) 20 else 100
    val sandWidth = if (quick) 96 else 512
    val sandHeight = if (quick) 54 else 288
    val sandFrames = if (quick) 50 else 700

    fun soup(size: Int, set: (Int, Int) -> Unit) {
        val random = java.util.Random(1)
        for (y in 0 until size) for (x in 0 until size) if (random.nextDouble() < 0.3) set(x, y)
    }

    fun bitLife(rule: Rule): Double {
        val grid = BitLife(lifeSize, lifeSize, Edge.TORUS, rule)
        soup(lifeSize) { x, y -> grid[x, y] = true }
        return time { repeat(lifeGenerations) { grid.step() } }
    }

    fun reference(): Double {
        val grid = LifeGrid(referenceSize, referenceSize, Edge.TORUS)
        soup(referenceSize) { x, y -> grid[x, y] = true }
        return time { repeat(referenceGenerations) { grid.step() } }
    }

    fun hashLife(): Double {
        val hash = HashLife()
        for ((x, y) in Rle.parse(Patterns.GOSPER_GUN)) hash[x.toLong(), y.toLong()] = true
        return time { hash.advancePowerOfTwo(hashExponent) }
    }

    fun sand(): Double {
        val world = SandWorld(sandWidth, sandHeight, seed = 1)
        demoScene(world)
        return time { repeat(sandFrames) { world.step() } }
    }

    val highLife = Rule.parse("B36/S23")
    val lifeCells = lifeGenerations.toDouble() * lifeSize * lifeSize
    val referenceCells = referenceGenerations.toDouble() * referenceSize * referenceSize
    val workloads = listOf<Triple<String, String, () -> Pair<Double, Pair<Double, String>>>>(
        Triple("bitlife-conway", "$lifeSize x $lifeSize torus, $lifeGenerations generations, B3/S23") {
            bitLife(Rule.CONWAY).let { it to (lifeCells / it to "cells/s") }
        },
        Triple("bitlife-rule", "$lifeSize x $lifeSize torus, $lifeGenerations generations, ${highLife.notation}") {
            bitLife(highLife).let { it to (lifeCells / it to "cells/s") }
        },
        Triple("lifegrid-reference", "$referenceSize x $referenceSize torus, $referenceGenerations generations, B3/S23") {
            reference().let { it to (referenceCells / it to "cells/s") }
        },
        Triple("hashlife-gosper", "Gosper gun, 2^$hashExponent generations") {
            hashLife().let { it to (1.0 / it to "runs/s") }
        },
        Triple("sand-demo", "$sandWidth x $sandHeight demo scene, $sandFrames frames") {
            sand().let { it to (sandFrames / it to "frames/s") }
        },
    )
    return workloads.map { (name, work, run) ->
        run()
        val (seconds, rate) = run()
        BenchResult(name, work, seconds, rate.first, rate.second)
    }
}

private inline fun time(block: () -> Unit): Double {
    val started = System.nanoTime()
    block()
    // A floor of one nanosecond keeps rates finite on coarse clocks.
    return maxOf(System.nanoTime() - started, 1L) / 1e9
}

internal fun runBench(options: Options, out: PrintStream) {
    val results = benchmark(options.flag("quick"))
    if (options.flag("json")) {
        out.println(
            Json.write(
                linkedMapOf(
                    "command" to "bench",
                    "quick" to options.flag("quick"),
                    "java" to System.getProperty("java.version"),
                    "results" to results.map {
                        linkedMapOf(
                            "name" to it.name,
                            "work" to it.work,
                            "seconds" to it.seconds,
                            "rate" to it.rate,
                            "unit" to it.unit,
                        )
                    },
                ),
            ),
        )
        return
    }
    for (r in results) {
        out.println("%-20s %10.4f s %12.3e %-9s %s".format(r.name, r.seconds, r.rate, r.unit, r.work))
    }
}
