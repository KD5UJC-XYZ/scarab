#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the script requests of recorded chain transactions through the pinned Haskell evaluator and record its verdicts.

The requests are the ledger's, written by `RecordedRequestsMain` in full into the evidence directory and by digest
into the fixture, which this runner checks against each other; the evaluator, its image and the shape of
requests and answers are the generated-program oracle's (`scripts/oracle/plutus/generated`), whose runner this one
loads unchanged and whose image it checks before use. `--verify-only` checks a retained fixture without Docker.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
DIRECTORY = ROOT / "fixtures/reference/plutus-recorded-requests"
_SPEC = importlib.util.spec_from_file_location("generated_run", HERE.parent / "generated" / "run.py")
GENERATED = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(GENERATED)
INPUTS = ("corpus.json", "models.jsonl", "requests.jsonl")
FILES = INPUTS + ("verdicts.jsonl",)
# The plutus source the harness Dockerfiles require unchanged at the pin.
PINNED_TREES = ("plutus-core", "plutus-ledger-api", "plutus-tx")


def era(protocol):
    return "Alonzo" if protocol < 7 else "Babbage" if protocol < 9 else "Conway"


def lines(path):
    return [json.loads(line) for line in path.read_text().splitlines()]


def sources(release):
    """The files whose digests bind the verdicts: the harness the image was built from and both runners."""
    harness = ROOT / release["harness"]
    return [harness / "Main.hs", harness / "Dockerfile", HERE.parent / "generated" / "run.py", HERE / "run.py"]


def check_image(release, owner):
    """The image holds the current harness source and the pinned, unchanged plutus source."""
    script = ("sha256sum /source/scarab-oracle/Main.hs | cut -d' ' -f1 && git rev-parse HEAD"
              f" && git diff --quiet --exit-code -- {' '.join(PINNED_TREES)} && echo unchanged")
    command = ["docker", "run", "--rm", "--network", "none", *GENERATED.LABELS,
               "--label", f"xyz.kd5ujc.scarab.owner={owner}", "--entrypoint", "sh", release["image"], "-c", script]
    measured = subprocess.run(command, capture_output=True, text=True, check=True).stdout.split()
    expected = [GENERATED.sha256(ROOT / release["harness"] / "Main.hs"), release["pin"], "unchanged"]
    if measured != expected:
        raise ValueError(f"{release['image']} is not the build of the current harness at {release['pin']}: {measured}")


def validate(fixtures):
    """A retained fixture: its files and runner sources are the ones its manifest binds, one verdict per request."""
    manifest = json.loads((fixtures / "manifest.json").read_text())
    for name in FILES:
        if GENERATED.sha256(fixtures / name) != manifest["sha256"][name]:
            raise ValueError(f"changed fixture file: {name}")
    release = GENERATED.RELEASES[json.loads((fixtures / "corpus.json").read_text())["release"]]
    current = {str(path.relative_to(ROOT)): GENERATED.sha256(path) for path in sources(release)}
    if current != manifest["toolSha256"]:
        raise ValueError("changed harness or runner source: record the fixture again")
    requests = lines(fixtures / "requests.jsonl")
    verdicts = lines(fixtures / "verdicts.jsonl")
    if [r["id"] for r in requests] != [v["id"] for v in verdicts] or len(requests) != manifest["requests"]:
        raise ValueError("a missing, extra or reordered verdict")
    if len({r["id"] for r in requests}) != len(requests):
        raise ValueError("duplicate request id")
    return requests, verdicts


def counts(requests, verdicts):
    by = lambda key: dict(sorted(Counter(key(r, v) for r, v in zip(requests, verdicts)).items()))
    return {
        "byNetwork": by(lambda r, v: r["id"].split("/", 1)[0]),
        "byEra": by(lambda r, v: era(r["protocol"])),
        "byProtocol": by(lambda r, v: str(r["protocol"])),
        "byLanguage": by(lambda r, v: f'V{r["language"]}'),
        "stages": by(lambda r, v: v["stage"]),
        "stagesByLanguage": by(lambda r, v: f'V{r["language"]}/{v["stage"]}'),
        "errors": by(lambda r, v: f'{v["stage"]}/{v.get("class", "-")}/{v.get("error", "-")}'),
    }


def digest(hex_bytes):
    return hashlib.sha256(bytes.fromhex(hex_bytes)).hexdigest()


def bind(rows, full):
    """The oracle's requests are the retained ones: the same fields, the script and arguments of their digests."""
    if len(rows) != len(full):
        raise ValueError(f"{len(full)} requests for {len(rows)} retained")
    for row, request in zip(rows, full):
        same = all(request[key] == row[key] for key in ("id", "case", "model", "language", "protocol", "cpu", "memory"))
        if (not same or digest(request["script"]) != row["scriptSha256"]
                or [digest(a) for a in request["arguments"]] != row["argumentsSha256"]):
            raise ValueError(f"the oracle's request is not the retained one: {row['id']}")


def record(fixtures, evidence, owner):
    corpus = json.loads((fixtures / "corpus.json").read_text())
    release = GENERATED.RELEASES[corpus["release"]]
    models = lines(fixtures / "models.jsonl")
    rows = lines(fixtures / "requests.jsonl")
    full = lines(evidence / "requests.jsonl")
    bind(rows, full)
    requests = [{**request, "costModel": models[request["model"]]["costModel"]} for request in full]
    check_image(release, owner)
    results = GENERATED.execute(release["image"], requests, evidence / "raw.jsonl", owner)
    verdicts = [GENERATED.verdict(row) for row in results]
    (fixtures / "verdicts.jsonl").write_text("".join(GENERATED.dump(v) + "\n" for v in verdicts))
    manifest = {
        "release": corpus["release"],
        "plutusCommit": release["pin"],
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "oracleImage": GENERATED.image_id(release["image"]),
        "oracleBaseImage": GENERATED.image_id(release["base"]),
        "cases": corpus["cases"],
        "requests": len(requests),
        "costModels": len(models),
        **counts(rows, verdicts),
        "withLogs": sum(bool(v.get("logs")) for v in verdicts),
        "withArguments": sum(bool(r["argumentsSha256"]) for r in rows),
        "sha256": {name: GENERATED.sha256(fixtures / name) for name in FILES},
        "oracleRequestsSha256": GENERATED.sha256(evidence / "requests.jsonl"),
        "toolSha256": {str(path.relative_to(ROOT)): GENERATED.sha256(path) for path in sources(release)},
        "rawSha256": GENERATED.sha256(evidence / "raw.jsonl"),
        "dateUtc": datetime.now(timezone.utc).isoformat(),
    }
    (fixtures / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    provenance = {
        "schemaVersion": 1,
        "scope": "Admission, stage, error class and constructor, budget spent (on success and on failure) and logs "
                 "of the script requests the ledger collects for recorded Preview and preprod transactions, under "
                 "deserialiseScript and evaluateScriptRestricting with the request's cost-model list and the "
                 "redeemer's budget; not the collection itself, which the requests are taken from",
        "plutusSourceCommit": release["pin"],
        "measuredAt": manifest["dateUtc"][:10],
        "network": "none",
        "sourceNetworks": sorted(manifest["byNetwork"]),
        "cases": corpus["cases"],
        "requests": len(requests),
        "imageId": manifest["oracleImage"],
        "baseImageId": manifest["oracleBaseImage"],
        "harnessSha256": manifest["toolSha256"][f'{release["harness"]}/Main.hs'],
        "runnerSha256": manifest["toolSha256"][str((HERE / "run.py").relative_to(ROOT))],
        "requestsSha256": manifest["sha256"]["requests.jsonl"],
        "oracleRequestsSha256": manifest["oracleRequestsSha256"],
        "modelsSha256": manifest["sha256"]["models.jsonl"],
        "verdictsSha256": manifest["sha256"]["verdicts.jsonl"],
    }
    (fixtures / "provenance.json").write_text(json.dumps(provenance, indent=1) + "\n")
    validate(fixtures)
    print(json.dumps({k: manifest[k] for k in ("requests", "byLanguage", "stages", "errors", "withLogs")}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, default=DIRECTORY)
    parser.add_argument("--evidence", type=Path,
                        help="outside the checkout: the oracle's requests RecordedRequestsMain wrote, and the raw answers")
    parser.add_argument("--owner", default="plutus-recorded-requests")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if args.verify_only:
        requests, _ = validate(args.fixtures)
        print(f"PASS: {len(requests)} requests, each with its verdict; fixture files and runner sources unchanged")
    elif args.evidence is None:
        parser.error("--evidence is required to record")
    else:
        record(args.fixtures, args.evidence, args.owner)


if __name__ == "__main__":
    main()
