# arzxsh for Linux

The desktop companion to the Android app: the same YouTube Music
catalog, the same design language, running as a local web app.
Decided 2026-11-07 as a local web app first (the laptop has 4 GB of
RAM; there is no native toolchain to feed), with nothing wasted if
it is later wrapped in Tauri.

## Run

```sh
./run.sh
```

That is the whole setup. The first run creates a Python venv for
the backend and installs its dependencies; every run after that
just starts. The player opens at http://127.0.0.1:8077 in your
default browser. Set `ARZXSH_PORT` to use a different port.

## How it works

- `../ytmusic-backend/app.py` (FastAPI + ytmusicapi) serves the
  catalog API **and** this frontend from one process, one origin.
- Playback resolves server-side with yt-dlp and the audio bytes are
  proxied through `/audio/{id}`, so the media is same-origin: the
  Web Audio visualizer gets real data and seeking works.
- Two decks alternate for a 3-second crossfade; the next track
  preloads as the current one nears its end. When a queue runs
  out, radio built on the last track continues it.
- Liked songs, recently played, and search history live in the
  browser's localStorage on this machine only. There are no
  accounts and nothing is synced anywhere.
- The server binds to 127.0.0.1 on purpose: it has no auth, so it
  must not be reachable from the local network.

## Offline and local music

- **Local files:** the backend scans your music folder
  (`ARZXSH_MUSIC_DIR`, default `~/Music`) recursively and lists
  every audio file under Library → On this device, with metadata
  and cover art read from the files' own tags (mutagen). Files are
  streamed from disk with range support; nothing is uploaded.
- **Downloads:** the ⬇ button on any streaming track (or in Now
  Playing) saves its audio into `<music folder>/arzxsh/` through
  yt-dlp (fragment retries and resume included) and writes the
  title/artist into the file's tags. From then on it is a local
  file and plays with no network at all. A ✓ marks saved tracks;
  deleting the file from disk clears the mark on next launch.

## Features (parity with the phone app)

Home with time-aware greeting, trending songs and top artists ·
search across songs, artists and albums with history suggestions ·
artist pages with an About block · album pages · Now Playing with
artwork backdrop, visualizer, lyrics (plain text), the queue, and
the About-the-artist card · liked songs · media-key support via
the Media Session API · keyboard: Space play/pause, ←/→ seek,
Esc closes Now Playing.

## Layout

- `app/` — the frontend (vanilla HTML/CSS/JS, no build step).
- `run.sh` — launcher.
- Backend additions for this app live in `../ytmusic-backend`:
  `/audio/{id}` (byte proxy), `/lyrics/{id}`, and the static mount.
