#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the script requests of recorded chain transactions through the pinned Haskell evaluator and record its verdicts.

The requests are the ledger's, written by `RecordedRequestsMain` in full into the evidence directory and by digest
into the fixture, which this runner checks against each other; the evaluator, its image and the shape of
requests and answers are the generated-program oracle's (`scripts/oracle/plutus/generated`), whose runner this one
loads unchanged. The image is resolved once to its immutable ID, which the check, the run and the record all use;
before it answers, the image must run the build of the repository's harness at the pinned, unchanged plutus, with
the executable and build files the fixture's provenance accepted. `--verify-only` checks a retained fixture without
Docker.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile

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
# What the image runs: the harness source (the repository's, by digest), and the build bound by the fixture's
# provenance (the harness's package, the cabal project the parent oracle's Dockerfile edits, and the executable that
# is the image's entry point), as the context-matrix runner binds its image (`image_binding`, `oracleBinarySha256`).
IMAGE_HARNESS = "/source/scarab-oracle/Main.hs"
IMAGE_BINARY = "/usr/local/bin/scarab-plutus-oracle"
IMAGE_BUILD = ("/source/scarab-oracle/scarab-plutus-oracle.cabal", "/source/cabal.project", IMAGE_BINARY)
# Run in the image (working directory /source, the plutus checkout): each line a key and a value. The plutus trees
# must equal their commit (no change, staged or not, no untracked or ignored file), and cabal must find the
# executable up to date with the sources the image holds.
MEASURE = "\n".join([
    *(f'echo "file:{path} $(sha256sum {path} | cut -d\' \' -f1)"' for path in (IMAGE_HARNESS, *IMAGE_BUILD)),
    'echo "head $(git rev-parse HEAD)"',
    f'git diff --quiet HEAD -- {" ".join(PINNED_TREES)}'
    f' && test -z "$(git status --porcelain --ignored --untracked-files=all -- {" ".join(PINNED_TREES)})"'
    ' && echo "pinned unchanged"',
    'cabal build scarab-plutus-oracle --offline --dry-run 2>/dev/null | grep -qx "Up to date" && echo "build current"',
    'echo "built $(sha256sum "$(cabal list-bin scarab-plutus-oracle)" | cut -d\' \' -f1)"',
])


def era(protocol):
    return "Alonzo" if protocol < 7 else "Babbage" if protocol < 9 else "Conway"


def lines(path):
    return [json.loads(line) for line in path.read_text().splitlines()]


def sources(release):
    """The files whose digests bind the verdicts: the harness the image was built from and both runners."""
    harness = ROOT / release["harness"]
    return [harness / "Main.hs", harness / "Dockerfile", HERE.parent / "generated" / "run.py", HERE / "run.py"]


def resolve(image):
    """The immutable ID of an image, resolved once: the check, the run and the record all name this ID."""
    value = subprocess.run(["docker", "image", "inspect", image, "--format", "{{.Id}}"],
                           capture_output=True, text=True, check=True).stdout.strip()
    if re.fullmatch(r"sha256:[0-9a-f]{64}", value) is None:
        raise ValueError(f"no immutable image ID for {image}")
    return value


def entrypoint(image_id):
    return json.loads(subprocess.run(["docker", "image", "inspect", image_id, "--format", "{{json .Config.Entrypoint}}"],
                                     capture_output=True, text=True, check=True).stdout)


def container(image_id, owner, *options):
    return ["docker", "run", "--rm", "--network", "none", *options, *GENERATED.LABELS,
            "--label", f"xyz.kd5ujc.scarab.owner={owner}", image_id]


def measure(image_id, owner):
    """The keys and values MEASURE prints in the image."""
    command = [*container(image_id, owner, "--entrypoint", "sh"), "-c", MEASURE]
    output = subprocess.run(command, capture_output=True, text=True, check=True).stdout
    return dict(line.split(" ", 1) for line in output.splitlines() if " " in line)


def image_binding(release, image_id, owner, accepted):
    """The image runs the build of the repository's harness at the pinned, unchanged plutus, and, unless `accepted`
    is None (a build accepted anew), the executable and build files the fixture's provenance accepted."""
    if entrypoint(image_id) != [IMAGE_BINARY]:
        raise ValueError(f"{image_id} does not run {IMAGE_BINARY}")
    measured = measure(image_id, owner)
    files = {key[len("file:"):]: value for key, value in measured.items() if key.startswith("file:")}
    refusals = [
        (measured.get("head") == release["pin"], f"plutus is not at {release['pin']}"),
        (measured.get("pinned") == "unchanged",
         f"the plutus source differs from its commit under {', '.join(PINNED_TREES)}"),
        (files.get(IMAGE_HARNESS) == GENERATED.sha256(ROOT / release["harness"] / "Main.hs"),
         "stale oracle harness: the image's Main.hs is not the repository's"),
        (measured.get("build") == "current", "stale executable: cabal would rebuild it from the image's sources"),
        (measured.get("built") == files.get(IMAGE_BINARY), "the entry point is not the executable cabal built"),
    ]
    for holds, why in refusals:
        if not holds:
            raise ValueError(f"{image_id}: {why}")
    build = {path: files[path] for path in IMAGE_BUILD}
    if accepted is not None and build != accepted:
        raise ValueError(f"{image_id}: executable or build files differ from the accepted oracle build "
                         "(record with --accept-build only for a build reviewed anew)")
    return {"imageId": image_id, "imageFilesSha256": {path: files[path] for path in (IMAGE_HARNESS, *IMAGE_BUILD)}}


def accepted_build(fixtures):
    """The build files the fixture's provenance accepted."""
    provenance = json.loads((fixtures / "provenance.json").read_text())
    if "imageFilesSha256" not in provenance:
        raise ValueError("the fixture accepts no oracle build yet: record with --accept-build")
    return {path: provenance["imageFilesSha256"][path] for path in IMAGE_BUILD}


def execute(image_id, requests, raw, owner):
    """The requests through the image, without network, one answer per request in order."""
    with tempfile.TemporaryFile(mode="w+") as inputs:
        inputs.writelines(GENERATED.dump(request) + "\n" for request in requests)
        inputs.seek(0)
        with raw.open("w") as results:
            subprocess.run(container(image_id, owner, "-i"), stdin=inputs, stdout=results, check=True)
    rows = lines(raw)
    if len(rows) != len(requests):
        raise ValueError(f"{len(rows)} results for {len(requests)} requests")
    for request, row in zip(requests, rows):
        if "toolError" in row or row.get("id") != request["id"]:
            raise ValueError((request["id"], row))
    return rows


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
    provenance = json.loads((fixtures / "provenance.json").read_text())
    image = provenance["imageFilesSha256"]
    if (provenance["imageId"] != manifest["oracleImage"] or image != manifest["imageFilesSha256"]
            or provenance["oracleBinarySha256"] != image[IMAGE_BINARY]
            or image[IMAGE_HARNESS] != GENERATED.sha256(ROOT / release["harness"] / "Main.hs")):
        raise ValueError("the provenance does not bind the image, its executable and the current harness")
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


def record(fixtures, evidence, owner, accept_build):
    corpus = json.loads((fixtures / "corpus.json").read_text())
    release = GENERATED.RELEASES[corpus["release"]]
    models = lines(fixtures / "models.jsonl")
    rows = lines(fixtures / "requests.jsonl")
    full = lines(evidence / "requests.jsonl")
    bind(rows, full)
    requests = [{**request, "costModel": models[request["model"]]["costModel"]} for request in full]
    image_id = resolve(release["image"])
    binding = image_binding(release, image_id, owner, None if accept_build else accepted_build(fixtures))
    results = execute(image_id, requests, evidence / "raw.jsonl", owner)
    verdicts = [GENERATED.verdict(row) for row in results]
    (fixtures / "verdicts.jsonl").write_text("".join(GENERATED.dump(v) + "\n" for v in verdicts))
    manifest = {
        "release": corpus["release"],
        "plutusCommit": release["pin"],
        "sourceCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "oracleImage": image_id,
        "oracleBaseImage": resolve(release["base"]),
        "imageFilesSha256": binding["imageFilesSha256"],
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
        "imageId": image_id,
        "baseImageId": manifest["oracleBaseImage"],
        "imageFilesSha256": binding["imageFilesSha256"],
        "oracleBinarySha256": binding["imageFilesSha256"][IMAGE_BINARY],
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
                        help="a git-ignored directory: the oracle's requests RecordedRequestsMain wrote there, "
                             "and the raw answers")
    parser.add_argument("--owner", default="plutus-recorded-requests")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--accept-build", action="store_true",
                        help="accept the image's build files and executable anew, instead of the ones recorded")
    args = parser.parse_args()
    if args.verify_only:
        requests, _ = validate(args.fixtures)
        print(f"PASS: {len(requests)} requests, each with its verdict; fixture files and runner sources unchanged")
    elif args.evidence is None:
        parser.error("--evidence is required to record")
    else:
        record(args.fixtures, args.evidence, args.owner, args.accept_build)


if __name__ == "__main__":
    main()
