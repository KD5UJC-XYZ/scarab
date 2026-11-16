#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Run the chain context harness of the Leios testnet build on the cases of a chain sample and record its answers
(#54).

The image is resolved once to its immutable ID, which the check, the run (without network) and the record all use.
Before it answers, the image must run the build of the repository's harness: its `Main.hs`, its package and the
cabal project equal to the repository's, the ledger and cardano-base checkouts at the commits the project pins and
unchanged, an executable cabal finds up to date, and that executable as its entry point; and, unless
`--accept-build`, the executable and build files the fixture's provenance accepted. The parent image's tag that
`Dockerfile` names is not trusted: the measurement is of the image that answers. `--verify-only` checks the
retained fixture without Docker.

    python3 -I scripts/oracle/leios-testnet/chain/run.py --evidence <a directory outside the checkout>
    python3 -I scripts/oracle/leios-testnet/chain/run.py --verify-only
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
DIRECTORY = ROOT / "fixtures/reference/leios-testnet-chain-contexts"
IMAGE = "scarab-leios-testnet-chain:local"
LABELS = ["--label", "xyz.kd5ujc.scarab.kind=oracle"]
PROJECT = "/oracle/leios-testnet"
IMAGE_HARNESS = f"{PROJECT}/chain/Main.hs"
IMAGE_BINARY = "/usr/local/bin/scarab-leios-testnet-chain"
IMAGE_BUILD = (f"{PROJECT}/chain/scarab-leios-testnet-chain.cabal", f"{PROJECT}/cabal.project",
               f"{PROJECT}/cabal.project.local", IMAGE_BINARY)
# The image's files that must be the repository's, by digest.
REPOSITORY_FILES = {
    IMAGE_HARNESS: HERE / "Main.hs",
    f"{PROJECT}/chain/scarab-leios-testnet-chain.cabal": HERE / "scarab-leios-testnet-chain.cabal",
    f"{PROJECT}/cabal.project": HERE.parent / "cabal.project",
}
# Run in the image by bash, printing one key and value a line. Every producer's output is taken by an assignment at
# the top level, so under `set -euo pipefail` a command that fails, whatever it printed, ends the measurement with
# its status and `measure` refuses the image; the last line says the measurement ran to its end. Each checkout of a
# `source-repository-package` (under dist-newstyle/src) is reported with its commit, and as unchanged only when it
# equals that commit (no change, staged or not, no untracked or ignored file: `git diff` exits 1 for a difference
# and above 1 for a failure). Cabal must find the executable up to date, with the flags the Dockerfile built it with.
MEASURE = "\n".join([
    "set -euo pipefail",
    f"cd {PROJECT}",
    f'for path in {" ".join((IMAGE_HARNESS, *IMAGE_BUILD))}; do',
    '  line=$(sha256sum "$path")',
    '  echo "file:$path ${line%% *}"',
    "done",
    "for dir in dist-newstyle/src/*/; do",
    '  head=$(git -C "$dir" rev-parse HEAD)',
    '  listing=$(git -C "$dir" status --porcelain --ignored --untracked-files=all)',
    '  differs=0; git -C "$dir" diff --quiet HEAD || differs=$?',
    '  [ "$differs" -le 1 ] || exit "$differs"',
    '  state=changed',
    '  if [ "$differs" -eq 0 ] && [ -z "$listing" ]; then state=unchanged; fi',
    '  echo "source:$head $state"',
    "done",
    "dry=$(cabal build scarab-leios-testnet-chain --offline --dry-run --extra-lib-dirs=/usr/local/lib 2>/dev/null)",
    'if printf "%s\\n" "$dry" | grep -qx "Up to date"; then echo "build current"; fi',
    "binary=$(cabal list-bin scarab-leios-testnet-chain)",
    'line=$(sha256sum "$binary")',
    'echo "built ${line%% *}"',
    'echo "measured all"',
])
DIGEST = re.compile(r"[0-9a-f]{64}")
IMAGE_ID = re.compile(r"sha256:[0-9a-f]{64}")


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def pinned():
    """The commits of the `source-repository-package`s of the repository's cabal project."""
    tags = re.findall(r"^\s*tag:\s*([0-9a-f]{40})\s*$", (HERE.parent / "cabal.project").read_text(), re.MULTILINE)
    if not tags:
        raise ValueError("the cabal project pins no source-repository-package")
    return sorted(tags)


def build_files(files, where):
    """The digests a record binds the image's files by: exactly the harness and the build files, each a SHA-256."""
    expected = {IMAGE_HARNESS, *IMAGE_BUILD}
    if not isinstance(files, dict) or set(files) != expected:
        raise ValueError(f"{where}: the image's files are not exactly {sorted(expected)}")
    if not all(isinstance(value, str) and DIGEST.fullmatch(value) for value in files.values()):
        raise ValueError(f"{where}: an image file without a SHA-256")
    return files


def resolve(image):
    """The immutable ID of an image, resolved once: the check, the run and the record all name this ID."""
    value = subprocess.run(["docker", "image", "inspect", image, "--format", "{{.Id}}"],
                           capture_output=True, text=True, check=True).stdout.strip()
    if IMAGE_ID.fullmatch(value) is None:
        raise ValueError(f"no immutable image ID for {image}")
    return value


def entrypoint(image_id):
    return json.loads(subprocess.run(["docker", "image", "inspect", image_id, "--format", "{{json .Config.Entrypoint}}"],
                                     capture_output=True, text=True, check=True).stdout)


def container(image_id, owner, *options):
    return ["docker", "run", "--rm", "--network", "none", *options, *LABELS,
            "--label", f"xyz.kd5ujc.scarab.owner={owner}", image_id]


def measure(image_id, owner):
    """The keys and values MEASURE prints in the image, which must run to its end."""
    result = subprocess.run([*container(image_id, owner, "--entrypoint", "bash"), "-c", MEASURE],
                            capture_output=True, text=True)
    measured = {}
    for line in result.stdout.splitlines():
        if " " in line:
            key, value = line.split(" ", 1)
            measured.setdefault(key, []).append(value)
    if result.returncode != 0 or measured.get("measured") != ["all"]:
        raise ValueError(f"{image_id}: the image could not be measured (exit {result.returncode}): "
                         f"{result.stderr.strip()[-400:]}")
    return measured


def image_binding(image_id, owner, accepted):
    """The image runs the build of the repository's harness against the pinned, unchanged checkouts, and, unless
    `accepted` is None (a build accepted anew), the executable and build files the fixture's provenance accepted."""
    if entrypoint(image_id) != [IMAGE_BINARY]:
        raise ValueError(f"{image_id} does not run {IMAGE_BINARY}")
    measured = measure(image_id, owner)
    files = {key[len("file:"):]: values[0] for key, values in measured.items()
             if key.startswith("file:") and len(values) == 1}
    sources = {}
    for key, values in measured.items():
        if key.startswith("source:"):
            sources[key[len("source:"):]] = values
    built = measured.get("built", [""])
    refusals = [
        (all(DIGEST.fullmatch(files.get(path, "")) for path in (IMAGE_HARNESS, *IMAGE_BUILD))
         and len(built) == 1 and DIGEST.fullmatch(built[0]) is not None,
         "an image file or the built executable could not be measured"),
        (all(files.get(path) == sha256(source) for path, source in REPOSITORY_FILES.items()
             if path != IMAGE_HARNESS), "the image's package or cabal project is not the repository's"),
        (files.get(IMAGE_HARNESS) == sha256(REPOSITORY_FILES[IMAGE_HARNESS]),
         "stale oracle harness: the image's Main.hs is not the repository's"),
        (sorted(sources) == pinned(), f"the image's checkouts are not those the cabal project pins: {sorted(sources)}"),
        (all(values == ["unchanged"] for values in sources.values()),
         "a pinned checkout differs from its commit"),
        (measured.get("build") == ["current"], "stale executable: cabal would rebuild it from the image's sources"),
        (built == [files.get(IMAGE_BINARY)], "the entry point is not the executable cabal built"),
    ]
    for holds, why in refusals:
        if not holds:
            raise ValueError(f"{image_id}: {why}")
    build = {path: files[path] for path in IMAGE_BUILD}
    if accepted is not None and build != accepted:
        raise ValueError(f"{image_id}: executable or build files differ from the accepted oracle build "
                         "(record with --accept-build only for a build reviewed anew)")
    return {"imageId": image_id, "imageFilesSha256": {path: files[path] for path in (IMAGE_HARNESS, *IMAGE_BUILD)},
            "pinnedSources": {commit: "unchanged" for commit in sorted(sources)}}


def accepted_build(fixtures):
    """The build files the fixture's provenance accepted."""
    oracle = json.loads((fixtures / "provenance.json").read_text()).get("oracle")
    if not isinstance(oracle, dict) or "imageFilesSha256" not in oracle:
        raise ValueError("the fixture accepts no oracle build yet: record with --accept-build")
    files = build_files(oracle["imageFilesSha256"], "provenance.json")
    return {path: files[path] for path in IMAGE_BUILD}


def levels(result):
    """Every collection of an accepted case, the top level's and each sub-transaction's, each collected with an
    explicit list of contexts; anything else is refused."""
    if result.get("accepted") is not True or result.get("failure") is not None:
        raise ValueError(f"not collected: {result}")
    subtxs = result.get("subtxs")
    if not isinstance(subtxs, list):
        raise ValueError("no list of sub-transaction collections")
    out = []
    for level in [result.get("top")] + subtxs:
        if not isinstance(level, dict) or level.get("collected") is not True or not isinstance(level.get("contexts"), list):
            raise ValueError(f"a collection that failed or lists no contexts: {level}")
        out.append(level["contexts"])
    return out


def validate(fixtures):
    """A retained fixture: its files are the ones its provenance binds, every case answered and collected at every
    level, and the provenance binds an image of the repository's harness and its executable."""
    provenance = json.loads((fixtures / "provenance.json").read_text())
    for name in ("cases", "results"):
        record = provenance[name]
        if sha256(fixtures / record["file"]) != record["sha256"]:
            raise ValueError(f"changed fixture file: {record['file']}")
    chain = json.loads((fixtures / "cases.json").read_text())
    results = json.loads((fixtures / "results.json").read_text())
    if [c["name"] for c in chain["cases"]] != [r["name"] for r in results]:
        raise ValueError("the results are not those of the cases")
    for r in results:
        if r.get("kind") != "context":
            raise ValueError(f"{r['name']}: not a context answer")
        levels(r["result"])
    oracle = provenance.get("oracle")
    if not isinstance(oracle, dict):
        raise ValueError("the provenance binds no oracle image")
    files = build_files(oracle.get("imageFilesSha256"), "provenance.json")
    if (not isinstance(oracle.get("imageId"), str) or IMAGE_ID.fullmatch(oracle["imageId"]) is None
            or oracle.get("oracleBinarySha256") != files[IMAGE_BINARY]
            or any(files[path] != sha256(source) for path, source in REPOSITORY_FILES.items())
            or oracle.get("pinnedSources") != {commit: "unchanged" for commit in pinned()}
            or oracle.get("runnerSha256") != sha256(HERE / "run.py")):
        raise ValueError("the provenance does not bind the image, its executable, the pinned sources and the "
                         "current harness and runner")
    return chain, results


def record(fixtures, evidence, owner, accept_build):
    """The cases through the verified image, without network: its answers become `results.json` (a copy stays in
    `evidence`), and the provenance binds them, the image and its build."""
    image_id = resolve(IMAGE)
    binding = image_binding(image_id, owner, None if accept_build else accepted_build(fixtures))
    evidence.mkdir(parents=True, exist_ok=True)
    raw = evidence / "results.json"
    with raw.open("wb") as out:
        subprocess.run(container(image_id, owner, "-v", f"{fixtures.resolve()}:/w:ro") + ["/w/cases.json"],
                       stdout=out, check=True)
    (fixtures / "results.json").write_bytes(raw.read_bytes())
    results = json.loads(raw.read_text())
    accepted = sum(1 for r in results if r["result"].get("accepted") is True and r["result"].get("failure") is None)
    provenance = json.loads((fixtures / "provenance.json").read_text())
    provenance["results"] = {"file": "results.json", "sha256": sha256(fixtures / "results.json"),
                             "accepted": accepted, "refused": len(results) - accepted}
    provenance["harness"] = {"source": str((HERE / "Main.hs").relative_to(ROOT)), "sha256": sha256(HERE / "Main.hs")}
    provenance["oracle"] = {
        "imageId": image_id,
        "imageFilesSha256": binding["imageFilesSha256"],
        "oracleBinarySha256": binding["imageFilesSha256"][IMAGE_BINARY],
        "pinnedSources": binding["pinnedSources"],
        "runnerSha256": sha256(HERE / "run.py"),
        "network": "none",
    }
    (fixtures / "provenance.json").write_text(json.dumps(provenance, indent=1) + "\n")
    validate(fixtures)
    print(json.dumps({"imageId": image_id, "results": provenance["results"]}))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--fixtures", type=Path, default=DIRECTORY)
    parser.add_argument("--evidence", type=Path, help="a directory outside the checkout for the image's answers")
    parser.add_argument("--owner", default="leios-chain-contexts")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--accept-build", action="store_true",
                        help="accept the image's build files and executable anew, instead of the ones recorded")
    args = parser.parse_args()
    if args.verify_only:
        chain, _ = validate(args.fixtures)
        print(f"PASS: {len(chain['cases'])} cases, each collected at every level; fixture files, harness and "
              "runner bound")
    elif args.evidence is None:
        parser.error("--evidence is required to record")
    else:
        record(args.fixtures, args.evidence, args.owner, args.accept_build)


if __name__ == "__main__":
    main()
