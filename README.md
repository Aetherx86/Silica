# Silica

Cellular automata on the JVM, written in Kotlin with no runtime dependencies. Includes Conway's
Game of Life with three engines of very different power, and a falling-sand world.

Life is synchronous: every cell changes at once. Falling sand moves particles in place and conserves
matter. The two need quite different techniques, and this library covers both.

```text
silica view
silica hashlife --pattern gosper --exponent 100
```

## Life engines

| Engine | How it works | Use it for |
| --- | --- | --- |
| `LifeGrid` | Double-buffered byte array, with any `B/S` rule | Clear reference behavior, and rules other than Conway's |
| `BitLife` | 64 cells per machine word, with a bit-sliced adder for neighbor counts, and any `B/S` rule | Large, dense grids |
| `HashLife` | Memoized, hash-consed quadtree on an unbounded plane, with any `B/S` rule except `B0` | Repetitive patterns over enormous spans of time |

```kotlin
val life = HashLife()
for ((x, y) in Rle.parse(Patterns.GOSPER_GUN)) life[x.toLong(), y.toLong()] = true
life.advancePowerOfTwo(100)
println(life.exactPopulation) // 211275100038038233582783867604
```

Populations past 64 bits are real here: a Gosper gun has about 2^97 cells after 2^100 generations.
`population` saturates at `Long.MAX_VALUE`, and `exactPopulation` gives the true count.

## Rules, RLE and Hashlife queries

Since 0.2.0 every engine takes a life-like rule. `Rule.parse` reads `B36/S23` in either case and
the older `S/B` form such as `23/36`, and `Rule.notation` prints the canonical form. `BitLife`
keeps a dedicated path for Conway's rule and evaluates any other rule from a four-bit bit-sliced
neighbor count. `HashLife` rejects `B0` rules, because under them the empty plane itself would
come alive.

`Rle.read` returns the cells together with the header's size and rule and the `#N` and `#C`
lines. `Rle.write` produces canonical RLE: translated to the origin, blank rows merged into one
`n$` token, lines wrapped at 70 characters. Reading is bounded (run lengths up to 1,000,000,
coordinates up to 2^30, ten million live cells by default), so a hostile file fails with an
error instead of exhausting memory.

`HashLife` answers questions about patterns far beyond 64-bit coordinates. `advance(BigInteger)`
takes any generation count, `boundingBox()` and `populationIn(...)` are exact at any size, and
`cells()` and `toRle()` export the pattern while it still fits in 64-bit coordinates.

```kotlin
val life = HashLife(Rule.parse("B36/S23"))
for ((x, y) in Rle.read(File("highlife.rle").readText()).cells) life[x.toLong(), y.toLong()] = true
life.advance(BigInteger.TEN.pow(30))
println(life.boundingBox())                      // Bounds(minX=-12, minY=-15, maxX=21, maxY=20)
println(life.populationIn(-100, -100, 100, 100)) // 39
println(life.toRle())
```

The command line exposes the same features. `--json` prints one JSON object. Hashlife
generations and populations are JSON strings in it, since they routinely pass 2^53.

```sh
silica hashlife --pattern acorn --generations 5206 --json
# {"command":"hashlife","rule":"B3/S23","generation":"5206","population":"633",...}
silica hashlife --pattern diehard --generations 140 --every 20
# generation 20: population 18 ... generation 140: population 0
silica life --pattern acorn --rule B36/S23 --generations 100 --size 256 --out highlife.rle
silica hashlife --rle highlife.rle --generations 1000000000000000000000000000000
# rule: B36/S23 ... population: 39, bounding box: (-12, -15) to (21, 20)
silica rle --pattern glider --rule 23/36
# x = 3, y = 3, rule = B36/S23
# bo$2bo$3o!
silica bench --json
```

`--rule` overrides the rule named in an RLE file, which overrides Conway's. `--every N` samples
the population every `N` generations, up to 10,000 samples. `bench` times one fixed workload per
engine after an untimed warm-up, and `--quick` shrinks the workloads. Bad input exits with
status 2 and a one-line message; a valid request that cannot be met, such as exporting a pattern
that has spread past 64-bit coordinates, exits with status 1.

## Falling sand

`SandWorld` packs each cell into one `Int`, through a `@JvmInline value class` with no boxing: 8 bits
of material, 8 of color variation and 16 of remaining lifetime.

| Material | Behavior |
| --- | --- |
| Sand | Falls, piles into slopes, and sinks through liquids |
| Water, oil, acid | Fall and spread sideways, and settle by density, so oil floats |
| Stone, wood | Stay put |
| Fire | Burns out over time, ignites wood and oil, and is quenched by water into steam |
| Acid | Dissolves stone, wood and sand, and is used up by each reaction |
| Smoke, steam | Rise and fade away |

### How it stays correct and fast

- **Scan order.** The grid is scanned from the bottom up, so a falling column moves as one solid block.
  The horizontal direction alternates each frame so that nothing drifts to one side.
- **Frame stamps.** A per-cell stamp records what already moved this tick, so a particle cannot be
  carried along by the scan. A single parity bit in the cell is the common alternative, but it breaks
  under chunk sleeping, because a sleeping cell keeps a stale parity.
- **Stateless randomness.** Every random choice is a pure hash of position, frame and purpose rather
  than a value from a stream. Sleeping chunks therefore consume no randomness.
- **Chunk sleeping.** The world is divided into chunks, and a chunk with no recent changes nearby is
  skipped. Combined with stateless randomness, this is purely an optimization: a world with sleeping
  on matches one with it off, cell for cell, on every frame. A settled world visits no cells at all.

## Usage

```sh
./gradlew :app:installDist
app/build/install/silica/bin/silica view
app/build/install/silica/bin/silica sand --frames 1000 --png world.png
app/build/install/silica/bin/silica life --pattern gosper --generations 2000 --size 2048
app/build/install/silica/bin/silica hashlife --pattern gosper --exponent 100
app/build/install/silica/bin/silica hashlife --pattern acorn --generations 5206 --json
app/build/install/silica/bin/silica rle --pattern glider --rule 23/36
app/build/install/silica/bin/silica bench
```

In the window, the mouse paints and the keys 0 to 9 pick a material.

To use the library, `silica-core` is published to GitHub Packages under `dev.aetherx86.silica`.

## How it is tested

The tests pin behavior against published facts and against independent implementations.

- The R-pentomino has to settle at generation 1103 with 116 cells, and a pulsar has to cycle through
  populations of 48, 56 and 72.
- `BitLife` is compared with `LifeGrid` on random soups, for widths on and around word boundaries
  and for both edge modes. `HashLife` is compared with `BitLife` for many step sizes.
- A Gosper gun is held to its exact population formula at 30 * 2^40 and at 2^100 generations, which
  no cell-by-cell simulation could reach and which only passes if memoization is correct.
- Sleeping and non-sleeping worlds are compared on every frame, with random scenes, several chunk
  sizes and painting mid-run.
- Matter is checked for conservation on every frame.
- `BitLife` is compared with `LifeGrid` under named and random rules, including `B0` rules, on
  every edge mode and word-boundary width. `HashLife` is compared with `BitLife` under random rules
  without `B0`. Day & Night is checked to commute with inverting every cell.
- The acorn has to settle at generation 5206 with 633 cells, and diehard has to vanish at exactly
  generation 130. A glider advanced by 4 * 2^40 generations has to move exactly 2^40 cells.
- `populationIn`, `boundingBox` and `cells` are compared with direct scans of `BitLife`. RLE is
  round-tripped on hundreds of random patterns and rules, and hostile files are checked to fail.
- Known bugs were injected on purpose, such as a top-down scan, a missing wake on a neighboring chunk
  and a dropped border in Hashlife. Each made the suite fail. One that first survived led to a test
  that found a real bug, where a falling column smeared out.

## Measured

Single runs on an Apple Silicon laptop.

| Operation | Result |
| --- | --- |
| `BitLife`, 2048 x 2048, 2000 generations | About 2 * 10^10 cells per second, on one thread |
| `BitLife`, 1024 x 1024, 500 generations of `B36/S23` | About 7 * 10^9 cells per second |
| `HashLife`, Gosper gun, 2^100 generations | 16 milliseconds |
| `SandWorld`, 512 x 288 demo scene, 700 frames | About 2500 frames per second |

## Versioning

A bug or security fix increments `0.0.X`. New functionality or new platform support increments
`0.X.0`. See [CHANGELOG.md](CHANGELOG.md).

## License

MIT. See [LICENSE](LICENSE).

## Limitations

- **Grid topology**: The double-buffered and bit-parallel engines operate on bounded 2D toroidal or fixed-boundary grids; unbounded sparse universes should use the `Hashlife` quadtree engine.
- **Falling-sand determinism**: Chunk sleeping preserves deterministic outcomes for a fixed seed and scan schedule, but cross-thread parallel stepping is intentionally omitted to keep single-bit cellular updates reproducible.
