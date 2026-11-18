#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reference answers of the Leios testnet build to two node-to-client queries on captured ledger states (#826).

The inputs are the Leios build's state files `conway-195`, `dijkstra-203` and `testnet-2168639`
(`fixtures/leios/state`). This script takes the ledger's `NewEpochState` out of each extended state, and the oracle
(`Main.hs`) decodes it at its era's version, refuses it unless it encodes back to the same bytes, and answers
`GetStakePoolState` (tag 40, `queryStakePoolState`) for five argument sets and `GetLeiosCommittee` (tag 41,
`queryLeiosCommittee`), each written as consensus writes the result (`toEraCBOR @era`); then, on a state derived
from it with the ledger's own lenses (its smallest pool retiring at the next epoch and with future parameters),
`GetStakePoolState` for that pool with and without delegators. The README says which parts are the consensus
layer's.

The image is resolved once to its immutable ID, which the measurement, the runs and the record all use, and it
answers only after it is measured, as the chain oracle of the Leios testnet harness measures its image
(`scripts/oracle/leios-testnet/chain/run.py`): its `Main.hs`, package and cabal project are the repository's,
each `source-repository-package` checkout is at the commit the cabal project pins and unchanged, cabal finds the
executable up to date offline, the entry point is that executable, and the ledger sources the result depends on
have their pinned digests. Replay and record also refuse an image whose executable or build files are not the
ones the provenance accepted, or whose immutable ID is not the recorded one, unless the record is given
`--accept-build` for an image reviewed anew.

  run.py                                  offline: the inputs, the program and the fixture match the provenance
  run.py --replay [--name N]              run the measured image (no network, read-only) and compare
  run.py --record [--name N] [--accept-build]
                                          run the measured image and rewrite the fixture and its provenance
"""
import argparse
import datetime
import hashlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from cleanup import container_labels  # noqa: E402


def _chain():
    spec = importlib.util.spec_from_file_location(
        "leios_chain_run", ROOT / "scripts/oracle/leios-testnet/chain/run.py")
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


# The chain oracle's image resolution, cabal-project readers and CBOR reader (`leios_cbor.py`).
CHAIN = _chain()
CBOR = CHAIN.CBOR

FIXTURE = ROOT / "fixtures/reference/leios-ntc-queries"
CASES_FILE = FIXTURE / "cases.jsonl"
PROVENANCE = FIXTURE / "provenance.json"
INPUTS = "fixtures/leios/state"
# Each state file, and the era the telescope's length says its tip is in: Conway is the seventh era, Dijkstra
# the eighth (ouroboros-consensus 2416493b `Ouroboros/Consensus/Cardano/Block.hs:266-278`, `CardanoEras`).
STATES = ("conway-195", "dijkstra-203", "testnet-2168639")
# The Shelley genesis file of the network each state is of, whose `networkId` (Testnet) `Main.hs` gives the derived
# state's future pool parameters.
GENESIS = {"conway-195": f"{INPUTS}/genesis/shelley-genesis.json",
           "dijkstra-203": f"{INPUTS}/genesis/shelley-genesis.json",
           "testnet-2168639": "fixtures/leios/testnet/config/shelley-genesis.json"}
NETWORK = "Testnet"
ERAS = {7: ("conway", 9), 8: ("dijkstra", 12)}
IMAGE = "scarab-leios-ntc-queries-oracle:local"
PROGRAM = "scripts/oracle/leios-ntc-queries/Main.hs"
PACKAGE = "scarab-leios-ntc-queries"
PROJECT = "/oracle/leios-testnet"
IMAGE_HARNESS = f"{PROJECT}/leios-ntc-queries/Main.hs"
IMAGE_BINARY = f"/usr/local/bin/{PACKAGE}"
IMAGE_BUILD = (f"{PROJECT}/leios-ntc-queries/{PACKAGE}.cabal", f"{PROJECT}/cabal.project",
               f"{PROJECT}/cabal.project.local", IMAGE_BINARY)
# What each of the image's files must be: the repository's file, or the line the Dockerfile writes.
REPOSITORY_FILES = {
    IMAGE_HARNESS: (HERE / "Main.hs").read_bytes(),
    IMAGE_BUILD[0]: (HERE / f"{PACKAGE}.cabal").read_bytes(),
    IMAGE_BUILD[1]: (ROOT / "scripts/oracle/leios-testnet/cabal.project").read_bytes(),
    IMAGE_BUILD[2]: b"packages: leios-ntc-queries\n",
}
# Run in the image by bash, one key and value a line, as the chain oracle's measurement (its `MEASURE`): under
# `set -euo pipefail` a failing command ends it and the last line is missing. A checkout is unchanged only when
# it equals its commit with no untracked or ignored file. Cabal must find the executable up to date offline.
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
    f"dry=$(cabal build {PACKAGE} --offline --dry-run --extra-lib-dirs=/usr/local/lib 2>/dev/null)",
    'if printf "%s\\n" "$dry" | grep -qx "Up to date"; then echo "build current"; fi',
    f"binary=$(cabal list-bin {PACKAGE})",
    'line=$(sha256sum "$binary")',
    'echo "built ${line%% *}"',
    'echo "measured all"',
])
LEDGER = "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c"
CONSENSUS = "2416493bfdbe93b761b3a4bb1ccf2b4a913e5e4b"

# The ledger sources the answers depend on, with the SHA-256 of each file at LEDGER. Replay and record refuse an
# image whose copies differ.
SOURCES = {
    "Api/State/Query.hs": "libs/cardano-ledger-api/src/Cardano/Ledger/Api/State/Query.hs",
    "State/StakePool.hs": "libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs",
    "State/CertState.hs": "libs/cardano-ledger-core/src/Cardano/Ledger/State/CertState.hs",
    "State/SnapShots.hs": "libs/cardano-ledger-core/src/Cardano/Ledger/State/SnapShots.hs",
    "State/LeiosCommittee.hs": "libs/cardano-ledger-core/src/Cardano/Ledger/State/LeiosCommittee.hs",
    "Binary/Encoding/EncCBOR.hs": "libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Encoding/EncCBOR.hs",
    "Binary/Encoding/Encoder.hs": "libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Encoding/Encoder.hs",
    "Shelley/LedgerState/Types.hs": "eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/Types.hs",
}
PINNED = {
    "Api/State/Query.hs": "41f79c623baa1eb72f7df965b5641b14a0c87ec235303cfea44a466a066df794",
    "State/StakePool.hs": "459d7de8a2c1bb070476cb0bebb0cd758efeea61ea7e450306af3654f1592e70",
    "State/CertState.hs": "d277d9ced2007b55a237a85133599a635ad571acf7fe7acd5e0082fa40023e86",
    "State/SnapShots.hs": "ae7d5e0db3afe5f97183008047d2f7ecf2974c7d1249752284a600b55557d414",
    "State/LeiosCommittee.hs": "2bbdb60617b43aa92a9b7bfaaf2a951767690d581f38f2f8cd9b018e653e11bb",
    "Binary/Encoding/EncCBOR.hs": "4d788d5940b186a6e5052a1f65a7e84ec4487b38c5e355bcbac5dc41281ef8dd",
    "Binary/Encoding/Encoder.hs": "3c6f222efddd43995afa425a18a748d35ca8f629799d5d2a362629db4bd50b47",
    "Shelley/LedgerState/Types.hs": "3c0f5153c41cf428c4d1843529d6cea605f6a447c83711acb2858fb529abe7c6",
}
# A pool id no pool is registered under, which the restricted queries also ask for.
UNREGISTERED = "ff" * 28


def single(path, buf, span, what):
    """The value of a one-byte CBOR item, refusing anything else."""
    if span[1] - span[0] != 1:
        raise SystemExit(f"{path}: {what} expected at {span[0]}")
    return buf[span[0]]


def new_epoch_state(path, buf):
    """The era and the span of the ledger's `NewEpochState` in a `state` file of the Leios build.

    The file is the versioned extended state `[1, [ledger, header, Peras state]]`; the ledger is the hard-fork
    telescope, the past eras and the current one (`[bound, state]`), whose number gives the era; the current
    era's state is `encodeShelleyLedgerState` (ouroboros-consensus 2416493b `Shelley/Ledger/Ledger.hs:885-903`):
    `[2, [tip, NewEpochState, transition, cumulative transaction bytes]]`."""
    outer = CBOR.items(buf, 0)
    if len(outer) != 2 or single(path, buf, outer[0], "the version") != 1 or outer[1][1] != len(buf):
        raise SystemExit(f"{path}: the extended state of version 1 expected")
    extended = CBOR.items(buf, outer[1][0])
    if len(extended) != 3:
        raise SystemExit(f"{path}: the extended state's three parts expected")
    telescope = CBOR.items(buf, extended[0][0])
    if len(telescope) not in ERAS:
        raise SystemExit(f"{path}: a telescope of {len(telescope)} eras, not Conway's or Dijkstra's")
    current = CBOR.items(buf, telescope[-1][0])
    versioned = CBOR.items(buf, current[1][0]) if len(current) == 2 else []
    if len(versioned) != 2 or single(path, buf, versioned[0], "the format version") != 2:
        raise SystemExit(f"{path}: the Shelley ledger state of format version 2 expected")
    shelley = CBOR.items(buf, versioned[1][0])
    if len(shelley) != 4:
        raise SystemExit(f"{path}: the Shelley ledger state's four fields expected")
    return ERAS[len(telescope)][0], shelley[1]


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def file_sha256(path):
    return sha256((ROOT / path).read_bytes())


def state_path(name):
    return f"{INPUTS}/{name}/state"


def genesis_files():
    """The digests of the Shelley genesis files, each refused unless its `networkId` is the one `Main.hs` uses."""
    for path in sorted(set(GENESIS.values())):
        if json.loads((ROOT / path).read_text()).get("networkId") != NETWORK:
            raise SystemExit(f"{path}: the network is not {NETWORK}")
    return {path: file_sha256(path) for path in sorted(set(GENESIS.values()))}


def inputs():
    """Each state's identity, era and epoch (the `NewEpochState`'s first field), with its `NewEpochState`'s
    bytes."""
    genesis_files()
    found = []
    for name in STATES:
        state = (ROOT / state_path(name)).read_bytes()
        era, (start, end) = new_epoch_state(state_path(name), state)
        nes = state[start:end]
        epoch = CBOR.value(nes, CBOR.items(nes, 0)[0][0])
        if not isinstance(epoch, int):
            raise SystemExit(f"{state_path(name)}: the NewEpochState's epoch is not a number")
        found.append(({"name": name, "era": era, "epoch": epoch, "stateSha256": sha256(state),
                       "newEpochStateSpan": [start, end], "newEpochStateSha256": sha256(nes)}, nes))
    return found


def container(name, *options, read_only=True):
    return ["docker", "run", "--rm", "--name", name, *container_labels("oracle", name), "--network", "none",
            "--cpus", "8", "--memory", "24g", *(["--read-only"] if read_only else []), *options]


def measure(image_id, name):
    """The keys and values MEASURE prints in the image, which must run to its end."""
    # cabal writes its caches even for a dry run, so this container alone is writable; it is removed after.
    done = subprocess.run([*container(name, "--entrypoint", "bash", read_only=False), image_id,
                           "-c", MEASURE], capture_output=True, text=True)
    measured = {}
    for line in done.stdout.splitlines():
        if " " in line:
            key, value = line.split(" ", 1)
            measured.setdefault(key, []).append(value)
    if done.returncode != 0 or measured.get("measured") != ["all"]:
        raise SystemExit(f"{image_id}: the image could not be measured (exit {done.returncode}): "
                         f"{done.stderr.strip()[-400:]}")
    return measured


def image_binding(image_id, name):
    """The image runs the build of the repository's oracle against the pinned, unchanged checkouts: the digests of
    its harness, build files and executable, and the checkouts' commits."""
    if CHAIN.ledger_pin() != LEDGER:
        raise SystemExit(f"the cabal project pins cardano-ledger {CHAIN.ledger_pin()}, not {LEDGER}")
    if CHAIN.entrypoint(image_id) != [IMAGE_BINARY]:
        raise SystemExit(f"{image_id} does not run {IMAGE_BINARY}")
    measured = measure(image_id, name)
    files = {key[len("file:"):]: values[0] for key, values in measured.items()
             if key.startswith("file:") and len(values) == 1}
    sources = {key[len("source:"):]: values for key, values in measured.items() if key.startswith("source:")}
    built = measured.get("built", [""])
    refusals = [
        (all(CHAIN.DIGEST.fullmatch(files.get(path, "")) for path in (IMAGE_HARNESS, *IMAGE_BUILD))
         and len(built) == 1 and CHAIN.DIGEST.fullmatch(built[0]) is not None,
         "an image file or the built executable could not be measured"),
        (files.get(IMAGE_HARNESS) == sha256(REPOSITORY_FILES[IMAGE_HARNESS]),
         "stale oracle: the image's Main.hs is not the repository's"),
        (all(files.get(path) == sha256(content) for path, content in REPOSITORY_FILES.items()),
         "the image's package or cabal project is not the repository's"),
        (sorted(sources) == CHAIN.pinned(),
         f"the image's checkouts are not those the cabal project pins: {sorted(sources)}"),
        (all(values == ["unchanged"] for values in sources.values()), "a pinned checkout differs from its commit"),
        (measured.get("build") == ["current"], "stale executable: cabal would rebuild it from the image's sources"),
        (built == [files.get(IMAGE_BINARY)], "the entry point is not the executable cabal built"),
    ]
    for holds, why in refusals:
        if not holds:
            raise SystemExit(f"{image_id}: {why}")
    return {"imageFilesSha256": {path: files[path] for path in (IMAGE_HARNESS, *IMAGE_BUILD)},
            "pinnedSources": {commit: "unchanged" for commit in sorted(sources)}}


def refusal(image_id, binding, provenance):
    """Why the provenance does not accept this image, or None: it accepts only the image it recorded, by its
    immutable ID, with the same executable, build files and checkouts. The ID binds the layers the measurement
    does not read (the base image, the runtime libraries)."""
    oracle = provenance.get("oracle") if isinstance(provenance, dict) else None
    if not isinstance(oracle, dict) or "imageFilesSha256" not in oracle:
        return "the fixture accepts no oracle build yet: record with --accept-build"
    if image_id != provenance.get("imageId"):
        return (f"the image {image_id} is not the one the provenance accepted ({provenance.get('imageId')}): "
                "record with --accept-build only for an image reviewed anew")
    if (binding["imageFilesSha256"] != oracle["imageFilesSha256"]
            or binding["pinnedSources"] != oracle.get("pinnedSources")):
        return ("the image's executable or build files differ from the accepted oracle build "
                "(record with --accept-build only for a build reviewed anew)")
    return None


def accepted(image_id, binding, accept_build):
    """The binding, if the provenance accepted this image ([[refusal]]), or if it is accepted anew."""
    if accept_build:
        return binding
    why = refusal(image_id, binding, json.loads(PROVENANCE.read_text()) if PROVENANCE.is_file() else None)
    if why is not None:
        raise SystemExit(why)
    return binding


def source_hashes(image_id, name):
    script = "; ".join(
        f"sha256sum $(find /oracle/leios-testnet/dist-newstyle/src -path '*/{path}')" for path in SOURCES.values())
    output = subprocess.check_output(
        [*container(name, "--entrypoint", "sh"), image_id, "-c", script], text=True).splitlines()
    if len(output) != len(SOURCES):
        raise SystemExit(f"expected one copy of each pinned source in the image, found {output}")
    found = {label: line.split()[0] for label, line in zip(SOURCES, output, strict=True)}
    differing = sorted(label for label in SOURCES if found[label] != PINNED[label])
    if differing:
        raise SystemExit(f"{IMAGE} does not hold the sources of cardano-ledger {LEDGER}: {differing}")
    return found


def pool_ids(result):
    """The keys of a `GetStakePoolState` answer, a map from 28-byte pool ids, in the order written."""
    buf = bytes.fromhex(result)
    if CBOR.skip(buf, 0) != len(buf):
        raise SystemExit("a GetStakePoolState answer with trailing bytes")
    keys = []
    for (start, end), _ in CBOR.entries(buf, 0):
        key = CBOR.value(buf, start)
        if not isinstance(key, bytes) or len(key) != 28:
            raise SystemExit("a GetStakePoolState answer keyed by other than a 28-byte pool id")
        keys.append(key.hex())
    return keys


def pool_fields(result, pool):
    """The spans of the 14 fields of one pool's entry in a `GetStakePoolState` answer, with the answer's bytes."""
    buf = bytes.fromhex(result)
    for (start, _), (value, _) in CBOR.entries(buf, 0):
        if CBOR.value(buf, start).hex() == pool:
            fields = CBOR.items(buf, value)
            if len(fields) != 14:
                raise SystemExit("a pool state of other than 14 fields")
            return buf, fields
    raise SystemExit(f"no entry for {pool}")


def check(case, result):
    """The oracle's answers on one state are what was asked: the era's version, the state read back to its own
    bytes, the five argument sets in order, each restricted answer the full one's pools that were asked for, the
    committee, and the two answers on the derived state: the smallest pool alone, its fields those of the full
    answer but for its retirement, the epoch after the state's, and its future parameters, present."""
    name, era = case["name"], case["era"]
    version = dict(ERAS.values())[era]
    if result.get("version") != version or result.get("roundTrip") is not True:
        raise SystemExit(f"{name}: not read at version {version} and written back to the same bytes")
    cases = result["cases"]
    every = pool_ids(cases[0]["result"])
    if every != sorted(every) or len(every) != result["pools"] or UNREGISTERED in every:
        raise SystemExit(f"{name}: the pools of the full answer are not the {result['pools']} registered, in order")
    selected = sorted(every[:2] + [UNREGISTERED])
    restricted = [(selected, False), (selected, True)] if len(every) >= 2 else []
    asked = [(None, True), (None, False), *restricted, ([], True)]
    pool_cases = [c for c in cases if c["query"] == "GetStakePoolState"]
    plain = [c for c in pool_cases if "derived" not in c]
    derived = [c for c in pool_cases if "derived" in c]
    smallest = every[:1]
    if ([(c["tag"], c["poolIds"], c["includeDelegators"]) for c in plain] != [(40, ids, b) for ids, b in asked]
            or [c["query"] for c in cases]
            != ["GetStakePoolState"] * len(asked) + ["GetLeiosCommittee"] + ["GetStakePoolState"] * len(derived)
            or cases[len(asked)]["tag"] != 41
            or [(c["tag"], c["poolIds"], c["includeDelegators"], c["derived"], c["retiringEpoch"], c["network"])
                for c in derived]
            != [(40, smallest, b, "retiring+future", case["epoch"] + 1, NETWORK)
                for b in (True, False) if smallest]):
        raise SystemExit(f"{name}: the cases are not the queries asked")
    for answer in pool_cases:
        expected = every if answer["poolIds"] is None else [p for p in every if p in answer["poolIds"]]
        if pool_ids(answer["result"]) != expected:
            raise SystemExit(f"{name}: an answer for {answer['poolIds']} holds other pools than those asked for")
    for answer in derived:
        buf, fields = pool_fields(answer["result"], smallest[0])
        full, original = pool_fields(cases[0 if answer["includeDelegators"] else 1]["result"], smallest[0])
        unchanged = [buf[a:b] for a, b in fields[:12]] == [full[a:b] for a, b in original[:12]]
        if (not unchanged or CBOR.value(buf, fields[12][0]) != answer["retiringEpoch"]
                or buf[fields[13][0]] == 0xF6 or [full[a:b] for a, b in original[12:]] != [b"\xf6", b"\xf6"]):
            raise SystemExit(f"{name}: the derived answer is not the pool's own with a retirement and future parameters")


def run_image(image_id, name, states):
    results = []
    with tempfile.TemporaryDirectory(prefix="scarab-leios-ntc-queries-") as scratch:
        for case, nes in states:
            (Path(scratch) / f"{case['name']}.cbor").write_bytes(nes)
        for case, _ in states:
            done = subprocess.run(
                [*container(name, "-v", f"{scratch}:/in:ro"),
                 image_id, case["era"], f"/in/{case['name']}.cbor"],
                capture_output=True, text=True)
            if done.returncode:
                raise SystemExit(f"the oracle failed on {case['name']}: {done.stderr}")
            result = json.loads(done.stdout)
            check(case, result)
            results.append(result)
    return results


def measured_image(name, accept_build):
    """The image resolved once to its ID, measured, its build accepted, and its ledger sources the pinned ones."""
    image_id = CHAIN.resolve(IMAGE)
    binding = accepted(image_id, image_binding(image_id, name), accept_build)
    return image_id, binding, source_hashes(image_id, name)


def recorded():
    return [json.loads(line) for line in CASES_FILE.read_text().splitlines() if line]


PROVENANCE_FIELDS = {"cases", "casesSha256", "consensusSourceCommit", "image", "imageId", "inputs", "ledger",
                     "measuredAt", "oracle", "packageSha256", "program", "programSha256", "schemaVersion", "scope",
                     "sourceSha256"}
ORACLE_FIELDS = {"imageFilesSha256", "network", "oracleBinarySha256", "pinnedSources"}
LEDGER_RECORD = {"repository": "IntersectMBO/cardano-ledger", "revision": LEDGER,
                 "packages": ["cardano-ledger-api 1.16.0.0", "cardano-ledger-core 1.23.0.0"]}
DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")


def validate(provenance, rows, cases_sha256, cases):
    """What is wrong with a retained fixture: its provenance (`provenance.json`, parsed), its rows (`cases.jsonl`,
    parsed) and the digest of its case file, against the cases `inputs` makes from the state files and against
    the repository. Each field of the provenance is checked: its schema exactly, the pins, the ledger sources,
    and the oracle build it accepted (no network, the image's ID, every build file the repository determines,
    the executable's digest and the pinned checkouts)."""
    problems = []

    def require(holds, why):
        if not holds:
            problems.append(why)

    require(isinstance(provenance, dict) and set(provenance) == PROVENANCE_FIELDS,
            f"the provenance's fields are not exactly {sorted(PROVENANCE_FIELDS)}")
    if problems:
        return problems
    require(provenance["schemaVersion"] == 1, "schemaVersion is not 1")
    require(isinstance(provenance["scope"], str) and provenance["scope"].strip() != "", "no scope")
    require(isinstance(provenance["measuredAt"], str) and DATE.fullmatch(provenance["measuredAt"]) is not None,
            "measuredAt is not a date")
    require(provenance["image"] == IMAGE, f"the image is not {IMAGE}")
    require(isinstance(provenance["imageId"], str) and CHAIN.IMAGE_ID.fullmatch(provenance["imageId"]) is not None,
            "imageId is not an immutable image ID")
    require(LEDGER == CHAIN.ledger_pin(), f"the cabal project does not pin cardano-ledger {LEDGER}")
    require(provenance["ledger"] == LEDGER_RECORD, f"the ledger is not cardano-ledger {LEDGER}")
    require(provenance["consensusSourceCommit"] == CONSENSUS, f"the consensus revision is not {CONSENSUS}")
    require(provenance["sourceSha256"] == PINNED, "the ledger sources are not the pinned ones")
    require(provenance["program"] == PROGRAM and provenance["programSha256"] == file_sha256(PROGRAM),
            f"{PROGRAM} differs from the recorded program")
    require(provenance["packageSha256"] == sha256(REPOSITORY_FILES[IMAGE_BUILD[0]]),
            f"{PACKAGE}.cabal differs from the recorded package")
    require(provenance["inputs"] == {**{state_path(c["name"]): c["stateSha256"] for c in cases}, **genesis_files()},
            "the input files differ from the provenance")
    require(provenance["cases"] == len(rows) == len(STATES), f"the fixture does not hold its {len(STATES)} states")
    require(provenance["casesSha256"] == cases_sha256, f"{CASES_FILE.name} differs from its provenance")
    require([{k: v for k, v in r.items() if k != "reference"} for r in rows] == cases,
            "the inputs differ from the recorded states")
    oracle = provenance["oracle"]
    require(isinstance(oracle, dict) and set(oracle) == ORACLE_FIELDS,
            f"the oracle build's fields are not exactly {sorted(ORACLE_FIELDS)}")
    if problems:
        return problems
    require(oracle["network"] == "none", "the oracle ran with a network")
    files = oracle["imageFilesSha256"]
    require(isinstance(files, dict) and set(files) == {IMAGE_HARNESS, *IMAGE_BUILD}
            and all(isinstance(v, str) and CHAIN.DIGEST.fullmatch(v) for v in files.values()),
            f"the image's files are not exactly {sorted((IMAGE_HARNESS, *IMAGE_BUILD))}, each with a SHA-256")
    if problems:
        return problems
    for path, content in REPOSITORY_FILES.items():
        require(files[path] == sha256(content), f"the accepted build's {path} is not the repository's")
    require(oracle["oracleBinarySha256"] == files[IMAGE_BINARY], "the executable's digest is not the accepted build's")
    require(oracle["pinnedSources"] == {commit: "unchanged" for commit in CHAIN.pinned()},
            "the accepted build's checkouts are not the cabal project's pins, unchanged")
    for row in rows:
        if not problems and isinstance(row.get("reference"), dict):
            try:
                check({k: v for k, v in row.items() if k != "reference"}, row["reference"])
            except SystemExit as refused:
                problems.append(str(refused))
    return problems


def offline(cases):
    """What is wrong with the retained fixture against its inputs and the repository."""
    return validate(json.loads(PROVENANCE.read_text()), recorded(), file_sha256(CASES_FILE.relative_to(ROOT)), cases)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--record", action="store_true")
    mode.add_argument("--replay", action="store_true")
    parser.add_argument("--name", default="leios-ntc-queries",
                        help="the container name and owner label of the oracle's runs")
    parser.add_argument("--accept-build", action="store_true",
                        help="with --record: accept the image's executable and build files anew")
    args = parser.parse_args()
    if args.accept_build and not args.record:
        parser.error("--accept-build only with --record")
    states = inputs()
    cases = [case for case, _ in states]
    if args.record:
        image_id, binding, sources = measured_image(args.name, args.accept_build)
        results = run_image(image_id, args.name, states)
        FIXTURE.mkdir(parents=True, exist_ok=True)
        CASES_FILE.write_text("".join(json.dumps(dict(case, reference=result), sort_keys=True) + "\n"
                                      for case, result in zip(cases, results, strict=True)))
        PROVENANCE.write_text(json.dumps({
            "schemaVersion": 1,
            "scope": "The unchanged queryStakePoolState and queryLeiosCommittee of the Leios testnet build, which "
                     "ouroboros-consensus answers GetStakePoolState (tag 40) and GetLeiosCommittee (tag 41) with, "
                     "on the NewEpochState of three Leios-build state files (a devnet's last Conway state and first "
                     "Dijkstra state, and the public Leios testnet at epoch 100), and GetStakePoolState for the "
                     "smallest pool on each state derived with the ledger's own lenses so that pool retires at the "
                     "next epoch and has future parameters (stakePoolStateToStakePoolParams, network Testnet); each "
                     "answer written with encCBOR at "
                     "the era's lowest version as consensus's toEraCBOR writes it. The era-level result only: not "
                     "the hard-fork or node-to-client envelope, nor the query's dispatch, which consensus does",
            "image": IMAGE,
            "imageId": image_id,
            "oracle": {"imageFilesSha256": binding["imageFilesSha256"],
                       "oracleBinarySha256": binding["imageFilesSha256"][IMAGE_BINARY],
                       "pinnedSources": binding["pinnedSources"], "network": "none"},
            "ledger": LEDGER_RECORD,
            "consensusSourceCommit": CONSENSUS,
            "sourceSha256": sources,
            "inputs": {**{state_path(case["name"]): case["stateSha256"] for case in cases}, **genesis_files()},
            "program": PROGRAM,
            "programSha256": file_sha256(PROGRAM),
            "packageSha256": file_sha256(f"scripts/oracle/leios-ntc-queries/{PACKAGE}.cabal"),
            "cases": len(cases),
            "casesSha256": file_sha256(CASES_FILE.relative_to(ROOT)),
            "measuredAt": datetime.date.today().isoformat(),
        }, indent=2, sort_keys=True) + "\n")
        for case, result in zip(cases, results, strict=True):
            print(f"recorded {case['name']}: {result['pools']} pools, "
                  + ", ".join(f"{len(c['result']) // 2} bytes" for c in result["cases"]))
        return 0
    problems = offline(cases)
    if problems:
        print("; ".join(problems) + ": run --record", file=sys.stderr)
        return 1
    if args.replay:
        image_id, _, _ = measured_image(args.name, False)
        fresh = run_image(image_id, args.name, states)
        if fresh != [row["reference"] for row in recorded()]:
            print("the oracle's answers differ from the fixture", file=sys.stderr)
            return 1
        print(f"{', '.join(STATES)}: the image's answers equal the fixture")
        return 0
    print(f"{', '.join(STATES)}: the inputs, the program and the fixture match the provenance")
    return 0


if __name__ == "__main__":
    sys.exit(main())
