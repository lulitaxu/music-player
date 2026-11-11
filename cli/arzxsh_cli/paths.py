"""Shared filesystem locations for the CLI (audit 2026-11-11).

Runtime files that used to sit at fixed, predictable names in the
shared temp dir (backend log, cava config, mpv IPC sockets) now live
in a per-user 0700 directory, so another local user cannot pre-plant
symlinks at those paths or enumerate the mpv sockets as easily. The
per-run backend token file location is shared by the launchers
(linux/run.sh, the CLI's ensure_backend) and the backend client.
"""
from __future__ import annotations

import os
import tempfile
from pathlib import Path


def runtime_dir() -> Path:
    base = os.environ.get("XDG_RUNTIME_DIR")
    root = Path(base) if base else Path(tempfile.gettempdir())
    try:
        uid = os.getuid()
    except AttributeError:  # non-unix fallback; the CLI targets Linux
        uid = 0
    d = root / f"arzxsh-{uid}"
    d.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(d, 0o700)
    except OSError:
        pass
    return d


def token_file() -> Path:
    root = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    return root / "arzxsh" / "api-token"


def load_api_token() -> str:
    env = os.environ.get("ARZXSH_API_TOKEN", "").strip()
    if env:
        return env
    try:
        return token_file().read_text().strip()
    except Exception:
        return ""
