# arzxsh-ytm backend

FastAPI + ytmusicapi catalog service for arzxsh, with server-side stream
resolution via yt-dlp. Spec: `../design/ytmusic-backend/SPEC.md`.

## Run locally

```sh
python3 -m venv .venv
.venv/bin/pip install fastapi "uvicorn[standard]" ytmusicapi yt-dlp
.venv/bin/uvicorn app:app --host 0.0.0.0 --port 8077
```

Endpoints: `/health` `/search` `/artist/{id}` `/album/{id}` `/charts`
`/radio?video_id=` `/stream/{videoId}` `/audio/{videoId}` (same-origin
byte proxy for the Linux web app) `/lyrics/{videoId}` `/local`
`/local/file/{id}` `/local/art/{id}` `/download/{videoId}` (offline
library: scans ARZXSH_MUSIC_DIR or ~/Music; downloads land in its
arzxsh subfolder). The server also
serves the Linux web frontend from `../linux/app` when present; see
`../linux/README.md`.

## Environment quirks (dev sandbox only)

This VM sits behind a TLS-intercepting egress proxy that fingerprint-blocks
ytmusicapi's stock Firefox 88 user agent, and yt-dlp's bundled CA store
rejects the proxy's chain. Two env flags exist for such environments; leave
both unset in production:

- `YTM_USER_AGENT` — override the user agent ytmusicapi sends.
- `YTM_DL_NO_VERIFY=1` — yt-dlp skips certificate verification.

## Verified 2026-11-06 (from the dev sandbox)

- `/search?q=whatsaheart` → 20 songs.
- `/artist/{whatsaheart}` → name, 114K subscribers, top songs, 10 singles,
  10 related artists.
- `/charts?country=GLOBAL` → 99 trending songs, 40 chart artists.
- `/radio` → 50-track queue.
- `/stream/{id}` → **blocked from this sandbox**: YouTube bot-walls this
  shared datacenter egress IP ("Sign in to confirm you're not a bot") or
  strips all playable formats for unauthenticated clients. The resolver
  code is in place; unblocking needs a cleaner egress IP on the deployment
  host, or a cookies / PO-token setup (account-risk trade-offs — founder
  decision, do not improvise with a personal Google account).
