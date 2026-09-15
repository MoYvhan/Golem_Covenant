#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Synthesises the 9 ritual / death-will sound effects and encodes them as Ogg
Vorbis, per spec 11.12 and docs/AUDIO_ASSETS.md.

Everything here is generated from scratch, so the result is original audio with
no licensing risk - which is exactly what AUDIO_ASSETS.md section 3 requires.
Mojang's original files are never read or referenced.

Design notes
------------
The encoder in `vorbis_encoder.py` carries the signal in the Vorbis *floor
curve*, which is piecewise-linear with 14 points per block. That means the
decoded audio is a heavily band-limited rendering of what we synthesise here.
So rather than fight it, these effects are written to survive that: each one is
built from a strong amplitude envelope plus a few low partials, which is what
a 14-point-per-block curve can actually represent. Dense high-frequency content
would be smeared into noise, so it is deliberately absent.

Each effect also has to be *distinguishable by ear* from the others - spec 5.3
lists 音效节奏 as one of the five axes that tell forms apart, so a B ceremony
and a C ceremony must not sound like the same sound at two pitches.
"""
from __future__ import annotations

import math
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import numpy as np  # noqa: E402

import vorbis_encoder as ve  # noqa: E402

RATE = 44100
BLOCK = 2048

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "src", "main", "resources", "assets",
                       "golem_covenant", "sounds")

# How many points the floor curve offers per block (see vorbis_encoder).
FLOOR_POINTS = len(ve._floor_x_list())


# ---------------------------------------------------------------------------
# synthesis primitives
# ---------------------------------------------------------------------------

def envelope(n: int, attack: float, decay: float, curve: float = 2.0) -> np.ndarray:
    """Attack/decay envelope over `n` samples, both given as fractions."""
    a = max(1, int(n * attack))
    d = max(1, n - a)
    up = np.linspace(0.0, 1.0, a, endpoint=False)
    # A power curve on the decay is what makes an effect read as a strike
    # rather than as a fade.
    down = (1.0 - np.linspace(0.0, 1.0, d, endpoint=True)) ** curve
    return np.concatenate([up, down])[:n]


def tone(n: int, freq: float, phase: float = 0.0,
         slope: float = 1.0) -> np.ndarray:
    """
    A sine whose frequency slides from `freq` to `freq*slope` across the
    buffer. The slide is what gives each effect its identity - a rising glide
    reads as "something arriving", a falling one as "something closing".
    """
    t = np.arange(n) / RATE
    if abs(slope - 1.0) < 1e-6:
        return np.sin(2 * math.pi * freq * t + phase)
    # Linear chirp: integrate the instantaneous frequency.
    span = math.log(max(slope, 1e-6))
    inst = freq * np.exp(span * np.linspace(0.0, 1.0, n))
    ph = 2 * math.pi * np.cumsum(inst) / RATE + phase
    return np.sin(ph)


def noise(n: int, seed: int, colour: float = 1.0) -> np.ndarray:
    """Deterministic noise, optionally low-passed (colour > 1 = darker)."""
    rng = np.random.default_rng(seed)
    x = rng.standard_normal(n)
    if colour > 1.0:
        k = int(colour)
        kernel = np.ones(k) / k
        x = np.convolve(x, kernel, mode="same")
    return x


def sub_click(n: int, freq: float, seed: int) -> np.ndarray:
    """A short low thump: the body of an impact."""
    body = tone(n, freq, slope=0.5) * envelope(n, 0.005, 0.995, 3.0)
    grit = noise(n, seed, 24.0) * envelope(n, 0.001, 0.999, 6.0) * 0.35
    return body + grit


def normalise(x: np.ndarray, peak: float = 0.89) -> np.ndarray:
    m = float(np.max(np.abs(x))) if x.size else 0.0
    return x * (peak / m) if m > 1e-9 else x


def resample_to_floor(signal: np.ndarray, blocks: int) -> list[list[float]]:
    """
    Reduces a waveform to `blocks` frames of FLOOR_POINTS values each.

    Each frame samples the waveform at the floor's own x positions, because
    those positions are where the decoder will place its vertices - sampling
    anywhere else would put the reconstructed curve in the wrong phase.
    """
    xs = ve._floor_x_list()
    n = len(signal)
    out: list[list[float]] = []
    total = blocks * BLOCK
    for b in range(blocks):
        frame: list[float] = []
        for x in xs:
            # x is 0..255 across the block; map it to a sample index.
            idx = int((b * BLOCK) + (x / 255.0) * (BLOCK - 1))
            if idx >= n:
                idx = n - 1
            # Average a small window so a single sample cannot dominate.
            lo = max(0, idx - 8)
            hi = min(n, idx + 9)
            frame.append(float(np.mean(signal[lo:hi])))
        out.append(frame)
    # Ensure the declared length matches what we actually emit.
    while len(out) * (BLOCK // 2) < total // 2:
        out.append([0.0] * FLOOR_POINTS)
    return out


def encode(signal: np.ndarray, path: str, seed: int) -> None:
    sig = normalise(signal)
    # Pad to a whole number of blocks so the granule count is exact.
    pad = (-len(sig)) % BLOCK
    if pad:
        sig = np.concatenate([sig, np.zeros(pad)])
    blocks = len(sig) // (BLOCK // 2)
    frames = resample_to_floor(sig, blocks)
    stream = ve.VorbisStream(rate=RATE, block0=256, block1=BLOCK,
                             serial=0x676F0000 | (seed & 0xFFFF))
    stream.write_headers()
    stream.write_audio(frames)
    data = stream.to_bytes()
    with open(path, "wb") as fh:
        fh.write(data)
    return len(data)


# ---------------------------------------------------------------------------
# the nine effects
# ---------------------------------------------------------------------------

def make_ritual_a(dur=1.9) -> np.ndarray:
    """
    A 级借魂: short, bright, one layer. Spec 5.5 calls A "快速、简洁、临时",
    so this is a single upward chime with no tail of any kind.
    """
    n = int(RATE * dur)
    t = np.arange(n) / RATE
    base = tone(n, 620, slope=1.28) * envelope(n, 0.01, 0.99, 2.4)
    bell = tone(n, 1240, slope=1.22) * envelope(n, 0.004, 0.996, 4.0) * 0.45
    shine = tone(n, 1860, slope=1.15) * envelope(n, 0.002, 0.998, 6.0) * 0.18
    return base + bell + shine


def make_ritual_b(dur=2.7) -> np.ndarray:
    """
    B 级觉醒: two layers, a soul core, and a final burst (spec 5.5).

    The shape follows spec 5.2's B sequence: a rising ring (the ground circle
    forming), a settling middle, then a struck burst at the end. That final
    impact is the audible signature of "复生".
    """
    n = int(RATE * dur)
    ring = tone(n, 196, slope=2.0) * envelope(n, 0.18, 0.82, 1.8) * 0.7
    core = tone(n, 392, slope=1.55) * envelope(n, 0.30, 0.70, 2.2) * 0.5
    shimmer = tone(n, 784, slope=1.35) * envelope(n, 0.42, 0.58, 3.0) * 0.3

    burst_len = int(RATE * 0.55)
    burst = sub_click(burst_len, 110, seed=11)
    burst = np.concatenate([np.zeros(n - burst_len), burst])

    return ring + core + shimmer + burst * 1.15


def make_ritual_c(dur=3.7) -> np.ndarray:
    """
    C 级圣契: the full seven-stage sequence (spec 5.2).

    Deliberately a *sequence*, not one sound stretched longer: three rings
    stack and rise, a rune swell passes through, then a held core with a slow
    vibrato, then the covenant lock - a hard two-part impact (outward blast,
    inward snap) that matches emitFinalBurst's visual grammar.
    """
    n = int(RATE * dur)
    t = np.arange(n) / RATE

    # 1. triple ring: three staggered rising tones
    rings = np.zeros(n)
    for i, (f, delay) in enumerate(((147, 0.00), (196, 0.18), (262, 0.36))):
        d = int(RATE * delay)
        seg = tone(n - d, f, slope=1.9) * envelope(n - d, 0.22, 0.78, 1.6)
        rings[d:] += seg * (0.55 - i * 0.08)

    # 2. rune column: a slow swell with a wide vibrato
    vibrato = 1.0 + 0.06 * np.sin(2 * math.pi * 5.5 * t)
    runes = np.sin(2 * math.pi * 330 * t * vibrato) \
        * envelope(n, 0.32, 0.68, 1.5) * 0.42

    # 3. core forging: six strikes across the later half
    forge = np.zeros(n)
    for k in range(6):
        at = int(RATE * (dur * 0.62 + k * (dur * 0.055)))
        seg_len = int(RATE * 0.16)
        if at + seg_len > n:
            break
        forge[at:at + seg_len] += sub_click(seg_len, 82 + k * 9, seed=20 + k) * 0.75

    # 4. final burst + covenant lock (outward, then inward)
    blast = np.zeros(n)
    at = int(RATE * (dur - 0.62))
    seg = int(RATE * 0.30)
    if at + seg > n:
        seg = n - at
    if seg > 0:
        blast[at:at + seg] += tone(seg, 90, slope=3.4) \
            * envelope(seg, 0.02, 0.98, 2.0) * 0.95
    at2 = int(RATE * (dur - 0.30))
    seg2 = n - at2
    if seg2 > 0:
        blast[at2:] += tone(seg2, 520, slope=0.42) \
            * envelope(seg2, 0.05, 0.95, 1.4) * 0.5

    return (rings + runes + forge + blast) * 0.9


def _will_theme(dur: float, base: float, slope: float, seed: int,
                sparkle: float, curve: float = 2.4) -> np.ndarray:
    """Shared body for the six death-will themes."""
    n = int(RATE * dur)
    body = tone(n, base, slope=slope) * envelope(n, 0.02, 0.98, curve)
    overtone = tone(n, base * 2.01, slope=slope * 0.94) \
        * envelope(n, 0.01, 0.99, curve * 1.6) * sparkle
    air = noise(n, seed, 32.0) * envelope(n, 0.004, 0.996, 5.0) * 0.22
    return body + overtone + air


def make_will_guard() -> np.ndarray:
    """护卫型: a low, settled, protective tone - no movement in it."""
    return _will_theme(1.6, 130, 1.10, seed=41, sparkle=0.30, curve=2.0)


def make_will_scout() -> np.ndarray:
    """侦察型: two quick rising pips, like a sonar return."""
    n = int(RATE * 1.4)
    out = np.zeros(n)
    for i, (delay, freq) in enumerate(((0.00, 880), (0.34, 1320))):
        d = int(RATE * delay)
        seg = int(RATE * 0.26)
        if d + seg > n:
            break
        out[d:d + seg] += tone(seg, freq, slope=1.35) \
            * envelope(seg, 0.01, 0.99, 3.0) * (0.9 - i * 0.15)
    return out


def make_will_heal() -> np.ndarray:
    """治疗型: a warm, slow major-third rise."""
    n = int(RATE * 1.7)
    a = tone(n, 330, slope=1.26) * envelope(n, 0.10, 0.90, 1.8) * 0.6
    b = tone(n, 415, slope=1.26) * envelope(n, 0.20, 0.80, 2.0) * 0.45
    c = tone(n, 660, slope=1.20) * envelope(n, 0.30, 0.70, 2.6) * 0.22
    return a + b + c


def make_will_element() -> np.ndarray:
    """元素型: a turbulent, wide-sweeping surge."""
    n = int(RATE * 1.5)
    sweep = tone(n, 210, slope=3.2) * envelope(n, 0.06, 0.94, 2.2) * 0.75
    churn = noise(n, 55, 12.0) * envelope(n, 0.15, 0.85, 2.0) * 0.35
    return sweep + churn


def make_will_control() -> np.ndarray:
    """控制型: a tightening, downward spiral that ends held."""
    n = int(RATE * 1.6)
    down = tone(n, 520, slope=0.36) * envelope(n, 0.05, 0.95, 1.7) * 0.7
    hold = tone(n, 148, slope=1.0) * envelope(n, 0.55, 0.45, 1.2) * 0.4
    return down + hold


def make_will_mobility() -> np.ndarray:
    """机动型: a fast upward whoosh - the shortest of the six."""
    n = int(RATE * 1.1)
    whoosh = tone(n, 240, slope=4.6) * envelope(n, 0.03, 0.97, 1.9) * 0.8
    dust = noise(n, 77, 26.0) * envelope(n, 0.01, 0.99, 3.2) * 0.3
    return whoosh + dust


BUILDERS = {
    "ritual_a": (make_ritual_a, 1),
    "ritual_b": (make_ritual_b, 2),
    "ritual_c": (make_ritual_c, 3),
    "death_will_guard": (make_will_guard, 4),
    "death_will_scout": (make_will_scout, 5),
    "death_will_heal": (make_will_heal, 6),
    "death_will_element": (make_will_element, 7),
    "death_will_control": (make_will_control, 8),
    "death_will_mobility": (make_will_mobility, 9),
}


def main() -> int:
    os.makedirs(OUT_DIR, exist_ok=True)
    print(f"[sounds] writing to {os.path.relpath(OUT_DIR)}")
    total = 0
    for name, (builder, seed) in BUILDERS.items():
        path = os.path.join(OUT_DIR, name + ".ogg")
        size = encode(builder(), path, seed)
        total += size
        print(f"  {name + '.ogg':<28} {size:>7} bytes")
    print(f"[sounds] {len(BUILDERS)} ogg files, {total} bytes total")
    return 0


if __name__ == "__main__":
    sys.exit(main())
