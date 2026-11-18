#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reference translation of a captured Conway ledger state into Dijkstra by the Leios testnet build (#826).

The input is the Leios build's snapshot of the last Conway block of a local devnet
(`fixtures/leios/state/conway-195`). This script takes the ledger's `NewEpochState` out of the snapshot's
extended state and the oracle (`Main.hs`) decodes it at Conway's version, runs the unchanged
`TranslateEra DijkstraEra NewEpochState` with the network's Dijkstra genesis as context, and writes the
Dijkstra state at Dijkstra's version; it also reads the snapshot's UTxO table and upgrades each output as
ejecting it into Dijkstra does. The README says which parts of the crossing are the consensus layer's.

  run.py                         offline: the inputs, the program and the fixture match the provenance
  run.py --replay [--name N]     run the image (no network, read-only) and compare with the fixture
  run.py --record [--name N]     run the image and rewrite the fixture and its provenance
"""
import argparse
import datetime
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from cleanup import container_labels  # noqa: E402

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
BASE_IMAGE = "scarab-leios-testnet:local"
PROGRAM = "scripts/oracle/dijkstra-state-translation/Main.hs"
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


def source_hashes(name):
    script = "; ".join(
        f"sha256sum $(find /oracle/leios-testnet/dist-newstyle/src -path '*/{path}')" for path in SOURCES.values())
    output = subprocess.check_output(
        ["docker", "run", "--rm", "--name", name, *container_labels("oracle", name), "--network", "none",
         "--read-only", "--entrypoint", "sh", IMAGE, "-c", script], text=True).splitlines()
    if len(output) != len(SOURCES):
        raise SystemExit(f"expected one copy of each pinned source in the image, found {output}")
    found = {label: line.split()[0] for label, line in zip(SOURCES, output, strict=True)}
    differing = sorted(label for label in SOURCES if found[label] != PINNED[label])
    if differing:
        raise SystemExit(f"{IMAGE} does not hold the sources of cardano-ledger {LEDGER}: {differing}")
    return found


def run_image(name, nes):
    with tempfile.TemporaryDirectory(prefix="scarab-dijkstra-state-translation-") as scratch:
        (Path(scratch) / "nes.cbor").write_bytes(nes)
        done = subprocess.run(
            ["docker", "run", "--rm", "--name", name, *container_labels("oracle", name),
             "--network", "none", "--read-only", "--cpus", "8", "--memory", "24g",
             "-v", f"{scratch}:/in:ro", "-v", f"{ROOT / INPUTS}:/inputs:ro",
             IMAGE, "/in/nes.cbor", f"/inputs/{CASE}/tables", "/inputs/genesis/dijkstra-genesis.json"],
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
    return result


def image_id(image):
    return subprocess.check_output(["docker", "image", "inspect", image, "--format", "{{.Id}}"], text=True).strip()


def recorded():
    return [json.loads(line) for line in CASES_FILE.read_text().splitlines() if line]


def offline(case):
    provenance = json.loads(PROVENANCE.read_text())
    rows = recorded()
    problems = []
    if [{k: v for k, v in r.items() if k != "reference"} for r in rows] != [case]:
        problems.append("the inputs differ from the recorded case")
    if provenance["programSha256"] != file_sha256(PROGRAM):
        problems.append(f"{PROGRAM} differs from the recorded program")
    if provenance["casesSha256"] != file_sha256(CASES_FILE.relative_to(ROOT)):
        problems.append(f"{CASES_FILE.name} differs from its provenance")
    if provenance["inputs"] != {STATE: case["stateSha256"], TABLES: case["tablesSha256"],
                                GENESIS: case["dijkstraGenesisSha256"]}:
        problems.append("the input files differ from the provenance")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--record", action="store_true")
    mode.add_argument("--replay", action="store_true")
    parser.add_argument("--name", default="dijkstra-state-translation",
                        help="the container name and owner label of the oracle's runs")
    args = parser.parse_args()
    case, nes = inputs()
    if args.record:
        sources = source_hashes(args.name)
        result = run_image(args.name, nes)
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
            "imageId": image_id(IMAGE),
            "baseImage": BASE_IMAGE,
            "baseImageId": image_id(BASE_IMAGE),
            "ledger": {"repository": "IntersectMBO/cardano-ledger", "revision": LEDGER,
                       "packages": ["cardano-ledger-dijkstra 0.5.0.0", "cardano-ledger-conway 1.25.0.0"]},
            "consensusSourceCommit": CONSENSUS,
            "sourceSha256": sources,
            "inputs": {STATE: case["stateSha256"], TABLES: case["tablesSha256"],
                       GENESIS: case["dijkstraGenesisSha256"]},
            "program": PROGRAM,
            "programSha256": file_sha256(PROGRAM),
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
        source_hashes(args.name)
        fresh = run_image(args.name, nes)
        if fresh != recorded()[0]["reference"]:
            print(f"the oracle's translation of {CASE} differs from the fixture", file=sys.stderr)
            return 1
        print(f"{CASE}: the image's translation equals the fixture")
        return 0
    print(f"{CASE}: the inputs, the program and the fixture match the provenance")
    return 0


if __name__ == "__main__":
    sys.exit(main())
