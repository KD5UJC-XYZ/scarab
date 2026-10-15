# SPDX-License-Identifier: Apache-2.0
"""The known gaps of the Scala node's ledger-state answers, and the comparison that allows exactly them.

`DebugNewEpochState` and `DebugEpochState` are answered from the mirror of the Haskell state
(`MirrorAnswers.scala`). The mirror cannot yet hold everything the Haskell state holds (roadmap: ledger-state
mirror, step 2), so on a devnet the Scala answer is byte-equal to the reference's except inside the places
listed in RULES. The comparison here blanks those places in both answers (a span of the original bytes, found
without decoding the rest) and compares every other byte. It fails when

  - a listed place is missing, or is not the kind of item the rule expects, in either answer;
  - the masked bytes are more than MAX_SHARE of the answer (the mask would be hiding more than the gaps);
  - any byte outside the masked places differs.

What was masked is reported per rule (answers, items, bytes), so a gap that stops being one shows as a rule
that masks nothing different.
"""
from dataclasses import dataclass, field

from . import cborspan as C

PLACEHOLDER = b"\xf7"
MAX_SHARE = 0.25

ARRAY, MAP, UINT = 4, 5, 0


@dataclass(frozen=True)
class Rule:
    """A place in the `NewEpochState` (Conway layout) the Scala answer does not yet fill as the reference does."""
    name: str
    path: tuple   # from the NewEpochState; in an `EpochState` answer the leading 3 (`nesEs`) is dropped
    major: int    # the CBOR major type of every item the path selects
    why: str
    only_if: object = None  # a predicate on the reference's NewEpochState bytes, or None: always


def _reference_update_present(nes):
    """The reference holds a reward update (`StrictMaybe`: `[]` is none)."""
    span = C.resolve(nes, [4])[0]
    return nes[span[0]] != 0x80


RULES = (
    Rule("NonMyopic", (3, 3), ARRAY,
         "the non-myopic likelihoods (floating-point values accumulated per epoch, `NonMyopic.hs`) and the "
         "reward pot are not tracked: the likelihoods are empty and the pot is 0"),
    Rule("SnapshotNumDelegators", (3, 2, (0, 1, 2), 1, "v*", 8), UINT,
         "`numDelegators` of the pools of the mark, set and go snapshots: counted from the accounts as they "
         "are now (mark) or only from the stake-bearing delegators (set, go), where the reference counted at "
         "the boundary the snapshot was taken"),
    Rule("VrfKeyHashes", (3, 1, 0, 1, 0), MAP,
         "`psVRFKeyHashes` of the pools the genesis registers: the reference leaves the genesis pools out of "
         "the registry, the mirror derives it from every pool"),
    Rule("RewardUpdate", (4,), ARRAY,
         "the reward update, while the reference holds one: it is pulsing there (the pulser state) and "
         "computed eagerly here, and its per-pool rewards (`rs`) are not attributed",
         _reference_update_present),
)

# the rule names, in the order of RULES (the documented gap list)
GAPS = tuple(r.name for r in RULES)

LABELS = ("DebugEpochState", "DebugNewEpochState", "GetCBOR/DebugEpochState", "GetCBOR/DebugNewEpochState")


def applies(label):
    return label in LABELS


@dataclass
class Verdict:
    equal: bool
    raw_equal: bool
    problems: list = field(default_factory=list)
    masked: dict = field(default_factory=dict)  # rule name -> {"items": n, "referenceBytes": n, "scalaBytes": n}


def _roots(raw, wrapped):
    """(prefix to compare, the state's bytes) of an answer `[4, [state]]`, or of the `GetCBOR` form
    `[4, [#6.24(bytes)]]`."""
    state = C.resolve(raw, [1, 0])[0]
    if not wrapped:
        return raw[:state[0]], raw[state[0]:state[1]]
    if raw[state[0]:state[0] + 2] != b"\xd8\x18":
        raise C.Malformed("GetCBOR answer is not #6.24")
    content = C.resolve(raw, [1, 0, 0])[0]
    return raw[:state[0] + 2], C.payload(raw, content)


def _blank(state, paths):
    """(the state with the selected spans replaced, the spans). Overlapping spans are not expected."""
    spans = sorted(s for path in paths for s in path)
    out, at = bytearray(), 0
    for start, end in spans:
        if start < at:
            raise C.Malformed("masked places overlap")
        out += state[at:start] + PLACEHOLDER
        at = end
    out += state[at:]
    return bytes(out)


def _select(state, rule, estate):
    path = rule.path[1:] if estate else rule.path
    return C.resolve(state, path) if path else [(0, C.item_end(state, 0))]


def compare(label, reference, scala):
    """Compare the two answers of a ledger-state query, allowing the gaps of RULES and nothing else."""
    wrapped = label.startswith("GetCBOR/")
    estate = label.endswith("DebugEpochState")
    verdict = Verdict(False, reference == scala)
    try:
        ref_prefix, ref_state = _roots(reference, wrapped)
        scala_prefix, scala_state = _roots(scala, wrapped)
    except (C.Malformed, IndexError) as e:
        verdict.problems.append(f"not a ledger-state answer: {e}")
        return verdict
    if ref_prefix != scala_prefix:
        verdict.problems.append("the answers differ before the state")
    ref_spans, scala_spans = [], []
    for rule in RULES:
        if estate and rule.path[0] != 3:
            continue  # the reward update is a field of the NewEpochState, which an EpochState does not have
        try:
            if rule.only_if is not None and not rule.only_if(ref_state):
                continue
            r, s = _select(ref_state, rule, estate), _select(scala_state, rule, estate)
        except (C.Malformed, IndexError) as e:
            verdict.problems.append(f"{rule.name}: the place is missing ({e})")
            continue
        for answer, spans in (("reference", r), ("scala", s)):
            data = ref_state if answer == "reference" else scala_state
            for start, end in spans:
                if C.head(data, start)[0] != rule.major:
                    verdict.problems.append(f"{rule.name}: not the expected kind of item in the {answer} answer")
        if len(r) != len(s):
            verdict.problems.append(f"{rule.name}: {len(r)} places in the reference, {len(s)} in the Scala answer")
        ref_spans.append(r)
        scala_spans.append(s)
        verdict.masked[rule.name] = {
            "items": len(r),
            "differing": sum(1 for a, b in zip(r, s) if ref_state[a[0]:a[1]] != scala_state[b[0]:b[1]]),
            "referenceBytes": sum(e - b for b, e in r),
            "scalaBytes": sum(e - b for b, e in s),
        }
    try:
        ref_masked, scala_masked = _blank(ref_state, ref_spans), _blank(scala_state, scala_spans)
    except C.Malformed as e:
        verdict.problems.append(str(e))
        return verdict
    hidden = sum(m["referenceBytes"] for m in verdict.masked.values())
    if hidden > MAX_SHARE * len(ref_state):
        verdict.problems.append(f"the mask hides {hidden} of {len(ref_state)} bytes (more than {MAX_SHARE:.0%})")
    if ref_masked != scala_masked:
        verdict.problems.append("the answers differ outside the masked places")
    verdict.equal = not verdict.problems
    return verdict


def add_to(total, verdict):
    """Accumulate a verdict into `total` (a label's row): answers compared, byte-equal without the mask,
    equal with it, and per rule the answers it applied to, the places masked, those that differed, the bytes."""
    total["answers"] = total.get("answers", 0) + 1
    total["byteEqual"] = total.get("byteEqual", 0) + int(verdict.raw_equal)
    total["equalMasked"] = total.get("equalMasked", 0) + int(verdict.equal)
    rules = total.setdefault("rules", {})
    for name, m in verdict.masked.items():
        row = rules.setdefault(name, {"answers": 0, "items": 0, "differing": 0, "referenceBytes": 0, "scalaBytes": 0})
        row["answers"] += 1 if m["items"] else 0
        row["items"] += m["items"]
        row["differing"] += m["differing"]
        row["referenceBytes"] += m["referenceBytes"]
        row["scalaBytes"] += m["scalaBytes"]
