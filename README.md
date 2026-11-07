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
| `BitLife` | 64 cells per machine word, with a bit-sliced adder for neighbor counts | Large, dense grids |
| `HashLife` | Memoized, hash-consed quadtree on an unbounded plane | Repetitive patterns over enormous spans of time |

```kotlin
val life = HashLife()
for ((x, y) in Rle.parse(Patterns.GOSPER_GUN)) life[x.toLong(), y.toLong()] = true
life.advancePowerOfTwo(100)
println(life.exactPopulation) // 211275100038038233582783867604
```

Populations past 64 bits are real here: a Gosper gun has about 2^97 cells after 2^100 generations.
`population` saturates at `Long.MAX_VALUE`, and `exactPopulation` gives the true count.

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
- Known bugs were injected on purpose, such as a top-down scan, a missing wake on a neighboring chunk
  and a dropped border in Hashlife. Each made the suite fail. One that first survived led to a test
  that found a real bug, where a falling column smeared out.

## Measured

Single runs on an Apple Silicon laptop.

| Operation | Result |
| --- | --- |
| `BitLife`, 2048 x 2048, 2000 generations | About 2 * 10^10 cells per second, on one thread |
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
