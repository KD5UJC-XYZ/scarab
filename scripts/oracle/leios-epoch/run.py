#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare Conway's tick across epoch boundaries, on the Leios layout, with the pinned Leios ledger build; --record captures native results."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from cleanup import container_labels

HERE = Path(__file__).resolve().parent
FIXTURE = ROOT / "fixtures/reference/leios-epoch"
CONFIG = ROOT / "fixtures/leios/testnet/config"
IMAGE = "scarab-leios-testnet:local"
LEDGER = "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c"
BASE = "c1e1ed8d7ae39c111c73fc5f59ac37ce5933b83c"

EPOCH = 21600
# one tick to each boundary, and one in each epoch past the start of the reward update
# (`randomnessStabilisationWindow`, 8640 slots on the testnet)
SLOTS = [EPOCH, EPOCH + 10800, 2 * EPOCH, 2 * EPOCH + 10800, 3 * EPOCH]


POOL = "6f1b079433f84acb8e4cc5faebae582d2e3fd7eeb13ee7111be91c9b"
# the first devnet pool's public voting key and proof of possession
KEYS = [line.split("\t") for line in (ROOT / "fixtures/leios/bls/devnet-pools.tsv").read_text().splitlines()
        if line and not line.startswith("#")]


def cases():
    """Each case is the Leios testnet's genesis files, its pool holding the first devnet pool's
    voting key (`keyed`) or none (as the testnet's file has it), and a change to the state Conway's
    genesis injection gives for them (`reregister`: the pool's own parameters with the cost one
    lovelace higher and no voting key, as a certificate registers them up to Conway; `retireAt`:
    the pool retires at that epoch), then Conway's tick to each of SLOTS in order."""
    rows = []

    def add(name, keyed, reregister=False, retire_at=None):
        rows.append({"name": name, "keyed": keyed, "reregister": reregister, "retireAt": retire_at,
                     "slots": SLOTS})

    add("testnet-genesis", keyed=False)
    add("testnet-genesis-reregistered", keyed=False, reregister=True)
    add("keyed", keyed=True)
    add("keyed-reregistered", keyed=True, reregister=True)
    add("keyed-retiring-at-1", keyed=True, retire_at=1)
    add("keyed-reregistered-and-retiring-at-2", keyed=True, reregister=True, retire_at=2)
    return rows


def base(keyed):
    """The testnet's Shelley genesis at Conway's protocol version, its pool keyed or not: the file
    names 12, Dijkstra's, which createInitialState refuses for Conway (`validateProtVerBounds`); the
    node reaches Conway through the era translations instead. The ticks run Conway's rules at
    version 10, inside Conway's range (9 to 11)."""
    doc = json.loads((CONFIG / "shelley-genesis.json").read_text())
    doc["protocolParams"]["protocolVersion"] = {"major": 10, "minor": 0}
    if keyed:
        doc["extraConfig"]["stakePools"]["data"][POOL]["blsKey"] = {
            "blsPubKey": KEYS[0][2], "blsPossessionProof": KEYS[0][3]}
    return doc


# The pinned sources the native result depends on, with the SHA-256 of each file at its pinned upstream
# revision (ledger LEDGER, cardano-base BASE). Both modes refuse an image whose copies differ.
SOURCES = {
    "Shelley/Transition.hs": ("*/eras/shelley/impl/src/Cardano/Ledger/Shelley/Transition.hs",
                              "6886aa7e600f352a3ed7f7822af115a0bdfcf406a6fe4affdfb409e6d9094e05"),
    "Shelley/Genesis.hs": ("*/eras/shelley/impl/src/Cardano/Ledger/Shelley/Genesis.hs",
                           "27faa873822e63d45fa3db8ad660e4a4bfc465d31a2ef0d52824c8a537b831aa"),
    "Conway/Transition.hs": ("*/eras/conway/impl/src/Cardano/Ledger/Conway/Transition.hs",
                             "f71c44727ba75ad5fc900c454c16abfa6008a63638a133f28e66c6934ca56ab1"),
    "StakePool.hs": ("*/libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs",
                     "459d7de8a2c1bb070476cb0bebb0cd758efeea61ea7e450306af3654f1592e70"),
    "SnapShots.hs": ("*/libs/cardano-ledger-core/src/Cardano/Ledger/State/SnapShots.hs",
                     "ae7d5e0db3afe5f97183008047d2f7ecf2974c7d1249752284a600b55557d414"),
    "LeiosCommittee.hs": ("*/libs/cardano-ledger-core/src/Cardano/Ledger/State/LeiosCommittee.hs",
                          "2bbdb60617b43aa92a9b7bfaaf2a951767690d581f38f2f8cd9b018e653e11bb"),
    "BLS12381/Internal.hs": ("*/cardano-crypto-class/src/Cardano/Crypto/DSIGN/BLS12381/Internal.hs",
                             "9ac5419c5febe57d72c5673cb1b12802ce3787058dbfebcd83103e5105986025"),
    "Shelley/Rules/Tick.hs": ("*/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Tick.hs",
                              "a482eace1620cc116eb0e60ab3d6740e2829441fe85013940574acba0b168d21"),
    "Conway/Rules/NewEpoch.hs": ("*/eras/conway/impl/src/Cardano/Ledger/Conway/Rules/NewEpoch.hs",
                                 "0da7018eb4de2a92b0dc5b5547c1d7ea3b7b883997f55be94e540ceaa61f4bf9"),
    "Conway/Rules/Epoch.hs": ("*/eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Epoch.hs",
                              "619d9553cc57d6d62e46343e90ee2ceb0c85a3f86d7fa16a937f10057a33ca1e"),
    "Shelley/Rules/PoolReap.hs": ("*/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/PoolReap.hs",
                                  "4d1e052ada703204f17ae40656afddd2db62116586d0a06dfc7b3212ce1b586c"),
    "Shelley/Rules/Snap.hs": ("*/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Snap.hs",
                              "6fb8fc09a1273056bbdb920f9b6cafa0f45bb0e73db57e9b36a09564a06fc1ea"),
    "Shelley/Rules/Rupd.hs": ("*/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Rupd.hs",
                              "14b05f72dd1b1104239813e565a982b8990c033e07d029f9f30da1a3e9ef4bad"),
}


def source_hashes(name):
    script = "; ".join(f"sha256sum $(find /oracle/leios-testnet/dist-newstyle/src -path '{pattern}')"
                       for pattern, _ in SOURCES.values())
    output = subprocess.check_output(
        ["docker", "run", "--rm", "--name", name, *container_labels("oracle", name), "--network", "none",
         "--entrypoint", "sh", IMAGE, "-c", script], text=True).splitlines()
    if len(output) != len(SOURCES):
        raise SystemExit(f"expected one copy of each pinned source in the image, found {output}")
    found = {label: line.split()[0] for label, line in zip(SOURCES, output, strict=True)}
    differing = sorted(label for label, (_, pinned) in SOURCES.items() if found[label] != pinned)
    if differing:
        raise SystemExit(f"{IMAGE} does not hold the pinned sources: {differing}")
    return found


PACKAGES = ["cardano-ledger-core", "cardano-ledger-binary", "cardano-ledger-shelley", "cardano-ledger-allegra",
            "cardano-ledger-mary", "cardano-ledger-alonzo", "cardano-ledger-babbage", "cardano-ledger-conway",
            "cardano-slotting", "containers", "fs-api", "microlens", "aeson",
            "base16-bytestring", "bytestring", "text"]


def native(rows, name):
    alonzo = (CONFIG / "alonzo-genesis.json").read_text()
    conway = (CONFIG / "conway-genesis.json").read_text()
    with tempfile.TemporaryDirectory(prefix="scarab-leios-epoch-") as tmp:
        inputs = Path(tmp) / "inputs.jsonl"
        inputs.write_text("".join(
            json.dumps({**row, "shelley": json.dumps(base(row["keyed"])), "alonzo": alonzo, "conway": conway}) + "\n" for row in rows))
        packages = " ".join(f"-package {p}" for p in PACKAGES)
        cmd = ["docker", "run", "--rm", "--name", name, *container_labels("oracle", name),
               "--network", "none", "--cpus", "2", "--memory", "4g", "--tmpfs", "/build:exec",
               "-v", f"{HERE}:/probe:ro", "-v", f"{inputs}:/inputs.jsonl:ro", "--entrypoint", "sh", IMAGE, "-c",
               "cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib "
               f"{packages} -outputdir /build /probe/Main.hs -o /build/oracle >&2 && /build/oracle < /inputs.jsonl"]
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stderr)
        outputs = [json.loads(line) for line in result.stdout.splitlines()]
    recorded = []
    for row, output in zip(rows, outputs, strict=True):
        if row["name"] != output["name"]:
            raise SystemExit("native result order changed")
        reference = output["reference"]
        if not reference["accepted"]:
            raise SystemExit(f"the native tick refused {row['name']}: {reference['failure']}")
        for tick in reference["ticks"]:
            tick["stateSha256"] = hashlib.sha256(bytes.fromhex(tick.pop("state"))).hexdigest()
        recorded.append({**row, "reference": reference})
    return recorded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--name", default="leios-epoch-reference")
    args = parser.parse_args()
    hashes = source_hashes(args.name)
    rows = native(cases(), args.name)
    path = FIXTURE / "cases.jsonl"
    if args.record:
        FIXTURE.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in rows))
        provenance = {
            "schemaVersion": 1,
            "capturedAt": datetime.datetime.now(datetime.timezone.utc).date().isoformat(),
            "scope": "The state Conway's unchanged genesis injection gives for the Leios testnet's genesis files "
                     "(its pool with a devnet voting key or none), changed by each case (a keyless re-registration of the genesis pool, its retirement, or both: "
                     "not a lawful chain's history), then Conway's unchanged TICK to boundaries and past the start "
                     "of the reward update: each input state, and after each tick the SHA-256 of the state as a "
                     "ledger snapshot holds it (without its UTxO, which it is also ticked without), its snapshots "
                     "and its pool states. Not Dijkstra's EPOCH, blocks, or the consensus layer's parts.",
            "build": "leios-rc-2026w47", "image": IMAGE, "ledgerSourceCommit": LEDGER, "cardanoBase": BASE,
            "imageId": subprocess.check_output(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"],
                                               text=True).strip(),
            "cases": len(rows),
            "genesisSha256": {f: hashlib.sha256((CONFIG / f).read_bytes()).hexdigest()
                              for f in ("shelley-genesis.json", "alonzo-genesis.json", "conway-genesis.json")},
            "sourceSha256": hashes,
            "harnessSha256": hashlib.sha256((HERE / "Main.hs").read_bytes()).hexdigest(),
            "casesSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
        (FIXTURE / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    else:
        expected = [json.loads(line) for line in path.read_text().splitlines()]
        if rows != expected:
            raise SystemExit("native ticks differ from the recorded corpus")
    print(f"Compared {len(rows)} native tick sequences")


if __name__ == "__main__":
    main()
