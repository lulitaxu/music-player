"""Album cover as terminal art.

The real artwork is downscaled with Pillow and rendered as
truecolor half-blocks: each character cell is a ▀ whose
foreground is the top pixel and background is the bottom pixel,
so a cols x rows render carries cols x (rows*2) pixels of the
actual cover. Works in every terminal; no graphics protocol
needed. Rendered output is a Rich Text the TUI drops into the
Now Playing pane and page headers.
"""
from __future__ import annotations

import io

from PIL import Image
from rich.style import Style
from rich.text import Text

# Decompression-bomb bound (audit 2026-11-11): artwork is decoded
# from catalog-supplied URLs, so cap the pixel count Pillow will
# expand before the downscale ever happens (~20MP is far beyond any
# real cover art).
Image.MAX_IMAGE_PIXELS = 20_000_000

_cache: dict[tuple[str, int, int], Text] = {}


def render_art(image_bytes: bytes, cols: int = 34, rows: int = 17) -> Text:
    img = Image.open(io.BytesIO(image_bytes)).convert("RGB")
    img = img.resize((cols, rows * 2), Image.LANCZOS)
    px = img.load()
    text = Text()
    for y in range(rows):
        for x in range(cols):
            top = px[x, y * 2]
            bottom = px[x, y * 2 + 1]
            style = Style(
                color="#%02x%02x%02x" % top,
                bgcolor="#%02x%02x%02x" % bottom,
            )
            text.append("▀", style=style)
        if y < rows - 1:
            text.append("\n")
    return text


async def art_for(backend, item: dict, cols: int = 34, rows: int = 17) -> Text | None:
    url = backend.artwork_url(item)
    if not url:
        return None
    key = (url, cols, rows)
    if key in _cache:
        return _cache[key]
    try:
        data = await backend.fetch_bytes(url)
        text = render_art(data, cols, rows)
    except Exception:
        return None
    _cache[key] = text
    return text
