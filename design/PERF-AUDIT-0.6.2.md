# arzxsh Performance Audit — 0.6.2 (Helio G95 / Realme 8)

**Date:** 2026-11-07 · **Scope:** READ-ONLY audit at Arz's report: "the app is slow" —
clarified as GENERAL sluggishness ("everything, just generally sluggish"), not one surface.
**Codebase:** `~/workspace/music-player`, branch `cross-platform-fixes`, HEAD `d89d99c` (0.6.2).
All line refs are `MainActivity.kt` unless another file is named. No code was changed.

**How to read this:** suspects are ranked by SYSTEMIC impact — costs paid on every
screen / every interaction / continuously — because the complaint is whole-app heaviness.
Surface-specific costs are included but ranked below the systemic ones.
Evidence strength: **CERTAIN** = read directly in code · **LIKELY** = mechanism certain,
magnitude depends on usage/timing · **SPECULATIVE** = plausible, needs device profiling.

---

## S1 — No list recycling anywhere: every screen is one giant hand-built view tree, rebuilt in full on the main thread at every navigation
**Evidence: CERTAIN · Impact: HIGHEST (systemic)**

- There is **no RecyclerView / ListView / GridView anywhere in the app** (grep across all
  Kotlin sources: zero hits). All UI is programmatic: 414 `addView` calls in MainActivity.kt.
- The shell is ONE `ScrollView` whose single child is one `content` LinearLayout
  (showShell, **918–924**). Every screen's entire content lives in that one tree.
- Rows are built fresh per track and never recycled: `addRows` (**2530–2545**) loops
  `addTrackRow` (**2551–2647**); each row is ~7–9 views (row LinearLayout, ImageView,
  weighted text column, 2 TextViews, hairline, menu icon / visualizer / spinner).
- Full builds happen in ONE main-thread block per navigation:
  Home (**1520–1600**: featured card + recents + 12 trending rows + artist circles +
  Made-for-You), a playlist page builds **every** track (**6281–6284** — a 200-track
  playlist ≈ 1,800 views + 200 artwork loads in one block), Search results (**1790–1840**),
  the queue overlay rebuilds all rows on every open.
- Because the screen is a single wrap-content LinearLayout inside a ScrollView, **any
  layout pass re-measures the entire tree** (keyboard open/close, the bottom-bar
  global-layout listener at **1089** refitting padding, font-scale changes). Rows use a
  `0dp + weight 1f` text column (**2597**), so LinearLayout measures row children twice.
- Retention (0.6.1) made tab *returns* cheap, but every *fresh* screen still pays the
  full construction burst. On a Helio G95 this is the classic "every tap hitches for a
  few hundred ms" architecture — it taxes navigation, scrolling (huge display-list tree,
  oversized bitmaps per S3), and memory simultaneously.

**Fix for the follow-up build:** RecyclerView with a real adapter + view-holder pool for
the long lists first (playlist page, Search results, Library, queue, Downloads); rows are
already uniform, so this is a mechanical conversion. Cheaper intermediate if time-boxed:
build lists incrementally in posted chunks (e.g. 20 rows per frame) so first paint is
fast, and drop the weighted measure from rows. Longer term the whole content area wants
to stop being one mega-LinearLayout.

---

## S2 — Library stores re-parse their ENTIRE JSON from SharedPreferences on every call — and they are called on navigation and playback paths
**Evidence: CERTAIN · Impact: HIGH (systemic, grows with the library)**

- `Library.kt`: `Playlists.all` (**61–80**) parses the full playlists JSON — every
  playlist × every track — from the prefs string on **every call. No in-memory cache.**
  `Favorites.liked`/`contains` (**~180–190**) does the same for Liked Songs.
  `PlayHistory.events` parses up to 300 events per call, and `PlayHistory.record`
  (**~230**) **parses all ≤300 events, re-serializes all of them, and writes the whole
  string back — on the main thread, on every `play()`** (MainActivity **3851**).
- Hot call sites on the main thread:
  - `rootFingerprint("Library")` (**7511**) = `Playlists.all` + `Favorites.liked`, and it
    runs inside `saveCurrentRoot()`/`restoreRoot()` — i.e. **on every tab switch and
    every screen render** (`beginScreen` calls `saveCurrentRoot`, **~7560**).
  - `refreshPlaybackUi` (**4992**) → `updateHeart` → `isFavorite` (**3799**) →
    `Favorites.contains` = full Liked parse on **every playback state change**.
  - Home's render block calls `PlayHistory.topTracks(this, 20)` (**~1579**) = full
    300-event parse inside the UI-thread render.
  - Startup `backfillFavorites` (**909**) runs a full-parse `contains` per legacy id.
  - Add-to-playlist menu (**5883**), Library screen (**5933**), Downloads fingerprint
    does per-entry file stats instead (**7519–7521**, see S6).
- The Downloads store proves the fix pattern was known: `Downloads.entries` IS cached
  (`Downloads.kt` **47–49**); the Library stores were never given the same treatment.
- Cost today is milliseconds-to-tens-of-ms per call on a G95, but it is paid *constantly*
  and scales linearly with playlists/likes/history — matching a slowness that creeps up
  as the app is used more.

**Fix:** in-memory caches with dirty-flag invalidation for `Playlists`, `Favorites`,
`PlayHistory` (parse once per process, invalidate on write — mirror `Downloads.entries`).
Make `PlayHistory.record` incremental (keep the parsed list in memory, persist async).
Make fingerprints O(1): stores maintain size/timestamp counters; navigation never parses.

---

## S3 — Artwork pipeline: oversized decodes, a count-based (not byte-based) bitmap cache, and the same image downloaded up to 6 times
**Evidence: CERTAIN · Impact: HIGH (systemic — every row, every screen, every scroll)**

`ArtworkLoader`, MainActivity **81–340**:

- The bitmap cache is `LruCache<String, Bitmap>(128)` (**82**) — sized by ENTRY COUNT,
  not bytes. Sizes in play: rows request `dp(104)` (**2579**) ≈ 280–310px on the Realme 8
  → decode bucket 512 (**103–109**); YouTube "small" art is ~540px, so the sampler leaves
  sample=1 (**304–312**) and each 52dp row ends up holding a **~540×540 ARGB bitmap
  (~1.1MB) displayed in a ~150px slot**. 128 such entries ≈ up to ~140MB of bitmaps;
  the manifest sets no `largeHeap`. Result: heap pressure and GC pauses that stall the
  UI thread — a prime suspect for *general* heaviness, not just scroll jank.
- **No byte-level cache:** `decode()` calls `downloadBytes()` on every miss (**283–284**),
  so each size bucket re-downloads the same URL over the network. One track's art is
  fetched/decoded up to **5 times**: row (512 bucket), mini player (256), Now Playing
  hero (screen-width → bucket 2048), blurred backdrop source (256, **189–213**), ambient
  color sample (256, **204–213**). `PlaybackService` then does a **6th independent
  network fetch** of the same image (`PlaybackService.kt` **287–290**) — it never
  consults ArtworkLoader.
- Everything shares **one 3-thread pool** (**83**): dozens of row decodes queue ahead of
  the Now Playing hero/blur/ambient work, so opening the player or building a list makes
  art straggle in and the hero pop late. The blur is a hand-rolled CPU box blur ×3 passes
  and `dominantVibrant` walks `getPixel` in nested loops (**216–247**) — background
  threads, but they burn the same small SoC the UI thread needs.
- During scroll, the GPU re-samples those 540px bitmaps down to 150px slots every frame
  (CENTER_CROP) — a steady texture-bandwidth tax on the Mali-G76 in every list.

**Fix:** size the LruCache in BYTES (e.g. 24–32MB via `sizeOf = byteCount`); decode rows
at their true display size (52dp → 128/256 bucket — pass the ImageView's real px size,
not dp(104)); add a small `ByteArray` LRU keyed by URL so all buckets share ONE download;
have PlaybackService read from the same cache; split hero/ambient onto their own thread
or prioritize them over row decodes.

---

## S4 — While music plays, the main thread never idles: 33ms FFT loop or a vsync-rate pulse animator, plus a never-sleeping 500ms ticker
**Evidence: CERTAIN (existence) · Impact: MEDIUM-HIGH (systemic during playback — the app's main state)**

- **Main ticker** (**832–840**): posted in `onCreate` (**890**), reposts every 500ms
  unconditionally, removed **only in onDestroy** (**7626**) — it runs while backgrounded
  and screen-off too. Per tick: listening accumulation (a prefs write every ~5s of play),
  `updateProgress` (well-guarded, cheap), gapless arm/warm checks that read prefs and can
  stat files (S5/S6).
- **Live visualizer** `vizTick` (**743–752**): every **33ms** while playing — `getFft` +
  band math + `scaleY` writes to the mini bars **and every registered row bar set**
  (**5270–5272**), including sets owned by retained/detached trees.
- **Decorative pulse fallback** — the path taken whenever RECORD_AUDIO is denied or live
  viz is off: a `ValueAnimator` with `INFINITE` repeat at display refresh rate
  (**5308–5335**) writing `scaleY` to the mini set plus **up to 25 registered bar sets**
  (registry cap, **7316–7319**) **every frame**, most of them invisible or detached.
  Transform-only writes are individually cheap, but on a G95 a perpetual vsync animator
  plus the 500ms ticker keeps the UI thread and GPU compositor permanently busy while
  the user is trying to scroll or tap — frame budget stolen from every interaction.
- Crossfade tick (100ms, **545–568**) and the lyrics tickers are properly scoped
  (fade-only / overlay-visible-only) — not suspects.

**Fix:** pause the 500ms ticker when the activity is stopped and nothing is playing;
drive bar writes ONLY for sets attached to the window (prune the registry on detach —
`OnAttachStateChangeListener` — and skip detached sets in both viz paths); consider
capping the decorative pulse at ~30fps via a Choreographer-throttled updater instead of
a raw ValueAnimator.

---

## S5 — Single-thread executors serialize unrelated work; gapless warm-up can queue ahead of a user's tap
**Evidence: CERTAIN (mechanism) · Impact: MEDIUM-HIGH (interaction latency spikes)**

- One single-thread `executor` (**463**) carries: Home fetch, the Made-for-You mix,
  artist/album/playlist pages, Downloads-tab jobs — and **Search, whose five source
  calls run strictly sequentially** on it (**1710–1790**: Audius → YT songs → artists →
  albums → playlists). Search results render only after the *sum* of five round trips,
  and any other queued job delays them further. The calls are independent — nothing
  about them requires serial execution.
- `playerExecutor` (**471**) is also single-threaded, and it is shared by the foreground
  resolution ladder (`advanceYtLadder`, **3985**) and the 0.6.1 gapless machinery:
  `maybeWarmAfterNext` submits a **full** `StreamResolver.resolveYoutube` walk
  (**4431**) — multiple Innertube contexts, 8s timeouts (`YtmApi.kt` **981–982**) — and
  `maybeArmGapless` another (**~4548**). FIFO means **a tap-to-play issued while a
  warm-up walk is mid-flight waits seconds behind background work the user never asked
  for.** The gapless prep then builds a second ExoPlayer and buffers the next stream
  while the current one plays (**4564–4618**) — bandwidth/CPU contention with the
  foreground stream on a weak radio + SoC (prep contention itself: LIKELY impact).

**Fix:** parallelize Search's independent calls on a small pool (render once, when all
settle); give gapless warm/arm its own low-priority executor, or make warm jobs
cancellable and have foreground resolution preempt (check a generation token inside the
walk); move Downloads-tab jobs off the UI fetch executor.

---

## S6 — Per-row synchronous disk IO on the main thread during list builds
**Evidence: CERTAIN · Impact: MEDIUM (multiplies S1)**

- `addTrackRow` calls `Downloads.isDownloaded(this, track)` per row (**2604**) →
  `Downloads.localFile` (`Downloads.kt` **81–85**): a **linear scan of all download
  entries plus `File.exists()` + `length()` stat syscalls**, per row, on the UI thread,
  inside the same build block as S1. (`dir()` even calls `mkdirs()` per call,
  `Downloads.kt` **46**.) A 100-row playlist open = 100+ stats + 100 scans before first
  paint. `maybeArmGapless`/`maybeWarmAfterNext` also call `localFile` from the ticker.
- `rootFingerprint("Downloads")` runs `Downloads.storageBytes` (a `.length()` stat per
  entry, `Downloads.kt` **84–85**) on every navigation save/restore.

**Fix:** snapshot the downloaded-key set ONCE before building a list (single pass over
the cached entries, zero stats — trust the index) and pass it into row binding; verify
files lazily only when actually playing/downloading.

---

## S7 — Startup builds four extra full screens before the first frame
**Evidence: CERTAIN · Impact: MEDIUM (cold-start feel; sets the tone for "slow app")**

`onCreate` (**870–891**) runs, all on the main thread before Home can paint:
`loadRecents` JSON parse (**872**), `YtmApi.initStore`, `Downloads.reconcileOnStart`
(directory listing + `.part` deletes, **876**), `backfillFavorites` (**877** — entries
parse + `LocalTracks.all` parse + a full-parse `Favorites.contains` per legacy id,
**909**), then `showShell()` synchronously constructs the content area, bottom bar,
**and the complete Now Playing tree** (`buildNowPlaying`, **3302+**), queue overlay,
lyrics overlay and menu overlay (**1081–1084**). Cold start pays for four screens the
user may never open, then Home's own fetch + S1 build burst follows.

**Fix:** build NP/queue/lyrics/menu overlays lazily on first open; move reconcile +
backfill to a background thread after first frame; keep onCreate to shell + Home skeleton.

---

## S8 — Tab retention (0.6.1) trades rebuild cost for resident memory: up to 5 full trees + their bitmaps alive at once
**Evidence: CERTAIN (structure) · Impact: MEDIUM-LOW (LIKELY contributor via GC/memory pressure)**

- Retention (**7436–7560**) holds the detached child trees of up to 4 tab roots — every
  row view and every ImageView bitmap — alongside the visible tree, the Now Playing tree
  and the overlays. Combined with S3's count-based cache, resident bitmap memory is the
  app's biggest footprint; on the Realme 8 that means GC churn during use and a higher
  chance the OEM killer reaps the backgrounded process (the already-noted background
  limitation).
- Restore re-parents every child (`removeAllViews` + add-all, **~7546**) → one full
  re-measure burst per tab switch (still much cheaper than rebuild + refetch — retention
  is a net win; this is about its memory price, not its existence).

**Fix:** retain at most 2 roots (Home + last-used), or retain data + scroll position but
release retained trees' artwork bitmaps on save (rows reload art from the byte cache on
restore). Surface `ArtworkLoader.cacheBytes()` (already tracked) in Settings so the
memory budget is visible.

---

## S9 — Now Playing layer stack: 4 full-screen layers + a radial gradient + an elevation shadow
**Evidence: CERTAIN · Impact: LOW-MEDIUM (NP-local, not whole-app)**

`buildNowPlaying` (**3308–3345**): full-screen blurred-backdrop ImageView (with a color
filter) + full-screen gradient shade view + full-screen ambient radial-gradient view
(**3322–3324**) + the scroll content + the glass control container (translucent fill +
stroke), and the hero artwork carries `elevation = dp(24)` (**~3429**) — a real-time
shadow. Every NP scroll frame composites ≥4 full-screen layers; radial gradients and
full-screen filtered bitmaps are among the most expensive fills on Mali GPUs.
Glass on the *chrome* (mini player, tab bar, sheets) is just argb fills (**1159–1170**)
— cheap, NOT a suspect; the queue overlay's translucent full-screen backdrop is the
same class as NP but only while open.

**Fix:** bake backdrop + shade into one layer (pre-darken the blurred bitmap at decode
time, drop the separate shade view and color filter); pause the ambient layer while
NP is actively scrolling; replace the artwork's elevation shadow with a static stroke.

---

## S10 — play()'s main-thread prelude: two JSON write cycles before resolution even starts
**Evidence: CERTAIN · Impact: LOW (fixed toll per tap/skip; network still dominates tap-to-audible)**

`play()` (**3833–3860**) on the UI thread: `saveRecent` (serialize ≤10 — trivial),
**`PlayHistory.record` (S2: full ≤300-event parse + re-serialize + prefs write)**,
`Downloads.localFile` stat, `refreshPlaybackUi` (→ S2 heart parse + 3 artwork loads).
Then the ladder runs (CACHE stage instant when warm; cold stages are sequential network
RTTs by design — that part is inherent, not a defect). Every skip repeats the toll.

**Fix:** move `record`/`saveRecent` persistence onto an order-preserving background
executor (in-memory state updates stay synchronous); the rest is fixed by S2/S6.

---

## Explicitly NOT suspects (checked, cleared)

- `updateProgress` (**3751–3790**) — change-guarded, NP-visibility-guarded, seek-bar +
  2 labels only. Clean.
- Lyrics tickers — 150ms loop self-stops when the overlay hides (**6834–6845**);
  `updateLyricsHighlight` early-returns when hidden (**6966+**). Clean.
- Crossfade tick — runs only during a fade. Clean.
- `PlaybackService` — fully event-driven, no polling; artwork once per track on its own
  executor, capped 256px (**104, 273–310**). Its only perf sin is S3's duplicate fetch.
- Skeleton animator — alpha-only, one shared animator, cleared on rebuild (**1389+**).
- Glass chrome fills — plain argb GradientDrawables, no runtime blur anywhere on
  scrolling surfaces (the only blur is the precomputed NP backdrop). Clean.
- SharedPreferences *reads* in hot paths (25 `getPreferences` sites) — after first load
  these are in-memory map reads; not a meaningful cost. The problem in S2 is the JSON
  *parsing* of the returned strings, not the reads.

## Suggested order for the fix build

1. **S2 store caches** (small, mechanical, pays off everywhere immediately).
2. **S3 artwork budgets** (byte-sized cache, true-size row decodes, shared byte cache).
3. **S1 RecyclerView for playlist/Search/Library/queue** (the big structural win).
4. **S5 executor split + parallel Search**; **S6 downloaded-set snapshot** (pairs with S1).
5. **S4 loop discipline** (ticker pause, visible-sets-only bar writes).
6. **S7 lazy overlays**, **S8 retention trim**, **S9 NP layer flatten**, **S10 async stats writes**.
