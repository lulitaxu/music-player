from __future__ import annotations

MODES = {
    "local": ("recent", "title", "artist", "duration"),
    "liked": ("recent", "title", "artist", "duration"),
    "playlist": ("playlist", "recent", "title", "artist", "duration"),
}

_LABELS = {
    "playlist": "Playlist order",
    "title": "Title",
    "artist": "Artist",
    "duration": "Duration",
}


def sort_label(view: str, mode: str) -> str:
    if mode == "recent":
        return "Recently liked" if view == "liked" else "Recently added"
    return _LABELS.get(mode, "Playlist order")


def next_mode(view: str, current: str) -> str:
    modes = MODES.get(view, ())
    if not modes:
        return current
    if current not in modes:
        return modes[0]
    return modes[(modes.index(current) + 1) % len(modes)]


def sort_tracks(tracks: list[dict], view: str, mode: str) -> list[dict]:
    if mode == "title":
        return sorted(tracks, key=lambda t: (t.get("title") or "").lower())
    if mode == "artist":
        return sorted(tracks, key=lambda t: ((t.get("artist") or "").lower(),
                                             (t.get("title") or "").lower()))
    if mode == "duration":
        return sorted(tracks, key=lambda t: t.get("duration_s") or 0)
    if mode == "recent":
        if view == "local":
            return sorted(tracks, key=lambda t: t.get("added_at") or 0,
                          reverse=True)
        if view == "playlist":
            return list(reversed(tracks))
    return list(tracks)
