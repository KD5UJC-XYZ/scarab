# SPDX-License-Identifier: Apache-2.0
"""Check provenance and replay the context corpus through unchanged released Haskell APIs offline."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(HERE))
from prepare import prepare

DIRECTORY = ROOT / "fixtures/reference/context-errors"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_submission(case):
    reference, context = case["reference"], case["contextCase"]
    auxiliary = bytes.fromhex(case["auxiliary"]) if case.get("auxiliary") else b"\xf6"
    original = (b"\x84" + bytes.fromhex(case["body"]) + bytes.fromhex(case["witnesses"])
                + (b"\xf5" if case["isValid"] else b"\xf4") + auxiliary)
    require(original.hex() == context["transaction"], "submission lost original transaction bytes")
    require(hashlib.blake2b(bytes.fromhex(case["body"]), digest_size=32).hexdigest() == case["txId"],
            "submission body hash differs")
    if reference["accepted"]:
        require(reference["reasonCbor"] is None, "accepted submission has refusal bytes")
        proof = context.get("inclusion", {})
        require(proof.get("txId") == case["txId"], "inclusion is not bound to the submitted transaction")
        require(proof.get("confirmedBlock", -1) - proof.get("firstSharedBlock", 0)
                >= proof.get("securityParam", 0) + 1, "missing shared k+1 inclusion")
        require(proof.get("securityParam", 0) > 0 and len(proof.get("sharedTip", "")) == 64,
                "incomplete shared-chain proof")
        require({row["node"] for row in context["broadcast"]} == {1, 2, 3}, "missing identical-byte broadcast")
        require(case["isValid"] or proof.get("collateralSpent") is True,
                "invalid-tag inclusion lacks collateral disposition")
    else:
        require(bool(reference["reasonCbor"]), "refusal lacks raw ApplyTxErr bytes")
        require("inclusion" not in context, "refused submission has an inclusion claim")


def validate(directory=DIRECTORY, check_manifest=True):
    directory = Path(directory)
    if check_manifest:
        manifest = json.loads((directory / "manifest.json").read_text())
        for name, digest in manifest["files"].items():
            path = (directory / name).resolve()
            require(path.is_relative_to(directory.resolve()), "manifest path escapes corpus")
            require(hashlib.sha256(path.read_bytes()).hexdigest() == digest, f"hash mismatch: {name}")
        provenance = json.loads((directory / "provenance.json").read_text())
        for name, digest in provenance["oracleSourcesSha256"].items():
            path = (ROOT / name).resolve()
            require(path.is_relative_to(ROOT), "oracle source path escapes checkout")
            require(hashlib.sha256(path.read_bytes()).hexdigest() == digest, f"oracle source changed: {name}")
    submissions = directory / "submissions/conway-pv10.json"
    actual = json.loads(submissions.read_text())
    require(len(actual) == 24 and sum(case["reference"]["accepted"] for case in actual) == 12,
            "expected 24 actual submissions with 12 admissions and 12 refusals")
    for case in actual:
        validate_submission(case)
    expected = prepare(submissions, ROOT / "fixtures/reference/v1-context")
    cases = json.loads((directory / "collection.json").read_text())
    require(len(cases) == 145, "incomplete context API corpus")
    require([{key: value for key, value in case.items() if key != "oracle"} for case in cases] == expected,
            "API input or original/derived provenance differs from reproducible preparation")
    for case in cases:
        oracle = case["oracle"]
        require(oracle["name"] == case["name"] and type(oracle["collected"]) is bool,
                "oracle name or result domain differs")
        require(not case["provenance"]["ledgerAdmissionClaim"], "API proof mislabelled as ledger admission")
        if case["provenance"]["kind"] == "api-only-derived-transaction":
            require(case["provenance"]["signaturesRevalidated"] is False,
                    "derived API input falsely claims signature revalidation")
        if not oracle["collected"]:
            require(bool(oracle["collectErrorsCbor"]) and bool(oracle["utxosFailureCbor"]),
                    "collection failure lacks reference encodings")
    return cases


def replay(cases, image, output=None):
    with tempfile.TemporaryDirectory(prefix="scarab-context-oracle-") as scratch:
        path = Path(scratch) / "cases.json"
        path.write_text(json.dumps(cases))
        result = subprocess.run(["docker", "run", "--rm", "--network", "none", "--read-only",
                                 "-v", f"{path}:/cases.json:ro", image, "/cases.json"],
                                text=True, capture_output=True, check=True)
    measured = json.loads(result.stdout)
    require(measured == [case["oracle"] for case in cases], "released Haskell API verdict/CBOR/order differs")
    if output:
        Path(output).write_text(json.dumps(measured, indent=1) + "\n")
    print(f"PASS: {len(cases)} unchanged-reference API results, encodings and script order")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="scarab-plutus176-context-reference:local")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    corpus = validate()
    if args.verify_only:
        print(f"PASS: {len(corpus)} byte-bound original/derived inputs and actual submission evidence")
    else:
        replay(corpus, args.image, args.out)
