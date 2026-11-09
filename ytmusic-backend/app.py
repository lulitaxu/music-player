"""arzxsh YouTube Music backend.

Thin FastAPI wrapper over ytmusicapi (catalog/search/artists/charts) with
server-side stream resolution via yt-dlp. Stateless, no accounts, no stored
credentials: unauthenticated public-catalog access only.

Spec: ../design/ytmusic-backend/SPEC.md
Run:  .venv/bin/uvicorn app:app --host 0.0.0.0 --port 8077
"""

import os
import time
from pathlib import Path
from urllib.parse import quote

import requests
import yt_dlp
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import StreamingResponse
from ytmusicapi import YTMusic

# Optional UA override. ytmusicapi's stock user agent is an old Firefox 88
# string; some egress proxies (including the dev sandbox this was built in)
# fingerprint-block it and kill the connection. Set YTM_USER_AGENT to a
# current browser UA in such environments. Unset = stock behavior.
_UA = os.environ.get("YTM_USER_AGENT")
if _UA:
    import ytmusicapi.constants as _ytc
    import ytmusicapi.helpers as _yth

    _ytc.USER_AGENT = _UA
    _yth.USER_AGENT = _UA

app = FastAPI(title="arzxsh-ytm", version="0.1.0")
ytm = YTMusic()

# --- tiny in-memory TTL cache -------------------------------------------------
_cache: dict[str, tuple[float, object]] = {}


def cached(key: str, ttl: float, fn):
    now = time.time()
    hit = _cache.get(key)
    if hit and hit[0] > now:
        return hit[1]
    val = fn()
    _cache[key] = (now + ttl, val)
    return val


# --- shape helpers (mirror the app's Track / Artist models) -------------------
def track(t: dict) -> dict | None:
    vid = t.get("videoId")
    if not vid:
        return None
    artists = t.get("artists") or []
    thumbs = t.get("thumbnails") or []
    art = thumbs[-1]["url"] if thumbs else None
    return {
        "id": vid,
        "source": "ytmusic",
        "title": t.get("title") or "",
        "artist": ", ".join(a.get("name", "") for a in artists if a.get("name")),
        "artist_id": artists[0].get("id") if artists else None,
        "album": (t.get("album") or {}).get("name") if isinstance(t.get("album"), dict) else t.get("album"),
        "duration_s": t.get("duration_seconds"),
        "artwork": art,
        "is_explicit": bool(t.get("isExplicit")),
    }


def tracks(items) -> list[dict]:
    out = []
    for t in items or []:
        m = track(t)
        if m:
            out.append(m)
    return out


# --- endpoints ------------------------------------------------------------------
@app.get("/health")
def health():
    return {"ok": True}


@app.get("/search")
def search(q: str = Query(min_length=1), filter: str = "songs", limit: int = 25):
    if filter not in ("songs", "artists", "albums", "videos"):
        raise HTTPException(400, "filter must be songs|artists|albums|videos")
    limit = max(1, min(limit, 50))

    def run():
        res = ytm.search(q, filter=filter, limit=limit)
        if filter in ("songs", "videos"):
            return {"type": filter, "results": tracks(res)}
        if filter == "artists":
            return {
                "type": filter,
                "results": [
                    {
                        "id": r.get("browseId"),
                        "name": r.get("artist"),
                        "artwork": (r.get("thumbnails") or [{}])[-1].get("url"),
                    }
                    for r in res
                    if r.get("browseId")
                ],
            }
        return {
            "type": filter,
            "results": [
                {
                    "id": r.get("browseId"),
                    "title": r.get("title"),
                    "artist": ", ".join(a.get("name", "") for a in (r.get("artists") or [])),
                    "year": r.get("year"),
                    "artwork": (r.get("thumbnails") or [{}])[-1].get("url"),
                }
                for r in res
                if r.get("browseId")
            ],
        }

    return cached(f"search:{filter}:{limit}:{q.lower()}", 300, run)


@app.get("/artist/{channel_id}")
def artist(channel_id: str):
    def run():
        a = ytm.get_artist(channel_id)
        songs = a.get("songs") or {}
        albums = a.get("albums") or {}
        singles = a.get("singles") or {}
        related = a.get("related") or {}
        return {
            "id": channel_id,
            "name": a.get("name"),
            "description": a.get("description"),
            "artwork": (a.get("thumbnails") or [{}])[-1].get("url"),
            "subscribers": a.get("subscribers"),
            "songs": tracks(songs.get("results")),
            "albums": [
                {
                    "id": r.get("browseId"),
                    "title": r.get("title"),
                    "year": r.get("year"),
                    "artwork": (r.get("thumbnails") or [{}])[-1].get("url"),
                }
                for r in (albums.get("results") or [])
                if r.get("browseId")
            ],
            "singles": [
                {
                    "id": r.get("browseId"),
                    "title": r.get("title"),
                    "year": r.get("year"),
                    "artwork": (r.get("thumbnails") or [{}])[-1].get("url"),
                }
                for r in (singles.get("results") or [])
                if r.get("browseId")
            ],
            "related": [
                {
                    "id": r.get("browseId"),
                    "name": r.get("title"),
                    "artwork": (r.get("thumbnails") or [{}])[-1].get("url"),
                }
                for r in (related.get("results") or [])
                if r.get("browseId")
            ],
        }

    try:
        return cached(f"artist:{channel_id}", 600, run)
    except Exception as e:  # upstream shape/lookup failure
        raise HTTPException(502, f"artist lookup failed: {e.__class__.__name__}")


@app.get("/album/{browse_id}")
def album(browse_id: str):
    def run():
        a = ytm.get_album(browse_id)
        return {
            "id": browse_id,
            "title": a.get("title"),
            "artist": ", ".join(x.get("name", "") for x in (a.get("artists") or [])),
            "year": a.get("year"),
            "artwork": (a.get("thumbnails") or [{}])[-1].get("url"),
            "tracks": tracks(a.get("tracks")),
        }

    try:
        return cached(f"album:{browse_id}", 600, run)
    except Exception as e:
        raise HTTPException(502, f"album lookup failed: {e.__class__.__name__}")


@app.get("/charts")
def charts(country: str = "GLOBAL"):
    def run():
        c = ytm.get_charts(country=country)
        # ytmusicapi 1.12 shape: {"videos": [...tracks...], "artists": [...]}
        vids = c.get("videos")
        if isinstance(vids, dict):
            vids = vids.get("items") or []
        # Chart "videos" entries are playlist cards (e.g. "Daily Top Music
        # Videos - Global"); the actual trending tracks live inside the
        # first playlist. Follow it.
        songs: list[dict] = []
        for v in vids or []:
            pid = v.get("playlistId")
            if pid:
                try:
                    pl = ytm.get_playlist(pid, limit=50)
                    songs = tracks(pl.get("tracks"))
                except Exception:
                    songs = []
                break
        arts = c.get("artists")
        if isinstance(arts, dict):
            arts = arts.get("items") or []
        return {
            "country": country,
            "songs": songs,
            "artists": [
                {
                    "id": r.get("browseId") or r.get("channelId"),
                    "name": r.get("title") or r.get("name"),
                    "artwork": (r.get("thumbnails") or [{}])[-1].get("url"),
                }
                for r in (arts or [])
                if (r.get("browseId") or r.get("channelId"))
            ],
        }

    try:
        return cached(f"charts:{country}", 900, run)
    except Exception as e:
        raise HTTPException(502, f"charts failed: {e.__class__.__name__}")


@app.get("/radio")
def radio(video_id: str):
    def run():
        w = ytm.get_watch_playlist(videoId=video_id, limit=25)
        return {"results": tracks(w.get("tracks"))}

    try:
        return cached(f"radio:{video_id}", 300, run)
    except Exception as e:
        raise HTTPException(502, f"radio failed: {e.__class__.__name__}")


def _resolve_stream(video_id: str) -> dict:
    """yt-dlp resolution shared by /stream (URL hand-off) and /audio
    (byte proxy). Signed googlevideo URLs expire, hence the short
    cache TTL at the call sites."""

    opts = {
        "quiet": True,
        "no_warnings": True,
        "format": "bestaudio[ext=m4a]/bestaudio/best",
        "skip_download": True,
    }
    # Only for TLS-intercepted dev sandboxes (MITM proxy with a private
    # CA that yt-dlp's bundled store rejects). Never set in production.
    if os.environ.get("YTM_DL_NO_VERIFY") == "1":
        opts["nocheckcertificate"] = True
    with yt_dlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(
            f"https://www.youtube.com/watch?v={quote(video_id)}",
            download=False,
        )
    return {
        "id": video_id,
        "url": info.get("url"),
        "format": info.get("format"),
        "ext": info.get("ext"),
        "abr": info.get("abr"),
        "expires_in_s": 300,
    }


@app.get("/stream/{video_id}")
def stream(video_id: str):
    """Resolve a playable audio URL server-side. Cached briefly; signed
    googlevideo URLs expire, so the TTL stays short."""

    try:
        return cached(f"stream:{video_id}", 300, lambda: _resolve_stream(video_id))
    except Exception as e:
        raise HTTPException(502, f"stream resolution failed: {e.__class__.__name__}")


@app.get("/audio/{video_id}")
def audio(video_id: str, request: Request):
    """Stream a track's audio bytes through this server (Linux web app).

    The web player needs same-origin media: a raw googlevideo URL is
    cross-origin without CORS headers, which would taint the Web Audio
    analyser and break the visualizer. Proxying keeps media same-origin
    and lets the browser treat the player like any local file. The
    client's Range header is forwarded so seeking works; the upstream
    status and range headers pass back untouched. Only the audio for
    the requested video id is ever fetched — this is not a general
    relay, and the server binds to localhost in the shipped run
    script."""
    try:
        resolved = cached(f"stream:{video_id}", 300, lambda: _resolve_stream(video_id))
    except Exception as e:
        raise HTTPException(502, f"stream resolution failed: {e.__class__.__name__}")
    url = resolved.get("url")
    if not url:
        raise HTTPException(502, "stream resolution returned no URL")
    headers = {}
    range_header = request.headers.get("range")
    if range_header:
        headers["Range"] = range_header
    try:
        upstream = requests.get(url, headers=headers, stream=True, timeout=30)
    except Exception as e:
        raise HTTPException(502, f"audio fetch failed: {e.__class__.__name__}")
    if upstream.status_code not in (200, 206):
        upstream.close()
        raise HTTPException(502, f"audio fetch returned {upstream.status_code}")
    passthrough = {}
    for name in ("content-type", "content-length", "content-range", "accept-ranges"):
        value = upstream.headers.get(name)
        if value:
            passthrough[name] = value

    def body():
        try:
            for chunk in upstream.iter_content(chunk_size=64 * 1024):
                if chunk:
                    yield chunk
        finally:
            upstream.close()

    return StreamingResponse(
        body(),
        status_code=upstream.status_code,
        headers=passthrough,
        media_type=passthrough.get("content-type"),
    )


@app.get("/lyrics/{video_id}")
def lyrics(video_id: str):
    """Plain-text lyrics via the watch playlist's lyrics page
    (ytmusicapi). Not every track has lyrics; an empty result is a
    normal 200 with null lyrics, not an error."""

    def run():
        watch = ytm.get_watch_playlist(videoId=video_id, limit=1)
        browse_id = watch.get("lyrics")
        if not browse_id:
            return {"id": video_id, "lyrics": None, "source": None}
        page = ytm.get_lyrics(browse_id, timestamps=False)
        return {
            "id": video_id,
            "lyrics": page.get("lyrics"),
            "source": page.get("source"),
        }

    try:
        return cached(f"lyrics:{video_id}", 3600, run)
    except Exception as e:
        raise HTTPException(502, f"lyrics lookup failed: {e.__class__.__name__}")


# --- Linux web app (static frontend, same origin) ------------------------------
# The web player ships as static files in ../linux/app and is served
# by this process, so the page and the API share an origin and the
# player needs no CORS configuration. Mounted last so every API
# route above wins over the static catch-all. Absent files (a
# backend-only checkout) simply skip the mount.
_WEB_APP_DIR = Path(__file__).resolve().parent.parent / "linux" / "app"
if _WEB_APP_DIR.is_dir():
    from fastapi.staticfiles import StaticFiles

    app.mount("/", StaticFiles(directory=_WEB_APP_DIR, html=True), name="webapp")
