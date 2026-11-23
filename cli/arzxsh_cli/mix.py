"""Auto Mix math for the CLI: the same transition generation and
Smart Reorder the Android app (Mix.kt) and the web player run,
over the same backend analysis (BPM, Camelot key, beat phase,
head/tail loudness). Pure functions; the app supplies analyses
keyed by track key and gets plain transition dicts back."""
from __future__ import annotations

import re


def track_key(track: dict) -> str:
    return f"{track.get('source') or 'ytmusic'}_{track['id']}"


_CAMELOT_RE = re.compile(r"^(\d+)([AB])$")


def camelot_cost(a: str | None, b: str | None) -> int:
    if not a or not b:
        return 3
    if a == b:
        return 0
    pa = _CAMELOT_RE.match(a)
    pb = _CAMELOT_RE.match(b)
    if not pa or not pb:
        return 3
    na, nb = int(pa.group(1)), int(pb.group(1))
    if na == nb:
        return 1
    step = min((na - nb) % 12, (nb - na) % 12)
    return 1 if step == 1 and pa.group(2) == pb.group(2) else 3


def generate_transition(a_out: dict, a_in: dict,
                        out_dur: float | None, in_dur: float | None) -> dict:
    """One automatic transition from the two tracks' analyses:
    style and bar-quantized length from the tempo gap and Camelot
    cost, the incoming start beat-aligned to the outgoing grid,
    and a loudness trim matching the incoming head to the
    outgoing tail (clamped to about +/-6 dB)."""
    bpm_a, bpm_b = float(a_out["bpm"]), float(a_in["bpm"])
    gap = min(abs(bpm_a - bpm_b), abs(bpm_a - 2 * bpm_b), abs(2 * bpm_a - bpm_b))
    cost = camelot_cost(a_out.get("camelot"), a_in.get("camelot"))
    style, bars = "fade", 2
    if cost <= 1 and gap <= 6:
        style, bars = "blend", 8
    elif cost <= 1 and gap <= 14:
        style, bars = "rise", 4
    length = round(bars * 4 * 60 / bpm_a)
    length = max(4, min(16, length))
    durs = [d for d in (out_dur, in_dur) if d]
    if durs:
        length = max(2, min(length, int(min(durs) // 4)))
    in_skip = 0.0
    beat_b = 60.0 / bpm_b
    phase_b = a_in.get("phase")
    if isinstance(phase_b, (int, float)):
        if out_dur:
            beat_a = 60.0 / bpm_a
            t = max(0.0, out_dur - length)
            frac = ((t - float(a_out.get("phase") or 0.0)) % beat_a) / beat_a
            in_skip = (phase_b + frac * beat_b) % beat_b
        else:
            in_skip = phase_b % beat_b
        in_skip = round(in_skip, 2)
    in_gain = 1.0
    tail_db, head_db = a_out.get("tailDb"), a_in.get("headDb")
    if isinstance(tail_db, (int, float)) and isinstance(head_db, (int, float)):
        in_gain = round(max(0.5, min(1.8, 10 ** ((tail_db - head_db) / 20))), 2)
    return {"style": style, "length": length, "outSkip": 0,
            "inSkip": in_skip, "inGain": in_gain, "auto": True}


def smart_reorder(tracks: list[dict], analyses: dict) -> list[dict]:
    """Greedy smooth order over the tracks that have analysis;
    unanalyzed tracks keep their relative order at the end (the
    Android rule: never invent a place for a track we know
    nothing about)."""
    analyzed = [t for t in tracks if track_key(t) in analyses]
    unknown = [t for t in tracks if track_key(t) not in analyses]
    if len(analyzed) < 2:
        return list(tracks)
    remaining = list(analyzed)
    out = [remaining.pop(0)]
    while remaining:
        last = analyses[track_key(out[-1])]
        best, best_cost = None, float("inf")
        for cand in remaining:
            a = analyses[track_key(cand)]
            cost = min(abs(float(a["bpm"]) - float(last["bpm"])) / 10, 4) \
                + camelot_cost(last.get("camelot"), a.get("camelot"))
            if cost < best_cost:
                best, best_cost = cand, cost
        out.append(best)
        remaining.remove(best)
    return out + unknown
