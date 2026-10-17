# SPDX-License-Identifier: Apache-2.0
"""The `kes-rotate` scenario (H2a, #116): the `pool` scenario with unattended KES rotation.

The Scala node is one of three pools. The genesis has a short `slotsPerKESPeriod` and a small
`maxKESEvolutions`; the node reads its KES key and certificate through a `current` link, the way
`scripts/pool/rotate_kes.py` expects, and serves a local socket. The driver runs on a short loop in the
scenario (a systemd timer in a real deployment) with the pool's cold key, which the node never sees. The
scenario fails unless the node rotated at least twice with no restart, kept forging across the rotations
with the certificate counter going up, and the Haskell pools took every block: their ledger counts the
Scala pool's blocks, and their view of the pool's counter is the last one used.
"""
import json
import os
import pathlib
import subprocess
import sys
import threading
import time
from ..util import ENV, MAGIC, REPO, docker, in_ref, put_file, ref

DRIVER = REPO / "scripts" / "pool" / "rotate_kes.py"
CLI_IMAGE = "ghcr.io/intersectmbo/cardano-node:11.1.3"
FIRST = "gen-000000-00000000"


def read_from_ref(args, path):
    return subprocess.run(["docker", "exec", ref(args.name), "cat", path], capture_output=True, check=True).stdout


def install_keys(args, root, ours):
    """The pool's keys as the driver keeps them: `keys/vrf.skey`, `keys/current -> gen-.../{kes.skey,node.cert}`;
    the cold key in `cold/`, which only the driver mounts. The node's socket directory is `ipc/`."""
    pool_keys = f"{ENV}/pools-keys/pool{ours}"
    keys = root / "keys"
    (keys / FIRST).mkdir(mode=0o700)
    (root / "cold").mkdir(mode=0o700)
    (root / "ipc").mkdir(mode=0o777)
    (root / "ipc").chmod(0o777)
    vrf = keys / "vrf.skey"
    vrf.write_bytes(read_from_ref(args, f"{pool_keys}/vrf.skey"))
    vrf.chmod(0o400)
    for source, target, mode in (("kes.skey", keys / FIRST / "kes.skey", 0o600),
                                 ("opcert.cert", keys / FIRST / "node.cert", 0o644),
                                 ("cold.skey", root / "cold" / "cold.skey", 0o400),
                                 ("cold.vkey", root / "cold" / "cold.vkey", 0o444)):
        target.write_bytes(read_from_ref(args, f"{pool_keys}/{source}"))
        target.chmod(mode)
    os.symlink(FIRST, keys / "current")


class Driver:
    """`rotate_kes.py` on a loop in a thread; every run's exit status and output are kept (and written to
    `rotate.log`)."""

    def __init__(self, args, root):
        self.args, self.root = args, root
        self.runs = []
        self.stop_flag = threading.Event()
        self.thread = threading.Thread(target=self.loop, daemon=True)

    def command(self):
        a, r = self.args, self.root
        return [sys.executable, str(DRIVER), "--key-dir", str(r / "keys"), "--cold-skey", str(r / "cold" / "cold.skey"),
                "--socket", str(r / "ipc" / "node.socket"), "--shelley-genesis", str(r / "genesis" / "shelley-genesis.json"),
                "--testnet-magic", str(MAGIC), "--container", f"{a.name}-scala", "--cli-image", CLI_IMAGE,
                "--threshold", str(a.rotate_threshold), "--confirm-timeout", "60"]

    def loop(self):
        log = (self.root / "rotate.log").open("a")
        while not self.stop_flag.is_set():
            started = time.time()
            done = subprocess.run(self.command(), capture_output=True, text=True)
            steps = [json.loads(l) for l in done.stdout.splitlines() if l.startswith("{")]
            self.runs.append({"at": started, "status": done.returncode, "steps": steps})
            log.write(json.dumps({"at": started, "status": done.returncode, "steps": steps,
                                  "stderr": done.stderr[-500:]}) + "\n")
            log.flush()
            self.stop_flag.wait(self.args.rotate_every)
        log.close()

    def start(self):
        self.thread.start()

    def stop(self):
        self.stop_flag.set()
        if self.thread.is_alive():
            self.thread.join(timeout=120)


def haskell_counter(args, root, observer):
    """The pool's issue counter on the chain, as a Haskell node reports it (`query kes-period-info` on the
    certificate the Scala node is using now)."""
    put_file(args.name, "/tmp/kes-rotate.cert", (root / "keys" / "current" / "node.cert").read_text())
    r = docker("exec", ref(args.name), "/bin/sh", "-c",
               f"cardano-cli conway query kes-period-info --testnet-magic {MAGIC} "
               f"--socket-path {ENV}/socket/node{observer}/sock --op-cert-file /tmp/kes-rotate.cert --output-json",
               check=False, timeout=60)
    start = r.stdout.find("{")  # the check marks the CLI prints first are not JSON
    try:
        return json.JSONDecoder().raw_decode(r.stdout[start:])[0].get("qKesNodeStateOperationalCertificateNumber")
    except ValueError:
        return None


def check(args, root, events, forged, on_chain, counts, driver, observer):
    """The failures and the summary of the rotation part of the run."""
    failures = []
    reloaded = [e for e in events if e["event"] == "kesReloaded"]
    counters = [e["opCertCounter"] for e in reloaded]
    started = [e for e in events if e["event"] == "forgeStarted"]
    summary = {"reloads": counters, "driverRuns": len(driver.runs),
               "driverStatuses": sorted({r["status"] for r in driver.runs}),
               "refused": counts.get("kesReloadRefused", 0), "persistFailed": counts.get("kesPersistFailed", 0)}
    if len(reloaded) < args.min_rotations:
        failures.append(f"{len(reloaded)} reloads, at least {args.min_rotations} required")
    if counters != sorted(set(counters)) or (counters and counters[0] <= started[0]["opCertCounter"]):
        failures.append(f"the certificate counters of the reloads do not increase: {counters}")
    if len(started) != 1:
        failures.append(f"the node started forging {len(started)} times (a restart); it must be once")
    if counts.get("kesReloadRefused"):
        failures.append(f"{counts['kesReloadRefused']} reloads were refused: "
                        f"{[e for e in events if e['event'] == 'kesReloadRefused'][:2]}")
    if counts.get("kesPersistFailed"):
        failures.append(f"{counts['kesPersistFailed']} evolved keys could not be written back: "
                        f"{[e for e in events if e['event'] == 'kesPersistFailed'][:2]}")
    bad_runs = [r for r in driver.runs if r["status"] not in (0,)]
    if bad_runs:
        failures.append(f"{len(bad_runs)} driver runs failed: {bad_runs[:2]}")
    # forging goes on across the rotations: blocks with every counter but (perhaps) the last, never an older one
    # after a newer one, and each reload is followed by blocks that carry its counter
    used = [e["opCertCounter"] for e in forged]
    if used != sorted(used):
        failures.append("a block carried an older certificate counter than an earlier block")
    expected = [started[0]["opCertCounter"], *counters] if started else []
    missing = [c for c in expected[:-1] if c not in used]
    if missing:
        failures.append(f"no block was forged with the certificate counters {missing}")
    # the counters in the log are in order: a reload takes effect before the next block is signed
    reload_at = {e["opCertCounter"]: e["at"] for e in reloaded}
    early = [e["hash"][:10] for e in forged if e["opCertCounter"] in reload_at and e["at"] < reload_at[e["opCertCounter"]]]
    if early:
        failures.append(f"blocks signed with a certificate before it was reloaded: {early}")
    chain_counters = sorted({e["opCertCounter"] for e in on_chain})
    summary.update({"countersUsed": sorted(set(used)), "countersOnChain": chain_counters})
    seen = haskell_counter(args, root, observer)
    summary["haskellCounter"] = seen
    if chain_counters and seen != chain_counters[-1]:
        failures.append(f"the Haskell nodes report the pool's counter as {seen}, the last block on the chain has "
                        f"{chain_counters[-1]}")
    generations = sorted(p.name for p in (root / "keys").iterdir() if p.name.startswith("gen-"))
    summary["generations"] = generations
    if len(generations) != 1 or generations[0] == FIRST:
        failures.append(f"the key directory holds {generations}: exactly the newest generation must remain")
    else:
        key, cert = root / "keys" / "current" / "kes.skey", root / "keys" / "current" / "node.cert"
        evolved_since = any(e["event"] == "kesEvolved" and e["at"] > reloaded[-1]["at"] for e in events) if reloaded else False
        if evolved_since and key.stat().st_mtime <= cert.stat().st_mtime:
            failures.append("the KES key file was not rewritten after the certificate was installed (no evolved key "
                            "was written back)")
    leftovers = sorted(p.name for p in (root / "keys").iterdir() if p.name.startswith(".staging") or p.name.endswith(".next"))
    if leftovers or list((root / "keys" / "current").glob("*.next")):
        failures.append(f"temporary key files remain: {leftovers}")
    return failures, summary
