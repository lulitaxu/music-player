# arzxsh for the terminal

A full music player in your terminal, on top of the same backend
as the Linux web player. Crossfade, synced lyrics, a real EQ,
offline downloads, your local library, playlists — mouse and
keyboard both work everywhere.

## Run it (from the repo)

```bash
sudo pacman -S mpv            # the playback engine (once)
cd cli && ./arzxsh
```

First run creates two Python environments (the CLI's and the
backend's) and starts the backend on 127.0.0.1:8077 if it is not
already running. After that, `./arzxsh` is the whole setup.

## Install it system-wide (Arch)

```bash
cd ../packaging && makepkg -si
arzxsh                        # from anywhere, pacman-managed
```

The package installs the sources under /usr/share/arzxsh and a
launcher at /usr/bin/arzxsh; libraries live in per-user venvs
under ~/.local/share/arzxsh. Optional: `cava` for live
visualizer bars, `mpv-mpris` for media keys / playerctl.

## Commands

| Command | What it does |
| --- | --- |
| `arzxsh` | the TUI player |
| `arzxsh play <query>` | play the top search result right now |
| `arzxsh search <query>` | print search results |
| `arzxsh download <query>` | save the top result for offline |

## In the TUI

Tabs: Home, Search, Library (Liked Songs + On this device),
Playlists, Settings. The Lyrics view (`y`) shows the cover as
truecolor block art, an About-the-artist block, and synced
lyrics with the active line lit (LRCLIB, any language; a
title+artist search fallback catches tracks the exact lookup
misses, Bangla catalog included). Settings holds the crossfade
length, playback speed, sleep timer, loudness normalization
and a 10-band EQ.

| Key | Action | Key | Action |
| --- | --- | --- | --- |
| ↑/↓ or j/k | move | space | play/pause |
| Enter | play row | n / p | next / previous |
| ←/→ | seek 10s | + / − | volume |
| l | like | d | download for offline |
| a | add to playlist | s / r | shuffle / repeat |
| y | lyrics view | q | queue panel |
| / | search | 1–5 | tabs |

## Playlists

Create one from the current queue, press `a` on any track to
add it to your most recent playlist, import a YouTube Music
playlist by link/id, or import an .m3u / .txt file (one
"Artist - Title" per line, resolved by search; local files in
an .m3u are matched against your library). Export writes an
.m3u + .txt pair to `~/Music/arzxsh/playlists/`.

## Offline

Library → On this device plays everything in your music folder
(`~/Music` by default, `ARZXSH_MUSIC_DIR` overrides), including
the `arzxsh` subfolder where downloads land. The backend is
local and auto-started, so offline listening needs no internet.

## State

Likes, history, recents, playlists and settings live in one
JSON file: `~/.local/state/arzxsh/state.json` (XDG). Nothing
leaves the machine except the catalog/lyrics lookups themselves.

Playback notes: crossfade runs on two mpv instances with IPC
volume ramps; the visualizer uses cava's raw output against
your system output (mpv cannot share its samples), and is
simply absent when cava is not installed — never faked.
