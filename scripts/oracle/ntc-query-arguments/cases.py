#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""The cases of the node-to-client query argument oracle (#709).

Each case is the bytes of one argument of a ledger query and the decoder the reference's query
decoder reads it with (`decodeShelleyQuery`, ouroboros-consensus 82ecba32,
`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Query.hs:916-995`):

- `plain-*`: `fromCBOR` of cardano-binary, for `GetNonMyopicMemberRewards` (2), `GetStakePoolParams`
  (17), `GetPoolState`, `GetStakeSnapshots`, `GetPoolDistr`, `GetPoolDistr2` (19, 20, 21, 36),
  `GetStakeDelegDeposits` (22), `GetDRepState` (25) and the credentials of
  `GetCommitteeMembersState` (27). A set there is tag 258 around a definite list in strictly
  ascending order (`decodeSetSkel`), a `Maybe` a definite list of none or one (`decodeMaybe`), an
  `Either` a definite list of two (`decodeListLenOf 2`).
- `era-*`: `LC.fromEraCBOR @era`, the ledger's `decCBOR` at Conway's version 9, for the others: a set
  definite or indefinite, tagged or not, without duplicates (`decodeSetEnforceNoDuplicates`).
- `babbage-*`: the same at Babbage's version 7, where a set takes no tag and two elements alike are
  one (`decodeSet` below version 9).
- `era-proposals` and `dijkstra-proposals`: the set of `GovActionId` of `GetProposals` (31), a
  `RecD` record below version 12, which an empty indefinite list before its fields writes, and
  `decodeRecordNamed` over its fields from 12 (`Conway/Governance/Procedures.hs:182-190`).
- `dijkstra-*`: the ledger's decoders at Dijkstra's version 12, where a byte string may be chunked;
  `dijkstra-plain-pools` is `fromCBOR`, which no era changes (#720).

The committed corpus is `fixtures/reference/ntc-query-arguments/cases.jsonl`:

    python3 scripts/oracle/ntc-query-arguments/cases.py --inputs /tmp/ntc-query-arguments-inputs.json
    docker run --rm --network none --read-only -v /tmp:/in:ro \\
        scarab-ntc-query-arguments:local /in/ntc-query-arguments-inputs.json > /tmp/ntc-query-arguments.jsonl
    python3 scripts/oracle/ntc-query-arguments/cases.py --inputs /tmp/ntc-query-arguments-inputs.json \\
        --reference /tmp/ntc-query-arguments.jsonl --out fixtures/reference/ntc-query-arguments/cases.jsonl
"""
import argparse
import json
import struct

CASES = []


def head(major, n):
    if n < 24:
        return bytes([major << 5 | n])
    for limit, code, fmt in ((0xFF, 24, ">B"), (0xFFFF, 25, ">H"), (0xFFFFFFFF, 26, ">I")):
        if n <= limit:
            return bytes([major << 5 | code]) + struct.pack(fmt, n)
    return bytes([major << 5 | 27]) + struct.pack(">Q", n)


def uint(n):
    return head(0, n)


def bstr(b):
    return head(2, len(b)) + b


def arr(*xs):
    return head(4, len(xs)) + b"".join(xs)


def indef(*xs):
    return b"\x9f" + b"".join(xs) + b"\xff"


def tagged(x):
    return head(6, 258) + x


def h(fill, n=28):
    return bytes([fill]) * n


A, B = bstr(h(0x11)), bstr(h(0x22))
KEY_A, KEY_B = arr(uint(0), A), arr(uint(0), B)
SCRIPT_A = arr(uint(1), A)
TX = bstr(h(0x66, 32))


def case(decoder, name, raw):
    CASES.append({"name": f"{decoder} {name}", "decoder": decoder, "bytes": raw.hex()})


# --- fromCBOR of cardano-binary --------------------------------------------------------------
case("plain-pools", "tagged ascending", tagged(arr(A, B)))
case("plain-pools", "tagged empty", tagged(arr()))
case("plain-pools", "untagged", arr(A, B))
case("plain-pools", "tagged indefinite", tagged(indef(A, B)))
case("plain-pools", "tagged descending", tagged(arr(B, A)))
case("plain-pools", "tagged duplicate", tagged(arr(A, A)))
case("plain-pools", "tagged short hash", tagged(arr(bstr(h(0x11, 27)))))
case("plain-maybe-pools", "nothing", arr())
case("plain-maybe-pools", "just a set", arr(tagged(arr(A))))
case("plain-maybe-pools", "indefinite nothing", indef())
case("plain-maybe-pools", "indefinite just", indef(tagged(arr(A))))
case("plain-maybe-pools", "just an untagged set", arr(arr(A)))
case("plain-maybe-pools", "two sets", arr(tagged(arr(A)), tagged(arr(B))))
# a script credential is ordered before a key one (`data Credential = ScriptHashObj | KeyHashObj`)
case("plain-staking", "tagged ascending", tagged(arr(SCRIPT_A, KEY_A, KEY_B)))
case("plain-staking", "tagged with a key before a script", tagged(arr(KEY_A, SCRIPT_A)))
case("plain-staking", "untagged", arr(KEY_A))
case("plain-staking", "tagged indefinite", tagged(indef(KEY_A)))
case("plain-staking", "tagged with an indefinite credential", tagged(arr(indef(uint(0), A))))
case("plain-staking", "tagged duplicate", tagged(arr(KEY_A, KEY_A)))
case("plain-dreps", "tagged ascending", tagged(arr(SCRIPT_A, KEY_B)))
case("plain-dreps", "untagged", arr(KEY_A))
case("plain-cold", "tagged ascending", tagged(arr(KEY_A, KEY_B)))
case("plain-cold", "tagged descending", tagged(arr(KEY_B, KEY_A)))
case("plain-hot", "tagged ascending", tagged(arr(SCRIPT_A)))
case("plain-hot", "untagged", arr(SCRIPT_A))
LEFT1, LEFT5, RIGHT = arr(uint(0), uint(1)), arr(uint(0), uint(5)), arr(uint(1), KEY_A)
case("plain-nonmyopic", "tagged ascending", tagged(arr(LEFT1, LEFT5, RIGHT)))
case("plain-nonmyopic", "tagged with a credential before a coin", tagged(arr(RIGHT, LEFT1)))
case("plain-nonmyopic", "tagged descending coins", tagged(arr(LEFT5, LEFT1)))
case("plain-nonmyopic", "indefinite either", tagged(arr(indef(uint(0), uint(1)))))
case("plain-nonmyopic", "either of an unknown side", tagged(arr(arr(uint(2), uint(1)))))
case("plain-nonmyopic", "untagged", arr(LEFT1))
case("plain-nonmyopic", "tagged empty", tagged(arr()))

# --- the ledger's decoders at version 9 ----------------------------------------------------------
case("era-staking", "untagged", arr(KEY_B, KEY_A))
case("era-staking", "tagged", tagged(arr(KEY_A)))
case("era-staking", "indefinite", indef(KEY_A, SCRIPT_A))
case("era-staking", "tagged indefinite", tagged(indef(KEY_A)))
case("era-staking", "indefinite credential", arr(indef(uint(0), A)))
case("era-staking", "duplicate", arr(KEY_A, KEY_A))
TXIN0, TXIN1 = arr(TX, uint(0)), arr(TX, uint(1))
case("era-txins", "unordered", arr(TXIN1, TXIN0))
case("era-txins", "indefinite input", indef(indef(TX, uint(0))))
case("era-txins", "duplicate", arr(TXIN0, TXIN0))
case("era-dreps", "every kind", arr(arr(uint(3)), arr(uint(2)), SCRIPT_A, KEY_A))
case("era-dreps", "indefinite kinds", indef(indef(uint(2)), indef(uint(0), A)))
case("era-dreps", "duplicate", arr(arr(uint(2)), arr(uint(2))))
case("era-pools", "untagged indefinite", indef(B, A))
case("era-pools", "duplicate", arr(A, A))
case("era-statuses", "every status", arr(uint(2), uint(0), uint(1)))
case("era-statuses", "indefinite", indef(uint(1)))
case("era-statuses", "status 3", arr(uint(3)))
case("era-statuses", "duplicate", arr(uint(0), uint(0)))
case("era-pool", "key hash", A)
case("era-pool", "short key hash", bstr(h(0x11, 27)))
ADDRESS = bstr(b"\x61" + h(0x33))
case("era-addresses", "indefinite", indef(ADDRESS))
case("era-addresses", "duplicate", arr(ADDRESS, ADDRESS))
# below version 9 (Babbage's 7) a ledger set takes no tag, and two elements alike are one
case("babbage-txins", "untagged", arr(TXIN1, TXIN0))
case("babbage-txins", "duplicate", arr(TXIN0, TXIN0))
case("babbage-txins", "tagged", tagged(arr(TXIN0)))
case("babbage-txins", "indefinite", indef(TXIN0))
case("babbage-staking", "duplicate", arr(KEY_A, KEY_A, SCRIPT_A))
case("babbage-staking", "tagged", tagged(arr(KEY_A)))
# not an address: an enterprise header with a hash one byte short
case("era-addresses", "short address", arr(bstr(b"\x61" + h(0x33, 27))))


# --- GovActionId, a `RecD` record below version 12 and `decodeRecordNamed` from it (#720) --------
def chunked(b):
    """`b` as an indefinite byte string of one chunk."""
    return b"\x5f" + bstr(b) + b"\xff"


def one(item):
    """A definite list of one element, which may be written as several items (a detached record)."""
    return head(4, 1) + item


ACTION = arr(TX, uint(0))
for d in ("era-proposals", "dijkstra-proposals"):
    case(d, "definite", arr(ACTION))
    case(d, "tagged", tagged(arr(ACTION, arr(TX, uint(1)))))
    case(d, "indefinite record", arr(indef(TX, uint(0))))
    case(d, "detached record", one(b"\x9f\xff" + TX + uint(0)))
    case(d, "index of 16 bits", arr(arr(TX, uint(0xFFFF))))
    case(d, "index past 16 bits", arr(arr(TX, uint(0x10000))))
    case(d, "chunked transaction id", arr(arr(chunked(h(0x66, 32)), uint(0))))
    case(d, "duplicate", arr(ACTION, ACTION))

# --- the ledger's decoders at Dijkstra's version 12: a chunked byte string is read ---------------
CHUNK_A, CHUNK_B = chunked(h(0x11)), chunked(h(0x22))
case("era-pool", "chunked key hash", CHUNK_A)
case("dijkstra-pool", "key hash", A)
case("dijkstra-pool", "chunked key hash", CHUNK_A)
case("dijkstra-pools", "chunked", arr(CHUNK_B, CHUNK_A))
case("dijkstra-pools", "duplicate", arr(A, CHUNK_A))
case("dijkstra-staking", "untagged", arr(KEY_B, SCRIPT_A))
case("dijkstra-staking", "chunked credential", arr(arr(uint(0), CHUNK_A)))
case("dijkstra-staking", "indefinite credential", arr(indef(uint(1), A)))
case("dijkstra-staking", "credential of three", arr(arr(uint(0), A, uint(0))))
case("dijkstra-txins", "chunked input id", arr(arr(chunked(h(0x66, 32)), uint(1))))
case("dijkstra-txins", "duplicate", arr(TXIN0, TXIN0))
case("dijkstra-dreps", "every kind, chunked", arr(arr(uint(3)), arr(uint(2)), arr(uint(1), CHUNK_A)))
case("dijkstra-dreps", "abstain of two", arr(arr(uint(2), A)))
case("dijkstra-statuses", "every status", arr(uint(2), uint(0), uint(1)))
case("dijkstra-statuses", "status 3", arr(uint(3)))
case("dijkstra-addresses", "chunked", arr(chunked(b"\x61" + h(0x33))))
case("dijkstra-addresses", "duplicate", arr(ADDRESS, ADDRESS))
# a plain argument reads a definite string in every era
case("dijkstra-plain-pools", "tagged ascending", tagged(arr(A, B)))
case("dijkstra-plain-pools", "tagged chunked", tagged(arr(CHUNK_A)))


def merge(lines):
    """The corpus lines: each case with the reference's verdict and the values it read."""
    results = [json.loads(line) for line in lines if line.strip()]
    assert len(results) == len(CASES), "the harness answered a different number of cases"
    out = []
    for c, r in zip(CASES, results):
        assert (r["name"], r["decoder"]) == (c["name"], c["decoder"])
        res = r["result"]
        row = {"name": c["name"], "decoder": c["decoder"], "accepted": res["accepted"],
               "failure": res.get("failure"), "values": res.get("values"),
               "nothing": res.get("nothing"), "bytes": c["bytes"]}
        out.append(json.dumps({k: v for k, v in row.items() if v is not None}, separators=(",", ":")))
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--inputs")
    p.add_argument("--reference")
    p.add_argument("--out")
    a = p.parse_args()
    names = [c["name"] for c in CASES]
    assert len(names) == len(set(names))
    if a.inputs:
        with open(a.inputs, "w") as f:
            json.dump(CASES, f, indent=1)
            f.write("\n")
    if a.out:
        with open(a.reference) as f:
            lines = merge(f.read().splitlines())
        with open(a.out, "w") as f:
            f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
