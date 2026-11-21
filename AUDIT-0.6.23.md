# arzxsh audit — 0.6.23 (pre-release)

Date: 2026-11-22. Scope: `android/app/src/main/java/com/arzx/music/*.kt` (16 files,
20,957 lines), `AndroidManifest.xml`, `res/xml/*`, both Gradle build files, plus a
lighter pass over `cli/` and `ytmusic-backend/`, and the three unreleased CLI commits
after 0.6.22 (`d689124`, `e699b6d`, `147a052`). Method: manual review against the
attack catalog (Module 6/7 items that map to a mobile client: unsafe URL handling,
error leakage, hard-coded credentials, insecure storage, vulnerable components) and
`design/OFFICIAL-APP-CHECKLIST.md`.

Counts: **Critical 0 · High 0 · Medium 3 (1 fixed) · Low 4 · Info 2**

## 1. Findings

| # | Severity | Location | Finding / impact |
|---|----------|----------|------------------|
| M1 | Medium (FIXED) | `UpdateChecker.kt` `fetchLatest` (~L356) | When an update existed, the API-enrichment `Release` replaced the primary-path one wholesale. If the API path's own version.json fetch failed, its tag fallback produced `sha256 = null`, and `downloadApk` then refused the download ("no checksum") forever — a legitimate update was offered but could never install. Fail-closed, so not a security hole, but a dead update path. Fix below. |
| M2 | Medium | `MainActivity.kt` `showUpdateCheckFailed` (~L5043); `UpdateChecker.kt` `ioDetail` (L150) | The manual update-check failure dialog prints the raw failure detail: HTTP status, GitHub's server message, and raw exception class names/messages (up to 100 chars). This is a deliberate 0.6.4 design ("never a bare check-your-connection"), but it conflicts with the standing rule that raw error/exception text never reaches the UI. Named, not changed — it is a documented product decision; Arzu's call whether the update dialog keeps its exception-class small print. |
| M3 | Medium | `MainActivity.kt` L4867 | About screen line "Sources · YouTube Music · Audius · On-device files" names both sources in the UI, against the standing no-source-branding rule. Ambiguous because About is also the credits surface (the legal trademark attribution at L4690-4691 is correct and stays). Named, not changed. |
| L1 | Low | `MainActivity.kt` L4133-4136 | Search browse row "Open a YouTube Link / Play or download any YouTube video as audio, even if it is not on YouTube Music" names the source in a Search row. Functional naming for user-pasted links, but it is branding on a page the rule covers. Named, not changed. |
| L2 | Low | `YoutubeSource.kt` `videoIdFromText` (L221-230) | ID extraction matches substrings: a lookalike host such as `fakeyoutube.com/watch?v=<id>` yields an ID, and any shared 6-20 char word token is accepted as a bare ID. Impact negligible: the ID is shape-validated and `trackFromId` only ever builds a canonical `youtube.com` URL from it — no attacker URL is ever loaded. Named only. |
| L3 | Low | `android/app/build.gradle.kts` L105 | NewPipeExtractor (`v0.26.5`, pinned) resolves through JitPack rather than Maven Central — a third-party build-service supply-chain dependency for a shipped component. No version drift risk (exact pin), noted for the release phase. |
| L4 | Low | `ytmusic-backend/app.py` L32, L406, L448, L850 | `YTM_DL_NO_VERIFY=1` env hatch disables yt-dlp TLS certificate verification in `/song`, stream resolve and `/download`. Dev-sandbox-only by design, logs a loud startup warning, and no launcher script in the repo sets it. Keep it that way: never set it in a shipped launcher. |
| I1 | Info | `cli/arzxsh_cli/links.py` | CLI parity nuance, not a break: the CLI's `extract_video_id` requires a real YouTube host (stricter than the app's regex), so a bare video ID pasted into CLI search resolves as a link in the app but not in the CLI. Pasted links work identically in both. |
| I2 | Info | `android/app/build.gradle.kts` L48 | `targetSdk 35`; Google Play requires target API 36 for new submissions. Already staged as release-phase work in the official-app checklist; the sideload channel is unaffected. |

## 2. Fixed in the working tree (not committed, no version bump)

1. **Raw exception text in two Toasts** — `MainActivity.kt`
   - L8947-8948 (autoplay): `Toast "Autoplay unavailable: $err"` showed `shortErr(t)` = exception class + message to the user. Now logs the diagnostic (`Log.e("Autoplay", …)`) and toasts plain "Autoplay unavailable right now."
   - L9060-9061 (radio): same pattern. Now logs (`Log.e("Radio", …)`) and toasts "Radio unavailable right now.", matching the radio empty-result toast already in the code.
   These were the only two UI surfaces still interpolating raw error text; every other error path (`showPlaybackError`, `addPageError`, `Downloads.humanError`) already sanitizes.
2. **Updater SHA-256 dropped by enrichment (M1)** — `UpdateChecker.kt` L356-365
   In `fetchLatest`, when the enriched release matches the primary version but carries no `sha256` while the primary path has one, the primary hash is carried over (`enriched.copy(sha256 = rel.sha256)`). Verification stays fail-closed: a release with no hash on either path is still refused.

Verification: `:app:compileDebugKotlin` — **BUILD SUCCESSFUL** with the fixes applied
(documented Android build recipe; all warnings pre-existing). `python3 -m py_compile`
passes for all `cli/arzxsh_cli/*.py` and `ytmusic-backend/app.py`. `keys/` untouched.

## 3. Verified clean

**Manifest surface** — `usesCleartextTraffic="false"`; `allowBackup="false"` plus
`data_extraction_rules.xml` / `backup_rules.xml` excluding every domain for cloud
backup and device transfer. Permissions are minimal: INTERNET, ACCESS_NETWORK_STATE,
WAKE_LOCK, MODIFY_AUDIO_SETTINGS, POST_NOTIFICATIONS, FOREGROUND_SERVICE +
media-playback type, REQUEST_INSTALL_PACKAGES (updater only). No RECORD_AUDIO.
`PlaybackService` is `exported="false"`. The widget receiver is exported with only
`APPWIDGET_UPDATE` in its filter, and its PendingIntents are immutable and addressed
to the app's own non-exported service. `MainActivity` (exported, singleTask) accepts
only: LAUNCHER, `ACTION_SEND` text/plain, and `ACTION_VIEW` for https on the five
YouTube hosts. No WebView anywhere in the app; no custom TrustManager or
HostnameVerifier; zero cleartext `http://` URLs in the Kotlin sources.

**Updater hash path** — a release with no SHA-256 in version.json is refused before
download; an already-downloaded APK is re-hashed and deleted on mismatch before it
can reach the installer; the downloaded bytes are hashed again after transfer and
deleted on mismatch; a truncated transfer (byte count ≠ Content-Length) is discarded.
`versionName` from remote JSON must match `^\d+\.\d+\.\d+$` before it is interpolated
into any URL or filename. API-path asset URLs are accepted only over https from
`github.com` / `*.githubusercontent.com`, and the APK asset must be exactly
`arzxsh-<versionName>.apk`. The installer hand-off uses the FileProvider URI with a
per-intent grant; `openReleasePage` hands the browser only https URLs whose host is
exactly `github.com`.

**FileProvider paths** — `res/xml/file_paths.xml` exposes exactly one path:
`cache-path updates/`. Nothing else in app storage is reachable through the provider.

**Intent / link handling** — shared text and VIEW data pass only through
`videoIdFromText` (strict ID shape); clipboard import uses the same path; the link
screen's failure state is plain-language with a Retry button. Local file import is
SAF-based (`ACTION_OPEN_DOCUMENT`, persistable read grant only), metadata via
MediaMetadataRetriever (released in `finally`), and extracted artwork is confined to
`filesDir/local_art`, which the artwork loader re-verifies by canonical path.

**Network hardening** — the artwork loader is https-only, follows redirects manually
(max 3 hops), re-runs the private/loopback/link-local address check on every hop and
every resolved address, caps response bytes, and refuses unbounded bitmap decodes.
YtmApi stream candidates are https-only. Audius IDs are URL-encoded as a single path
segment at every call site (playback, gapless, mix, downloads). Lyrics providers get
URL-encoded queries and there is no XML parser in the app (regex extraction — no XXE
surface); provider names appear only in log reports, never in the UI. The NewPipe
downloader caps buffered bodies at 16 MB.

**Storage / secrets** — no accounts, tokens or cookies exist to store: YtmApi persists
only a `last_good_client` label, visitor data is memory-only, the profile is local
(name in private prefs, photo ≤512 px JPEG in internal storage). The Innertube
`API_KEY` in `YtmApi.kt` is the public web-client key shipped in music.youtube.com
itself, not a user credential. Logging is diagnostics-only (error reports and ladder
outcomes); the one profile/download identifier logged is none — no tokens exist.

**Bugs / concurrency** — all three `!!` in the codebase are null-guarded by their
enclosing condition. `PlayerRegistry` enforces single player ownership across
activity instances; `Downloads` guards its state with `@Synchronized` on a
process-lifetime executor, shuts down its segmented-download pool, and the activity
shuts down all six of its executors in `onDestroy`. Playback/download failures map to
plain-language UI with Retry (`Downloads.humanError`, `addPageError`), and
pull-to-refresh reloads every root screen (Home, Search, Library, Downloads, plus
nav-stack restore for sub-pages).

**CLI parity (commits `d689124`, `e699b6d`, `147a052`)** — parity holds. Sorting modes
match the app's 0.6.22 set (Recently added / Title / Artist / Duration for On this
device, Liked Songs and playlists, per-view choice persisted); link tracks get lyrics
hard-off in `cli/arzxsh_cli/lyrics.py`, matching the app; `Store.canonical_id` reverse
mapping matches the app's offline-lyrics ID fix. The backend `/song` resolver validates
the video ID charset/length and returns `source: "link"`, consistent with the CLI's
lyrics suppression.

**Backend posture** (`ytmusic-backend/app.py`) — documented to bind 127.0.0.1 only;
Host allowlist (loopback names), per-run token compared with `hmac.compare_digest` on
API routes, 120 req/min budget on expensive routes, security headers (nosniff,
X-Frame-Options DENY, CSP, referrer-policy) on responses, downloads-dir confinement
for deletes, sanitized download filenames, and a MIME allowlist + nosniff on served
local artwork.
