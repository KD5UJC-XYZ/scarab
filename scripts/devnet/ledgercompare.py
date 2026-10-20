# SPDX-License-Identifier: Apache-2.0
"""Exact comparison of same-point ledger-state answers.

Every received byte participates. Historical gaps in Scarab state are differences,
not permission to blank fields before deciding whether a reference check passed.
"""
from dataclasses import dataclass, field

from . import cborspan as C

LABELS = ("DebugEpochState", "DebugNewEpochState", "GetCBOR/DebugEpochState", "GetCBOR/DebugNewEpochState")


@dataclass
class Verdict:
    equal: bool
    raw_equal: bool
    problems: list = field(default_factory=list)


def applies(label):
    return label in LABELS


def _state(raw, wrapped, epoch_state):
    if C.item_end(raw, 0) != len(raw):
        raise C.Malformed("bytes after the answer")
    if C.head(raw, 0)[:2] != (4, 2):
        raise C.Malformed("answer is not a two-element array")
    fields = C.children(raw, 0)
    if raw[fields[0][0]:fields[0][1]] != b"\x04":
        raise C.Malformed("answer is not MsgResult")
    result = fields[1][0]
    if C.head(raw, result)[:2] != (4, 1):
        raise C.Malformed("hard-fork result is not a singleton array")
    state = C.children(raw, result)[0]
    if not wrapped:
        data = raw[state[0]:state[1]]
    else:
        if raw[state[0]:state[0] + 2] != b"\xd8\x18":
            raise C.Malformed("GetCBOR answer is not #6.24")
        content = C.resolve(raw, [1, 0, 0])[0]
        data = C.payload(raw, content)
    if C.head(data, 0)[:2] != (4, 4 if epoch_state else 7) or C.item_end(data, 0) != len(data):
        raise C.Malformed("ledger state is not one complete array of the expected arity")
    return data


def compare(label, reference, scala):
    """Validate framing and require complete wire-byte equality at the acquired point."""
    verdict = Verdict(False, reference == scala)
    if not applies(label):
        verdict.problems.append(f"not a ledger-state query: {label}")
        return verdict
    for name, raw in (("reference", reference), ("scala", scala)):
        try:
            _state(raw, label.startswith("GetCBOR/"), label.endswith("DebugEpochState"))
        except (C.Malformed, IndexError) as error:
            verdict.problems.append(f"{name}: not a ledger-state answer: {error}")
    if not verdict.raw_equal:
        verdict.problems.append("the complete ledger-state answer bytes differ")
    verdict.equal = verdict.raw_equal and not verdict.problems
    return verdict


def add_to(total, verdict):
    """Accumulate exact comparisons without a second, weaker success measure."""
    total["answers"] = total.get("answers", 0) + 1
    total["byteEqual"] = total.get("byteEqual", 0) + int(verdict.raw_equal)
    total["matched"] = total.get("matched", 0) + int(verdict.equal)
