package dev.aetherx86.silica.app

import dev.aetherx86.silica.HashLife
import dev.aetherx86.silica.Patterns
import dev.aetherx86.silica.Rle
import dev.aetherx86.silica.Rule
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliTest {
    private data class Result(val status: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val status = run(args.toList(), PrintStream(out, true, Charsets.UTF_8), PrintStream(err, true, Charsets.UTF_8))
        return Result(status, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun field(json: String, key: String): String =
        Regex("\"$key\":(\"[^\"]*\"|[^,}]*)").find(json)?.groupValues?.get(1)?.trim('"')
            ?: error("no $key in $json")

    private fun tempDir(): File = Files.createTempDirectory("silica-cli").toFile().apply { deleteOnExit() }

    @Test
    fun jsonEscapesStringsAndHandlesEveryValueType() {
        assertEquals(
            """{"s":"a\"b\\c\nd\u0001","n":null,"b":true,"i":[1,2,3],"big":123456789012345678901234567890,"nan":null}""",
            Json.write(
                linkedMapOf(
                    "s" to "a\"b\\c\nd\u0001",
                    "n" to null,
                    "b" to true,
                    "i" to listOf(1, 2L, 3),
                    "big" to java.math.BigInteger("123456789012345678901234567890"),
                    "nan" to Double.NaN,
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> { Json.write(Any()) }
    }

    @Test
    fun optionsSeparateSwitchesFromValuesAndRejectUnknownOnes() {
        val options = Options.parse(listOf("--json", "--size", "64"), setOf("size"), setOf("json"))
        assertTrue(options.flag("json"))
        assertEquals(64, options.int("size", 1))
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("--nope", "1"), setOf("size")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("--size"), setOf("size")) }
        assertFailsWith<IllegalArgumentException> { Options.parse(listOf("size", "1"), setOf("size")) }
        assertFailsWith<IllegalArgumentException> {
            Options.parse(listOf("--size", "-3"), setOf("size")).int("size", 1)
        }
    }

    @Test
    fun hashlifeReportsTheAcornsSettledStateAsJson() {
        val result = cli("hashlife", "--pattern", "acorn", "--generations", "5206", "--json")
        assertEquals(0, result.status, result.err)
        assertEquals("hashlife", field(result.out, "command"))
        assertEquals("5206", field(result.out, "generation"))
        assertEquals("633", field(result.out, "population"))
        assertEquals("B3/S23", field(result.out, "rule"))
    }

    @Test
    fun hashlifeSamplesAtTheRequestedInterval() {
        val result = cli("hashlife", "--pattern", "diehard", "--generations", "130", "--every", "50")
        assertEquals(0, result.status, result.err)
        val lines = result.out.lines()
        assertTrue(lines.any { it.startsWith("generation 50: population ") }, result.out)
        assertTrue(lines.any { it.startsWith("generation 100: ") }, result.out)
        assertTrue("generation 130: population 0" in lines, result.out)
        assertTrue("bounding box: empty" in lines, result.out)
    }

    @Test
    fun hashlifeMatchesTheLibraryForAnAstronomicalCount() {
        val result = cli("hashlife", "--pattern", "gosper", "--exponent", "100", "--json")
        assertEquals(0, result.status, result.err)
        val reference = HashLife()
        for ((x, y) in Rle.parse(Patterns.GOSPER_GUN)) reference[x.toLong(), y.toLong()] = true
        reference.advancePowerOfTwo(100)
        assertEquals(reference.exactPopulation.toString(), field(result.out, "population"))
        assertEquals(reference.generation.toString(), field(result.out, "generation"))
    }

    @Test
    fun lifeWritesRleThatHashlifeReadsBackWithItsRule() {
        val dir = tempDir()
        val written = File(dir, "out.rle")
        val life = cli(
            "life", "--pattern", "rpentomino", "--rule", "B36/S23", "--generations", "60",
            "--size", "256", "--edge", "dead", "--out", written.path, "--json",
        )
        assertEquals(0, life.status, life.err)
        assertEquals("B36/S23", field(life.out, "rule"))
        val pattern = Rle.read(written.readText())
        assertEquals(Rule.parse("B36/S23"), pattern.rule)
        assertEquals(field(life.out, "population").toInt(), pattern.cells.size)

        // The file's own rule carries over, and a zero-generation run reports it unchanged.
        val hash = cli("hashlife", "--rle", written.path, "--generations", "0", "--json")
        assertEquals(0, hash.status, hash.err)
        assertEquals("B36/S23", field(hash.out, "rule"))
        assertEquals(pattern.cells.size.toString(), field(hash.out, "population"))
    }

    @Test
    fun rlePrintsCanonicalPatternsUnderAnyRule() {
        val result = cli("rle", "--pattern", "glider", "--rule", "23/36")
        assertEquals(0, result.status, result.err)
        assertEquals("x = 3, y = 3, rule = B36/S23\nbo\$2bo\$3o!\n", result.out)
    }

    @Test
    fun benchReportsEveryEngine() {
        val result = cli("bench", "--quick", "--json")
        assertEquals(0, result.status, result.err)
        for (name in listOf("bitlife-conway", "bitlife-rule", "lifegrid-reference", "hashlife-gosper", "sand-demo")) {
            assertTrue("\"name\":\"$name\"" in result.out, "missing $name in ${result.out}")
        }
        val rates = Regex("\"rate\":([0-9.E+-]+)").findAll(result.out).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(5, rates.size)
        assertTrue(rates.all { it > 0 && it.isFinite() })
    }

    @Test
    fun sandRunsHeadlessAndWritesAnImage() {
        val png = File(tempDir(), "world.png")
        val result = cli("sand", "--frames", "5", "--png", png.path, "--json")
        assertEquals(0, result.status, result.err)
        assertEquals("5", field(result.out, "frames"))
        assertTrue(png.length() > 0)
    }

    @Test
    fun badInputExitsWithAMessageInsteadOfAStackTrace() {
        val cases = listOf(
            listOf("life", "--bogus", "1") to 2,
            listOf("life", "--pattern", "nothing") to 2,
            listOf("life", "--rule", "B9/S1") to 2,
            listOf("life", "--size", "4") to 2,
            listOf("life", "--edge", "sphere") to 2,
            listOf("hashlife", "--rule", "B0/S8") to 2,
            listOf("hashlife", "--exponent", "3", "--generations", "8") to 2,
            listOf("hashlife", "--generations", "-1") to 2,
            listOf("hashlife", "--every", "1", "--exponent", "20") to 2,
            listOf("hashlife", "--rle", "/nonexistent/silica.rle") to 2,
            listOf("hashlife", "--pattern", "glider", "--rle", "x.rle") to 2,
            listOf("hashlife", "--exponent", "100", "--out", File(tempDir(), "big.rle").path) to 1,
            listOf("frobnicate") to 2,
        )
        for ((args, expected) in cases) {
            val result = cli(*args.toTypedArray())
            assertEquals(expected, result.status, "$args: ${result.out} ${result.err}")
            assertTrue(result.err.isNotBlank(), "$args should explain itself")
            assertTrue("Exception" !in result.err, "$args: ${result.err}")
        }
    }

    @Test
    fun hostileRleFilesAreRejected() {
        val file = File(tempDir(), "hostile.rle")
        file.writeText("x = 1, y = 1\n" + "999999b".repeat(2000) + "o!")
        val result = cli("hashlife", "--rle", file.path)
        assertEquals(2, result.status)
        assertTrue("wider" in result.err, result.err)
    }
}
