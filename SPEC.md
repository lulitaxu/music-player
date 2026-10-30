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

## Addendum 2026-10-31 (Arz): animation, discovery algorithms, mixing

Three additions, adopted with phase placement and honest limits.

### 1. Apple Music-style animation (Phases 1, 3, 4)
- Motion is a design-system layer, not per-screen improvisation:
  shared spring tokens live in `DesignSystem/Animations` (landed with
  Milestone 1), artwork-driven ambient color backgrounds on Now
  Playing, fluid mini-player expansion, animated karaoke line
  emphasis in the lyrics UI.
- Static artwork gets subtle life (slow pan/zoom, color-reactive
  ambience). Apple's own animated album covers are label-supplied
  assets exclusive to Apple Music; no third-party app can license
  them, so they are explicitly out of scope.
- All motion respects Reduce Motion: a complete static experience is
  the fallback, never a broken one. The XS performance budget rules:
  no stacked blur animations, no perpetual loops on list screens.

### 2. Discovery algorithms (Phase 6, growing in Phase 7)
Spotify's recommender is trained on hundreds of millions of users;
that dataset cannot be replicated and this spec does not pretend to.
What is built instead, honestly labeled in the UI as it matures:
- **Taste profile, on-device only:** weighted artists, genres, and
  eras from the user's own listening history, favorites, and skips.
  It never leaves the device (consistent with the privacy section).
- **Made-for-you mixes:** generated locally from the taste profile
  against provider catalogs (Audius trending, Jamendo and Internet
  Archive categories, MusicBrainz relationships).
- **Radio mode:** seed a track or artist, the queue extends itself
  from similar tracks (provider related-data plus metadata graph).
- **Similar artists and tracks** on every artist and track page
  (extends section A discovery).
Quality grows with the user's own listening, which the UI may say.
It must never claim Spotify-scale intelligence it does not have.

### 3. Mixing (Phase 6, staged)
In increasing order of difficulty, shipped in this order:
- **Gapless** playback (already in section B).
- **Configurable crossfade** (1 to 12 seconds, volume-ramped, two
  player instances). This is the Spotify setting most users mean.
- **Smart transitions (stretch, Phase 7):** crossfade alignment using
  tempo and energy metadata where it exists (local files analyzed
  on-device; tracks without data fall back to plain crossfade).
Full beat-matched DJ mixing across arbitrary remote streams
(Spotify's newest Mix feature) is research-grade on a phone CPU and
is **not** promised; if smart transitions prove out on local files,
the phase plan revisits it with evidence.

### 4. Live Art on Now Playing (Phase 3, reactive layer grows Phase 7)
The popular services' animated covers (Apple's animated artwork,
Spotify Canvas) are artist/label assets that exist only inside those
platforms; no API licenses them to a third-party app, and extracting
them is out of bounds. The buildable equivalent is **Live Art**:
animation generated on-device from the song itself.
- Layer 1: artwork color field (ambient tinting, from the design
  study) cross-fading per track.
- Layer 2: the cover in slow motion: a subtle Ken Burns drift and
  breathing scale, off entirely under Reduce Motion.
- Layer 3: audio-reactive visuals driven by the actual playing audio
  (level/frequency data rendered as a restrained waveform or radial
  field, frame-capped for the XS). Local files get the true reactive
  layer first via the audio-engine tap; streamed playback gets
  Layers 1-2 until the stream tap lands as a Phase 7 stretch item.
Live Art is a Now Playing surface only, never a list background, and
it pauses with playback. It is generated, never downloaded, so it
works for every track including the user's own files.

### 5. International text: Bangla and every language render properly
Added 2026-10-31 (Arz). Lyrics, metadata, and search are multilingual
by design, with Bangla as a first-class case, not an edge case.
- All ingested text (providers, LRC files, local file tags) is
  normalized to Unicode NFC. Legacy non-Unicode encodings (e.g. Bijoy
  for Bangla) are out of scope unless a real source demands them, and
  are never silently rendered as garbage without a note.
- Content text uses system fonts so script fallback works (iOS ships
  Kohinoor Bangla for Bengali). No Latin-only custom font may be
  applied to lyrics or metadata.
- Bangla conjuncts and vowel signs must survive intact: karaoke
  highlighting is by whole line, and no code may split grapheme
  clusters for animation or truncation.
- Lyric lines carry per-line direction: the first strong character
  decides LTR vs RTL (Arabic, Urdu, Hebrew right-align; Bangla and
  Latin left-align), including mixed lines inside one song.
- LRC parsing treats lyric text as opaque Unicode; only the
  timestamp tags are parsed.
- Search accepts input in any script; queries are percent-encoded.
- Foundation landed early: `Core/Utilities/LocalizedText.swift`
  (NFC normalization + per-line direction) with unit tests covering
  Bangla, Arabic, mixed, and decomposed-input cases. The lyrics
  layer's acceptance tests must add Bangla and Arabic LRC fixtures.

## Definition of done
App builds; runs on iPhone XS / iOS 18; search returns real
provider-backed results; at least one legitimate audio source plays
real audio; background playback and Lock Screen controls work; queue
and playlists work; lyrics sync correctly for supported tracks;
authorized downloads and offline playback work; sleep timer works;
battery guard works within iOS execution limits; errors handled without
crashes; UI responsive; tests pass; setup and architecture docs exist.

### 6. Product name: arzxsh
Added 2026-10-31 (Arz). The app is his product and carries his name:
home-screen display name and in-app branding are "arzxsh". Provider
names do not headline user-facing screens; honest attribution lives
in Settings > About (streams via Audius, metadata via MusicBrainz
and the Cover Art Archive). Availability honesty is unchanged:
Playable vs Metadata only labels stay.
