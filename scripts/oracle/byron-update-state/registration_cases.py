#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Cases for the Byron software registration oracle (#748).

Each case is a software update proposal (its installer metadata's system tags, its application name
and number, the protocol version it proposes) and the state it is registered against (the
application versions and the registered software proposals). The harness (`Main.hs register`)
signs it with a throwaway key derived from a fixed seed and runs `Registration.registerProposal`
(`Cardano/Chain/Update/Validation/Registration.hs`, cardano-ledger f649f975). The cases put the
system tags and application names at and past the length bounds (10 and 12 characters, counted in
code points), make them non-ASCII, and make several checks fail at once, to pin the order of
`registerSoftwareUpdate`: the system tags in key order, the duplicate, the application name, the
version.

`--inputs` writes the harness input; `--reference` with `--out` merges the harness output into
`cases.jsonl`: a line per case with its description, the proposal's bytes and the verdict, and for
an accepted one the registered software proposals after it (`registeredAfter`: name, number and
system tags).
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cases import GENESIS, genesis_parameters  # noqa: E402

PARAMETERS = dict(genesis_parameters(GENESIS["mainnet"]), maxProposalSize=100000)
EMOJI = "\U0001f600"


def case(name, tags=(), app="cardano-sl", number=1, version=(0, 0, 0), apps=(), registered=()):
    return {
        "name": name,
        "tags": list(tags),
        "app": app,
        "number": number,
        "version": list(version),
        "adoptedParameters": PARAMETERS,
        "appVersions": [{"name": n, "number": k} for n, k in apps],
        "registered": [{"name": n, "number": k} for n, k in registered],
    }


def cases():
    return [
        case("accepted", tags=["linux", "win64", "macos64"]),
        case("no-metadata"),
        # system tags: length in code points, then ASCII
        case("tag-empty", tags=[""]),
        case("tag-10-ascii", tags=["a" * 10]),
        case("tag-11-ascii", tags=["a" * 11]),
        case("tag-del-is-ascii", tags=["a\x7f"]),
        case("tag-u0080", tags=["a\x80"]),
        case("tag-accented", tags=["linué"]),
        case("tag-10-emoji", tags=[EMOJI * 10]),
        case("tag-11-emoji", tags=[EMOJI * 11]),
        case("tag-6-emoji-is-12-utf16-units", tags=[EMOJI * 6]),
        case("tag-11-accented", tags=["é" * 11]),
        # application names: length in code points, then ASCII
        case("name-empty", app=""),
        case("name-12-ascii", app="a" * 12),
        case("name-13-ascii", app="a" * 13),
        case("name-accented", app="cardano-sé"),
        case("name-12-emoji", app=EMOJI * 12),
        case("name-13-emoji", app=EMOJI * 13),
        case("name-7-emoji-is-14-utf16-units", app=EMOJI * 7),
        case("name-13-accented", app="é" * 13),
        # the order of the checks
        case("two-bad-tags-first-in-key-order", tags=["zzzzzzzzzzzz", "aé"]),
        case("two-bad-tags-utf8-order", tags=["�", EMOJI * 2]),
        case("bad-tag-then-bad-name", tags=["a" * 11], app="a" * 13),
        case("bad-tag-then-duplicate", tags=["aé"], registered=[("cardano-sl", 1)]),
        case("duplicate-then-bad-name", app="a" * 13, registered=[("a" * 13, 1)]),
        case("bad-name-then-version", app="a" * 13, number=5),
        case("version-not-next", number=5),
        case("version-follows-an-application", number=3, apps=[("cardano-sl", 2)]),
        case("duplicate", registered=[("cardano-sl", 1)]),
        # an unchanged software version is not registered, so its name and tags are not checked
        case("unchanged-software-not-checked", tags=["a" * 11], app="a" * 13, number=2,
             version=(0, 1, 0), apps=[("a" * 13, 2)]),
        case("unchanged-software-null", app="a" * 13, number=2, apps=[("a" * 13, 2)]),
    ]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--inputs", required=True, type=Path)
    ap.add_argument("--reference", type=Path)
    ap.add_argument("--out", type=Path)
    args = ap.parse_args()
    all_cases = cases()
    names = [c["name"] for c in all_cases]
    assert len(names) == len(set(names)), "case names repeat"
    if args.reference is None:
        args.inputs.write_text(json.dumps(all_cases, ensure_ascii=False) + "\n", encoding="utf-8")
        return
    if args.out is None:
        ap.error("--reference needs --out")
    results = json.loads(args.reference.read_text(encoding="utf-8"))
    if len(results) != len(all_cases):
        raise SystemExit(f"{len(results)} results for {len(all_cases)} cases")
    lines = []
    for c, r in zip(all_cases, results):
        if r.get("name") != c["name"] or "proposal" not in r:
            raise SystemExit(f"{c['name']}: unexpected result {r}")
        row = dict(c)
        row.update({k: r[k] for k in ("proposal", "accepted", "rule", "text", "failure") if k in r})
        if "registered" in r:
            row["registeredAfter"] = r["registered"]
        lines.append(json.dumps(row, ensure_ascii=False, sort_keys=True))
    args.out.write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
