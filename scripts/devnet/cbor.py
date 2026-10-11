# SPDX-License-Identifier: Apache-2.0
"""Canonical CBOR encoder (stdlib only): the subset the transaction builder and NtC client need."""
import struct


class Tag:
    def __init__(self, tag, value):
        self.tag, self.value = tag, value


class Raw:
    """Already encoded CBOR."""

    def __init__(self, data):
        self.data = data


def head(major, n):
    if n < 24:
        return bytes([major << 5 | n])
    if n < 1 << 8:
        return bytes([major << 5 | 24, n])
    if n < 1 << 16:
        return bytes([major << 5 | 25]) + struct.pack(">H", n)
    if n < 1 << 32:
        return bytes([major << 5 | 26]) + struct.pack(">I", n)
    return bytes([major << 5 | 27]) + struct.pack(">Q", n)


def enc(x):
    if isinstance(x, Raw):
        return x.data
    if x is None:
        return b"\xf6"
    if x is True:
        return b"\xf5"
    if x is False:
        return b"\xf4"
    if isinstance(x, int):
        return head(0, x) if x >= 0 else head(1, -1 - x)
    if isinstance(x, (bytes, bytearray)):
        return head(2, len(x)) + bytes(x)
    if isinstance(x, str):
        b = x.encode()
        return head(3, len(b)) + b
    if isinstance(x, (list, tuple)):
        return head(4, len(x)) + b"".join(enc(i) for i in x)
    if isinstance(x, frozenset):
        return enc(sorted(x))
    if isinstance(x, dict):
        items = sorted(((enc(k), enc(v)) for k, v in x.items()), key=lambda kv: (len(kv[0]), kv[0]))
        return head(5, len(items)) + b"".join(k + v for k, v in items)
    if isinstance(x, Tag):
        return head(6, x.tag) + enc(x.value)
    raise TypeError(type(x))
