"""arzxsh YouTube Music backend.

Thin FastAPI wrapper over ytmusicapi (catalog/search/artists/charts) with
server-side stream resolution via yt-dlp. Stateless, no accounts, no stored
credentials: unauthenticated public-catalog access only.

Spec: ../design/ytmusic-backend/SPEC.md
Run:  .venv/bin/uvicorn app:app --host 0.0.0.0 --port 8077
"""

import os
import time
from urllib.parse import quote

import yt_dlp
from fastapi import FastAPI, HTTPException, Query
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


@app.get("/stream/{video_id}")
def stream(video_id: str):
    """Resolve a playable audio URL server-side. Cached briefly; signed
    googlevideo URLs expire, so the TTL stays short."""

    def run():
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
            info = ydl.extract_info(f"https://www.youtube.com/watch?v={quote(video_id)}", download=False)
        return {
            "id": video_id,
            "url": info.get("url"),
            "format": info.get("format"),
            "ext": info.get("ext"),
            "abr": info.get("abr"),
            "expires_in_s": 300,
        }

    try:
        return cached(f"stream:{video_id}", 300, run)
    except Exception as e:
        raise HTTPException(502, f"stream resolution failed: {e.__class__.__name__}")
