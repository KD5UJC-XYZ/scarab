#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Native start-up refusals of the genesis files for #796.

Each case is the public preview configuration and genesis set with one change: a genesis file whose
bytes or JSON are altered, missing or unreadable, a configured hash that no longer matches, the
configuration file named by a relative path, or the program started under another name. The
unchanged reference image runs `cardano-node run` on it once, without a network, and fails before
any cluster or database exists. The fixture keeps the change, the input hashes, the whole standard
error and the exit code; standard output (the node's configuration dump) is kept as a hash only.

  run.py            offline: the generated cases equal the recorded ones; the inputs rebuild to their hashes
  run.py --replay   run the image and compare every outcome with the fixture
  run.py --record   run the image and rewrite the fixture and its provenance
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
FIXTURE = ROOT / "fixtures/reference/genesis-startup"
CASES_FILE = FIXTURE / "cases.jsonl"
PROVENANCE = FIXTURE / "provenance.json"
IMAGE = "cardano-reference-11.1.3:local"
BINARY = "/opt/reference/bin/cardano-node"
BASE = ROOT / "fixtures/preview/genesis"
FILES = {
    "byron": "byron-genesis.json",
    "shelley": "shelley-genesis.json",
    "alonzo": "alonzo-genesis.json",
    "conway": "conway-genesis.json",
}
HASH_KEYS = {
    "byron": "ByronGenesisHash",
    "shelley": "ShelleyGenesisHash",
    "alonzo": "AlonzoGenesisHash",
    "conway": "ConwayGenesisHash",
}
CONFIG_HASHES = list(HASH_KEYS.values()) + ["CheckpointsFileHash"]
LABELS = ["--label", "xyz.kd5ujc.scarab.kind=oracle", "--label", "xyz.kd5ujc.scarab.owner=genesis-startup-796"]


def case(name, era=None, edits=(), keep_hash=False, absent=False, directory=False, unreadable=False,
         argv0="cardano-node", relative=False, also=None, hidden=False):
    """One change to the preview set. Each edit of the era's file is on its original bytes:
    `("substitute", from, to)` replaces the first occurrence of a text (`to` may be bytes, as hex,
    with `("splice", from, hex)`), `("truncate", n)` keeps the first n bytes and `("append", text)`
    adds text at the end. `also` is a second era's file changed the same way, with its hash kept,
    for the order the files are read in."""
    c = {"name": name, "argv0": argv0, "relativeConfig": relative}
    if also:
        c["also"] = {"era": also[0], "edits": [list(e) for e in also[1]]}
    if era:
        c["era"] = era
    if edits:
        c["edits"] = [list(e) for e in edits]
    for flag, value in (("keepHash", keep_hash), ("absent", absent), ("directory", directory),
                        ("unreadable", unreadable), ("hidden", hidden)):
        if value:
            c[flag] = True
    return c


def sub(old, new):
    return ("substitute", old, new)


def generate():
    no_start = sub('"systemStart": "2022-10-25T00:00:00Z",\n    ', "")
    no_magic = sub('"networkMagic": 2,\n    ', "")
    no_value_size = sub('"maxValueSize": 5000,\n    ', "")
    start = '"2022-10-25T00:00:00Z"'
    member = '"scriptHash-ff9babf23fef3f54ec29132c07a8e23807d7b395b143ecd8ff79f4c7": 1000'
    cases = [
        case("shelley/hash", "shelley", [no_start], keep_hash=True),
        case("shelley/truncated", "shelley", [("truncate", 50)]),
        case("shelley/trailing", "shelley", [("append", " x")]),
        case("shelley/trailing-and-field", "shelley", [no_start, ("append", " x")]),
        case("shelley/invalid-utf8", "shelley", [("splice", '"Testnet"', "22ff")]),
        case("shelley/missing-key", "shelley", [no_magic]),
        case("shelley/nested-type", "shelley", [sub('"minFeeA": 44,', '"minFeeA": "x",')]),
        case("shelley/zero-security", "shelley", [sub('"securityParam": 432,', '"securityParam": 0,')]),
        case("shelley/quorum-past-word64", "shelley",
             [sub('"updateQuorum": 5\n', '"updateQuorum": 18446744073709551616\n')]),
        case("shelley/rho-above-one", "shelley", [sub('"rho": 0.003,', '"rho": 1.5,')]),
        case("shelley/coefficient-too-precise", "shelley",
             [sub('"activeSlotsCoeff": 0.05,', '"activeSlotsCoeff": 1e-20,')]),
        case("shelley/invalid-day", "shelley", [sub('"2022-10-25T00:00:00Z"', '"2022-02-30T00:00:00Z"')]),
        case("shelley/no-timezone", "shelley", [sub('"2022-10-25T00:00:00Z"', '"2022-10-25T00:00:00"')]),
        case("shelley/nonce-tag", "shelley", [sub('"tag": "NeutralNonce"', '"tag": "Other"')]),
        case("shelley/delegate-key", "shelley",
             [sub('"genDelegs": {', '"genDelegs": {\n        "00": {"delegate": "00", "vrf": "00"},')]),
        case("shelley/fund-address", "shelley", [sub('"initialFunds": {},', '"initialFunds": {"00": 1},')]),
        case("shelley/missing-file", "shelley", absent=True),
        case("shelley/directory", "shelley", directory=True),
        case("shelley/unreadable", "shelley", unreadable=True),
        case("shelley/argv0-scarab", "shelley", [no_magic], argv0="scarab"),
        case("shelley/relative-config", "shelley", [no_magic], relative=True),
        case("alonzo/hash", "alonzo", [no_value_size], keep_hash=True),
        case("alonzo/missing-key", "alonzo", [no_value_size]),
        case("alonzo/cost-model-key", "alonzo",
             [sub('"addInteger-cpu-arguments-intercept": 197209,', '"addInteger-cpu-arguments-intercept": "x",')]),
        case("conway/hash", "conway", [sub('"dRepActivity": 20,', '"dRepActivity": "x",')], keep_hash=True),
        case("conway/cost-model-index", "conway",
             [sub('"plutusV3CostModel": [\n    100788,', '"plutusV3CostModel": [\n    "x",')]),
        case("conway/committee-credential", "conway", [sub(member, '"keyHash-00": 1000')]),
        case("conway/injection-file-and-data", "conway",
             [sub('"committee": {', '"extraConfig": {"delegs": {"file": ["x"], "data": {}}},\n  "committee": {')]),
        case("conway/truncated", "conway", [("truncate", 10)]),
        case("byron/truncated", "byron", [("truncate", 50)]),
        case("byron/missing-key", "byron",
             [sub('\n, "protocolConsts": { "k": 432 , "protocolMagic": 2 }', "")]),
        case("byron/hash", "byron", [sub('"startTime": 1666656000', '"startTime": 1666656001')], keep_hash=True),
        case("shelley/year-start", "shelley", [sub(start, '"x022-10-25T00:00:00Z"')]),
        case("shelley/short-year", "shelley", [sub(start, '"202-10-25T00:00:00Z"')]),
        case("shelley/hour-25", "shelley", [sub(start, '"2022-10-25T25:00:00Z"')]),
        case("shelley/no-decimals", "shelley", [sub(start, '"2022-10-25T00:00:00.Z"')]),
        case("shelley/thirteen-decimals", "shelley", [sub(start, '"2022-10-25T00:00:00.1234567890123Z"')]),
        case("shelley/zone-24", "shelley", [sub(start, '"2022-10-25T00:00:00+24:00"')]),
        case("shelley/after-zone", "shelley", [sub(start, '"2022-10-25T00:00:00Zx"')]),
        case("shelley/unreadable-directory", "shelley", hidden=True),
        case("alonzo/unknown-cost-model-key", "alonzo",
             [sub('"maxValueSize": 5000,', '"extraConfig": {"costModels": {"Unknown": {"1x": []}}},\n    "maxValueSize": 5000,')]),
        case("order/shelley-field-before-conway-hash", "shelley", [no_magic],
             also=("conway", [sub('"dRepActivity": 20,', '"dRepActivity": 21,')])),
        case("order/alonzo-hash-before-conway-syntax", "alonzo", [no_value_size], keep_hash=True,
             also=("conway", [("truncate", 10)])),
    ]
    names = [c["name"] for c in cases]
    assert len(names) == len(set(names)), "case names must be unique"
    return cases


def edited(data, edits):
    for op, *args in edits:
        if op == "truncate":
            data = data[: args[0]]
        elif op == "append":
            data = data + args[0].encode()
        else:
            old = args[0].encode()
            new = bytes.fromhex(args[1]) if op == "splice" else args[1].encode()
            assert old in data, f"{old!r} is not in the file"
            data = data.replace(old, new, 1)
    return data


def build(c, directory):
    """Write the case's configuration and genesis files into `directory`; return their hashes. The
    configuration is the preview one without the lines of its hashes, but the era's when the case
    keeps it, and with another file name for an absent file."""
    era = c.get("era")
    also = c.get("also", {})
    kept = {HASH_KEYS.get(era) if c.get("keepHash") else None, HASH_KEYS.get(also.get("era"))}
    dropped = [key for key in CONFIG_HASHES if key not in kept]
    lines = [line for line in (BASE / "config.json").read_text().split("\n")
             if not any(f'"{key}":' in line for key in dropped)]
    config = "\n".join(lines)
    for name in ["topology.json", "checkpoints.json", *FILES.values()]:
        shutil.copyfile(BASE / name, directory / name)
    if era:
        target = directory / FILES[era]
        if c.get("absent"):
            named = f'"{era.capitalize()}GenesisFile": "{FILES[era]}"'
            assert named in config
            config = config.replace(named, f'"{era.capitalize()}GenesisFile": "absent-genesis.json"')
        elif c.get("directory"):
            target.unlink()
            target.mkdir()
        elif c.get("hidden"):
            # the file in a directory without read or search permission
            named = f'"{era.capitalize()}GenesisFile": "{FILES[era]}"'
            assert named in config
            config = config.replace(named, f'"{era.capitalize()}GenesisFile": "hidden/{FILES[era]}"')
            (directory / "hidden").mkdir()
            target.rename(directory / "hidden" / FILES[era])
            (directory / "hidden").chmod(0)
        else:
            target.write_bytes(edited(target.read_bytes(), c.get("edits", [])))
            if c.get("unreadable"):
                target.chmod(0)
    if also:
        second = directory / FILES[also["era"]]
        second.write_bytes(edited(second.read_bytes(), also["edits"]))
    (directory / "config.json").write_text(config)
    hashes = {}
    for path in sorted(directory.iterdir()):
        if path.is_file() and os.access(path, os.R_OK):
            hashes[path.name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return hashes


def run_case(c):
    with tempfile.TemporaryDirectory(prefix="genesis-startup-796-") as scratch:
        directory = Path(scratch) / "in"
        directory.mkdir()
        hashes = build(c, directory)
        directory.chmod(0o755)
        config = "config.json" if c["relativeConfig"] else "/in/config.json"
        program = c["argv0"] if c["argv0"] == "cardano-node" else f"/tmp/bin/{c['argv0']}"
        script = (f"mkdir -p /tmp/bin && ln -s {BINARY} /tmp/bin/{c['argv0']} && cd /in && "
                  f"exec {program} run --config {config} --topology /in/topology.json "
                  "--database-path /tmp/db --socket-path /tmp/node.socket")
        done = subprocess.run(
            ["docker", "run", "--rm", "--network", "none", "--read-only", "--tmpfs", "/tmp", *LABELS,
             "--user", "65534:65534", "-v", f"{directory}:/in:ro", "--entrypoint", "sh", IMAGE, "-c", script],
            capture_output=True, timeout=300)
        restore(c, directory)
    return dict(c, inputs=hashes, reference={
        "exit": done.returncode,
        "stderr": done.stderr.decode("utf-8", "surrogateescape"),
        "stdoutSha256": hashlib.sha256(done.stdout).hexdigest(),
        "stdoutBytes": len(done.stdout),
    })


def recorded():
    return [json.loads(line) for line in CASES_FILE.read_text().splitlines() if line]


def strip(c):
    return {k: v for k, v in c.items() if k not in ("reference", "inputs")}


def rebuilt(c):
    with tempfile.TemporaryDirectory(prefix="genesis-startup-796-") as scratch:
        directory = Path(scratch) / "in"
        directory.mkdir()
        hashes = build(c, directory)
        restore(c, directory)
        return hashes


def restore(c, directory):
    """Make a case's withheld file or directory removable again."""
    if c.get("unreadable"):
        (directory / FILES[c["era"]]).chmod(0o600)
    if c.get("hidden"):
        (directory / "hidden").chmod(0o700)


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--record", action="store_true")
    mode.add_argument("--replay", action="store_true")
    args = parser.parse_args()
    cases = generate()
    if args.record:
        results = [run_case(c) for c in cases]
        FIXTURE.mkdir(parents=True, exist_ok=True)
        CASES_FILE.write_text("".join(json.dumps(r, sort_keys=True) + "\n" for r in results))
        image = subprocess.check_output(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"], text=True).strip()
        binary = subprocess.check_output(
            ["docker", "run", "--rm", "--network", "none", *LABELS, "--entrypoint", "sh", IMAGE, "-c",
             f"sha256sum {BINARY} && cardano-node --version"], text=True)
        PROVENANCE.write_text(json.dumps({
            "schemaVersion": 1,
            "scope": "cardano-node run on the preview configuration with one genesis change; standard error and exit code before any database or network",
            "image": IMAGE,
            "imageId": image,
            "binary": {"path": BINARY, "sha256": binary.split()[0], "version": binary.split("\n", 1)[1].strip()},
            "node": {"repository": "IntersectMBO/cardano-node", "tag": "11.1.3",
                     "revision": "938cba990357ae7c4b7f95c8f75dd9d31174bbeb"},
            "referenceGitRevision": "938cba990357ae7c4b7f95c8f75dd9d31174bbeb",
            "base": {name: hashlib.sha256((BASE / name).read_bytes()).hexdigest()
                     for name in ["config.json", "topology.json", "checkpoints.json", *FILES.values()]},
            "program": "scripts/oracle/genesis-startup/run.py",
            "cases": len(results),
        }, indent=2, sort_keys=True) + "\n")
        print(f"recorded {len(results)} cases")
        return 0
    if [strip(c) for c in recorded()] != cases:
        print("the generated cases differ from the fixture: run --record", file=sys.stderr)
        return 1
    base = json.loads(PROVENANCE.read_text())["base"]
    if base != {name: hashlib.sha256((BASE / name).read_bytes()).hexdigest() for name in base}:
        print("a base file changed: run --record", file=sys.stderr)
        return 1
    differing = [c["name"] for c in recorded() if rebuilt(c) != c["inputs"]]
    if differing:
        print(f"inputs rebuild differently: {differing}", file=sys.stderr)
        return 1
    if args.replay:
        fresh = [run_case(c) for c in cases]
        differing = [f["name"] for f, r in zip(fresh, recorded(), strict=True)
                     if (f["reference"]["exit"], f["reference"]["stderr"]) != (r["reference"]["exit"], r["reference"]["stderr"])]
        if differing:
            print(f"{len(differing)} outcomes differ: {differing[:5]}", file=sys.stderr)
            return 1
    print(f"{len(cases)} cases agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
