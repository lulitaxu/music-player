"""Async client for the arzxsh ytmusic-backend (same server the
Linux web player uses). The CLI is a front-end only: catalog,
stream resolution, local library and downloads all live in the
backend, so every client stays in sync."""
from __future__ import annotations

import httpx

BASE = "http://127.0.0.1:8077"


class Backend:
    def __init__(self, base: str = BASE):
        self.base = base.rstrip("/")
        # trust_env=False: the backend is localhost-only; it must
        # never be routed through an HTTP(S) proxy from the env.
        self._client = httpx.AsyncClient(base_url=self.base, timeout=30.0, trust_env=False)

    async def close(self):
        await self._client.aclose()

    async def _get(self, path: str, **params):
        r = await self._client.get(path, params=params or None)
        r.raise_for_status()
        return r.json()

    async def health(self) -> bool:
        try:
            r = await self._client.get("/health", timeout=3.0)
            return r.status_code == 200
        except Exception:
            return False

    async def charts(self) -> dict:
        return await self._get("/charts")

    async def search(self, q: str, filter: str = "songs", limit: int = 25) -> dict:
        return await self._get("/search", q=q, filter=filter, limit=limit)

    async def artist(self, channel_id: str) -> dict:
        return await self._get(f"/artist/{channel_id}")

    async def album(self, browse_id: str) -> dict:
        return await self._get(f"/album/{browse_id}")

    async def radio(self, video_id: str) -> list[dict]:
        data = await self._get("/radio", video_id=video_id)
        return data.get("results", [])

    async def playlist(self, playlist_id: str) -> dict:
        return await self._get(f"/playlist/{playlist_id}")

    async def backend_lyrics(self, video_id: str) -> dict:
        return await self._get(f"/lyrics/{video_id}")

    async def local_list(self, fresh: bool = False) -> dict:
        return await self._get("/local", fresh=1 if fresh else 0)

    async def download(self, track: dict) -> dict:
        r = await self._client.post(
            f"/download/{track['id']}",
            params={"title": track.get("title", ""), "artist": track.get("artist", "")},
            timeout=300.0,
        )
        r.raise_for_status()
        return r.json()

    def stream_url(self, track: dict) -> str:
        """What mpv should play for this track."""
        if track.get("source") == "local":
            return f"{self.base}/local/file/{track['id']}"
        return f"{self.base}/audio/{track['id']}"

    def artwork_url(self, track_or_artist: dict) -> str | None:
        art = track_or_artist.get("artwork")
        if not art:
            return None
        if art.startswith("http"):
            return art
        return f"{self.base}{art}"  # local art is a backend path

    async def fetch_bytes(self, url: str) -> bytes:
        r = await self._client.get(url, timeout=20.0)
        r.raise_for_status()
        return r.content
