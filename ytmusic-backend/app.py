"""arzxsh YouTube Music backend.

Thin FastAPI wrapper over ytmusicapi (catalog/search/artists/charts) with
server-side stream resolution via yt-dlp. Stateless, no accounts, no stored
credentials: unauthenticated public-catalog access only.

Spec: ../design/ytmusic-backend/SPEC.md
Run:  .venv/bin/uvicorn app:app --host 127.0.0.1 --port 8077
WARNING: bind 127.0.0.1 only. This server has no accounts or auth
and must never be reachable from the LAN (the same rule linux/run.sh
states); the Host allowlist and per-run token below are seatbelts,
not a licence to expose it.
"""

import hashlib
import hmac
import json
import logging
import os
import secrets
import subprocess
import threading
import time
from collections import OrderedDict
from pathlib import Path
from urllib.parse import quote

import requests
import yt_dlp
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse
from ytmusicapi import YTMusic

logger = logging.getLogger("arzxsh-backend")
if os.environ.get("YTM_DL_NO_VERIFY") == "1":
    # Loud on purpose (audit 2026-11-11): this flag disables yt-dlp
    # TLS verification, so it must never be on silently.
    logger.warning(
        "YTM_DL_NO_VERIFY=1 is set: yt-dlp TLS certificate verification "
        "is DISABLED. Only ever use this inside a TLS-intercepted dev "
        "sandbox; never in production."
    )

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


class _LazyYTMusic:
    def __init__(self):
        self._client = None
        self._lock = threading.Lock()

    def get(self):
        if self._client is None:
            with self._lock:
                if self._client is None:
                    self._client = YTMusic()
        return self._client

    def __getattr__(self, name):
        return getattr(self.get(), name)


ytm = _LazyYTMusic()


def _prewarm_ytm():
    try:
        ytm.get()
    except Exception:
        pass


threading.Thread(target=_prewarm_ytm, daemon=True).start()

# --- request guards (audit 2026-11-11) ---------------------------------------
# The service is a same-machine, single-user backend with no accounts.
# Three layered guards keep it that way even when a browser on the
# machine visits a hostile page:
#  1. Host allowlist: only loopback Host headers are served. A DNS-
#     rebinding origin carries the attacker's own hostname in Host,
#     so classic rebinding dies here.
#  2. Per-run token: launchers (linux/run.sh, the CLI) generate a
#     random token, pass it via ARZXSH_API_TOKEN, and their clients
#     send it as X-Arzxsh-Token. It is required (when configured) on
#     every mutating request and on the /local enumeration: a drive-by
#     cross-origin POST is a CORS-safelisted simple request and cannot
#     set a custom header, so it can never present the token. Media
#     GETs (/audio, /local/file, /local/art) cannot carry headers from
#     <audio>/<img> elements; they rely on the Host allowlist, the
#     absence of CORS headers, and (for local files) unguessable
#     per-run ids. With no token configured (bare uvicorn dev run)
#     the Host allowlist and headers still apply.
#  3. Baseline security headers on every response.
_API_TOKEN = os.environ.get("ARZXSH_API_TOKEN", "").strip()
_ALLOWED_HOSTS = {"127.0.0.1", "localhost", "::1"}
_CSP = (
    "default-src 'self'; script-src 'self'; "
    "style-src 'self' 'unsafe-inline'; "
    "img-src 'self' https: data: blob:; media-src 'self' blob:; "
    "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'"
)
# Coarse budget for the routes that shell out to yt-dlp / upstream
# fetches: generous for one interactive user, useless for a loop.
_EXPENSIVE_PREFIXES = ("/search", "/stream", "/audio", "/download",
                       "/radio", "/artist", "/album", "/playlist", "/lyrics", "/song",
                       "/analyze")
_RATE_LIMIT = 120  # requests per minute across the expensive routes
_rate_state: dict[str, float] = {}
_rate_count = 0


def _host_allowed(host_header: str) -> bool:
    host = (host_header or "").strip().lower()
    if host.startswith("["):  # [::1]:8077
        host = host[1:].split("]", 1)[0]
    else:
        host = host.rsplit(":", 1)[0] if ":" in host else host
    return host in _ALLOWED_HOSTS


def _rate_ok() -> bool:
    global _rate_count
    now = time.time()
    window = _rate_state.get("window", 0.0)
    if now - window >= 60:
        _rate_state["window"] = now
        _rate_count = 0
    _rate_count += 1
    return _rate_count <= _RATE_LIMIT


@app.middleware("http")
async def _guard(request: Request, call_next):
    if not _host_allowed(request.headers.get("host", "")):
        return JSONResponse({"detail": "host not allowed"}, status_code=400)
    if request.url.path.startswith(_EXPENSIVE_PREFIXES) and not _rate_ok():
        return JSONResponse({"detail": "rate limit exceeded"}, status_code=429)
    if _API_TOKEN:
        needs_token = request.method in ("POST", "PUT", "PATCH", "DELETE") \
            or request.url.path == "/local"
        if needs_token and not hmac.compare_digest(
                request.headers.get("x-arzxsh-token", ""), _API_TOKEN):
            return JSONResponse({"detail": "missing or bad token"}, status_code=403)
    response = await call_next(request)
    response.headers.setdefault("x-content-type-options", "nosniff")
    response.headers.setdefault("x-frame-options", "DENY")
    response.headers.setdefault("content-security-policy", _CSP)
    response.headers.setdefault("referrer-policy", "no-referrer")
    return response


# --- tiny in-memory TTL cache (bounded, audit 2026-11-11) --------------------
# OrderedDict with LRU eviction: cache keys embed caller-chosen strings
# (search queries, ids), so an unbounded dict was a memory-exhaustion
# vector for any caller who could reach the service.
_CACHE_MAX = 256
_cache: "OrderedDict[str, tuple[float, object]]" = OrderedDict()


def cached(key: str, ttl: float, fn):
    now = time.time()
    hit = _cache.get(key)
    if hit and hit[0] > now:
        _cache.move_to_end(key)
        return hit[1]
    val = fn()
    _cache[key] = (now + ttl, val)
    _cache.move_to_end(key)
    while len(_cache) > _CACHE_MAX:
        _cache.popitem(last=False)
    return val


# --- shape helpers (mirror the app's Track / Artist models) -------------------

def hi_res_art(url):
    if not url:
        return url
    if "googleusercontent.com" in url:
        if re.search(r"=w\d+-h\d+", url):
            return re.sub(r"=w\d+-h\d+", "=w1080-h1080", url, count=1)
        if "=" not in url.rsplit("/", 1)[-1]:
            return url + "=w1080-h1080"
        return url
    if "i.ytimg.com" in url:
        return url.replace("mqdefault", "hqdefault")
    return url


def track(t: dict) -> dict | None:
    vid = t.get("videoId")
    if not vid:
        return None
    artists = t.get("artists") or []
    thumbs = t.get("thumbnails") or []
    art = hi_res_art(thumbs[-1]["url"]) if thumbs else None
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
    # The identity fields let clients (the CLI) verify the service on
    # the fixed loopback port really is this backend before trusting
    # it with catalog/download traffic (port-squat check).
    return {"ok": True, "app": "arzxsh-ytm", "version": "0.1.0"}


@app.get("/ui-config")
def ui_config():
    """Hands the per-run token to the same-origin web UI. Reachable
    only under the Host allowlist, and the app sets no CORS headers,
    so a cross-origin page cannot read the response; a rebound origin
    fails the Host check outright."""
    return {"token": _API_TOKEN}


@app.get("/search")
def search(q: str = Query(min_length=1), filter: str = "songs", limit: int = 25):
    if filter not in ("songs", "artists", "albums", "videos"):
        raise HTTPException(400, "filter must be songs|artists|albums|videos")
    limit = max(1, min(limit, 50))

    def run():
        res = ytm.search(q, filter=filter, limit=limit)
        if filter == "songs":
            results = tracks(res)
            # A songs-only search misses everything YouTube Music
            # carries only as a video (remixes, mashups, live takes,
            # regional uploads): the songs shelf backfills with
            # related official tracks, so the wanted upload can be
            # absent even from a full-length result list. Always top
            # the list up from the videos shelf: same shaping,
            # deduped by video id, songs first. A failed videos call
            # leaves the songs result standing on its own.
            try:
                vids = ytm.search(q, filter="videos", limit=limit)
            except Exception:
                vids = []
            seen = {t["id"] for t in results}
            for t in tracks(vids):
                if t["id"] not in seen:
                    seen.add(t["id"])
                    results.append(t)
            return {"type": filter, "results": results[:50]}
        if filter == "videos":
            return {"type": filter, "results": tracks(res)}
        if filter == "artists":
            return {
                "type": filter,
                "results": [
                    {
                        "id": r.get("browseId"),
                        "name": r.get("artist"),
                        "artwork": hi_res_art((r.get("thumbnails") or [{}])[-1].get("url")),
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
                    "artwork": hi_res_art((r.get("thumbnails") or [{}])[-1].get("url")),
                }
                for r in res
                if r.get("browseId")
            ],
        }

    return cached(f"search:{filter}:{limit}:{q.lower()}", 300, run)


@app.get("/suggest")
def suggest(q: str = Query(min_length=1)):
    def run():
        try:
            res = ytm.get_search_suggestions(q)
        except Exception:
            return {"results": []}
        out: list[str] = []
        for item in res or []:
            if isinstance(item, str):
                text = item
            elif isinstance(item, dict):
                text = item.get("text")
            else:
                text = None
            if text and text not in out:
                out.append(str(text))
        return {"results": out[:10]}

    return cached(f"suggest:{q.lower()}", 120, run)


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
            "artwork": hi_res_art((a.get("thumbnails") or [{}])[-1].get("url")),
            "subscribers": a.get("subscribers"),
            "songs": tracks(songs.get("results")),
            "albums": [
                {
                    "id": r.get("browseId"),
                    "title": r.get("title"),
                    "year": r.get("year"),
                    "artwork": hi_res_art((r.get("thumbnails") or [{}])[-1].get("url")),
                }
                for r in (albums.get("results") or [])
                if r.get("browseId")
            ],
            "singles": [
                {
                    "id": r.get("browseId"),
                    "title": r.get("title"),
                    "year": r.get("year"),
                    "artwork": hi_res_art((r.get("thumbnails") or [{}])[-1].get("url")),
                }
                for r in (singles.get("results") or [])
                if r.get("browseId")
            ],
            "related": [
                {
                    "id": r.get("browseId"),
                    "name": r.get("title"),
                    "artwork": hi_res_art((r.get("thumbnails") or [{}])[-1].get("url")),
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
            "artwork": hi_res_art((a.get("thumbnails") or [{}])[-1].get("url")),
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
                    "artwork": hi_res_art((r.get("thumbnails") or [{}])[-1].get("url")),
                }
                for r in (arts or [])
                if (r.get("browseId") or r.get("channelId"))
            ],
        }

    try:
        return cached(f"charts:{country}", 900, run)
    except Exception as e:
        raise HTTPException(502, f"charts failed: {e.__class__.__name__}")


@app.get("/playlist/{playlist_id}")
def playlist(playlist_id: str):
    """A YouTube Music playlist's tracks (used by the CLI's
    playlist import). Accepts the bare playlist id."""

    def run():
        p = ytm.get_playlist(playlist_id, limit=200)
        return {
            "id": playlist_id,
            "title": p.get("title"),
            "author": (p.get("author") or {}).get("name") if isinstance(p.get("author"), dict) else p.get("author"),
            "tracks": tracks(p.get("tracks")),
        }

    try:
        return cached(f"playlist:{playlist_id}", 600, run)
    except Exception as e:
        raise HTTPException(502, f"playlist lookup failed: {e.__class__.__name__}")


@app.get("/radio")
def radio(video_id: str):
    def run():
        w = ytm.get_watch_playlist(videoId=video_id, limit=25)
        return {"results": tracks(w.get("tracks"))}

    try:
        return cached(f"radio:{video_id}", 300, run)
    except Exception as e:
        raise HTTPException(502, f"radio failed: {e.__class__.__name__}")


_VIDEO_ID_CHARS = frozenset(
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")


@app.get("/song/{video_id}")
def song(video_id: str):
    if not (6 <= len(video_id) <= 20
            and all(c in _VIDEO_ID_CHARS for c in video_id)):
        raise HTTPException(404, "video not found")

    def run():
        opts = {
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
        }
        if os.environ.get("YTM_DL_NO_VERIFY") == "1":
            opts["nocheckcertificate"] = True
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(
                f"https://www.youtube.com/watch?v={quote(video_id)}",
                download=False,
            )
        thumbs = info.get("thumbnails") or []
        art = hi_res_art(info.get("thumbnail") or (thumbs[-1]["url"] if thumbs else None))
        return {
            "id": video_id,
            "source": "link",
            "title": info.get("title") or "",
            "artist": info.get("uploader") or info.get("channel") or "",
            "artist_id": info.get("channel_id"),
            "album": None,
            "duration_s": info.get("duration"),
            "artwork": art,
            "is_explicit": False,
        }

    try:
        return cached(f"song:{video_id}", 3600, run)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(502, f"song failed: {e.__class__.__name__}")


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
        # Total byte budget (audit 2026-11-11): the upstream read
        # timeout only bounds gaps between chunks, so without a cap a
        # single proxied response could stream forever. 256MB is far
        # beyond any single track's audio.
        sent = 0
        try:
            for chunk in upstream.iter_content(chunk_size=64 * 1024):
                if chunk:
                    sent += len(chunk)
                    if sent > 256 * 1024 * 1024:
                        break
                    yield chunk
        finally:
            upstream.close()

    return StreamingResponse(
        body(),
        status_code=upstream.status_code,
        headers=passthrough,
        media_type=passthrough.get("content-type"),
    )


_LRCLIB_UA = "arzxsh-backend/0.1.0 (https://github.com/lulitaxu/music-player)"


def _parse_lrc(text: str | None) -> list[dict] | None:
    """LRC text -> [{"t": seconds, "text": line}], sorted by time.
    A line may carry several stamps (repeated chorus); each stamp
    gets its own entry. Stamp-only / metadata lines are dropped.
    The pattern compiles per call: `re` is imported further down
    this module (local-library section), so a module-level compile
    here would run before that import."""
    if not text:
        return None
    stamp = re.compile(r"\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]")
    out: list[dict] = []
    for raw in text.splitlines():
        stamps = stamp.findall(raw)
        if not stamps:
            continue
        line = stamp.sub("", raw).strip()
        if not line:
            continue
        for minutes, seconds, frac in stamps:
            t = int(minutes) * 60 + int(seconds)
            if frac:
                t += int(frac) / (10 ** len(frac))
            out.append({"t": round(t, 3), "text": line})
    if not out:
        return None
    out.sort(key=lambda item: item["t"])
    return out


def _watch_track_meta(watch: dict) -> tuple[str, str, int | None]:
    """(title, artist, duration_s) for the watch playlist's first
    track: the lookup keys LRCLIB matches on. The watch parser
    carries duration as a "M:SS" length string, not seconds."""
    items = watch.get("tracks") or []
    if not items:
        return "", "", None
    first = items[0] or {}
    title = first.get("title") or ""
    artists = first.get("artists") or []
    artist = artists[0].get("name", "") if artists else ""
    duration = None
    length = first.get("length")
    if isinstance(length, str) and length:
        try:
            parts = [int(p) for p in length.split(":")]
            duration = 0
            for p in parts:
                duration = duration * 60 + p
        except ValueError:
            duration = None
    if duration is None and isinstance(first.get("duration_seconds"), int):
        duration = first["duration_seconds"]
    return title, artist, duration


def _lrclib_lookup(title: str, artist: str, duration: int | None) -> dict | None:
    """LRCLIB (no key, public LRC database). Returns
    {"synced": [...] | None, "plain": str | None} on a hit, None on
    a miss or any failure: LRCLIB is an enhancement, never a hard
    dependency of the lyrics endpoint."""
    if not title or not artist:
        return None
    params = {"track_name": title, "artist_name": artist}
    if duration:
        params["duration"] = duration
    try:
        resp = requests.get(
            "https://lrclib.net/api/get", params=params,
            headers={"User-Agent": _LRCLIB_UA}, timeout=6,
        )
    except Exception:
        return None
    if resp.status_code != 200:
        return None
    try:
        data = resp.json()
    except Exception:
        return None
    if not isinstance(data, dict) or data.get("instrumental"):
        return None
    return {
        "synced": _parse_lrc(data.get("syncedLyrics")),
        "plain": data.get("plainLyrics"),
    }


def _lrclib_search_lookup(title: str, artist: str,
                          duration: int | None) -> dict | None:
    if not title or not artist:
        return None
    try:
        resp = requests.get(
            "https://lrclib.net/api/search",
            params={"track_name": title, "artist_name": artist},
            headers={"User-Agent": _LRCLIB_UA}, timeout=6,
        )
        if resp.status_code != 200:
            return None
        rows = resp.json()
    except Exception:
        return None
    if not isinstance(rows, list):
        return None
    best, best_score = None, None
    for row in rows:
        if not isinstance(row, dict) or row.get("instrumental"):
            continue
        if not row.get("syncedLyrics"):
            continue
        score = 0
        if duration and row.get("duration"):
            score = abs(float(row["duration"]) - float(duration))
            if score > 5:
                continue
        if best is None or score < best_score:
            best, best_score = row, score
    if best is None:
        return None
    return {
        "synced": _parse_lrc(best.get("syncedLyrics")),
        "plain": best.get("plainLyrics"),
    }


def _netease_lookup(title: str, artist: str,
                    duration: int | None) -> dict | None:
    if not title or not artist:
        return None
    headers = {
        "User-Agent": _LRCLIB_UA,
        "Referer": "https://music.163.com/",
    }
    try:
        resp = requests.get(
            "https://music.163.com/api/cloudsearch/pc",
            params={"s": f"{title} {artist}", "type": 1, "limit": 5,
                    "offset": 0},
            headers=headers, timeout=8,
        )
        if resp.status_code != 200:
            return None
        songs = ((resp.json() or {}).get("result") or {}).get("songs") or []
        if not songs:
            return None
        song_id = songs[0].get("id")
        if not song_id:
            return None
        resp = requests.get(
            "https://music.163.com/api/song/lyric",
            params={"id": song_id, "lv": 1, "kv": 1, "tv": -1},
            headers=headers, timeout=8,
        )
        if resp.status_code != 200:
            return None
        lrc = ((resp.json() or {}).get("lrc") or {}).get("lyric")
    except Exception:
        return None
    synced = _parse_lrc(lrc)
    if not synced:
        return None
    return {
        "synced": synced,
        "plain": "\n".join(line["text"] for line in synced),
    }


def _timed_page_synced(page) -> list[dict] | None:
    """ytmusicapi timed lyrics -> [{"t": seconds, "text": line}].
    LyricLine start_time is milliseconds; when the timed page has
    no timestamps after all, its lyrics field is a plain string
    and this returns None."""
    if not page:
        return None
    items = page.get("lyrics")
    if not isinstance(items, list):
        return None
    out: list[dict] = []
    for item in items:
        if isinstance(item, dict):
            text, start = item.get("text"), item.get("start_time")
        else:
            text = getattr(item, "text", None)
            start = getattr(item, "start_time", None)
        if text is None or start is None:
            continue
        text = str(text).strip()
        if not text:
            continue
        out.append({"t": round(float(start) / 1000.0, 3), "text": text})
    if not out:
        return None
    out.sort(key=lambda item: item["t"])
    return out


@app.get("/lyrics/{video_id}")
def lyrics(video_id: str):
    """Lyrics via the watch playlist's lyrics page (ytmusicapi),
    plus synced (timestamped) lines where a source has them.
    Synced sources, in order: LRCLIB exact match, LRCLIB search
    (duration-checked), NetEase, then ytmusicapi's own timed page.
    Not every track has lyrics; an empty result is a normal 200
    with null lyrics, not an error. The "lyrics" / "source" fields
    keep their original meaning (the CLI renders them); "synced"
    is the additive field the web player's karaoke view consumes."""

    def run():
        watch = ytm.get_watch_playlist(videoId=video_id, limit=1)
        browse_id = watch.get("lyrics")
        title, artist, duration = _watch_track_meta(watch)

        synced_hit, synced_source = None, None
        plain_hit, plain_source = None, None
        for fn, name in ((_lrclib_lookup, "LRCLIB"),
                         (_lrclib_search_lookup, "LRCLIB"),
                         (_netease_lookup, "NetEase")):
            try:
                hit = fn(title, artist, duration)
            except Exception:
                hit = None
            if not hit:
                continue
            if hit.get("synced") and synced_hit is None:
                synced_hit, synced_source = hit["synced"], name
            if hit.get("plain") and plain_hit is None:
                plain_hit, plain_source = hit["plain"], name
            if synced_hit is not None:
                break

        if synced_hit is not None:
            plain = plain_hit
            if plain is None and browse_id:
                page = ytm.get_lyrics(browse_id, timestamps=False)
                plain = page.get("lyrics")
            return {"id": video_id, "lyrics": plain,
                    "source": synced_source, "synced": synced_hit}

        if not browse_id:
            if plain_hit:
                return {"id": video_id, "lyrics": plain_hit,
                        "source": plain_source, "synced": None}
            return {"id": video_id, "lyrics": None, "source": None,
                    "synced": None}

        synced = None
        try:
            synced = _timed_page_synced(ytm.get_lyrics(browse_id, timestamps=True))
        except Exception:
            synced = None
        page = ytm.get_lyrics(browse_id, timestamps=False)
        plain = page.get("lyrics")
        source = page.get("source")
        if plain is None and plain_hit:
            plain, source = plain_hit, plain_source
        return {"id": video_id, "lyrics": plain, "source": source,
                "synced": synced}

    try:
        return cached(f"lyrics:{video_id}", 3600, run)
    except Exception as e:
        raise HTTPException(502, f"lyrics lookup failed: {e.__class__.__name__}")


# --- local library + offline downloads (Linux web app) ------------------------
# The web player also plays the user's own audio files. The server
# scans a music folder (ARZXSH_MUSIC_DIR, default ~/Music) and
# serves tracks by ids from its own registry: a request can only
# ever reach a file the scan itself found under that root, never an
# arbitrary path supplied by a client. Ids are HMACs of the path
# keyed by a random per-process secret (audit 2026-11-11): stable
# for the life of the process, but not computable offline from a
# guessed path the way the old unsalted truncated SHA-1 was.
# Downloads land in <music>/arzxsh/ and become local tracks.
import mimetypes
import re

try:
    import mutagen
except ImportError:  # backend still serves the catalog without it
    mutagen = None

_AUDIO_EXTS = {".mp3", ".m4a", ".flac", ".ogg", ".opus", ".wav", ".aac"}
_MUSIC_DIR = Path(
    os.environ.get("ARZXSH_MUSIC_DIR", str(Path.home() / "Music"))
).expanduser()
_DOWNLOADS_DIR = _MUSIC_DIR / "arzxsh"
_local_registry: dict[str, Path] = {}


_LOCAL_SECRET = secrets.token_bytes(32)


def _local_id(path: Path) -> str:
    return "loc_" + hmac.new(
        _LOCAL_SECRET, str(path).encode(), hashlib.sha256
    ).hexdigest()[:20]


def _tag_first(tags, *keys):
    if not tags:
        return None
    for key in keys:
        try:
            value = tags.get(key)
        except Exception:
            value = None
        if value:
            return str(value[0]) if isinstance(value, (list, tuple)) else str(value)
    return None


def _extract_art(raw):
    """Embedded cover art across formats: ID3 APIC frames (mp3),
    the covr atom (m4a), picture blocks (flac/ogg). Returns
    (bytes, mime) or None."""
    try:
        for key in list(raw.keys()):
            frame = raw[key]
            if key.startswith("APIC"):
                return bytes(frame.data), frame.mime or "image/jpeg"
            if key == "covr" and frame:
                mime = "image/png" if int(frame[0].imageformat) == 14 else "image/jpeg"
                return bytes(frame[0]), mime
        pics = getattr(raw, "pictures", None)
        if pics:
            return bytes(pics[0].data), pics[0].mime or "image/jpeg"
        for key in list(raw.keys()):
            if key.lower() == "metadata_block_picture":
                import base64
                from mutagen.flac import Picture
                vals = raw[key]
                if vals:
                    pic = Picture(base64.b64decode(vals[0]))
                    return bytes(pic.data), pic.mime or "image/jpeg"
    except Exception:
        return None
    return None


_COVER_NAMES = ("cover.jpg", "cover.png", "cover.jpeg", "folder.jpg",
                "folder.png", "album.jpg", "album.png", "front.jpg",
                "front.png")


def _folder_cover(path: Path):
    try:
        for name in _COVER_NAMES:
            cand = path.parent / name
            if cand.is_file():
                return cand
    except Exception:
        pass
    return None


def _local_track(path: Path) -> dict:
    tid = _local_id(path)
    title, artist, album = path.stem, "Local file", path.parent.name
    duration, has_art = None, False
    if mutagen is not None:
        raw = None
        try:
            raw = mutagen.File(path)
        except Exception:
            raw = None
        if raw is not None:
            # Duration comes from the raw parse: some easy wrappers
            # (EasyMP4) report no length for untagged files.
            if raw.info and raw.info.length:
                duration = int(raw.info.length) or None
            has_art = _extract_art(raw) is not None
        try:
            easy = mutagen.File(path, easy=True)
            if easy:
                title = _tag_first(easy, "title") or title
                artist = _tag_first(easy, "artist") or artist
                album = _tag_first(easy, "album") or album
                if duration is None and easy.info and easy.info.length:
                    duration = int(easy.info.length) or None
        except Exception:
            pass
        if duration is None and path.suffix.lower() == ".wav":
            # mutagen reports no length for plain WAV; the stdlib
            # wave module reads it from the header directly.
            try:
                import wave

                with wave.open(str(path)) as w:
                    duration = int(w.getnframes() / w.getframerate()) or None
            except Exception:
                pass
    if not has_art:
        has_art = _folder_cover(path) is not None
    try:
        added_at = path.stat().st_mtime
    except Exception:
        added_at = None
    return {
        "id": tid,
        "source": "local",
        "title": title,
        "artist": artist,
        "artist_id": None,
        "album": album,
        "duration_s": duration,
        "artwork": f"/local/art/{tid}" if has_art else None,
        "is_explicit": False,
        "added_at": added_at,
    }


def _scan_local() -> list[dict]:
    global _local_registry
    registry: dict[str, Path] = {}
    if _MUSIC_DIR.is_dir():
        for p in sorted(_MUSIC_DIR.rglob("*")):
            if p.is_file() and p.suffix.lower() in _AUDIO_EXTS:
                registry[_local_id(p)] = p
    _local_registry = registry
    return [_local_track(p) for p in registry.values()]


def _registry_lookup(track_id: str):
    path = _local_registry.get(track_id)
    if path is None:
        _scan_local()
        path = _local_registry.get(track_id)
    return path


@app.get("/local")
def local_library(fresh: int = 0):
    if fresh:
        _cache.pop("local:scan", None)
    return cached(
        "local:scan", 20,
        lambda: {"dir": str(_MUSIC_DIR), "tracks": _scan_local()},
    )


def _range_stream(path: Path, request: Request):
    """Byte-range file streaming for local tracks (seek support),
    same contract as the /audio proxy."""
    size = path.stat().st_size
    start, end, status = 0, size - 1, 200
    range_header = request.headers.get("range", "")
    if range_header.startswith("bytes="):
        a, _, b = range_header[6:].partition("-")
        try:
            if a:
                start = int(a)
                end = int(b) if b else size - 1
            elif b:
                start = max(0, size - int(b))
        except ValueError:
            start, end = 0, size - 1
        end = min(end, size - 1)
        if 0 <= start <= end:
            status = 206
        else:
            start, end, status = 0, size - 1, 200
    length = end - start + 1
    headers = {"accept-ranges": "bytes", "content-length": str(length),
               "x-content-type-options": "nosniff"}
    if status == 206:
        headers["content-range"] = f"bytes {start}-{end}/{size}"

    def body():
        with open(path, "rb") as fh:
            fh.seek(start)
            remaining = length
            while remaining > 0:
                chunk = fh.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
                yield chunk

    mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    return StreamingResponse(body(), status_code=status, headers=headers, media_type=mime)


@app.delete("/local/file/{track_id}")
def local_delete(track_id: str):
    """Delete a downloaded file. Only files inside the downloads
    dir (the app's own downloads) may be deleted through the app;
    the user's own music elsewhere in the library folder is never
    touched from here, by design."""
    path = _registry_lookup(track_id)
    if path is None:
        raise HTTPException(404, "unknown local track")
    try:
        inside = path.resolve().is_relative_to(_DOWNLOADS_DIR.resolve())
    except Exception:
        inside = False
    if not inside:
        raise HTTPException(
            403, "only files downloaded by the app can be deleted here")
    try:
        path.unlink()
    except FileNotFoundError:
        pass
    except OSError:
        raise HTTPException(500, "could not delete the file")
    _cache.pop("local:scan", None)
    _scan_local()
    return {"ok": True, "deleted": track_id}


@app.get("/local/file/{track_id}")
def local_file(track_id: str, request: Request):
    path = _registry_lookup(track_id)
    if path is None or not path.is_file():
        raise HTTPException(404, "local track not found")
    return _range_stream(path, request)


@app.get("/local/art/{track_id}")
def local_art(track_id: str):
    from fastapi.responses import Response

    path = _registry_lookup(track_id)
    if path is None:
        raise HTTPException(404, "local track not found")
    art = None
    if mutagen is not None:
        try:
            raw = mutagen.File(path)
            art = _extract_art(raw) if raw else None
        except Exception:
            art = None
    if not art:
        cover = _folder_cover(path)
        if cover is not None:
            try:
                mime = "image/png" if cover.suffix.lower() == ".png" else "image/jpeg"
                art = (cover.read_bytes(), mime)
            except Exception:
                art = None
    if not art:
        raise HTTPException(404, "no embedded artwork")
    data, mime = art
    # The MIME string comes from inside the audio file itself
    # (audit 2026-11-11): never serve it verbatim — a crafted file
    # could declare text/html and run script in this origin.
    if mime not in ("image/jpeg", "image/png", "image/webp", "image/gif"):
        mime = "image/jpeg"
    return Response(content=data, media_type=mime,
                    headers={"cache-control": "max-age=3600",
                             "x-content-type-options": "nosniff"})


def _lrc_text(synced) -> str | None:
    if not synced:
        return None
    out = []
    for line in synced:
        t = float(line.get("t") or 0)
        m, s = divmod(t, 60)
        out.append(f"[{int(m):02d}:{s:05.2f}] {line.get('text', '')}".rstrip())
    return "\n".join(out)


def _fetch_art_bytes(url: str):
    if not url:
        return None
    try:
        r = requests.get(url, headers={"User-Agent": _LRCLIB_UA}, timeout=8)
        r.raise_for_status()
        data = r.content
    except Exception:
        if "maxresdefault" in url:
            return _fetch_art_bytes(url.replace("maxresdefault", "hqdefault"))
        return None
    if data[:3] == b"\xff\xd8\xff":
        return data, "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return data, "image/png"
    return None


def _embed_art_lyrics(path: Path, lyrics_text, art) -> None:
    if mutagen is None or (not lyrics_text and not art):
        return
    try:
        f = mutagen.File(path)
        if f is None:
            return
        ext = path.suffix.lower()
        if ext in (".m4a", ".mp4"):
            from mutagen.mp4 import MP4Cover
            if f.tags is None:
                f.add_tags()
            if lyrics_text:
                f.tags["\xa9lyr"] = lyrics_text
            if art:
                fmt = (MP4Cover.FORMAT_PNG if art[1] == "image/png"
                       else MP4Cover.FORMAT_JPEG)
                f.tags["covr"] = [MP4Cover(art[0], imageformat=fmt)]
            f.save()
        elif ext == ".mp3":
            from mutagen.id3 import APIC, USLT
            if f.tags is None:
                f.add_tags()
            if art:
                f.tags.delall("APIC")
                f.tags.add(APIC(encoding=3, mime=art[1], type=3,
                                desc="", data=art[0]))
            if lyrics_text:
                f.tags.delall("USLT")
                f.tags.add(USLT(encoding=3, lang="eng", desc="",
                                text=lyrics_text))
            f.save()
        elif ext in (".flac", ".ogg", ".opus"):
            from mutagen.flac import Picture
            pic = None
            if art:
                pic = Picture()
                pic.type = 3
                pic.mime = art[1]
                pic.data = art[0]
            if ext == ".flac":
                if pic is not None:
                    f.clear_pictures()
                    f.add_picture(pic)
                if lyrics_text:
                    f["LYRICS"] = lyrics_text
                f.save()
            else:
                import base64
                if pic is not None:
                    f["metadata_block_picture"] = \
                        base64.b64encode(pic.write()).decode()
                if lyrics_text:
                    f["LYRICS"] = lyrics_text
                f.save()
    except Exception:
        pass


def _download_extras(path: Path, video_id: str, thumb_url: str = "") -> None:
    lyrics_text = None
    try:
        res = lyrics(video_id)
        lyrics_text = _lrc_text(res.get("synced")) or res.get("lyrics")
    except Exception:
        lyrics_text = None
    art = _fetch_art_bytes(
        thumb_url or f"https://i.ytimg.com/vi/{video_id}/maxresdefault.jpg")
    _embed_art_lyrics(path, lyrics_text, art)


@app.post("/download/{video_id}")
def download(video_id: str, title: str = "", artist: str = ""):
    """Save a track's audio into <music>/arzxsh/ (offline). The
    download runs through yt-dlp itself, which retries fragments
    and resumes partial files — a plain streamed copy dies whole
    on any connection hiccup. The filename is sanitized, the write
    never leaves the downloads dir, and a repeat call for an
    existing file returns the existing local track."""
    def clean(s: str) -> str:
        s = re.sub(r"[^\w\s.-]+", "", s or "", flags=re.UNICODE).strip()
        return re.sub(r"\s+", " ", s)[:80]

    name = clean(f"{artist} - {title}") if (artist or title) else ""
    if not name or name == "-":
        name = video_id
    _DOWNLOADS_DIR.mkdir(parents=True, exist_ok=True)
    for existing in _DOWNLOADS_DIR.iterdir():
        if existing.is_file() and existing.stem == name and existing.suffix.lower() in _AUDIO_EXTS:
            _cache.pop("local:scan", None)
            _scan_local()
            return {"ok": True, "already": True, "track": _local_track(existing)}
    opts = {
        "quiet": True,
        "no_warnings": True,
        "format": "bestaudio[ext=m4a]/bestaudio[ext=opus]/bestaudio[ext=ogg]/bestaudio/best",
        "outtmpl": str(_DOWNLOADS_DIR / f"{name}.%(ext)s"),
        "retries": 3,
        "fragment_retries": 3,
        "continuedl": True,
    }
    # Same dev-sandbox-only escape hatch as _resolve_stream: TLS-
    # intercepted environments only, NEVER production — it disables
    # certificate verification for the download too. A loud warning
    # is logged at startup whenever the variable is set.
    if os.environ.get("YTM_DL_NO_VERIFY") == "1":
        opts["nocheckcertificate"] = True
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(
                f"https://www.youtube.com/watch?v={quote(video_id)}",
                download=True,
            )
            dest = Path(ydl.prepare_filename(info))
    except Exception as e:
        for part in _DOWNLOADS_DIR.glob(f"{name}.*.part"):
            part.unlink(missing_ok=True)
        raise HTTPException(502, f"download failed: {e.__class__.__name__}")
    if not dest.is_file():
        raise HTTPException(502, "download produced no file")
    # YTM audio arrives untagged; write the known title/artist into
    # the file so every later scan shows real metadata, not the
    # filename. Best-effort: an untaggable format keeps the
    # filename fallback.
    if mutagen is not None and (title or artist):
        try:
            f = mutagen.File(dest, easy=True)
            if f is not None:
                if title:
                    f["title"] = title
                if artist:
                    f["artist"] = artist
                f.save()
        except Exception:
            pass
    thumbs = (info or {}).get("thumbnails") or []
    _download_extras(dest, video_id,
                     hi_res_art(thumbs[-1].get("url", "")) if thumbs else "")
    _cache.pop("local:scan", None)
    _scan_local()
    return {"ok": True, "already": False, "track": _local_track(dest)}


# --- track analysis for Auto Mix ---------------------------------------------
# BPM + musical key analysis, the desktop/CLI counterpart of the
# Android app's on-device analysis (0.6.19). Same method and same
# honesty rules: values are computed from the track's real audio or
# not at all. Local files are read from the scanned library; catalog
# tracks are fetched once into an analysis cache under the data dir
# and read from there. Results persist per track key
# ("<source>_<id>") so analysis runs once per track.
#
# Method (mirrors the Android DSP): decode to mono 22,050 Hz PCM
# (ffmpeg), spectral-flux onset envelope (2,048-point FFT, hop 512),
# autocorrelation over ~35-240 BPM with a Gaussian tempo prior at
# 120, Krumhansl-Schmuckler chroma key mapped to a Camelot code,
# plus a beat phase (first-beat offset) and head/tail loudness so
# the players can align beats and match levels across a transition.
# numpy does the math; without it the endpoints report unavailable
# and every other feature keeps working.

try:
    import numpy as _np
except ImportError:
    _np = None

_DATA_DIR = Path(
    os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local" / "share"))
) / "arzxsh"
_ANALYSIS_FILE = _DATA_DIR / "analysis.json"
_ANALYSIS_AUDIO_DIR = _DATA_DIR / "analysis-audio"
_analysis_cache: "dict | None" = None
_analysis_lock = threading.Lock()

_FIFTHS_ABOVE_C = [0, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10, 5]
_KS_MAJOR = [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88]
_KS_MINOR = [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17]


def _camelot(root: int, minor: bool) -> str:
    pc = ((root % 12) + 12) % 12
    if minor:
        rel_major = (pc + 3) % 12
        return f"{((_FIFTHS_ABOVE_C[rel_major] + 7) % 12) + 1}A"
    return f"{((_FIFTHS_ABOVE_C[pc] + 7) % 12) + 1}B"


def _analysis_load() -> dict:
    global _analysis_cache
    if _analysis_cache is not None:
        return _analysis_cache
    data = {}
    try:
        raw = json.loads(_ANALYSIS_FILE.read_text())
        if isinstance(raw, dict):
            data = {k: v for k, v in raw.items() if isinstance(v, dict)}
    except Exception:
        data = {}
    _analysis_cache = data
    return data


def _analysis_save() -> None:
    try:
        _DATA_DIR.mkdir(parents=True, exist_ok=True)
        tmp = _ANALYSIS_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(_analysis_cache or {}))
        tmp.replace(_ANALYSIS_FILE)
    except Exception:
        pass


def _analysis_audio_path(source: str, tid: str) -> "Path | None":
    if source == "local":
        path = _local_registry.get(tid)
        if path is None:
            _scan_local()
            path = _local_registry.get(tid)
        return path
    if source != "ytmusic":
        return None
    _ANALYSIS_AUDIO_DIR.mkdir(parents=True, exist_ok=True)
    for existing in _ANALYSIS_AUDIO_DIR.iterdir():
        if existing.is_file() and existing.stem == tid \
                and existing.suffix.lower() in _AUDIO_EXTS:
            return existing
    opts = {
        "quiet": True,
        "no_warnings": True,
        "format": "bestaudio[ext=m4a]/bestaudio[ext=opus]/bestaudio[ext=ogg]/bestaudio/best",
        "outtmpl": str(_ANALYSIS_AUDIO_DIR / f"{tid}.%(ext)s"),
        "retries": 3,
        "fragment_retries": 3,
    }
    if os.environ.get("YTM_DL_NO_VERIFY") == "1":
        opts["nocheckcertificate"] = True
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            ydl.download([f"https://music.youtube.com/watch?v={tid}"])
    except Exception:
        return None
    for existing in _ANALYSIS_AUDIO_DIR.iterdir():
        if existing.is_file() and existing.stem == tid \
                and existing.suffix.lower() in _AUDIO_EXTS:
            return existing
    return None


def _decode_pcm(path: Path):
    if _np is None:
        return None
    try:
        proc = subprocess.run(
            ["ffmpeg", "-v", "error", "-nostdin", "-i", str(path),
             "-t", "240", "-ac", "1", "-ar", "22050", "-f", "f32le", "-"],
            capture_output=True, timeout=120)
    except Exception:
        return None
    if proc.returncode != 0 or not proc.stdout:
        return None
    return _np.frombuffer(proc.stdout, dtype=_np.float32).copy()


def _analyze_pcm(x) -> "dict | None":
    sr, n_fft, hop = 22050, 2048, 512
    fps = sr / hop
    if x is None or len(x) < sr * 20:
        return None
    window = _np.hanning(n_fft).astype(_np.float32)
    n_frames = 1 + (len(x) - n_fft) // hop
    if n_frames < int(fps * 20):
        return None
    freqs = _np.fft.rfftfreq(n_fft, 1.0 / sr)
    chroma_mask = (freqs >= 55.0) & (freqs <= 5000.0)
    pcs = _np.zeros(len(freqs), dtype=_np.int64)
    with _np.errstate(divide="ignore", invalid="ignore"):
        pcs = ((_np.round(12.0 * _np.log2(_np.where(freqs > 0, freqs, 1.0) / 440.0)) + 9) % 12).astype(_np.int64)
    chroma_mat = _np.zeros((len(freqs), 12), dtype=_np.float64)
    rows = _np.where(chroma_mask)[0]
    chroma_mat[rows, pcs[rows]] = 1.0
    env = _np.zeros(n_frames, dtype=_np.float64)
    chroma = _np.zeros(12, dtype=_np.float64)
    prev = _np.zeros((1, n_fft // 2 - 1), dtype=_np.float64)
    chunk = 2048
    for start in range(0, n_frames, chunk):
        idx = _np.arange(start, min(start + chunk, n_frames))
        frames = _np.stack([x[i * hop:i * hop + n_fft] for i in idx]) * window
        mag = _np.abs(_np.fft.rfft(frames, axis=1)).astype(_np.float64)
        chroma += mag.sum(axis=0) @ chroma_mat
        flux_src = mag[:, 2:]
        d = _np.diff(_np.vstack([prev, flux_src]), axis=0)
        env[idx] = _np.where(d > 0, d, 0).sum(axis=1)
        prev = flux_src[-1:]
    e = env - env.mean()
    if float(e @ e) < 1e-9:
        return None
    min_lag = max(4, int(fps * 60.0 / 240.0))
    max_lag = min(int(fps * 60.0 / 35.0), len(e) // 2)
    if max_lag <= min_lag:
        return None

    def ac(lag: int) -> float:
        return float(e[:-lag] @ e[lag:]) / (len(e) - lag)

    best_lag, best_score = -1, -1e308
    for lag in range(min_lag, max_lag + 1):
        bpm = 60.0 * fps / lag
        octaves = _np.log2(bpm / 120.0)
        prior = _np.exp(-(octaves * octaves) / (2 * 0.55 * 0.55))
        score = ac(lag) * (0.35 + 0.65 * prior)
        if score > best_score:
            best_score, best_lag = score, lag
    if best_lag < 0:
        return None
    refined = float(best_lag)
    if min_lag < best_lag < max_lag:
        a, b, c = ac(best_lag - 1), ac(best_lag), ac(best_lag + 1)
        denom = a - 2 * b + c
        if denom != 0.0:
            refined = best_lag + 0.5 * (a - c) / denom
    bpm = 60.0 * fps / refined
    if not (30.0 <= bpm <= 250.0):
        return None
    bpm = round(bpm * 2) / 2.0
    if chroma.sum() < 1e-9:
        return None
    cvec = chroma / chroma.sum()

    def pearson(a, b) -> float:
        a = a - a.mean()
        b = b - b.mean()
        den = float(_np.sqrt((a @ a) * (b @ b)))
        return float(a @ b) / den if den else 0.0

    best_corr, best_root, best_minor = -2.0, 0, False
    for minor, profile in ((False, _KS_MAJOR), (True, _KS_MINOR)):
        prof = _np.array(profile, dtype=_np.float64)
        for root in range(12):
            rotated = _np.array([prof[(i - root) % 12] for i in range(12)])
            corr = pearson(cvec, rotated)
            if corr > best_corr:
                best_corr, best_root, best_minor = corr, root, minor
    period = fps * 60.0 / bpm
    best_phase, best_energy = 0, -1.0
    for phase in range(max(1, int(period))):
        grid = _np.arange(phase, len(env), period)
        energy = float(env[grid.astype(_np.int64)].sum())
        if energy > best_energy:
            best_energy, best_phase = energy, phase
    win = min(int(sr * 15), len(x) // 2)

    def db(segment) -> float:
        if len(segment) == 0:
            return -60.0
        rms = float(_np.sqrt(_np.mean(segment.astype(_np.float64) ** 2)))
        return max(-60.0, min(0.0, 20.0 * _np.log10(rms + 1e-9)))

    return {
        "bpm": bpm,
        "root": best_root,
        "minor": best_minor,
        "camelot": _camelot(best_root, best_minor),
        "phase": round(best_phase / fps, 3),
        "headDb": round(db(x[:win]), 1),
        "tailDb": round(db(x[-win:]), 1),
    }


@app.get("/analysis")
def analysis(keys: str = ""):
    store_map = _analysis_load()
    out = {}
    for key in [k.strip() for k in keys.split(",") if k.strip()][:200]:
        out[key] = store_map.get(key)
    return out


@app.post("/analyze")
def analyze(source: str = "ytmusic", id: str = ""):
    if _np is None:
        raise HTTPException(503, "analysis is unavailable on this install")
    if not id or len(id) > 200 or "/" in id or "\\" in id or ".." in id:
        raise HTTPException(400, "bad track id")
    if source not in ("ytmusic", "local"):
        raise HTTPException(400, "bad source")
    key = f"{source}_{id}"
    with _analysis_lock:
        store_map = _analysis_load()
        if key in store_map:
            return store_map[key]
        path = _analysis_audio_path(source, id)
        if path is None:
            raise HTTPException(422, "no audio available for this track")
        result = _analyze_pcm(_decode_pcm(path))
        if result is None:
            raise HTTPException(422, "could not analyze this track")
        store_map[key] = result
        _analysis_save()
        return result


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
