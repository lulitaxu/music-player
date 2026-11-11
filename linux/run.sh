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
  "$BACKEND/.venv/bin/pip" install -q -r "$BACKEND/requirements.txt"
fi

# Per-run request token (audit 2026-11-11): the backend requires it
# on mutating calls and /local enumeration, which is what stops a
# website the user happens to visit from driving this server. The
# web UI fetches it from the same-origin /ui-config endpoint; the
# CLI reads the token file below. The file is 0600 in a 0700 dir.
TOKEN_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/arzxsh"
mkdir -p "$TOKEN_DIR"
chmod 700 "$TOKEN_DIR"
ARZXSH_API_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
(umask 077 && printf '%s' "$ARZXSH_API_TOKEN" > "$TOKEN_DIR/api-token")
export ARZXSH_API_TOKEN

URL="http://127.0.0.1:$PORT"
echo "arzxsh for Linux → $URL  (Ctrl+C to stop)"
if command -v xdg-open >/dev/null 2>&1; then
  (sleep 1.5 && xdg-open "$URL" >/dev/null 2>&1) &
fi
exec "$BACKEND/.venv/bin/uvicorn" app:app --app-dir "$BACKEND" --host 127.0.0.1 --port "$PORT"
