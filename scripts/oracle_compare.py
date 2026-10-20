#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare the Scala node's ledger state with the Haskell node's at epoch boundaries (roadmap K1).

    oracle_compare.py project SNAPSHOT [--full]      the comparable projection of a db-analyser snapshot directory
    oracle_compare.py compare SNAPSHOT DIGEST        diff a snapshot against a `follow --dump-state` digest file
    oracle_compare.py reference ...          db-analyser over a ChainDB: the Haskell state at the start of every
                                             epoch, projected (parallel chains; Preview, Preprod, Mainnet)
    oracle_compare.py diff ...               pair those projections with `follow --dump-state` digests and compare
    oracle_compare.py export ...             write the Scala node's immutable store as a Haskell ImmutableDB

For a devnet cluster use `devnet.py e2e --oracle` / `devnet.py oracle`. The Haskell side is the reference
image's db-analyser (`cardano-reference-11.1.3:local`), run in Docker; everything else is standard library.
"""
import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from oracle import compare, export, ledger, reference  # noqa: E402


def project(args):
    if args.full:
        s = ledger.Snapshot(args.snapshot)
        print(json.dumps(s.projection(with_utxo=False), indent=1))
    else:
        print(json.dumps(ledger.reduced(args.snapshot), indent=1))


def compare_cmd(args):
    reference = ledger.reduced(args.snapshot)
    differences = compare.compare_files(reference, args.digest)
    print(json.dumps({"matched": not differences, "differenceCount": len(differences),
                      "differences": [{"path": p, "reference": str(a)[:400], "scala": str(b)[:400]}
                                      for p, a, b in differences[:50]]}, indent=1))
    sys.exit(0 if not differences else 1)


def reference_cmd(args):
    rows = reference.run(args.config, args.db, args.work, args.out, args.epoch_slots, args.first_epoch,
                         args.last_epoch, jobs=args.jobs, seed_every=args.seed_every, cpus=args.cpus,
                         memory=args.memory, live=args.live, log=lambda m: print(json.dumps(m), flush=True))
    print(json.dumps({"epochsProjected": len(rows)}))


def export_cmd(args):
    n = export.export(args.scala_immutable, args.out, args.chunk_slots, args.magic_file, upto_slot=args.upto_slot,
                      log=lambda m: print(json.dumps(m), flush=True))
    print(json.dumps({"blocks": n}))
    if args.verify_against:
        bad = export.verify(args.out, args.verify_against, args.verify_chunks)
        print(json.dumps({"verifiedChunks": args.verify_chunks, "differingFiles": bad}))
        sys.exit(1 if bad else 0)


def diff_cmd(args):
    summary = compare.compare_dirs(args.reference, args.dump, args.results, args.expected_epochs)
    print(json.dumps(summary, indent=1))
    passed = summary["complete"] if args.require_complete or args.expected_epochs is not None else summary["mismatched"] == 0
    sys.exit(0 if passed else 1)


def positive_int(value):
    n = int(value)
    if n < 1:
        raise argparse.ArgumentTypeError("must be positive")
    return n


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    pr = sub.add_parser("project")
    pr.add_argument("snapshot")
    pr.add_argument("--full", action="store_true", help="unreduced projection without the UTxO")
    pr.set_defaults(run=project)
    c = sub.add_parser("compare")
    c.add_argument("snapshot")
    c.add_argument("digest")
    c.set_defaults(run=compare_cmd)
    rf = sub.add_parser("reference", help="project the Haskell ledger state at the start of each epoch of a ChainDB "
                                          "(db-analyser, in Docker) into --out")
    rf.add_argument("--config", required=True, help="node configuration directory (config.json and genesis files)")
    rf.add_argument("--db", required=True, help="ChainDB directory holding immutable/ (a node's, or an export)")
    rf.add_argument("--work", required=True, help="working directory (hard-linked ChainDB copies, snapshots)")
    rf.add_argument("--out", required=True, help="output directory of the projections and index.jsonl")
    rf.add_argument("--epoch-slots", type=int, required=True, help="slots per epoch (Preview 86400)")
    rf.add_argument("--first-epoch", type=int, default=1)
    rf.add_argument("--last-epoch", type=int, default=100000, help="stop here (default: the end of the chain)")
    rf.add_argument("--jobs", type=int, default=4, help="parallel segments")
    rf.add_argument("--seed-every", type=int, default=50, help="epochs per segment")
    rf.add_argument("--cpus", type=int, default=2, help="CPUs per db-analyser")
    rf.add_argument("--memory", default="16g", help="memory per db-analyser")
    rf.add_argument("--live", action="store_true", help="--db is a running node's: copy its finished chunks first")
    rf.set_defaults(run=reference_cmd)
    ex = sub.add_parser("export", help="write the Scala node's immutable store (--db <dir>/immutable) as a Haskell "
                                       "ImmutableDB")
    ex.add_argument("--scala-immutable", required=True, help="the immutable/ directory of the Scala node's --db")
    ex.add_argument("--out", required=True, help="output ChainDB directory (gets immutable/ and protocolMagicId)")
    ex.add_argument("--chunk-slots", type=int, required=True,
                    help="slots per chunk: ten times the security parameter of the Byron genesis (Preview 4320)")
    ex.add_argument("--magic-file", help="a protocolMagicId file to copy (a Haskell node's; the decimal magic)")
    ex.add_argument("--upto-slot", type=int, help="export blocks up to this slot")
    ex.add_argument("--verify-against", help="a Haskell node's ChainDB directory: compare the first chunks byte for byte")
    ex.add_argument("--verify-chunks", type=int, default=100)
    ex.set_defaults(run=export_cmd)
    df = sub.add_parser("diff", help="compare the projections of `reference` with the Scala digests")
    df.add_argument("--reference", required=True, help="the --out directory of `reference`")
    df.add_argument("--dump", required=True, help="directory of the Scala node's --dump-state digests")
    df.add_argument("--results", required=True, help="JSON file to write")
    df.add_argument("--require-complete", action="store_true",
                    help="require a nonempty reference, one entry per epoch and every exact block matched")
    df.add_argument("--expected-epochs", type=positive_int,
                    help="require complete comparison of exactly this many reference epochs")
    df.set_defaults(run=diff_cmd)
    args = p.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
