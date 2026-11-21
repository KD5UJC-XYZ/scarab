#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Reference states of the Leios testnet build at Dijkstra's epoch boundary (#54).

The inputs are two of the Leios build's state files (`fixtures/leios/state`): `dijkstra-203`, a devnet's first
Dijkstra state, and `testnet-2168639`, the public Leios testnet at epoch 100. This script takes the ledger's
`NewEpochState` out of each, and the oracle (`Main.hs`) decodes it at version 12, refuses it unless it encodes back
to the same bytes, and ticks it with Dijkstra's unchanged `TICK` (`applyTickNoEvents`, with the globals
`mkShelleyGlobals` makes of the network's Shelley genesis file) along two paths, each tick from the state the one
before reached:

  boundaries  the first slot of the next epoch, then of the epoch after: two boundaries, nothing between them;
  rewards     the first slot after the reward update's start point (the epoch's first slot plus the randomness
              stabilisation window), where RUPD starts the reward pulser; the next epoch's first slot; the same
              point of that epoch; and the first slot of the epoch after: two boundaries, each with a pulser.

It also derives, from the testnet's state only, one in which a parameter change is proposed (`ratify`: keys 47 and
48, with the yes votes of every hot committee credential, DRep and pool) and ticks it along both full paths, so that
the pulser started at the first boundary ratifies it and the second boundary enacts it (`ratification.json`); the
devnet's state has no committee member to ratify one. Two more derivations of the testnet's state propose actions
(`governance.json`): `remove-branches`, a forest in which the second boundary removes an enacted action's competing
sibling with its descendant and an expired action with its descendant, keeping two; and `deposit-credit`, `ratify`'s
action with its deposit returned to an account delegated to both a pool and a DRep, whose entries in the pulser's
distributions the first boundary credits with it.

It also derives four states from each input with the ledger's own lenses and functions (`retire`,
`reregister-new-vrf`, `both`, `both-sharer`: a pool retiring, a pool re-registering with another VRF key hash while a
second pool shares its active one, and both), which exercise Dijkstra's POOLREAP, and ticks each along both paths up
to the first boundary. Every state a tick reaches is recorded by its digest, size and facts; its bytes are kept in a
`.cbor` file when the tick crosses an epoch boundary, and also inside an epoch for the devnet (the testnet's pulsing
states, 0.8 MB each, are kept by digest only), and the derived states and theirs for the devnet only.

The image is resolved once to its immutable ID, which the measurement, the runs and the record all use, and it runs
only after it is measured, as the Leios node-to-client query oracle measures its image
(`scripts/oracle/leios-ntc-queries/run.py`): its `Main.hs`, package and cabal project are the repository's, each
`source-repository-package` checkout is at the commit the cabal project pins and unchanged, cabal finds the
executable up to date offline, the entry point is that executable, and the ledger sources the states depend on have
their pinned digests. Replay and record also refuse an image whose executable or build files are not the ones the
provenance accepted, or whose immutable ID is not the recorded one, unless the record is given `--accept-build` for
an image reviewed anew.

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
import math
import re
import subprocess
import sys
import tempfile
from fractions import Fraction
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from cleanup import container_labels  # noqa: E402


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


# The chain oracle's image resolution, cabal-project readers and CBOR reader (`leios_cbor.py`), and the Leios
# node-to-client query oracle's reading of a state file (`new_epoch_state`).
CHAIN = _load("leios_chain_run", ROOT / "scripts/oracle/leios-testnet/chain/run.py")
NTC = _load("leios_ntc_queries_run", ROOT / "scripts/oracle/leios-ntc-queries/run.py")
CBOR = CHAIN.CBOR

FIXTURE = ROOT / "fixtures/reference/dijkstra-epoch"
CASES_FILE = FIXTURE / "cases.json"
RATIFICATION_FILE = FIXTURE / "ratification.json"
GOVERNANCE_FILE = FIXTURE / "governance.json"
PROVENANCE = FIXTURE / "provenance.json"
INPUTS = "fixtures/leios/state"
# Each state file: the Shelley genesis file of its network, whether the states a tick reaches inside an epoch are
# kept as files too (the devnet's are 25 kB, the testnet's 0.8 MB), and whether the derived states are (the devnet's
# are 20 kB, the testnet's 0.5 MB and more).
STATES = {
    "dijkstra-203": {"genesis": f"{INPUTS}/genesis/shelley-genesis.json", "keepInEpoch": True, "keepDerived": True},
    "testnet-2168639": {"genesis": "fixtures/leios/testnet/config/shelley-genesis.json", "keepInEpoch": False,
                        "keepDerived": False},
}
PATHS = ("boundaries", "rewards")
# The states `Main.hs` derives from each input before ticking it (its `derivation`), each ticked along the paths up
# to their first boundary. P, Q and R are the input's registered pools in key order.
DERIVATIONS = ("retire", "reregister-new-vrf", "both", "both-sharer", "pledge-cap", "committee-size")
# Each derivation's pool roles and VRF key hash roles (`Main.hs`'s `derivation`).
POOL_ROLES = {"retire": ["P"], "reregister-new-vrf": ["P", "Q"], "both": ["P", "Q", "R"],
              "both-sharer": ["P", "Q"], "pledge-cap": [], "committee-size": []}
VRF_ROLES = {"retire": ["P"], "reregister-new-vrf": ["P", "new", "Q"], "both": ["P", "new", "Q", "R"],
             "both-sharer": ["P", "new", "Q"], "pledge-cap": [], "committee-size": []}
# The pledge leverage cap of `pledge-cap` (`pledgeCap`, numerator and denominator) and the Leios committee size of
# `committee-size`'s future parameters (`futureCommitteeSize`).
PLEDGE_CAP = [1, 1]
FUTURE_COMMITTEE_SIZE = 2
# The facts other than the pool state's that a derivation changes, with their derived values.
CHANGED_FACTS = {"pledge-cap": {"maxPledgeLeverage": PLEDGE_CAP}, "committee-size": {"futurePParams": "definite"}}
# The `ratify` derivation (`Main.hs`'s `ratifyAction`): its input, the id of its action (a transaction of 32 bytes of
# 0xaa, index 0), and the values its change gives keys 47 and 48 (`ratifyEndorserLimits`: the memory and steps of an
# endorser block's scripts, its reference scripts' size).
RATIFY = "ratify"
RATIFY_STATE = "testnet-2168639"
RATIFY_ID = "aa" * 32 + "#0"
RATIFY_LIMITS = {"maxEndorserBlockExUnits": [140000000, 100000000000], "maxRefScriptSizePerEndorserBlock": 2097152}
# The derivations of the testnet's state that propose actions and report their proposals (`Main.hs`'s
# `removeBranchesActions` and `depositCreditAction`), each ticked along both full paths, and the ids of their actions
# (a transaction of 32 bytes of 0xaa, indices 0 to 6).
BRANCHES = "remove-branches"
CREDIT = "deposit-credit"
GOVERNANCE = (BRANCHES, CREDIT)
ACTION = {name: "aa" * 32 + f"#{ix}" for ix, name in enumerate("ABCDEFG")}
# The governance action tags of a proposal's action (`ParameterChange`, `NewConstitution`, `InfoAction`) and the shape
# of `remove-branches`: each action's tag and parent, the parent `None` for the root of its purpose and an action's
# letter otherwise, and whether everyone votes yes on it.
PARAMETER_CHANGE, NEW_CONSTITUTION, INFO_ACTION = 0, 5, 6
BRANCH_SHAPE = {"A": (PARAMETER_CHANGE, None, True), "B": (PARAMETER_CHANGE, None, False),
                "C": (PARAMETER_CHANGE, "B", False), "D": (NEW_CONSTITUTION, None, False),
                "E": (NEW_CONSTITUTION, "D", False), "F": (PARAMETER_CHANGE, "A", False),
                "G": (INFO_ACTION, None, False)}
# What the second boundary of `remove-branches` removes (Dijkstra's EPOCH's `GovInfoEvent`) and keeps.
BRANCH_REMOVALS = {"enacted": ["A"], "removedDueToEnactment": ["B", "C"], "expired": ["D", "E"], "unclaimed": {}}
BRANCH_SURVIVORS = ["F", "G"]
# The genesis files of the Dijkstra genesis case: the public Leios testnet's, which start every era at epoch 0.
GENESIS_DIR = "fixtures/leios/testnet/config"
GENESIS_FILES = ("shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json", "dijkstra-genesis.json")
GENESIS_NAME = "testnet"
# The VRF key hash every derived re-registration takes (`newVrfBytes`).
NEW_VRF = "ee" * 32
CASE_ONLY = ("slots", "derivedSlots", "keepInEpoch", "keepDerived")
VERSION = 12
IMAGE = "scarab-dijkstra-epoch-oracle:local"
PROGRAM = "scripts/oracle/dijkstra-epoch/Main.hs"
PACKAGE = "scarab-dijkstra-epoch"
PROJECT = "/oracle/leios-testnet"
IMAGE_HARNESS = f"{PROJECT}/dijkstra-epoch/Main.hs"
IMAGE_BINARY = f"/usr/local/bin/{PACKAGE}"
IMAGE_BUILD = (f"{PROJECT}/dijkstra-epoch/{PACKAGE}.cabal", f"{PROJECT}/cabal.project",
               f"{PROJECT}/cabal.project.local", IMAGE_BINARY)
# What each of the image's files must be: the repository's file, or the line the Dockerfile writes.
REPOSITORY_FILES = {
    IMAGE_HARNESS: (HERE / "Main.hs").read_bytes(),
    IMAGE_BUILD[0]: (HERE / f"{PACKAGE}.cabal").read_bytes(),
    IMAGE_BUILD[1]: (ROOT / "scripts/oracle/leios-testnet/cabal.project").read_bytes(),
    IMAGE_BUILD[2]: b"packages: dijkstra-epoch\n",
}
# Run in the image by bash, one key and value a line, as the node-to-client query oracle's measurement: under
# `set -euo pipefail` a failing command ends it and the last line is missing. A checkout is unchanged only when it
# equals its commit with no untracked or ignored file. Cabal must find the executable up to date offline.
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
# The ledger sources the ticked states depend on, by their path in cardano-ledger, with the SHA-256 of each file at
# LEDGER. Replay and record refuse an image whose copies differ.
PINNED = {
    "eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Validation.hs":
        "2ed82b36bcda4ed24daaa2dc837f3c38c40ad0b9eda4533d42048a072a87e65e",
    "eras/shelley/impl/src/Cardano/Ledger/Shelley/Genesis.hs":
        "27faa873822e63d45fa3db8ad660e4a4bfc465d31a2ef0d52824c8a537b831aa",
    "eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/Types.hs":
        "3c0f5153c41cf428c4d1843529d6cea605f6a447c83711acb2858fb529abe7c6",
    "eras/shelley/impl/src/Cardano/Ledger/Shelley/RewardUpdate.hs":
        "606bd0b166e678d7cb6993adea6f66760aea4504dfbba09675d954f52db15317",
    "eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Rupd.hs":
        "14b05f72dd1b1104239813e565a982b8990c033e07d029f9f30da1a3e9ef4bad",
    "eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Tick.hs":
        "a482eace1620cc116eb0e60ab3d6740e2829441fe85013940574acba0b168d21",
    "eras/conway/impl/src/Cardano/Ledger/Conway/Rules/NewEpoch.hs":
        "0da7018eb4de2a92b0dc5b5547c1d7ea3b7b883997f55be94e540ceaa61f4bf9",
    "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Era.hs":
        "ee9c125ff6ae6f0881dbb94451af877f99857fff9e29dd462360512f71030fd0",
    "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Epoch.hs":
        "d331b54c5ca332195f6b0f9783b71e0421ff85466478b243255cd13fcdc7eb59",
    "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Pool.hs":
        "3eebeb6bd8689fd12e62e2dd6727b2fa28546bcb746496e2c1e2d5152306ad5f",
    "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/PoolReap.hs":
        "dee1a784a3a001ac58b37a5b9d0b89e0ec2e5f74900152742e9005ba6b99e315",
    "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Snap.hs":
        "987c10a59d354260caea526141654a6e8e1dd177384c8161815986005c0feb99",
    "libs/cardano-ledger-core/src/Cardano/Ledger/State/CertState.hs":
        "d277d9ced2007b55a237a85133599a635ad571acf7fe7acd5e0082fa40023e86",
    "libs/cardano-ledger-core/src/Cardano/Ledger/State/SnapShots.hs":
        "ae7d5e0db3afe5f97183008047d2f7ecf2974c7d1249752284a600b55557d414",
    "libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs":
        "459d7de8a2c1bb070476cb0bebb0cd758efeea61ea7e450306af3654f1592e70",
    "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Transition.hs":
        "4d343b18d71142017eb25458f17b335bf7bd3183395c7551d824f1552c6fab82",
    "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Translation.hs":
        "17152a4b587514eb00a2bd36cbf7043daa814018c4a29795d6184252fbb76923",
    "eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs":
        "2342bd7fcc497dd9aa312ce13994205a6acb5eec278c9001df035d99d8dbc8cf",
    "eras/conway/impl/src/Cardano/Ledger/Conway/Transition.hs":
        "f71c44727ba75ad5fc900c454c16abfa6008a63638a133f28e66c6934ca56ab1",
    "eras/shelley/impl/src/Cardano/Ledger/Shelley/Transition.hs":
        "6886aa7e600f352a3ed7f7822af115a0bdfcf406a6fe4affdfb409e6d9094e05",
    "eras/shelley/impl/src/Cardano/Ledger/Shelley/API/ByronTranslation.hs":
        "3bde7cd55f6c79459938c3cc5d5a96fcf39132b8464a4f87597f15628ecf8608",
    "eras/shelley/impl/src/Cardano/Ledger/Shelley/Rewards.hs":
        "e2b8b93438e1abc39ef521f99ef50313ad45de161bca73c703cf123da4eb5a39",
    "eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Ratify.hs":
        "715376c04ae980e884d219f86f1ada893c98b84dedcea58f1a93085cefbd045a",
    "eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Enact.hs":
        "b3b5fd8d8d82372af7e1d0ebaa3c3b000f96ce77d87364342fc117b702123d05",
    "eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Epoch.hs":
        "619d9553cc57d6d62e46343e90ee2ceb0c85a3f86d7fa16a937f10057a33ca1e",
    "eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Certs.hs":
        "d52be44293f43b62525450042eeacb3b3e4b146736aff77b56d5e9e6e743b82f",
    "eras/conway/impl/src/Cardano/Ledger/Conway/Governance.hs":
        "331a4e5d54382b2ad6c266dcc058c0d683c4ecd488bb7f6b673c101d1e8ac86e",
    "eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Proposals.hs":
        "a7b56cacf6fb38a13291cde2914fb06e12549e82b52002e43e2d24e6fdfe4ebe",
    "eras/conway/impl/src/Cardano/Ledger/Conway/Governance/DRepPulser.hs":
        "4842168df798e28ae859504dc8de2bb287b74cceb125b745ff004e99a811ddee",
    "eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Internal.hs":
        "c64a2a425cb7691304dd191a276b15fd100f4804f6ce2a6f415cae4a814d2ec4",
    "libs/cardano-ledger-core/src/Cardano/Ledger/State/Governance.hs":
        "abcdf3895abb73eaaf5ee17b7f764e7415f946ae67456e0fa11be4f694216cd9",
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def file_sha256(path):
    return sha256((ROOT / path).read_bytes())


def state_path(name):
    return f"{INPUTS}/{name}/state"


def tip_slot(path, buf):
    """The slot of the ledger state's tip: the Shelley ledger state's first field, `WithOrigin` written as a list of
    zero or one `[slot, block number, hash]` (ouroboros-consensus 2416493b `Shelley/Ledger/Ledger.hs:885-903`)."""
    outer = CBOR.items(buf, 0)
    extended = CBOR.items(buf, outer[1][0])
    telescope = CBOR.items(buf, extended[0][0])
    current = CBOR.items(buf, telescope[-1][0])
    shelley = CBOR.items(buf, CBOR.items(buf, current[1][0])[1][0])
    origin = CBOR.items(buf, shelley[0][0])
    if len(origin) != 1:
        raise SystemExit(f"{path}: a ledger state at a block expected, not at the origin")
    slot = CBOR.value(buf, CBOR.items(buf, origin[0][0])[0][0])
    if not isinstance(slot, int):
        raise SystemExit(f"{path}: the tip's slot is not a number")
    return slot


def genesis(path):
    """What the globals take from a Shelley genesis file: the epoch length, k, f and the KES setup, with the
    stability windows `mkShelleyGlobals` computes, ceiling(3k / f) and ceiling(4k / f) (cardano-ledger 9df9f98c
    `Shelley/Genesis.hs:768-789`, `computeStabilityWindow` and `computeRandomnessStabilisationWindow`)."""
    content = json.loads((ROOT / path).read_text())
    k, f = content["securityParam"], Fraction(str(content["activeSlotsCoeff"]))
    return {"epochLength": content["epochLength"], "securityParam": k,
            "stabilityWindow": math.ceil(Fraction(3 * k) / f),
            "randomnessStabilisationWindow": math.ceil(Fraction(4 * k) / f),
            "maxKESEvolutions": content["maxKESEvolutions"], "slotsPerKESPeriod": content["slotsPerKESPeriod"]}


def slots_of(epoch, length, window):
    """The slots of each path from a state in `epoch`, each tick from the state the one before reached."""
    first = epoch * length
    return {"boundaries": [first + length, first + 2 * length],
            "rewards": [first + window + 1, first + length, first + length + window + 1, first + 2 * length]}


def derived_slots(slots, epoch, length):
    """A derived state's paths: each path's slots up to and including the first slot of the next epoch."""
    return {path: [slot for slot in ticks if slot <= (epoch + 1) * length] for path, ticks in slots.items()}


def inputs():
    """Each state's identity, tip, epoch and genesis, with its `NewEpochState`'s bytes. The epoch must be the one the
    tip's slot is in, with every epoch of the genesis's length (the fixed epoch information the oracle's globals
    use), and the tip must lie before the epoch's reward start point."""
    found = []
    for name, spec in STATES.items():
        state = (ROOT / state_path(name)).read_bytes()
        era, (start, end) = NTC.new_epoch_state(state_path(name), state)
        if era != "dijkstra":
            raise SystemExit(f"{state_path(name)}: not a Dijkstra state")
        nes = state[start:end]
        epoch = CBOR.value(nes, CBOR.items(nes, 0)[0][0])
        slot = tip_slot(state_path(name), state)
        g = genesis(spec["genesis"])
        if not isinstance(epoch, int) or slot // g["epochLength"] != epoch:
            raise SystemExit(f"{state_path(name)}: the tip's slot {slot} is not in epoch {epoch} of "
                             f"{g['epochLength']} slots")
        if slot > epoch * g["epochLength"] + g["randomnessStabilisationWindow"]:
            raise SystemExit(f"{state_path(name)}: the tip is past the epoch's reward start point")
        found.append(({"name": name, "stateSha256": sha256(state), "newEpochStateSpan": [start, end],
                       "newEpochStateSha256": sha256(nes), "tipSlot": slot, "epoch": epoch,
                       "genesis": spec["genesis"], "keepInEpoch": spec["keepInEpoch"],
                       "keepDerived": spec["keepDerived"],
                       "slots": slots_of(epoch, g["epochLength"], g["randomnessStabilisationWindow"]),
                       "derivedSlots": derived_slots(slots_of(epoch, g["epochLength"],
                                                              g["randomnessStabilisationWindow"]),
                                                     epoch, g["epochLength"])},
                      nes))
    return found


def genesis_files():
    """The digests of the Shelley genesis files of the inputs and of the genesis case's files, Byron's among them."""
    paths = {spec["genesis"] for spec in STATES.values()}
    paths |= {f"{GENESIS_DIR}/{name}" for name in (*GENESIS_FILES, "byron-genesis.json")}
    return {path: file_sha256(path) for path in sorted(paths)}


def byron_utxo_empty():
    """The genesis case starts from the empty Byron UTxO, which is Byron's genesis UTxO only when its genesis has no
    AVVM and no non-AVVM balance (cardano-ledger-byron's `genesisUtxo`)."""
    byron = json.loads((ROOT / GENESIS_DIR / "byron-genesis.json").read_text())
    if byron.get("avvmDistr") or byron.get("nonAvvmBalances"):
        raise SystemExit(f"{GENESIS_DIR}/byron-genesis.json: Byron balances, so the Byron UTxO is not empty")


def container(name, *options, read_only=True, user=True):
    return ["docker", "run", "--rm", "--name", name, *container_labels("oracle", name), "--network", "none",
            "--cpus", "8", "--memory", "24g", *(["--user", "1000:1000"] if user else []),
            *(["--read-only"] if read_only else []), *options]


def measure(image_id, name):
    """The keys and values MEASURE prints in the image, which must run to its end."""
    # cabal writes its caches even for a dry run, and its store and configuration are root's (`/root`, mode 700),
    # so this container alone runs as the image's user, writable, with nothing mounted; it is removed after.
    done = subprocess.run([*container(name, "--entrypoint", "bash", read_only=False, user=False), image_id,
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
        f"sha256sum $(find {PROJECT}/dist-newstyle/src -path '*/{path}')" for path in PINNED)
    output = subprocess.check_output(
        [*container(name, "--entrypoint", "sh"), image_id, "-c", script], text=True).splitlines()
    if len(output) != len(PINNED):
        raise SystemExit(f"expected one copy of each pinned source in the image, found {output}")
    found = {path: line.split()[0] for path, line in zip(PINNED, output, strict=True)}
    differing = sorted(path for path in PINNED if found[path] != PINNED[path])
    if differing:
        raise SystemExit(f"{IMAGE} does not hold the sources of cardano-ledger {LEDGER}: {differing}")
    return found


def state_file(case, path, slot, derivation="none"):
    if derivation == "none":
        return f"{case['name']}-{path}-{slot}.cbor"
    return f"{case['name']}-{derivation}-{path}-{slot}.cbor"


def derived_file(case, derivation):
    return f"{case['name']}-{derivation}.cbor" if case["keepDerived"] else None


def tick_file(case, derivation, path, slot, crossed):
    keep = (crossed or case["keepInEpoch"]) if derivation == "none" else case["keepDerived"]
    return state_file(case, path, slot, derivation) if keep else None


def case_rows(case, derivation, result):
    """The fixture's rows for one input and derivation: one per path, each tick with its state's digest, size and
    file (or null), the oracle's facts and the counts of the derivation's VRF key hashes; the derivation (`none`, or
    its name, pools, changes, derived state's digest, size, file, facts and counts); and the bytes of each state
    kept, by file name."""
    rows, kept = [], {}
    derived = "none"
    if derivation != "none":
        source = result["derivation"]
        state = bytes.fromhex(source["state"])
        name = derived_file(case, derivation)
        if name is not None:
            kept[name] = state
        derived = {"name": source["name"], "pools": source["pools"], "changes": source["changes"],
                   "stateSha256": sha256(state), "bytes": len(state), "file": name, "facts": source["facts"],
                   "vrfCounts": source["vrfCounts"]}
    for path in result["paths"]:
        ticks = []
        for tick in path["ticks"]:
            state = bytes.fromhex(tick["state"])
            name = tick_file(case, derivation, path["name"], tick["slot"], tick["crossedBoundary"])
            if name is not None:
                kept[name] = state
            ticks.append({"slot": tick["slot"], "stateSha256": sha256(state), "bytes": len(state), "file": name,
                          **{k: tick[k] for k in ("facts", "crossedBoundary", "rewardUpdateApplied", "poolsRetired",
                                                  "vrfCounts")}})
        rows.append({**{k: v for k, v in case.items() if k not in CASE_ONLY},
                     "derivation": derived, "path": path["name"], "version": result["version"],
                     "roundTrip": result["roundTrip"], "globals": result["globals"], "inputFacts": result["input"],
                     "ticks": ticks})
    return rows, kept


def expected_changes(derivation, pools, vrfs, epoch, network):
    """The changes a derivation makes, by the roles of its pools and VRF key hashes (`Main.hs`'s `derivation`)."""
    if derivation == "pledge-cap":
        return [{"change": "setMaxPledgeLeverage", "numerator": PLEDGE_CAP[0], "denominator": PLEDGE_CAP[1]}]
    if derivation == "committee-size":
        return [{"change": "setFutureLeiosCommitteeSize", "leiosCommitteeSize": FUTURE_COMMITTEE_SIZE}]
    retire = {"retire": "P", "both": "R", "both-sharer": "Q"}.get(derivation)
    reregister = [] if derivation == "retire" else [
        {"change": "setActiveVrf", "pool": pools.get("Q"), "vrf": vrfs.get("P")},
        {"change": "setFutureParams", "pool": pools.get("P"), "network": network, "vrf": NEW_VRF},
        {"change": "populateVRFKeyHashes"}]
    return reregister + ([{"change": "retire", "pool": pools.get(retire), "epoch": epoch + 1}] if retire else [])


def modelled_counts(derivation, changes, crossed):
    """The counts of the derivation's VRF key hashes as `populateVRFKeyHashes` counts them (one per pool's active
    hash and one per future hash that differs from it), on the derived state, or after its boundary, where the
    future parameters are the active ones and the retiring pools are gone: the invariant Dijkstra's POOLREAP keeps
    (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Pool.hs:176-186`), modelled over the pools P, Q and R."""
    roles = {"P", "Q", "R"} & set(derivation["pools"])
    pool_role = {derivation["pools"][role]: role for role in roles}
    active = {role: derivation["vrfCounts"][role]["vrf"] for role in roles}
    future, retiring = {}, set()
    for c in changes:
        if c["change"] == "setActiveVrf":
            active[pool_role[c["pool"]]] = c["vrf"]
        elif c["change"] == "setFutureParams":
            future[pool_role[c["pool"]]] = c["vrf"]
        elif c["change"] == "retire":
            retiring.add(pool_role[c["pool"]])
    if crossed:
        active = {role: future.get(role, vrf) for role, vrf in active.items() if role not in retiring}
        future = {}
    held = list(active.values()) + [vrf for role, vrf in future.items() if vrf != active.get(role)]
    return {role: held.count(entry["vrf"]) for role, entry in derivation["vrfCounts"].items()}


def check_derivation(case, derivation_name, derivation, input_facts, network, refuse):
    """A derived state is the input with the derivation's changes only: its pools and their roles in key order, the
    changes `Main.hs` names, the facts outside the pool state unchanged, and the VRF key hash counts modelled."""
    pools = derivation.get("pools", {})
    roles, vrf_roles = POOL_ROLES[derivation_name], VRF_ROLES[derivation_name]
    if (set(derivation) != {"name", "pools", "changes", "stateSha256", "bytes", "file", "facts", "vrfCounts"}
            or derivation["name"] != derivation_name or sorted(pools) != sorted(roles)
            or [pools[r] for r in roles] != sorted(pools[r] for r in roles)
            or sorted(derivation["vrfCounts"]) != sorted(vrf_roles)
            or derivation["file"] != derived_file(case, derivation_name)):
        refuse(f"{derivation_name}: the derivation is not the one asked")
    vrfs = {role: entry["vrf"] for role, entry in derivation["vrfCounts"].items()}
    changes = expected_changes(derivation_name, pools, vrfs, case["epoch"], network)
    if derivation["changes"] != changes or vrfs.get("new", NEW_VRF) != NEW_VRF:
        refuse(f"{derivation_name}: the changes are not the derivation's")
    facts = derivation["facts"]
    unchanged = {k: v for k, v in input_facts.items() if k not in ("poolsRetiring", "futurePools", "vrfKeyHashes")}
    unchanged.update(CHANGED_FACTS.get(derivation_name, {}))
    if ({k: facts.get(k) for k in unchanged} != unchanged
            or facts["poolsRetiring"] != input_facts["poolsRetiring"] + sum(c["change"] == "retire" for c in changes)
            or facts["futurePools"] != input_facts["futurePools"]
            + sum(c["change"] == "setFutureParams" for c in changes)):
        refuse(f"{derivation_name}: the derived state differs from the input outside its changes")
    if {r: e["count"] for r, e in derivation["vrfCounts"].items()} != modelled_counts(derivation, changes, False):
        refuse(f"{derivation_name}: the derived state's VRF key hash counts are not populateVRFKeyHashes's")
    return changes


def check(case, rows):
    """The rows of one input are what was asked and what the reference's rules make of it: version 12 and the state
    read back to its bytes; the globals of the network's Shelley genesis (epoch size, randomness stabilisation
    window, KES setup, and the key age Dijkstra's SNAP derives from them, which is also the key age the node wrote
    into the input's mark snapshot); each path's slots; each state's epoch the one its slot is in; on each
    boundary a fresh mark of that epoch, of the input's committee size and key age, the old mark's committee in the
    set position, and no reward update left; a reward update applied exactly when the state ticked from held one;
    and on the rewards path a pulser started at each reward start point. A derived state is checked by
    [[check_derivation]], and after its boundary the retiring pools are gone, no future parameters remain and the
    VRF key hash counts are those of the pools left ([[modelled_counts]])."""
    name = case["name"]
    g = genesis(case["genesis"])
    network = json.loads((ROOT / case["genesis"]).read_text())["networkId"]
    length = g["epochLength"]
    key_age = math.ceil(Fraction(g["maxKESEvolutions"] * g["slotsPerKESPeriod"], length)) + 2

    def refuse(why):
        raise SystemExit(f"{name}: {why}")

    asked = [("none", path) for path in PATHS] + [(d, path) for d in DERIVATIONS for path in PATHS]
    if [(row.get("derivation") if row.get("derivation") == "none" else (row.get("derivation") or {}).get("name"),
         row.get("path")) for row in rows] != asked:
        refuse(f"the derivations and paths are not {asked}")
    derivations = {}
    for row in rows:
        path = row["path"]
        derivation_name = "none" if row["derivation"] == "none" else row["derivation"]["name"]
        if row["version"] != VERSION or row["roundTrip"] is not True:
            refuse(f"not read at version {VERSION} and written back to the same bytes")
        glob = row["globals"]
        first = case["epoch"] * length
        if glob != {"epochSize": length, "securityParameter": g["securityParam"],
                    "randomnessStabilisationWindow": g["randomnessStabilisationWindow"],
                    "stabilityWindow": g["stabilityWindow"], "maxKESEvo": g["maxKESEvolutions"],
                    "slotsPerKESPeriod": g["slotsPerKESPeriod"], "maxKeyAge": key_age, "epochFirstSlot": first,
                    "nextEpochFirstSlot": first + length, "epochAfterFirstSlot": first + 2 * length}:
            refuse(f"the globals are not those of {case['genesis']} with epochs of {length} slots")
        before = row["inputFacts"]
        if before["epoch"] != case["epoch"] or before["mark"]["maxKeyAge"] != key_age:
            refuse("the input's mark snapshot was not made with the key age these globals give")
        if row["inputFacts"] != rows[0]["inputFacts"]:
            refuse("the rows read different inputs")
        changes = []
        if derivation_name != "none":
            derivation = row["derivation"]
            if derivations.setdefault(derivation_name, derivation) != derivation:
                refuse(f"{derivation_name}: the paths start from different derived states")
            changes = check_derivation(case, derivation_name, derivation, before, network, refuse)
            before = derivation["facts"]
        slots = case["slots" if derivation_name == "none" else "derivedSlots"][path]
        if [t["slot"] for t in row["ticks"]] != slots:
            refuse(f"{derivation_name} {path}: the slots are not {slots}")
        for tick in row["ticks"]:
            after = tick["facts"]
            crossed = after["epoch"] != before["epoch"]
            where = f"{derivation_name} {path} at {tick['slot']}"
            if (after["epoch"] != tick["slot"] // length or tick["crossedBoundary"] != crossed
                    or tick["file"] != tick_file(case, derivation_name, path, tick["slot"], crossed)
                    or tick["rewardUpdateApplied"] != (crossed and before["rewardUpdate"] != "absent")
                    or tick["poolsRetired"] != before["pools"] - after["pools"]):
                refuse(f"{where}: the tick's facts do not follow from the states")
            if crossed and (after["epoch"] != before["epoch"] + 1
                            or after["mark"]["epoch"] != after["epoch"]
                            or after["mark"]["committeeSize"] != after["leiosCommitteeSize"]
                            or after["leiosCommitteeSize"] != (FUTURE_COMMITTEE_SIZE
                                                               if derivation_name == "committee-size"
                                                               else before["leiosCommitteeSize"])
                            or after["mark"]["seats"] > after["leiosCommitteeSize"]
                            or after["mark"]["maxKeyAge"] != key_age
                            or after["set"] != {k: before["mark"][k] for k in ("seats", "keyedSeats")}
                            or after["rewardUpdate"] != "absent"):
                refuse(f"{where}: not one epoch boundary of Dijkstra's rules")
            pulser = "pulsing" if path == "rewards" else before["rewardUpdate"]
            if not crossed and (after["mark"] != before["mark"] or after["set"] != before["set"]
                                or after["rewardUpdate"] != pulser):
                refuse(f"{where}: the snapshots changed inside an epoch, or no pulser started")
            if derivation_name == "none":
                if tick["vrfCounts"] != {}:
                    refuse(f"{where}: VRF key hash counts without a derivation")
            else:
                derivation = row["derivation"]
                expected_pools = before["pools"] - (before["poolsRetiring"] if crossed else 0)
                if ({r: e["vrf"] for r, e in tick["vrfCounts"].items()}
                        != {r: e["vrf"] for r, e in derivation["vrfCounts"].items()}
                        or {r: e["count"] for r, e in tick["vrfCounts"].items()}
                        != modelled_counts(derivation, changes, crossed)
                        or (crossed and (after["pools"] != expected_pools or after["poolsRetiring"] != 0
                                         or after["futurePools"] != 0))):
                    refuse(f"{where}: the pools or VRF key hash counts are not those the boundary leaves")
            before = after
    # The pledge leverage cap binds: at the boundary the capped reward update pays the reward accounts less than the
    # uncapped one at the same slot, the rest staying in the reserves, the treasury's share alike.
    rewards = {("none" if row["derivation"] == "none" else row["derivation"]["name"]): row
               for row in rows if row["path"] == "rewards"}
    capped = rewards["pledge-cap"]["ticks"][-1]
    uncapped = next(t for t in rewards["none"]["ticks"] if t["slot"] == capped["slot"])
    if not (capped["rewardUpdateApplied"] and uncapped["rewardUpdateApplied"]
            and capped["facts"]["accountBalances"] < uncapped["facts"]["accountBalances"]
            and capped["facts"]["reserves"] > uncapped["facts"]["reserves"]
            and capped["facts"]["treasury"] == uncapped["facts"]["treasury"]):
        refuse("pledge-cap: the capped reward update is not smaller than the uncapped one")


def ratification_record(case, result):
    """The fixture's record of the `ratify` derivation and the bytes of its derived state by file name: the input's
    governance, the derived state (its change, digest, size, file, facts and governance), and each tick of both
    paths with its state's digest and size (no file: the states are 0.8 MB each), facts and governance."""
    source = result["derivation"]
    state = bytes.fromhex(source["state"])
    name = f"{case['name']}-{RATIFY}.cbor"
    record = {"name": case["name"], "inputGovernance": result["inputGovernance"],
              "inputDeposited": result["input"]["deposited"],
              "derivation": {"name": source["name"], "changes": source["changes"], "stateSha256": sha256(state),
                             "bytes": len(state), "file": name, "facts": source["facts"],
                             "governance": source["governance"]},
              "paths": [{"path": path["name"],
                         "ticks": [{"slot": t["slot"], "stateSha256": sha256(bytes.fromhex(t["state"])),
                                    "bytes": len(bytes.fromhex(t["state"])), "file": None,
                                    **{k: t[k] for k in ("facts", "governance", "crossedBoundary",
                                                         "rewardUpdateApplied")}}
                                   for t in path["ticks"]]}
                        for path in result["paths"]]}
    return record, {name: state}


def check_ratification(case, record):
    """The `ratify` derivation is what was asked and what the reference's rules make of it: the testnet's state with
    one action proposed, a change of keys 47 and 48 to RATIFY_LIMITS whose parent is the input's root, with every
    hot committee credential's, DRep's and pool's yes vote, its deposit in the pot, and nothing else of the facts
    changed; at the first boundary the fresh pulser's ratification enacts it, the action still proposed; on the
    rewards path the tick past the slot of no return makes the future parameters definite (`solidifyFuturePParams`),
    and the second boundary enacts it: the action gone, the root of the parameter changes it, keys 47 and 48 the
    change's values and its deposit back out of the pot; on the boundaries path, with no tick past that slot, the
    future parameters stay potential, so the second boundary removes the action and moves the root to it, and the
    parameters stay the input's (`nextEpochPParams`)."""
    def refuse(why):
        raise SystemExit(f"{RATIFY}: {why}")

    derivation, before = record["derivation"], record["inputGovernance"]
    length = genesis(case["genesis"])["epochLength"]
    changes = derivation["changes"]
    if (record["name"] != RATIFY_STATE or derivation["name"] != RATIFY or len(changes) != 1
            or changes[0]["change"] != "propose" or changes[0]["id"] != RATIFY_ID
            or changes[0]["proposedIn"] != case["epoch"]
            or [p["path"] for p in record["paths"]] != list(PATHS)
            or [[t["slot"] for t in p["ticks"]] for p in record["paths"]] != [case["slots"][p] for p in PATHS]):
        refuse("not the derivation asked")
    if (set(record) != {"name", "inputGovernance", "inputDeposited", "derivation", "paths"}
            or derivation["file"] != f"{RATIFY_STATE}-{RATIFY}.cbor"
            or derivation["facts"]["deposited"] <= record["inputDeposited"]
            or before["proposals"] != [] or before["enacted"] != []
            or derivation["governance"] != {**before, "proposals": [RATIFY_ID]}
            or any(before[k] == v for k, v in RATIFY_LIMITS.items())
            or min(changes[0][k] for k in ("committeeVotes", "drepVotes", "stakePoolVotes")) < 1):
        refuse("the derived state's governance is not the input's with the action proposed")
    for path in record["paths"]:
        state, governance = derivation["facts"], derivation["governance"]
        for tick in path["ticks"]:
            after, gov = tick["facts"], tick["governance"]
            where = f"{path['path']} at {tick['slot']}"
            crossed = after["epoch"] != state["epoch"]
            if tick["crossedBoundary"] != crossed or after["epoch"] != tick["slot"] // length:
                refuse(f"{where}: the tick's facts do not follow from the states")
            if crossed and after["epoch"] == case["epoch"] + 1:
                if gov != {**governance, "enacted": [RATIFY_ID]} or after["futurePParams"] != "potential":
                    refuse(f"{where}: the fresh pulser does not ratify the action")
            elif crossed:
                solid = state["futurePParams"] == "definite"
                limits = RATIFY_LIMITS if solid else {k: before[k] for k in RATIFY_LIMITS}
                if (gov != {**limits, "proposals": [], "enacted": [], "pparamUpdateRoot": RATIFY_ID}
                        or after["deposited"] != record["inputDeposited"]):
                    refuse(f"{where}: the boundary does not enact the action as the future parameters say")
            elif gov != governance or after["futurePParams"] != (
                    "definite" if state["epoch"] == case["epoch"] + 1 else "none"):
                refuse(f"{where}: the governance changed inside an epoch, or the parameters were not solidified")
            state, governance = after, gov
    if not any(p["ticks"][-2]["facts"]["futurePParams"] == "definite" for p in record["paths"]):
        refuse("no path solidifies the future parameters")


def run_ratification(image_id, name, states):
    case, nes = next((c, n) for c, n in states if c["name"] == RATIFY_STATE)
    with tempfile.TemporaryDirectory(prefix="scarab-dijkstra-epoch-") as scratch:
        Path(scratch).chmod(0o755)
        target = Path(scratch) / f"{case['name']}.cbor"
        target.write_bytes(nes)
        target.chmod(0o644)
        done = subprocess.run(
            [*container(name, "-v", f"{scratch}:/in:ro", "-v", f"{ROOT / case['genesis']}:/genesis.json:ro"),
             image_id, "/genesis.json", f"/in/{case['name']}.cbor", RATIFY,
             *(f"{path}={','.join(map(str, case['slots'][path]))}" for path in PATHS)],
            capture_output=True, text=True)
    if done.returncode:
        raise SystemExit(f"the oracle failed on {case['name']} ({RATIFY}): {done.stderr}")
    result = json.loads(done.stdout)
    record, kept = ratification_record(case, result)
    check_ratification(case, record)
    return record, kept


def recorded_ratification():
    return json.loads(RATIFICATION_FILE.read_text())


def governance_record(case, result):
    """The fixture's record of a derivation that proposes actions (GOVERNANCE): as the `ratify` derivation's, with
    the account delegated to a pool and a DRep whose entries the governance reports (`delegated`), each tick's
    removals, and for `deposit-credit` the control (`ratify`'s derived state at the first boundary); no state file,
    the derived state and those of the ticks being kept by digest only (0.5 MB and more each)."""
    source = result["derivation"]
    state = bytes.fromhex(source["state"])
    record = {"name": case["name"], "inputGovernance": result["inputGovernance"],
              "inputDeposited": result["input"]["deposited"], "delegated": result["delegated"],
              "derivation": {"name": source["name"], "changes": source["changes"], "stateSha256": sha256(state),
                             "bytes": len(state), "file": None, "facts": source["facts"],
                             "governance": source["governance"]},
              "paths": [{"path": path["name"],
                         "ticks": [{"slot": t["slot"], "stateSha256": sha256(bytes.fromhex(t["state"])),
                                    "bytes": len(bytes.fromhex(t["state"])), "file": None,
                                    **{k: t[k] for k in ("facts", "governance", "removals", "crossedBoundary",
                                                         "rewardUpdateApplied")}}
                                   for t in path["ticks"]]}
                        for path in result["paths"]]}
    if "control" in result:
        record["control"] = result["control"]
    return record


def proposal_shape(proposal):
    """A proposal procedure's deposit, return account (its credential's bytes), action tag and parent id
    (`txid#index`, or None), read from its CBOR: `[deposit, account, action, anchor]`, the action
    `[tag, parent, ...]` but for an information action's `[6]`."""
    data = bytes.fromhex(proposal)
    deposit, account, action, _ = CBOR.value(data, 0)
    if action[0] == INFO_ACTION:
        return deposit, account[1:].hex(), INFO_ACTION, None
    parent = action[1]
    return deposit, account[1:].hex(), action[0], None if parent is None else f"{parent[0].hex()}#{parent[1]}"


def check_governance_ticks(case, record, at_boundary):
    """The ticks of a GOVERNANCE derivation's paths follow from the states: the facts' epochs and boundaries, no
    removals and no change of the governance inside an epoch, and `at_boundary(where, path, epoch, before, tick)` at
    each boundary, which refuses what the rules do not make there."""
    length = genesis(case["genesis"])["epochLength"]
    for path in record["paths"]:
        state, governance = record["derivation"]["facts"], record["derivation"]["governance"]
        for tick in path["ticks"]:
            after, gov = tick["facts"], tick["governance"]
            where = f"{record['derivation']['name']}, {path['path']} at {tick['slot']}"
            crossed = after["epoch"] != state["epoch"]
            if tick["crossedBoundary"] != crossed or after["epoch"] != tick["slot"] // length:
                raise SystemExit(f"{where}: the tick's facts do not follow from the states")
            if crossed:
                at_boundary(where, path["path"], after["epoch"], governance, tick)
            elif (tick["removals"] is not None or gov["proposals"] != governance["proposals"]
                  or gov["roots"] != governance["roots"]):
                raise SystemExit(f"{where}: the governance changed inside an epoch")
            state, governance = after, gov


def check_governance_derivation(case, record, name, changes):
    """A GOVERNANCE record is of the derivation asked, on the testnet's state, along both full paths, with only
    actions proposed in the input's epoch, ids ACTION's in order, and the derived governance the input's with them
    proposed."""
    derivation, before = record["derivation"], record["inputGovernance"]
    expected = {"name", "inputGovernance", "inputDeposited", "delegated", "derivation", "paths",
                *(["control"] if name == CREDIT else [])}
    if (set(record) != expected or record["name"] != RATIFY_STATE or derivation["name"] != name
            or derivation["file"] is not None
            or [p["path"] for p in record["paths"]] != list(PATHS)
            or [[t["slot"] for t in p["ticks"]] for p in record["paths"]] != [case["slots"][p] for p in PATHS]
            or any(t["file"] is not None for p in record["paths"] for t in p["ticks"])
            or [c["change"] for c in derivation["changes"]] != ["propose"] * changes
            or [c["id"] for c in derivation["changes"]] != list(ACTION.values())[:changes]
            or any(c["proposedIn"] != case["epoch"] for c in derivation["changes"])
            or before["proposals"] != [] or before["enacted"] != [] or before["expired"] != []
            or derivation["governance"] != {**before, "proposals": list(ACTION.values())[:changes]}):
        raise SystemExit(f"{name}: not the derivation asked")
    deposits = [proposal_shape(c["proposal"])[0] for c in derivation["changes"]]
    if min(deposits) <= 0 or derivation["facts"]["deposited"] != record["inputDeposited"] + sum(deposits):
        raise SystemExit(f"{name}: the deposits are not in the pot")


def check_branches(case, record):
    """`remove-branches` is the forest asked (BRANCH_SHAPE, every action returned to one account, D expiring after the
    input's epoch and the others voted on to the epoch after next at least), and the reference's rules make of it
    what `Main.hs` says: the first boundary's fresh pulser enacts A and finds D expired, removing nothing yet; the
    second removes D and its descendant E as expired, makes A the root of the parameter changes and removes its
    competing sibling B with B's descendant C, keeping F, now the root's child, and G, in order (BRANCH_REMOVALS,
    BRANCH_SURVIVORS), the other roots the input's and the deposit pot the input's with the survivors' deposits."""
    check_governance_derivation(case, record, BRANCHES, len(ACTION))
    before = record["inputGovernance"]
    roots = {PARAMETER_CHANGE: before["roots"]["pparamUpdate"], NEW_CONSTITUTION: before["roots"]["constitution"],
             INFO_ACTION: None}
    accounts, deposits = set(), {}
    for letter, change in zip(ACTION, record["derivation"]["changes"]):
        tag, parent, voted = BRANCH_SHAPE[letter]
        deposits[letter], account, shape_tag, shape_parent = proposal_shape(change["proposal"])
        accounts.add(account)
        votes = [change[k] for k in ("committeeVotes", "drepVotes", "stakePoolVotes")]
        expiry = case["epoch"] if letter == "D" else case["epoch"] + 2
        if (shape_tag != tag or shape_parent != (roots[tag] if parent is None else ACTION[parent])
                or (min(votes) < 1 if voted else max(votes) != 0)
                or (change["expiresAfter"] != expiry if letter == "D" else change["expiresAfter"] < expiry)):
            raise SystemExit(f"{BRANCHES}: action {letter} is not the one asked")
    if len(accounts) != 1:
        raise SystemExit(f"{BRANCHES}: the deposits do not return to one account")

    def ids(letters):
        return [ACTION[x] for x in letters]

    def at_boundary(where, path, epoch, governance, tick):
        gov = tick["governance"]
        if epoch == case["epoch"] + 1:
            if (tick["removals"] != {"enacted": [], "removedDueToEnactment": [], "expired": [], "unclaimed": {}}
                    or gov["proposals"] != governance["proposals"] or gov["roots"] != governance["roots"]
                    or gov["enacted"] != ids("A") or gov["expired"] != ids("D")):
                raise SystemExit(f"{where}: the fresh pulser does not enact A and expire D, or something was removed")
        elif (tick["removals"] != {k: (v if k == "unclaimed" else ids(v)) for k, v in BRANCH_REMOVALS.items()}
              or gov["proposals"] != ids(BRANCH_SURVIVORS)
              or gov["roots"] != {**before["roots"], "pparamUpdate": ACTION["A"]}
              or tick["facts"]["deposited"] != record["inputDeposited"] + sum(deposits[x] for x in BRANCH_SURVIVORS)):
            raise SystemExit(f"{where}: the boundary does not remove the branches as the reference's rules do")
    check_governance_ticks(case, record, at_boundary)


def check_credit(case, record):
    """`deposit-credit` is `ratify`'s action returned to the delegated account (a registered account delegated to a
    registered pool and a registered DRep of a credential, not the first account, to which the control's action
    returns its deposit), and at the first boundary of `boundaries`, the control's slot with no tick before it, the
    fresh pulser's DRep and pool distributions hold the delegated DRep's and pool's entries the control's holds plus the deposit, both
    ratifying the action; the second boundary enacts it and removes it, as `ratify`'s."""
    check_governance_derivation(case, record, CREDIT, 1)
    change = record["derivation"]["changes"][0]
    deposit, account, tag, parent = proposal_shape(change["proposal"])
    delegated, control = record["delegated"], record["control"]
    if (set(delegated) != {"account", "pool", "drep"} or delegated["account"] != {"keyHash": account}
            or tag != PARAMETER_CHANGE or parent != record["inputGovernance"]["roots"]["pparamUpdate"]
            or min(change[k] for k in ("committeeVotes", "drepVotes", "stakePoolVotes")) < 1):
        raise SystemExit(f"{CREDIT}: not ratify's action returned to the delegated account")
    if control["slot"] != case["slots"]["boundaries"][0] or control["governance"]["enacted"] != [ACTION["A"]]:
        raise SystemExit(f"{CREDIT}: the control is not ratify's state at the first boundary")

    def at_boundary(where, path, epoch, governance, tick):
        gov = tick["governance"]
        if epoch == case["epoch"] + 1:
            same = {k: v for k, v in gov.items() if k not in ("drepStake", "poolStake")}
            if (tick["removals"] != {"enacted": [], "removedDueToEnactment": [], "expired": [], "unclaimed": {}}
                    or gov["enacted"] != [ACTION["A"]] or gov["proposals"] != governance["proposals"]):
                raise SystemExit(f"{where}: the fresh pulser does not ratify the action")
            if path == "boundaries" and (
                    same != {k: v for k, v in control["governance"].items() if k not in ("drepStake", "poolStake")}
                    or gov["drepStake"] != control["governance"]["drepStake"] + deposit
                    or gov["poolStake"] != control["governance"]["poolStake"] + deposit):
                raise SystemExit(f"{where}: the distributions are not the control's with the deposit credited")
        elif (tick["removals"] != {"enacted": [ACTION["A"]], "removedDueToEnactment": [], "expired": [],
                                   "unclaimed": {}}
              or gov["proposals"] != [] or gov["roots"]["pparamUpdate"] != ACTION["A"]):
            raise SystemExit(f"{where}: the boundary does not enact the action")
    check_governance_ticks(case, record, at_boundary)


GOVERNANCE_CHECKS = {BRANCHES: check_branches, CREDIT: check_credit}


def check_governance(case, records):
    """Each GOVERNANCE derivation's record, by name, and no other."""
    if not isinstance(records, dict) or set(records) != set(GOVERNANCE):
        raise SystemExit(f"{GOVERNANCE_FILE.name}: not the records of {', '.join(GOVERNANCE)}")
    for name in GOVERNANCE:
        GOVERNANCE_CHECKS[name](case, records[name])


def run_governance(image_id, name, states):
    case, nes = next((c, n) for c, n in states if c["name"] == RATIFY_STATE)
    records = {}
    with tempfile.TemporaryDirectory(prefix="scarab-dijkstra-epoch-") as scratch:
        Path(scratch).chmod(0o755)
        target = Path(scratch) / f"{case['name']}.cbor"
        target.write_bytes(nes)
        target.chmod(0o644)
        for derivation in GOVERNANCE:
            done = subprocess.run(
                [*container(name, "-v", f"{scratch}:/in:ro", "-v", f"{ROOT / case['genesis']}:/genesis.json:ro"),
                 image_id, "/genesis.json", f"/in/{case['name']}.cbor", derivation,
                 *(f"{path}={','.join(map(str, case['slots'][path]))}" for path in PATHS)],
                capture_output=True, text=True)
            if done.returncode:
                raise SystemExit(f"the oracle failed on {case['name']} ({derivation}): {done.stderr}")
            records[derivation] = governance_record(case, json.loads(done.stdout))
    check_governance(case, records)
    return records


def recorded_governance():
    return json.loads(GOVERNANCE_FILE.read_text())


GENESIS_STATE_FILES = {"translated": f"genesis-{GENESIS_NAME}-translated.cbor",
                       "fromByron": f"genesis-{GENESIS_NAME}.cbor",
                       "createInitialState": f"genesis-{GENESIS_NAME}-create-initial-state.cbor"}


def genesis_record(result):
    """The fixture's record of the genesis case, and the bytes of its three states by file name: the state the era
    translations reach before the injection (with its UTxO), and the injected states from Byron and from
    `createInitialState` (without their UTxO, which is beside them)."""
    kept, record = {}, {"name": GENESIS_NAME, "genesis": [f"{GENESIS_DIR}/{name}" for name in GENESIS_FILES],
                        "byronUtxo": "empty", "version": result["version"], "equal": result["equal"]}
    translated = bytes.fromhex(result["translated"])
    kept[GENESIS_STATE_FILES["translated"]] = translated
    record["translated"] = {"stateSha256": sha256(translated), "bytes": len(translated),
                            "file": GENESIS_STATE_FILES["translated"]}
    for key in ("fromByron", "createInitialState"):
        state = bytes.fromhex(result[key]["state"])
        kept[GENESIS_STATE_FILES[key]] = state
        record[key] = {"stateSha256": sha256(state), "bytes": len(state), "file": GENESIS_STATE_FILES[key],
                       "utxo": result[key]["utxo"], "facts": result[key]["facts"]}
    return record, kept


def check_genesis(record):
    """The genesis case is what was asked and what Dijkstra's injection makes: version 12; the genesis files of the
    testnet; both injected states at epoch 0 with the genesis's pools registered and their VRF key hashes counted,
    the mark of epoch 0 with the Dijkstra genesis's committee size and the key age of the Shelley genesis's KES
    setup, a non-empty committee and the set the same; `equal` true exactly when the two states and their UTxO
    are."""
    def refuse(why):
        raise SystemExit(f"genesis {GENESIS_NAME}: {why}")

    byron_utxo_empty()
    shelley = json.loads((ROOT / GENESIS_DIR / "shelley-genesis.json").read_text())
    dijkstra = json.loads((ROOT / GENESIS_DIR / "dijkstra-genesis.json").read_text())
    pools = len({**shelley.get("staking", {}).get("pools", {}),
                 **shelley.get("extraConfig", {}).get("stakePools", {}).get("data", {})})
    g = genesis(f"{GENESIS_DIR}/shelley-genesis.json")
    key_age = math.ceil(Fraction(g["maxKESEvolutions"] * g["slotsPerKESPeriod"], g["epochLength"])) + 2
    if (set(record) != {"name", "genesis", "byronUtxo", "version", "equal", "translated", "fromByron",
                        "createInitialState"}
            or record["name"] != GENESIS_NAME or record["byronUtxo"] != "empty" or record["version"] != VERSION
            or record["genesis"] != [f"{GENESIS_DIR}/{name}" for name in GENESIS_FILES]
            or any(record[key]["file"] != file for key, file in GENESIS_STATE_FILES.items())):
        refuse("not the case asked")
    for key in ("fromByron", "createInitialState"):
        facts = record[key]["facts"]
        mark = facts["mark"]
        if (facts["epoch"] != 0 or facts["pools"] != pools or facts["vrfKeyHashes"] != pools
                or facts["rewardUpdate"] != "absent" or mark["epoch"] != 0
                or mark["committeeSize"] != dijkstra["leiosCommitteeSize"]
                or facts["leiosCommitteeSize"] != dijkstra["leiosCommitteeSize"] or mark["maxKeyAge"] != key_age
                or not 0 < mark["seats"] <= mark["committeeSize"]
                or facts["set"] != {k: mark[k] for k in ("seats", "keyedSeats")}):
            refuse(f"{key}: not the state Dijkstra's injection seats")
    same = (record["fromByron"]["stateSha256"] == record["createInitialState"]["stateSha256"]
            and record["fromByron"]["utxo"] == record["createInitialState"]["utxo"])
    if record["equal"] is not same:
        refuse("equal does not say whether the two states are equal")


def run_genesis(image_id, name):
    byron_utxo_empty()
    done = subprocess.run([*container(name, "-v", f"{ROOT / GENESIS_DIR}:/g:ro"), image_id, "genesis",
                           *(f"/g/{file}" for file in GENESIS_FILES)], capture_output=True, text=True)
    if done.returncode:
        raise SystemExit(f"the oracle failed on the genesis case: {done.stderr}")
    record, kept = genesis_record(json.loads(done.stdout))
    check_genesis(record)
    return record, kept


def run_image(image_id, name, states):
    results = []
    with tempfile.TemporaryDirectory(prefix="scarab-dijkstra-epoch-") as scratch:
        Path(scratch).chmod(0o755)
        for case, nes in states:
            target = Path(scratch) / f"{case['name']}.cbor"
            target.write_bytes(nes)
            target.chmod(0o644)
        for case, _ in states:
            rows, kept = [], {}
            for derivation in ("none", *DERIVATIONS):
                slots = case["slots" if derivation == "none" else "derivedSlots"]
                done = subprocess.run(
                    [*container(name, "-v", f"{scratch}:/in:ro", "-v", f"{ROOT / case['genesis']}:/genesis.json:ro"),
                     image_id, "/genesis.json", f"/in/{case['name']}.cbor", derivation,
                     *(f"{path}={','.join(map(str, slots[path]))}" for path in PATHS)],
                    capture_output=True, text=True)
                if done.returncode:
                    raise SystemExit(f"the oracle failed on {case['name']} ({derivation}): {done.stderr}")
                more, files = case_rows(case, derivation, json.loads(done.stdout))
                rows += more
                kept.update(files)
            check(case, rows)
            results.append((rows, kept))
    return results


def measured_image(name, accept_build):
    """The image resolved once to its ID, measured, its build accepted, and its ledger sources the pinned ones."""
    image_id = CHAIN.resolve(IMAGE)
    binding = accepted(image_id, image_binding(image_id, name), accept_build)
    return image_id, binding, source_hashes(image_id, name)


def recorded():
    return json.loads(CASES_FILE.read_text())["cases"]


def recorded_genesis():
    return json.loads(CASES_FILE.read_text()).get("genesis")


def state_files():
    return sorted(path.name for path in FIXTURE.glob("*.cbor"))


PROVENANCE_FIELDS = {"cases", "casesSha256", "consensusSourceCommit", "image", "imageId", "inputs", "ledger",
                     "governanceSha256", "measuredAt", "oracle", "packageSha256", "program", "programSha256",
                     "ratificationSha256",
                     "schemaVersion", "scope", "sourceSha256", "stateFiles"}
ORACLE_FIELDS = {"imageFilesSha256", "network", "oracleBinarySha256", "pinnedSources"}
LEDGER_RECORD = {"repository": "IntersectMBO/cardano-ledger", "revision": LEDGER,
                 "packages": ["cardano-ledger-dijkstra 0.5.0.0", "cardano-ledger-shelley 1.21.0.0",
                              "cardano-ledger-core 1.23.0.0"]}
DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")
SCOPE = ("Dijkstra's unchanged TICK (applyTickNoEvents) of the Leios testnet build, with the globals mkShelleyGlobals "
         "makes of each network's Shelley genesis file and a fixed epoch size, applied to the NewEpochState of two "
         "Leios-build state files (a devnet's first Dijkstra state, and the public Leios testnet at epoch 100) along "
         "two paths: the first slots of the next two epochs, and the same with a tick at each epoch's reward start "
         "point before its boundary; and, up to the first boundary, four states derived from each with the "
         "ledger's own lenses and functions (a pool retiring; a pool re-registering with another VRF key hash while "
         "a second pool shares its active one, the counts recomputed by populateVRFKeyHashes; both, retiring a third "
         "pool or the sharing one) to exercise Dijkstra's POOLREAP; a binding pledge leverage cap (CIP-50) in the "
         "current and previous parameters; and definite future parameters with another Leios committee size. No "
         "ledger rule makes the derived states. Also Dijkstra's genesis injection (injectIntoTestState) on the "
         "Leios testnet's genesis files, applied to the state the era translations reach from the empty Byron UTxO, "
         "as the node starts a chain in Dijkstra, and to createInitialState for comparison. And, from the testnet's "
         "state, one derived with a parameter change of keys 47 and 48 proposed (proposalsAddAction) with the yes "
         "votes of every hot committee credential, DRep and pool, the DReps' dormant epochs added as a "
         "transaction with a proposal adds them, ticked along both full paths: the pulser started at the first "
         "boundary ratifies it, and the second boundary enacts it. And two more such states, ticked alike: one with "
         "seven actions proposed, the first ratify's, so that the second boundary removes its competing sibling with "
         "the sibling's descendant, and an expired action with its descendant, keeping two (the removals as EPOCH's "
         "GovInfoEvent reports them); and ratify's action with its deposit returned to an account delegated to both "
         "a pool and a DRep, whose entries in the fresh pulser's distributions the first boundary credits with it. "
         "Each state reached is written with toCBOR at version 12, as a ledger snapshot "
         "holds it. The ledger's NewEpochState only: not the consensus layer's ledger state around it (tip, "
         "transition, cumulative transaction bytes), nor the header state")


def validate(provenance, rows, cases_sha256, cases, files, genesis_case, ratification, ratification_sha256,
             governance, governance_sha256):
    """What is wrong with a retained fixture: its provenance (`provenance.json`, parsed), its rows (`cases.json`'s
    cases), the digest of its case file, the names and digests of its state files (None to skip them), its genesis
    case (`cases.json`'s `genesis`), its ratification case (`ratification.json`) and the digest of that file, and its
    governance cases (`governance.json`) and the digest of that file, against the inputs and against the
    repository. Each field of the
    provenance is checked: its schema exactly, the pins, the ledger sources, and the oracle build it accepted (no
    network, the image's ID, every build file the repository determines, the executable's digest and the pinned
    checkouts)."""
    problems = []

    def require(holds, why):
        if not holds:
            problems.append(why)

    require(isinstance(provenance, dict) and set(provenance) == PROVENANCE_FIELDS,
            f"the provenance's fields are not exactly {sorted(PROVENANCE_FIELDS)}")
    if problems:
        return problems
    require(provenance["schemaVersion"] == 1, "schemaVersion is not 1")
    require(provenance["scope"] == SCOPE, "the scope is not the oracle's")
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
    count = len(STATES) * (1 + len(DERIVATIONS)) * len(PATHS)
    require(provenance["cases"] == len(rows) == count, f"the fixture does not hold its {count} cases")
    require(provenance["casesSha256"] == cases_sha256, f"{CASES_FILE.name} differs from its provenance")
    require(provenance["ratificationSha256"] == ratification_sha256,
            f"{RATIFICATION_FILE.name} differs from its provenance")
    require(provenance["governanceSha256"] == governance_sha256, f"{GOVERNANCE_FILE.name} differs from its provenance")
    named = {t["file"]: t["stateSha256"] for row in rows for t in row.get("ticks", []) if t.get("file")}
    named.update({row["derivation"]["file"]: row["derivation"]["stateSha256"] for row in rows
                  if isinstance(row.get("derivation"), dict) and row["derivation"].get("file")})
    if isinstance(genesis_case, dict):
        named.update({genesis_case[key]["file"]: genesis_case[key]["stateSha256"] for key in GENESIS_STATE_FILES
                      if isinstance(genesis_case.get(key), dict) and genesis_case[key].get("file")})
        try:
            check_genesis(genesis_case)
        except SystemExit as refused:
            problems.append(str(refused))
    else:
        problems.append("no genesis case")
    if isinstance(ratification, dict) and isinstance(ratification.get("derivation"), dict):
        named[ratification["derivation"]["file"]] = ratification["derivation"]["stateSha256"]
        try:
            check_ratification(next(c for c in cases if c["name"] == RATIFY_STATE), ratification)
        except SystemExit as refused:
            problems.append(str(refused))
    else:
        problems.append("no ratification case")
    try:
        check_governance(next(c for c in cases if c["name"] == RATIFY_STATE), governance)
    except (SystemExit, KeyError, TypeError, ValueError, IndexError) as refused:
        problems.append(f"{GOVERNANCE_FILE.name}: {refused}")
    require(provenance["stateFiles"] == dict(sorted(named.items())),
            "the state files of the provenance are not those the cases name")
    if files is not None:
        require(files == provenance["stateFiles"], "the fixture's .cbor files differ from those the provenance names")
    by_name = {c["name"]: c for c in cases}
    require([r.get("name") for r in rows]
            == [c["name"] for c in cases for _ in range((1 + len(DERIVATIONS)) * len(PATHS))],
            "the cases are not each input's derivations and paths, in order")
    for row in rows:
        case = by_name.get(row.get("name"))
        if case is not None:
            require({k: v for k, v in row.items() if k in case} == {k: v for k, v in case.items()
                                                                    if k not in CASE_ONLY},
                    f"{row.get('name')}: the input differs from the recorded one")
    oracle = provenance["oracle"]
    require(isinstance(oracle, dict) and set(oracle) == ORACLE_FIELDS,
            f"the oracle build's fields are not exactly {sorted(ORACLE_FIELDS)}")
    if problems:
        return problems
    require(oracle["network"] == "none", "the oracle ran with a network")
    image_files = oracle["imageFilesSha256"]
    require(isinstance(image_files, dict) and set(image_files) == {IMAGE_HARNESS, *IMAGE_BUILD}
            and all(isinstance(v, str) and CHAIN.DIGEST.fullmatch(v) for v in image_files.values()),
            f"the image's files are not exactly {sorted((IMAGE_HARNESS, *IMAGE_BUILD))}, each with a SHA-256")
    if problems:
        return problems
    for path, content in REPOSITORY_FILES.items():
        require(image_files[path] == sha256(content), f"the accepted build's {path} is not the repository's")
    require(oracle["oracleBinarySha256"] == image_files[IMAGE_BINARY],
            "the executable's digest is not the accepted build's")
    require(oracle["pinnedSources"] == {commit: "unchanged" for commit in CHAIN.pinned()},
            "the accepted build's checkouts are not the cabal project's pins, unchanged")
    if not problems:
        for case in cases:
            try:
                check(case, [row for row in rows if row["name"] == case["name"]])
            except SystemExit as refused:
                problems.append(str(refused))
    return problems


def offline(cases):
    """What is wrong with the retained fixture against its inputs and the repository."""
    files = {name: sha256((FIXTURE / name).read_bytes()) for name in state_files()}
    return validate(json.loads(PROVENANCE.read_text()), recorded(), file_sha256(CASES_FILE.relative_to(ROOT)), cases,
                    files, recorded_genesis(), recorded_ratification(),
                    file_sha256(RATIFICATION_FILE.relative_to(ROOT)), recorded_governance(),
                    file_sha256(GOVERNANCE_FILE.relative_to(ROOT)))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--record", action="store_true")
    mode.add_argument("--replay", action="store_true")
    parser.add_argument("--name", default="dijkstra-epoch",
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
        genesis_case, genesis_kept = run_genesis(image_id, args.name)
        ratification, ratification_kept = run_ratification(image_id, args.name, states)
        governance = run_governance(image_id, args.name, states)
        rows = [row for case_rows_, _ in results for row in case_rows_]
        kept = {name: data for _, files in results for name, data in files.items()}
        kept.update(genesis_kept)
        kept.update(ratification_kept)
        FIXTURE.mkdir(parents=True, exist_ok=True)
        for stale in set(state_files()) - set(kept):
            (FIXTURE / stale).unlink()
        for name, data in kept.items():
            (FIXTURE / name).write_bytes(data)
        CASES_FILE.write_text(json.dumps({"cases": rows, "genesis": genesis_case}, indent=1, sort_keys=True) + "\n")
        RATIFICATION_FILE.write_text(json.dumps(ratification, indent=1, sort_keys=True) + "\n")
        GOVERNANCE_FILE.write_text(json.dumps(governance, indent=1, sort_keys=True) + "\n")
        PROVENANCE.write_text(json.dumps({
            "schemaVersion": 1,
            "scope": SCOPE,
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
            "packageSha256": file_sha256(f"scripts/oracle/dijkstra-epoch/{PACKAGE}.cabal"),
            "cases": len(rows),
            "casesSha256": file_sha256(CASES_FILE.relative_to(ROOT)),
            "ratificationSha256": file_sha256(RATIFICATION_FILE.relative_to(ROOT)),
            "governanceSha256": file_sha256(GOVERNANCE_FILE.relative_to(ROOT)),
            "stateFiles": {name: sha256(data) for name, data in sorted(kept.items())},
            "measuredAt": datetime.date.today().isoformat(),
        }, indent=2, sort_keys=True) + "\n")
        for row in rows:
            print(f"recorded {row['name']} {row['path']}: "
                  + ", ".join(f"{t['slot']} (epoch {t['facts']['epoch']}, {t['bytes']} bytes"
                              f"{', reward update applied' if t['rewardUpdateApplied'] else ''})"
                              for t in row["ticks"]))
        return 0
    problems = offline(cases)
    if problems:
        print("; ".join(problems) + ": run --record", file=sys.stderr)
        return 1
    if args.replay:
        image_id, _, _ = measured_image(args.name, False)
        fresh = run_image(image_id, args.name, states)
        genesis_case, genesis_kept = run_genesis(image_id, args.name)
        ratification, ratification_kept = run_ratification(image_id, args.name, states)
        governance = run_governance(image_id, args.name, states)
        rows = [row for case_rows_, _ in fresh for row in case_rows_]
        kept = {name: data for _, files in fresh for name, data in files.items()}
        kept.update(genesis_kept)
        kept.update(ratification_kept)
        if (rows != recorded() or genesis_case != recorded_genesis() or ratification != recorded_ratification()
                or governance != recorded_governance()
                or any((FIXTURE / name).read_bytes() != data for name, data in kept.items())):
            print("the oracle's states differ from the fixture", file=sys.stderr)
            return 1
        print(f"{', '.join(STATES)}: the image's states equal the fixture")
        return 0
    print(f"{', '.join(STATES)}: the inputs, the program and the fixture match the provenance")
    return 0


if __name__ == "__main__":
    sys.exit(main())
