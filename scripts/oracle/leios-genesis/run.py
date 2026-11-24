#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare the genesis injection of a chain that starts in Conway with the pinned Leios ledger build; --record captures native results."""
import argparse
import copy
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
FIXTURE = ROOT / "fixtures/reference/leios-genesis"
CONFIG = ROOT / "fixtures/leios/testnet/config"
IMAGE = "scarab-leios-testnet:local"
LEDGER = "9df9f98cce86c5f2195142b2c02dd9b39dd9dc7c"
BASE = "c1e1ed8d7ae39c111c73fc5f59ac37ce5933b83c"
POOL = "6f1b079433f84acb8e4cc5faebae582d2e3fd7eeb13ee7111be91c9b"
KEY = ["extraConfig", "stakePools", "data", POOL, "blsKey"]

KEYS = [line.split("\t") for line in (ROOT / "fixtures/leios/bls/devnet-pools.tsv").read_text().splitlines()
        if line and not line.startswith("#")]
VK, POP = KEYS[0][2], KEYS[0][3]


def flip_last(text):
    return text[:-2] + format(int(text[-2:], 16) ^ 1, "02x")


def bls(vk=VK, pop=POP):
    return {"blsPubKey": vk, "blsPossessionProof": pop}


def cases():
    """Each case is a change to the Leios testnet's Shelley genesis (`op` none, remove or replace at
    `path` with `value`, or legacy: see `changed`); the Alonzo and Conway genesis files are the
    testnet's."""
    rows = []

    def add(name, op="none", path=(), value=None):
        row = {"name": name, "op": op, "path": list(path)}
        if op in ("replace", "legacy"):
            row["value"] = value
        rows.append(row)

    add("testnet-genesis")
    add("bls-key-absent", "remove", KEY)
    add("bls-key-of-a-devnet-pool", "replace", KEY, bls())
    add("bls-key-upper-case-hex", "replace", KEY, bls(VK.upper(), POP.upper()))
    add("bls-key-proof-of-another-key", "replace", KEY, bls(pop=KEYS[1][3]))
    add("bls-key-unknown-field", "replace", KEY, {**bls(), "registeredIn": 5})
    add("bls-key-not-an-object", "replace", KEY, 5)
    add("bls-key-a-string", "replace", KEY, VK)
    add("bls-key-an-array", "replace", KEY, [VK, POP])
    add("bls-key-missing-proof", "replace", KEY, {"blsPubKey": VK})
    add("bls-key-missing-public-key", "replace", KEY, {"blsPossessionProof": POP})
    add("bls-key-public-key-a-number", "replace", KEY, {"blsPubKey": 1, "blsPossessionProof": POP})
    add("bls-key-public-key-null", "replace", KEY, {"blsPubKey": None, "blsPossessionProof": POP})
    add("bls-key-public-key-odd-hex", "replace", KEY, bls(VK[:-1]))
    add("bls-key-public-key-not-hex", "replace", KEY, bls("zz" + VK[2:]))
    add("bls-key-public-key-0x-prefix", "replace", KEY, bls("0x" + VK))
    add("bls-key-public-key-empty", "replace", KEY, bls(""))
    add("bls-key-public-key-short", "replace", KEY, bls(VK[:-2]))
    add("bls-key-public-key-long", "replace", KEY, bls(VK + "00"))
    add("bls-key-public-key-infinity", "replace", KEY, bls("c0" + "00" * 95))
    add("bls-key-public-key-not-on-the-curve", "replace", KEY, bls(flip_last(VK)))
    add("bls-key-public-key-not-compressed", "replace", KEY, bls(format(int(VK[:2], 16) & 0x7F, "02x") + VK[2:]))
    add("bls-key-proof-short", "replace", KEY, bls(pop=POP[:-2]))
    add("bls-key-proof-infinity", "replace", KEY, bls(pop="c0" + "00" * 47))
    add("bls-key-proof-not-on-the-curve", "replace", KEY, bls(pop=flip_last(POP)))
    add("bls-key-proof-a-verification-key", "replace", KEY, bls(pop=VK))
    # the testnet's pools moved to the genesis's legacy `staking.pools`, with the devnet pool's key
    add("bls-key-in-staking-pools", "legacy", KEY, bls())
    return rows


def base():
    """The testnet's Shelley genesis at Conway's protocol version: it names 12, Dijkstra's, which
    createInitialState refuses for Conway (`validateProtVerBounds`); the node reaches Conway through
    the era translations instead, and the injection does not read the version."""
    doc = json.loads((CONFIG / "shelley-genesis.json").read_text())
    doc["protocolParams"]["protocolVersion"] = {"major": 10, "minor": 0}
    return doc


def changed(row):
    """The Shelley genesis of a case: the base with the case's change. A `legacy` case moves the
    extra source's pools to `staking.pools` (the reference refuses both at once) and replaces the
    voting key there."""
    doc = base()
    path = row["path"]
    if row["op"] == "none":
        return doc
    if row["op"] == "legacy":
        doc["staking"]["pools"] = doc["extraConfig"].pop("stakePools")["data"]
        doc["staking"]["pools"][path[3]][path[4]] = copy.deepcopy(row["value"])
        return doc
    parent = doc
    for step in path[:-1]:
        parent = parent[step]
    if row["op"] == "remove":
        del parent[path[-1]]
    else:
        parent[path[-1]] = copy.deepcopy(row["value"])
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
            "fs-api", "microlens", "aeson", "base16-bytestring", "bytestring", "text"]


def native(rows, name):
    alonzo = (CONFIG / "alonzo-genesis.json").read_text()
    conway = (CONFIG / "conway-genesis.json").read_text()
    with tempfile.TemporaryDirectory(prefix="scarab-leios-genesis-") as tmp:
        inputs = Path(tmp) / "inputs.jsonl"
        inputs.write_text("".join(
            json.dumps({"name": row["name"], "shelley": json.dumps(changed(row)), "alonzo": alonzo,
                        "conway": conway}) + "\n" for row in rows))
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
    starts = {o["reference"]["start"] for o in outputs if o["reference"]["accepted"]}
    if len(starts) != 1:
        raise SystemExit(f"expected one start state across the accepted cases, found {len(starts)}: "
                         f"{outputs[0]['reference']}")
    recorded = [{"name": "start", "kind": "start", "cbor": starts.pop()}]
    for row, output in zip(rows, outputs, strict=True):
        if row["name"] != output["name"]:
            raise SystemExit("native result order changed")
        reference = output["reference"]
        if reference["accepted"]:
            reference.pop("start")
            reference["stateSha256"] = hashlib.sha256(bytes.fromhex(reference.pop("state"))).hexdigest()
        recorded.append({**row, "reference": reference})
    return recorded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true")
    parser.add_argument("--name", default="leios-genesis-reference")
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
            "scope": "The Leios testnet's genesis files and changes to its pool's voting key, read with the unchanged "
                     "FromJSON instances; createInitialState of Conway from them; Conway's injectIntoTestState "
                     "applied to that state: acceptance parity, the SHA-256 of the resulting NewEpochState "
                     "without its UTxO (as a ledger snapshot holds it, the UTxO in its tables), and its exact UTxO, "
                     "snapshots and pool states. Not the Byron to Conway translations the node "
                     "applies before the injection, nor the consensus layer's parts of the start state.",
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
            raise SystemExit("native genesis injections differ from the recorded corpus")
    print(f"Compared {len(rows) - 1} native genesis injections")


if __name__ == "__main__":
    main()
