# SPDX-License-Identifier: Apache-2.0
"""Run the leader-check cases through the reference's native leader check (#770).

The cases straddle the threshold 1 - (1 - f)^sigma for active-slot coefficients across (0, 1],
for the Praos (2^256) and TPraos (2^512) value ranges. With --write the native results replace
the corpus; otherwise they are compared with it.
"""
import argparse
from decimal import Decimal, getcontext
import json
from pathlib import Path
import subprocess
import tempfile

WORD64 = (1 << 64) - 1
COEFFICIENTS = [(1, 20), (1, 2), (3, 5), (63, 100), (16, 25), (3, 4), (9, 10), (99, 100),
                (WORD64 - 1, WORD64)]
STAKES = [(1, 1), (1, 3), (1, 1000)]
RANGES = [("praos", 1 << 256), ("tpraos", 1 << 512)]
# relative offsets from the threshold: clear decisions, and ones decided at E34 resolution
OFFSETS = [Decimal("-1e-3"), Decimal("-1e-12"), Decimal("-1e-24"), Decimal(0),
           Decimal("1e-24"), Decimal("1e-12"), Decimal("1e-3")]


def threshold(f, sigma):
    getcontext().prec = 200
    one_minus = 1 - Decimal(f[0]) / Decimal(f[1])
    return 1 - (Decimal(sigma[0]) / Decimal(sigma[1]) * one_minus.ln()).exp()


def cases():
    rows = []
    for f in COEFFICIENTS:
        for sigma in STAKES:
            p = threshold(f, sigma)
            for label, size in RANGES:
                values = sorted({min(size - 1, max(0, int(p * (1 + d) * size))) for d in OFFSETS})
                for i, v in enumerate([0] + values + [size - 1]):
                    rows.append((f"f={f[0]}/{f[1]} stake={sigma[0]}/{sigma[1]} {label} #{i}",
                                 f, sigma, v, size))
    for label, size in RANGES:
        for v in (0, size // 2, size - 1):
            rows.append((f"f=1/1 stake=0/1 {label} value={v}", (1, 1), (0, 1), v, size))
    return [{"name": name, "activeNumerator": str(f[0]), "activeDenominator": str(f[1]),
             "stakeNumerator": str(s[0]), "stakeDenominator": str(s[1]),
             "value": str(v), "range": str(size)} for name, f, s, v, size in rows]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="scarab-plutus176-context-reference:local")
    parser.add_argument("--name", default="scarab-leader-threshold")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    corpus = here.parents[2] / "fixtures/reference/leader-threshold/cases.jsonl"
    inputs = cases()
    with tempfile.TemporaryDirectory(prefix="scarab-leader-threshold-") as directory:
        path = Path(directory) / "inputs.jsonl"
        path.write_text("".join(json.dumps(row) + "\n" for row in inputs))
        command = ["docker", "run", "--rm", "--name", args.name, "--network", "none",
                   "--cpus", "2", "--memory", "4g", "--tmpfs", "/build:exec",
                   "-v", f"{here}:/probe:ro", "-v", f"{path}:/inputs.jsonl:ro",
                   "--entrypoint", "sh", args.image, "-c",
                   "cabal exec -- ghc -Wall -Werror -L/usr/local/lib -optl-Wl,-rpath,/usr/local/lib -package cardano-ledger-core -package non-integral "
                   "-package aeson -outputdir /build /probe/Main.hs -o /build/oracle >&2 "
                   "&& /build/oracle < /inputs.jsonl"]
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(result.stderr)
        actual = [json.loads(line) for line in result.stdout.split("\n") if line]
    if args.write:
        corpus.parent.mkdir(parents=True, exist_ok=True)
        corpus.write_text("".join(json.dumps(row, sort_keys=True) + "\n" for row in actual))
        print(f"Wrote {len(actual)} native leader-check cases")
        return
    expected = [json.loads(line) for line in corpus.read_text().split("\n") if line]
    if actual != expected:
        raise SystemExit("native leader-check replay differs from the captured corpus")
    print(f"All {len(expected)} native leader-check comparisons passed")


if __name__ == "__main__":
    main()
