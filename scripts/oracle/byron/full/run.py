# SPDX-License-Identifier: Apache-2.0
"""Replay retained complete Byron registration fixtures with the pinned unchanged reference APIs."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "scripts"))
from oracle.byron.full.boundaries import replace

FIXTURES = ROOT / "fixtures/byron-version/full-registration"


def validate(directory):
    """Bind original bytes, scope, independent crypto controls and every retained verdict."""
    directory = Path(directory)
    manifest = json.loads((directory / "provenance.json").read_text())
    for row in manifest["files"]:
        raw = (directory / row["path"]).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == row["sha256"], row["path"]
    cases = json.loads((directory / "cases.json").read_text())
    report = json.loads((directory / "reference.json").read_text())
    rows = {row["caseName"]: row for row in report["results"]}
    names = {row["name"] for row in cases}
    widths = {"major-65536", "minor-65536", "alt-256", "major-4294967297"}
    expected = {f"transition-{i}" for i in range(11)} | {
        "invalid-proposal-signature", "unauthorised-proposer", "invalid-header-signature"} | {
        location + "-" + width for location in ("header", "proposal") for width in widths}
    assert len(cases) == len(report["results"]) == len(expected) == len(rows)
    assert names == rows.keys() == expected
    transitions = json.loads((directory.parent / "registration.json").read_text())["cases"]
    for i, source in enumerate(transitions):
        case = cases[i]
        assert case["name"] == f"transition-{i}" and case["adopted"] == source["adopted"]
        wanted = "Accepted" if source["accepted"] else "InvalidVersion"
        row = rows[case["name"]]
        for field in ("registration", "admission", "ledgerBlock", "compositeBlock"):
            assert row[field]["verdict"] == wanted, (case["name"], field)
        assert row["headerSignature"] is True and row["certificateSignature"] is True
        assert row["transactionAdmission"]["verdict"] == "Accepted"
        if source["accepted"]:
            assert row["registeredVersions"] == row["admissionVersions"] == row["blockVersions"] == [source["proposed"]]
            assert row["inputSpent"] is True and row["outputPresent"] is True
    for name, wanted in (("invalid-proposal-signature", "InvalidSignature"),
                         ("unauthorised-proposer", "Unauthorised")):
        row = rows[name]
        for field in ("registration", "admission", "ledgerBlock", "compositeBlock"):
            assert row[field]["verdict"] == wanted
        assert row["headerSignature"] is True and row["certificateSignature"] is True
        assert row["transactionAdmission"]["verdict"] == "Accepted"
    header = rows["invalid-header-signature"]
    assert header["headerSignature"] is False and header["certificateSignature"] is True
    assert header["ledgerBlock"]["verdict"] == "Accepted"
    assert header["compositeBlock"]["verdict"] == "InvalidSignature"
    for location in ("header", "proposal"):
        for width in widths:
            row = rows[location + "-" + width]
            assert row["blockDecode"]["verdict"] == "DecodeFailure"
            assert row["compositeBlock"]["verdict"] == "NotRun"
            if location == "proposal":
                assert row["proposalDecode"]["verdict"] == "DecodeFailure"
                assert row["proposalSignature"] is None
    by_name = {row["name"]: row for row in cases}
    mutations = json.loads((directory / "mutations.json").read_text())
    assert len(mutations) == 8
    for mutation in mutations:
        source = by_name[mutation["source"]]
        case = by_name[mutation["name"]]
        original = bytes.fromhex(source["block"])
        changed, span = replace(original, mutation["path"], mutation["version"])
        assert span == {key: mutation[key] for key in span}
        assert changed == bytes.fromhex(case["block"])
        assert hashlib.sha256(original).hexdigest() == mutation["sourceBlockSha256"]
        assert hashlib.sha256(changed).hexdigest() == mutation["blockSha256"]
        proposal = bytes.fromhex(source["proposal"])
        if mutation["name"].startswith("proposal-"):
            proposal, _ = replace(proposal, [0], mutation["version"])
        assert proposal == bytes.fromhex(case["proposal"])
    for row in rows.values():
        assert all(value.get("verdict") != "OtherFailure" for value in row.values() if isinstance(value, dict))
    return report


def replay(image, output):
    expected = validate(FIXTURES)
    result = subprocess.run([
        "docker", "run", "--rm", "--network", "none", "--read-only",
        "-v", f"{FIXTURES}:/fixtures:ro", image, "replay", "/fixtures",
    ], text=True, capture_output=True, check=True)
    actual = json.loads(result.stdout)
    assert actual == expected, "reference replay differs from retained original-byte verdicts"
    if output:
        Path(output).write_text(json.dumps(actual, indent=2) + "\n")
    print(f"Matched {len(actual['results'])} complete Byron reference cases")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="scarab-byron171-full-reference:local")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    replay(args.image, args.output)
