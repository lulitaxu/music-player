# Music Player (working title)

A production-grade personal iOS music player: global discovery, authorized
playback sources, offline downloads, synced lyrics, and a premium native
interface. Target device: iPhone XS on iOS 18 (minimum OS: iOS 18).

**Status: Phase 0 (repository + architecture).** See `SPEC.md` for the full
product specification and `docs/PHASE0.md` for the feasibility report,
provider decisions, roadmap, and Milestone 1 acceptance tests.

## Build workflow (read this first)

Development happens on Arch Linux, which cannot compile or sign iOS apps.
Source, docs, and Git live here. Builds run on macOS:

- GitHub Actions macOS runners generate the Xcode project from
  `project.yml` (XcodeGen), build, and run the test suites.
- The `.xcodeproj` is never committed; `project.yml` is the source of truth.
- Physical-device verification (iPhone XS) happens on Arz's iPhone.

No feature is "done" because code exists. A feature is done when its
tests pass in CI and, where the spec requires it, on the physical device.

## Non-negotiables (from SPEC.md)

- No DRM bypass, no provider-restriction bypass, ever.
- Metadata, playable, and downloadable are three different claims and the
  UI always says which one is true.
- Playback, offline, and lyrics sync are built before decorative UI.
