# SPDX-License-Identifier: Apache-2.0
"""Publish fresh signed refusal observations without rewriting historical captures."""
import argparse
from enum import Enum
import json
from pathlib import Path

from .refusalcorpus import GENESIS, families, item, provenance, sha256, write


class Problem(Enum):
    PROFILE = "capture has another era or protocol"
    MISSING = "refused case lacks complete wire and CLI evidence"
    ITEM = "wire item does not contain the original signed transaction"
    REPEATS = "complete repeated rejection messages do not agree"
    IDENTITY = "content-addressed input does not match its identity"


class ExportError(Exception):
    def __init__(self, problem, label):
        self.problem, self.label = problem, label
        super().__init__(f"{label}: {problem.value}")


def validate(row, era, protocol):
    label, reference = row["name"], row["reference"]
    if row["era"] != era or row["protocolMajor"] != protocol:
        raise ExportError(Problem.PROFILE, label)
    if reference["accepted"]:
        return None
    wire, cli = reference.get("wire"), reference.get("cli")
    if not wire or not cli or cli["exit"] == 0:
        raise ExportError(Problem.MISSING, label)
    transaction = bytes.fromhex(wire["transaction"])
    if item(era, transaction).hex() != wire["item"]:
        raise ExportError(Problem.ITEM, label)
    observations = wire["observations"]
    reason = reference["reasonCbor"]
    # MsgRejectTx is [2, reason]. Compare each complete message, not parsed text or a projection.
    if len(observations) < 4 or any(
            o["reasonCbor"] != reason or o["messageCbor"] != "8202" + reason
            for o in observations):
        raise ExportError(Problem.REPEATS, label)
    return wire


def export(name, root, input_file, out, corpus_name, era, protocol, source_commit):
    """Retain source rows verbatim and add a corpus consumed by the whole-reason Scala gate."""
    rows = json.loads(input_file.read_text())
    directory = out / corpus_name
    if directory.exists():
        raise FileExistsError(directory)
    records = []
    for row in rows:
        wire = validate(row, era, protocol)
        if wire is None:
            continue
        paths = {}
        for kind, field in (("states", "stateSha256"), ("utxo", "utxoSha256")):
            identity = wire[field]
            data = (root / "refusal-evidence" / kind / f"{identity}.cbor").read_bytes()
            if sha256(data) != identity:
                raise ExportError(Problem.IDENTITY, row["name"])
            relative = f"{kind}/{identity}.cbor"
            write(directory / relative, data)
            paths[kind] = relative
        reference = row["reference"]
        reason = bytes.fromhex(reference["reasonCbor"])
        records.append({"label": row["name"], "era": era, "families": families(era, reason),
                        "item": wire["item"], "tip": wire["tip"],
                        "mempoolSlot": wire["mempoolSlot"], "state": paths["states"],
                        "utxo": paths["utxo"], "reason": reason.hex(),
                        "reasonSha256": sha256(reason), "cli": reference["cli"],
                        "observations": wire["observations"],
                        "controlledInterveningRequests": wire["controlledInterveningRequests"]})
    data = b"".join((json.dumps(row, separators=(",", ":")) + "\n").encode() for row in records)
    write(directory / "cases/cases.jsonl", data)
    # This is the fresh original input, including accepted controls and exact signed transactions.
    source = input_file.read_bytes()
    write(directory / "recording.json", source)
    genesis = {n: (root / "genesis" / n).read_bytes() for n in GENESIS}
    for filename, raw in genesis.items():
        write(out / "genesis" / f"{sha256(raw)}-{filename}", raw)
    command = ("python3 -m devnet.refusalexport --name <devnet> --input <recording> "
               f"--corpus {corpus_name} --era {era} --protocol {protocol} --source-commit {source_commit}")
    meta = {"format": 2, "era": era, "protocolVersion": [protocol, 0],
            "provenance": provenance(name, command),
            "captureSourceCommit": source_commit,
            "recording": {"file": "recording.json", "sha256": sha256(source)},
            "genesis": {n: sha256(raw) for n, raw in genesis.items()},
            "cases": [{"file": "cases/cases.jsonl", "sha256": sha256(data)}]}
    meta_bytes = (json.dumps(meta, indent=2) + "\n").encode()
    write(directory / "corpus.json", meta_bytes)
    manifest_path = out / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["corpora"] = sorted(manifest["corpora"] + [
        {"path": corpus_name, "sha256": sha256(meta_bytes)}], key=lambda entry: entry["path"])
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    return {"corpus": corpus_name, "cases": len(rows), "refusals": len(records),
            "recordingSha256": sha256(source)}


if __name__ == "__main__":
    from .util import data_root, REPO
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--corpus", required=True)
    parser.add_argument("--era", choices=("alonzo", "babbage", "conway"), required=True)
    parser.add_argument("--protocol", type=int, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--out", type=Path, default=REPO / "fixtures/reference/refusals")
    args = parser.parse_args()
    print(json.dumps(export(args.name, data_root(args.name), args.input, args.out,
                            args.corpus, args.era, args.protocol, args.source_commit)))
