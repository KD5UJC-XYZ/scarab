# SPDX-License-Identifier: Apache-2.0
"""A minimal node-to-client LocalStateQuery client (stdlib only).

cardano-cli 11.2.3 refuses `query ledger-state` and `query protocol-state` before the Babbage era,
but the node still answers them. This speaks the mini-protocol directly through `socat` inside the
reference container, so the e2e can read the reference ledger in every Shelley-based era.
"""
import select
import socket
import struct
import subprocess
import time
from fractions import Fraction

from .cbor import enc


class Incomplete(Exception):
    pass


class Deadline(TimeoutError):
    """A session's overall deadline passed while it waited for the node (handshake, acquire or query)."""


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


class _HostSocket:
    """The process interface `LocalStateQuery` uses, over a Unix socket on the host."""

    def __init__(self, path):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(120)
        self.sock.connect(path)
        self.stdin = self
        self.stdout = self

    def write(self, data):
        self.sock.sendall(data)

    def flush(self):
        pass

    def read(self, n):
        return self.sock.recv(n)

    def fileno(self):
        return self.sock.fileno()

    def kill(self):
        self.sock.close()

    def wait(self):
        pass


class LocalStateQuery:
    """Context manager: one NtC connection to a node socket, inside a container (through `socat`)
    or, with `host=True`, a socket path on the host (the Scala node's socket directory).

    `deadline` (seconds, None for none) bounds the whole session from its start: every read of the
    handshake, an acquire or a query waits at most until then and otherwise raises `Deadline`, and the
    transport is terminated and reaped, so a node that keeps the connection open without answering
    cannot hang the caller."""

    VERSIONS = [32784 + i for i in range(0, 10)]

    def __init__(self, container, socket, magic, host=False, version=None, deadline=None):
        self.container, self.socket, self.magic, self.host = container, socket, magic, host
        self.version = version
        self.deadline = deadline

    def __enter__(self):
        self.expires = None if self.deadline is None else time.monotonic() + self.deadline
        if self.host:
            self.proc = _HostSocket(self.socket)
        else:
            self.proc = subprocess.Popen(
                ["docker", "exec", "-i", self.container, "socat", "-", f"UNIX-CONNECT:{self.socket}"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
        try:
            self.buffers = {}
            versions = self.VERSIONS if self.version is None else [32768 + self.version]
            self._send(0, enc([0, {v: [self.magic, False] for v in versions}]))
            reply = self._recv(0)
            if reply[0] != 1:
                raise RuntimeError(f"handshake refused: {reply}")
            self.agreed_version = reply[1] - 32768
            if self.version is not None and self.agreed_version != self.version:
                raise RuntimeError(f"handshake agreed {self.agreed_version}, requested {self.version}")
        except BaseException:
            self._close()  # `__exit__` does not run when `__enter__` fails
            raise
        return self

    def __exit__(self, *exc):
        try:
            if not isinstance(exc[1], Deadline):
                self._send(7, enc([5]))  # release (a no-op error when nothing is acquired)
                self._send(7, enc([7]))
        except Exception:
            pass
        self._close()

    def _close(self):
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
            if self.expires is not None:
                remaining = self.expires - time.monotonic()
                if remaining <= 0 or not select.select([self.proc.stdout], [], [], remaining)[0]:
                    raise Deadline(f"no answer from {self.socket} within {self.deadline} s")
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
                    self.last_raw = bytes(buf[:pos])
                    self.buffers[protocol] = buf[pos:]
                    return value
                except Incomplete:
                    pass
            _, proto, length = struct.unpack(">IHH", self._read(8))
            payload = self._read(length)
            self.buffers[proto & 0x7fff] = self.buffers.get(proto & 0x7fff, b"") + payload

    def acquire(self, point=None):
        """Acquire the volatile tip, or the state at `point` (None for the origin is not supported)."""
        self._send(7, enc([8] if point is None else [0, point]))
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

    def submit(self, tx, era_index=6):
        """LocalTxSubmission: send one transaction (its CBOR bytes) as the hard-fork-combinator
        `[era, #6.24(tx)]` (a transaction travels as CBOR-in-CBOR). Returns None when accepted, else the raw bytes of the rejection reason (an HFC
        `ApplyTxErr`)."""
        from .cbor import Tag
        self._send(6, enc([0, [era_index, Tag(24, bytes(tx))]]))
        reply = self._recv(6)
        if reply[0] == 1:
            return None
        if reply[0] != 2:
            raise RuntimeError(f"unexpected submission reply {reply}")
        return self._reason(self.last_raw)

    @staticmethod
    def _reason(raw):
        """The reason item of a `[2, reason]` message."""
        assert raw[0] == 0x82 and raw[1] == 0x02
        return raw[2:]
