# Phase 0 report: audit, feasibility, providers, roadmap

Date: 2026-10-30. Prepared before any feature code, per SPEC.md rules.
Verdict up front: **buildable, with one structural constraint** — nothing
compiles on the dev machine. Every build and test runs on macOS CI until
a Mac exists. That shapes the whole workflow below.

## 1. Repository audit

Greenfield. No pre-existing code, repo, or project files existed.
Created 2026-10-30:

- Directory layout exactly per SPEC.md section on project structure
  (`App/ Core/ Features/ Providers/ DesignSystem/ Resources/ Tests/`),
  plus `Core/Models/`.
- `Core/Models/Track.swift`: canonical Track / Artist / Album models,
  the `AudioAvailability` four-state enum, `AudioStream`,
  `DownloadSource`. Foundation-only, provider-agnostic.
- `Providers/ProviderProtocols.swift`: `MusicMetadataProvider`,
  `AudioSourceProvider`, `LyricsProvider`, `ProviderCapabilities`,
  `LyricsResult`. Adapters map into the canonical models; the app never
  sees provider payloads.
- `App/MusicApp.swift`: minimal `@main` entry and placeholder root view
  so Milestone 1 has something real to build.
- `project.yml`: XcodeGen definition (app + unit/UI test targets,
  GRDB and Nuke packages, iOS 18 deployment target, background-audio
  plist mode declared from day one).
- `.gitignore`: the generated `.xcodeproj` is never committed.

Not yet done, deliberately: no GitHub remote (public vs private is
Arz's call, it changes CI economics, see section 2), no CI workflow
(Milestone 1), no feature code.

## 2. Environment and build feasibility

Dev environment (audited 2026-10-30): Arch Linux VM, Linux 7.0 x86_64,
no Swift toolchain, no Xcode. Arz's laptop is the same class of machine
(Arch, 4 GB RAM). **There is no way to compile, simulate, or sign an
iOS app on Linux.** A macOS VM on a 4 GB laptop is not viable. So:

- **Compiler of record: GitHub Actions macOS runners.** The workflow
  installs XcodeGen, generates the project from `project.yml`, builds
  for the iOS 18 simulator, and runs XCTest. This is a standard,
  proven setup.
- **Public repo:** Actions minutes including macOS are free.
  **Private repo:** the free plan's included minutes burn at 10x on
  macOS (about 200 real macOS minutes/month), workable but tight for
  an iterate-often project. Recommendation: public repo. The app
  contains no secrets by design (SPEC rule 7).
- **Device testing:** CI simulators cannot validate XS performance,
  background audio behavior, or interruptions honestly. The final
  authority is Arz's physical iPhone. (Open question: exact model. The
  spec targets the XS; if his phone is newer, the XS stays the
  performance floor and his device is the test unit.)
- **Installing on his phone:** two routes. Free provisioning (personal
  team): works, but builds expire after 7 days and must be re-signed,
  fine during development. Apple Developer Program ($99/year): 1-year
  provisioning, TestFlight, and the only route to the App Store.
  Decision needed before Phase 8, not before Milestone 1.
- **One design consequence:** the iPhone XS tops out at iOS 18. It will
  never run the OS versions with native Liquid Glass APIs. On the
  target device, the SwiftUI Material fallback is not a fallback, it is
  the product. Phase 1 must design it as the primary surface and treat
  Liquid Glass as progressive enhancement for newer phones.

## 3. Architecture proposal

As SPEC.md mandates, with these concrete decisions:

- **Project generation:** XcodeGen (`project.yml` committed,
  `.xcodeproj` generated). Avoids hand-editing `project.pbxproj` from
  Linux and keeps every change reviewable as YAML.
- **Persistence:** GRDB over raw SQLite for the local library, queues,
  playlists, caches. Migrations are versioned and tested (Phase 7 gate
  includes migration tests).
- **Playback:** a single `PlaybackEngine` service wrapping AVPlayer,
  owning the queue, `MPNowPlayingInfoCenter`, `MPRemoteCommandCenter`,
  and `AVAudioSession` interruption handling. It consumes
  `AudioStream` values from whichever `AudioSourceProvider` resolved
  the track; it knows no provider names.
- **Lyrics:** the sync engine reads the player's own clock
  (`AVPlayer` periodic time observer). No independent timers anywhere
  in the lyrics path, per SPEC.
- **Sleep timer:** deadline-based (`Date` comparison on a coarse
  check), per SPEC; state persisted so suspension/recovery is testable.
- **Concurrency:** async/await end to end; providers are `Sendable`;
  no main-thread I/O anywhere (SPEC rule 8).
- **DI:** a single `DependencyContainer` built at app start; features
  receive services, never construct them.

## 4. Dependency list

| Dependency | Purpose | Notes |
|---|---|---|
| GRDB.swift 6.29+ | SQLite persistence | SPM, MIT |
| Nuke 12.7+ (NukeUI) | Artwork loading/caching | SPM, MIT |
| XcodeGen (CI only) | Project generation | Not shipped in the app |
| XCTest (Apple) | All test suites | No third-party test deps |

Nothing else enters without a documented reason in this file.

## 5. Provider and licensing feasibility

The spec names categories; these are the concrete, verified-real
choices. Every entry is a documented public API with the stated terms;
nothing here requires scraping or restriction bypass.

| Role | Provider | Access | What it honestly gives |
|---|---|---|---|
| Metadata | MusicBrainz | Free, no key; 1 req/sec; identifying User-Agent required | Tracks, artists, albums, ISRCs. Metadata only, never audio |
| Artwork | Cover Art Archive | Free, no key | Album covers by MusicBrainz release id |
| Audio (primary) | Audius | Free public API, no key for read/stream | Full-track streaming of artist-uploaded music; downloads only where the artist enabled them |
| Audio (archive) | Internet Archive | Free public API | Public-domain and Creative Commons audio; stream and download per item rights |
| Audio (CC catalog) | Jamendo | Free developer client_id | Full streaming of CC-licensed catalog; downloads per track license |
| Audio (local) | User file import | On-device | The user's own MP3/AAC/WAV/ALAC; always Playable + Local |
| Lyrics | LRCLIB | Free, no key | Synced LRC and plain lyrics; lookup by title + artist + album + duration, matching the SPEC matching rules |
| Lyrics (backup) | MusicBrainz-linked sources later | TBD in Phase 4 | Only if LRCLIB coverage proves thin in testing |

Explicitly **not** sources: Spotify, Deezer, Apple Music, SoundCloud.
Their APIs do not license third-party full-track playback for an app
like this (Spotify's API never delivers audio at all; see the project
history). They may appear later as metadata-only references if a phase
justifies it, labeled Metadata only, or not at all.

Every search result merges metadata (MusicBrainz) with availability
(audio providers). A song can be globally known and honestly
Metadata only in this app; the UI states that instead of faking a
play button. This is the SPEC's core product constraint, implemented.

## 6. Ordered roadmap

SPEC phases, unchanged, with the sequencing constraint that heavy
phases land after the Meta hackathon (Nov 5) and Haven (Nov 13-14):

- **Milestone 1 (= Phase 0 completion + Phase 1 shell):** CI workflow,
  tab skeleton, design tokens. Acceptance tests below.
- **Phase 2:** MusicBrainz + Cover Art Archive search, Audius
  availability merge, artist/album pages, search cache, local indexing.
- **Phase 3:** playback engine end to end on Audius + local files.
  Critical milestone; device-verified background audio and Lock Screen.
- **Phase 4:** LRCLIB lyrics, LRC parser, sync engine, karaoke.
- **Phase 5:** downloads (Audius/IA/Jamendo where permitted) + import,
  offline library.
- **Phase 6:** playlists, favorites, sleep timer, battery guard,
  history/statistics. Plus the 2026-10-31 addendum: the on-device
  discovery engine (taste profile, made-for-you mixes, radio) and
  mixing stage 2 (configurable crossfade). Animation tokens already
  landed in Milestone 1; Now Playing motion lands in Phase 3 and
  karaoke motion in Phase 4. See SPEC.md addendum.
- **Phase 7:** performance, security, accessibility gates.
- **Phase 8:** device testing on the physical iPhone, release prep,
  provisioning decision executed.

## 7. Milestone 1 and its acceptance tests

Scope: GitHub repo created (visibility per Arz), Actions workflow that
generates the project and builds + tests on a macOS runner, five-tab
shell with the Material design tokens, mini-player placeholder wired to
nothing (no fake playback).

Acceptance tests (all must pass before Phase 2 starts):

1. CI is green on a clean checkout: project generates, app target
   builds for the iOS 18 simulator, unit test target runs.
2. A unit test suite exists and passes covering: `AudioAvailability`
   coding round-trip, canonical model decoding from a MusicBrainz-shaped
   fixture and an Audius-shaped fixture (recorded JSON in the repo, no
   network in tests).
3. The app launches in the iOS 18 simulator to the five-tab shell.
4. No network call, database query, or artwork decode happens on the
   main thread in the shell (verified by construction: no such code
   exists yet, and the container injects stubs).
5. README and this report match reality; any step that could not be
   run is listed here as unrun rather than claimed.

Known unrun items at Phase 0 sign-off: everything in Milestone 1.
Nothing in this report claims a build has succeeded; no build has been
attempted yet, because the macOS environment does not exist until the
repo and CI do.
