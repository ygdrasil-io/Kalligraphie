# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Changed
- Kotlin 2.4.0 → 2.4.10
- Gradle 9.5.0 → 9.6.1
- Added blocking pull request policy checks aligned with `CONTRIBUTING.md`.
- Legacy `font/` sources are retained for reference but excluded from Gradle project aggregation, API documentation, and CI.
- The documentation site now embeds the API reference for the Kalligraphie modules.

### Added
- Advanced typography and derived content through `org.graphiks:kalligraphie`: typed `GlyphProvenance` for direct, derived, and synthetic glyphs, soft and automatic hyphenation (versioned pattern service with digest-verified `hyph-en-us` data and structured diagnostics), inter-word / inter-character / kashida justification, tab stops with START/END/CENTER/DECIMAL alignment and synthetic leaders, ellipsis truncation (`INLINE_START`, `MIDDLE`, `INLINE_END`) with explicit hidden source ranges, inline objects for `U+FFFC` with caret/selection/hit-testing semantics, and final-glyph recertification after every transform.
- KMP project template (Android, iOS, Desktop)
- JVM reference editable Unicode lines through `org.graphiks:kalligraphie`: canonical UTF-8/UTF-16 decoding, Unicode analysis, pinned HarfBuzz shaping, exact caret and selection geometry, hit testing, and outline-route certification.
- Clean Architecture / DDD
- JVM-only autonomous embedded TrueType catalog support through `org.graphiks:kalligraphie`, including immutable source/face/instance data, cmap and metrics, design-unit outlines, and detached render assets.
- Incremental real-time paragraph layout on the JVM, with versioned edit deltas, reusable checkpoints, cancellation, editor-journey coverage, and opt-in measurement guidance.
- Maven Central publishing via Vanniktech
- Legacy JVM font module sources retained under `font/` for reference only.
- OpenType core and SFNT parsing sources for the JVM font stack.
- COLR/CPAL parsing, glyph scaling, and text shaping sources for the JVM font stack.
- Glyph surfaces, glyph cache primitives, A8 rasterization, and renderer-neutral atlas upload planning.
- Multilingual docs (EN/FR) MkDocs + Dokka
- GitHub templates (issues, PR)
- Code of Conduct, CONTRIBUTING, SECURITY, SUPPORT, CHANGELOG

### Changed
- Replaced the Dokka GFM and Python post-processing pipeline with Dokka for Material for MkDocs.

### Fixed
- Default snapshot publication version when no workflow version is provided.

### Removed
- The `:shared` KMP template and its CI workflow.

### Built with
- Kotlin 2.4.10, Gradle 9.6.1, AGP 9.0.0
- Koin 4.0.0, Ktor 3.0.3, Compose Multiplatform 1.11.1
