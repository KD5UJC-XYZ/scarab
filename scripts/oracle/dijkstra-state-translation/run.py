#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reference translation of a captured Conway ledger state into Dijkstra by the Leios testnet build (#826).

The input is the Leios build's snapshot of the last Conway block of a local devnet
(`fixtures/leios/state/conway-195`). This script takes the ledger's `NewEpochState` out of the snapshot's
extended state and the oracle (`Main.hs`) decodes it at Conway's version, runs the unchanged
`TranslateEra DijkstraEra NewEpochState` with the network's Dijkstra genesis as context, and writes the
Dijkstra state at Dijkstra's version; it also reads the snapshot's UTxO table and upgrades each output as
ejecting it into Dijkstra does. The README says which parts of the crossing are the consensus layer's.

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


# The chain oracle's image resolution and cabal-project readers.
CHAIN = _chain()

FIXTURE = ROOT / "fixtures/reference/dijkstra-state-translation"
CASES_FILE = FIXTURE / "cases.jsonl"
PROVENANCE = FIXTURE / "provenance.json"
INPUTS = "fixtures/leios/state"
CASE = "conway-195"
STATE = f"{INPUTS}/{CASE}/state"
TABLES = f"{INPUTS}/{CASE}/tables"
GENESIS = f"{INPUTS}/genesis/dijkstra-genesis.json"
CONFIG = f"{INPUTS}/genesis/config.json"
IMAGE = "scarab-dijkstra-state-translation-oracle:local"
PROGRAM = "scripts/oracle/dijkstra-state-translation/Main.hs"
PACKAGE = "scarab-dijkstra-state-translation"
PROJECT = "/oracle/leios-testnet"
IMAGE_HARNESS = f"{PROJECT}/dijkstra-state-translation/Main.hs"
IMAGE_BINARY = f"/usr/local/bin/{PACKAGE}"
IMAGE_BUILD = (f"{PROJECT}/dijkstra-state-translation/{PACKAGE}.cabal", f"{PROJECT}/cabal.project",
               f"{PROJECT}/cabal.project.local", IMAGE_BINARY)
# What each of the image's files must be: the repository's file, or the line the Dockerfile writes.
REPOSITORY_FILES = {
    IMAGE_HARNESS: (HERE / "Main.hs").read_bytes(),
    IMAGE_BUILD[0]: (HERE / f"{PACKAGE}.cabal").read_bytes(),
    IMAGE_BUILD[1]: (ROOT / "scripts/oracle/leios-testnet/cabal.project").read_bytes(),
    IMAGE_BUILD[2]: b"packages: dijkstra-state-translation\n",
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

# The ledger sources the result depends on, with the SHA-256 of each file at LEDGER. Replay and record
# refuse an image whose copies differ.
SOURCES = {
    "Dijkstra/Translation.hs": "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Translation.hs",
    "Dijkstra/Genesis.hs": "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Genesis.hs",
    "Dijkstra/PParams.hs": "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs",
    "Dijkstra/TxBody.hs": "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxBody.hs",
    "Dijkstra/TxOut.hs": "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxOut.hs",
    "Dijkstra/Scripts.hs": "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs",
    "Conway/TxOut.hs": "eras/conway/impl/src/Cardano/Ledger/Conway/TxOut.hs",
    "Conway/Governance/Proposals.hs": "eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Proposals.hs",
    "Conway/Governance/DRepPulser.hs": "eras/conway/impl/src/Cardano/Ledger/Conway/Governance/DRepPulser.hs",
    "Babbage/TxOut.hs": "eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs",
    "Shelley/LedgerState/Types.hs": "eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/Types.hs",
    "State/CertState.hs": "libs/cardano-ledger-core/src/Cardano/Ledger/State/CertState.hs",
    "Core/Translation.hs": "libs/cardano-ledger-core/src/Cardano/Ledger/Core/Translation.hs",
}
PINNED = {
    "Dijkstra/Translation.hs": "17152a4b587514eb00a2bd36cbf7043daa814018c4a29795d6184252fbb76923",
    "Dijkstra/Genesis.hs": "d8fa3a55eabd8407370d657532833c4b1fdca50f79428e86c7a4f15d4102b04a",
    "Dijkstra/PParams.hs": "2342bd7fcc497dd9aa312ce13994205a6acb5eec278c9001df035d99d8dbc8cf",
    "Dijkstra/TxBody.hs": "926751caf4d53d18defb467c2a8a00c96e98c3464c6a68c3a6b882c2df34a612",
    "Dijkstra/TxOut.hs": "802666de93f4d756e57ea176520c05d5fb42d624f53044f36f48e1232102694f",
    "Dijkstra/Scripts.hs": "50c37164804f795343c7505446eb6f43117b93e24b7717a6940921d8f9c995a7",
    "Conway/TxOut.hs": "48eae221ccc4665faf2a88ee10120691f4a14be4f1eeecab0d38d1ac6284a92b",
    "Conway/Governance/Proposals.hs": "a7b56cacf6fb38a13291cde2914fb06e12549e82b52002e43e2d24e6fdfe4ebe",
    "Conway/Governance/DRepPulser.hs": "4842168df798e28ae859504dc8de2bb287b74cceb125b745ff004e99a811ddee",
    "Babbage/TxOut.hs": "8062da7b84f001fd7e4a390b1c1a11bff6082affa3a15cde3b43613b50eae114",
    "Shelley/LedgerState/Types.hs": "3c0f5153c41cf428c4d1843529d6cea605f6a447c83711acb2858fb529abe7c6",
    "State/CertState.hs": "d277d9ced2007b55a237a85133599a635ad571acf7fe7acd5e0082fa40023e86",
    "Core/Translation.hs": "c1e35a92f7779e470b51060cbdb4044345897662f898c48a0829a2e003708912",
}


def head(buf, pos):
    """A CBOR head at pos: (major type, argument or None for an indefinite length, offset after it)."""
    first = buf[pos]
    major, info = first >> 5, first & 31
    pos += 1
    if info < 24:
        return major, info, pos
    if info == 31:
        return major, None, pos
    if info > 27:
        raise SystemExit(f"CBOR: reserved additional information {info} at {pos - 1}")
    size = 1 << (info - 24)
    return major, int.from_bytes(buf[pos:pos + size], "big"), pos + size


def skip(buf, pos):
    """The offset after the CBOR item at pos."""
    major, arg, pos = head(buf, pos)
    if major in (0, 1, 7):
        return pos
    if major in (2, 3):
        if arg is not None:
            return pos + arg
        while buf[pos] != 0xFF:
            pos = skip(buf, pos)
        return pos + 1
    if major == 6:
        return skip(buf, pos)
    items = None if arg is None else arg * (2 if major == 5 else 1)
    count = 0
    while (buf[pos] != 0xFF) if items is None else (count < items):
        pos = skip(buf, pos)
        count += 1
    return pos + 1 if items is None else pos


def elements(buf, pos, length):
    """The (start, end) of each element of the definite array of `length` items at pos."""
    major, arg, pos = head(buf, pos)
    if major != 4 or arg != length:
        raise SystemExit(f"{STATE}: an array of {length} expected at {pos}, found major type {major} of {arg}")
    spans = []
    for _ in range(length):
        end = skip(buf, pos)
        spans.append((pos, end))
        pos = end
    return spans


def new_epoch_state(buf):
    """The span of the ledger's `NewEpochState` in a snapshot's `state` file of the Leios build at Conway.

    The file is the versioned extended state `[1, [ledger, header, Peras state]]`; the ledger is the
    hard-fork telescope, six past eras and the current one (`[bound, state]`); Conway's state is
    `encodeShelleyLedgerState` (ouroboros-consensus 2416493b `Shelley/Ledger/Ledger.hs:885-903`):
    `[2, [tip, NewEpochState, transition, cumulative transaction bytes]]`."""
    outer = elements(buf, 0, 2)
    if buf[outer[0][0]:outer[0][1]] != b"\x01" or outer[1][1] != len(buf):
        raise SystemExit(f"{STATE}: the extended state of version 1 expected")
    extended = elements(buf, outer[1][0], 3)
    telescope = elements(buf, extended[0][0], 7)
    current = elements(buf, telescope[6][0], 2)
    versioned = elements(buf, current[1][0], 2)
    if buf[versioned[0][0]:versioned[0][1]] != b"\x02":
        raise SystemExit(f"{STATE}: the Shelley ledger state of format version 2 expected")
    return elements(buf, versioned[1][0], 4)[1]


def table_entries(buf):
    """The (key, value) byte strings of a `tables` file: an array of one map of byte strings
    (`valuesMKEncoder`, ouroboros-consensus 2416493b `Ledger/Tables.hs:276-282`), in key order."""
    major, arg, pos = head(buf, 0)
    if (major, arg) != (4, 1):
        raise SystemExit(f"{TABLES}: an array of one map expected")
    major, count, pos = head(buf, pos)
    if major != 5:
        raise SystemExit(f"{TABLES}: a map expected")
    entries = []
    while (buf[pos] != 0xFF) if count is None else (len(entries) < count):
        pair = []
        for _ in range(2):
            major, size, start = head(buf, pos)
            if major != 2 or size is None:
                raise SystemExit(f"{TABLES}: a definite byte string expected at {pos}")
            pos = start + size
            pair.append(buf[start:pos].hex())
        entries.append(tuple(pair))
    if skip(buf, 0) != len(buf):
        raise SystemExit(f"{TABLES}: trailing bytes")
    return sorted(entries)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def file_sha256(path):
    return sha256((ROOT / path).read_bytes())


def inputs():
    """The input identities, checked: the Dijkstra genesis file is the one the devnet's nodes read
    (its Blake2b-256 is the configuration's `DijkstraGenesisHash`)."""
    genesis = (ROOT / GENESIS).read_bytes()
    named = json.loads((ROOT / CONFIG).read_text())["DijkstraGenesisHash"]
    if hashlib.blake2b(genesis, digest_size=32).hexdigest() != named:
        raise SystemExit(f"{GENESIS} is not the file {CONFIG} names")
    state = (ROOT / STATE).read_bytes()
    start, end = new_epoch_state(state)
    return {
        "name": CASE,
        "stateSha256": sha256(state),
        "tablesSha256": file_sha256(TABLES),
        "dijkstraGenesisSha256": sha256(genesis),
        "newEpochStateSpan": [start, end],
        "newEpochStateSha256": sha256(state[start:end]),
    }, state[start:end]


def container(name, *options, read_only=True):
    return ["docker", "run", "--rm", "--name", name, *container_labels("oracle", name), "--network", "none",
            *(["--read-only"] if read_only else []), *options]


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


def run_image(image_id, name, nes):
    with tempfile.TemporaryDirectory(prefix="scarab-dijkstra-state-translation-") as scratch:
        (Path(scratch) / "nes.cbor").write_bytes(nes)
        done = subprocess.run(
            [*container(name, "--cpus", "8", "--memory", "24g",
                        "-v", f"{scratch}:/in:ro", "-v", f"{ROOT / INPUTS}:/inputs:ro"),
             image_id, "/in/nes.cbor", f"/inputs/{CASE}/tables", "/inputs/genesis/dijkstra-genesis.json"],
            capture_output=True, text=True)
    if done.returncode:
        raise SystemExit(f"the oracle failed: {done.stderr}")
    result = json.loads(done.stdout)
    if (result["conwayVersion"], result["dijkstraVersion"]) != (9, 12):
        raise SystemExit(f"unexpected era versions: {result['conwayVersion']}, {result['dijkstraVersion']}")
    if not result["conwayRoundTrip"]:
        raise SystemExit("the Conway NewEpochState does not encode back to the snapshot's bytes")
    if not result["dijkstraRoundTrip"]:
        raise SystemExit("the Dijkstra NewEpochState does not decode and encode back to the same bytes")
    read = [(o["txIn"], o["conwayTxOut"]) for o in result["outputs"]]
    if read != table_entries((ROOT / TABLES).read_bytes()):
        raise SystemExit(f"the outputs the oracle read are not the entries of {TABLES}, byte for byte")
    return result


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
                 "packages": ["cardano-ledger-dijkstra 0.5.0.0", "cardano-ledger-conway 1.25.0.0"]}
DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")


def validate(provenance, rows, cases_sha256, case):
    """What is wrong with a retained fixture: its provenance (`provenance.json`, parsed), its rows (`cases.jsonl`,
    parsed) and the digest of its case file, against the case `inputs` makes from the input files and against
    the repository. Each field of the provenance is checked: its schema exactly, the pins, the ledger sources,
    and the oracle build it accepted (no network, the image's ID, every build file the repository determines,
    the executable's digest and the pinned checkouts)."""
    problems = []

    def check(holds, why):
        if not holds:
            problems.append(why)

    check(isinstance(provenance, dict) and set(provenance) == PROVENANCE_FIELDS,
          f"the provenance's fields are not exactly {sorted(PROVENANCE_FIELDS)}")
    if problems:
        return problems
    check(provenance["schemaVersion"] == 1, "schemaVersion is not 1")
    check(isinstance(provenance["scope"], str) and provenance["scope"].strip() != "", "no scope")
    check(isinstance(provenance["measuredAt"], str) and DATE.fullmatch(provenance["measuredAt"]) is not None,
          "measuredAt is not a date")
    check(provenance["image"] == IMAGE, f"the image is not {IMAGE}")
    check(isinstance(provenance["imageId"], str) and CHAIN.IMAGE_ID.fullmatch(provenance["imageId"]) is not None,
          "imageId is not an immutable image ID")
    check(LEDGER == CHAIN.ledger_pin(), f"the cabal project does not pin cardano-ledger {LEDGER}")
    check(provenance["ledger"] == LEDGER_RECORD, f"the ledger is not cardano-ledger {LEDGER}")
    check(provenance["consensusSourceCommit"] == CONSENSUS, f"the consensus revision is not {CONSENSUS}")
    check(provenance["sourceSha256"] == PINNED, "the ledger sources are not the pinned ones")
    check(provenance["program"] == PROGRAM and provenance["programSha256"] == file_sha256(PROGRAM),
          f"{PROGRAM} differs from the recorded program")
    check(provenance["packageSha256"] == sha256(REPOSITORY_FILES[IMAGE_BUILD[0]]),
          f"{PACKAGE}.cabal differs from the recorded package")
    check(provenance["inputs"] == {STATE: case["stateSha256"], TABLES: case["tablesSha256"],
                                   GENESIS: case["dijkstraGenesisSha256"]}, "the input files differ from the provenance")
    check(provenance["cases"] == len(rows) == 1, "the fixture does not hold its one case")
    check(provenance["casesSha256"] == cases_sha256, f"{CASES_FILE.name} differs from its provenance")
    check([{k: v for k, v in r.items() if k != "reference"} for r in rows] == [case],
          "the inputs differ from the recorded case")
    oracle = provenance["oracle"]
    check(isinstance(oracle, dict) and set(oracle) == ORACLE_FIELDS,
          f"the oracle build's fields are not exactly {sorted(ORACLE_FIELDS)}")
    if problems:
        return problems
    check(oracle["network"] == "none", "the oracle ran with a network")
    files = oracle["imageFilesSha256"]
    check(isinstance(files, dict) and set(files) == {IMAGE_HARNESS, *IMAGE_BUILD}
          and all(isinstance(v, str) and CHAIN.DIGEST.fullmatch(v) for v in files.values()),
          f"the image's files are not exactly {sorted((IMAGE_HARNESS, *IMAGE_BUILD))}, each with a SHA-256")
    if problems:
        return problems
    for path, content in REPOSITORY_FILES.items():
        check(files[path] == sha256(content), f"the accepted build's {path} is not the repository's")
    check(oracle["oracleBinarySha256"] == files[IMAGE_BINARY], "the executable's digest is not the accepted build's")
    check(oracle["pinnedSources"] == {commit: "unchanged" for commit in CHAIN.pinned()},
          "the accepted build's checkouts are not the cabal project's pins, unchanged")
    return problems


def offline(case):
    """What is wrong with the retained fixture against its inputs and the repository."""
    return validate(json.loads(PROVENANCE.read_text()), recorded(), file_sha256(CASES_FILE.relative_to(ROOT)), case)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--record", action="store_true")
    mode.add_argument("--replay", action="store_true")
    parser.add_argument("--name", default="dijkstra-state-translation",
                        help="the container name and owner label of the oracle's runs")
    parser.add_argument("--accept-build", action="store_true",
                        help="with --record: accept the image's executable and build files anew")
    args = parser.parse_args()
    if args.accept_build and not args.record:
        parser.error("--accept-build only with --record")
    case, nes = inputs()
    if args.record:
        image_id, binding, sources = measured_image(args.name, args.accept_build)
        result = run_image(image_id, args.name, nes)
        FIXTURE.mkdir(parents=True, exist_ok=True)
        CASES_FILE.write_text(json.dumps(dict(case, reference=result), sort_keys=True) + "\n")
        PROVENANCE.write_text(json.dumps({
            "schemaVersion": 1,
            "scope": "The unchanged TranslateEra DijkstraEra NewEpochState of the Leios testnet build on the ledger "
                     "state of a Leios-devnet snapshot at its last Conway block, with the network's Dijkstra genesis "
                     "as context: the Dijkstra NewEpochState at version 12; and Dijkstra's upgradeTxOut on each "
                     "output of the snapshot's UTxO table. Not the consensus layer's parts of the crossing (tip, "
                     "transition counter, cumulative transaction bytes, telescope, chain-dependent state, header "
                     "and Peras state), nor Dijkstra's TICK that follows it",
            "image": IMAGE,
            "imageId": image_id,
            "oracle": {"imageFilesSha256": binding["imageFilesSha256"],
                       "oracleBinarySha256": binding["imageFilesSha256"][IMAGE_BINARY],
                       "pinnedSources": binding["pinnedSources"], "network": "none"},
            "ledger": {"repository": "IntersectMBO/cardano-ledger", "revision": LEDGER,
                       "packages": ["cardano-ledger-dijkstra 0.5.0.0", "cardano-ledger-conway 1.25.0.0"]},
            "consensusSourceCommit": CONSENSUS,
            "sourceSha256": sources,
            "inputs": {STATE: case["stateSha256"], TABLES: case["tablesSha256"],
                       GENESIS: case["dijkstraGenesisSha256"]},
            "program": PROGRAM,
            "programSha256": file_sha256(PROGRAM),
            "packageSha256": file_sha256(f"scripts/oracle/dijkstra-state-translation/{PACKAGE}.cabal"),
            "cases": 1,
            "casesSha256": file_sha256(CASES_FILE.relative_to(ROOT)),
            "measuredAt": datetime.date.today().isoformat(),
        }, indent=2, sort_keys=True) + "\n")
        print(f"recorded {CASE}: {len(result['outputs'])} outputs, "
              f"{len(result['dijkstraNewEpochState']) // 2} bytes of Dijkstra NewEpochState")
        return 0
    problems = offline(case)
    if problems:
        print("; ".join(problems) + ": run --record", file=sys.stderr)
        return 1
    if args.replay:
        image_id, _, _ = measured_image(args.name, False)
        fresh = run_image(image_id, args.name, nes)
        if fresh != recorded()[0]["reference"]:
            print(f"the oracle's translation of {CASE} differs from the fixture", file=sys.stderr)
            return 1
        print(f"{CASE}: the image's translation equals the fixture")
        return 0
    print(f"{CASE}: the inputs, the program and the fixture match the provenance")
    return 0


if __name__ == "__main__":
    sys.exit(main())
