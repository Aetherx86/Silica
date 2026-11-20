package dev.aetherx86.silica.app

import dev.aetherx86.silica.Material
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
import java.io.PrintStream
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.system.exitProcess

internal val USAGE = """
    usage: silica <command> [options]

      view                                  open the interactive sand window
      sand [--frames N] [--png FILE] [--json]
                                            run a headless sand scene and report timing
      life [--pattern NAME | --rle FILE] [--rule RULE] [--generations N] [--size N]
           [--edge torus|dead] [--png FILE] [--out FILE] [--json]
                                            run bit-parallel Life under any B/S rule
      hashlife [--pattern NAME | --rle FILE] [--rule RULE] [--exponent N | --generations N]
               [--every N] [--out FILE] [--json]
                                            advance with Hashlife by 2^N or by any count
      rle [--pattern NAME | --rle FILE] [--rule RULE]
                                            print a pattern as canonical RLE
      bench [--quick] [--json]              time every engine on fixed workloads

    patterns: glider, pulsar, gosper, rpentomino, acorn, diehard
    rules: B3/S23 notation, or the older S/B form such as 23/36
""".trimIndent()

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

/** Runs one command and returns its exit status, without exiting, so it can be tested. */
internal fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
    if (args.isEmpty()) {
        out.println(USAGE)
        return 0
    }
    val rest = args.drop(1)
    return try {
        when (args[0]) {
            "view" -> {
                Options.parse(rest, emptySet())
                SwingUtilities.invokeLater { openWindow() }
            }
            "sand" -> runSand(Options.parse(rest, setOf("frames", "png"), setOf("json")), out)
            "life" -> runLife(
                Options.parse(rest, setOf("pattern", "rle", "rule", "generations", "size", "edge", "png", "out"), setOf("json")),
                out,
            )
            "hashlife" -> runHashLife(
                Options.parse(rest, setOf("pattern", "rle", "rule", "exponent", "generations", "every", "out"), setOf("json")),
                out,
            )
            "rle" -> runRle(Options.parse(rest, setOf("pattern", "rle", "rule")), out)
            "bench" -> runBench(Options.parse(rest, emptySet(), setOf("json", "quick")), out)
            "help", "--help", "-h" -> out.println(USAGE)
            else -> {
                err.println(USAGE)
                return 2
            }
        }
        0
    } catch (e: IllegalArgumentException) {
        err.println("error: ${e.message}")
        2
    } catch (e: IllegalStateException) {
        err.println("error: ${e.message}")
        1
    } catch (e: java.io.IOException) {
        err.println("error: ${e.message}")
        1
    }
}

fun main(args: Array<String>) {
    val status = run(args.toList(), System.out, System.err)
    System.out.flush()
    if (status != 0) exitProcess(status)
}
