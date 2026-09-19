# Conformance matrix

Kalligraphie carries its portable conformance contract, standard corpus, and
reference oracle in the non-published `:kalligraphie:conformance` module. The
module is not a consumer artifact: it exists to declare what each reference
platform implements and to compare the observables those platforms publish.

The contract is expressed by a small set of types. `ComparisonClass` and
`ComparisonQuantity` define how a portable outcome is compared; `CanonicalUnit`
and `CanonicalScale` fix the unit of cross-platform numeric comparison;
`Tolerance` and `ReferenceToleranceCatalog` declare justified numeric
tolerances; `DiagnosticComparison` compares diagnostic sequences;
`PortableCapability`, `PortableCapabilityIdentity`, and
`currentPortableCapabilityIdentity()` declare the platform capability surface;
`ConformanceScenario`, `StandardConformanceCorpus`, `ConformanceObservation`,
`CanonicalEnvelope`, and `observableEnvelope()` describe and encode corpus
results; and `ReferenceDivergenceRecord` records bounded divergences.

This page publishes the resulting matrix: which portable stages each reference
platform exposes, how results are compared, which divergences are bounded, and
what remains a known limitation.

## Capability matrix

| Platform | Unicode analysis | Shaping | End-to-end layout | Glyph representation variants | Decoding | Cancellation/atomicity | Verification route |
| --- | --- | --- | --- | --- | --- | --- | --- |
| JVM | Present | Present | Present | Present | Present | Present | `:kalligraphie:conformance:jvmTest` |
| iOS (Kotlin/Native) | Absent | Absent | Absent | Present | Present | Present | `:kalligraphie:conformance:iosSimulatorArm64Test` |
| Android | Absent | Absent | Absent | Present | Present | Present | `:kalligraphie:conformance:connectedAndroidDeviceTest`; declared `:kalligraphie:conformance:mediumPhoneAndroidDeviceTest` |

Capability availability is declared, never inferred from compilation. The JVM
declares the complete reference surface (`jvm-reference`). Apple and Android
declare Unicode analysis, shaping, and end-to-end layout `absent`, and the glyph
representation route present (`portable-glyph`). Every `PortableCapability` is
declared exactly once; `PortableCapabilityIdentity` exposes `presenceOf`,
`canonicalFingerprint`, and `absenceDiagnostics`, and
`blockingDiagnostic(scenario)` returns the deterministic
`conformance.portable-capability-absent` diagnostic for the first unavailable
capability a scenario requires instead of producing an observation.

Decoding and cancellation/atomicity are portable behaviors rather than gated
capabilities: every reference platform executes them. Their observables are
platform-invariant. The observable envelopes are asserted identically on JVM and
iOS, and the corresponding observable values are reproduced on Android through
the public facade's device tests. The only admissible cross-platform difference
is the declared capability identity. This matrix claims no shaping or end-to-end
layout conformance on Apple or Android.

## Corpus and invariant observables

`StandardConformanceCorpus` defines four decoding scenarios — `ascii`,
`multibyte-utf8`, `surrogate-utf16`, and `malformed-utf8` — executed by the
reference oracle on every compiled target. Each produces a
`ConformanceObservation` (scenario, capability fingerprint, decoded scalars,
source-unit widths, diagnostic codes), and `CanonicalEnvelope` encodes it under
schema version 1 as a deterministic, line-based text envelope that needs no
serialization dependency. `ConformanceObservation.observableEnvelope()` drops
the capability fingerprint so the platform-invariant observables can be compared
directly. Because those observables are invariant, the same envelopes hold
across platforms even though the declared capability identity differs.

## Comparison classes

`ComparisonClass` carries three values, and `ComparisonQuantity` declares which
one applies to each observable:

- `BIT_IDENTICAL` — exact equality of integers or bytes. The quantities are
  `CODEPOINT_BOUNDARY`, `GRAPHEME_BOUNDARY`, `CLUSTER_INDEX`, `GLYPH_INDEX`,
  `CARET_INTEGER_POSITION`, `LINE_INDEX`, `FRAGMENT_INDEX`,
  `DIAGNOSTIC_IDENTITY`, and `DIAGNOSTIC_ORDER`.
- `STRUCTURALLY_IDENTICAL` — the same decision or ordering, not necessarily
  byte-equal. The quantities are `LINE_BREAK_DECISION`,
  `FALLBACK_CANDIDATE_ORDER`, and `BIDI_RESOLUTION`.
- `NUMERIC_TOLERANCE` — floating geometry compared under a declared tolerance in
  the canonical unit. The quantities are `ADVANCE`, `ORIGIN`, `BOX`, and
  `CARET_FRACTIONAL_POSITION`.

`ComparisonQuantity.effectiveClass(scope)` conditions the declared class on the
comparison scope: a bit-identical quantity compared across different
implementation profiles is only structurally identical, because the underlying
implementation is not the same. Within one profile the declared class applies
unchanged. `DiagnosticComparison` compares diagnostic sequences under the
bit-identical contract and distinguishes `Match`, `OrderDivergence` (same
diagnostics, different emitted order), and `Mismatch`.

## Tolerances

`Tolerance` declares a numeric tolerance for one floating observable. Every
tolerance carries its scope (`ToleranceScope.INTRA_PLATFORM` or
`CROSS_PLATFORM`), its unit (`ToleranceUnit.Canonical` or
`ToleranceUnit.RouteNative`), a strictly positive maximum deviation, and a
non-blank justification tied to the unit. A cross-platform tolerance must be
expressed in the canonical unit; only an intra-platform comparison may use a
route-native unit. A tolerance is never a global epsilon and is never widened to
hide a regression.

The reference corpus requires no numeric tolerance today.
`ReferenceToleranceCatalog.tolerances` is empty because the portable corpus
produces only bit-identical decoding results: every observable is an integer (a
scalar value or a source-unit width) or a typed diagnostic code, so nothing is
compared under a numeric tolerance. The canonical target, while it remains the
only canonical unit, is `CanonicalUnit.FONT_DESIGN_UNIT`, reachable through
`CanonicalScale`. Tolerances will be added, with a canonical-unit justification,
when portable geometry becomes observable and the reference oracle can calibrate
them.

## Bounded divergences

`ReferenceDivergenceRecord` records six bounded divergences — Unicode analysis,
shaping, and end-to-end layout on iOS and Android. Each carries a justification
tied to the contract rather than a tolerance chosen to hide a regression.

| Platform | Capability | Divergence | Justification |
| --- | --- | --- | --- |
| iOS | Unicode analysis | Not portable on Apple yet. | Portable Unicode analysis is owned by the dedicated analysis workstream. |
| iOS | Shaping | Not portable on Apple yet. | Portable shaping is owned by the dedicated HarfBuzz bindings workstream. |
| iOS | End-to-end layout | Cannot execute on Apple without portable analysis and shaping. | End-to-end layout depends on portable analysis and shaping. |
| Android | Unicode analysis | Not portable on Android yet. | Portable Unicode analysis is owned by the dedicated analysis workstream. |
| Android | Shaping | Not portable on Android yet. | Portable shaping is owned by the dedicated HarfBuzz bindings workstream. |
| Android | End-to-end layout | Cannot execute on Android without portable analysis and shaping. | End-to-end layout depends on portable analysis and shaping. |

## Known limitations

- Portable Unicode analysis and shaping are owned by separate workstreams: the
  system-font-provider workstream and the HarfBuzz/kffi bindings workstream.
  Until they land, Apple and Android cannot execute the Unicode analysis,
  shaping, or end-to-end layout stages.
- `iosArm64` is compiled but not device-executed on hosted runners; the
  Kotlin/Native verification route is `iosSimulatorArm64Test`.
- The Android device APK currently exercises the public portable facade rather
  than the shared `commonTest` corpus, because `androidDeviceTest` does not
  inherit `commonTest`.
- The `mediumPhone` Gradle Managed Device is declared in the build, but its
  task `:kalligraphie:conformance:mediumPhoneAndroidDeviceTest` is not yet
  executed in CI; the Android route was validated locally on an emulator through
  `connectedAndroidDeviceTest`.
- `:kalligraphie:conformance` is intentionally non-published: the contract,
  corpus, and oracle are test tooling, not a consumer dependency.
