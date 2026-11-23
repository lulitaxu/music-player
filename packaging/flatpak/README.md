# arzxsh on Flathub (Flatpak)

Goal: arzxsh discoverable and installable from KDE Discover / GNOME
Software on Arch and every other distro, via Flathub.

## What ships in the Flatpak

The desktop player: the FastAPI catalog backend
(`ytmusic-backend/app.py`), the web player (`linux/app/`), and a small
GTK4 + WebKitGTK window (`linux/shell/arzxsh_shell.py`) that starts the
backend on 127.0.0.1:8077 with a per-run token and shows the player.
All Python libraries are bundled at build time by
`python3-backend-deps.json` (generated from
`ytmusic-backend/requirements.txt` with flatpak-pip-generator). Nothing
is pip-installed and no venv is created at runtime; that is a hard
Flathub rule and the main difference from the Arch PKGBUILD route.

The terminal TUI is NOT in the Flatpak for now: it plays through mpv,
and bundling mpv means building a whole ffmpeg/video stack inside the
sandbox for a terminal extra. The TUI stays available through the Arch
package in `packaging/PKGBUILD`, where mpv is a normal pacman
dependency. Adding an mpv module is a possible phase 2.

## Permissions (finish-args) and why

- network: the backend resolves and streams the catalog
- pulseaudio, wayland, fallback-x11, ipc, dri: the WebKit window, its
  audio output and GPU-accelerated animation
- xdg-music (read-write): the local library scan and the downloads
  folder (`~/Music/arzxsh`). Nothing else in the home directory is
  visible to the app.

## Customization

In-app Settings cover accent (artwork-derived, arzxsh red, or a custom
color), visualizer style (bars / wave / off) and motion level. For
deeper theming, the desktop shell hands the player a user stylesheet:
put CSS in `~/.config/arzxsh/user.css` (inside the Flatpak sandbox
that is `~/.var/app/me.arafatarzu.arzxsh/config/arzxsh/user.css`) and
it is applied on top of the app's own styles at launch, Spicetify-style.

## Local test build (on Arch)

```bash
sudo pacman -S flatpak flatpak-builder
flatpak remote-add --if-not-exists flathub https://dl.flathub.org/repo/flathub.flatpakrepo
flatpak install flathub org.gnome.Platform//47 org.gnome.Sdk//47
flatpak-builder --user --install --force-clean build-dir packaging/flatpak/me.arafatarzu.arzxsh.yml
flatpak run me.arafatarzu.arzxsh
```

## Regenerating the Python deps module

```bash
python3 /path/to/flatpak-pip-generator.py \
  --requirements-file=ytmusic-backend/requirements.txt \
  --output packaging/flatpak/python3-backend-deps
```

(flatpak-pip-generator.py comes from the flatpak/flatpak-builder-tools
repo, pip/ folder.)

Note: the committed `python3-backend-deps.json` has one hand-applied
fix on top of the generator output. The generator emits source tarballs
for the six packages with compiled code (pydantic-core, uvloop,
httptools, watchfiles, PyYAML, numpy); those were swapped for their official
per-arch cp312 manylinux wheels (x86_64 + aarch64, `only-arches`), so
the Flathub build needs no Rust or C compilation at all. If you
regenerate the file, re-apply that swap.

## Staying up to date

A local build never updates itself. Two ways to keep it current:

- By hand, one command: `./packaging/flatpak/update-local.sh`
  (pulls the repo, rebuilds, reinstalls; the build cache is reused,
  so repeat runs are fast).
- Automatically: copy `arzxsh-update.service` and
  `arzxsh-update.timer` to `~/.config/systemd/user/`, then
  `systemctl --user enable --now arzxsh-update.timer`. It rebuilds
  weekly and catches up after the machine was off. The service
  assumes the clone lives at `~/music-player`; edit its ExecStart
  path if yours is elsewhere.

Once arzxsh is on Flathub this stops mattering: `flatpak update`
(and Discover's auto-update) ships new releases like any other app.

## Before submitting to Flathub

1. Test build above passes and the player actually plays, shows synced
   lyrics and downloads a track.
2. Capture 2-3 real screenshots of the app (Home, Now Playing with
   lyrics) and add them to the metainfo `<screenshots>` section with
   hosted URLs.
3. Run flatpak-builder-lint on the manifest and the built repo.
4. Submission is a PR to github.com/flathub/flathub with this manifest;
   in the submission repo the app module's `type: dir` source is
   replaced by a `type: git` source pointing at the music-player repo
   and the release tag. Expect a review round or two.

App ID: `me.arafatarzu.arzxsh` (from arzxsh.arafatarzu.me).
Runtime: org.gnome.Platform 47 (GTK4 + WebKitGTK 6 + Python 3.12, which
matches the pinned wheels in the backend requirements).
