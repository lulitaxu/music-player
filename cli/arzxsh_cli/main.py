"""arzxsh CLI entry point.

  arzxsh                     the TUI player
  arzxsh play <query...>     play the top search result now (mpv)
  arzxsh search <query...>   print search results
  arzxsh download <query...> download the top result for offline

Everything talks to the ytmusic-backend on 127.0.0.1:8077. If it
is not running, it is started from the sibling ytmusic-backend
checkout (repo layout or the installed /usr/share/arzxsh layout)
and left running: it is the shared server for the web player too.
"""
from __future__ import annotations

import asyncio
import os
import re
import secrets
import shutil
import subprocess
import sys
import time
from pathlib import Path

import httpx

from .paths import runtime_dir, token_file

BASE = "http://127.0.0.1:8077"

_CTRL_CHARS = re.compile(r"[\x00-\x1f\x7f-\x9f]")


def _display(value) -> str:
    """Make catalog metadata safe to print raw to a terminal
    (audit 2026-11-11): C0/C1 control characters — ESC sequences
    above all — are replaced so a hostile title cannot rewrite the
    user's terminal, spoof prompts, or set the window title."""
    return _CTRL_CHARS.sub(" ", str(value or ""))


def _backend_dir() -> Path | None:
    env = os.environ.get("ARZXSH_BACKEND_DIR")
    if env:
        return Path(env)
    here = Path(__file__).resolve()
    # repo layout: cli/arzxsh_cli/main.py -> ../../ytmusic-backend
    cand = here.parents[2] / "ytmusic-backend"
    if (cand / "app.py").exists():
        return cand
    # installed layout: /usr/share/arzxsh/ytmusic-backend
    cand = Path("/usr/share/arzxsh/ytmusic-backend")
    if (cand / "app.py").exists():
        return cand
    return None


def _health_probe() -> tuple[dict | None, bool]:
    """(health JSON, did anything answer). Identity matters
    (audit 2026-11-11): any local service can squat the fixed port
    and answer 200, so 'alive' means 'identifies as arzxsh-ytm',
    and a foreign responder is reported, never trusted."""
    try:
        r = httpx.get(f"{BASE}/health", timeout=2.0, trust_env=False)
    except Exception:
        return None, False
    try:
        return r.json(), True
    except Exception:
        return None, True


def backend_alive() -> bool:
    payload, _ = _health_probe()
    return bool(payload and payload.get("app") == "arzxsh-ytm")


def _write_api_token() -> str:
    """Generate the per-run backend token, store it 0600 in the
    shared token file (the backend client and linux/run.sh use the
    same location), and return it for the spawned process env."""
    token = secrets.token_urlsafe(32)
    tf = token_file()
    tf.parent.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(tf.parent, 0o700)
    except OSError:
        pass
    fd = os.open(tf, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        fh.write(token)
    return token


def ensure_backend() -> bool:
    if backend_alive():
        return True
    _, answered = _health_probe()
    if answered:
        print("arzxsh: something that is not the arzxsh backend is already "
              "answering on 127.0.0.1:8077; refusing to trust it. Stop that "
              "service first.", file=sys.stderr)
        return False
    backend = _backend_dir()
    if backend is None:
        print("arzxsh: backend not found (set ARZXSH_BACKEND_DIR).", file=sys.stderr)
        return False
    # The venv lives in the user data dir, not next to the backend
    # sources: an installed backend lives in read-only /usr/share.
    # The sources themselves are only read (state lives elsewhere).
    data_dir = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share")) / "arzxsh"
    venv = data_dir / "backend-venv"
    uvicorn = venv / "bin" / "uvicorn"
    if not uvicorn.exists():
        print("arzxsh: first run, creating the backend environment…")
        venv.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
        # Pinned installs only (audit 2026-11-11): the backend's
        # requirements.txt carries exact versions; bare package
        # names here would float to whatever PyPI serves that day.
        subprocess.run([str(venv / "bin" / "pip"), "install", "-q",
                        "-r", str(backend / "requirements.txt")], check=True)
    log = Path(tempfile_log())
    token = _write_api_token()
    env = {**os.environ, "ARZXSH_API_TOKEN": token}
    with open(log, "ab") as fh:
        subprocess.Popen(
            [str(uvicorn), "app:app", "--app-dir", str(backend),
             "--host", "127.0.0.1", "--port", "8077"],
            stdout=fh, stderr=fh, start_new_session=True, env=env)
    for _ in range(60):
        if backend_alive():
            return True
        time.sleep(0.5)
    print(f"arzxsh: backend did not come up; see {log}", file=sys.stderr)
    return False


def tempfile_log() -> str:
    # Inside the per-user 0700 runtime dir (audit 2026-11-11), not a
    # fixed symlink-followable name in the shared temp dir.
    return str(runtime_dir() / "arzxsh-backend.log")


async def _search_first(query: str) -> dict | None:
    from .backend import Backend
    be = Backend()
    try:
        data = await be.search(query, "songs", 10)
        results = data.get("results", [])
        return results[0] if results else None
    finally:
        await be.close()


async def _search_all(query: str) -> list[dict]:
    from .backend import Backend
    be = Backend()
    try:
        data = await be.search(query, "songs", 15)
        return data.get("results", [])
    finally:
        await be.close()


def cmd_search(query: str) -> int:
    results = asyncio.run(_search_all(query))
    if not results:
        print("No results.")
        return 1
    for i, t in enumerate(results, 1):
        dur = t.get("duration_s") or 0
        print(f"{i:>2}. {_display(t.get('title',''))} — {_display(t.get('artist',''))} "
              f"({dur // 60}:{dur % 60:02d})")
    return 0


def cmd_play(query: str) -> int:
    track = asyncio.run(_search_first(query))
    if not track:
        print("No results.")
        return 1
    if not shutil.which("mpv"):
        print("mpv is not installed (pacman -S mpv).", file=sys.stderr)
        return 1
    from urllib.parse import quote
    url = f"{BASE}/audio/{quote(str(track['id']), safe='')}"
    title = _display(track.get('title', ''))
    print(f"▶ {title} — {_display(track.get('artist',''))}  (Ctrl+C to stop)")
    try:
        subprocess.run(["mpv", "--no-video", f"--title={title}", url])
    except KeyboardInterrupt:
        pass
    return 0


def cmd_download(query: str) -> int:
    from .backend import Backend
    from .store import Store

    async def run() -> tuple[dict, dict]:
        track = await _search_first(query)
        if not track:
            raise LookupError("no results")
        be = Backend()
        try:
            data = await be.download(track)
        finally:
            await be.close()
        Store().mark_downloaded(track["id"], data["track"]["id"])
        return data, track

    try:
        data, track = asyncio.run(run())
    except LookupError:
        print("No results.")
        return 1
    except Exception as e:
        print(f"Download failed: {e}", file=sys.stderr)
        return 1
    print(("Already saved: " if data.get("already") else "Saved for offline: ")
          + f"{_display(track.get('title',''))} — {_display(track.get('artist',''))}")
    return 0


def main(argv: list[str] | None = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv and argv[0] in ("play", "search", "download"):
        if len(argv) < 2:
            print(f"usage: arzxsh {argv[0]} <query>", file=sys.stderr)
            return 2
        if not ensure_backend():
            return 1
        query = " ".join(argv[1:])
        return {"play": cmd_play, "search": cmd_search,
                "download": cmd_download}[argv[0]](query)
    if argv and argv[0] in ("-h", "--help", "help"):
        print(__doc__)
        return 0
    if not ensure_backend():
        return 1
    from .app import run_tui
    run_tui()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
