"""Playback engine: two mpv processes ("decks") driven over their
JSON IPC sockets, alternating for crossfade exactly like the web
player's dual audio elements.

- One deck plays; the other preloads the next track (paused at 0).
- At (duration - crossfade) the idle deck starts and both volumes
  ramp; the old deck is stopped at the end of the ramp.
- Without crossfade, mpv's own end-file event advances the queue.
- The engine owns the queue model (shuffle / repeat off|all|one,
  radio continuation via the backend) and a 250ms ticker the UI
  polls for position. EQ / loudnorm are applied to both decks as
  one lavfi chain; speed and volume are plain mpv properties.
"""
from __future__ import annotations

import asyncio
import json
import os
import random
import shutil

from .paths import runtime_dir
from .store import EQ_FREQS


class MpvDeck:
    def __init__(self, name: str, on_end_file, on_file_loaded):
        self.name = name
        # IPC sockets live in the per-user 0700 runtime dir
        # (audit 2026-11-11), not at enumerable fixed names in the
        # shared temp dir: the socket speaks unauthenticated mpv
        # JSON IPC, so its directory is its access control.
        self.sock_path = str(runtime_dir() / f"arzxsh-mpv-{name}-{os.getpid()}.sock")
        self.proc: asyncio.subprocess.Process | None = None
        self.reader: asyncio.StreamReader | None = None
        self.writer: asyncio.StreamWriter | None = None
        self._req_id = 0
        self._pending: dict[int, asyncio.Future] = {}
        self._read_task: asyncio.Task | None = None
        self.on_end_file = on_end_file
        self.on_file_loaded = on_file_loaded
        self._closing = False
        self._handler_tasks: set[asyncio.Task] = set()
        self.track_id: str | None = None
        self.volume = 0  # 0..100 deck-local (master scaling applied by engine)
        self.ready = False

    async def start(self, extra_args: list[str]):
        try:
            os.unlink(self.sock_path)
        except FileNotFoundError:
            pass
        args = [
            "mpv", "--no-video", "--idle=yes", "--really-quiet",
            "--gapless-audio=yes", "--cache=yes",
            f"--input-ipc-server={self.sock_path}",
            *extra_args,
        ]
        self.proc = await asyncio.create_subprocess_exec(
            *args, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
        for _ in range(60):
            try:
                self.reader, self.writer = await asyncio.open_unix_connection(self.sock_path)
                break
            except (FileNotFoundError, ConnectionRefusedError):
                await asyncio.sleep(0.1)
        else:
            raise RuntimeError("mpv IPC socket never appeared")
        self._read_task = asyncio.create_task(self._read_loop())
        await self.command("observe_property", 1, "time-pos")
        await self.command("observe_property", 2, "duration")
        await self.command("observe_property", 3, "pause")
        self.ready = True

    async def _read_loop(self):
        try:
            while True:
                line = await self.reader.readline()
                if not line:
                    break
                try:
                    msg = json.loads(line.decode("utf-8", "replace"))
                except Exception:
                    continue
                rid = msg.get("request_id")
                if rid is not None and rid in self._pending:
                    fut = self._pending.pop(rid)
                    if not fut.done():
                        fut.set_result(msg)
                event = msg.get("event")
                if event == "end-file":
                    # Never await handlers inline: a handler may
                    # itself send commands whose replies only this
                    # loop can deliver (inline awaiting deadlocks).
                    # Handlers are tracked so shutdown can cancel
                    # them; an untracked handler outlives the app
                    # and crashes calling back into a dead UI.
                    # The reason matters: "stop" is what mpv reports
                    # for a file OUR loadfile replace just replaced,
                    # so treating every end-file as a natural end
                    # makes a manual track switch advance forever.
                    if not self._closing:
                        self._spawn(self.on_end_file(self, msg.get("reason")))
                elif event == "file-loaded":
                    if not self._closing:
                        self._spawn(self.on_file_loaded(self))
        except Exception:
            pass

    def _spawn(self, coro):
        task = asyncio.create_task(coro)
        self._handler_tasks.add(task)
        task.add_done_callback(self._handler_tasks.discard)
        return task

    async def command(self, *args):
        if not self.writer:
            raise RuntimeError("deck not started")
        self._req_id += 1
        rid = self._req_id
        fut = asyncio.get_running_loop().create_future()
        self._pending[rid] = fut
        payload = json.dumps({"command": list(args), "request_id": rid}) + "\n"
        self.writer.write(payload.encode())
        await self.writer.drain()
        try:
            msg = await asyncio.wait_for(fut, timeout=10)
        except asyncio.TimeoutError:
            self._pending.pop(rid, None)
            raise
        if msg.get("error") not in (None, "success"):
            raise RuntimeError(f"mpv error: {msg.get('error')}")
        return msg.get("data")

    async def get(self, prop: str):
        try:
            return await self.command("get_property", prop)
        except Exception:
            return None

    async def set(self, prop: str, value):
        return await self.command("set_property", prop, value)

    async def load(self, url: str, play: bool):
        await self.command("loadfile", url, "replace")
        await self.set("pause", not play)

    async def stop(self):
        try:
            await self.command("stop")
        except Exception:
            pass
        self.track_id = None

    async def shutdown(self):
        # Closing first: no new event handlers spawn, and any
        # handler still in flight (e.g. an end-file advance racing
        # the quit) is cancelled before it can touch the UI.
        self._closing = True
        handlers = list(self._handler_tasks)
        for task in handlers:
            task.cancel()
        if handlers:
            await asyncio.gather(*handlers, return_exceptions=True)
        try:
            await self.command("quit")
        except Exception:
            pass
        if self._read_task:
            self._read_task.cancel()
        try:
            os.unlink(self.sock_path)
        except FileNotFoundError:
            pass


class Engine:
    def __init__(self, backend, store, on_track_changed=None, on_error=None):
        self.backend = backend
        self.store = store
        self.on_track_changed = on_track_changed or (lambda track: None)
        self.on_error = on_error or (lambda msg: None)
        self.queue: list[dict] = []
        self.index = -1
        self.current: dict | None = None
        self.context_name = ""
        self.playing = False
        self.shuffle = False
        self.repeat = 0  # 0 off, 1 all, 2 one
        self.position = 0.0
        self.duration = 0.0
        self._radio_busy = False
        self._error_streak = 0  # consecutive end-file "error" advances
        self._fading = False
        self._closing = False
        self._ticker: asyncio.Task | None = None
        self._sleep_task: asyncio.Task | None = None
        self.sleep_deadline: float | None = None
        mpris = self._mpris_args()
        self.deck_a = MpvDeck("a", self._on_end_file, self._on_file_loaded)
        self.deck_b = MpvDeck("b", self._on_end_file, self._on_file_loaded)
        self.active = self.deck_a
        self.idle = self.deck_b
        self._mpris_args_cache = mpris

    @staticmethod
    def _mpris_args() -> list[str]:
        # Optional desktop integration: if the mpv-mpris plugin is
        # installed, media keys / playerctl see the player.
        for cand in ("/usr/lib/mpv/mpris.so", "/usr/lib/mpv-mpris/mpris.so",
                     "/usr/lib/x86_64-linux-gnu/mpv/mpris.so"):
            if os.path.exists(cand):
                return [f"--script={cand}"]
        return []

    async def start(self):
        if not shutil.which("mpv"):
            raise RuntimeError("mpv is not installed (pacman -S mpv)")
        await self.deck_a.start(self._mpris_args_cache)
        await self.deck_b.start([])
        await self._apply_audio_chain()
        await self._apply_volume()
        self._ticker = asyncio.create_task(self._tick_loop())

    async def shutdown(self):
        self._closing = True
        if self._ticker:
            self._ticker.cancel()
        if self._sleep_task:
            self._sleep_task.cancel()
        await self.deck_a.shutdown()
        await self.deck_b.shutdown()

    # -- audio chain ------------------------------------------------
    def _af_chain(self) -> str:
        parts = []
        gains = self.store.data["eq"]
        if any(g != 0 for g in gains):
            filters = ",".join(
                f"equalizer=f={freq}:width_type=o:width=1.1:g={gain}"
                for freq, gain in zip(EQ_FREQS, gains))
            parts.append(f"lavfi=[{filters}]")
        if self.store.data["loudnorm"]:
            parts.append("lavfi=[loudnorm=I=-16:TP=-1.5:LRA=11]")
        return ",".join(parts)

    async def _apply_audio_chain(self):
        chain = self._af_chain()
        for deck in (self.deck_a, self.deck_b):
            if deck.ready:
                try:
                    await deck.command("af", "set", chain)
                except Exception:
                    pass

    async def _apply_volume(self):
        if self._fading:
            return
        await self._set_deck_volume(self.active, float(self.store.data["volume"]))
        await self._set_deck_volume(self.idle, 0)

    async def _set_deck_volume(self, deck: MpvDeck, value: float):
        deck.volume = value
        if deck.ready:
            try:
                await deck.set("volume", round(value, 1))
            except Exception:
                pass

    async def set_volume(self, value: int):
        self.store.data["volume"] = max(0, min(100, value))
        self.store.save()
        if not self._fading:
            await self._set_deck_volume(self.active, float(self.store.data["volume"]))

    async def set_speed(self, speed: float):
        self.store.data["speed"] = speed
        self.store.save()
        for deck in (self.deck_a, self.deck_b):
            if deck.ready:
                await deck.set("speed", speed)

    async def apply_eq(self):
        self.store.save()
        await self._apply_audio_chain()

    # -- queue model --------------------------------------------------
    def _next_index(self, auto: bool) -> int:
        if not self.queue:
            return -1
        if self.repeat == 2 and auto:
            return self.index
        if self.shuffle and len(self.queue) > 1:
            n = self.index
            while n == self.index:
                n = random.randrange(len(self.queue))
            return n
        if self.index < len(self.queue) - 1:
            return self.index + 1
        if self.repeat == 1:
            return 0
        return -1

    def has_next(self) -> bool:
        return self._next_index(True) >= 0

    async def play_context(self, tracks: list[dict], index: int, context_name: str = ""):
        if not tracks or index < 0 or index >= len(tracks):
            return
        self._error_streak = 0  # manual start: fresh error budget
        self.queue = list(tracks)
        self.context_name = context_name
        await self._start_at(index, fade=False)

    async def _start_at(self, index: int, fade: bool):
        if self._closing:
            return
        track = self.queue[index]
        self.index = index
        self.current = track
        self._fading = False
        from_deck, to_deck = self.active, self.idle if fade else self.active
        if fade:
            self.active, self.idle = to_deck, from_deck
        url = self.backend.stream_url(track)
        to_deck.track_id = track["id"]
        preloaded = getattr(to_deck, "_preloaded_id", None) == track["id"]
        if not preloaded:
            await to_deck.load(url, play=True)
        else:
            await to_deck.set("pause", False)
            to_deck._preloaded_id = None
        await to_deck.set("speed", self.store.data["speed"])
        master = float(self.store.data["volume"])
        if fade:
            self._fading = True
            asyncio.create_task(self._fade(master, from_deck, to_deck))
        else:
            await self._set_deck_volume(to_deck, master)
            await self._set_deck_volume(self.idle, 0)
        self.playing = True
        self.store.note_played(track)
        self.on_track_changed(track)

    async def _fade(self, master: float, from_deck: MpvDeck, to_deck: MpvDeck):
        fade_s = max(0.5, float(self.store.data["crossfade_s"]))
        steps = 12
        from_start = from_deck.volume
        for i in range(1, steps + 1):
            frac = i / steps
            await self._set_deck_volume(to_deck, master * frac)
            await self._set_deck_volume(from_deck, from_start * (1 - frac))
            await asyncio.sleep(fade_s / steps)
        await from_deck.stop()
        self._fading = False

    async def next(self, auto: bool = False):
        if not auto:
            self._error_streak = 0  # manual skip: fresh error budget
        n = self._next_index(auto)
        if n >= 0:
            await self._start_at(n, fade=False)
            return
        if auto and self.current and not self._radio_busy:
            self._radio_busy = True
            try:
                results = await self.backend.radio(self.current["id"])
                fresh = [t for t in results if all(q["id"] != t["id"] for q in self.queue)]
                if fresh:
                    self.queue.extend(fresh)
                    self._radio_busy = False
                    await self._start_at(self.index + 1, fade=False)
                    return
            except Exception:
                pass
            self._radio_busy = False
        if auto:
            self.playing = False

    async def prev(self):
        self._error_streak = 0  # manual: fresh error budget
        pos = await self.active.get("time-pos") or 0
        if pos and pos > 3:
            await self.seek(0)
        elif self.index > 0:
            await self._start_at(self.index - 1, fade=False)

    async def toggle(self):
        if not self.current:
            return
        paused = await self.active.get("pause")
        await self.active.set("pause", not bool(paused))
        self.playing = not bool(paused)

    async def seek(self, seconds: float):
        try:
            await self.active.command("seek", seconds, "absolute")
        except Exception:
            pass

    async def seek_relative(self, delta: float):
        try:
            await self.active.command("seek", delta, "relative")
        except Exception:
            pass

    def cycle_repeat(self) -> int:
        self.repeat = (self.repeat + 1) % 3
        return self.repeat

    # -- mpv events ---------------------------------------------------
    async def _on_file_loaded(self, deck: MpvDeck):
        if self._closing:
            return
        if deck is self.active:
            # A track that actually loads breaks the error streak.
            self._error_streak = 0
            dur = await deck.get("duration")
            if dur:
                self.duration = float(dur)

    # Consecutive broken tracks skipped on end-file "error" before
    # the engine gives up instead of racing through the queue.
    _ERROR_STREAK_MAX = 3

    async def _on_end_file(self, deck: MpvDeck, reason: str | None = None):
        if self._closing or deck is not self.active or self._fading:
            return
        if reason == "error":
            # A broken stream skips like the Android stage ladder,
            # but a run of dead tracks must not cascade: after a
            # few in a row, stop and tell the user plainly.
            self._error_streak += 1
            if self._error_streak > self._ERROR_STREAK_MAX:
                if self.playing:
                    self.playing = False
                    self.on_error(
                        "Playback stopped: several songs in a row "
                        "could not be played.")
                return
            await self.next(auto=True)
            return
        if reason != "eof":
            # "stop" (our own loadfile replace / stop command),
            # "quit", "redirect" and missing reasons are not the
            # track ending; acting on them is what made a manual
            # switch cascade through the whole queue.
            return
        # Natural end. The ticker normally starts the crossfade
        # early; if the track still ends first (very short track),
        # advance here.
        await self.next(auto=True)

    # -- ticker: position, preload, crossfade trigger -----------------
    async def _tick_loop(self):
        while True:
            try:
                await asyncio.sleep(0.25)
                if not self.current or not self.active.ready:
                    continue
                pos = await self.active.get("time-pos")
                dur = await self.active.get("duration")
                if pos is None:
                    continue
                self.position = float(pos)
                if dur:
                    self.duration = float(dur)
                if not self.duration:
                    continue
                remaining = self.duration - self.position
                fade_s = float(self.store.data["crossfade_s"])
                nxt = self._next_index(True)
                # Preload the next track on the idle deck.
                if (nxt >= 0 and self.repeat != 2 and remaining <= 45
                        and getattr(self.idle, "_preloaded_id", None) != self.queue[nxt]["id"]):
                    self.idle._preloaded_id = self.queue[nxt]["id"]
                    self.idle.track_id = self.queue[nxt]["id"]
                    try:
                        await self.idle.load(self.backend.stream_url(self.queue[nxt]), play=False)
                    except Exception:
                        self.idle._preloaded_id = None
                # Crossfade window.
                if (fade_s > 0 and nxt >= 0 and self.repeat != 2
                        and 0.15 < remaining <= fade_s and not self._fading):
                    await self._start_at(nxt, fade=True)
            except asyncio.CancelledError:
                raise
            except Exception:
                continue

    # -- sleep timer ----------------------------------------------------
    def set_sleep_timer(self, minutes: float | None):
        if self._sleep_task:
            self._sleep_task.cancel()
            self._sleep_task = None
        self.sleep_deadline = None
        if minutes:
            self.sleep_deadline = asyncio.get_running_loop().time() + minutes * 60
            self._sleep_task = asyncio.create_task(self._sleep_run(minutes * 60))

    async def _sleep_run(self, seconds: float):
        try:
            await asyncio.sleep(max(0, seconds - 10))
            # 10 second fade-out, then pause.
            start = float(self.store.data["volume"])
            for i in range(1, 11):
                await self._set_deck_volume(self.active, start * (1 - i / 10))
                await asyncio.sleep(1)
            await self.active.set("pause", True)
            self.playing = False
            await self._set_deck_volume(self.active, start)
            self.sleep_deadline = None
        except asyncio.CancelledError:
            pass
