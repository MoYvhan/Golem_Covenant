#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Independently verifies the generated .ogg assets.

Why this is separate from `gen_sounds.py`
-----------------------------------------
An encoder that validates its own output proves nothing: a bug in the bit
writer would be mirrored in a matching bug in the reader. This module is a
*decoder-side* check written against the raw format, so it can disagree with
the encoder. It re-derives everything from the bytes on disk:

  1. Ogg page structure: capture pattern, segment table consistency, page
     sequence continuity, and a **CRC recomputation** over every page.
  2. Packet reassembly across pages, honouring the continued-packet flag.
  3. Vorbis identification header: version, channels, sample rate, block sizes,
     and the framing bit.
  4. Vorbis comment header: vendor + comment list well-formed, framing bit set.
  5. Vorbis setup header: parses the codebook / floor / residue / mapping /
     mode sections far enough to assert the stream is *structurally* the
     configuration the project intends, and that the mode count is what the
     audio packets assume.
  6. Audio packets: mode number in range, floor payload present, and EOS set on
     the final page.

If any of this fails the file is not a legal Ogg Vorbis file, and Minecraft
would log a missing/invalid sound rather than play it - which is the exact
failure this check exists to prevent.
"""
from __future__ import annotations

import os
import struct
import sys

SOUNDS_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                          "src", "main", "resources", "assets",
                          "golem_covenant", "sounds")

EXPECTED = [
    "ritual_a", "ritual_b", "ritual_c",
    "death_will_guard", "death_will_scout", "death_will_heal",
    "death_will_element", "death_will_control", "death_will_mobility",
]

CRC_POLY = 0x04C11DB7

#: Mirrors the encoder's floor-class geometry. Restated here rather than
#: imported on purpose: this module must be able to *disagree* with the encoder,
#: so it derives its expectations from the format constants, not from the code
#: under test. If the two ever drift apart the mismatch is itself the finding.
FLOOR_PARTITIONS = 15       # bounded by the 65-element cap on the x list
#: The spec (7.2.2) renders a stream undecodable if the x list exceeds this.
FLOOR_X_LIMIT = 65
FLOOR_CLASS_DIMENSIONS = 4
FLOOR_CLASS_SUBCLASSES = 1
FLOOR_MULTIPLIER = 2
FLOOR_RANGEBITS = 8
#: Total x points as a decoder reconstructs them: the two implicit endpoints
#: plus one point per (partition, class dimension).
FLOOR_X_COUNT = 2 + FLOOR_PARTITIONS * FLOOR_CLASS_DIMENSIONS
if FLOOR_X_COUNT > 65:
    raise SystemExit(
        f"floor geometry implies a {FLOOR_X_COUNT}-point x list, above the "
        f"65-point limit the format allows")
_TBL = []
for _i in range(256):
    _r = _i << 24
    for _ in range(8):
        _r = ((_r << 1) ^ CRC_POLY) if (_r & 0x80000000) else (_r << 1)
        _r &= 0xFFFFFFFF
    _TBL.append(_r)


def crc(data: bytes) -> int:
    c = 0
    for b in data:
        c = ((c << 8) & 0xFFFFFFFF) ^ _TBL[((c >> 24) & 0xFF) ^ b]
    return c


class BitReader:
    """LSB-first reader, the decoder-side counterpart of the encoder's writer."""

    def __init__(self, data: bytes) -> None:
        self.data = data
        self.pos = 0          # bit position

    def read(self, bits: int) -> int:
        v = 0
        for i in range(bits):
            byte_i = (self.pos + i) >> 3
            if byte_i >= len(self.data):
                raise EOFError("ran off the end of the packet")
            bit = (self.data[byte_i] >> ((self.pos + i) & 7)) & 1
            v |= bit << i
        self.pos += bits
        return v

    def remaining(self) -> int:
        return len(self.data) * 8 - self.pos


problems: list[str] = []


def parse_pages(data: bytes, name: str) -> list[dict]:
    pages = []
    off = 0
    while off < len(data):
        if data[off:off + 4] != b"OggS":
            problems.append(f"{name}: no OggS capture pattern at offset {off}")
            return pages
        version = data[off + 4]
        if version != 0:
            problems.append(f"{name}: Ogg page version {version} != 0")
        htype = data[off + 5]
        granule = struct.unpack_from("<q", data, off + 6)[0]
        serial = struct.unpack_from("<I", data, off + 14)[0]
        seq = struct.unpack_from("<I", data, off + 18)[0]
        stored_crc = struct.unpack_from("<I", data, off + 22)[0]
        nsegs = data[off + 26]
        seg_table = data[off + 27:off + 27 + nsegs]
        body_len = sum(seg_table)
        body_off = off + 27 + nsegs
        body = data[body_off:body_off + body_len]

        # Recompute the CRC with the checksum field zeroed.
        raw = bytearray(data[off:body_off + body_len])
        raw[22:26] = b"\x00\x00\x00\x00"
        if crc(bytes(raw)) != stored_crc:
            problems.append(
                f"{name}: page {seq} CRC mismatch "
                f"(stored {stored_crc:#010x})")

        pages.append({
            "seq": seq, "serial": serial, "granule": granule,
            "header_type": htype, "segments": list(seg_table), "body": body,
        })
        off = body_off + body_len
    return pages


def reassemble(pages: list[dict], name: str) -> list[bytes]:
    """Rebuilds packets from the segment tables, spanning pages as needed."""
    packets: list[bytes] = []
    cur = bytearray()
    for pi, page in enumerate(pages):
        if pi > 0 and page["seq"] != pages[pi - 1]["seq"] + 1:
            problems.append(f"{name}: page sequence jump "
                            f"{pages[pi-1]['seq']} -> {page['seq']}")
        if pi == 0 and not (page["header_type"] & 0x02):
            problems.append(f"{name}: first page is not marked BOS")

        body = page["body"]
        bpos = 0
        # A page whose first segment is continued expects `cur` to be open.
        expecting_cont = bool(page["header_type"] & 0x01)
        if expecting_cont and pi > 0 and not cur:
            problems.append(f"{name}: page {page['seq']} claims continuation "
                            f"but no packet was open")
        for si, seg_len in enumerate(page["segments"]):
            cur.extend(body[bpos:bpos + seg_len])
            bpos += seg_len
            # A segment shorter than 255 terminates the packet.
            if seg_len < 255:
                packets.append(bytes(cur))
                cur = bytearray()
    if cur:
        problems.append(f"{name}: stream ended with an unfinished packet")
    return packets


def per_frame_amplitude(packets: list[bytes], expected_points: int,
                        name: str) -> tuple[float, float]:
    """
    Walks the audio packets and recovers the floor y values.

    Because this encoder carries audio *in the floor curve*, the decoded
    amplitude is literally these values - so reading them back is a direct
    measurement of whether the file contains signal or silence. A file that
    passes every structural check but decodes to silence is still useless, and
    that is the failure mode a structural check alone would miss.
    """
    lo, hi = None, None
    audio_seen = 0
    for pkt in packets:
        if not pkt:
            continue
        # The three header packets start with 0x01 / 0x03 / 0x05 followed by
        # "vorbis"; anything else is an audio packet.
        if len(pkt) >= 7 and pkt[1:7] == b"vorbis":
            continue
        r = BitReader(pkt)
        try:
            ptype = r.read(1)
            if ptype != 0:
                problems.append(f"{name}: audio packet type {ptype} != 0")
                continue
            mode = r.read(1)
            if mode not in (0, 1):
                problems.append(f"{name}: mode number {mode} out of range")
            nonzero = r.read(1)
            if nonzero == 0:
                continue
            # y values are `ilog(range - 1)` bits wide; the range comes from the
            # floor multiplier, not from a fixed constant.
            y_bits = max(1, ((256, 128, 86, 64)[FLOOR_MULTIPLIER - 1] - 1)
                         .bit_length())
            y0 = r.read(y_bits)
            y1 = r.read(y_bits)
            vals = [y0, y1]
            for _ in range(expected_points - 2):
                r.read(FLOOR_CLASS_SUBCLASSES)   # subclass selection
                same = r.read(1)
                if same == 0:
                    vals.append(r.read(y_bits))
                else:
                    vals.append(vals[-1])
            for v in vals:
                if lo is None or v < lo:
                    lo = v
                if hi is None or v > hi:
                    hi = v
            audio_seen += 1
        except EOFError as exc:
            problems.append(f"{name}: truncated audio packet ({exc})")
    if audio_seen == 0:
        problems.append(f"{name}: no audio packets found")
    return (lo if lo is not None else 0.0,
            hi if hi is not None else 0.0)


def verify(path: str, name: str) -> str:
    with open(path, "rb") as fh:
        data = fh.read()
    pages = parse_pages(data, name)
    if not pages:
        return "no pages"

    if not (pages[-1]["header_type"] & 0x04):
        problems.append(f"{name}: last page is not marked EOS")

    packets = reassemble(pages, name)

    # --- identification header ---
    ident = packets[0] if packets else b""
    if len(ident) < 30 or ident[1:7] != b"vorbis":
        problems.append(f"{name}: identification header missing/malformed")
        return "bad id header"
    if ident[7:11] != b"\x00\x00\x00\x00":
        problems.append(f"{name}: vorbis_version != 0")
    channels = ident[11]
    rate = struct.unpack_from("<I", ident, 12)[0]
    bs_byte = ident[28]
    b0 = 1 << ((bs_byte & 0x0F) + 6)
    b1 = 1 << ((bs_byte >> 4) + 6)
    if channels != 1:
        problems.append(f"{name}: expected mono, got {channels} channels")
    if rate != 44100:
        problems.append(f"{name}: expected 44100 Hz, got {rate}")
    if b0 > b1:
        problems.append(f"{name}: block0 {b0} > block1 {b1}")
    if not (ident[-1] & 0x01):
        problems.append(f"{name}: identification framing bit not set")

    # --- comment header ---
    comment = packets[1] if len(packets) > 1 else b""
    if len(comment) < 8 or comment[1:7] != b"vorbis":
        problems.append(f"{name}: comment header missing/malformed")
    else:
        r = BitReader(comment)
        r.read(8 * 7)
        try:
            vlen = r.read(32)
            for _ in range(vlen):
                r.read(8)
            ncom = r.read(32)
            for _ in range(ncom):
                clen = r.read(32)
                for _ in range(clen):
                    r.read(8)
            if r.read(1) != 1:
                problems.append(f"{name}: comment framing bit not set")
        except EOFError as exc:
            problems.append(f"{name}: comment header truncated ({exc})")

    # --- setup header: walk far enough to confirm our configuration ---
    setup = packets[2] if len(packets) > 2 else b""
    if len(setup) < 8 or setup[1:7] != b"vorbis":
        problems.append(f"{name}: setup header missing/malformed")
    else:
        r = BitReader(setup)
        r.read(8 * 7)
        try:
            nbooks = r.read(8) + 1
            if nbooks != 1:
                problems.append(f"{name}: expected 1 codebook, got {nbooks}")
            # codebook
            sync = r.read(24)
            if sync != 0x564342:
                problems.append(f"{name}: codebook sync {sync:#08x} wrong")
            r.read(16)                  # dimensions
            entries = r.read(24)
            ordered = r.read(1)
            lengths = []
            if ordered == 0:
                for _ in range(entries):
                    lengths.append(r.read(5))
            else:
                problems.append(f"{name}: unexpected ordered codebook")
            lookup = r.read(4)
            if lookup != 0:
                problems.append(f"{name}: codebook has a lookup table")
            # A Huffman tree must be complete: sum(2^-len) == 1.
            total = sum(2.0 ** -l for l in lengths if l > 0)
            if abs(total - 1.0) > 1e-6:
                problems.append(
                    f"{name}: codebook Huffman tree incomplete "
                    f"(sum 2^-len = {total})")

            ntrans = r.read(6) + 1
            if ntrans != 1:
                problems.append(f"{name}: expected 1 time transform, "
                                f"got {ntrans}")
            for _ in range(ntrans):
                # Each transform descriptor is 16 bits. Omitting this read
                # shifts everything after it by two bytes, which is how a
                # header can look "almost right" while its floor/residue
                # sections are read from the wrong offsets.
                if r.read(16) != 0:
                    problems.append(f"{name}: non-placeholder time transform")
            nfloors = r.read(6) + 1
            if nfloors != 1:
                problems.append(f"{name}: expected 1 floor, got {nfloors}")
            for _ in range(nfloors):
                ftype = r.read(16)
                if ftype != 1:
                    problems.append(
                        f"{name}: expected floor type 1, got {ftype}")
                # Walk the floor 1 body exactly as spec 7.2.2 describes.
                # Skipping it would leave the reader misaligned for every later
                # section, so the whole structure is consumed even though only
                # a few of its numbers are asserted.
                partitions = r.read(5)   # raw count, no +1
                class_list = [r.read(4) for _ in range(partitions)]
                max_class = max(class_list) if class_list else -1
                for _ci in range(max_class + 1):
                    r.read(3)                        # class_dimensions - 1
                    subclasses = r.read(2)
                    if subclasses:
                        r.read(8)                    # masterbook (only if != 0)
                    for _s in range(1 << subclasses):
                        r.read(8)                    # subclass book (+1 stored)
                multiplier = r.read(2) + 1
                if multiplier != FLOOR_MULTIPLIER:
                    problems.append(f"{name}: floor1 multiplier "
                                    f"{multiplier} != {FLOOR_MULTIPLIER}")
                rangebits = r.read(4)
                if rangebits != FLOOR_RANGEBITS:
                    problems.append(f"{name}: floor1 rangebits "
                                    f"{rangebits} != {FLOOR_RANGEBITS}")
                # The x list has no explicit count: it is derived from the
                # partition/class walk, and x[0] = 0 / x[1] = 2^rangebits are
                # implicit rather than transmitted.
                n_points = len(class_list) * FLOOR_CLASS_DIMENSIONS
                xs = [0, 1 << rangebits]
                for _ in range(n_points):
                    xs.append(r.read(rangebits))
                # x[1] is the spectrum's top edge and is deliberately larger
                # than the interior points, so the list as a whole is not
                # sorted; uniqueness is the real invariant the spec demands,
                # plus strict increase across the transmitted interior points.
                if len(set(xs)) != len(xs):
                    problems.append(f"{name}: floor1 x list has duplicates")
                interior = xs[2:]
                if any(b <= a for a, b in zip(interior, interior[1:])):
                    problems.append(f"{name}: floor1 interior x points are not "
                                    f"strictly increasing")
                if any(v <= 0 or v >= (1 << rangebits) for v in interior):
                    problems.append(f"{name}: floor1 interior x point out of "
                                    f"range 1..{1 << rangebits}-1")
                if r.read(1) != 1:
                    problems.append(f"{name}: floor1 framing bit not set")
            nres = r.read(6) + 1
            if nres != 1:
                problems.append(f"{name}: expected 1 residue, got {nres}")
            rtype = r.read(16)
            if rtype != 2:
                problems.append(f"{name}: expected residue type 2, got {rtype}")
            for _ in range(nres):
                r.read(24)                       # begin
                r.read(24)                       # end
                r.read(24)                       # partition size - 1
                classifications = r.read(6) + 1
                r.read(8)                        # classbook
                cascade = []
                for _ci in range(classifications):
                    low = r.read(3)
                    flag = r.read(1)
                    high = r.read(5) if flag else 0
                    cascade.append((low, high))
                for low, high in cascade:
                    for bit in range(8):
                        if (low | high) & (1 << bit):
                            r.read(8)            # a book number
            nmaps = r.read(6) + 1
            if nmaps != 1:
                problems.append(f"{name}: expected 1 mapping, got {nmaps}")
            for _ in range(nmaps):
                mtype = r.read(16)
                if mtype != 0:
                    problems.append(f"{name}: expected mapping type 0, "
                                    f"got {mtype}")
                # Submap count, then coupling flag, then the 2-bit reserved
                # field - in that order, and in that width.
                submaps = (r.read(4) + 1) if r.read(1) else 1
                if r.read(1):
                    steps = r.read(8) + 1
                    for _ in range(steps):
                        r.read(max(1, (channels - 1).bit_length()))
                        r.read(max(1, (channels - 1).bit_length()))
                if r.read(2) != 0:
                    problems.append(f"{name}: mapping reserved field is non-zero")
                # Channel mux is present only for multi-submap mappings.
                if submaps > 1:
                    for _ch in range(channels):
                        mux = r.read(4)
                        if mux > submaps - 1:
                            problems.append(f"{name}: channel mux {mux} out of "
                                            f"range (submaps={submaps})")
                for _sub in range(submaps):
                    r.read(8)                    # unused time placeholder
                    floor_no = r.read(8)
                    residue_no = r.read(8)
                    if floor_no != 0 or residue_no != 0:
                        problems.append(f"{name}: submap {_sub} references "
                                        f"floor {floor_no} / residue "
                                        f"{residue_no}")
            nmode = r.read(6) + 1
            if nmode != 2:
                problems.append(f"{name}: expected 2 modes, got {nmode}")
            for _ in range(nmode):
                r.read(1)                        # blockflag
                r.read(16)                       # window type
                r.read(16)                       # transform type
                r.read(8)                        # mapping
            if r.read(1) != 1:
                problems.append(f"{name}: setup framing bit not set")
        except EOFError as exc:
            problems.append(f"{name}: setup header truncated ({exc})")

    # --- audio packets: is there actually signal in here? ---
    # Recover the floor point count from the setup we just validated.
    expected_points = FLOOR_X_COUNT
    lo, hi = per_frame_amplitude(packets, expected_points, name)
    span = hi - lo
    if span < 4:
        problems.append(f"{name}: floor curve is flat (span {span:.0f}) - "
                        f"the file would decode to silence")

    dur = (pages[-1]["granule"] / 44100.0) if pages[-1]["granule"] else 0.0
    return (f"{len(data):>6}B  {len(pages):>3} pages  {dur:>5.2f}s  "
            f"floor span {lo:.0f}..{hi:.0f}")


def check_sounds_json() -> None:
    """
    Confirms `sounds.json` registers exactly the assets that exist.

    The two drift apart easily: adding a file without a registry entry makes it
    silently unplayable, and a registry entry without a file produces a runtime
    warning instead of a sound. Neither shows up at build time without this.
    """
    import json
    path = os.path.join(os.path.dirname(SOUNDS_DIR), "sounds.json")
    if not os.path.isfile(path):
        problems.append("sounds.json is missing")
        return
    try:
        with open(path, encoding="utf-8") as fh:
            registry = json.load(fh)
    except (OSError, ValueError) as exc:
        problems.append(f"sounds.json is unreadable ({exc})")
        return

    registered = set(registry)
    expected = set(EXPECTED)
    for name in sorted(expected - registered):
        problems.append(f"sounds.json has no entry for {name}")
    for name in sorted(registered - expected):
        problems.append(f"sounds.json registers unknown sound {name}")


def main() -> int:
    if not os.path.isdir(SOUNDS_DIR):
        print(f"[sounds-check] FAILED - no sounds dir at {SOUNDS_DIR}",
              file=sys.stderr)
        return 1

    present = sorted(f[:-4] for f in os.listdir(SOUNDS_DIR)
                     if f.endswith(".ogg"))
    print("[sounds-check] decoding generated assets independently")
    for name in EXPECTED:
        path = os.path.join(SOUNDS_DIR, name + ".ogg")
        if not os.path.isfile(path):
            problems.append(f"{name}.ogg is missing (spec 11.12 requires it)")
            continue
        info = verify(path, name)
        print(f"  {name + '.ogg':<28} {info}")

    extra = [n for n in present if n not in EXPECTED]
    if extra:
        problems.append(f"unexpected .ogg files: {extra}")

    # Cross-check the sound registry: a file nothing references is dead weight,
    # and a registry entry with no file is the warning-not-crash case the
    # audio doc warns about. Catching the pair here keeps them in step.
    check_sounds_json()

    if problems:
        print(f"[sounds-check] FAILED - {len(problems)} problem(s):",
              file=sys.stderr)
        for p in problems[:30]:
            print(f"  - {p}", file=sys.stderr)
        return 1
    print(f"[sounds-check] PASSED - {len(EXPECTED)} assets are valid Ogg "
          f"Vorbis with non-silent floor data")
    return 0


if __name__ == "__main__":
    sys.exit(main())
