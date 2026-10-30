# SPEC: Personal Music Player (production)

Working spec, condensed from the full blueprint Arz supplied on
2026-10-30 (reference image embeds omitted; no requirement dropped).
Where this file and any chat message disagree, this file wins.

## Target

- Native iOS app. Minimum OS: iOS 18. Reference device: iPhone XS.
- Premium dark-first interface, "Liquid Glass" direction: native Liquid
  Glass APIs where the OS provides them, SwiftUI Material fallback on
  iOS 18 (the XS never runs the newer OS, so the fallback IS the target
  device's experience and must be first-class).
- Development on Arch Linux; compilation, simulator, signing, and device
  builds happen on macOS (CI runners or a Mac). See docs/PHASE0.md.

## Product constraint (non-negotiable)

No legitimate app can guarantee free streaming and downloads of every
copyrighted song worldwide. The app searches broadly but always
distinguishes metadata, playable streams, and downloadable audio.
**Never bypass DRM, access controls, or provider restrictions.**

Users can: discover music across countries and genres; search by song,
artist, album, lyrics, alternate versions; play through supported audio
providers; import their own audio files; download where the provider
permits; listen offline; read synced lyrics; manage playlists, queues,
favorites, history; use a sleep timer and battery guard; control
playback from Lock Screen and Control Center.

## Features

### A. Discovery
Global search by track, artist, album, genre, ISRC, across supported
providers. Remixes, live versions, covers, alternate releases. Recently
played and trending where providers support them. Search suggestions
and typo correction, search history, genre/mood browsing, artist
profiles, album pages, similar artists and related tracks, local library
indexing, cached search results. Every result carries an availability
indicator: **Playable / Downloadable / Metadata only / Local**.

### B. Player
Play, pause, previous, next, seek, progress, shuffle, repeat modes,
queue management (add to queue, play next), background playback, Lock
Screen controls, headphone/Bluetooth controls, audio-session
interruption handling, graceful handling of unavailable tracks, playback
position memory, gapless where supported, playback speed where
supported. Advanced: crossfade, sleep fade-out, volume normalization
where supported, quality selection where supported, EQ presets and
custom EQ where technically supported, listening statistics, recently
played, favorites.

### C. Lyrics (a major subsystem, not an afterthought)
Synced LRC with real-time line highlighting; plain lyrics fallback;
automatic matching; manual search; karaoke mode with large typography;
auto-scroll; active-line highlighting; translation when available;
offline lyrics cache when licensing permits; loading, error, and
unavailable states. Matching priority: ISRC, title, artist, album,
duration, alternate title/release info. **Never match on title alone**
(creates wrong lyrics for remixes, covers, live versions).
Architecture: `LyricsProvider` protocol, separate adapters, LRC parser,
timestamp sync engine driven by actual playback time (never a drifting
side timer), lyrics repository, local cache, SwiftUI renderer. Never
present an unverified match as verified.

### D. Downloads and offline
Authorized downloads only. Local file import. Progress, pause/resume
where supported, retry, download management, offline library, local
metadata and artwork, storage usage view, delete, detection of missing
or corrupted files, offline playlists, offline lyrics when permitted.
Local formats: MP3, AAC, WAV, ALAC where the playback stack supports
them. Never present a metadata URL as an audio download URL.

### E. Sleep timer
Presets 5/10/15/20/30/45/60/90 minutes, custom countdown, stop after
current track, gradual volume fade, cancel, remaining-time display,
remembered preferences, no auto-advance after the timer completes.
Deadline-based implementation (no drift-prone repeating timer);
recovers correctly after app suspension.

### F. Battery guard
Monitor battery while the app is active; configurable stop threshold;
optional warning before stopping; save playback state, then stop.
Never delete downloads or playlists. Best-effort only: iOS does not
guarantee monitoring while suspended or terminated, and the UI must
not promise otherwise.

### G. Library and playlists
Favorites, custom playlists, recently played, recently added, most
played, downloaded music, artist and album collections, playlist
artwork, reordering, bulk add/remove, smart playlists from supported
local metadata.

### H. Settings
Theme, glass intensity/appearance, audio quality, download preferences,
storage management, lyrics preferences, sleep timer defaults, battery
guard, playback preferences, cache management, privacy information,
about and credits.

## Design system
Dark-first; deep black and graphite; large album artwork; translucent
floating navigation; subtle blur and depth; smooth transitions;
deliberate typography; minimal borders; consistent spacing; no generic
dashboard-card grids. Five tabs: Home, Search, Library, Downloads,
Settings. Now Playing is globally reachable from a floating mini-player.
Avoid excessive blur layers and heavy effects; the XS is a first-class
performance target, not an afterthought.

## Technical architecture
Swift + SwiftUI. AVFoundation/AVPlayer for playback. SQLite via GRDB
for library, metadata, queues, cache. URLSession networking and
authorized downloads. Nuke for artwork loading/caching. XCTest for
unit, integration, and UI tests. Async/await, dependency injection,
business logic outside SwiftUI views, clear error types, testable
services.

Provider architecture is protocol-based (see
`Providers/ProviderProtocols.swift`): `MusicMetadataProvider`,
`AudioSourceProvider`, `LyricsProvider`. No provider is hardcoded into
the app; each adapter declares capabilities and licensing limits.

Project layout: `App/ Core/ Features/ Providers/ DesignSystem/
Resources/ Tests/` as created in Phase 0. The Xcode project is
generated from `project.yml` (XcodeGen) and never committed.

## Performance targets
Cached search under 200 ms; cold main screen under 1.5 s where
practical; stable 60 FPS scrolling; no UI-induced audio interruptions;
artwork memory + disk caching; no main-thread database work;
background-capable downloads where iOS permits; bounded caches and
controlled image sizes. Performance claims require profiling on the
physical device, not "it compiles".

## Roadmap (strict order; no decorative work before playback works)
- Phase 0: repository, architecture, models, protocols, DI, build/test
  workflow, architecture documentation.
- Phase 1: design system (colors, type, navigation, glass components,
  artwork components, loading/empty/error states, iOS 18 path).
- Phase 2: discovery and search (metadata provider, results, artist and
  album screens, artwork, search cache, local indexing).
- Phase 3: playback engine (audio source abstraction, AVPlayer, queue,
  state, background audio, Lock Screen, interruptions). Critical
  milestone.
- Phase 4: lyrics engine (providers, matching, LRC parser, sync,
  karaoke, cache).
- Phase 5: downloads and offline (authorized pipeline, file management,
  queue, offline playback, persistence, storage management).
- Phase 6: personal features (playlists, favorites, sleep timer,
  battery guard, history, EQ and crossfade where supported).
- Phase 7: performance and security (profiling, stress tests, network
  failure handling, migration tests, cache optimization, accessibility,
  privacy review). Release gate.
- Phase 8: device testing and release (macOS build, iPhone XS, iOS 18,
  background playback, offline mode, critical crash fixes, release docs).

## Development rules
1. Inspect the repository before changing anything.
2. Plan before major code changes.
3. Build in small, testable phases; compile and test at every milestone
   (macOS CI or a Mac).
4. Never replace working functionality with placeholders.
5. Never invent API endpoints or provider capabilities.
6. Never embed private API keys or credentials in the app.
7. Never block the main thread with networking, database, or artwork.
8. Swift concurrency; clear separation of responsibilities.
9. Document architectural decisions.
10. iOS 18 compatibility always.
11. A feature is not complete until its tests pass.
12. When an API, SDK, signing path, or build environment is
    unavailable, document the blocker and build the abstraction; never
    fake working functionality.

## Testing requirements (per phase)
- Playback: play/pause, skip/seek, queue changes, background playback,
  headphone disconnect, incoming interruptions.
- Lyrics: matching correctness, LRC parsing, sync accuracy, missing
  lyrics, wrong-match handling, translation availability.
- Offline: download completion, failed and interrupted downloads,
  offline startup, missing local files.
- Sleep timer: countdown, stop after track, fade-out, cancel,
  suspension and recovery.
- Battery: threshold config, warning, stop, battery data unavailable.

## Security and privacy
Credentials in Keychain where appropriate; no hardcoded secrets; HTTPS
only; validate imported files; local database consistency; no
unnecessary analytics; no listening-data collection without explicit
consent; document external providers and their privacy policies.

## Definition of done
App builds; runs on iPhone XS / iOS 18; search returns real
provider-backed results; at least one legitimate audio source plays
real audio; background playback and Lock Screen controls work; queue
and playlists work; lyrics sync correctly for supported tracks;
authorized downloads and offline playback work; sleep timer works;
battery guard works within iOS execution limits; errors handled without
crashes; UI responsive; tests pass; setup and architecture docs exist.
