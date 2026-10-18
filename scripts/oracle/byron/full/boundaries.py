# SPDX-License-Identifier: Apache-2.0
"""Original-byte width mutations of generated shallow Byron boundary fixtures."""
import hashlib
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from devnet.cbor import enc
from devnet.cborspan import resolve

WIDTHS = {
    "major-65536": [65536, 0, 0],
    "minor-65536": [65535, 65536, 0],
    "alt-256": [65535, 0, 256],
    "major-4294967297": [4294967297, 0, 0],
}
HEADER = [1, 0, 4, 0]
PROPOSAL = [1, 1, 3, 0, 0, 0]


def replace(raw, path, value):
    """Only the selected item changes; bytes on either side remain original."""
    [(start, end)] = resolve(raw, path)
    changed = enc(value)
    return raw[:start] + changed + raw[end:], {
        "path": path, "start": start, "end": end,
        "original": raw[start:end].hex(), "replacement": changed.hex(),
    }


def augment(directory):
    path = Path(directory) / "cases.json"
    cases = json.loads(path.read_text())
    if len(cases) != 14 or any(row["name"].startswith(("header-", "proposal-")) for row in cases):
        raise ValueError("expected exactly the fourteen original generated cases")
    source = next(row for row in cases if row["name"] == "transition-0")
    mutations = []
    for location, item_path in (("header", HEADER), ("proposal", PROPOSAL)):
        for label, value in WIDTHS.items():
            block, span = replace(bytes.fromhex(source["block"]), item_path, value)
            proposal = bytes.fromhex(source["proposal"])
            if location == "proposal":
                proposal, _ = replace(proposal, [0], value)
            name = location + "-" + label
            cases.append({"name": name, "adopted": source["adopted"],
                          "proposal": proposal.hex(), "block": block.hex()})
            mutations.append({"name": name, "source": source["name"], "version": value,
                              "sourceBlockSha256": hashlib.sha256(bytes.fromhex(source["block"])).hexdigest(),
                              "blockSha256": hashlib.sha256(block).hexdigest(), **span})
    path.write_text(json.dumps(cases, indent=2) + "\n")
    (Path(directory) / "mutations.json").write_text(json.dumps(mutations, indent=2) + "\n")
