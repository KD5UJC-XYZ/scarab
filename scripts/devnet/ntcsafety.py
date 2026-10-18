# SPDX-License-Identifier: Apache-2.0
"""LocalStateQuery nesting and exact hard-fork constructors (REF-05 / REF-08).

The singleton constructors follow ouroboros-consensus 82ecba3:
HardFork/Combinator/Ledger/Query.hs:536-551 and Serialisation/SerialiseNodeToClient.hs:410-417.
The deep query goes only to Scala; its leaf answer is compared with a shallow reference query
at the same acquired point. Python builds the query and peels CBOR-in-CBOR without recursion.
"""
import socket
import threading

from . import ntcquery
from .cbor import enc

DEPTH = 20000
INVALID = {
    "anytime-empty": [0, [1, [], 6]],
    "anytime-wrong-tag": [0, [1, [1], 6]],
    "anytime-extra-inner-field": [0, [1, [0, 0], 6]],
    "anytime-missing-era": [0, [1, [0]]],
    "anytime-extra-outer-field": [0, [1, [0], 6, 0]],
    "hardfork-empty": [0, [2, []]],
    "hardfork-wrong-tag": [0, [2, [2]]],
    "interpreter-extra-inner-field": [0, [2, [0, 0]]],
    "current-era-extra-inner-field": [0, [2, [1, 0]]],
    "hardfork-missing-query": [0, [2]],
    "hardfork-extra-outer-field": [0, [2, [1], 0]],
}
VALID = {
    "era-start": [0, [1, [0], 6]],
    "interpreter": [0, [2, [0]]],
    "current-era": [0, [2, [1]]],
}


def encoded_query(era, tag, depth):
    """BlockQuery(QueryIfCurrent(era, GetCBOR^depth(query tag)))."""
    return b"\x82\x00\x82\x00\x82" + enc(era) + b"\x82\x09" * depth + enc([tag])


def query_encoded(session, raw):
    """Return the original result item, without recursively encoding the query AST."""
    session._send(7, b"\x82\x03" + raw)
    reply = session._recv(7)
    if not isinstance(reply, list) or len(reply) != 2 or reply[0] != 4:
        raise RuntimeError("expected LocalStateQuery Result")
    if not session.last_raw.startswith(b"\x82\x04"):
        raise RuntimeError("expected definite LocalStateQuery Result envelope")
    return session.last_raw[2:]


def unwrap(raw, depth):
    """Peel the reference's tag-24 byte strings; check every embedded item's full length."""
    for _ in range(depth):
        item, end = ntcquery.decode(raw)
        if end != len(raw) or not isinstance(item, ntcquery.Tagged) or item.tag != 24 \
                or not isinstance(item.value, bytes):
            raise RuntimeError("malformed GetCBOR answer")
        raw = item.value
    return raw


def _single(raw):
    if not raw.startswith(b"\x81"):
        raise RuntimeError("expected successful singleton QueryIfCurrent answer")
    return raw[1:]


def _closed(opener, raw):
    # A malformed query must close this connection. Bound a refusal probe so a decoder waiting
    # forever for more fields fails the scenario instead of blocking it.
    with opener() as session:
        expired = threading.Event()

        def timeout():
            expired.set()
            if session.host:
                try:
                    session.proc.sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            session.proc.kill()

        timer = threading.Timer(15, timeout)
        timer.start()
        try:
            session.acquire()
            try:
                query_encoded(session, raw)
                return {"closed": False, "timedOut": False}
            except (EOFError, ConnectionResetError, BrokenPipeError):
                return {"closed": not expired.is_set(), "timedOut": expired.is_set()}
        finally:
            timer.cancel()


def run(open_reference, open_scala):
    """(recorded results, gate failures), with a fresh connection for each refusal."""
    report, failures = {"depth": DEPTH, "valid": {}, "invalid": {}}, []
    with open_scala() as scala, open_reference() as reference:
        scala.acquire()
        point = scala.query_raw([3])
        reference.acquire(point)
        era = scala.query_raw(VALID["current-era"])
        for label, query in VALID.items():
            same = query_encoded(scala, enc(query)) == query_encoded(reference, enc(query))
            report["valid"][label] = same
            if not same:
                failures.append(f"query safety: valid {label} differs from reference")
        plain = _single(query_encoded(reference, encoded_query(era, 0, 0)))
        deep = _single(query_encoded(scala, encoded_query(era, 0, DEPTH)))
        report["deepAnswerEqual"] = unwrap(deep, DEPTH) == plain
        if not report["deepAnswerEqual"]:
            failures.append("query safety: deep GetCBOR differs from shallow reference answer")

    for label, query in INVALID.items():
        row = {"reference": _closed(open_reference, enc(query)), "scala": _closed(open_scala, enc(query))}
        report["invalid"][label] = row
        if not all(result["closed"] for result in row.values()):
            failures.append(f"query safety: invalid {label} not refused by both nodes: {row}")
    # This path exercises the refusal diagnostic after decoding the deep AST, too.
    report["deepUnsupported"] = _closed(open_scala, encoded_query(era, 14, DEPTH))
    if not report["deepUnsupported"]["closed"]:
        failures.append("query safety: deep unsupported query did not close its connection")
    with open_scala() as scala:
        scala.acquire()
        report["healthyAfterRefusals"] = scala.query_raw(VALID["current-era"]) == era
    if not report["healthyAfterRefusals"]:
        failures.append("query safety: Scala node did not answer after refusal probes")
    return report, failures
