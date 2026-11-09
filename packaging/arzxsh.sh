#!/usr/bin/env bash
# arzxsh — system launcher (installed as /usr/bin/arzxsh).
# Sources live read-only in /usr/share/arzxsh; the Python library
# venv is per-user under ~/.local/share/arzxsh and is created on
# first run. Requires python3 and mpv (pacman dependencies).
set -e
SHARE=/usr/share/arzxsh
DATA="${XDG_DATA_HOME:-$HOME/.local/share}/arzxsh"
VENV="$DATA/venv"
if [ ! -x "$VENV/bin/python" ]; then
  echo "arzxsh: first run, creating the CLI environment…"
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q -r "$SHARE/cli/requirements.txt"
fi
export PYTHONPATH="$SHARE/cli"
export ARZXSH_BACKEND_DIR="$SHARE/ytmusic-backend"
exec "$VENV/bin/python" -m arzxsh_cli.main "$@"
