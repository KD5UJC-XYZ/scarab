# SPDX-License-Identifier: Apache-2.0
"""Write the blocks of the Scala node's immutable store as a Haskell ImmutableDB that db-analyser can open.

The Scala store (`storage/ImmutableStore.scala`) keeps the raw blocks exactly as the peer sent them, and the
bytes of a Shelley-based block are the bytes the Haskell node writes to its chunk files (`[era tag, block]`;
checked against the chunks of a Haskell node that synced the same chain, see `verify`). Only the index files
differ: the Haskell ones are

  <chunk>.chunk      the blocks of the chunk, concatenated
  <chunk>.secondary  one 56-byte entry per block: block offset (u64), header offset (u16), header size (u16),
                     CRC32 of the block (u32), header hash (32 bytes), slot (u64); all big endian
  <chunk>.primary    version byte 1, then u32 offsets into the secondary file, one per relative slot of the
                     chunk plus the slot of an epoch boundary block (relative slot 0 of the file), and one more

Chunks hold `chunk_slots` slots: ten times the security parameter in the Byron genesis (Preview 4320,
Preprod and Mainnet 21600), whatever the era. Shelley-based blocks only (Byron epoch-boundary blocks have
a different entry type and are not handled).
"""
import pathlib
import struct
import sys
import zlib

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from devnet import ntcquery  # noqa: E402

INDEX_MAGIC = b"CSLIDX01"
ENTRY = 68
SECONDARY = 56


def scala_chunks(directory):
    """[(chunk file, index file)] of a Scala immutable directory, oldest first."""
    d = pathlib.Path(directory)
    return [(d / f"{f.stem}.chunk", f) for f in sorted(d.glob("*.index"))]


def scala_blocks(directory):
    """Yield (slot, hash, crc, raw block) for every block of a Scala immutable directory, in order."""
    for chunk, index in scala_chunks(directory):
        idx = index.read_bytes()
        assert idx[:8] == INDEX_MAGIC, f"{index}: not a Scala index"
        data = chunk.read_bytes()
        for i in range((len(idx) - 8) // ENTRY):
            e = idx[8 + i * ENTRY: 8 + (i + 1) * ENTRY]
            offset, length, crc, slot, _block_no = struct.unpack(">QIIQQ", e[:32])
            if e[64] & 1:
                raise ValueError("Byron epoch-boundary blocks are not exported")
            yield slot, e[32:64], crc, data[offset:offset + length]


def header_span(block):
    """(offset, size) of the header inside `[era tag, [header, ...]]`: the first element of the block array."""
    assert block[0] == 0x82 and block[2] >> 5 == 4, "a Shelley-based block `82 <era> <array>` is expected"
    start = 2 + (1 if block[2] & 31 < 24 else 2)
    _, end = ntcquery.decode(block, start)
    return start, end - start


def secondary_entry(offset, block, hash_, crc, slot):
    h_off, h_size = header_span(block)
    return struct.pack(">QHHI", offset, h_off, h_size, crc) + hash_ + struct.pack(">Q", slot)


def primary_index(chunk_no, chunk_slots, slots):
    """The primary index of a chunk whose filled slots are `slots` (absolute)."""
    first = chunk_no * chunk_slots
    filled = {s - first + 1 for s in slots}  # index 0 is the epoch boundary block's slot
    offsets, total = [0], 0
    for i in range(chunk_slots + 1):
        total += SECONDARY if i in filled else 0
        offsets.append(total)
    return bytes([1]) + struct.pack(f">{len(offsets)}I", *offsets)


class ChunkWriter:
    def __init__(self, out, chunk_slots):
        self.out, self.chunk_slots = pathlib.Path(out), chunk_slots
        self.chunk_no = None
        self.blocks = bytearray()
        self.secondary = bytearray()
        self.slots = []

    def add(self, slot, hash_, crc, block):
        no = slot // self.chunk_slots
        if no != self.chunk_no:
            self.flush()
            self.chunk_no = no
        self.secondary += secondary_entry(len(self.blocks), block, hash_, crc, slot)
        self.blocks += block
        self.slots.append(slot)

    def flush(self):
        if self.chunk_no is None or not self.slots:
            return
        stem = self.out / f"{self.chunk_no:05d}"
        stem.with_suffix(".chunk").write_bytes(self.blocks)
        stem.with_suffix(".secondary").write_bytes(self.secondary)
        stem.with_suffix(".primary").write_bytes(primary_index(self.chunk_no, self.chunk_slots, self.slots))
        self.blocks, self.secondary, self.slots = bytearray(), bytearray(), []


def export(scala_immutable, out_db, chunk_slots, protocol_magic_file=None, upto_slot=None, log=print):
    """Export the blocks of `scala_immutable` (up to `upto_slot`) to `out_db/immutable`; returns the block count.

    The last chunk written may be incomplete, so a node-style tip is always the last block exported."""
    out = pathlib.Path(out_db) / "immutable"
    out.mkdir(parents=True, exist_ok=True)
    if protocol_magic_file:
        (pathlib.Path(out_db) / "protocolMagicId").write_bytes(pathlib.Path(protocol_magic_file).read_bytes())
    w = ChunkWriter(out, chunk_slots)
    n = 0
    for slot, h, crc, block in scala_blocks(scala_immutable):
        if upto_slot is not None and slot > upto_slot:
            break
        w.add(slot, h, crc, block)
        n += 1
        if n % 200000 == 0:
            log({"exported": n, "slot": slot})
    w.flush()
    return n


def verify(exported, reference, chunks):
    """Compare the first `chunks` chunks of an export with a Haskell node's own ImmutableDB, file by file.
    Returns the list of differing files (empty: the export is byte for byte what the Haskell node wrote)."""
    bad = []
    for c in range(chunks):
        for ext in ("chunk", "secondary", "primary"):
            a = pathlib.Path(exported) / "immutable" / f"{c:05d}.{ext}"
            b = pathlib.Path(reference) / "immutable" / f"{c:05d}.{ext}"
            if a.read_bytes() != b.read_bytes():
                bad.append(str(a.name))
    return bad


def crc32(data):
    return zlib.crc32(data) & 0xffffffff
