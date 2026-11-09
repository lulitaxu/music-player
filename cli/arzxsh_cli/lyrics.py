"""Lyrics for the CLI: synced lines from LRCLIB (the phone app's
source) with the backend's plain-text lyrics as the fallback.

Synced lines are (timestamp_seconds, text) pairs parsed from LRC.
The active line for a playback position is a binary search away.
"""
from __future__ import annotations

import bisect
import re

import httpx

_LRC_TAG = re.compile(r"\[(\d+):(\d+(?:\.\d+)?)\]")


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
    def __init__(self, backend):
        self.backend = backend
        self._cache: dict[str, dict] = {}

    async def get(self, track: dict) -> dict:
        """Returns {"synced": [(t, text)...], "plain": str|None,
        "source": str}. Every track tries LRCLIB by metadata
        (local files included: their tags carry artist/title);
        only the backend fallback is stream-only, since it is
        keyed to a YouTube video id."""
        key = track.get("id", "")
        if key in self._cache:
            return self._cache[key]
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
        return result
