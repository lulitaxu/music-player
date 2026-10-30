# Design inspiration: what we studied, what we take, what we do better

Researched 2026-10-31 at Arz's direction ("use inspo from the internet
and make it better"). Every pattern below names its source, our version,
and the improvement. Anything we could not build honestly on an
iPhone XS is in the Rejected section with its reason.

## Sources

- Apple Music on iOS 26: Liquid Glass, AutoMix, animated Lock Screen
  artwork, lyric translation/pronunciation (cecritic.com roundup;
  en.zicos.com feature list)
- Liquid Glass legibility backlash and Apple's "Tinted" fix
  (indianexpress.com)
- Spotify Design, "Bringing the Spotify Heart to Life": Encore motion
  principles and the like-button micro-interaction (medium.com)
- Spotify experience principles: speed, relevance, rhythmic motion
  (latterly.org); "faster than piracy" as the founding design brief
  (owler.com design history)
- Indie players Marvis Pro, Soor, Next: customizable home, inline
  media views, Magic Mix / Magic DJs (notecable.com ranking;
  smallcapnews.co.uk roundup)

## Patterns adopted, and our better version

### 1. Artwork-tinted chrome (from Apple Music)
Their move: the Now Playing bar and Lock Screen take a hint of the
album artwork behind translucent glass.
**Ours:** the mini-player and Now Playing sample the artwork's
dominant color and tint the surface with it, cross-fading on track
change with the `gentle` motion token.
**Better:** Apple shipped glass so clear that users complained text
became unreadable, and Apple later had to add a system-wide "Tinted"
option. We ship both from Phase 1: Clear and Tinted glass as a
Settings choice, plus a hard rule that text never sits on artwork or
glass without a minimum-opacity surface or scrim under it. Legibility
is a design token, not a patch.

### 2. Inline expansion (from Marvis Pro)
Their move: tapping an item inserts its detail view below it instead
of pushing a new screen, so browsing never loses its place.
**Ours:** tapping a track row expands inline actions (play next, add
to queue, favorite, lyrics peek when available). Albums and artists
still push full pages.
**Better:** one-handed reach on the XS is the design constraint
Marvis gestures at with its side menu; inline expansion plus a
bottom-docked mini-player gets there without a hidden gesture menu
nobody discovers.

### 3. A home the user arranges (from Marvis and Soor)
Their move: the home screen is modular; users pick and order sections.
**Ours:** Phase 6 home sections (Recently played, Downloads, Made for
you, Trending on Audius) are reorderable and removable in Settings.
**Better:** our default order is chronological and honest, not an
engagement-ranked feed, and every section is labeled with where its
contents came from. No infinite scroll home.

### 4. Mixes that explain themselves (from Soor's Magic Mix, Next's Magic DJs)
Their move: filter- and habit-based smart mixes resurfaced from the
user's own library.
**Ours:** the Phase 6 discovery engine generates named mixes from the
on-device taste profile, and every mix and radio queue carries a
"why" line ("Because you played X", "More from artists near Y").
**Better:** Spotify and Apple never explain a recommendation. An
explanation is checkable, it teaches the user what the profile
believes, and it fits this app's spine: claims come with evidence.
It also makes wrong recommendations fixable by the user (remove the
seed), not just skippable.

### 5. Motion with a job (from Spotify's Encore principles)
Their rules: move with purpose, provide feedback, add delight;
motion inspired by rhythm; micro-interactions capped around 500 ms
at 60 fps. Their like-button burst measurably increased likes, which
improved their recommendation data.
**Ours:** those caps are written into our motion system: `snappy` and
`fluid` springs only, nothing decorative over 500 ms except ambient
artwork color shifts. Favoriting gets one small burst in our accent
color, because the same data logic applies to our on-device profile:
more honest signals in, better mixes out.
**Better:** Spotify's Canvas loops video behind songs; we reject
that below. Our delight budget is spent on feedback, not on content
competing with the music.

### 6. Time-to-music (from Spotify's founding brief)
Their brief: be faster than piracy, or nothing else matters.
**Ours, as numbers:** cached home interactive under 200 ms (already
in SPEC performance targets); first audible frame targeted under
1.5 s after a tap on a warm connection; Phase 3 prefetches the next
track's stream URL while the current one plays, so next is instant
and crossfade has both ends ready.
**Better:** we publish these as acceptance tests in the phases, not
as vibes.

### 7. Sing-along depth (from Apple Music Sing)
Translation and pronunciation lines beneath karaoke lyrics.
**Ours:** Phase 4 stretch goal, only where LRCLIB or a later licensed
provider actually supplies the translation. If it is not available,
the UI shows nothing rather than a machine-guessed line dressed up as
a lyric. Same honesty rule as lyrics matching.

## Rejected inspiration

- **Dribbble-style stacked-glass mockups.** Beautiful in a still,
  unusable in the hand: layered blurs murder the XS frame rate and
  repeat the exact legibility failure Apple had to patch. Our glass
  is one material layer, tinted, with scrim rules.
- **Looping video backgrounds (Spotify Canvas).** Data, battery, and
  licensing cost for decoration that fights the artwork. Out.
- **The algorithmic infinite feed as home.** An engagement pattern,
  not a music pattern. Home is finite, arranged, and labeled.
- **Text over raw artwork.** Everywhere it appears in the references,
  it is the first thing users complain about. Never in this app.

## Where this lands in the build

- Phase 1: tinted/clear glass setting, artwork color sampling utility,
  scrim and opacity tokens, motion caps enforced in the token file.
- Phase 3: inline track expansion, Now Playing artwork tinting,
  stream prefetch, time-to-music acceptance tests.
- Phase 4: karaoke emphasis per tokens; translation lines only when
  a provider truly has them.
- Phase 6: arrangeable home, mixes with "why" lines, favorite burst.
