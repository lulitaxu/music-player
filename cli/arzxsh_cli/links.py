from __future__ import annotations

import re
from urllib.parse import parse_qs, urlparse

_ID = re.compile(r"^[A-Za-z0-9_-]{6,20}$")
_HOSTS = {
    "youtube.com",
    "www.youtube.com",
    "m.youtube.com",
    "music.youtube.com",
    "youtube-nocookie.com",
    "www.youtube-nocookie.com",
}
_SEGMENT_PATHS = ("shorts", "embed", "live", "v")


def extract_video_id(text: str) -> str | None:
    s = (text or "").strip()
    if not s:
        return None
    if "://" not in s:
        s = "https://" + s
    try:
        u = urlparse(s)
    except Exception:
        return None
    if u.scheme not in ("http", "https"):
        return None
    host = (u.hostname or "").lower()
    parts = [p for p in u.path.split("/") if p]
    candidate = None
    if host == "youtu.be":
        if parts:
            candidate = parts[0]
    elif host in _HOSTS:
        if parts and parts[0] == "watch":
            values = parse_qs(u.query).get("v")
            if values:
                candidate = values[0]
        elif len(parts) >= 2 and parts[0] in _SEGMENT_PATHS:
            candidate = parts[1]
    if candidate and _ID.match(candidate):
        return candidate
    return None
