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
import logging
import os
import secrets
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
ytm = YTMusic()

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
                       "/radio", "/artist", "/album", "/playlist", "/lyrics")
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
    except Exception:
        return None
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
        "format": "bestaudio[ext=m4a]/bestaudio/best",
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
    _cache.pop("local:scan", None)
    _scan_local()
    return {"ok": True, "already": False, "track": _local_track(dest)}


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
