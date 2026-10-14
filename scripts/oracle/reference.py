# SPDX-License-Identifier: Apache-2.0
"""The Haskell side of the comparison on a public network: reference projections at epoch boundaries.

db-analyser, run from the reference image over a ChainDB copy, stores the ledger state after the first block at or
after the first slot of each epoch (`epoch * epoch_slots`: the block the Scala node digests, `--dump-state-at
epoch-boundaries`). Each snapshot is projected (`ledger.reduced`) and written to `<out>/<slot>-<hash>.json`, with
a line in `<out>/index.jsonl`; `compare.compare_dirs` pairs these files with the Scala digests later, so the two
sides do not have to run at the same time or speed.

A run in `jobs` parallel chains: a seed chain stores the states at every `seed_every`-th epoch (one pass over the
chain), and each segment of `seed_every` epochs then continues from its seed in a worker of its own. A segment
runs `db-analyser --store-ledger <slot> --analyse-from <previous slot>` once per epoch (one snapshot load, one
epoch of blocks, one snapshot write), keeps only the newest snapshot and writes the projection. Everything is
resumable: epochs already in the index are skipped.
"""
import json
import os
import pathlib
import shutil
import subprocess
import threading
import time
import traceback
from concurrent.futures import ThreadPoolExecutor

from . import ledger
from .analyse import Analyser, REFERENCE_IMAGE


def link_tree(src, dst):
    """A copy of the ImmutableDB made of hard links (db-analyser reopens the newest chunk but does not change it)."""
    subprocess.run(["cp", "-al", str(src), str(dst)], check=True)


def sync_immutable(source, target):
    """Copy the finished chunks of a live node's `source/immutable` to `target/immutable` (all but the newest
    chunk, which the node is still appending to). Returns the number of chunks copied."""
    src, dst = pathlib.Path(source) / "immutable", pathlib.Path(target) / "immutable"
    dst.mkdir(parents=True, exist_ok=True)
    chunks = sorted({f.name.split(".")[0] for f in src.iterdir() if f.name[:5].isdigit()})
    for c in chunks[:-1]:
        for ext in ("chunk", "primary", "secondary"):
            s, d = src / f"{c}.{ext}", dst / f"{c}.{ext}"
            if s.exists() and (not d.exists() or d.stat().st_size != s.stat().st_size):
                shutil.copyfile(s, d)
    magic = pathlib.Path(source) / "protocolMagicId"
    if magic.exists():
        shutil.copyfile(magic, pathlib.Path(target) / "protocolMagicId")
    return max(len(chunks) - 1, 0)


def docker_launch(config_dir, db, cpus, memory, name):
    def launch(args):
        return ["docker", "run", "--rm", "--name", name, f"--cpus={cpus}", f"--memory={memory}",
                "--user", f"{os.getuid()}:{os.getgid()}", "-v", f"{config_dir}:/config:ro", "-v", f"{db}:/db",
                "--entrypoint", "db-analyser", REFERENCE_IMAGE, *args]
    return launch


class Index:
    """`<out>/index.jsonl`: the epochs already projected (thread safe)."""

    def __init__(self, out):
        self.out = pathlib.Path(out)
        self.out.mkdir(parents=True, exist_ok=True)
        self.file = self.out / "index.jsonl"
        self.lock = threading.Lock()
        self.rows = {}
        if self.file.exists():
            for line in self.file.read_text().splitlines():
                r = json.loads(line)
                self.rows[r["epoch"]] = r

    def add(self, row):
        with self.lock:
            self.rows[row["epoch"]] = row
            with self.file.open("a") as f:
                f.write(json.dumps(row) + "\n")


def project_into(snapshot, out, epoch, target, started, utxo_workers):
    """Project one snapshot into the output directory; returns its index row."""
    t = time.monotonic()
    reference = ledger.reduced(snapshot, workers=utxo_workers)
    name = f"{reference['point']['slot']:012d}-{reference['point']['hash']}.json"
    (pathlib.Path(out) / (name + ".tmp")).write_text(json.dumps(reference))
    os.replace(pathlib.Path(out) / (name + ".tmp"), pathlib.Path(out) / name)
    return {"epoch": epoch, "target": target, "slot": reference["point"]["slot"], "hash": reference["point"]["hash"],
            "era": reference["era"], "ledgerEpoch": reference["epoch"], "utxoSize": reference["utxo"]["count"],
            "projectSeconds": round(time.monotonic() - t, 1), "totalSeconds": round(time.monotonic() - started, 1)}


def run(config_dir, db, work, out, epoch_slots, first_epoch, last_epoch, jobs=4, seed_every=50, cpus=2,
        memory="16g", utxo_workers=2, live=False, log=print):
    """Project the Haskell ledger state at the start of epochs `first_epoch..last_epoch` (all the chain has)."""
    work = pathlib.Path(work)
    work.mkdir(parents=True, exist_ok=True)
    base = work / "base"
    if live:
        sync_immutable(db, base)
    elif not base.exists():
        base.mkdir()
        link_tree(pathlib.Path(db) / "immutable", base / "immutable")
        magic = pathlib.Path(db) / "protocolMagicId"
        if magic.exists():
            shutil.copyfile(magic, base / "protocolMagicId")
    index = Index(out)
    todo = [(e, e * epoch_slots) for e in range(first_epoch, last_epoch + 1) if e not in index.rows]
    if not todo:
        return index.rows
    segments = [todo[i:i + seed_every] for i in range(0, len(todo), seed_every)]
    seeds = [threading.Event() for _ in segments]
    seed_dir = work / "seeds"
    seed_dir.mkdir(exist_ok=True)
    failed = []

    def chain_dir(name):
        d = work / name
        shutil.rmtree(d, ignore_errors=True)
        (d / "ledger").mkdir(parents=True)
        (d / "volatile").mkdir()
        link_tree(base / "immutable", d / "immutable")
        if (base / "protocolMagicId").exists():
            shutil.copyfile(base / "protocolMagicId", d / "protocolMagicId")
        return d

    def step(analyser, d, epoch, target, previous):
        """Store the state of one epoch start and project it; returns (slot, row) or None at the chain's end."""
        started = time.monotonic()
        stored = analyser.store(target, analyse_from=previous)
        if stored is None:
            return None
        row = project_into(d / "ledger" / f"{stored}_db-analyser", out, epoch, target, started, utxo_workers)
        row["analyserSeconds"] = round(analyser.last_seconds, 1)
        index.add(row)
        log({k: row[k] for k in ("epoch", "slot", "era", "utxoSize", "analyserSeconds", "projectSeconds")})
        return stored

    def seed_chain():
        d = chain_dir("seed")
        analyser = Analyser(docker_launch(config_dir, d, cpus, memory, "csl-k1-seed"), "/db", "/config/config.json")
        previous = None
        try:
            for i, segment in enumerate(segments):
                epoch, target = segment[0]
                try:
                    stored = step(analyser, d, epoch, target, previous)
                except Exception:
                    failed.append(("seed", epoch, traceback.format_exc()[-1500:]))
                    break
                if stored is None:
                    break  # the chain ends before this segment
                dst = seed_dir / f"{i}"
                shutil.rmtree(dst, ignore_errors=True)
                shutil.copytree(d / "ledger" / f"{stored}_db-analyser", dst / f"{stored}_db-analyser")
                if previous is not None:
                    shutil.rmtree(d / "ledger" / f"{previous}_db-analyser", ignore_errors=True)
                previous = stored
                seeds[i].set()
        finally:
            for e in seeds:
                e.set()  # segments of a chain that ended must not wait forever
        shutil.rmtree(d, ignore_errors=True)

    def segment_worker(i):
        seeds[i].wait()
        src = seed_dir / f"{i}"
        if not src.exists():
            return
        d = chain_dir(f"seg{i}")
        shutil.copytree(src, d / "ledger", dirs_exist_ok=True)
        previous = int(next(src.iterdir()).name.split("_")[0])
        analyser = Analyser(docker_launch(config_dir, d, cpus, memory, f"csl-k1-seg{i}"), "/db", "/config/config.json")
        try:
            for epoch, target in segments[i][1:]:
                try:
                    stored = step(analyser, d, epoch, target, previous)
                except Exception:
                    failed.append((f"segment {i}", epoch, traceback.format_exc()[-1500:]))
                    return
                if stored is None:
                    return
                shutil.rmtree(d / "ledger" / f"{previous}_db-analyser", ignore_errors=True)
                previous = stored
        finally:
            shutil.rmtree(d, ignore_errors=True)
            shutil.rmtree(src, ignore_errors=True)

    with ThreadPoolExecutor(max_workers=jobs + 1) as pool:
        futures = [pool.submit(seed_chain)] + [pool.submit(segment_worker, i) for i in range(len(segments))]
        for f in futures:
            f.result()
    if failed:
        log({"failed": failed})
    return index.rows
