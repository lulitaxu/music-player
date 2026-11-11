"""Persistent CLI state: likes, search history, recently played,
download marks and playback settings. One JSON file under the
XDG state dir, written atomically. Nothing here leaves the
machine."""
from __future__ import annotations

import json
import os
from pathlib import Path

EQ_FREQS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]

DEFAULTS = {
    "likes": [],          # full track dicts
    "history": [],        # search strings, newest first, max 10
    "recent": [],         # full track dicts, max 12
    "downloads": {},      # videoId -> local track id
    "playlists": [],      # [{"name": str, "tracks": [track dicts]}]
    "volume": 80,
    "crossfade_s": 3,
    "eq": [0] * 10,       # dB per band, EQ_FREQS order
    "loudnorm": False,
    "speed": 1.0,
    # UI customization: accent preset name (see app.ACCENTS) and
    # the two list-badge toggles. Badges default ON — the
    # behavior the TUI shipped with.
    "settings": {"accent": "green", "download_badges": True, "lyrics_badges": True},
}


def state_path() -> Path:
    root = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state"))
    return root / "arzxsh" / "state.json"


class Store:
    def __init__(self):
        self.path = state_path()
        self.data = dict(DEFAULTS)
        try:
            saved = json.loads(self.path.read_text())
            if isinstance(saved, dict):
                self.data.update({k: saved[k] for k in DEFAULTS if k in saved})
        except Exception:
            pass
        # Normalize the nested settings dict: fill any missing
        # key from the defaults (older state files predate it)
        # and detach it from the shared DEFAULTS object so
        # in-place updates can never corrupt the defaults.
        saved_settings = self.data.get("settings")
        self.data["settings"] = {
            **DEFAULTS["settings"],
            **(saved_settings if isinstance(saved_settings, dict) else {}),
        }

    # -- ui settings --------------------------------------------------
    def setting(self, key: str):
        return self.data["settings"].get(key, DEFAULTS["settings"].get(key))

    def set_setting(self, key: str, value):
        self.data["settings"] = {**self.data["settings"], key: value}
        self.save()

    def save(self):
        try:
            # State-dir hygiene (audit 2026-11-11): the directory is
            # 0700 and the file lands 0600, not umask defaults.
            self.path.parent.mkdir(parents=True, exist_ok=True)
            try:
                os.chmod(self.path.parent, 0o700)
            except OSError:
                pass
            tmp = self.path.with_suffix(".tmp")
            tmp.write_text(json.dumps(self.data))
            try:
                os.chmod(tmp, 0o600)
            except OSError:
                pass
            tmp.replace(self.path)
        except Exception:
            pass

    # -- likes ------------------------------------------------------
    def is_liked(self, track_id: str) -> bool:
        return any(t.get("id") == track_id for t in self.data["likes"])

    def toggle_like(self, track: dict) -> bool:
        likes = self.data["likes"]
        if self.is_liked(track["id"]):
            self.data["likes"] = [t for t in likes if t.get("id") != track["id"]]
            self.save()
            return False
        self.data["likes"] = [track] + [t for t in likes if t.get("id") != track["id"]]
        self.save()
        return True

    # -- history / recent -------------------------------------------
    def add_history(self, q: str):
        q = q.strip()
        if not q:
            return
        h = [x for x in self.data["history"] if x.lower() != q.lower()]
        self.data["history"] = [q] + h[:9]
        self.save()

    def note_played(self, track: dict):
        r = [t for t in self.data["recent"] if t.get("id") != track["id"]]
        self.data["recent"] = [track] + r[:11]
        self.save()

    def mark_downloaded(self, video_id: str, local_id: str):
        self.data["downloads"][video_id] = local_id
        self.save()

    # -- playlists ----------------------------------------------------
    def create_playlist(self, name: str, tracks: list[dict]) -> dict:
        pl = {"name": name.strip() or "Untitled", "tracks": list(tracks)}
        self.data["playlists"].append(pl)
        self.save()
        return pl

    def delete_playlist(self, index: int):
        if 0 <= index < len(self.data["playlists"]):
            self.data["playlists"].pop(index)
            self.save()

    def playlist_add(self, index: int, track: dict):
        pls = self.data["playlists"]
        if 0 <= index < len(pls):
            if all(t.get("id") != track["id"] for t in pls[index]["tracks"]):
                pls[index]["tracks"].append(track)
                self.save()

    def playlist_remove(self, index: int, track_id: str):
        pls = self.data["playlists"]
        if 0 <= index < len(pls):
            pls[index]["tracks"] = [t for t in pls[index]["tracks"] if t.get("id") != track_id]
            self.save()
