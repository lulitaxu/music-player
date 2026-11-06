# arzxsh Reference UI Spec — Apple Music × Spotify, detail level

**Purpose.** This is the build-to reference for arzxsh's UI. It was compiled from real
screenshots of the current Apple Music (iOS 26 / 26.4) and Spotify (Android + iOS) apps and
from credible step-by-step breakdowns of those apps. Builders are held to the DETAILS
CHECKLIST at the end; a reviewer should be able to verify an APK screenshot against it line
by line.

**Priority.** Apple Music is arzxsh's primary design language. Spotify is the reference for
features Apple Music lacks or hides (on-player shuffle/repeat, queue editing, sleep timer,
download state machine, complete context menu). Where the two disagree, both are documented
and the Apple-fitting choice is marked **[APPLE-FIRST]**.

**Citation convention.** Every claim cites a file in `refs/` and/or a source URL.
Sizes are in dp/pt as rendered on a phone. Anything measured off a screenshot is marked
*(estimated from screenshot)*. Anything no source could evidence is marked **GAP** — do not
invent a value to fill a GAP; flag it instead.

**Palette anchors (observed in refs, not official tokens).**
- Apple Music accent: bright pink-red (tab selection, links, Play/Shuffle labels). arzxsh's
  approved `#FC3C44` is the same family. Foreground on tinted surfaces is only ever white or
  black — Apple picks per artwork (Mayo, on iOS 26.4 pages).
- Spotify accent: bright green *(approx. #1ED760 as rendered in refs)* — used ONLY for active
  states, the playing-track title, and downloaded marks. Never as a general decoration color.
- Spotify surfaces: near-black `#121212`-family backgrounds *(estimated from screenshot)*;
  Apple surfaces: artwork-derived blurred wash or artwork-matched solid tint.

## Reference index (what each file proves)

| File | Proves |
|---|---|
| `refs/apple-tab-bar-miniplayer-two-states.png` | iOS 26 glass tab bar + mini player, expanded vs scroll-minimized inline state (Benjamin Mayo) |
| `refs/apple-liquid-glass-closeup.webp` | Real Liquid Glass: content color bleeds/refracts through bar and mini player; selected tab gets an inner tinted pill |
| `refs/apple-now-playing-and-queue.png` | Album page (Play/Shuffle pill pair, download arrow), Now Playing layout, Playing Next with mode buttons + AutoPlay section |
| `refs/apple-album-playlist-ios26-4.webp` | iOS 26.4 fullscreen album/playlist pages: artwork-matched background, glass header buttons, Play pill + circular shuffle/download |
| `refs/apple-playlist-ios26-4.webp` | Same, light tint (black foreground), track rows, minimized bottom chrome (Home circle + inline mini player + search circle) |
| `refs/apple-lyrics-view.jpg` | Lyrics mode: mini header, fading neighbor lines, translation line, floating Sing pill, reduced control set |
| `refs/apple-home-lyrics-translation.jpg` | Home tab sections; lyrics with pronunciation + translation lines and their hide menu |
| `refs/spotify-now-playing-and-menu.webp` | Spotify Android Now Playing full layout + the complete ••• menu with header and item order |
| `refs/spotify-queue-sheet.webp` | Queue as bottom sheet; green playing title with equalizer glyph; drag handles; Shuffle/Repeat/Timer tiles at sheet bottom |
| `refs/spotify-repeat-states.webp` | Repeat off / all / one states (green + "1" badge); transport row order; green check = added |
| `refs/spotify-shuffle-states.webp` | Shuffle off (white) / on (green + dot) / Smart Shuffle (green + sparkle) |
| `refs/spotify-sleep-timer-sheet.jpg` | Sleep timer bottom sheet (presets, remaining-time title, Turn off timer), confirmation snackbar, green active timer icon |
| `refs/spotify-lyrics-card-and-fullscreen.webp` | Lyrics card with inverted colors (sung line white, others dark), share/expand icons; fullscreen lyrics with mini progress + pause |

Text sources are listed at the end (Sources). Key ones: Cult of Mac's Apple Music button
guide, i5apps' Apple Music symbols guide, Apple's iPhone User Guide (via FCC mirror),
Benjamin Mayo's iOS 26 Music writing, 9to5Mac on iOS 26.4, Spotify Community's official
Play Queue KB article, Beebom's Spotify icon guide, and a June 2026 screenshot of Spotify's
track menu posted to Threads.

---

## 1. Global chrome — tab bar + mini player

### 1.1 Apple Music (iOS 26) **[APPLE-FIRST]**
Source: `apple-tab-bar-miniplayer-two-states.png`, `apple-liquid-glass-closeup.webp`,
WWDC25 session 284 notes, Apple HIG "Tab bars" (rev. 2026-06-08).

- **Two separate floating glass pieces**, not one bar: (a) a capsule tab bar with the tabs,
  (b) a detached circular Search button at the right. Both float above content with side
  margins; content scrolls underneath them.
- **Tab order:** Home, New, Radio, Library, then the separate Search circle. Icon above a
  small label on each tab (house, four-square grid, radio waves, stacked-discs/music note).
- **Selected tab:** icon + label turn Apple-red, and the tab sits inside a soft lighter pill
  highlight within the glass (clearly visible in the glass close-up). Unselected tabs are
  dark gray/black glyphs.
- **Mini player is a separate glass capsule ABOVE the tab bar** (Apple calls it the tab bar's
  "accessory"). Left to right: small square artwork (rounded corners), two-line text
  (title semibold, artist gray, both truncated), Pause/Play button, Next button. No progress
  bar on the mini player.
- **Scroll-minimized state:** scrolling down shrinks the tab bar to just the selected tab's
  icon in a small glass circle, and the mini player animates down to sit **inline** in the
  same row (Home circle | narrower mini player | Search circle). In inline form the mini
  player drops the Next button and truncates the title sooner. Exit the minimized state by
  tapping a tab or scrolling back to the top (HIG). Scrolling up re-expands.
- **Glass is real**: the bar refracts and tints with the content behind it (playlist art
  bleeds orange/red through both capsules in the close-up). A flat translucent fill is
  visibly wrong next to this reference.
- Gestures: swipe left/right **on the mini player** skips to previous/next track (added in
  iOS 26.1). Tap mini player body → full Now Playing. Tap artwork/buttons only for their
  own actions.

### 1.2 Spotify (contrast)
Source: Beebom icon guide; `spotify-*.webp` status bars/bottom edges.

- Bottom nav is a **flat, non-floating bar** with a top gradient fade over content: Home,
  Search, Your Library (a 4th "Premium" tab appears for free accounts). Icon + label;
  active tab = filled white icon; inactive = gray outline icon. No glass, no separate
  search button.
- Mini player: a rounded rect "card" that sits directly above the nav bar, tinted with a
  color extracted from the artwork; contains artwork, title/artist, a device icon, the
  add (+) / check button, play/pause, and a **thin progress line along its bottom edge**
  (Apple's has none). Swipe on track rows elsewhere can add to queue; the mini player
  itself is tap-to-expand.

**arzxsh verdict:** keep the Apple two-piece glass chrome (already approved); the one
Spotify behavior worth stealing is the mini player's bottom-edge progress line — Apple
omits it, so this is a deliberate deviation if adopted. Marking it is optional; do not add
it silently.

---

## 2. Now Playing — Apple Music **[APPLE-FIRST]**
Sources: `apple-now-playing-and-queue.png` (panel 2), `apple-lyrics-view.jpg`, i5apps,
Cult of Mac.

Vertical order, top to bottom:
1. **Grabber handle**: short, centered, rounded bar at the very top (the sheet can be
   dragged down to dismiss).
2. **Artwork**: large square, nearly full content width, rounded corners
   *(~12dp radius, estimated from screenshot)*, soft drop shadow. **It visibly shrinks to a
   smaller size when playback is paused and grows back on play** (see Motion §13.3).
3. **Title row**: song title, semibold, left-aligned *(~20sp, estimated)*; artist name
   directly under it in ~55% white. At the right of this row: a **star (Favorite)** button
   and a **••• in a circle** (More) button (`apple-home-lyrics-translation.jpg` header,
   `apple-now-playing-and-queue.png`).
4. **Scrubber**: thin rounded track *(~5dp tall, estimated)*, filled portion white, rest
   translucent white. Elapsed time at left under the bar, remaining time at right shown as a
   negative (`-0:11`), both small *(~12sp, estimated)* and dimmed. At rest there is **no
   knob**; the bar thickens while scrubbing (behavior; exact growth GAP).
5. **Transport row — three buttons only**: Previous, Play/Pause, Next. Large filled glyphs
   (double-triangle with bar; play/pause), spaced wide and centered. **There is no shuffle
   and no repeat on this screen** (see §4 — they live on Playing Next). Touch-and-hold
   Previous/Next fast-seeks (iPhone User Guide).
6. **Volume row**: small speaker icon (no waves), slider, speaker icon (full waves). Same
   thin-slider styling as the scrubber.
7. **Bottom action row — three items spread edge to edge**: Lyrics (speech-bubble-with-quote
   icon) at left, AirPlay/output at center, Playing Next (list icon) at right. When an
   output device is connected, its name appears in small text under the AirPlay icon
   ("JBL TUNE500BT" in the ref). The Lyrics icon renders **dimmed when the track has no
   lyrics**; when lyrics are showing it is filled/active.
- **Background**: the artwork, heavily blurred and darkened, fills the screen (or an
  artwork-matched tint in the 26.4 style). Text is white on dark washes.
- **More (•••) menu** from this screen contains (i5apps, order varies by context):
  Favorite, Add to Library, Download, Add to a Playlist…, Create Station, Share Song…,
  Suggest Less Like This, plus playback items. This menu is a standard iOS menu anchored to
  the button, not a full-screen sheet.

### 2.1 Where arzxsh deliberately deviates (decided in v4, do not "fix" silently)
Arz ordered Spotify-style shuffle/repeat flanking the transport row and a ••• action set on
Now Playing. That contradicts Apple's model (§2 item 5). The spec records the deviation so
a reviewer does not flag it as a bug: **if the buttons are on Now Playing, they must follow
Spotify's placement and state treatment (§3), not an invented one.**

---

## 3. Now Playing — Spotify (feature reference)
Sources: `spotify-now-playing-and-menu.webp`, `spotify-repeat-states.webp`,
`spotify-shuffle-states.webp`, Beebom.

Vertical order:
1. **Header row**: chevron-down at left (minimize). Centered, two lines: a small all-caps
   gray source label ("PLAYING FROM ALBUM" / "PLAYING FROM YOUR LIBRARY" / "PLAYING FROM
   PODCAST") over the source name in semibold white. **Vertical ••• at top-right** (Android;
   horizontal on iOS).
2. **Artwork**: large rounded square. For tracks with Canvas, a looping video fills the
   whole screen instead, and the title row gains a small artwork thumbnail at its left.
   A small dark pill "Switch to video" (play-in-rect icon + label) sits just above the
   title row when video exists.
3. **Title row**: title semibold *(~20sp, estimated)*, artist gray beneath. Right-aligned
   circular **(+) button**: outline plus = not saved; tapping adds to Liked Songs and the
   button becomes a **filled green circle with a white check**. (This replaced the old
   heart; green check is the current treatment in all refs.)
4. **Progress bar**: thin *(~4dp, estimated)*, white fill on translucent white; small round
   knob at the playhead; elapsed time left, total time right (no negative remaining),
   small gray text.
5. **Transport row — five controls in one row**: **Shuffle** (far left), Previous,
   Play/Pause (**large white filled circle with black glyph**, roughly 1.6× the width of
   the side buttons, *estimated*), Next, **Repeat** (far right). Shuffle/Repeat are white
   when off.
   - Shuffle ON: icon turns **green with a small dot beneath it**. Pressing again cycles to
     **Smart Shuffle**: green, with a sparkle/star worked into the icon.
   - Repeat states cycle: off (white) → Repeat All (**green + dot**) → Repeat One
     (**green + dot + small "1" badge**) → off.
6. **Bottom action row**: Devices/Connect icon at **far left**; at the right, **Share**
   then **Queue** (three horizontal lines, the middle styling of a list/play glyph). This
   row is smaller and dimmer than the transport row.
7. **Below the fold (the screen scrolls)**: a **Lyrics card** in a solid artwork-extracted
   color with a "Lyrics" header (see §6), then "About the artist" card, etc. The transport
   area stays put while cards scroll beneath — the first card peeking at the bottom edge is
   the affordance.
- **Background**: a smooth vertical **gradient of colors extracted from the artwork**
  (not a blur). Flat, cheap to render — see Performance §14.
- Podcast episodes change the transport row: playback **speed (1×)** at far left,
  **back-15s**, play/pause, **forward-15s**, and a **sleep-timer (stopwatch)** icon at far
  right (`spotify-sleep-timer-sheet.jpg`).

---

## 4. Queue / Playing Next

### 4.1 Apple Music — Playing Next **[APPLE-FIRST]**
Sources: `apple-now-playing-and-queue.png` (panels 3–4), Cult of Mac, i5apps, User Guide.

- Opened by the list icon at Now Playing's bottom-right. The Now Playing controls
  **compress rather than disappear**: a mini header row appears at top (small artwork,
  title, artist, •••), followed by the queue; the transport, volume and bottom row stay
  visible below the list.
- **Section header**: "Playing Next" bold, with the source on the next line
  ("From Origins (Deluxe)"). A **Clear** action sits at the section's top-right and
  empties only the manually queued part.
- **Mode button row directly under the header — four toggles left to right: Shuffle,
  Repeat, AutoPlay (∞), AutoMix.** Active mode gets a **filled rounded-rect highlight**
  behind the glyph (Shuffle is shown active/highlighted in the ref); Repeat shows its "1"
  state on the glyph. These are the ONLY place these toggles exist on iPhone.
- **Rows**: small artwork thumb, title, artist; a **drag handle (≡) at the right edge** of
  every row. Press-and-hold the handle to reorder. **Swipe a row left to remove it** from
  the queue.
- **Continue Playing section** below: the rest of the album/playlist you started, in order.
  It is visually the same row style — the section header is what separates it.
- **AutoPlay section** (when AutoPlay is on): headed by the ∞ glyph + "AutoPlay" and an
  explanation line ("Similar songs will keep playing"); its rows carry a small ∞ marker.
  Turning AutoPlay off removes the section.
- **History**: scrolling **up** past the current track reveals previously played songs;
  tapping one jumps back to it.

### 4.2 Spotify — Queue
Sources: `spotify-queue-sheet.webp`, Spotify Community Play Queue KB (official).

- Opened by the queue icon on Now Playing (and from the mini player's queue icon). It is a
  **bottom sheet** over the dimmed Now Playing screen, with a grabber at top.
- Sheet header: "**Queue**" bold, with "Playing <source>" in gray beneath.
- **Sections, in order**: **Now Playing** (the current track only — title in **green**,
  preceded by a small **animated equalizer-bars glyph**, artist gray; a circular
  pause/play button at the row's right), **Next in Queue** (tracks the user manually added
  via "Add to Queue" — these play before everything else), **Next Up** (the rest of the
  source context; if Autoplay is on, its generated tracks land here).
- **Rows**: artwork thumb left; title; explicit badge = small filled square with "E"
  placed before the artist line; **drag handle (≡) at the right edge**. Hold the handle to
  reorder (Premium-gated on real Spotify; arzxsh has no gate).
- **Edit mode**: an **Edit** button (top-right of the sheet) turns rows multi-selectable
  with **empty circles at the left**; a bottom bar offers **Remove** and **Move up**
  (Move up sends selected tracks to the top of Next in Queue). "Clear queue" empties the
  Next in Queue section.
- Newer builds pin three chunky tiles at the sheet's bottom: **Shuffle | Repeat | Timer**,
  each an icon over a label inside a rounded dark-gray tile (`spotify-queue-sheet.webp`).

**arzxsh verdict:** Apple's model fits the design language (modes at the top of the queue,
sections named by intent). Spotify's contributions worth taking: the green + equalizer
treatment of the current row, the Edit/multi-select mode, and the Timer tile (Apple has no
sleep timer at all).

---

## 5. Track context menus

### 5.1 Apple Music — long-press / ••• menu **[APPLE-FIRST]**
Sources: iGeeksBlog (long-press item list), i5apps (Now Playing More menu), User Guide.

- **Long-press** any track/album/playlist row opens the menu **with a preview card** of the
  item (artwork + title) floating above the menu list; the rest of the screen blurs.
  The ••• button opens the same actions without the preview.
- Song menu items (grouped, icons at left of each row; destructive/suggest-less items at
  the bottom): Favorite (star), Add to Library / Remove from Library, **Download** (or
  Remove Download), Add to a Playlist…, **Play Next**, **Play Last** (Apple's name for add
  to queue end), Create Station, **Share Song…**, Suggest Less Like This, and on iOS 26,
  **Pin**. Exact order varies by context — GAP for a single canonical order; the item set
  and grouping above are the requirement.
- Behavior: choosing Play Next/Play Last shows no confirmation UI; the change is visible
  next time the queue opens. Download starts immediately and the row gains the downloaded
  state (§7).

### 5.2 Spotify — track menu (the completeness reference)
Sources: `spotify-now-playing-and-menu.webp` (Now Playing •••, Android, verified visually),
Threads screenshot of a track-row menu (June 2026), Beebom.

- Presentation: a **full-height dark sheet** (not a popover). Header row at top: small
  artwork thumb, title semibold, "Artist • Album" gray beneath. Menu rows: **outline icon
  at left, white label**, generous row height *(~52dp, estimated)*. No section dividers in
  the music menu.
- **Now Playing menu order (verified from screenshot):**
  1. Add to Liked Songs (its icon is the purple-gradient heart tile)
  2. Add to playlist
  3. Hide this song
  4. Add to Queue
  5. Go to album
  6. Go to artist
  7. Share
  8. Start a Jam
  9. **Sleep timer**
  10. Go to song radio
  11. View song credits
  12. Show Spotify Code
- **Track-row menu order (2026 screenshot; context-dependent items swap in/out):**
  Add to playlist, Hide in this playlist, Add to Queue, Go to Queue, Go to album,
  Go to artist, Explore SongDNA (with a green "Beta" pill badge), Start a Jam,
  Exclude track from your taste profile, Go to song radio, View song credits,
  Show Spotify Code.
- Rules visible across both: library actions first, queue actions early, navigation
  (album/artist) in the middle, share/social lower, radio/credits/code last. Sleep timer
  lives **only** in the Now Playing menu, near the bottom (see §6).

---

## 6. Sleep timer (Spotify only — Apple Music has none)
Sources: `spotify-sleep-timer-sheet.jpg`, Spotify newsroom how-to (2022-08-02) as cited by
robots.net, LuxRise/SolveYourTech step guides (checked Aug–Sep 2026).

- **For music**: Now Playing → ••• (top right) → **Sleep timer** (near the bottom of the
  menu). **For podcasts**: a dedicated stopwatch/moon icon sits in the transport row
  itself (far right).
- **Picker**: a bottom sheet titled "Sleep timer". Options as plain text rows:
  **5, 10, 15, 30, 45 minutes, 1 hour, End of track** (podcasts: "End of episode").
  When a timer is already running, the sheet title becomes "**Sleep timer – 25 min left**"
  (remaining time in the title) and a **Turn off timer** row appears at the bottom.
- **Confirmation**: a bottom **snackbar** in a white rounded rectangle with dark text:
  "Your sleep timer is set." (older guides quote "OK, your sleep timer is set." — the
  screenshot wins). It auto-dismisses; nothing else changes on screen.
- **Active-state visibility**: on the podcast player the timer icon turns **green** while
  counting. For music, reopening the ••• menu shows the remaining time next to "Sleep
  timer" (with a green moon glyph in some builds). Playback **fades out** at expiry rather
  than hard-cutting (LuxRise). The timer does not persist between sessions.

**arzxsh verdict:** adopt Spotify's flow wholesale (menu entry + sheet + snackbar), but
present the entry with Apple's menu styling per §5.1.

---

## 7. Downloads and their states

### 7.1 Apple Music **[APPLE-FIRST]**
Sources: `apple-now-playing-and-queue.png` (panel 1), `apple-album-playlist-ios26-4.webp`,
`apple-playlist-ios26-4.webp`, i5apps.

- Entry points: a **download arrow** button in the album/playlist header area (down arrow;
  in the 26.4 layout it is one of the circular glass buttons beside Play), in the ••• menu
  ("Download"), and per-track via long-press menu.
- Action-row layout on album/playlist pages (26.4): circular **Shuffle** button, wide
  **Play** pill, circular **Download/Add** button — three in a row, centered.
- States: not downloaded = down-arrow glyph; downloading = circular progress replaces the
  glyph; downloaded = the button shows a **check / filled state**, and in Library the item
  appears under the **Downloaded** category. In track lists, a downloaded track is marked
  by a small down-arrow glyph near the row (visible in Library's Downloaded views; exact
  in-row placement in album lists is a GAP in the stills collected).
- Removing: •••/long-press → Remove Download; the glyph reverts. No toasts anywhere —
  state change is the feedback.

### 7.2 Spotify (the state-machine reference)
Sources: Beebom (download button item), Spotify download guides (GB Times roundup of
Spotify's documented behavior), `spotify-sleep-timer-sheet.jpg` iconography family.

- Entry point: playlist/album page action row — a **down-arrow-in-circle** button beside
  the play/shuffle cluster; Liked Songs has the same button. Individual tracks download via
  their ••• menu or by downloading their container.
- **Three states, strictly:** (1) Not downloaded — gray/outline arrow. (2) Downloading —
  the arrow is replaced by a **circular progress ring**, and each track row shows its own
  progress; a status line can appear under the page header. (3) Downloaded — the arrow
  turns **solid green**; every downloaded track row shows a **small green down-arrow under
  its title/artist text**.
- Tapping the green button again offers removal of the downloads. Your Library has a
  **Downloaded** filter chip (§10.2) that shows only offline content.

**arzxsh verdict:** implement Spotify's three-state machine with Apple's glyph placement
(header circle button + row marker). A single icon swap with no progress state is the most
common clone failure (see §15).

---

## 8. Home / Listen Now
Sources: `apple-home-lyrics-translation.jpg`, `apple-tab-bar-miniplayer-two-states.png`,
Mayo.

### 8.1 Apple Music ("Home" tab in iOS 26; "Listen Now" in older labels)
- Large title at top ("Home"), **profile avatar circle at top-right**.
- Content is a vertical stack of **titled sections** ("…For You", "Stations for You",
  "New Releases", "Recently Played"…). Section headers are bold *(~20sp, estimated)*,
  left-aligned, with the section content as a **horizontally scrolling row of cards**
  that peeks the next card at the right edge.
- Card anatomy: square artwork (rounded corners), then **two caption lines below**:
  title (semibold, one line, truncated), subtitle (gray — artist / "Radio Station" /
  year). Personalized-mix cards instead carry the caption **on** the artwork's bottom
  band in white text over a tint (visible in Mayo's ref: "The Wanted, Take That, Ed
  Sheeran…" over orange).
- Everything scrolls **under** the floating glass chrome; the last section needs bottom
  padding equal to mini player + tab bar + margin so it can clear them (the v3 bug Arz
  reported — songs sitting under the bars — is exactly a missing-inset failure).
- Spotify Home differs: filter chips row under the header (All / Music / Podcasts),
  a 2-column grid of small recent-item tiles (artwork left, title right), then section
  shelves. Not adopted; noted for completeness. (Beebom home icon entries; chips are
  visible in current builds — GAP for a saved screenshot.)

## 9. Search
Sources: `apple-tab-bar-miniplayer-two-states.png` (search circle), Beebom (Spotify search
items 16–17), iGeeksBlog (Apple lyric search).

### 9.1 Apple Music
- Search is its **own tab**, reached from the detached glass circle at the bottom right —
  it is not inside the tab capsule.
- Page: large "Search" title; a rounded search field; below, a **"Browse Categories"
  grid**: two columns of wide, artwork-tinted tiles with the category name in bold white
  at the bottom-left.
- Typing switches to results with scope segments (Apple Music / Your Library) and grouped
  results: Top Results first, then Songs / Albums / Artists rows in the standard row
  style. Lyric-line search is supported (a line of lyrics finds the song).
- Recent searches appear as a simple list under the field when it's empty.

### 9.2 Spotify
- Search tab: a **white rounded search field** at the top ("What do you want to listen
  to?") with a **camera icon at its right end** (scans Spotify Codes) — Beebom items 16–17.
- Below: "Browse all" — two-column grid of **bright solid-color category tiles**, label
  top-left, with a small album-art thumbnail rotated ~25° clipped at the tile's
  bottom-right corner. This rotated-art tile is a signature detail clones miss.
- Results page uses filter chips (All, Songs, Playlists, Albums, Artists…) pinned under
  the field.

## 10. Library
Sources: iGeeksBlog/zicos (pins), Apple HIG tab-bar notes, Beebom (Your Library items
18–24), `apple-playlist-ios26-4.webp` (Library tab icon).

### 10.1 Apple Music — Library
- Large "Library" title; profile avatar top-right; an Edit/••• affordance for sorting.
- **Pins first (iOS 26)**: up to **six** pinned items (playlists, albums, artists, songs)
  pinned via long-press → Pin, shown at the very top.
- **Category list**: full-width rows with a **red icon at left, label, chevron at right**:
  Playlists, Artists, Albums, Songs, Genres, Downloaded, Music Videos. Red icons on white/
  dark rows are the recognition detail.
- Below: **Recently Added** as a two-column artwork grid (artwork, title, artist).
- Playlists live one level down (Library → Playlists), with playlist folders (iOS 26).

### 10.2 Spotify — Your Library
- Header row: **profile avatar at left** (opens the account drawer), "Your Library" title,
  then **search (magnifier)** and **+ (create)** icons at right.
- **Filter chips row** under the header: Playlists / Artists / Albums / Downloaded /
  Podcasts — pill chips, selected chip gets a light fill; an X appears on the selected
  chip to clear the filter.
- **Sort row**: a sort icon + current sort label ("Recents"); tapping opens Sort by:
  Recents, Recently added, Alphabetical, Creator. A **grid/list toggle** sits at the
  row's right.
- Rows: large artwork thumb, title, a sub-line "Playlist • Owner" / "Artist"; **pinned
  items show a small green pin glyph**; downloaded items show the small green arrow.
  Liked Songs is always the first pinned row with its purple-gradient heart tile.

## 11. Artist page
Sources: `spotify-lyrics-card-and-fullscreen.webp` (About the artist card), Beebom,
iGeeksBlog (Apple artist long-press items). *Artist headers are the weakest-evidenced area
in the stills collected; treat exact header geometry as estimated.*

### 11.1 Apple Music
- Full-bleed artist photo header; the artist name in very large bold type overlaid at the
  bottom-left of the photo; the page background below is system background (26.4 tinting
  applies to albums/playlists).
- Sections: **Latest Release** (wide row: artwork + release type/date + title), **Top
  Songs** (numbered/standard rows), then horizontal shelves: Albums, Singles & EPs,
  Music Videos, Similar Artists (round artist photos with names under).
- A Play/Shuffle pair (same pills as album page) appears with the Top Songs section.

### 11.2 Spotify
- Header photo with huge name bottom-left; under it: **monthly listeners** in small gray
  text. Action row: **Follow** (outline pill; becomes "Following"), •••, and at the right
  a **shuffle icon + large green play disc**.
- **Popular** list: rank number, artwork thumb, title, **play count** in gray under the
  title; ••• at right.
- Below: Discography (filter chips: Popular releases / Albums / Singles), About card
  (photo, monthly listeners, bio excerpt — `spotify-lyrics-card-and-fullscreen.webp` shows
  this card living under Now Playing too).

## 12. Album / Playlist page
Sources: `apple-album-playlist-ios26-4.webp`, `apple-playlist-ios26-4.webp`,
`apple-now-playing-and-queue.png` panel 1, `spotify-now-playing-and-menu.webp` header style.

### 12.1 Apple Music (iOS 26.4 fullscreen design) **[APPLE-FIRST]**
- The **whole page takes an artwork-matched background color**; foreground text is only
  white or black, chosen against the tint (Mayo). The artwork itself does not need a
  separate card on playlist pages — artwork color bleeds from the top.
- **Floating glass header controls**: circular back button at left; **Share + ••• grouped
  in one glass pill** at right. They float over the tint (no solid nav bar).
- Header block, centered: artwork (albums show it at reduced size, ~45% width,
  *estimated*), **title bold centered**, artist/curator under it (on the older layout the
  artist name is in the red accent; on 26.4 tints it follows the white/black rule), then a
  tiny meta line (genre • year • Lossless badge).
- **Action row, centered: circular Shuffle button | wide Play pill (▶ Play, high-contrast
  fill — white on dark tints, black on light) | circular button** (download ↓ when not
  downloaded / ✓ state when added; on playlists a **+** add button).
- Playlist description under the actions, small, two lines with a "MORE" expansion.
- **Track rows**: albums use **track numbers** (no thumbs) with hairline separators and a
  ••• at right; playlists use **artwork thumbs**. The currently playing row replaces its
  number with a **tiny animated equalizer-bars glyph** (visible in the 26.4 album ref,
  track 1) — no title color change on Apple.
- Badges row under the header for audio formats: "Lossless", "Apple Digital Master",
  Dolby Atmos wordmarks in small gray type (panel 1 ref).

### 12.2 Spotify (contrast)
- Header: artwork centered at reduced size over an **artwork-extracted gradient** that
  fades to the page's dark background about halfway down. Title left-aligned below,
  then owner row (avatar + name), then meta line (likes • duration) in gray.
- Action row, left-to-right: **Download** (arrow-in-circle, §7.2 states), **Add user/
  invite** and ••• on some pages; at the **right: shuffle icon and a large green play
  disc**. The green disc is the page's primary action — the single most recognized
  Spotify element.
- Liked Songs page: purple-gradient header tile with a white heart as the artwork;
  "Liked Songs" title; count line ("230 songs"); same action row. A downloaded Liked
  Songs shows the green arrow filled.
- Apple **Favorites** equivalent: favorited songs collect under Library → Songs filtered
  by Favorite (star), and the Favorite action is the star on Now Playing — Apple has no
  separate gradient "Liked Songs" hero page. **[APPLE-FIRST]: use the star/Favorites
  model; do not clone the purple heart page.**

## Cross-cutting micro-details
Sources: all refs.

- **Playing-track marker in lists.** Apple: tiny animated **equalizer bars glyph replaces
  the track number**; title color unchanged. Spotify: **title turns green** and a small
  equalizer glyph appears with it (queue ref shows the glyph before the title). Paused
  state freezes the bars in both apps. **[APPLE-FIRST] = Apple's number-replacement.**
- **Explicit badge**: small filled square "E" before the artist line (Spotify refs) /
  after the title (Apple). Never a text word.
- **Add/save feedback**: Spotify's (+) morphs into a **green check** and stays; Apple's
  star fills. No dialogs.
- **Toasts/snackbars**: Apple uses **none** for these actions. Spotify uses exactly one
  pattern: white rounded snackbar, dark text, pinned above the mini player, auto-dismiss
  ("Your sleep timer is set."). arzxsh (Android, Apple language): prefer silent state
  change; reserve the Spotify snackbar for the sleep timer confirmation only.
- **Empty states**: neither app decorates them — a short gray sentence + the obvious
  action ("Songs you download will appear here" style). GAP: no empty-state screenshot
  was captured; keep copy plain and functional, no illustrations.
- **Row press feedback**: rows flash a brief light-gray highlight (Apple) / white-overlay
  (Spotify); buttons scale or highlight per §13. Nothing bounces.
- **Share**: both open the **system share sheet**; Spotify additionally offers its
  branded share card + Spotify Code (a scannable waveform strip under the artwork in the
  share view). Apple Music's song share includes "Share Lyrics…" (pick lines to share as
  a card) when synced lyrics exist (User Guide).

---

## 13. MOTION — transitions, feedback, and feel
Animations are requirements, not decoration (Arz). Each item states what the reference
apps do and the evidence class: **[stills]** = inferable from saved screenshots,
**[text]** = described by a cited source, **[GAP]** = no trustworthy source found — the
builder must choose values and the reviewer must judge the result against the described
character, not against invented numbers.

### 13.1 Mini player → Now Playing expansion
- Apple: tapping the mini player raises Now Playing as a **sheet that grows out of the
  mini player** — the artwork scales up from the mini player's thumbnail position to the
  full artwork while the sheet's background crossfades to the blurred artwork wash. The
  grabber handle is the dismiss affordance; a downward drag (or swipe) reverses it, and
  the drag is **interactive** (the sheet follows the finger, artwork shrinking as you
  pull). [text: User Guide/Cult of Mac describe open/dismiss; geometry from stills]
- Spotify: same sheet-up pattern; the mini player card's tint becomes the Now Playing
  gradient. Dismiss via chevron-down or swipe down. [stills + Beebom item 6]
- **Exact durations/easing: GAP.** No public source states them. Character to hit:
  Apple's system sheets use a spring (slight overshoot-free settle, ~0.35–0.45s feel);
  anything linear or longer than ~0.5s will read wrong. Flag as tuned-by-eye.

### 13.2 Screen-to-screen transitions
- Apple: pushes slide the new page in from the right with the previous page parallaxing
  slightly left and dimming; the floating glass chrome **does not move** — content slides
  under it. Tab switches are **crossfades with no slide**; the selected tab's inner pill
  highlight animates between tabs. [stills + HIG tab-bar guidance]
- Spotify: pushes slide similarly; tab switches crossfade; the mini player persists
  across all of it untouched. [stills]
- Rule for arzxsh: chrome (tab bar, mini player, search circle) is a fixed layer. Only the
  content layer animates. Any build where the bars slide with the page is wrong.

### 13.3 Artwork behavior on play/pause (Apple signature detail)
- On **pause**, Apple Music's Now Playing artwork **animates to a slightly smaller size**
  (a subtle scale-down, roughly 8–10% — *estimated from screenshot comparisons*); on
  **play** it springs back to full size. It is the app's quiet playing/paused signal —
  users notice its absence. The jailbreak tweak "DontShrinkMyAlbumArt" exists solely to
  disable this, which documents the behavior (iDownloadBlog). [text]
- Track change: new artwork crossfades/slides in the direction of travel (next = from the
  right). [GAP for exact treatment in iOS 26 stills; keep crossfade if unsure]
- Spotify: artwork does **not** scale on pause; state is carried by the play/pause glyph
  alone. **[APPLE-FIRST]: implement the shrink.**

### 13.4 Button press feedback
- Apple glass controls: on press, the glass **brightens and the glyph scales down
  slightly**, releasing with a spring settle (Liquid Glass is described by Apple as
  "dynamic and alive"; WWDC25 session 284). Tab items: the inner pill highlight fades/
  slides to the newly selected tab. [text]
- Transport buttons (both apps): instant glyph swap play↔pause with a small scale pop;
  no ripple. Spotify's material ripple exists on list rows/menus but the player controls
  themselves use opacity/scale feedback. [stills + text]
- Toggles (shuffle/repeat): color/dot/badge changes are **instant on tap** — no animated
  transition between states beyond the press feedback. The "1" badge pops in.
- Android note for arzxsh: kill the default Material ripple on player controls and glass
  chrome; it reads as "generic Android app" against these references. Rows may keep a
  subtle highlight fade.

### 13.5 List scroll feel
- Content scrolls **under** the floating chrome with the glass showing the passing
  content (Apple) — lists must extend edge-to-edge under the bars, with top/bottom
  **content insets** so first/last rows can rest clear of the chrome.
- Deceleration is the platform's standard fling; neither app uses rubber-band overscroll
  glow on these surfaces (Android's edge glow should be disabled or tinted neutral).
- Apple's tab bar/mini player minimize-on-scroll (§1.1) is part of scroll feel: it
  triggers on scroll **down**, restores on scroll to top or tab tap, and the mini player
  and tab bar animate **together in one motion** (Mayo documents the multi-part
  choreography: tab glyphs fade + scale, platter moves down + narrows, Next button
  disappears). A build where the two pieces animate separately will look broken. [text]
- Spotify lists: no minimize behavior; the mini player is fixed. Sticky section headers
  (e.g., queue sections, Popular) pin under the top bar.

### 13.6 Queue and menu presentation
- Apple Playing Next: the Now Playing layout **reflows in place** — artwork shrinks to
  the mini header, controls compress — rather than a new screen sliding over. Lyrics mode
  is the same in-place reflow (artwork → mini header, lyrics take the freed space;
  `apple-lyrics-view.jpg`). [stills]
- Apple long-press menu: item preview scales up from the pressed row with a **blur of
  the surrounding screen**, menu fading/scaling in beneath the preview. [text: iOS
  context-menu pattern as described in iGeeksBlog]
- Spotify menus/queue/sleep-timer: **bottom sheets** that slide up over a dimming scrim;
  the underlying Now Playing stays visible at the top edge (queue/sleep refs). Sheets
  dismiss by grabber drag, scrim tap, or back. [stills]
- Snackbar (Spotify): slides/fades in above the mini player, holds ~2–3s
  *(estimated)*, fades out. [stills + guides]

### 13.7 Tab switching
- No content slide. Instant swap or short crossfade; scroll position is **remembered per
  tab** in both apps; re-tapping the current tab scrolls it to top (Apple convention).
- The minimized chrome state (§13.5) is per-scroll-position and animates with the list,
  not with the tab switch.

### 13.8 Lyrics motion
- Apple: lines **auto-scroll** keeping the current line near the upper third; the sung
  portion fills word-by-word (karaoke sweep); lines above/below progressively **fade and
  blur** with distance from the current line (`apple-lyrics-view.jpg` shows the falloff).
  Tapping a line **seeks** to it (User Guide). Tapping the artwork/bottom bar toggles the
  controls back in temporarily (Cult of Mac).
- Spotify: line-based (not word-based) highlight — sung line turns **white**, all others
  stay dark on the colored card; auto-scroll keeps pace; fullscreen keeps a mini progress
  bar + pause control (`spotify-lyrics-card-and-fullscreen.webp`).

### 13.9 What stills cannot evidence (explicit GAPs)
Exact millisecond durations, spring constants, and the scrubber's drag-growth values are
not published by either company and cannot be read off screenshots. They are marked GAP
throughout. The acceptance test for motion is comparative feel against §13.1–13.8, not a
number.

---

## 14. Performance notes (target device: Realme 8, Helio G95)

The reference apps cheat differently, and the cheap cheats are the ones to copy:

- **Real blur is spent in exactly two places by Apple**: the floating glass chrome and
  the Now Playing background wash. Both are **small or static** targets. arzxsh already
  pre-blurs the artwork bitmap for the Now Playing backdrop — keep that (render once per
  track at reduced resolution, e.g. quarter-size, then scale up; the blur hides it).
- **Spotify's Now Playing background is a flat extracted-color gradient — zero blur
  cost** — and its mini player/lyrics card reuse the same extracted palette. Extracting
  2–3 colors per artwork (palette) is CPU-cheap and cacheable.
- **Do not** run live backdrop blur over scrolling lists (the glass close-up look). On the
  G95 that is the frame-rate killer. Acceptable arzxsh approach (already the app's):
  translucent fills matched to the reference tint + a 1px light inner top edge + soft
  shadow, i.e. fake the refraction, keep the geometry and states exact. Where a list
  scrolls under the chrome, a **pre-rendered blur strip** is also not viable (content
  changes) — the flat tint is the shipping answer.
- Equalizer-bars animations (playing markers) must be tiny and cheap: 3–4 bars, transform
  only, and **frozen when paused** and when off-screen.
- Lyrics auto-scroll and the karaoke sweep are transform/opacity work; never re-layout
  text per frame.
- Keep a strict frame budget: if a surface can't hold 60fps on the G95 with the cheap
  treatments above, cut the effect, not the frame rate. Smoothness is the feature
  (Arz's standing order).

---

## 15. Common ways clones get this wrong
Grounded in the references above; each item is a real divergence seen in the refs'
counterexamples or called out by the cited guides.

1. **Shuffle/Repeat on the Apple-style Now Playing screen.** Apple hides them on the
   Playing Next header; Spotify puts them in the transport row. Mixing the languages
   (Apple chrome + Spotify transport) is a decision, not a default — see §2.1.
2. **One flat bottom bar.** Apple's chrome is two floating glass pieces plus a detached
   search circle, and it **minimizes on scroll** with the mini player folding inline.
   A static full-width bar with 5 equal tabs is the fastest tell of a clone.
3. **Fake glass that's just 60% black.** The reference glass tints and refracts content;
   clones ship a uniform dark fill with no inner highlight and no selected-tab pill —
   and then put the accent color on every tab instead of only the selected one.
4. **Content ending above the bars.** Real lists scroll *under* the chrome with insets;
   clones stop the list at the bar, or worse, let rows sit hidden under it (arzxsh's own
   v3 bug).
5. **A queue that's one flat list.** Both apps section it (manual queue vs context
   continuation vs autoplay) with different rules per section, drag handles on every
   row, and the mode toggles at the top (Apple) or bottom tiles (Spotify).
6. **Download as a two-state icon.** The real pattern is a state machine: idle →
   per-item progress → completed mark (green on Spotify, check/filled on Apple),
   mirrored in list rows and a Library filter.
7. **Context menus with invented order.** Spotify's menu is library-first, queue early,
   navigation mid, radio/credits/code last, sleep timer only from Now Playing. Apple's
   is grouped with Play Next/Play Last and Pin. Random order reads wrong instantly.
8. **Lyrics as a static text page.** Apple = synced lines, fading/blurred neighbors,
   tap-to-seek; Spotify = inverted-color card, sung line white. A plain scrollable text
   view matches neither.
9. **Artwork that ignores playback state** (no pause-shrink on Apple language), a
   scrubber with a fat knob at rest, and **negative remaining time missing** on Apple
   language (Spotify uses total time — don't mix).
10. **Toast spam / Material ripples everywhere.** Apple confirms nothing with popups;
    Spotify has exactly one snackbar pattern. Confirmation = the state change itself
    ((+) → green check, star fills, glyph turns green with a dot).

---

## 16. DETAILS CHECKLIST
Verify against an APK screenshot, line by line. [A] = Apple-first requirement,
[S] = Spotify-derived requirement adopted for arzxsh.

### Global chrome
- [ ] Tab bar is a floating glass capsule, detached from screen edges, content scrolls under it [A]
- [ ] Search is a separate glass circle at the bottom-right, not a tab inside the capsule [A]
- [ ] Tabs in order: Home, New, Radio, Library; icon above label on each [A]
- [ ] Only the selected tab is red, and it sits in a lighter inner pill highlight [A]
- [ ] Mini player is its own glass capsule above the tab bar: artwork, title/artist (2 lines), play/pause, next [A]
- [ ] Mini player has NO progress bar (unless the §1.2 deviation was explicitly approved) [A]
- [ ] Scrolling down minimizes: tab bar → selected-icon circle; mini player folds inline and loses its Next button [A]
- [ ] Tapping a tab or scrolling to top restores the expanded chrome [A]
- [ ] Swipe left/right on the mini player skips tracks [A]
- [ ] No Material ripple on chrome or player controls [A]

### Now Playing (Apple language, §2 + deviation §2.1)
- [ ] Grabber handle centered at top [A]
- [ ] Artwork large, rounded corners, shadow; background is the blurred artwork wash [A]
- [ ] Artwork shrinks slightly when paused, grows back on play [A]
- [ ] Title semibold left, artist dimmed under it; star + circled ••• at the right of the title row [A]
- [ ] Scrubber thin with NO knob at rest; elapsed left, **negative** remaining right [A]
- [ ] Transport = Previous / Play-Pause / Next, large glyphs, wide spacing [A]
- [ ] Volume row with quiet/loud speaker icons at both ends [A]
- [ ] Bottom row: Lyrics (left), output/AirPlay (center, device name under when connected), Playing Next (right) [A]
- [ ] Lyrics icon dimmed when the track has no lyrics [A]
- [ ] If shuffle/repeat are on this screen (v4 deviation): they flank the transport row and use Spotify state treatment [S]
- [ ] (Spotify transport, where used) Play/Pause is a large white disc with black glyph, ~1.6× side buttons [S]
- [ ] (Spotify) Shuffle on = green + dot beneath; Smart Shuffle = green + sparkle [S]
- [ ] (Spotify) Repeat cycles off → all (green+dot) → one (green+dot+"1" badge) [S]
- [ ] (Spotify) Header shows small-caps source label + source name, centered; chevron-down left; ••• top-right [S]

### Queue / Playing Next
- [ ] Mode toggles (Shuffle, Repeat, AutoPlay ∞, AutoMix) sit at the TOP of the queue, active one pill-highlighted [A]
- [ ] Queue header shows "Playing Next" + the source it plays from; Clear action present [A]
- [ ] Manually queued tracks and "Continue Playing" (source continuation) are separate sections [A]
- [ ] AutoPlay section, when on, is headed by ∞ with its explanation line [A]
- [ ] Every row has a drag handle (≡) at the right edge; hold-to-reorder works [A][S]
- [ ] Swipe-left on a row removes it from the queue [A]
- [ ] Scrolling up reveals play History; tapping a history row plays it [A]
- [ ] Current track row is marked (Apple: equalizer glyph; Spotify model: green title + equalizer) [A][S]
- [ ] Spotify model extras if adopted: Edit mode with left circles + Remove / Move up bar; bottom Shuffle/Repeat/Timer tiles [S]

### Context menus
- [ ] Long-press opens menu WITH an artwork preview card; background blurred [A]
- [ ] Apple menu contains: Favorite, Add/Remove Library, Download/Remove Download, Add to a Playlist…, Play Next, Play Last, Create Station, Share, Suggest Less, Pin [A]
- [ ] Spotify menu order (if Spotify menu is used): Add to Liked Songs, Add to playlist, Hide, Add to Queue, Go to album, Go to artist, Share, Start a Jam, Sleep timer, Go to song radio, View credits, Show code [S]
- [ ] Menu header (Spotify model): artwork thumb + title + "Artist • Album" [S]
- [ ] Menu rows: left icon + label, no random reordering, destructive items last [A][S]

### Sleep timer
- [ ] Entry lives in the Now Playing ••• menu, near the bottom (music) [S]
- [ ] Sheet options exactly: 5, 10, 15, 30, 45 minutes, 1 hour, End of track [S]
- [ ] Active timer: sheet title shows remaining time; "Turn off timer" row appears [S]
- [ ] Setting it shows ONE white snackbar above the mini player: "Your sleep timer is set." [S]
- [ ] Playback fades out at expiry; timer does not persist to the next session [S]

### Downloads
- [ ] Album/playlist action row: circular Shuffle | wide Play pill | circular Download button [A]
- [ ] Three states exist and are visually distinct: idle arrow → progress ring → completed [A][S]
- [ ] Per-track progress is visible in the track list while downloading [S]
- [ ] Downloaded tracks carry a small marker in rows; a Downloaded view/filter exists in Library [A][S]
- [ ] Completed state color: filled/check (Apple model) or solid green arrow (Spotify model) — one model, used consistently [A][S]
- [ ] Remove Download lives in the •••/long-press menu; no confirmation dialog, no toast [A]

### Lyrics
- [ ] Lyrics replace the artwork area; mini header (thumb + title/artist + •••) at top [A]
- [ ] Current line large and fully white; lines fade (and blur, Apple) with distance [A]
- [ ] Sung portion highlights progressively (word sweep on Apple model; whole line on Spotify model) [A][S]
- [ ] Tapping a lyric line seeks playback to it [A]
- [ ] Translation line (when present) is smaller, directly under the current line [A]
- [ ] Lyrics button state on Now Playing reflects on/off; unavailable = dimmed [A]
- [ ] Spotify card model (if used): lyrics on solid extracted-color card, sung line white, others dark; share + expand icons at card top-right [S]

### Home / Search / Library / Pages
- [ ] Home sections: bold left headers + horizontally scrolling card rows that peek the next card [A]
- [ ] Card captions: title semibold + gray subtitle BELOW the artwork (mix cards: white caption on the art) [A]
- [ ] Last Home section can scroll fully clear of mini player + tab bar (inset check) [A]
- [ ] Search page: category tiles in 2 columns; results grouped (Top Results, Songs, Albums, Artists) [A]
- [ ] Library: Pins (max 6) above category rows; category rows have red icon + chevron; Recently Added grid below [A]
- [ ] (Spotify library model, if used) Filter chips row + sort row (Recents) + grid/list toggle; pin glyph on pinned rows [S]
- [ ] Album page (26.4): full-page artwork-matched tint; text only white or black against it [A]
- [ ] Album/playlist header controls float in glass (back circle; Share + ••• pill) [A]
- [ ] Album rows use track numbers; playlist rows use artwork thumbs; both have ••• at right [A]
- [ ] Playing row in an album replaces its number with the equalizer glyph; no title color change [A]
- [ ] Artist page: full-bleed photo header, huge name bottom-left, Latest Release + Top Songs + shelves [A]
- [ ] Explicit badge is a small "E" square, never a text label [A][S]
- [ ] Favorite feedback is the star filling / (+) morphing to a check — never a dialog [A][S]

### Motion (verify by screen recording, not stills)
- [ ] Now Playing opens as a sheet growing from the mini player; drag-down dismisses interactively [A]
- [ ] Chrome never slides with page content; only the content layer animates [A][S]
- [ ] Tab switches crossfade; per-tab scroll position is remembered [A][S]
- [ ] Pause/play artwork scale animation runs every time, both directions [A]
- [ ] Sheets (queue/menus/timer) slide over a dimming scrim; underlying screen visible at top [S]
- [ ] Toggle state changes (shuffle/repeat/download) are instant on tap [A][S]
- [ ] No linear or >0.5s-feeling transitions anywhere in the player surfaces [A]

---

## Sources
Saved screenshots: see the reference index table (all files in `refs/`, captured
2026-11-06 from the URLs below).

- Benjamin Mayo — iOS 26 Apple Music tab bar / receding UI: https://bzamayo.com
  (image: `apple-tab-bar-miniplayer-two-states.png`); "iOS 26.4 Introduces Redesigned
  Albums Page In Music": https://bzamayo.com/ios-26-new-album-page-design-music
- 9to5Mac — "Apple Music in iOS 26.4 has new design for albums, playlists, and more":
  https://9to5mac.com/2026/02/16/apple-music-in-ios-26-4-has-new-design-for-albums-playlists-and-more/
- Cult of Mac — "Apple Music buttons: What do they all do?":
  https://www.cultofmac.com/how-to/apple-music-signs-symbols-icons
- i5apps — "Apple Music Symbols Explained": https://i5apps.com/apple-music-symbols-explained-a-guide-to-understanding-icons/
- Apple iPhone User Guide, Music chapter (queue, lyrics, share lyrics) — via FCC mirror:
  https://fccid.io/BCG-E4005A/User-Manual/AA20xx-User-Guide-v1-0-draft2-5422066.pdf
- iGeeksBlog — "Apple Music on iPhone: 30+ tips and tricks" (long-press items, Pins):
  https://www.igeeksblog.com/apple-music-tips-tricks-for-iphone/
- Apple HIG — Tab bars (Liquid Glass, minimize behavior, MiniPlayer accessory), mirrored:
  https://github.com/prisma-labs-dev/apple-skills/blob/HEAD/skills/hig/tab-bars.md
- WWDC25 session 284, "Build a UIKit app with the new design" (tab bar minimize +
  accessory animation), transcript mirror:
  https://github.com/tsdsj/apple-style/blob/HEAD/skills/Apple-Style-HIG/reference/wwdc25/284-build-a-uikit-app-with-the-new-design.md
- iDownloadBlog — "DontShrinkMyAlbumArt" (documents pause-shrink behavior):
  https://www.idownloadblog.com/wp-json/wp/v2/posts/866972
- Spotify Community (official KB) — "How does the Play Queue work?":
  https://community.spotify.com/forum/en/kb/articles/807-unknown
- Beebom — "Spotify Icons and Symbols Explained":
  https://beebom.com/spotify-icons-and-symbols-explained/amp/
- Threads (June 2026 screenshot + itemized description of Spotify's track menu):
  https://www.threads.com/@chiefnonsenseofficer/post/DZ6J42KES7V
- Spotify sleep timer steps/confirmation: Spotify Newsroom (2022-08-02) as cited by
  https://robots.net/tech/how-to-set-a-timer-on-spotify/ ; options and remaining-time
  behavior cross-checked at https://www.luxrise.io/spotify-sleep-timer and
  https://www.solveyourtech.com/how-to-set-a-spotify-sleep-timer-iphone-13/
- Aatma — Apple Music AutoPlay/queue walkthrough (screenshot source):
  https://aatma.es/como-deshabilitar-la-reproduccion-automatica-en-Apple-Music-iPhone-iPad
- AppleInsider — "Apple Music iOS 26 vs iOS 18" (lyrics translation UI):
  https://appleinsider.com/inside/apple-music/vs/apple-music-ios-26-vs-ios-18-automix-liquid-glass-and-lyrics-in-your-language
