"""Lyrics for the CLI: synced lines from LRCLIB (the phone app's
source) with the backend's plain-text lyrics as the fallback.

Synced lines are (timestamp_seconds, text) pairs parsed from LRC.
The active line for a playback position is a binary search away.

Persistence parity with the Android app: a successful fetch is
written through to an on-disk store (one JSON per track id under
the XDG state dir's lyrics/ subdir, next to state.json). Disk
hits never expire, so downloaded tracks keep their lyrics
offline and across restarts — the in-memory cache alone lost
them on every exit.
"""
from __future__ import annotations

import bisect
import json
import os
import re
from pathlib import Path

import httpx

_LRC_TAG = re.compile(r"\[(\d+):(\d+(?:\.\d+)?)\]")


def lyrics_dir() -> Path:
    """The on-disk lyrics store: <state dir>/arzxsh/lyrics,
    beside store.py's state.json."""
    root = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state"))
    return root / "arzxsh" / "lyrics"


def parse_lrc(text: str) -> list[tuple[float, str]]:
    lines: list[tuple[float, str]] = []
    for raw in (text or "").splitlines():
        stamps = _LRC_TAG.findall(raw)
        if not stamps:
            continue
        lyric = _LRC_TAG.sub("", raw).strip()
        for minutes, seconds in stamps:
            lines.append((int(minutes) * 60 + float(seconds), lyric))
    lines.sort(key=lambda x: x[0])
    return lines


def active_index(lines: list[tuple[float, str]], pos: float) -> int:
    if not lines:
        return -1
    times = [t for t, _ in lines]
    return bisect.bisect_right(times, pos) - 1


class LyricsFetcher:
    def __init__(self, backend, store_dir: Path | None = None):
        self.backend = backend
        self._cache: dict[str, dict] = {}
        self._dir = Path(store_dir) if store_dir is not None else lyrics_dir()

    # -- on-disk store -------------------------------------------------
    def _disk_path(self, key: str) -> Path:
        safe = re.sub(r"[^A-Za-z0-9_-]", "_", key)
        return self._dir / f"{safe}.json"

    def _read_disk(self, key: str) -> dict | None:
        """A stored entry, or None. Disk hits NEVER expire: the
        store exists so a downloaded track's lyrics outlive any
        session. Corrupt or unreadable files count as absent."""
        try:
            data = json.loads(self._disk_path(key).read_text())
            synced = [(float(t), str(x)) for t, x in data.get("synced", [])]
            return {
                "synced": synced,
                "plain": data.get("plain"),
                "source": data.get("source", ""),
            }
        except Exception:
            return None

    def _write_disk(self, key: str, result: dict):
        """Atomic publish (temp + rename, like the Android cache
        and store.py): a crash mid-write never leaves a torn
        file for the next read. Best-effort — a failed write
        only loses the persistence, not the lyrics."""
        try:
            self._dir.mkdir(parents=True, exist_ok=True)
            dest = self._disk_path(key)
            tmp = dest.with_suffix(".tmp")
            tmp.write_text(json.dumps({
                "synced": [[t, x] for t, x in result["synced"]],
                "plain": result["plain"],
                "source": result["source"],
            }))
            tmp.replace(dest)
        except Exception:
            pass

    def has_lyrics(self, track_id: str) -> bool:
        """Cheap availability probe for list badges: true when
        this track's lyrics are already in the memory cache or
        the on-disk store. Disk files only ever hold real lyrics
        (misses are never written), so existence is proof. No
        network, safe to call per row."""
        if not track_id:
            return False
        hit = self._cache.get(track_id)
        if hit and (hit.get("synced") or hit.get("plain")):
            return True
        return self._disk_path(track_id).exists()

    async def get(self, track: dict) -> dict:
        """Returns {"synced": [(t, text)...], "plain": str|None,
        "source": str}. Lookup order: memory cache, then the
        on-disk store, then the network chain. Every track tries
        LRCLIB by metadata (local files included: their tags
        carry artist/title); only the backend fallback is
        stream-only, since it is keyed to a YouTube video id."""
        key = track.get("id", "")
        if key in self._cache:
            return self._cache[key]
        if key:
            disk = self._read_disk(key)
            if disk is not None and (disk["synced"] or disk["plain"]):
                self._cache[key] = disk
                return disk
        result = {"synced": [], "plain": None, "source": ""}
        duration = track.get("duration_s")
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                r = await client.get(
                    "https://lrclib.net/api/get",
                    params={
                        "artist_name": track.get("artist", ""),
                        "track_name": track.get("title", ""),
                        **({"duration": int(duration)} if duration else {}),
                    },
                    headers={"User-Agent": "arzxsh-cli"},
                )
                if r.status_code == 200:
                    data = r.json()
                    if data.get("syncedLyrics"):
                        result["synced"] = parse_lrc(data["syncedLyrics"])
                        result["source"] = "LRCLIB"
                    if data.get("plainLyrics"):
                        result["plain"] = data["plainLyrics"]
                        result["source"] = result["source"] or "LRCLIB"
                if not result["synced"]:
                    # Fallback: search by title + artist and take
                    # the first SYNCED candidate. The exact lookup
                    # needs a duration match, which is where
                    # less-charted tracks (much of the Bangla
                    # catalog included) fall through even though
                    # synced lyrics for them exist.
                    r = await client.get(
                        "https://lrclib.net/api/search",
                        params={
                            "track_name": track.get("title", ""),
                            "artist_name": track.get("artist", ""),
                        },
                        headers={"User-Agent": "arzxsh-cli"},
                    )
                    if r.status_code == 200:
                        for cand in r.json():
                            if cand.get("syncedLyrics"):
                                result["synced"] = parse_lrc(cand["syncedLyrics"])
                                result["source"] = "LRCLIB"
                                if not result["plain"]:
                                    result["plain"] = cand.get("plainLyrics")
                                break
        except Exception:
            pass
        if not result["synced"] and not result["plain"] and track.get("source") != "local":
            try:
                data = await self.backend.backend_lyrics(track["id"])
                if data.get("lyrics"):
                    result["plain"] = data["lyrics"]
                    result["source"] = data.get("source") or "YouTube Music"
            except Exception:
                pass
        self._cache[key] = result
        if key and (result["synced"] or result["plain"]):
            # Only real lyrics persist — a miss must keep the
            # network retry behavior on the next play, not become
            # a permanent disk "hit" of nothing.
            self._write_disk(key, result)
        return result
