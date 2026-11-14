# SPDX-License-Identifier: Apache-2.0
"""The official CLI's complete formatting of a recorded or replayed refusal reason.

This is a formatting oracle, not a native ledger capture. A small submission peer supplies the
already-judged complete MsgRejectTx to the unchanged pinned cardano-cli executable.
"""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import tempfile

from . import eratx
from .cbor import enc
from .ntcquery import decode, Incomplete
from .util import REFERENCE_IMAGE


def exact(connection, size):
    parts = bytearray()
    while len(parts) < size:
        part = connection.recv(size - len(parts))
        if not part:
            raise EOFError("CLI closed its formatting connection")
        parts.extend(part)
    return bytes(parts)


def receive(connection, protocol, pending):
    while True:
        buffered = pending.setdefault(protocol, bytearray())
        try:
            _, end = decode(buffered)
        except Incomplete:
            end = None
        if end is not None:
            result = bytes(buffered[:end])
            del buffered[:end]
            return result
        _, channel, size = struct.unpack(">IHH", exact(connection, 8))
        pending.setdefault(channel & 0x7fff, bytearray()).extend(exact(connection, size))


def send(connection, protocol, payload):
    for offset in range(0, len(payload), 65535):
        part = payload[offset:offset + 65535]
        connection.sendall(struct.pack(">IHH", 0, protocol | 0x8000, len(part)) + part)


def serve(listener, reason):
    connection, _ = listener.accept()
    with connection:
        connection.settimeout(20)
        pending = {}
        hello, _ = decode(receive(connection, 0, pending))
        version = max(hello[1])
        send(connection, 0, enc([1, version, hello[1][version]]))
        submitted = receive(connection, 6, pending)
        send(connection, 6, b"\x82\x02" + reason)
        return submitted


def render(era, transaction, reason, magic, *, image=REFERENCE_IMAGE):
    """Full stdout/stderr and exit status, without truncation, parsing, or newline normalization."""
    with tempfile.TemporaryDirectory(prefix="scarab796-cli-") as temporary:
        root = Path(temporary)
        (root / "transaction.json").write_text(eratx.envelope(era, transaction))
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            listener.settimeout(20)
            listener.bind(str(root / "node.socket"))
            listener.listen(1)
            with ThreadPoolExecutor(max_workers=1) as workers:
                observed = workers.submit(serve, listener, reason)
                result = subprocess.run([
                    "docker", "run", "--rm", "--network", "none", "--pull=never",
                    "--label", "xyz.kd5ujc.scarab.kind=oracle",
                    "--label", "xyz.kd5ujc.scarab.owner=codex796-cli-format",
                    "--user", f"{os.getuid()}:{os.getgid()}",
                    "-v", f"{root}:/io:ro", "--entrypoint", "/opt/reference/bin/cardano-cli", image,
                    "conway", "transaction", "submit", "--testnet-magic", str(magic),
                    "--socket-path", "/io/node.socket", "--tx-file", "/io/transaction.json"],
                    capture_output=True, timeout=30)
                submitted = observed.result(timeout=20)
        return {"exit": result.returncode, "stdout": result.stdout.decode(),
                "stderr": result.stderr.decode(), "submittedMessageCbor": submitted.hex(),
                "evidenceKind": "official-cli-formatting-of-recorded-wire-reason"}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recording", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--magic", type=int, default=42)
    args = parser.parse_args()
    results = []
    for row in json.loads(args.recording.read_text()):
        if row["reference"]["accepted"]:
            continue
        wire = row["reference"]["wire"]
        rendered = render(row["era"], bytes.fromhex(wire["transaction"]),
                          bytes.fromhex(row["reference"]["reasonCbor"]), args.magic)
        expected = row["reference"]["cli"]
        equal = {key: rendered[key] for key in ("exit", "stdout", "stderr")} == expected
        results.append({"case": row["name"], "equal": equal, "cli": rendered})
        args.output.write_text(json.dumps(results, indent=2) + "\n")
    if not results or not all(row["equal"] for row in results):
        raise SystemExit("complete CLI formatting differed or no refusal was exercised")
