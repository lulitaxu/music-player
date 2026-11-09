#!/usr/bin/env bash
# arzxsh for Linux — one-command local web app.
# Starts the catalog backend (FastAPI) which also serves the web
# player, then opens it in the default browser. Everything runs on
# this machine: the server binds to 127.0.0.1 only, because it has
# no accounts or auth and should never be reachable from the LAN.
set -e
cd "$(dirname "$0")"
BACKEND="$PWD/../ytmusic-backend"
PORT="${ARZXSH_PORT:-8077}"

if [ ! -x "$BACKEND/.venv/bin/uvicorn" ]; then
  echo "First run: creating the backend environment (one time)…"
  python3 -m venv "$BACKEND/.venv"
  "$BACKEND/.venv/bin/pip" install -q fastapi "uvicorn[standard]" ytmusicapi yt-dlp requests
fi

URL="http://127.0.0.1:$PORT"
echo "arzxsh for Linux → $URL  (Ctrl+C to stop)"
if command -v xdg-open >/dev/null 2>&1; then
  (sleep 1.5 && xdg-open "$URL" >/dev/null 2>&1) &
fi
exec "$BACKEND/.venv/bin/uvicorn" app:app --app-dir "$BACKEND" --host 127.0.0.1 --port "$PORT"
