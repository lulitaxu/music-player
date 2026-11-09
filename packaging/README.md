# Packaging arzxsh for Arch

`PKGBUILD` + `arzxsh.sh` (the /usr/bin launcher) in this folder
build a pacman package named **arzxsh**:

```bash
makepkg -si        # builds from the GitHub repo and installs
arzxsh             # run it from anywhere
pacman -R arzxsh   # remove it cleanly
```

What the package does:

- depends: `python`, `mpv` (pacman resolves these)
- optdepends: `cava` (visualizer bars), `mpv-mpris` (media keys)
- installs sources to /usr/share/arzxsh (cli, ytmusic-backend,
  the web player's static app) and the launcher to /usr/bin/arzxsh
- Python libraries are not official-repo packages as a set, so
  the launcher creates per-user venvs under ~/.local/share/arzxsh
  on first run instead of pretending they are pacman deps

Routes to a literal repo install:

- **AUR**: submit this PKGBUILD + arzxsh.sh to aur.archlinux.org
  (needs an AUR account); then it is `yay -S arzxsh`.
- **Custom repo**: build the package, sign it, host the .pkg.tar.zst
  plus a repo database (`repo-add`) somewhere (GitHub Releases
  works), add a `[arzxsh]` section to pacman.conf once, and
  `sudo pacman -S arzxsh` works from then on, updates included.
