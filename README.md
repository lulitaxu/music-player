# arzxsh

A personal music player for Android, built by Arafat Arzu.

arzxsh started life as an Audius-powered player. It has since pivoted: **YouTube Music is now the primary source**, and everything happens on your own device. The app talks to YouTube the way a real client does, from your phone and your connection. No server in the middle, no account, no sign-in. **Audius** stays on as a fallback source, and the music files already on your device are a third.

## Features

- Search, charts, artists, albums and radio from YouTube Music
- Automatic fallback to the Audius version when a YouTube track cannot play
- Playlists, Liked Songs, listening history and your listening stats
- Offline downloads with progress, pause and retry
- Synced lyrics (LRCLIB) that highlight as the song plays, tap a line to jump
- Sleep timer with fade-out, crossfade, and source modes (YouTube Music / Audius / Both)
- Import and play local audio files

## How it works

Catalog browsing and stream resolution run entirely on-device, using the same internal client interfaces the official apps use, with NewPipeExtractor as a secondary resolver. Playback runs on Media3 ExoPlayer, and stream requests go out with the same client identity that resolved them. Lyrics come from LRCLIB with a lyrics.ovh fallback. Nothing about your listening goes anywhere except the requests a normal client would make.

## Status

Personal project under active development. Android is the lead platform (built and tested on a Realme 8); an iOS sibling build lives in this repo too.

## Notes

Not affiliated with YouTube, Google, Apple or Audius. Built with open-source libraries including NewPipeExtractor (GPL-3.0); full attributions are in the app's licenses screen.
