# SPDX-License-Identifier: Apache-2.0
"""Decoder of the UTxO-HD `tables` file of a db-analyser snapshot (the in-memory backend).

The file is a CBOR array holding one map: the key is the `BigEndianTxIn` (32-byte transaction id and a
big-endian 16-bit index) and the value the era's `TxOut` in the `MemPack` binary format of cardano-ledger
(`mempack` library: tags are one byte, fixed-width integers little endian, `VarLen` integers base-128
most significant group first, byte strings prefixed by a `VarLen` length). Both are wrapped as CBOR byte
strings. The result is the projection the Scala node writes (`Projection.txOut`).

Sources: Cardano.Ledger.{Shelley,Alonzo,Babbage}.TxOut, Cardano.Ledger.Mary.Value (CompactValue and its
flattened multi-asset representation), Cardano.Ledger.Plutus.Data (Datum), the per-era Scripts modules.
"""
import hashlib
import struct


def blake2b224(data):
    return hashlib.blake2b(data, digest_size=28).hexdigest()


class Reader:
    __slots__ = ("b", "i")

    def __init__(self, b, i=0):
        self.b, self.i = b, i

    def u8(self):
        v = self.b[self.i]
        self.i += 1
        return v

    def take(self, n):
        v = self.b[self.i:self.i + n]
        if len(v) != n:
            raise ValueError("MemPack buffer ended early")
        self.i += n
        return v

    def varlen(self):
        acc = 0
        while True:
            b = self.b[self.i]
            self.i += 1
            acc = (acc << 7) | (b & 0x7f)
            if not b & 0x80:
                return acc

    def sbs(self):
        return self.take(self.varlen())

    def done(self):
        return self.i == len(self.b)


def compact_coin(r):
    if r.u8() != 0:
        raise ValueError("CompactCoin tag")
    return r.varlen()


def compact_value(r, multi_asset):
    """(lovelace, {policy hex: {asset name hex: quantity}}) of a `CompactForm (Value era)`."""
    if not multi_asset:
        return compact_coin(r), {}
    tag = r.u8()
    coin = r.varlen()
    if tag == 0:
        return coin, {}
    if tag != 1:
        raise ValueError("CompactValue tag")
    n = r.varlen()
    rep = r.sbs()
    quantities = struct.unpack_from(f"<{n}Q", rep, 0)
    pid_offsets = struct.unpack_from(f"<{n}H", rep, 8 * n)
    name_offsets = struct.unpack_from(f"<{n}H", rep, 10 * n)
    ends = sorted(set(name_offsets))
    assets = {}
    for q, po, no in zip(quantities, pid_offsets, name_offsets):
        later = [e for e in ends if e > no]
        end = later[0] if later else len(rep)
        name = rep[no:end] if no < len(rep) else b""
        if q:
            assets.setdefault(rep[po:po + 28].hex(), {})[name.hex()] = q
    return coin, assets


def addr28(stake_is_key, stake_hash, extra):
    """Rebuild the base address from `TxOut_AddrHash28_*`: the payment hash and flags are in the extra words."""
    a, b, c, d = struct.unpack("<4Q", extra)
    pay_is_key = d & 1
    mainnet = (d >> 1) & 1
    pay_hash = a.to_bytes(8, "big") + b.to_bytes(8, "big") + c.to_bytes(8, "big") + (d >> 32).to_bytes(4, "big")
    header = ((0 if pay_is_key else 1) << 4 | (0 if stake_is_key else 1) << 5) | (1 if mainnet else 0)
    return bytes([header]) + pay_hash + stake_hash


def script_hash_of(kind, body):
    return blake2b224(bytes([kind]) + body)


def plutus_script(r, versions):
    """A `PlutusScript era`: tag = language index (0 is PlutusV1), then the script bytes. Hash prefix 1..3."""
    t = r.u8()
    if t >= versions:
        raise ValueError("PlutusScript tag")
    return t + 1, r.sbs()


def script(r, plutus_versions):
    """An `AlonzoScript`: 0 = native (the original CBOR bytes), 1 = Plutus. Returns the script hash."""
    t = r.u8()
    if t == 0:
        return script_hash_of(0, r.sbs())
    if t == 1:
        lang, body = plutus_script(r, plutus_versions)
        return script_hash_of(lang, body)
    raise ValueError("Script tag")


def datum(r, out):
    t = r.u8()
    if t == 0:
        return
    if t == 1:
        out["datumHash"] = r.take(32).hex()
    elif t == 2:
        out["inlineDatum"] = r.sbs().hex()
    else:
        raise ValueError("Datum tag")


def tx_out(era, value):
    """Projection of one MemPack `TxOut`; `era` is the ledger-state era (1 Shelley .. 6 Conway)."""
    r = Reader(value)
    multi_asset = era >= 3
    out = {}
    tag = r.u8()
    if era == 1 or era == 2 or era == 3:
        if tag != 0:
            raise ValueError("TxOut tag")
        out["address"] = r.sbs().hex()
        coin, assets = compact_value(r, multi_asset)
    else:
        if tag in (0, 1, 4, 5):
            out["address"] = r.sbs().hex()
            coin, assets = compact_value(r, True)
            if tag == 1:
                out["datumHash"] = r.take(32).hex()
            elif tag >= 4:
                if era < 5:
                    raise ValueError("Babbage TxOut tag in Alonzo")
                if tag == 4:  # TxOutCompactDatum: the inline datum's bytes, no tag
                    out["inlineDatum"] = r.sbs().hex()
                else:  # TxOutCompactRefScript: a tagged Datum, then the script
                    datum(r, out)
                    out["scriptRef"] = script(r, 2 if era == 5 else 3)
        elif tag in (2, 3):
            cred_tag = r.u8()
            stake = r.take(28)
            extra = r.take(32)
            out["address"] = addr28(cred_tag == 1, stake, extra).hex()
            coin = compact_coin(r)
            assets = {}
            if tag == 3:  # DataHash32: four little-endian words of the hash's big-endian words
                out["datumHash"] = b"".join(w.to_bytes(8, "big") for w in struct.unpack("<4Q", r.take(32))).hex()
        else:
            raise ValueError("TxOut tag")
    if not r.done():
        raise ValueError("MemPack TxOut not fully consumed")
    out["lovelace"] = coin
    if assets:
        out["assets"] = assets
    return out


def read_tables(buf):
    """Yield (txin text, raw value bytes) of a `tables` file (array of one map of byte strings; the map has
    a definite or an indefinite length)."""
    assert buf[0] == 0x81, "tables: array of one map expected"
    assert buf[1] >> 5 == 5, "tables: map expected"
    if buf[1] == 0xbf:
        pos, n = 2, None
    else:
        pos, n = _head(buf, 1)
    count = 0
    while True:
        if n is None:
            if buf[pos] == 0xff:
                pos += 1
                break
        elif count == n:
            break
        pos, klen = _head(buf, pos)
        key = buf[pos:pos + klen]
        pos += klen
        pos, vlen = _head(buf, pos)
        value = buf[pos:pos + vlen]
        pos += vlen
        count += 1
        yield f"{key[:32].hex()}#{int.from_bytes(key[32:], 'big')}", value
    assert pos == len(buf), "tables: trailing bytes"


def table_entries(buf):
    """Start offsets of the entries of a `tables` file, and the offset where they end."""
    from array import array
    assert buf[0] == 0x81 and buf[1] >> 5 == 5, "tables: array of one map expected"
    offsets = array("Q")
    if buf[1] == 0xbf:
        pos, n = 2, None
    else:
        pos, n = _head(buf, 1)
    while True:
        if n is None:
            if buf[pos] == 0xff:
                return offsets, pos
        elif len(offsets) == n:
            return offsets, pos
        offsets.append(pos)
        pos, klen = _head(buf, pos)
        pos += klen
        pos, vlen = _head(buf, pos)
        pos += vlen


def read_entries(buf, start, end):
    """Yield (txin text, raw value) of the entries in the byte range [start, end) of a `tables` file."""
    pos = start
    while pos < end:
        pos, klen = _head(buf, pos)
        key = buf[pos:pos + klen]
        pos += klen
        pos, vlen = _head(buf, pos)
        yield f"{key[:32].hex()}#{int.from_bytes(key[32:], 'big')}", buf[pos:pos + vlen]
        pos += vlen


def _head(buf, pos):
    b = buf[pos]
    info = b & 31
    pos += 1
    if info < 24:
        return pos, info
    size = 1 << (info - 24)
    return pos + size, int.from_bytes(buf[pos:pos + size], "big")
