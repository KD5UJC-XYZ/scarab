# SPDX-License-Identifier: Apache-2.0
"""A minimal node-to-client LocalStateQuery client (stdlib only).

cardano-cli 11.2.3 refuses `query ledger-state` and `query protocol-state` before the Babbage era,
but the node still answers them. This speaks the mini-protocol directly through `socat` inside the
reference container, so the e2e can read the reference ledger in every Shelley-based era.
"""
import struct
import subprocess
from fractions import Fraction

from .cbor import enc


class Incomplete(Exception):
    pass


class Tagged:
    def __init__(self, tag, value):
        self.tag, self.value = tag, value

    def __repr__(self):
        return f"Tagged({self.tag}, {self.value!r})"


class Undefined:
    pass


def _hashable(x):
    if isinstance(x, list):
        return tuple(_hashable(i) for i in x)
    if isinstance(x, dict):
        return tuple(sorted((k, _hashable(v)) for k, v in x.items()))
    if isinstance(x, Tagged):
        return (x.tag, _hashable(x.value))
    return x


def decode(buf, pos=0):
    """(value, next position); raises Incomplete when the buffer ends inside an item."""
    if pos >= len(buf):
        raise Incomplete()
    b = buf[pos]
    major, info = b >> 5, b & 31
    pos += 1
    if major == 7:
        if info == 20:
            return False, pos
        if info == 21:
            return True, pos
        if info == 22:
            return None, pos
        if info == 23:
            return Undefined(), pos
        if info == 31:
            return "break", pos
        size = {25: 2, 26: 4, 27: 8}.get(info)
        if size is None:
            return info, pos
        if pos + size > len(buf):
            raise Incomplete()
        fmt = {2: ">e", 4: ">f", 8: ">d"}[size]
        return struct.unpack(fmt, buf[pos:pos + size])[0], pos + size
    if info < 24:
        n = info
    elif info in (24, 25, 26, 27):
        size = 1 << (info - 24)
        if pos + size > len(buf):
            raise Incomplete()
        n = int.from_bytes(buf[pos:pos + size], "big")
        pos += size
    elif info == 31:
        n = None
    else:
        raise ValueError(f"bad additional info {info}")
    if major == 0:
        return n, pos
    if major == 1:
        return -1 - n, pos
    if major in (2, 3):
        if n is None:
            parts = []
            while True:
                if pos >= len(buf):
                    raise Incomplete()
                if buf[pos] == 0xff:
                    pos += 1
                    break
                part, pos = decode(buf, pos)
                parts.append(part)
            return (b"" if major == 2 else "").join(parts), pos
        if pos + n > len(buf):
            raise Incomplete()
        raw = bytes(buf[pos:pos + n])
        return (raw if major == 2 else raw.decode()), pos + n
    if major == 4:
        out = []
        if n is None:
            while True:
                item, pos = decode(buf, pos)
                if item == "break":
                    return out, pos
                out.append(item)
        for _ in range(n):
            item, pos = decode(buf, pos)
            out.append(item)
        return out, pos
    if major == 5:
        out = {}

        def put(k, v):
            out[_hashable(k)] = v
        if n is None:
            while True:
                k, pos = decode(buf, pos)
                if isinstance(k, str) and k == "break":
                    return out, pos
                v, pos = decode(buf, pos)
                put(k, v)
        for _ in range(n):
            k, pos = decode(buf, pos)
            v, pos = decode(buf, pos)
            put(k, v)
        return out, pos
    # tag
    v, pos = decode(buf, pos)
    if n == 2:
        return int.from_bytes(v, "big"), pos
    if n == 3:
        return -1 - int.from_bytes(v, "big"), pos
    if n == 30:
        return Fraction(v[0], v[1]), pos
    if n == 258:
        return v, pos
    if n == 24:
        return Tagged(24, v), pos
    return Tagged(n, v), pos


def decode_all(buf):
    v, pos = decode(buf, 0)
    return v


class LocalStateQuery:
    """Context manager: one NtC connection to a node socket inside a container."""

    VERSIONS = [32784 + i for i in range(0, 10)]

    def __init__(self, container, socket, magic):
        self.container, self.socket, self.magic = container, socket, magic

    def __enter__(self):
        self.proc = subprocess.Popen(
            ["docker", "exec", "-i", self.container, "socat", "-", f"UNIX-CONNECT:{self.socket}"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
        self.buffers = {}
        self._send(0, enc([0, {v: [self.magic, False] for v in self.VERSIONS}]))
        reply = self._recv(0)
        if reply[0] != 1:
            raise RuntimeError(f"handshake refused: {reply}")
        return self

    def __exit__(self, *exc):
        try:
            self._send(7, enc([7]))
        except Exception:
            pass
        self.proc.kill()
        self.proc.wait()

    def _send(self, protocol, payload):
        for i in range(0, len(payload), 12288):
            chunk = payload[i:i + 12288]
            self.proc.stdin.write(struct.pack(">IHH", 0, protocol, len(chunk)) + chunk)
        self.proc.stdin.flush()

    def _read(self, n):
        out = b""
        while len(out) < n:
            part = self.proc.stdout.read(n - len(out))
            if not part:
                raise EOFError("node closed the connection")
            out += part
        return out

    def _recv(self, protocol):
        while True:
            buf = self.buffers.get(protocol, b"")
            if buf:
                try:
                    value, pos = decode(buf, 0)
                    self.buffers[protocol] = buf[pos:]
                    return value
                except Incomplete:
                    pass
            _, proto, length = struct.unpack(">IHH", self._read(8))
            payload = self._read(length)
            self.buffers[proto & 0x7fff] = self.buffers.get(proto & 0x7fff, b"") + payload

    def acquire(self):
        self._send(7, enc([8]))  # VolatileTip
        reply = self._recv(7)
        if reply[0] != 1:
            raise RuntimeError(f"acquire failed: {reply}")

    def release(self):
        self._send(7, enc([5]))

    def query_raw(self, query):
        self._send(7, enc([3, query]))
        reply = self._recv(7)
        if reply[0] != 4:
            raise RuntimeError(f"query failed: {reply}")
        return reply[1]

    def ledger_query(self, era_index, shelley_query):
        """A Shelley-family block query in the current era; returns the unwrapped result."""
        result = self.query_raw([0, [0, [era_index, shelley_query]]])
        if not isinstance(result, list) or len(result) != 1:
            raise RuntimeError(f"era mismatch or unexpected reply: {str(result)[:200]}")
        return result[0]

    def point(self):
        """(slot, hash) of the acquired state (GetLedgerTip is query 0)."""
        raise NotImplementedError
