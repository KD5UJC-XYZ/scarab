# SPDX-License-Identifier: Apache-2.0
"""Run `cardano-cli` queries against one explicitly chosen ledger point.

`cardano-cli` has no option to acquire a given point: every invocation sends MsgAcquireVolatileTip
([8]) on its own connection and so reads the tip as it is when its process starts. On a devnet whose
blocks arrive every half second, a bundle of queries whose slowest member takes a second can never be
sure they read the same block, however often it is retried. `Pin` listens on one Unix socket per query
inside the reference container (through `socat`, the transport `ntcquery` uses), forwards the
connection to the node's socket and rewrites only the client's acquire message to
MsgAcquire ([0, point], or MsgReAcquire [6, point]), so every query reads the ledger state at the same
block. The ledger state of an acquired point is held by the node for the life of the connection, so
a query that outlasts the volatile window is still answered. A point that is no longer in the window
is refused by the node (MsgFailure) and the query fails; nothing is substituted.

Mini-protocol numbers and message tags: ouroboros-network `Ouroboros.Network.Protocol.LocalStateQuery.Type`
(MsgAcquire 0, MsgAcquired 1, MsgFailure 2, MsgQuery 3, MsgResult 4, MsgRelease 5, MsgReAcquire 6, MsgDone 7,
MsgAcquireVolatileTip 8, MsgReAcquireVolatileTip 9); LocalStateQuery is mini-protocol 7 of the node-to-client mux.
"""
import os
import struct
import subprocess
import threading

from .cbor import enc
from .ntcquery import Incomplete, decode

LOCAL_STATE_QUERY = 7
ACQUIRE_VOLATILE_TIP, REACQUIRE_VOLATILE_TIP = 8, 9
ACQUIRE_POINT, REACQUIRE_POINT = 0, 6
ACQUIRE_IMMUTABLE_TIP, REACQUIRE_IMMUTABLE_TIP = 10, 11
HEADER = struct.Struct(">IHH")
MAX_PAYLOAD = 12288


class AcquirePin:
    """Rewrites the acquire messages of a client's LocalStateQuery stream to acquire `point`.

    `feed` takes the payload bytes of the client's protocol-7 mux segments in order and returns the
    complete messages that can be sent on, re-encoded only when they are acquire or reacquire messages
    (volatile tip, immutable tip or explicit point): every one is sent as an acquisition of `point`.
    `point` is a function, so the point can be chosen after the connections are prepared.
    `acquires` counts the acquisitions, so a caller can tell a pinned connection from one that
    never asked for a state. `foreign` counts the explicit points that differed from `point`, which
    the client asked for itself (an explicit point, or the immutable tip) and which were replaced; a comparison with any is refused."""

    def __init__(self, point):
        self.point = point  # a function: the [slot, hash bytes] to acquire, read when an acquire arrives
        self.buffer = b""
        self.acquires = 0
        self.foreign = 0

    def feed(self, data):
        self.buffer += data
        out = []
        while self.buffer:
            try:
                message, size = decode(self.buffer, 0)
            except Incomplete:
                break
            raw, self.buffer = self.buffer[:size], self.buffer[size:]
            if isinstance(message, list) and message == [ACQUIRE_VOLATILE_TIP]:
                raw = enc([ACQUIRE_POINT, self.point()])
                self.acquires += 1
            elif isinstance(message, list) and message == [REACQUIRE_VOLATILE_TIP]:
                raw = enc([REACQUIRE_POINT, self.point()])
                self.acquires += 1
            elif isinstance(message, list) and len(message) == 2 and message[0] in (ACQUIRE_POINT, REACQUIRE_POINT):
                pinned = self.point()
                if message[1] != pinned:
                    self.foreign += 1
                raw = enc([message[0], pinned])
                self.acquires += 1
            elif isinstance(message, list) and message in ([ACQUIRE_IMMUTABLE_TIP], [REACQUIRE_IMMUTABLE_TIP]):
                # the immutable tip is another state than the pinned block: the client asked for something this
                # comparison does not read, so it is replaced like a foreign point and the comparison is refused
                raw = enc([ACQUIRE_POINT if message[0] == ACQUIRE_IMMUTABLE_TIP else REACQUIRE_POINT, self.point()])
                self.foreign += 1
                self.acquires += 1
            out.append(raw)
        return out


def segments(protocol_field, timestamp, message):
    """The mux segments carrying one message of one mini-protocol."""
    return b"".join(
        HEADER.pack(timestamp, protocol_field, len(message[i:i + MAX_PAYLOAD])) + message[i:i + MAX_PAYLOAD]
        for i in range(0, max(len(message), 1), MAX_PAYLOAD))


class MuxStream:
    """Splits a byte stream into mux segments."""

    def __init__(self):
        self.buffer = b""

    def feed(self, data):
        """The complete (timestamp, protocol field, payload) segments in what has arrived so far."""
        self.buffer += data
        out = []
        while len(self.buffer) >= HEADER.size:
            timestamp, field, length = HEADER.unpack_from(self.buffer)
            if len(self.buffer) < HEADER.size + length:
                break
            out.append((timestamp, field, self.buffer[HEADER.size:HEADER.size + length]))
            self.buffer = self.buffer[HEADER.size + length:]
        return out


def pin_client_segments(stream, pin, data):
    """Bytes to forward to the node for `data` from the client: the segments of other mini-protocols
    unchanged, and each LocalStateQuery message as one segment after `pin` has rewritten its acquire."""
    out = b""
    for timestamp, field, payload in stream.feed(data):
        if field & 0x7fff == LOCAL_STATE_QUERY:
            for message in pin.feed(payload):
                out += segments(field, timestamp, message)
        else:
            out += HEADER.pack(timestamp, field, len(payload)) + payload
    return out


CLAIM = """#!/bin/bash
# one client connection: relay it to the first prepared upstream whose listener still accepts
for up in /work/pin/$1.up.*; do
  case "$up" in *.pid) continue;; esac
  socat - UNIX-CONNECT:$up && exit 0
done
exit 1
"""


class Pair:
    """One prepared upstream: a listener that accepts one connection (the client's) in the reference
    container, and a connection to the node, with the client's acquire rewritten on the way."""

    def __init__(self, container, path, upstream, point):
        self.pin = AcquirePin(point)
        self.stream = MuxStream()
        self.listen = subprocess.Popen(
            ["docker", "exec", "-i", container, "sh", "-c",
             f"echo $$ > {path}.pid; exec socat UNIX-LISTEN:{path},mode=666 -"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
        self.node = subprocess.Popen(
            ["docker", "exec", "-i", container, "socat", "-", f"UNIX-CONNECT:{upstream}"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
        self.used = False
        self.threads = [threading.Thread(target=self._pump, args=(self.listen, self.node, True), daemon=True),
                        threading.Thread(target=self._pump, args=(self.node, self.listen, False), daemon=True)]
        for t in self.threads:
            t.start()

    def _pump(self, source, sink, from_client):
        try:
            while True:
                data = os.read(source.stdout.fileno(), 65536)
                if not data:
                    break
                if from_client:
                    self.used = True
                    data = pin_client_segments(self.stream, self.pin, data)
                sink.stdin.write(data)
        except (OSError, ValueError, RuntimeError):
            pass
        finally:
            try:
                sink.stdin.close()
            except OSError:
                pass

    def close(self):
        for p in (self.listen, self.node):
            p.kill()
            p.wait()
        for t in self.threads:
            t.join(timeout=5)
        for p in (self.listen, self.node):
            for f in (p.stdin, p.stdout):
                try:
                    f.close()
                except OSError:
                    pass


class Pin:
    """Sockets in the reference container, one per name, through which `cardano-cli` reads the ledger
    state at `point`. Each socket accepts as many connections as `depth` (a `cardano-cli` query opens
    more than one: it settles the era first), each forwarded to the node with its acquire pinned.

    `point` ([slot, hash bytes] of the block to read) is set after `start` and before any client connects.
    `acquires` reports, per name, how
    many acquire messages were rewritten; a name with none was never pinned."""

    DIRECTORY = "/work/pin"

    def __init__(self, container, upstream, names, depth=4):
        self.container, self.upstream, self.names, self.depth = container, upstream, list(names), depth
        self.point = None  # set by the caller, before the first client connects
        self.pairs = {n: [] for n in self.names}
        self.hubs = []

    def path(self, name):
        return f"{self.DIRECTORY}/{name}.sock"

    def _exec(self, script):
        return subprocess.run(["docker", "exec", self.container, "/bin/sh", "-c", script],
                              capture_output=True, text=True, timeout=60)

    def start(self):
        self._exec(f"rm -rf {self.DIRECTORY}; mkdir -p {self.DIRECTORY}")
        subprocess.run(["docker", "exec", "-i", self.container, "sh", "-c", f"cat > {self.DIRECTORY}/claim"],
                       input=CLAIM, text=True, timeout=60, check=True)
        for name in self.names:
            for i in range(self.depth):
                self.pairs[name].append(Pair(self.container, f"{self.DIRECTORY}/{name}.up.{i}", self.upstream, self.current_point))
            self.hubs.append(subprocess.Popen(
                ["docker", "exec", self.container, "sh", "-c",
                 f"echo $$ > {self.DIRECTORY}/{name}.hub.pid; exec socat UNIX-LISTEN:{self.path(name)},fork,mode=666 "
                 f"EXEC:'/bin/bash {self.DIRECTORY}/claim {name}'"],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))

    def current_point(self):
        if self.point is None:
            raise RuntimeError("a client connected before the point was chosen")
        return self.point

    def ready_script(self):
        """Shell text that waits until every socket exists."""
        return "for s in " + " ".join(self.path(n) for n in self.names) + \
               "; do for i in $(seq 100); do [ -S $s ] && break; sleep 0.05; done; done"

    def acquires(self):
        return {n: sum(p.pin.acquires for p in self.pairs[n]) for n in self.names}

    def foreign(self):
        """Per name, the explicit acquire points that were not the pinned one (replaced, and a failure)."""
        return {n: sum(p.pin.foreign for p in self.pairs[n]) for n in self.names}

    def connections(self):
        return {n: sum(1 for p in self.pairs[n] if p.used) for n in self.names}

    def close(self):
        for hub in self.hubs:
            hub.kill()
            hub.wait()
        self._exec(f"kill $(cat {self.DIRECTORY}/*.pid) 2>/dev/null; rm -rf {self.DIRECTORY}")
        for pairs in self.pairs.values():
            for pair in pairs:
                pair.close()
