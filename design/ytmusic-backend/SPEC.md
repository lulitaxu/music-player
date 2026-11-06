# arzxsh YouTube Music backend — spec (approved by Arz 2026-11-06, build AFTER the Android UI rebuild lands)

## Why
Audius catalog is too small (Arz: searched a small artist, barely any songs). ytmusicapi (sigma67, MIT, actively maintained, v1.12.3) gives the full YouTube Music catalog as a second source next to Audius. Apps stay Audius-capable; YTM becomes the deep catalog.

## Shape
Thin Python service (FastAPI + ytmusicapi), stateless, no user accounts, no stored credentials. Unauthenticated public-catalog access only.

## Endpoints (all JSON, shapes mirror the app's existing Track/Artist models)
- GET /search?q=&filter=songs|artists|albums&limit= — merged catalog search
- GET /artist/{channelId} — artist header + full songs, albums, singles, related artists
- GET /album/{browseId} — album + tracklist
- GET /charts?country=GLOBAL|BD — trending songs/artists
- GET /radio?videoId= — watch playlist (next-up queue)
- GET /stream/{videoId} — resolves a playable audio URL server-side (yt-dlp), short-TTL cache, returns expiry

## Rules
- Stream URLs are resolved on the server and cached briefly; never expose raw extractor internals to clients.
- Cache search/artist responses (minutes) to stay gentle on upstream.
- One-command local run for testing; the fragile part (stream resolution) lives here only, so a Google-side break gets fixed in one place.
- Unofficial / YouTube ToS gray zone: personal use only. If arzxsh ever goes public, this backend gets dropped or replaced with a licensed source. (Arz accepted this trade-off 2026-11-06.)

## Deployment (open choice at build time)
Small always-on host: Render / Railway / Fly.io free or near-free tier, or a cheap VPS. NOT Vercel functions (cold starts + Python runtime fit poorly).

## App side (after backend is up)
- New provider in the apps next to Audius: YTM search results, artist page, charts on Home.
- Playback resolves through /stream; Audius playback path unchanged.
