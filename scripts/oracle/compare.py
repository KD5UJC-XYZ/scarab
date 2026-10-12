# SPDX-License-Identifier: Apache-2.0
"""Compare the reference projection of a snapshot with a digest written by `follow --dump-state`."""
import json
import pathlib

from . import digest

DIGEST_KEYS = {"count", "digest", "buckets"}


def is_digest(x):
    return isinstance(x, dict) and set(x) == DIGEST_KEYS


def as_digest(x):
    """The digest of a plain object (so a digest can be compared with an unreduced object)."""
    return x if is_digest(x) else digest.digest_of(x.items())


def diff(reference, scala, path=""):
    """[(path, reference, scala)] where the two projections differ. Keys the reference does not have are
    not compared (the Scala projection is a superset); digests narrow a difference to buckets."""
    out = []
    if isinstance(reference, dict) and isinstance(scala, dict):
        if is_digest(reference) or is_digest(scala):
            r, s = as_digest(reference), as_digest(scala)
            if r != s:
                bad = [i for i, (a, b) in enumerate(zip(r["buckets"], s["buckets"])) if a != b]
                out.append((path, f"{r['count']} entries, buckets {bad} differ",
                            f"{s['count']} entries, digest {s['digest'][:16]}"))
            return out
        for k in sorted(reference):
            if k not in scala:
                out.append((f"{path}/{k}", reference[k], "<absent>"))
            else:
                out += diff(reference[k], scala[k], f"{path}/{k}")
    elif isinstance(reference, list) and isinstance(scala, list) and len(reference) == len(scala):
        for i, (a, b) in enumerate(zip(reference, scala)):
            out += diff(a, b, f"{path}[{i}]")
    elif isinstance(reference, float) or isinstance(scala, float):
        # parameters given as doubles: compared to 10 digits, as the devnet comparison does
        if not (isinstance(reference, (int, float)) and isinstance(scala, (int, float))
                and round(reference, 10) == round(scala, 10)):
            out.append((path, reference, scala))
    elif reference != scala:
        out.append((path, reference, scala))
    return out


def load_dumps(directory):
    """{slot: (hash, path)} of the digests under `directory` (names `<slot>-<hash>.json`)."""
    out = {}
    for f in sorted(pathlib.Path(directory).glob("*.json")):
        slot, _, rest = f.stem.partition("-")
        out.setdefault(int(slot), []).append((rest, f))
    return out


def compare_files(reference, scala_path, pre_alonzo_ok=False):
    scala = json.loads(pathlib.Path(scala_path).read_text())
    ref = dict(reference)
    ref.pop("era", None)
    differences = diff(ref, scala)
    return differences


def compare_dirs(ref_dir, dump_dir, results):
    """Pair the reference projections of `ref_dir` (see `reference.py`) with the Scala digests of `dump_dir`
    by block, compare them and write `results` (JSON: a summary and one row per epoch). Returns the summary."""
    ref_dir = pathlib.Path(ref_dir)
    index = [json.loads(line) for line in (ref_dir / "index.jsonl").read_text().splitlines()]
    dumps = load_dumps(dump_dir)
    rows, waiting = [], []
    for r in sorted({r["epoch"]: r for r in index}.values(), key=lambda r: r["epoch"]):
        paired = [p for h, p in dumps.get(r["slot"], []) if h == r["hash"]]
        row = {k: r[k] for k in ("epoch", "slot", "hash", "era", "utxoSize")}
        if not paired:
            waiting.append(r["epoch"])
            if r["slot"] in dumps:
                row.update(matched=False, error="the Scala digest is for another block: " +
                           ", ".join(h for h, _ in dumps[r["slot"]]))
                rows.append(row)
            continue
        reference = json.loads((ref_dir / f"{r['slot']:012d}-{r['hash']}.json").read_text())
        differences = compare_files(reference, paired[0])
        row.update(matched=not differences, differenceCount=len(differences),
                   differences=[{"path": p, "reference": str(a)[:300], "scala": str(b)[:300]}
                                for p, a, b in differences[:25]])
        rows.append(row)
    mismatched = [r for r in rows if not r["matched"]]
    summary = {"referenceEpochs": len(index), "compared": len(rows), "matched": len(rows) - len(mismatched),
               "mismatched": len(mismatched), "waitingForScala": len(waiting),
               "firstMismatch": mismatched[0] if mismatched else None}
    pathlib.Path(results).write_text(json.dumps({"summary": summary, "rows": rows}, indent=1))
    return summary
