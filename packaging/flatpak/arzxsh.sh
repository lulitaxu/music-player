#!/bin/sh
for d in /app/lib/python3*/site-packages; do
  if [ -d "$d" ]; then
    PYTHONPATH="$d${PYTHONPATH:+:$PYTHONPATH}"
  fi
done
export PYTHONPATH
export GDK_BACKEND="${GDK_BACKEND:-x11}"
exec python3 /app/share/arzxsh/shell/arzxsh_shell.py "$@"
