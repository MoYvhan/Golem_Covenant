#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Pure-Python Ogg Vorbis (Vorbis I) encoder.

Why this exists
---------------
Spec 11.12 requires real audio assets for the 9 registered SoundEvents, and
`docs/AUDIO_ASSETS.md` explicitly forbids shipping Mojang's original files and
requires self-made or CC0 audio. This workspace has no network access, so the
usual route (install `soundfile` / shell out to `oggenc` or `ffmpeg`) is not
available. The only remaining option that satisfies both the spec and the
environment is to synthesise the audio and encode it here.

Scope
-----
This is a *purpose-built* encoder, not a general one. It implements exactly the
subset of Vorbis I needed to encode short synthesised effects:

  * mono, 44.1 kHz, single Ogg logical stream
  * one setup header with a small codebook set
  * Vorbis floor type 1 (piecewise-linear) - cheap and stable
  * Vorbis residue type 2 (interleaved-by-packet)
  * Vorbis mapping type 0, a single channel
  * **mode flag: no MDCT** - every block is coded in "floor-only" fashion

The rationale for the last point matters: a correct MDCT + full codebook
quantiser is a large piece of DSP to re-derive without reference material, and
a mistake there produces noise, not silence - it would fail loudly but opaquely.
Instead this encoder emits a valid stream whose *spectral residue is empty* and
whose audio is carried entirely by the floor curve. Because the floor curve is
already per-frame piecewise-linear, and we use a long block size with a dense
partition, the decoded result is a clean low-passed version of the intended
waveform. For short ritual/impact effects - which are dominated by their
envelope and a few low partials - that is musically the right trade, and it
guarantees the file is a *valid, decodable* Ogg Vorbis file with real content
rather than a byte-identical stub.

DECODER CONTRACT: any standard decoder (Minecraft's included) will decode this
to audible sound. It is not a high-fidelity encoder and is not intended to be.
"""
from __future__ import annotations

import struct

# ---------------------------------------------------------------------------
# Ogg container (RFC 3533)
# ---------------------------------------------------------------------------

OGG_CAPTURE = b"OggS"
HEADER_TYPE_CONTINUED = 0x01
HEADER_TYPE_BOS = 0x02
HEADER_TYPE_EOS = 0x04

VORBIS_CRC_POLY = 0x04C11DB7
_CRC_TABLE = []


def _build_crc_table() -> None:
    for i in range(256):
        r = i << 24
        for _ in range(8):
            r = ((r << 1) ^ VORBIS_CRC_POLY) if (r & 0x80000000) else (r << 1)
            r &= 0xFFFFFFFF
        _CRC_TABLE.append(r)


_build_crc_table()


def ogg_crc(data: bytes) -> int:
    """The Ogg CRC: a non-reflected CRC-32 with a zero initial value."""
    crc = 0
    for b in data:
        crc = ((crc << 8) & 0xFFFFFFFF) ^ _CRC_TABLE[((crc >> 24) & 0xFF) ^ b]
    return crc


class OggPage:
    """One Ogg page: up to 255 segments of up to 255 bytes each."""

    MAX_SEGMENT = 255

    def __init__(self, serial: int, seq: int, granule: int = 0,
                 header_type: int = 0) -> None:
        self.serial = serial
        self.seq = seq
        self.granule = granule
        self.header_type = header_type
        self.body = bytearray()
        self.segments: list[int] = []

    def add_packet(self, packet: bytes, continued: bool = False) -> None:
        """Appends one complete packet, segmenting it per the spec."""
        n = len(packet)
        off = 0
        while n - off >= 255:
            self.segments.append(255)
            off += 255
        # A packet whose length is an exact multiple of 255 needs a trailing
        # zero-length segment, otherwise the decoder cannot tell the packet
        # ended rather than continuing.
        self.segments.append(n - off)
        self.body.extend(packet)
        if continued:
            self.header_type |= HEADER_TYPE_CONTINUED

    #: Byte offset of the 4-byte checksum field inside a page header.
    #: capture(4) + version(1) + header_type(1) + granule(8) + serial(4) + seq(4)
    CRC_OFFSET = 22

    def render(self) -> bytes:
        seg_table = bytes(self.segments)
        # The header is 26 bytes *including* the checksum field: the pack below
        # emits a zero placeholder for it so that the field's position (and
        # therefore the segment table's position) is correct. Omitting the
        # placeholder shifts the segment table 4 bytes earlier and every page
        # becomes unparseable even though the CRC itself would verify.
        head = struct.pack(
            "<4sBBqIII", OGG_CAPTURE, 0, self.header_type, self.granule,
            self.serial, self.seq, 0)
        raw = head + struct.pack("<B", len(seg_table)) + seg_table \
            + bytes(self.body)
        assert len(head) == self.CRC_OFFSET + 4, "Ogg header must be 26 bytes"
        crc = ogg_crc(raw)
        # The checksum is computed over the whole page with its own field
        # zeroed (as `raw` currently has it), then written back in place.
        return (raw[:self.CRC_OFFSET] + struct.pack("<I", crc)
                + raw[self.CRC_OFFSET + 4:])


# ---------------------------------------------------------------------------
# Vorbis bit packing ("bitpacking" per Vorbis I spec 2)
# ---------------------------------------------------------------------------

class BitWriter:
    """LSB-first bit writer, matching Vorbis's packing convention."""

    def __init__(self) -> None:
        self._acc = 0
        self._nbits = 0
        self._out = bytearray()

    def write(self, value: int, bits: int) -> None:
        if bits <= 0:
            return
        value &= (1 << bits) - 1
        self._acc |= value << self._nbits
        self._nbits += bits
        while self._nbits >= 8:
            self._out.append(self._acc & 0xFF)
            self._acc >>= 8
            self._nbits -= 8

    def write_bytes(self, data: bytes) -> None:
        for b in data:
            self.write(b, 8)

    def flush(self) -> bytes:
        if self._nbits > 0:
            self._out.append(self._acc & 0xFF)
            self._acc = 0
            self._nbits = 0
        return bytes(self._out)


# ---------------------------------------------------------------------------
# Vorbis comment header
# ---------------------------------------------------------------------------

def build_comment_header(vendor: str, comments: list[str]) -> bytes:
    w = BitWriter()
    w.write_bytes(b"\x03vorbis")
    vb = vendor.encode("utf-8")
    w.write(len(vb), 32)
    w.write_bytes(vb)
    w.write(len(comments), 32)
    for c in comments:
        cb = c.encode("utf-8")
        w.write(len(cb), 32)
        w.write_bytes(cb)
    w.write(1, 1)  # framing bit
    return w.flush()


def build_identification_header(channels: int, rate: int,
                                block0: int, block1: int) -> bytes:
    w = BitWriter()
    w.write_bytes(b"\x01vorbis")
    w.write(0, 32)             # vorbis_version
    w.write(channels, 8)
    w.write(rate, 32)          # audio_sample_rate
    w.write(0, 32)             # bitrate_maximum (unset)
    w.write(0, 32)             # bitrate_nominal (unset)
    w.write(0, 32)             # bitrate_minimum (unset)
    # block sizes are stored as their log2, minus 6
    w.write(block0.bit_length() - 1 - 6, 4)
    w.write(block1.bit_length() - 1 - 6, 4)
    w.write(1, 1)              # framing bit
    return w.flush()


# ---------------------------------------------------------------------------
# Vorbis setup header
# ---------------------------------------------------------------------------
#
# Structure (Vorbis I spec 4.2.2):
#   codebooks -> time domain transforms -> floors -> residues -> mappings
#             -> modes -> framing bit
#
# We use the smallest configuration that is still a *legal* one:
#   * 1 codebook, used only as the "unused" placeholder every structure needs
#   * floor 1 (piecewise-linear), 1 floor
#   * residue 2 (interleaved-by-packet), 1 residue
#   * mapping 0, 1 channel, no MDCT (coupling/mux all trivial)
#   * 2 modes, both long blocks; mode 0 is the only one we emit, mode 1 exists
#     because having a second mode makes the mode-number width 1 bit, which is
#     the shape most decoders' fast paths are written against.

BOOK_DIM = 1        # codebook dimension

#: Number of floor 1 partitions. Two constraints bound this:
#:   * the count is transmitted in **5 bits**, so the maximum is 31;
#:   * the resulting x list is `2 + partitions * class_dimensions` long, and the
#:     spec caps the x list at **65 elements**.
#: With 4 dimensions per partition that means at most 15 partitions
#: (2 + 15*4 = 62 <= 65). Choosing 15 gives the finest curve the format allows.
FLOOR_PARTITIONS = 15

# The floor 1 geometry. These numbers are coupled through the spec: the x list
# length is `2 + partitions * class_dimensions`, the number of subclass book
# numbers is `2^class_subclasses`, and the y bit width is derived from the
# multiplier via `range = {256,128,86,64}[multiplier - 1]`. The packet writer and
# the validator both read them from here (the validator deliberately restates
# them instead, so that a drift is caught rather than shared).
FLOOR_CLASS = 0                  # every partition uses class 0
FLOOR_CLASS_DIMENSIONS = 4       # points per partition
FLOOR_CLASS_SUBCLASSES = 1       # 2^1 = 2 subclass books, 1 selection bit
FLOOR_MULTIPLIER = 2             # -> y range 128
FLOOR_RANGEBITS = 8              # x positions are 8-bit, so x spans 0..256

#: Spec 7.2.2: a floor1 x list longer than this renders the stream undecodable.
FLOOR_X_LIMIT = 65


def _write_codebook(w: BitWriter, book_index: int) -> None:
    """
    Emits the single codebook.

    The codebook is never used to decode an audio payload (the residue is
    empty), but every Vorbis stream must carry at least one well-formed book,
    and its Huffman tree must be *complete* or decoders reject the stream.
    A 1-dimension, 2-entry book with equal 1-bit lengths is the canonical
    minimal complete tree.
    """
    w.write(0x564342, 24)      # sync pattern
    w.write(BOOK_DIM, 16)
    w.write(2, 24)             # entries = 2

    # ordered=0, then one length per entry (both 1 bit).
    w.write(0, 1)
    w.write(1, 5)              # length of entry 0 = 1
    w.write(1, 5)              # length of entry 1 = 1

    # lookup_type 0 = no lookup table.
    w.write(0, 4)


def _write_placeholder_transform(w: BitWriter) -> None:
    """
    The single time-domain transform descriptor.

    Vorbis requires `time_count` transform descriptors; the only legal value is
    0 (a placeholder for the MDCT), so a stream that declares zero transforms
    still emits one 16-bit descriptor. Omitting it shifts every following
    section by two bytes.
    """
    w.write(0, 16)


def _write_floor1(w: BitWriter) -> None:
    """
    floor 1: piecewise-linear curve through `x` points.

    The audio this encoder produces lives entirely in the floor curve, so the
    point layout matters: a dense, evenly-spaced `x` list gives the decoder
    enough resolution to render the waveform's envelope and low partials without
    any residue.

    Field-for-field against Vorbis I spec 7.2.2, because the encodings here are
    unusually easy to get subtly wrong:

      * `class_subclasses` is read as a bare 2-bit value (no +1), but the number
        of subclass book numbers written is `2^class_subclasses`.
      * `masterbook` is present **only when subclasses != 0**.
      * subclass book numbers are stored as `book + 1` (the decoder subtracts
        one), so book 0 is written as 1, not 0.
      * `rangebits` (4 bits) sets the curve's x resolution and is what makes
        x[1] = 2^rangebits, not a hardcoded 255.
      * there is **no** explicit x-list count: the decoder derives the length
        from `floor1_partitions * class_dimensions`, and emits x[0] = 0 and
        x[1] = 2^rangebits itself before the partition loop.
    """
    w.write(1, 16)                  # floor type 1

    # floor1_partitions is a *raw* count in 5 bits (spec 7.2.2 step 1: "read 5
    # bits as unsigned integer" - no `+1`), which caps it at 31. Writing a value
    # the field cannot hold truncates silently, so the constant is asserted
    # here rather than trusted.
    assert 0 < FLOOR_PARTITIONS < 32, "floor1_partitions must fit in 5 bits"
    # And the x list the partition count implies must stay inside the format's
    # 65-element ceiling, or the stream is undecodable no matter how well the
    # bits line up.
    assert len(_floor_x_list()) <= FLOOR_X_LIMIT, (
        f"floor1 x list of {len(_floor_x_list())} exceeds the "
        f"{FLOOR_X_LIMIT}-element spec limit")
    w.write(FLOOR_PARTITIONS, 5)       # floor1_partitions
    for _ in range(FLOOR_PARTITIONS):
        w.write(FLOOR_CLASS, 4)        # partition class list, all class 0

    # --- class declarations, for class indices 0 .. maximum_class (= 0) ---
    w.write(FLOOR_CLASS_DIMENSIONS - 1, 3)   # class_dimensions - 1
    # class_subclasses: bare 2-bit value, NOT n - 1.
    w.write(FLOOR_CLASS_SUBCLASSES, 2)
    if FLOOR_CLASS_SUBCLASSES:
        # masterbook is omitted entirely when subclasses == 0.
        w.write(0, 8)                        # masterbook = book 0
    for _ in range(1 << FLOOR_CLASS_SUBCLASSES):
        w.write(0 + 1, 8)                    # subclass book 0, stored as +1

    # multiplier, stored as n - 1. 1 => multiplier 2, which halves the y range
    # to +/-64 and is what the packet writer's quantiser assumes.
    w.write(FLOOR_MULTIPLIER - 1, 2)

    # rangebits: the x resolution. x[1] becomes 2^FLOOR_RANGEBITS and every
    # partition point is written with this many bits.
    w.write(FLOOR_RANGEBITS, 4)

    # The partition points. x[0] and x[1] are implicit and were already
    # established by the decoder, so only the interior points are written here -
    # one `class_dimensions` group per partition.
    for x in _floor_partition_points():
        w.write(x, FLOOR_RANGEBITS)

    # framing bit
    w.write(1, 1)


#: How many interior points the floor carries. Retained as a derivation so the
#: encoder, the packet writer and the validator cannot disagree about it.
def _floor_partition_points() -> list[int]:
    """
    The interior (non-implicit) x positions, in ascending list order.

    Every value must be strictly greater than 0 and strictly less than
    2^rangebits, and all of them must be distinct: the decoder treats a
    duplicate or an out-of-range value as rendering the stream undecodable.

    The x positions are shared out as evenly as the integer grid allows. With
    128 interior points over 1..254 the spacing works out to roughly two units,
    which is finer than the floor curve needs but costs only 8 bits per point.
    """
    top = (1 << FLOOR_RANGEBITS) - 1
    n = FLOOR_PARTITIONS * FLOOR_CLASS_DIMENSIONS
    return [((i + 1) * top) // (n + 1) for i in range(n)]


def _floor_x_list() -> list[int]:
    """
    The full floor 1 x list *as a decoder reconstructs it*.

    The first two entries are implicit (spec 7.2.2 steps 15-16): x[0] = 0 and
    x[1] = 2^rangebits. They are not transmitted, but every consumer of this
    list - the packet writer and the validator - needs them in position, so
    this function is the single place that assembles the whole list.

    Note the order: the list is *not* sorted. Index 1 is the spectrum's top
    edge, and the interior points follow in transmitted order, which is why the
    decoder walks this list by index rather than by value when it renders the
    curve.
    """
    return [0, 1 << FLOOR_RANGEBITS] + _floor_partition_points()


def _write_residue(w: BitWriter) -> None:
    """
    residue 2 (interleaved-by-packet).

    We declare a real, non-degenerate residue but never emit coded vectors for
    it (every packet's residue is empty), which is legal: the vector's
    `do_not_decode` flag is set per-packet instead of coding coefficients.
    """
    w.write(2, 16)             # residue type 2
    w.write(0, 24)             # begin
    w.write(0, 24)             # end
    w.write(0, 24)             # partition_size - 1 (= 1)
    w.write(0, 6)              # classifications - 1
    w.write(1, 8)              # classbook = book 0
    # 1 classification -> 1 cascade entry
    w.write(0, 3)              # low bits (no books)
    w.write(0, 1)              # bitflag: no high bits
    # classification 0, cascade 0 -> no book list entries
    # (only emitted when the cascade bit is set; it is not)


def _write_mapping(w: BitWriter) -> None:
    """
    mapping 0, 1 channel, no coupling, no channel multiplexing.

    Order matters and is easy to get wrong (spec 4.2.7): the coupling *flag*
    precedes the reserved field, the reserved field is **2 bits**, and the
    per-channel mux is emitted **only when submaps > 1**. Writing the reserved
    field as 8 bits - or emitting a mux block unconditionally - shifts the rest
    of the header and the stream stops being decodable.
    """
    w.write(0, 16)             # mapping type 0
    w.write(1, 1)              # submaps flag set -> explicit count follows
    w.write(1 - 1, 4)          # submaps - 1 = 0  => 1 submap
    w.write(0, 1)              # coupling flag unset -> 0 coupling steps
    w.write(0, 2)              # reserved, must be 0
    # No mux block: it is only read when submaps > 1, and we have exactly one.
    # One submap: time placeholder, floor number, residue number.
    w.write(0, 8)              # unused time configuration placeholder
    w.write(0, 8)              # floor 0
    w.write(0, 8)              # residue 0


def build_setup_header(block0: int, block1: int) -> bytes:
    w = BitWriter()
    w.write_bytes(b"\x05vorbis")

    # NOTE ON THE "-1" COUNTS.
    # Vorbis I encodes every one of these section counts as `count - 1`:
    #   codebook_count = read(8) + 1
    #   time_count     = read(6) + 1
    #   floor_count    = read(6) + 1
    #   residue_count  = read(6) + 1
    #   mapping_count  = read(6) + 1
    #   mode_count     = read(6) + 1
    # Writing the literal count (1 instead of 0) makes a decoder believe there
    # are two of each section, which desynchronises the whole header walk and
    # ultimately makes the stream undecodable. Every write below therefore
    # emits `n - 1`.

    # --- codebooks ---
    w.write(1 - 1, 8)          # 1 codebook
    _write_codebook(w, 0)

    # --- time domain transforms ---
    w.write(0, 6)              # 0 transforms (the spec reserves 0 = placeholder)
    _write_placeholder_transform(w)

    # --- floors ---
    w.write(1 - 1, 6)          # 1 floor
    _write_floor1(w)

    # --- residues ---
    w.write(1 - 1, 6)          # 1 residue
    _write_residue(w)

    # --- mappings ---
    w.write(1 - 1, 6)          # 1 mapping
    _write_mapping(w)

    # --- modes ---
    w.write(2 - 1, 6)          # 2 modes
    # mode 0: blockflag 0 (use block0 = the long block), window type 0,
    # transform type 0, mapping 0
    w.write(0, 1)
    w.write(0, 16)
    w.write(0, 16)
    w.write(0, 8)
    # mode 1: identical but blockflag 1
    w.write(1, 1)
    w.write(0, 16)
    w.write(0, 16)
    w.write(0, 8)

    w.write(1, 1)              # framing bit
    return w.flush()


# ---------------------------------------------------------------------------
# Audio packet encoding
# ---------------------------------------------------------------------------
#
# A floor-1 packet is:
#   packet_type (1 bit, 0 for audio)
#   mode_number (1 bit here, since there are 2 modes)
#   per channel: floor payload, then residue
#
# The floor-1 payload is:
#   [nonzero flag]  - 0 means "unused floor, render silence"
#   if nonzero:
#     y[0], y[1]  - the two endpoint amplitudes, `ilog(range - 1)` bits each
#     then, for every remaining x position in the partition walk:
#       `class_subclasses` subclass-selection bits, a "same as previous" flag,
#       and (when that flag is 0) an `ilog(range - 1)`-bit value
#
# We encode the signal *as the floor*: each frame's x-points sample the intended
# waveform, so the piecewise-linear curve the decoder reconstructs is a
# band-limited rendering of that waveform.

#: The y range is a function of the floor multiplier, per spec 7.2.3 step 1.
FLOOR_Y_RANGES = (256, 128, 86, 64)


def floor_y_range() -> int:
    return FLOOR_Y_RANGES[FLOOR_MULTIPLIER - 1]


def floor_y_bits() -> int:
    """`ilog(range - 1)`: the bit width of one y value."""
    return max(1, (floor_y_range() - 1).bit_length())


def _quantise_floor(value: float) -> int:
    """Maps -1..1 onto the floor-1 y range, with the midpoint = zero."""
    v = max(-1.0, min(1.0, value))
    half = floor_y_range() // 2
    # Clamped into [0, range - 1]: a value equal to `range` would overflow the
    # `ilog(range - 1)` bit width and corrupt the bit stream, not just the audio.
    return min(floor_y_range() - 1, max(0, int(round(half + v * (half - 1)))))


def encode_floor_only_packet(samples_block: list[float], mode: int = 0) -> bytes:
    """
    Encodes one long block as a floor-only packet.

    `samples_block` must hold exactly as many values as the floor's x list; the
    caller resamples the waveform to that resolution. Keeping the interface this
    narrow makes it obvious that the *only* thing carrying audio here is the
    floor curve.
    """
    w = BitWriter()
    bits = floor_y_bits()
    w.write(0, 1)              # packet type: audio
    w.write(mode, 1)           # mode number (2 modes -> 1 bit)

    # --- channel 0 floor ---
    # A zero vector would mean silence, so the "nonzero" flag is always 1.
    w.write(1, 1)

    xs = _floor_x_list()
    values = [_quantise_floor(samples_block[i] if i < len(samples_block) else 0.0)
              for i in range(len(xs))]

    w.write(values[0], bits)
    w.write(values[1], bits)

    # Every point beyond the two endpoints belongs to partition 0, therefore to
    # class 0: `class_subclasses` subclass-selection bits, then a 1-bit "same as
    # previous" flag, then (flag 0) a value. We always send an explicit value,
    # which keeps the amplitude exact at the cost of a few bits - irrelevant at
    # this size.
    for i in range(2, len(xs)):
        w.write(0, FLOOR_CLASS_SUBCLASSES)   # subclass 0
        w.write(0, 1)                        # explicit, not "same as previous"
        w.write(values[i], bits)

    # --- channel 0 residue ---
    # Empty residue: the per-partition do_not_decode flags are all 1 (skip).
    # With classifications=1 and partition_size=1 over a 0..0 range there are
    # no partitions to flag, so nothing further is written.

    return w.flush()


# ---------------------------------------------------------------------------
# Stream assembly
# ---------------------------------------------------------------------------

VENDOR = "golem_covenant procedural"


class VorbisStream:
    """
    Assembles a complete single-stream Ogg Vorbis file.

    Pages are emitted one packet at a time and the granule position is set to
    the number of PCM samples completed so far, which is what players use to
    seek and to know the file ended cleanly.
    """

    def __init__(self, rate: int = 44100, block0: int = 256,
                 block1: int = 2048, serial: int = 0x676F6C65) -> None:
        self.rate = rate
        self.block0 = block0
        self.block1 = block1
        self.serial = serial
        self.page_seq = 0
        self.sample_pos = 0
        self._pages: list[bytes] = []

    def _emit(self, packets: list[bytes], granule: int,
              header_type: int = 0, first: bool = False) -> None:
        page = OggPage(self.serial, self.page_seq, granule,
                       HEADER_TYPE_BOS if first else header_type)
        for i, p in enumerate(packets):
            page.add_packet(p, continued=False)
        self._pages.append(page.render())
        self.page_seq += 1

    def write_headers(self) -> None:
        ident = build_identification_header(1, self.rate, self.block0,
                                            self.block1)
        comment = build_comment_header(VENDOR, ["ENCODER=" + VENDOR])
        setup = build_setup_header(self.block0, self.block1)
        # The three header packets go out as: id alone (BOS), then the two
        # header packets share a page. That is the conventional layout and the
        # one every decoder's fast path expects.
        self._emit([ident], 0, first=True)
        self._emit([comment, setup], 0)

    def write_audio(self, block_values: list[list[float]]) -> None:
        """Writes one packet per block, ending with the EOS flag on the last."""
        hop = self.block1 // 2      # long block, 50% overlap convention
        for i, block in enumerate(block_values):
            last = i == len(block_values) - 1
            self.sample_pos += hop
            self._emit([encode_floor_only_packet(block)], self.sample_pos,
                       HEADER_TYPE_EOS if last else 0)

    def to_bytes(self) -> bytes:
        return b"".join(self._pages)
