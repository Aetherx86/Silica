package dev.aetherx86.silica.app

import dev.aetherx86.silica.BitLife
import dev.aetherx86.silica.Edge
import dev.aetherx86.silica.HashLife
import dev.aetherx86.silica.Material
import dev.aetherx86.silica.Patterns
import dev.aetherx86.silica.Rle
import dev.aetherx86.silica.SandWorld
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.system.exitProcess

private val USAGE = """
    usage: silica <command> [options]

      view                                  open the interactive sand window
      sand [--frames N] [--png FILE]        run a headless sand scene and report timing
      life [--pattern NAME] [--generations N] [--size N] [--png FILE]
                                            run bit-parallel Life
      hashlife [--pattern NAME] [--exponent N]
                                            advance by 2^N generations with Hashlife

    patterns: glider, pulsar, gosper, rpentomino
""".trimIndent()

private fun options(args: List<String>): Map<String, String> {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        require(key.startsWith("--") && i + 1 < args.size) { "expected --option value, got $key" }
        out[key.removePrefix("--")] = args[i + 1]
        i += 2
    }
    return out
}

private fun patternNamed(name: String): String = when (name) {
    "glider" -> Patterns.GLIDER
    "pulsar" -> Patterns.PULSAR
    "gosper" -> Patterns.GOSPER_GUN
    "rpentomino" -> Patterns.R_PENTOMINO
    else -> throw IllegalArgumentException("unknown pattern: $name")
}

internal fun colorOf(type: Int, variation: Int): Int {
    val v = variation and 15
    return when (type) {
        Material.SAND -> rgb(214 + v / 2, 180 + v, 110 + v)
        Material.WATER -> rgb(40, 110 + v, 220)
        Material.OIL -> rgb(70 + v, 50, 30)
        Material.ACID -> rgb(120, 230 + v / 2, 60)
        Material.STONE -> rgb(110 + v, 110 + v, 115 + v)
        Material.WOOD -> rgb(110 + v, 70, 30)
        Material.FIRE -> rgb(240, 110 + v * 6, 30)
        Material.SMOKE -> rgb(90 + v, 90 + v, 90 + v)
        Material.STEAM -> rgb(200 + v, 210 + v, 220 + v)
        else -> rgb(14, 14, 20)
    }
}

private fun rgb(r: Int, g: Int, b: Int): Int = (r shl 16) or (g shl 8) or b

internal fun render(world: SandWorld, image: BufferedImage) {
    for (y in 0 until world.height) {
        for (x in 0 until world.width) {
            val cell = world[x, y]
            image.setRGB(x, y, colorOf(cell.type, cell.variation))
        }
    }
}

/** A scene that exercises every material, used by the headless run and the window. */
internal fun demoScene(world: SandWorld) {
    val w = world.width
    val h = world.height
    for (x in 0 until w) {
        world.paint(x, h - 1, Material.STONE)
        world.paint(x, h - 2, Material.STONE)
    }
    for (y in h / 2 until h - 2) {
        world.paint(w / 3, y, Material.STONE)
        world.paint(2 * w / 3, y, Material.WOOD)
    }
    for (x in w / 12 until w / 4) for (y in 2 until h / 4) world.paint(x, y, Material.SAND)
    for (x in w / 3 + 4 until 2 * w / 3 - 4) for (y in 2 until h / 5) world.paint(x, y, Material.WATER)
    for (x in w / 3 + 10 until 2 * w / 3 - 10) for (y in h / 5 until h / 4) world.paint(x, y, Material.OIL)
    for (x in 2 * w / 3 + 8 until 5 * w / 6) for (y in h / 2 until h - 2) world.paint(x, y, Material.WOOD)
    for (x in 2 * w / 3 + 8 until 5 * w / 6) world.paint(x, h / 2 - 1, Material.FIRE)
}

private fun runSand(args: Map<String, String>) {
    val frames = args["frames"]?.toInt() ?: 1000
    val world = SandWorld(512, 288, seed = 1)
    demoScene(world)
    val started = System.nanoTime()
    repeat(frames) { world.step() }
    val elapsed = (System.nanoTime() - started) / 1e9
    println("%d frames in %.3f s (%.0f frames/s)".format(frames, elapsed, frames / elapsed))
    println("cells visited: %d of %d".format(world.totalCellsVisited, frames.toLong() * 512 * 288))
    args["png"]?.let { path ->
        val image = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_RGB)
        render(world, image)
        ImageIO.write(image, "png", File(path))
        println("wrote $path")
    }
}

private fun runLife(args: Map<String, String>) {
    val size = args["size"]?.toInt() ?: 1024
    val generations = args["generations"]?.toInt() ?: 2000
    val grid = BitLife(size, size, Edge.TORUS)
    for ((x, y) in Rle.parse(patternNamed(args["pattern"] ?: "gosper"))) {
        grid[x + size / 4, y + size / 4] = true
    }
    val started = System.nanoTime()
    repeat(generations) { grid.step() }
    val elapsed = (System.nanoTime() - started) / 1e9
    val cellsPerSecond = generations.toDouble() * size * size / elapsed
    println("%d generations in %.3f s (%.2e cells/s)".format(generations, elapsed, cellsPerSecond))
    println("population: ${grid.population()}")
    args["png"]?.let { path ->
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) for (x in 0 until size) image.setRGB(x, y, if (grid[x, y]) 0xE8E8F0 else 0x0E0E14)
        ImageIO.write(image, "png", File(path))
        println("wrote $path")
    }
}

private fun runHashLife(args: Map<String, String>) {
    val exponent = args["exponent"]?.toInt() ?: 100
    val hash = HashLife()
    for ((x, y) in Rle.parse(patternNamed(args["pattern"] ?: "gosper"))) hash[x.toLong(), y.toLong()] = true
    val started = System.nanoTime()
    hash.advancePowerOfTwo(exponent)
    val elapsed = (System.nanoTime() - started) / 1e9
    println("advanced 2^$exponent generations in %.3f s".format(elapsed))
    println("generation: ${hash.generation}")
    println("population: ${hash.exactPopulation}")
    println("distinct nodes: ${hash.nodeCount}")
}

private fun openWindow() {
    val world = SandWorld(320, 180, seed = System.nanoTime())
    demoScene(world)
    val image = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_RGB)
    var brush = Material.SAND
    val scale = 4
    var mouseX = -1
    var mouseY = -1

    val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            g.drawImage(image, 0, 0, world.width * scale, world.height * scale, null)
        }
    }
    panel.preferredSize = Dimension(world.width * scale, world.height * scale)
    panel.background = Color(14, 14, 20)
    val status = JLabel(" ")
    val mouse = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = update(e)
        override fun mouseDragged(e: MouseEvent) = update(e)
        override fun mouseReleased(e: MouseEvent) {
            mouseX = -1
        }
        private fun update(e: MouseEvent) {
            mouseX = e.x / scale
            mouseY = e.y / scale
        }
    }
    panel.addMouseListener(mouse)
    panel.addMouseMotionListener(mouse)

    val frame = JFrame("Silica")
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    frame.layout = BorderLayout()
    frame.add(panel, BorderLayout.CENTER)
    frame.add(status, BorderLayout.SOUTH)
    frame.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            val digit = e.keyChar - '0'
            if (digit in 0 until Material.COUNT) brush = digit
        }
    })
    frame.pack()
    frame.isVisible = true

    Timer(16) {
        if (mouseX >= 0) {
            for (dy in -2..2) for (dx in -2..2) world.paint(mouseX + dx, mouseY + dy, brush)
        }
        world.step()
        render(world, image)
        status.text = "  brush: ${Material.name(brush)} (keys 0-9), awake cells: ${world.cellsVisited}"
        panel.repaint()
    }.start()
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println(USAGE)
        return
    }
    try {
        val rest = options(args.drop(1))
        when (args[0]) {
            "view" -> SwingUtilities.invokeLater { openWindow() }
            "sand" -> runSand(rest)
            "life" -> runLife(rest)
            "hashlife" -> runHashLife(rest)
            else -> {
                println(USAGE)
                exitProcess(2)
            }
        }
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}")
        exitProcess(2)
    }
}
