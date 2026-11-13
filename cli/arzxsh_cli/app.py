"""arzxsh CLI — a full Textual TUI over the ytmusic-backend.

Mouse and keyboard are both first class: every row and button is
clickable, and the whole app is driven by keys (arrows/jk move,
Enter plays, space pauses, n/p skip, l likes, d downloads,
a adds to a playlist, / searches). The Now Playing strip shows
the cover as truecolor block art (art.py) and, when cava is
installed, live frequency bars.
"""
from __future__ import annotations

import asyncio
import os
import time
import re
import shutil
import subprocess
import threading
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from rich.markup import escape as _markup_escape
from rich.text import Text
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.theme import Theme
from textual.widgets import (Button, Input, Label, ListItem, ListView,
                             ProgressBar, Static, Switch)

from .art import art_for
from .backend import Backend
from .engine import Engine
from .lyrics import LyricsFetcher, active_index
from .paths import runtime_dir
from .store import EQ_FREQS, Store


def esc(value) -> str:
    """Escape catalog metadata for Rich-markup contexts
    (audit 2026-11-11): titles, artist names, descriptions and
    lyrics are attacker-shaped strings, and any of them containing
    '[...]' would otherwise be parsed as markup by Static/update/
    notify. Paths built on rich Text objects never needed this."""
    return _markup_escape(str(value or ""))

# Accent presets, mirroring the Android app's theme-accent
# concept: one accent drives the whole TUI (active tab, playing
# marker, progress, selection, buttons) through Textual theme
# variables, so switching recolors live, no restart. Spotify
# green is the default; the old CLI red survives as a preset.
ACCENTS = {
    "green": "#1DB954",
    "red": "#fc3c44",
    "blue": "#3b82f6",
    "violet": "#8b5cf6",
    "amber": "#f59e0b",
    "teal": "#14b8a6",
}
ACCENT_ORDER = list(ACCENTS)

# The one track-row grid, shared by every list (search, playlist
# detail, Liked Songs, queue, home shelves): status marker,
# flexible title, fixed artist, fixed badges, right-aligned
# fixed duration. Widths are constants — columns cannot drift
# with title length.
COL_MARKER_W = 2
COL_ARTIST_W = 26
COL_BADGES_W = 9
COL_DUR_W = 6


def fmt(sec) -> str:
    if not sec:
        return "0:00"
    sec = int(sec)
    return f"{sec // 60}:{sec % 60:02d}"


def greeting() -> tuple[str, str]:
    import datetime
    h = datetime.datetime.now().hour
    if h >= 22 or h < 4:
        return "Good night", "The late queue is yours."
    if h < 12:
        return "Good morning", "Start the day loud."
    if h < 17:
        return "Good afternoon", "The midday queue."
    return "Good evening", "Tonight's rotation."


class TrackItem(ListItem):
    """One playable row. Carries its track + the list it belongs
    to, so activation can hand the whole context to the engine.

    The row is the shared fixed grid (see COL_* above): marker,
    title (flex, ellipsis-truncated), artist (fixed width,
    truncated), badges, duration (fixed width, right-aligned) —
    built once here so every list in the app aligns identically.
    """

    def __init__(self, app_ref, track: dict, context: list[dict], context_name: str):
        self.app_ref = app_ref
        self.track = track
        self.context = context
        self.context_name = context_name
        store = app_ref.store
        accent = app_ref.accent_hex
        badges = []
        if store.is_liked(track["id"]):
            badges.append("♥")
        if store.setting("download_badges") \
                and track.get("source") != "local" \
                and track["id"] in store.data["downloads"]:
            badges.append("✓")
        if store.setting("lyrics_badges") \
                and app_ref.lyrics_fetcher.has_lyrics(track["id"]):
            badges.append("♪")
        if track.get("source") == "local":
            badges.append("local")
        playing = app_ref.engine and app_ref.engine.current \
            and app_ref.engine.current["id"] == track["id"]
        sub = track.get("artist", "")
        if track.get("album"):
            sub += f" · {track['album']}"
        row = Horizontal(
            Static(
                Text("▶" if playing else "", style=f"bold {accent}"),
                classes="col-marker",
            ),
            Static(
                Text(
                    track.get("title", ""),
                    style=f"bold {accent}" if playing else "bold",
                    no_wrap=True, overflow="ellipsis",
                ),
                classes="col-title",
            ),
            Static(
                Text(sub, style="dim", no_wrap=True, overflow="ellipsis"),
                classes="col-artist",
            ),
            Static(
                Text(" ".join(badges), style=accent),
                classes="col-badges",
            ),
            Static(
                Text(fmt(track.get("duration_s"))),
                classes="col-duration",
            ),
            classes="track-row",
        )
        super().__init__(row)


class LinkItem(ListItem):
    """A navigable row (artist, album, library entry, playlist)."""

    def __init__(self, label: str, action: str, payload=None, sub: str = ""):
        self.action = action
        self.payload = payload
        text = Text()
        text.append(label, style="bold")
        if sub:
            text.append(f"   {sub}", style="dim")
        super().__init__(Static(text))


class ArzxshApp(App):
    ALLOW_SELECT = False

    CSS = """
    Screen { background: $background; }
    #topbar { height: 3; background: $surface; padding: 0 1; }
    #topbar .wordmark { width: auto; text-style: bold; padding: 1 2 0 0; }
    .tab { margin: 0 1; min-width: 10; }
    .tab.active { background: $accent; color: #ffffff; }
    #middle { height: 1fr; }
    #content { width: 1fr; padding: 0 1; }
    #queue-panel { width: 42; background: $surface; border-left: solid $panel; padding: 0 1; display: none; }
    #queue-panel.open { display: block; }
    #playerbar { height: 6; background: $surface; border-top: solid $panel; padding: 0 1; }
    #np-title { width: 1fr; }
    #np-times { width: auto; color: #8a8a8a; }
    #progressbar { width: 1fr; margin: 0 1; }
    #controls { height: 3; align: center middle; }
    #controls Button { min-width: 7; margin: 0 1; }
    #controls Button.on { background: $accent; color: white; }
    #viz { width: 30; color: $accent; }
    .section { text-style: bold; margin: 1 0 0 0; color: #ffffff; }
    .dim { color: #8a8a8a; }
    ListView { background: transparent; }
    ListView > ListItem { padding: 0; }
    ListView > ListItem.--highlight { background: $accent-darken-3; }
    .track-row { height: 1; width: 1fr; }
    .col-marker { width: 2; }
    .col-title { width: 1fr; }
    .col-artist { width: 26; color: #8a8a8a; }
    .col-badges { width: 9; }
    .col-duration { width: 6; text-align: right; color: #8a8a8a; }
    .setting-row { height: 3; }
    .setting-row Button { min-width: 5; margin: 0 1; }
    .setting-name { width: 26; padding: 1 0; }
    .setting-value { width: 10; padding: 1 0; text-align: center; }
    #helpbar { height: 1; background: $surface; color: #6a6a6a; padding: 0 1; }
    Input { margin: 0 0 1 0; }
    """

    BINDINGS = [
        Binding("space", "toggle_play", "Play/Pause", show=False),
        Binding("n", "next", "Next", show=False),
        Binding("p", "prev", "Prev", show=False),
        Binding("right", "seek_fwd", "Seek +10", show=False),
        Binding("left", "seek_back", "Seek -10", show=False),
        Binding("plus", "vol_up", "Vol+", show=False),
        Binding("minus", "vol_down", "Vol-", show=False),
        Binding("s", "shuffle", "Shuffle", show=False),
        Binding("r", "repeat", "Repeat", show=False),
        Binding("l", "like", "Like", show=False),
        Binding("d", "download_focused", "Download", show=False),
        Binding("a", "add_to_playlist", "Add to playlist", show=False),
        Binding("x", "remove_from_playlist", "Remove from playlist", show=False),
        Binding("y", "lyrics", "Lyrics", show=False),
        Binding("ctrl+y", "lyrics", "Lyrics", show=False),
        Binding("q", "queue_panel", "Queue", show=False),
        Binding("slash", "focus_search", "Search", show=False),
        Binding("1", "tab('home')", "Home", show=False),
        Binding("2", "tab('search')", "Search", show=False),
        Binding("3", "tab('library')", "Library", show=False),
        Binding("4", "tab('playlists')", "Playlists", show=False),
        Binding("5", "tab('settings')", "Settings", show=False),
    ]

    def __init__(self):
        super().__init__()
        self.store = Store()
        self.backend = Backend()
        self.lyrics_fetcher = LyricsFetcher(self.backend)
        # One registered theme per accent preset: the CSS speaks
        # only in theme variables ($accent, $background, $surface,
        # $panel), so switching the theme recolors the whole TUI
        # live. Background stays in the near-black #121212 family
        # for every preset, like the Android app.
        for name, hexv in ACCENTS.items():
            self.register_theme(Theme(
                name=f"arzxsh-{name}",
                primary=hexv,
                accent=hexv,
                background="#121212",
                surface="#1a1a1a",
                panel="#232323",
                foreground="#f5f5f5",
                dark=True,
            ))
        saved_accent = self.store.setting("accent")
        self.theme = f"arzxsh-{saved_accent if saved_accent in ACCENTS else 'green'}"
        self.engine: Engine | None = None
        self.engine_error: str | None = None
        self._view = ("home", {})
        self._lyrics_track_id: str | None = None
        self._lyrics_data: dict | None = None
        self._lyrics_idx: int | None = None
        self._bulk_dl = False
        self._pending_local_delete: tuple[str, float] | None = None
        self._viz_bars = ""
        self._cava_proc: subprocess.Popen | None = None

    @property
    def accent_hex(self) -> str:
        """The current accent preset's color, for the places
        that style Rich text inline (row titles, the active
        lyrics line) rather than through CSS variables."""
        return ACCENTS.get(self.store.setting("accent"), ACCENTS["green"])

    # -- layout -------------------------------------------------------
    def compose(self) -> ComposeResult:
        with Horizontal(id="topbar"):
            yield Static("arzxsh.", classes="wordmark")
            for name in ("home", "search", "library", "playlists", "settings"):
                yield Button(name.title(), id=f"tab-{name}", classes="tab" + (" active" if name == "home" else ""))
            yield Static("", id="top-right")
        with Horizontal(id="middle"):
            yield VerticalScroll(id="content")
            with Vertical(id="queue-panel"):
                yield Static("Queue", classes="section")
                yield ListView(id="queue-list")
        with Vertical(id="playerbar"):
            with Horizontal():
                yield Static("Nothing playing", id="np-title")
                yield Static("", id="viz")
                yield Static("", id="np-times")
            yield ProgressBar(total=1000, show_eta=False, show_percentage=False, id="progressbar")
            with Horizontal(id="controls"):
                yield Button("⏮", id="btn-prev")
                yield Button("▶", id="btn-play")
                yield Button("⏭", id="btn-next")
                yield Button("⤨", id="btn-shuffle")
                yield Button("↻", id="btn-repeat")
                yield Button("♡", id="btn-like")
                yield Button("⬇", id="btn-download")
                yield Button("Lyrics", id="btn-lyrics")
                yield Button("Queue", id="btn-queue")
                yield Static("", id="vol-label")
        yield Static("↑↓/jk move · Enter play · space pause · n/p skip · ←/→ seek · l like · d download · a add to playlist · y lyrics · q queue · / search", id="helpbar")

    async def on_mount(self):
        self._start_cava()
        try:
            self.engine = Engine(
                self.backend, self.store,
                on_track_changed=self._engine_track_changed,
                on_error=lambda m: self.notify(m, severity="error"),
            )
            await self.engine.start()
        except Exception as e:
            self.engine_error = str(e)
            self.engine = None
            self.notify(f"Playback unavailable: {esc(e)}", severity="error", timeout=8)
        await self.show_home()
        self.set_interval(0.3, self._poll)

    async def on_unmount(self):
        if self.engine:
            await self.engine.shutdown()
        if self._cava_proc:
            self._cava_proc.terminate()

    # -- engine callbacks ------------------------------------------------
    def _engine_track_changed(self, track: dict):
        # The engine can report a track change while the app is on
        # its way out (mpv fires end-file during quit). Workers
        # cannot start then: run_worker raises "App is not running"
        # and the orphaned coroutine logs a never-awaited warning.
        if not self.is_running:
            return
        self._lyrics_track_id = None
        self._lyrics_data = None
        self._lyrics_idx = None
        self._refresh_queue_panel()
        coro = self._update_np_art(track)
        try:
            self.run_worker(coro, exclusive=False)
        except RuntimeError:
            coro.close()
        self._refresh_current_view_light()

    async def _update_np_art(self, track: dict):
        text = await art_for(self.backend, track, cols=16, rows=4)
        # The player bar has no room for a big render; the art is
        # shown in the Lyrics / Now Playing view. Here we only
        # refresh the title line.
        title = self.query_one("#np-title", Static)
        label = Text()
        label.append(track.get("title", ""), style="bold")
        label.append(f"  ·  {track.get('artist', '')}", style="dim")
        title.update(label)

    # -- generic view plumbing --------------------------------------------
    async def _set_content(self, widgets: list, focus=None):
        content = self.query_one("#content", VerticalScroll)
        await content.remove_children()
        for w in widgets:
            await content.mount(w)
        if focus is not None:
            focus.focus()

    def _mark_tab(self, name: str):
        for tab in ("home", "search", "library", "playlists", "settings"):
            self.query_one(f"#tab-{tab}", Button).set_class(tab == name, "active")

    def _error_widgets(self, msg: str):
        return [Static(f"[red]{msg}[/red]  (press the tab again to retry)", classes="section")]

    def _track_list(self, tracks, context_name: str) -> ListView:
        lv = ListView(*[TrackItem(self, t, tracks, context_name) for t in tracks])
        lv.data_context = (tracks, context_name)  # type: ignore[attr-defined]
        return lv

    def _refresh_current_view_light(self):
        # Re-mark the playing row without a full rebuild: cheapest
        # correct approach is re-running the current view renderer.
        name, kw = self._view
        if name in ("home", "liked", "local", "playlist_detail", "album", "artist"):
            coro = self._rerun_view()
            try:
                self.run_worker(coro, exclusive=True, group="rerun")
            except RuntimeError:
                coro.close()

    async def _rerun_view(self):
        name, kw = self._view
        fn = {"home": self.show_home, "liked": self.show_liked, "local": self.show_local,
              "album": self.show_album, "artist": self.show_artist,
              "playlist_detail": self.show_playlist_detail}.get(name)
        if fn:
            await fn(**kw, _rerun=True)

    # -- views --------------------------------------------------------------
    async def show_home(self, _rerun: bool = False):
        self._view = ("home", {})
        self._mark_tab("home")

        def header():
            g, sub = greeting()
            return [Static(f"[bold]{g}[/bold]"), Static(sub, classes="dim")]

        widgets = header()
        recent = self.store.data["recent"]
        widgets.append(Static("Trending now", classes="section"))
        widgets.append(Static("Loading…", id="home-loading"))
        await self._set_content(widgets)
        try:
            charts = await self.backend.charts()
        except Exception:
            if self._view[0] == "home":
                await self._set_content(self._error_widgets("Couldn't load the catalog. Is the backend running?"))
            return
        if self._view[0] != "home":
            return
        # Fresh widget instances for the real render: re-mounting
        # instances removed by _set_content is not reliable.
        widgets = header()
        if recent:
            widgets.append(Static("Jump back in", classes="section"))
            widgets.append(self._track_list(recent[:8], "Jump back in"))
        widgets.append(Static("Trending now", classes="section"))
        widgets.append(self._track_list(charts.get("songs", [])[:20], "Trending now"))
        if charts.get("artists"):
            widgets.append(Static("Top artists", classes="section"))
            widgets.append(ListView(*[
                LinkItem(a.get("name", ""), "artist", a.get("id"), "Artist")
                for a in charts["artists"]]))
        await self._set_content(widgets)

    async def show_search(self):
        self._view = ("search", {})
        self._mark_tab("search")
        inp = Input(placeholder="Artists, Songs, Lyrics and More", id="search-input")
        widgets = [inp]
        history = self.store.data["history"]
        if history:
            widgets.append(Static("Recent searches", classes="section"))
            widgets.append(ListView(*[LinkItem(q, "history", q) for q in history], id="history-list"))
        widgets.append(Vertical(id="search-results"))
        await self._set_content(widgets, focus=inp)

    async def _run_search(self, q: str):
        q = q.strip()
        if not q:
            return
        self.store.add_history(q)
        box = self.query_one("#search-results", Vertical)
        await box.remove_children()
        await box.mount(Static("Searching…"))
        try:
            songs, artists, albums = await asyncio.gather(
                self.backend.search(q, "songs", 25),
                self.backend.search(q, "artists", 10),
                self.backend.search(q, "albums", 10),
            )
        except Exception:
            await box.remove_children()
            await box.mount(Static("[red]Search failed. Check your connection.[/red]"))
            return
        await box.remove_children()
        if artists.get("results"):
            await box.mount(Static("Artists", classes="section"))
            await box.mount(ListView(*[LinkItem(a.get("name", ""), "artist", a.get("id"), "Artist") for a in artists["results"]]))
        await box.mount(Static("Songs", classes="section"))
        results = songs.get("results", [])
        if results:
            await box.mount(self._track_list(results, "Search"))
        else:
            await box.mount(Static(f"No songs found for “{esc(q)}”.", classes="dim"))
        if albums.get("results"):
            await box.mount(Static("Albums", classes="section"))
            await box.mount(ListView(*[LinkItem(a.get("title", ""), "album", a.get("id"), f"{a.get('artist','')} {a.get('year','')}") for a in albums["results"]]))

    async def show_artist(self, artist_id: str, _rerun: bool = False):
        self._view = ("artist", {"artist_id": artist_id})
        try:
            a = await self.backend.artist(artist_id)
        except Exception:
            await self._set_content(self._error_widgets("Couldn't load this artist."))
            return
        widgets = []
        art = await art_for(self.backend, a, cols=28, rows=9)
        if art:
            widgets.append(Static(art))
        widgets.append(Static(f"[bold]{esc(a.get('name',''))}[/bold]"))
        if a.get("subscribers"):
            widgets.append(Static(f"{esc(a['subscribers'])} subscribers", classes="dim"))
        if a.get("description"):
            widgets.append(Static("About", classes="section"))
            widgets.append(Static(esc(a["description"])))
        if a.get("songs"):
            widgets.append(Static("Top songs", classes="section"))
            widgets.append(self._track_list(a["songs"], a.get("name", "Artist")))
        for key, title in (("albums", "Albums"), ("singles", "Singles")):
            if a.get(key):
                widgets.append(Static(title, classes="section"))
                widgets.append(ListView(*[LinkItem(x.get("title", ""), "album", x.get("id"), x.get("year", "")) for x in a[key]]))
        if a.get("related"):
            widgets.append(Static("Fans also like", classes="section"))
            widgets.append(ListView(*[LinkItem(x.get("name", ""), "artist", x.get("id"), "Artist") for x in a["related"]]))
        await self._set_content(widgets)

    async def show_album(self, album_id: str, _rerun: bool = False):
        self._view = ("album", {"album_id": album_id})
        try:
            a = await self.backend.album(album_id)
        except Exception:
            await self._set_content(self._error_widgets("Couldn't load this album."))
            return
        widgets = []
        art = await art_for(self.backend, a, cols=28, rows=9)
        if art:
            widgets.append(Static(art))
        widgets.append(Static(f"[bold]{esc(a.get('title',''))}[/bold]"))
        widgets.append(Static(f"{esc(a.get('artist',''))} · {esc(a.get('year',''))} · {len(a.get('tracks', []))} songs", classes="dim"))
        widgets.append(Static("Songs", classes="section"))
        widgets.append(self._track_list(a.get("tracks", []), a.get("title", "Album")))
        await self._set_content(widgets)

    # -- library / local / liked --------------------------------------
    async def show_library(self):
        self._view = ("library", {})
        self._mark_tab("library")
        likes = self.store.data["likes"]
        widgets = [
            Static("Library", classes="section"),
            ListView(
                LinkItem("♥ Liked Songs", "liked", None, f"{len(likes)} songs"),
                LinkItem("On this device", "local", None, "Local files and downloads"),
            ),
            Static("Recently played", classes="section"),
        ]
        recent = self.store.data["recent"]
        if recent:
            widgets.append(self._track_list(recent, "Recently played"))
        else:
            widgets.append(Static("Tracks you play will appear here.", classes="dim"))
        await self._set_content(widgets)

    async def show_liked(self, _rerun: bool = False):
        self._view = ("liked", {})
        likes = self.store.data["likes"]
        widgets = [Static(f"[bold]Liked Songs[/bold]"), Static(f"{len(likes)} songs · stored on this machine only", classes="dim")]
        if likes:
            widgets.append(Horizontal(Button("⬇ Download all", id="btn-liked-download-all")))
            widgets.append(self._track_list(likes, "Liked Songs"))
        else:
            widgets.append(Static("Press l on any song and it lands here.", classes="dim"))
        await self._set_content(widgets)

    async def show_local(self, fresh: bool = False, _rerun: bool = False):
        self._view = ("local", {})
        try:
            data = await self.backend.local_list(fresh=fresh)
        except Exception:
            await self._set_content(self._error_widgets("Couldn't read the local library."))
            return
        tracks = data.get("tracks", [])
        widgets = [
            Static("[bold]On this device[/bold]"),
            Static(f"{len(tracks)} tracks · {esc(data.get('dir',''))}", classes="dim"),
            Horizontal(
                Button("↻ Rescan folder", id="btn-rescan"),
                Button("Delete download", id="btn-local-delete"),
            ),
            Static("Highlight a song and press Delete download (or x twice) to remove a file the app downloaded. Your own files in the music folder are never deleted from here.", classes="dim"),
            Static("Songs", classes="section"),
        ]
        if tracks:
            widgets.append(self._track_list(tracks, "On this device"))
        else:
            widgets.append(Static("No audio files found in that folder yet. Downloads land in its arzxsh subfolder.", classes="dim"))
        await self._set_content(widgets)

    # -- playlists ------------------------------------------------------
    async def show_playlists(self):
        self._view = ("playlists", {})
        self._mark_tab("playlists")
        pls = self.store.data["playlists"]
        widgets = [Static("Playlists", classes="section")]
        if pls:
            widgets.append(ListView(*[
                LinkItem(pl["name"], "playlist_detail", i, f"{len(pl['tracks'])} songs")
                for i, pl in enumerate(pls)]))
        else:
            widgets.append(Static("No playlists yet. Save the current queue or import one below.", classes="dim"))
        widgets.append(Static("New from current queue", classes="section"))
        widgets.append(Input(placeholder="Playlist name", id="pl-name"))
        widgets.append(Button("Save queue as playlist", id="btn-pl-save"))
        widgets.append(Static("Import", classes="section"))
        widgets.append(Static("Paste a YouTube Music playlist link or id, or a path to an .m3u / .txt file (one “Artist - Title” per line).", classes="dim"))
        widgets.append(Input(placeholder="Playlist link, id, or file path", id="pl-import"))
        widgets.append(Button("Import playlist", id="btn-pl-import"))
        await self._set_content(widgets)

    async def show_playlist_detail(self, index: int, _rerun: bool = False):
        self._view = ("playlist_detail", {"index": index})
        pls = self.store.data["playlists"]
        if not (0 <= index < len(pls)):
            await self.show_playlists()
            return
        pl = pls[index]
        widgets = [
            Static(f"[bold]{esc(pl['name'])}[/bold]"),
            Static(f"{len(pl['tracks'])} songs", classes="dim"),
            Horizontal(
                Button("▶ Play all", id="btn-pl-playall"),
                Button("⬇ Download all", id="btn-pl-download-all"),
                Button("Remove song", id="btn-pl-remove"),
                Button("Export (.m3u + .txt)", id="btn-pl-export"),
                Button("Delete playlist", id="btn-pl-delete"),
            ),
        ]
        if pl["tracks"]:
            widgets.append(Static("Highlight a song, then press the Remove song button (or x) to take it out of this playlist.", classes="dim"))
            widgets.append(self._track_list(pl["tracks"], pl["name"]))
        else:
            widgets.append(Static("Empty. Press a on any track to add it here.", classes="dim"))
        await self._set_content(widgets)

    def _export_playlist(self, name: str, tracks: list[dict]) -> Path:
        music = Path(os.environ.get("ARZXSH_MUSIC_DIR", Path.home() / "Music"))
        outdir = music / "arzxsh" / "playlists"
        outdir.mkdir(parents=True, exist_ok=True)
        safe = re.sub(r"[^\w\s.-]+", "", name).strip() or "playlist"
        # Never silently overwrite an earlier export (audit
        # 2026-11-11): suffix until both target names are free.
        stem, n = safe, 1
        m3u, txt = outdir / f"{stem}.m3u", outdir / f"{stem}.txt"
        while m3u.exists() or txt.exists():
            n += 1
            stem = f"{safe} ({n})"
            m3u, txt = outdir / f"{stem}.m3u", outdir / f"{stem}.txt"

        def clean_line(value) -> str:
            # A CR/LF inside a title/artist would inject whole new
            # EXTINF entries or URL lines into the export.
            return re.sub(r"[\r\n]+", " ", str(value or ""))

        from urllib.parse import quote
        lines = ["#EXTM3U"]
        plain = []
        for t in tracks:
            lines.append(f"#EXTINF:{int(t.get('duration_s') or -1)},{clean_line(t.get('artist',''))} - {clean_line(t.get('title',''))}")
            if t.get("source") == "local":
                lines.append(f"{self.backend.base}/local/file/{quote(str(t['id']), safe='')}")
            else:
                lines.append(f"https://music.youtube.com/watch?v={quote(str(t['id']), safe='')}")
            plain.append(f"{clean_line(t.get('artist',''))} - {clean_line(t.get('title',''))}")
        m3u.write_text("\n".join(lines) + "\n")
        txt.write_text("\n".join(plain) + "\n")
        return m3u

    async def _import_playlist(self, source: str):
        source = source.strip()
        if not source:
            return
        name, tracks = None, []
        path = Path(source).expanduser()
        if path.is_file() and path.suffix.lower() in (".m3u", ".m3u8", ".txt"):
            name = path.stem
            queries = []
            local_by_stem = {}
            try:
                data = await self.backend.local_list()
                for t in data.get("tracks", []):
                    local_by_stem[t["title"].lower()] = t
            except Exception:
                pass
            for line in path.read_text(errors="replace").splitlines():
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                stem = Path(line).stem.lower() if ("/" in line or line.lower().endswith((".mp3", ".m4a", ".flac", ".ogg", ".opus", ".wav"))) else None
                if stem and stem in local_by_stem:
                    tracks.append(local_by_stem[stem])
                elif stem:
                    queries.append(Path(line).stem)
                else:
                    queries.append(line)
            for q in queries[:200]:
                try:
                    res = await self.backend.search(q, "songs", 1)
                    if res.get("results"):
                        tracks.append(res["results"][0])
                except Exception:
                    continue
        else:
            pid = source
            if "list=" in source:
                pid = parse_qs(urlparse(source).query).get("list", [source])[0]
            try:
                data = await self.backend.playlist(pid)
            except Exception:
                self.notify("Couldn't fetch that playlist. Check the link.", severity="error")
                return
            name = data.get("title") or "Imported playlist"
            tracks = data.get("tracks", [])
        if not tracks:
            self.notify("Import found no tracks.", severity="warning")
            return
        self.store.create_playlist(name or "Imported playlist", tracks)
        self.notify(f"Imported “{esc(name)}” ({len(tracks)} tracks)")
        await self.show_playlists()

    # -- settings ---------------------------------------------------------
    async def show_settings(self):
        self._view = ("settings", {})
        self._mark_tab("settings")
        d = self.store.data
        widgets = [Static("Settings", classes="section")]
        widgets.append(self._setting_row("Crossfade (seconds)", str(d["crossfade_s"]), "crossfade"))
        widgets.append(self._setting_row("Playback speed", f"{d['speed']}x", "speed"))
        widgets.append(self._setting_row("Sleep timer", "off", "sleep"))
        widgets.append(Horizontal(
            Static("Loudness normalization", classes="setting-name"),
            Switch(value=bool(d["loudnorm"]), id="sw-loudnorm"),
            classes="setting-row"))
        widgets.append(Static("Appearance", classes="section"))
        accent_name = self.store.setting("accent")
        if accent_name not in ACCENTS:
            accent_name = "green"
        widgets.append(self._setting_row("Accent color", accent_name.capitalize(), "accent"))
        widgets.append(Horizontal(
            Static("Download badges in lists", classes="setting-name"),
            Switch(value=bool(self.store.setting("download_badges")), id="sw-badge-download"),
            classes="setting-row"))
        widgets.append(Horizontal(
            Static("Lyrics badges in lists", classes="setting-name"),
            Switch(value=bool(self.store.setting("lyrics_badges")), id="sw-badge-lyrics"),
            classes="setting-row"))
        widgets.append(Static("Equalizer (dB)", classes="section"))
        for i, freq in enumerate(EQ_FREQS):
            label = f"{freq} Hz" if freq < 1000 else f"{freq // 1000} kHz"
            widgets.append(self._setting_row(label, str(d["eq"][i]), f"eq{i}"))
        widgets.append(Button("Reset EQ", id="btn-eq-reset"))
        await self._set_content(widgets)

    def _setting_row(self, name: str, value: str, key: str) -> Horizontal:
        return Horizontal(
            Static(name, classes="setting-name"),
            Button("−", id=f"set-{key}-minus"),
            Static(value, classes="setting-value", id=f"set-{key}-value"),
            Button("+", id=f"set-{key}-plus"),
            classes="setting-row")

    async def _adjust_setting(self, key: str, delta: int):
        d = self.store.data
        if key == "accent":
            cur = self.store.setting("accent")
            i = ACCENT_ORDER.index(cur) if cur in ACCENT_ORDER else 0
            new = ACCENT_ORDER[(i + delta) % len(ACCENT_ORDER)]
            self.store.set_setting("accent", new)
            # Live recolor: the theme swap restyles every
            # CSS-variable surface at once; the queue panel is
            # rebuilt so its inline-styled rows follow too.
            self.theme = f"arzxsh-{new}"
            self.query_one("#set-accent-value", Static).update(new.capitalize())
            self._refresh_queue_panel()
        elif key == "crossfade":
            d["crossfade_s"] = max(0, min(12, d["crossfade_s"] + delta))
            self.store.save()
            self.query_one("#set-crossfade-value", Static).update(str(d["crossfade_s"]))
        elif key == "speed":
            steps = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0]
            i = steps.index(d["speed"]) if d["speed"] in steps else 2
            d["speed"] = steps[max(0, min(len(steps) - 1, i + delta))]
            self.store.save()
            if self.engine:
                await self.engine.set_speed(d["speed"])
            self.query_one("#set-speed-value", Static).update(f"{d['speed']}x")
        elif key == "sleep":
            steps = [0, 10, 20, 30, 45, 60, 90]
            cur = getattr(self, "_sleep_minutes", 0)
            i = steps.index(cur) if cur in steps else 0
            self._sleep_minutes = steps[max(0, min(len(steps) - 1, i + delta))]
            if self.engine:
                self.engine.set_sleep_timer(self._sleep_minutes or None)
            self.query_one("#set-sleep-value", Static).update(
                "off" if not self._sleep_minutes else f"{self._sleep_minutes} min")
        elif key.startswith("eq"):
            band = int(key[2:])
            d["eq"][band] = max(-12, min(12, d["eq"][band] + delta))
            if self.engine:
                await self.engine.apply_eq()
            else:
                self.store.save()
            self.query_one(f"#set-{key}-value", Static).update(str(d["eq"][band]))

    # -- lyrics / now playing ------------------------------------------------
    async def show_lyrics(self):
        if not self.engine or not self.engine.current:
            self.notify("Nothing is playing.")
            return
        self._view = ("lyrics", {})
        track = self.engine.current
        widgets = []
        art = await art_for(self.backend, track, cols=30, rows=10)
        if art:
            widgets.append(Static(art))
        widgets.append(Static(f"[bold]{esc(track.get('title',''))}[/bold]"))
        widgets.append(Static(esc(track.get("artist", "")), classes="dim"))
        widgets.append(Static("About the artist", classes="section"))
        widgets.append(Static("…", id="lyrics-about"))
        widgets.append(Static("Lyrics", classes="section"))
        widgets.append(Static("Loading…", id="lyrics-body"))
        await self._set_content(widgets)
        self.run_worker(self._fill_lyrics(track), exclusive=False)
        self.run_worker(self._fill_about(track), exclusive=False)

    async def _fill_about(self, track: dict):
        try:
            el = self.query_one("#lyrics-about", Static)
        except Exception:
            return
        artist_id = track.get("artist_id")
        if not artist_id:
            el.update("No artist info for this track.")
            return
        try:
            a = await self.backend.artist(artist_id)
        except Exception:
            el.update("Couldn't load artist info.")
            return
        text = Text()
        text.append(a.get("name", ""), style="bold")
        if a.get("subscribers"):
            text.append(f"  ·  {a['subscribers']} subscribers", style="dim")
        if a.get("description"):
            text.append(f"\n{a['description']}")
        try:
            el.update(text)
        except Exception:
            pass

    async def _fill_lyrics(self, track: dict):
        data = await self.lyrics_fetcher.get(track)
        self._lyrics_data = data
        self._lyrics_idx = None
        self._lyrics_track_id = track["id"]
        self._render_lyrics_body()

    def _render_lyrics_body(self):
        if self._view[0] != "lyrics" or not self._lyrics_data:
            return
        try:
            el = self.query_one("#lyrics-body", Static)
        except Exception:
            return
        data = self._lyrics_data
        pos = self.engine.position if self.engine else 0
        if data["synced"]:
            idx = active_index(data["synced"], pos)
            if idx == self._lyrics_idx:
                return
            self._lyrics_idx = idx
            text = Text()
            for i, (_, line) in enumerate(data["synced"]):
                if i == idx:
                    text.append(line + "\n", style=f"bold {self.accent_hex}")
                else:
                    text.append(line + "\n", style="white" if abs(i - idx) <= 3 else "dim")
            el.update(text)
            if idx >= 0:
                try:
                    content = self.query_one("#content", VerticalScroll)
                    content.scroll_to(y=max(0, el.virtual_region.y + idx - 3), animate=False)
                except Exception:
                    pass
        elif data["plain"]:
            # Built as a Text object, not a markup string: lyric
            # text is attacker-shaped and must render literally —
            # as markup it could inject styles and even forge the
            # Source line appended below.
            text = Text(str(data["plain"]))
            if data["source"]:
                text.append("\n\n")
                text.append(f"Source: {data['source']}", style="dim")
            el.update(text)
        else:
            el.update("No lyrics found for this track.")

    # -- queue panel ---------------------------------------------------------
    def _refresh_queue_panel(self):
        if not self.engine:
            return
        lv = self.query_one("#queue-list", ListView)
        lv.clear()
        for i, t in enumerate(self.engine.queue):
            item = TrackItem(self, t, self.engine.queue, self.engine.context_name)
            lv.append(item)

    # -- events ---------------------------------------------------------------
    async def on_button_pressed(self, event: Button.Pressed):
        bid = event.button.id or ""
        tabs = {"tab-home": self.show_home, "tab-search": self.show_search,
                "tab-library": self.show_library, "tab-playlists": self.show_playlists,
                "tab-settings": self.show_settings}
        if bid in tabs:
            await tabs[bid]()
        elif bid == "btn-play":
            await self._toggle()
        elif bid == "btn-next":
            if self.engine: await self.engine.next()
        elif bid == "btn-prev":
            if self.engine: await self.engine.prev()
        elif bid == "btn-shuffle":
            self.action_shuffle()
        elif bid == "btn-repeat":
            self.action_repeat()
        elif bid == "btn-like":
            self.action_like()
        elif bid == "btn-download":
            await self._download_current_or_focused()
        elif bid == "btn-lyrics":
            await self.show_lyrics()
        elif bid == "btn-queue":
            self.action_queue_panel()
        elif bid == "btn-rescan":
            await self.show_local(fresh=True)
        elif bid == "btn-local-delete":
            await self._delete_local_focused()
        elif bid == "btn-pl-save":
            name = self.query_one("#pl-name", Input).value
            if self.engine and self.engine.queue:
                self.store.create_playlist(name or "My playlist", self.engine.queue)
                self.notify(f"Saved playlist “{esc(name or 'My playlist')}”")
                await self.show_playlists()
            else:
                self.notify("The queue is empty. Play something first.", severity="warning")
        elif bid == "btn-pl-import":
            src = self.query_one("#pl-import", Input).value
            await self._import_playlist(src)
        elif bid == "btn-pl-playall":
            _, kw = self._view
            pls = self.store.data["playlists"]
            if self.engine and 0 <= kw.get("index", -1) < len(pls) and pls[kw["index"]]["tracks"]:
                await self.engine.play_context(pls[kw["index"]]["tracks"], 0, pls[kw["index"]]["name"])
        elif bid == "btn-pl-export":
            _, kw = self._view
            pls = self.store.data["playlists"]
            if 0 <= kw.get("index", -1) < len(pls):
                path = self._export_playlist(pls[kw["index"]]["name"], pls[kw["index"]]["tracks"])
                self.notify(f"Exported to {esc(path)}")
        elif bid == "btn-pl-delete":
            _, kw = self._view
            self.store.delete_playlist(kw.get("index", -1))
            await self.show_playlists()
        elif bid == "btn-pl-remove":
            _, kw = self._view
            index = kw.get("index", -1)
            track = self._focused_track()
            pls = self.store.data["playlists"]
            if track and 0 <= index < len(pls):
                self.store.playlist_remove(index, track["id"])
                self.notify(f"Removed from “{esc(pls[index]['name'])}”")
                await self.show_playlist_detail(index)
            else:
                self.notify("Highlight a song in the list first, then press Remove song.", severity="warning")
        elif bid == "btn-pl-download-all":
            _, kw = self._view
            pls = self.store.data["playlists"]
            if 0 <= kw.get("index", -1) < len(pls):
                self._start_bulk_download(pls[kw["index"]]["tracks"], pls[kw["index"]]["name"])
        elif bid == "btn-liked-download-all":
            self._start_bulk_download(list(self.store.data["likes"]), "Liked Songs")
        elif bid == "btn-eq-reset":
            self.store.data["eq"] = [0] * 10
            if self.engine:
                await self.engine.apply_eq()
            else:
                self.store.save()
            await self.show_settings()
        elif bid.startswith("set-") and (bid.endswith("-plus") or bid.endswith("-minus")):
            key = bid[4:].rsplit("-", 1)[0]
            await self._adjust_setting(key, 1 if bid.endswith("-plus") else -1)

    async def on_switch_changed(self, event: Switch.Changed):
        if event.switch.id == "sw-loudnorm":
            self.store.data["loudnorm"] = event.value
            if self.engine:
                await self.engine.apply_eq()
            else:
                self.store.save()
        elif event.switch.id == "sw-badge-download":
            self.store.set_setting("download_badges", event.value)
            self._refresh_queue_panel()
        elif event.switch.id == "sw-badge-lyrics":
            self.store.set_setting("lyrics_badges", event.value)
            self._refresh_queue_panel()

    async def on_input_submitted(self, event: Input.Submitted):
        if event.input.id == "search-input":
            await self._run_search(event.value)
        elif event.input.id == "pl-import":
            await self._import_playlist(event.value)
        elif event.input.id == "pl-name":
            await self.on_button_pressed(Button.Pressed(self.query_one("#btn-pl-save", Button)))

    async def on_list_view_selected(self, event: ListView.Selected):
        item = event.item
        if isinstance(item, TrackItem):
            if self.engine:
                ctx = item.context
                idx = next((i for i, t in enumerate(ctx) if t["id"] == item.track["id"]), -1)
                if idx >= 0:
                    await self.engine.play_context(ctx, idx, item.context_name)
            else:
                self.notify("Playback unavailable (mpv missing?).", severity="error")
        elif isinstance(item, LinkItem):
            if item.action == "artist":
                await self.show_artist(item.payload)
            elif item.action == "album":
                await self.show_album(item.payload)
            elif item.action == "history":
                await self._run_search(item.payload)
                self.query_one("#search-input", Input).value = item.payload
            elif item.action == "liked":
                await self.show_liked()
            elif item.action == "local":
                await self.show_local()
            elif item.action == "playlist_detail":
                await self.show_playlist_detail(item.payload)

    # -- actions ---------------------------------------------------------------
    async def _toggle(self):
        if self.engine:
            await self.engine.toggle()

    def action_toggle_play(self):
        self.run_worker(self._toggle())

    def action_next(self):
        if self.engine:
            self.run_worker(self.engine.next())

    def action_prev(self):
        if self.engine:
            self.run_worker(self.engine.prev())

    def action_seek_fwd(self):
        if self.engine and not isinstance(self.focused, Input):
            self.run_worker(self.engine.seek_relative(10))

    def action_seek_back(self):
        if self.engine and not isinstance(self.focused, Input):
            self.run_worker(self.engine.seek_relative(-10))

    def _vol(self, delta: int):
        if self.engine:
            self.run_worker(self.engine.set_volume(self.store.data["volume"] + delta))

    def action_vol_up(self):
        self._vol(5)

    def action_vol_down(self):
        self._vol(-5)

    def action_shuffle(self):
        if self.engine:
            self.engine.shuffle = not self.engine.shuffle
            self.query_one("#btn-shuffle", Button).set_class(self.engine.shuffle, "on")
            self.notify(f"Shuffle {'on' if self.engine.shuffle else 'off'}")

    def action_repeat(self):
        if self.engine:
            r = self.engine.cycle_repeat()
            self.query_one("#btn-repeat", Button).set_class(r > 0, "on")
            self.query_one("#btn-repeat", Button).label = ["↻", "↻¹", "↻²"][r]
            self.notify(["Repeat off", "Repeat all", "Repeat one"][r])

    def _focused_track(self) -> dict | None:
        focused = self.focused
        if isinstance(focused, ListView) and focused.highlighted_child is not None:
            item = focused.highlighted_child
            if isinstance(item, TrackItem):
                return item.track
        return None

    def action_like(self):
        track = self._focused_track() or (self.engine.current if self.engine else None)
        if track:
            now = self.store.toggle_like(track)
            self.notify(f"{'♥ Liked' if now else 'Removed from Liked'}: {esc(track.get('title',''))}")
            self._sync_like_button()

    def _fetch_lyrics_background(self, track: dict):
        # Lyrics ride along with downloads (mirrors the Android
        # app since 0.6.12): the moment a track lands, fetch its
        # lyrics so the fetcher's on-disk store is populated at
        # download time and the song has synced lyrics offline
        # and after a restart. Scheduled as its own worker —
        # best-effort, silent, and never awaited by (so never
        # slowing or failing) the download itself.
        if not self.is_running:
            return

        async def _go():
            try:
                await self.lyrics_fetcher.get(track)
            except Exception:
                pass

        try:
            self.run_worker(_go(), exclusive=False)
        except RuntimeError:
            pass

    async def _download_current_or_focused(self):
        track = self._focused_track() or (self.engine.current if self.engine else None)
        if not track:
            return
        if track.get("source") == "local":
            self.notify("That track is already a local file.")
            return
        if track["id"] in self.store.data["downloads"]:
            self.notify("Already saved for offline.")
            return
        self.notify(f"Downloading “{esc(track.get('title',''))}”…")
        try:
            data = await self.backend.download(track)
            self.store.mark_downloaded(track["id"], data["track"]["id"])
            self._fetch_lyrics_background(track)
            self.notify("Saved for offline" if not data.get("already") else "Already saved for offline")
        except Exception:
            self.notify("Download failed. Check your connection.", severity="error")

    def _start_bulk_download(self, tracks: list[dict], name: str):
        if self._bulk_dl:
            self.notify("A bulk download is already running. Let it finish first.", severity="warning")
            return
        self.run_worker(self._download_all_tracks(tracks, name), name="bulk-download")

    async def _download_all_tracks(self, tracks: list[dict], name: str):
        # One at a time on purpose: the backend resolves and downloads
        # server-side, and a 90-song playlist fired off in parallel
        # would hammer both YouTube and the disk. Already-local and
        # already-saved tracks are skipped; a failure never stops the
        # rest of the batch.
        self._bulk_dl = True
        try:
            pending = [t for t in tracks
                       if t.get("source") != "local"
                       and t.get("id") not in self.store.data["downloads"]]
            skipped = len(tracks) - len(pending)
            if not pending:
                self.notify(f"Everything in “{esc(name)}” is already saved for offline.")
                return
            self.notify(f"Downloading {len(pending)} songs from “{esc(name)}”… one at a time, this can take a while.")
            saved = failed = 0
            for i, track in enumerate(pending, 1):
                try:
                    data = await self.backend.download(track)
                    self.store.mark_downloaded(track["id"], data["track"]["id"])
                    self._fetch_lyrics_background(track)
                    saved += 1
                except Exception:
                    failed += 1
                if i % 10 == 0 and i < len(pending):
                    self.notify(f"“{esc(name)}”: {i} of {len(pending)} done · {saved} saved"
                                + (f" · {failed} failed" if failed else ""))
            self.notify(f"“{esc(name)}” finished: {saved} saved"
                        + (f" · {skipped} already had" if skipped else "")
                        + (f" · {failed} failed" if failed else ""))
        finally:
            self._bulk_dl = False

    def action_download_focused(self):
        self.run_worker(self._download_current_or_focused())

    def action_add_to_playlist(self):
        track = self._focused_track() or (self.engine.current if self.engine else None)
        if not track:
            return
        pls = self.store.data["playlists"]
        if not pls:
            self.store.create_playlist("My Playlist", [track])
            self.notify("Created “My Playlist” and added the track (see Playlists tab)")
            return
        self.store.playlist_add(len(pls) - 1, track)
        self.notify(f"Added to “{esc(pls[-1]['name'])}”")

    async def _delete_local_focused(self):
        """Two-step delete for downloaded files: the first press
        arms (and says what will happen), the second press on the
        same song within a few seconds deletes. A file deletion is
        not undoable, so it never fires on a single keypress. The
        backend refuses anything outside the downloads folder."""
        track = self._focused_track()
        if not track:
            self.notify("Highlight a song first.", severity="warning")
            return
        tid = track["id"]
        now = time.monotonic()
        pending = self._pending_local_delete
        if not pending or pending[0] != tid or now - pending[1] > 6:
            self._pending_local_delete = (tid, now)
            self.notify(f"Press Delete again to remove “{esc(track.get('title', ''))}” (only works for app downloads)")
            return
        self._pending_local_delete = None
        try:
            await self.backend.delete_local(tid)
        except Exception as e:
            if "403" in str(e):
                self.notify("That file was not downloaded by the app, so the app will not delete it. Remove it from your music folder directly.", severity="error")
            else:
                self.notify("Couldn't delete that file. Is the backend running?", severity="error")
            return
        self.store.forget_download(tid)
        self.notify("Download deleted")
        await self.show_local(fresh=True)

    def action_remove_from_playlist(self):
        # Only meaningful inside a playlist's own screen and the
        # local library; everywhere else x is a no-op so it can
        # never eat a keypress by surprise.
        if self._view[0] == "local":
            self.run_worker(self._delete_local_focused())
            return
        if self._view[0] != "playlist_detail":
            return
        track = self._focused_track()
        if not track:
            return
        index = self._view[1].get("index", -1)
        pls = self.store.data["playlists"]
        if not (0 <= index < len(pls)):
            return
        self.store.playlist_remove(index, track["id"])
        self.notify(f"Removed from “{esc(pls[index]['name'])}”")
        self.run_worker(self.show_playlist_detail(index))

    def action_lyrics(self):
        self.run_worker(self.show_lyrics())

    def action_queue_panel(self):
        panel = self.query_one("#queue-panel")
        panel.set_class(not panel.has_class("open"), "open")
        if panel.has_class("open"):
            self._refresh_queue_panel()

    def action_focus_search(self):
        self.run_worker(self.show_search())

    def action_tab(self, name: str):
        fn = {"home": self.show_home, "search": self.show_search, "library": self.show_library,
              "playlists": self.show_playlists, "settings": self.show_settings}.get(name)
        if fn:
            self.run_worker(fn())

    # -- poll loop / cava ---------------------------------------------------------
    def _sync_like_button(self):
        cur = self.engine.current if self.engine else None
        liked = bool(cur and self.store.is_liked(cur["id"]))
        self.query_one("#btn-like", Button).label = "♥" if liked else "♡"
        self.query_one("#btn-like", Button).set_class(liked, "on")

    def _poll(self):
        if not self.engine or not self.engine.current:
            return
        eng = self.engine
        bar = self.query_one("#progressbar", ProgressBar)
        dur = eng.duration or eng.current.get("duration_s") or 0
        if dur:
            bar.update(progress=int(1000 * min(1.0, eng.position / dur)))
        self.query_one("#np-times", Static).update(f"{fmt(eng.position)} / {fmt(dur)}")
        self.query_one("#btn-play", Button).label = "⏸" if eng.playing else "▶"
        self.query_one("#vol-label", Static).update(f"  🔊 {self.store.data['volume']}")
        self._sync_like_button()
        if self._viz_bars:
            self.query_one("#viz", Static).update(self._viz_bars)
        if self._view[0] == "lyrics" and self._lyrics_data and self._lyrics_data.get("synced"):
            self._render_lyrics_body()

    def _start_cava(self):
        if not shutil.which("cava"):
            return
        try:
            cfg = runtime_dir() / "arzxsh-cava.conf"
            cfg.write_text(
                "[general]\nbars = 26\nframerate = 30\n"
                "[output]\nmethod = raw\nraw_target = /dev/stdout\n"
                "data_format = ascii\nascii_max_range = 7\n")
            self._cava_proc = subprocess.Popen(
                ["cava", "-p", str(cfg)], stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, text=True)

            def reader():
                blocks = " ▁▂▃▄▅▆▇"
                assert self._cava_proc and self._cava_proc.stdout
                for line in self._cava_proc.stdout:
                    try:
                        vals = [int(x) for x in line.strip().split(";") if x != ""]
                        self._viz_bars = "".join(blocks[max(0, min(7, v))] for v in vals)
                    except Exception:
                        continue

            threading.Thread(target=reader, daemon=True).start()
        except Exception:
            self._cava_proc = None


def run_tui():
    ArzxshApp().run()
