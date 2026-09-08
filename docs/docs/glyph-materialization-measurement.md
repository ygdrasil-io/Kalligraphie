# Glyph materialization measurement

Kalligraphie provides an opt-in JVM measurement runner for portable glyph
materialization. It is test-source tooling, not a functional latency test and
not a published benchmark result. It exercises the checked-in, audited COLR/
CPAL, SVG-in-OpenType, and EBDT format 1 fixtures through the public catalog,
resolver, instance, asset, and `resolveGlyph(...)` path.

The runner records thirteen profiles, in this order:

- cold and warm COLR v0 / CPAL v0 normalization;
- cold and warm SVG-in-OpenType normalization;
- cold and warm EBLC v2 / EBDT v2 format 1 bitmap decoding;
- CPAL palette 0 to palette 1 selection;
- SVG profile-key pressure followed by LRU eviction and re-resolution;
- cooperative cancellation during a real two-layer COLR materialization.
- cold and warm public `RENDERABLE` consumer journeys with one Bungee Color
  Latin glyph;
- cold and warm public `RENDERABLE` consumer journeys with Bungee Color Latin
  plus Liberation Sans Hebrew fallback in one BiDi paragraph.

Cold samples start before embedded-catalog creation and end after the returned
immutable representation is consumed. Warm samples create and seed their
catalog, resolver, instance, and asset before the clock starts, then time only
`resolveGlyph(...)` and consumption of its result. Asset and resolver closure
are intentionally excluded from both intervals. The palette profile starts
before palette-1 asset acquisition after a palette-0 seed. The pressure profile
includes seeding, five distinct certified SVG profile keys, and the final
re-resolution. The cancellation profile measures call entry through typed
cancellation, and separately measures the first in-operation cancellation
signal through that return.

The consumer-cold profiles include catalog and resolver creation, then end once
the public paragraph facade has produced and consumed a layout whose final
glyphs all carry materialization certificates. The consumer-warm profiles keep
their catalog and resolver open, seed the portable representation cache with an
untimed first layout, then measure the same public facade boundary. The JVM
facade deliberately opens and closes its documented shaping backend for every
call, so these warm profiles report asset-cache reuse rather than hidden
backend reuse.

## Reproducible invocation

The report must be written outside the repository. `--rerun-tasks` prevents a
previous Gradle result from suppressing an explicitly requested measurement.

```bash
env \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_MEASUREMENT=true \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_WARMUP=5 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_ITERATIONS=20 \
  KALLIGRAPHIE_GLYPH_MATERIALIZATION_OUTPUT=/tmp/kalligraphie-glyph-materialization.md \
  ./gradlew :kalligraphie:glyphMaterializationMeasurement \
  --rerun-tasks --no-daemon
```

Use one warmup and two iterations only as a smoke run. It verifies that every
route can produce a report but is not suitable for comparisons.

## Report contents and limits

Every report records the measured commit, machine, OS, architecture, JVM,
fixture SHA-256 hashes, corpus, exact route, timed boundary, cache state,
warmup count, iteration count, nearest-rank latency percentiles, measured-thread
allocations, and a signed used-heap delta sampled after the documented forced
GC requests. It also records the input source bytes supplied to an embedded
catalog in the timed interval, decoded bitmap bytes and pixels, and normalized
paint-node counts.

Source-byte values are fixture buffer sizes supplied to the portable catalog;
they are not filesystem-I/O counters. A warm profile reports zero source bytes
because its catalog is intentionally opened before the timed boundary. The
portable routes in this runner expose no trustworthy native-memory or
native-allocation accounting boundary, so those fields explicitly report
`unavailable` rather than estimate a platform value. The retained JVM-memory
field is a runner-scoped heap observation, not cache accounting or a universal
process-memory measurement; it can be negative after GC.

The runner has no latency threshold. Functional `check` runs do not execute a
measurement unless the opt-in environment variable is set, and the runner does
not add a renderer, rasterizer, GPU API, or native bridge.
