"""Async client for the arzxsh ytmusic-backend (same server the
Linux web player uses). The CLI is a front-end only: catalog,
stream resolution, local library and downloads all live in the
backend, so every client stays in sync."""
from __future__ import annotations

from urllib.parse import quote

import httpx

from .paths import load_api_token

BASE = "http://127.0.0.1:8077"


def _q(value) -> str:
    """Quote a record id for use as a single URL path segment
    (audit 2026-11-11): a '/', '..', '?' or '#' in an id must never
    rewrite which localhost endpoint a request hits."""
    return quote(str(value), safe="")


class Backend:
    def __init__(self, base: str = BASE):
        self.base = base.rstrip("/")
        # trust_env=False: the backend is localhost-only; it must
        # never be routed through an HTTP(S) proxy from the env.
        # The per-run token (when the launcher configured one) rides
        # on every call as a default header.
        token = load_api_token()
        headers = {"X-Arzxsh-Token": token} if token else None
        self._client = httpx.AsyncClient(base_url=self.base, timeout=30.0,
                                         trust_env=False, headers=headers)

    async def close(self):
        await self._client.aclose()

    async def _get(self, path: str, **params):
        r = await self._client.get(path, params=params or None)
        r.raise_for_status()
        return r.json()

    async def health(self) -> bool:
        """True only if the responder identifies as the arzxsh
        backend (audit 2026-11-11): any service can answer 200 on
        the fixed port, so the identity field is what is trusted,
        not the status code alone."""
        try:
            r = await self._client.get("/health", timeout=3.0)
            if r.status_code != 200:
                return False
            return r.json().get("app") == "arzxsh-ytm"
        except Exception:
            return False

    async def charts(self) -> dict:
        return await self._get("/charts")

    async def search(self, q: str, filter: str = "songs", limit: int = 25) -> dict:
        return await self._get("/search", q=q, filter=filter, limit=limit)

    async def artist(self, channel_id: str) -> dict:
        return await self._get(f"/artist/{_q(channel_id)}")

    async def album(self, browse_id: str) -> dict:
        return await self._get(f"/album/{_q(browse_id)}")

    async def radio(self, video_id: str) -> list[dict]:
        data = await self._get("/radio", video_id=video_id)
        return data.get("results", [])

    async def playlist(self, playlist_id: str) -> dict:
        return await self._get(f"/playlist/{_q(playlist_id)}")

    async def backend_lyrics(self, video_id: str) -> dict:
        return await self._get(f"/lyrics/{_q(video_id)}")

    async def delete_local(self, track_id: str) -> dict:
        r = await self._client.delete(f"/local/file/{_q(track_id)}")
        r.raise_for_status()
        return r.json()

    async def local_list(self, fresh: bool = False) -> dict:
        return await self._get("/local", fresh=1 if fresh else 0)

    async def download(self, track: dict) -> dict:
        r = await self._client.post(
            f"/download/{_q(track['id'])}",
            params={"title": track.get("title", ""), "artist": track.get("artist", "")},
            timeout=300.0,
        )
        r.raise_for_status()
        return r.json()

    def stream_url(self, track: dict) -> str:
        """What mpv should play for this track."""
        if track.get("source") == "local":
            return f"{self.base}/local/file/{_q(track['id'])}"
        return f"{self.base}/audio/{_q(track['id'])}"

    def artwork_url(self, track_or_artist: dict) -> str | None:
        art = track_or_artist.get("artwork")
        if not art:
            return None
        if art.startswith("http"):
            return art
        return f"{self.base}{art}"  # local art is a backend path

    async def fetch_bytes(self, url: str, max_bytes: int = 10 * 1024 * 1024) -> bytes:
        """Fetch artwork bytes with a hard size cap and an image
        content-type check (audit 2026-11-11): the URL comes from
        catalog metadata, so the response is untrusted — stream it,
        abort past the cap, and refuse non-image payloads before
        anything reaches the Pillow decoder."""
        async with self._client.stream("GET", url, timeout=20.0) as r:
            r.raise_for_status()
            ctype = r.headers.get("content-type", "").split(";")[0].strip().lower()
            if ctype and not (ctype.startswith("image/")
                              or ctype == "application/octet-stream"):
                raise ValueError(f"artwork response is not an image: {ctype}")
            chunks: list[bytes] = []
            total = 0
            async for chunk in r.aiter_bytes():
                total += len(chunk)
                if total > max_bytes:
                    raise ValueError("artwork response exceeds size cap")
                chunks.append(chunk)
            return b"".join(chunks)
