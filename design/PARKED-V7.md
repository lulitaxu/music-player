# Parked v7 features — preservation note

Branch: `parked/v7-features` (snapshot taken 2026-11-07, mid-v6-build).

Arz's order: "lets first improve and work on our existing features and then we will work on new features."
So 0.6.0 ships polish only. The features below were started during the v6 build, then parked.
They are preserved in the snapshot commit on this branch so v7 starts from them, not from zero.
Do NOT merge this branch wholesale into the v6+ line: MainActivity.kt and Library.kt kept
evolving with polish after the split. Cherry-pick or hand-port the parked hunks listed here.

## What is preserved, and where

### Library.kt (data layer, complete as specced)
- `Playlists.lastUsedAt` on Playlist (+ JSON persistence, `Playlists.touch(id)`) — recency sort support.
- `SavedCollections` — saved remote playlists/albums/artists for Library (workstream 5b), with `savedAt`/`lastUsedAt`.
- `Pins` — pin state for user playlists, Liked, Downloaded, saved collections (workstream 5d).
- `TileRecency` — last-opened stamps for the Liked/Downloaded special tiles (recency sort support).
- `PlayHistory` gains completions + early-skip event lists: `recordCompletion`, `recordSkip`,
  `completions()`, `skips()`, `completionCount()`, `skipCount()` (workstream 6 signals).
- `Taste` — the full on-device taste profile: artist affinity = plays + completions×2 + likes×4
  + downloads×3 − skips×2, 7-day half-life recency decay, cached profile invalidated after a day
  or ≥20 new plays (workstream 6). No UI surfaces were built yet (Daily Mix / Discover /
  "Because you listened to" shelves were NOT started).

### MainActivity.kt (engine wiring only)
- Fields `sessionTrack`, `sessionEnded`.
- In `play()`: outgoing track classified as an early skip when it played >0 and <30% of its
  duration and the session had not ended → `PlayHistory.recordSkip(...)`.
- In `handleCompletion()`: `sessionEnded = true` + `PlayHistory.recordCompletion(...)`.
- Now Playing contains an empty `npRelatedBox` container (plus field) intended for the Related
  Songs sections (refinement 4). The loader was NOT written.

### Drawables (unused in 0.6.0)
- `res/drawable/ic_pin.xml` (pin glyph), `ic_grid.xml`, `ic_list.xml` (Library grid/list toggle).

## Not started at all when parked (v7 builds these fresh)
- Workstream 5 UI: Downloaded auto-playlist page, Save buttons on remote pages, Moods tile,
  Library grid + filter chips + pinning menus.
- Workstream 6 UI: Daily Mix / Discover / Because-you-listened shelves on Listen Now.
- Workstream 7/7c/7d: playlist export/import (backup.json, share-as-text, text/CSV matching,
  YouTube Music link import). No code exists.
- Refinement 4 UI: Top Songs / Similar Songs loaders for `npRelatedBox`.

## What DID ship in 0.6.0 (for orientation)
Spotify reskin, Poppins, theme color (presets + hue slider), ambient artwork color, lyrics
restyle + sync offsets (`LyricOffsets` lives in Library.kt on the main line too — it is polish,
not parked), skeletons, buffering indicators, Now Playing rebuild with lyric line + preview card.
