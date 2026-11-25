#!/bin/sh
set -e
cd "$(dirname "$0")/../.."
git pull --ff-only
flatpak-builder --user --install --force-clean build-dir packaging/flatpak/me.arafatarzu.arzxsh.yml
