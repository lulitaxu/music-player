# arzxsh

A personal music player for Android, built by Arafat Arzu.

One library for everything you listen to: a huge streaming catalog, a deep fallback catalog, and the music files already on your device. Everything resolves and plays on your own phone, over your own connection. No server in the middle, no account, no sign-in.

## Features

- Search, charts, artists, albums and radio
- Automatic fallback to another version when a track cannot play
- Playlists, Liked Songs, listening history and your listening stats
- Offline downloads with progress, pause and retry
- Synced lyrics that highlight as the song plays, tap a line to jump
- Sleep timer with fade-out, and crossfade up to twelve seconds
- Import and play local audio files

## How it works

Catalog browsing and stream resolution run entirely on-device, using the same internal client interfaces the official apps use, with a secondary on-device resolver. Playback runs on Media3 ExoPlayer, and stream requests go out with the same client identity that resolved them. Synced lyrics are fetched from open lyrics databases with a fallback chain. Nothing about your listening goes anywhere except the requests a normal client would make.

## Status

Personal project under active development. Android is the lead platform (built and tested on a Realme 8); an iOS sibling build lives in this repo too.

## Notes

Not affiliated with any streaming service or platform. Built with open-source libraries including NewPipeExtractor (GPL-3.0); full attributions are in the app's licenses screen.
