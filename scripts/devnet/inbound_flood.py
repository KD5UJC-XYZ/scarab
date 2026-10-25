#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Connection-slot probe for the `inbound-flood` scenario: opens many connections to a node's
listener and observes how long the node keeps them, while a peer on another address connects.

    python3 inbound_flood.py --target HOST:PORT --magic N --source 127.0.0.2 --legit-source 127.0.0.3 \
        --connections 64 --mode handshake|silent [--limit-seconds 40]

`handshake` completes a node-to-node handshake (version 14, initiator only) on every connection and
then sends nothing; `silent` sends nothing at all. A legitimate peer (handshake, one KeepAlive round
trip) connects from `--legit-source` while the flood is open, and again once every flood connection
has been closed by the node. Prints one JSON object. Standard library only; it runs in the network
namespace of the node.
"""
import argparse
import asyncio
import json
import struct
import time

PROTOCOL_HANDSHAKE = 0
PROTOCOL_KEEPALIVE = 8
VERSION = 14


def uint(value):
    if value < 24:
        return bytes([value])
    for marker, size in ((0x18, 1), (0x19, 2), (0x1A, 4), (0x1B, 8)):
        if value < 1 << (8 * size):
            return bytes([marker]) + value.to_bytes(size, "big")
    raise ValueError(value)


def sdu(protocol, payload):
    return struct.pack(">IHH", int(time.monotonic() * 1e6) & 0xFFFFFFFF, protocol, len(payload)) + payload


def propose(magic):
    # [0, {14: [magic, initiatorOnly = true, peerSharing = 0, query = false]}]
    return sdu(PROTOCOL_HANDSHAKE, b"\x82\x00\xa1" + uint(VERSION) + b"\x84" + uint(magic) + b"\xf5\x00\xf4")


async def read_sdu(reader, timeout):
    header = await asyncio.wait_for(reader.readexactly(8), timeout)
    _, mode_protocol, length = struct.unpack(">IHH", header)
    payload = await asyncio.wait_for(reader.readexactly(length), timeout) if length else b""
    return mode_protocol & 0x7FFF, payload


async def connect(host, port, source):
    return await asyncio.wait_for(asyncio.open_connection(host, port, local_addr=(source, 0)), 5)


async def handshake(reader, writer, magic):
    """True when the node accepted version 14; False when it refused or closed."""
    writer.write(propose(magic))
    await writer.drain()
    try:
        protocol, payload = await read_sdu(reader, 10)
    except (asyncio.IncompleteReadError, ConnectionError, asyncio.TimeoutError):
        return False
    return protocol == PROTOCOL_HANDSHAKE and payload[:3] == b"\x83\x01" + uint(VERSION)


async def legitimate(host, port, source, magic):
    """A peer on its own address: handshake and one KeepAlive round trip."""
    began = time.monotonic()
    try:
        reader, writer = await connect(host, port, source)
        if not await handshake(reader, writer, magic):
            return {"connected": False, "reason": "handshake not accepted"}
        cookie = 0x1234
        writer.write(sdu(PROTOCOL_KEEPALIVE, b"\x82\x00" + uint(cookie)))
        await writer.drain()
        protocol, payload = await read_sdu(reader, 10)
        writer.close()
        ok = protocol == PROTOCOL_KEEPALIVE and payload == b"\x82\x01" + uint(cookie)
        return {"connected": ok, "seconds": round(time.monotonic() - began, 3)}
    except (OSError, asyncio.IncompleteReadError, asyncio.TimeoutError) as e:
        return {"connected": False, "reason": type(e).__name__}


async def flood_one(host, port, source, magic, mode, started, outcome, index):
    try:
        reader, writer = await connect(host, port, source)
    except (OSError, asyncio.TimeoutError):
        outcome[index] = {"accepted": False, "closedAfter": None}
        return
    if mode == "handshake" and not await handshake(reader, writer, magic):
        outcome[index] = {"accepted": False, "closedAfter": round(time.monotonic() - started, 3)}
        writer.close()
        return
    since = time.monotonic()
    try:
        # nothing is ever sent after this point: wait for the node to close
        while True:
            data = await reader.read(4096)
            if not data:
                break
    except (OSError, asyncio.IncompleteReadError):
        pass
    held = round(time.monotonic() - since, 3)
    # a silent connection the node refuses is closed at once: it never held a slot
    outcome[index] = {"accepted": mode == "handshake" or held > 1.0, "closedAfter": held}
    writer.close()


async def main(args):
    host, port = args.target.rsplit(":", 1)
    port = int(port)
    started = time.monotonic()
    outcome = {}
    tasks = [asyncio.create_task(flood_one(host, port, args.source, args.magic, args.mode, started, outcome, i))
             for i in range(args.connections)]
    await asyncio.sleep(1.5)
    during = await legitimate(host, port, args.legit_source, args.magic)
    try:
        await asyncio.wait_for(asyncio.gather(*tasks), args.limit_seconds)
    except asyncio.TimeoutError:
        pass
    still_open = sum(1 for i in range(args.connections) if i not in outcome)
    for task in tasks:
        task.cancel()
    after = await legitimate(host, port, args.legit_source, args.magic)
    reopened = await legitimate(host, port, args.source, args.magic)
    accepted = [o for o in outcome.values() if o["accepted"]]
    times = sorted(o["closedAfter"] for o in accepted)
    print(json.dumps({
        "mode": args.mode, "connections": args.connections,
        "accepted": len(accepted), "refused": len(outcome) - len(accepted),
        "stillOpenAtLimit": still_open,
        "closedAfterSeconds": {"min": times[0], "max": times[-1]} if times else None,
        "legitimateDuringFlood": during, "legitimateAfterFlood": after,
        "floodAddressReconnectsAfterRelease": reopened,
    }))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--target", required=True)
    parser.add_argument("--magic", type=int, required=True)
    parser.add_argument("--source", default="127.0.0.2")
    parser.add_argument("--legit-source", default="127.0.0.3")
    parser.add_argument("--connections", type=int, default=64)
    parser.add_argument("--mode", choices=["handshake", "silent"], default="handshake")
    parser.add_argument("--limit-seconds", type=float, default=40)
    asyncio.run(main(parser.parse_args()))
