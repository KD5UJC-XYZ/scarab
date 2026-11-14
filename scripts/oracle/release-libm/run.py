# SPDX-License-Identifier: Apache-2.0
"""Golden bits of the release node's own log, pow and exp, for MuslMath (#1003).

The cardano-node 11.1.3 release binary is static and linked against musl. `probe.c` maps it and calls its own
`log`, `pow` and `exp`; this script builds the probe in a pinned compiler image, runs it inside the reference
image with no network, and writes `fixtures/reference/release-libm`:

- `cases.jsonl`: one function case per line, `{"f": "log"|"exp"|"pow", "x": X, ["y": Y,] "r": R}` with every
  value an IEEE-754 bit pattern in hex, and one likelihood record per line, `{"f": "lik", "stake": [C, T],
  "s": S, "d": D, "asc": F, "n": N, "m": M, "samples": [100 Float bit patterns]}`: the reference's
  `likelihood n (leaderProbability f (C/T) d) (n + m)`, PoolRank.hs:126-172 at cardano-ledger f649f975;
- `tables.txt`: the bytes of the binary's `__log_data`, `__pow_log_data` and `__exp_data`.

The inputs are generated here with exact rationals and a fixed seed. The binary must have the digest
`scripts/prepare_reference_image.py` pins. With `--other-image` the same probe runs against a second release
(11.2.1) and must give identical results. Unless `--skip-musl`, the pinned musl release is downloaded, its
signature-checked digest compared, and its own tables, compiled by `musl_tables.c`, compared with the
binary's. With `--write` the corpus is replaced; otherwise the run is compared with it.

`--bulk DIR` instead writes 26,000,000 arguments (seed 1003) to `DIR/{log,exp,pow}.in` and the binary's results
to `DIR/{log,exp,pow}.ref`, for a comparison too large to commit: run
`tools/runMain xyz.kd5ujc.scarab.ledger.rules.MuslMathMain raw log DIR/log.in DIR/log.scala` (and `exp`, `pow`)
in the JDK image, then `cmp DIR/log.ref DIR/log.scala`.
"""
import argparse
from fractions import Fraction
import hashlib
import io
import json
import math
import os
from pathlib import Path
import random
import struct
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from array import array

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
from prepare_reference_image import BINARY_HASHES  # noqa: E402

CORPUS = ROOT / "fixtures/reference/release-libm"
COMPILER = "gcc:14.2.0@sha256:b99b86a28812b1e6453a231a947dc43d76fe192788a12f344a9b568bf9f5d24c"
BINARY = "/opt/reference/bin/cardano-node"
MUSL = "musl-1.2.5"
MUSL_URL = f"https://musl.libc.org/releases/{MUSL}.tar.gz"
MUSL_SHA256 = "a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4"
MUSL_FILES = ["log_data.c", "log_data.h", "pow_data.c", "pow_data.h", "exp_data.c", "exp_data.h"]
# Preview's circulation at the reward-likelihood corpus's snapshot (storage reward-likelihood-1.19.0.1).
PREVIEW_TOTAL = 37511237419704189
# The stake shares whose Float samples JVM Math rounded differently from the release binary (#1003).
FLIPS = [530407219, 100255168, 223921727, 58649595, 4478023683, 1158886040, 23328003492, 2461254950,
         468304424, 1328391258, 11766585580]


def bits(x):
    return struct.unpack("<Q", struct.pack("<d", x))[0]


def double(u):
    return struct.unpack("<d", struct.pack("<Q", u))[0]


def ratio(n, d):
    """The correctly rounded Double of n/d, as the reference converts a Rational (`fromRational`)."""
    return float(Fraction(n, d))


def log_cases(rng):
    xs = [(i + 0.5) / 100.0 for i in range(100)]
    off, lo, hi = 0x3fe6000000000000, 0x3fee000000000000, 0x3ff1090000000000
    # Every table interval at several exponents, at its start, middle and end.
    for k in (-1021, -1, 0, 1023):
        for i in range(128):
            for within in (0, 1 << 44, (1 << 45) - 1):
                u = (off + (k << 52) + (i << 45) + within) & (2**64 - 1)
                if 0 < u < 0x7ff0000000000000:
                    xs.append(double(u))
    # Subnormal arguments, normalised by musl before the table step.
    xs += [double(u) for u in (1, 3, 0x0008000000000000, 0x000fffffffffffff, 0x0000000100000000)]
    xs += [double(rng.getrandbits(52) or 1) for _ in range(20)]
    # The path near 1, its bounds and ulps around 1.
    xs += [double(u) for u in (lo - 1, lo, lo + 1, hi - 1, hi, hi + 1)]
    xs += [double(0x3ff0000000000000 + j) for j in range(-20, 21)]
    xs += [double(lo + rng.randrange(hi - lo)) for _ in range(200)]
    # Edge values: zeros, infinities, NaN, negatives, the extremes.
    xs += [double(u) for u in (0, 1 << 63, 0x7ff0000000000000, 0xfff0000000000000, 0x7ff8000000000000,
                               0xfff8000000000000, 0x7ff0000000000001, 0x8000000000000001,
                               0x0010000000000000, 0x7fefffffffffffff)]
    xs += [-1.0, -0.5, 2.0, 0.5, 0.25, 10.0]
    # log(area) of the query's normalisation: areas from 0.01 to 1, and 1 - t x arguments.
    xs += [rng.uniform(0.01, 1.0) for _ in range(100)]
    xs += [1.0 - rng.random() * 1e-6 for _ in range(100)]
    return [{"f": "log", "x": bits(x)} for x in xs]


def pow_cases(rng):
    pairs = []
    base = 1.0 - ratio(1, 20)
    # pow(1 - f, s) of leaderProbability: the flips, decades of stake, and log-uniform shares.
    for c in FLIPS:
        pairs.append((base, ratio(c, PREVIEW_TOTAL)))
    for k in range(17):
        pairs.append((base, ratio(10**k, PREVIEW_TOTAL)))
    pairs += [(base, ratio(int(PREVIEW_TOTAL * 10 ** rng.uniform(-17, 0)) or 1, PREVIEW_TOTAL))
              for _ in range(300)]
    for f in (Fraction(1, 10), Fraction(1, 2), Fraction(9, 10), Fraction(1, 10**6), Fraction(1, 1)):
        x = float(1 - f)
        pairs += [(x, ratio(int(PREVIEW_TOTAL * 10 ** rng.uniform(-12, 0)) or 1, PREVIEW_TOTAL))
                  for _ in range(20)]
        pairs += [(x, 0.0), (x, 1.0)]
    # Every table interval of log_inline, at k = 0 and k = -1.
    off = 0x3fe6955500000000
    for k in (0, -1):
        for i in range(128):
            x = double((off + (k << 52) + (i << 45) + (1 << 44)) & (2**64 - 1))
            pairs += [(x, 0.5), (x, -7.25)]
    # Results that overflow, underflow, are subnormal, or are negative (odd integer y).
    pairs += [(2.0, 1023.5), (2.0, 1024.0), (2.0, -1074.0), (2.0, -1075.0), (2.0, -1022.5), (2.0, -1073.7),
              (10.0, 308.3), (10.0, -310.0), (10.0, -323.5), (0.5, 1074.5), (0.5, 1075.0),
              (-2.0, 1023.0), (-2.0, 1025.0), (-2.0, -1075.0), (-2.0, -1073.0), (-0.5, 1075.0),
              (-0.5, 1073.0), (-3.0, 3.0), (-3.0, 2.0), (-3.0, 0.5), (1.5, 1750.0), (1.5, -1840.0),
              (0.999, 7e5), (0.999, -7e5), (1.0000001, 7.1e9), (1.0000001, -7.45e9)]
    # Tiny and huge y.
    pairs += [(2.0, 1e-20), (0.5, 1e-20), (2.0, -1e-20), (-2.0, 1e-20), (1.0, 1e-20),
              (1.0000001, 1e19), (0.9, 1e19), (1.1, -1e19), (0.9, -1e19), (-1.0, 1e19), (-1.0, 2.0**63),
              (2.0, double(0x3c00000000000000)), (2.0, double(0x43e0000000000000))]
    # Zero, infinite and NaN arguments.
    inf, nan = math.inf, math.nan
    for x in (0.0, -0.0, inf, -inf, nan, 1.0, -1.0, 0.5, 2.0, -0.5, -2.0):
        for y in (0.0, -0.0, inf, -inf, nan, 1.0, 3.0, -3.0, 2.0, -2.0, 0.5, -0.5):
            pairs.append((x, y))
    # Subnormal x.
    pairs += [(double(1), 0.5), (double(1), -0.01), (double(0x000fffffffffffff), 2.0),
              (-double(3), 3.0), (double(0x0000000100000000), 0.75)]
    pairs += [(double(rng.getrandbits(52) or 1), rng.uniform(-2, 2)) for _ in range(20)]
    # Arbitrary arguments.
    pairs += [(rng.uniform(0, 2), rng.uniform(-50, 50)) for _ in range(200)]
    pairs += [(-rng.uniform(0, 3), float(rng.randint(-1200, 1200))) for _ in range(50)]
    return [{"f": "pow", "x": bits(x), "y": bits(y)} for x, y in pairs]


def exp_cases(rng):
    xs = []
    # The query's normalisation: Floats at or below 0, widened to Double.
    f32 = lambda v: struct.unpack("<f", struct.pack("<f", v))[0]
    xs += [f32(-(10 ** rng.uniform(-8, 4))) for _ in range(200)]
    xs += [f32(v) for v in (-0.0, -0.5, -1.0, -87.5, -103.9, -708.4, -745.1, -746.0, -800.0, -1e10, -3.4e38)]
    # Every table index: (k + 1/4) ln2/128 and k ln2/128 for k in [-128, 128).
    ln2 = 0.6931471805599453
    for k in range(-128, 128):
        xs += [(k + 0.25) * ln2 / 128, (k + 0.5) * ln2 / 128]
    # Tiny |x| below 0x1p-54, and the bounds of the special case.
    xs += [0.0, -0.0, 1e-17, -1e-17, double(1), double(0x3c90000000000000), double(0x3c8fffffffffffff)]
    xs += [512.0, 600.0, 709.0, 709.782712893384, 709.7827128933841, 710.0, 1023.9, 1024.0, 1e300]
    xs += [-708.4, -708.5, -720.0, -740.0, -745.1332191019411, -745.1332191019412, -746.0, -1000.0,
           -1023.9, -1024.0, -1e300]
    xs += [math.inf, -math.inf, math.nan, double(0xfff8000000000000)]
    xs += [rng.uniform(-760, 720) for _ in range(200)]
    xs += [rng.uniform(-1, 1) for _ in range(50)]
    return [{"f": "exp", "x": bits(x)} for x in xs]


def likelihood_records(rng):
    rows = []

    def add(c, total, d, asc, n, slots):
        rows.append({"stake": [c, total], "s": bits(ratio(c, total)), "d": bits(float(d)),
                     "asc": bits(float(asc)), "n": n, "m": slots - n})

    for c in FLIPS:
        for slots in (86400, 432000):
            add(c, PREVIEW_TOTAL, Fraction(0), Fraction(1, 20), 0, slots)
    # Pools of every size producing about their share, the Shelley decentralisation steps, other f.
    for _ in range(30):
        c = int(PREVIEW_TOTAL * 10 ** rng.uniform(-9, -1))
        slots = rng.choice([86400, 432000])
        n = int(slots * 0.05 * c / PREVIEW_TOTAL * rng.uniform(0, 1.5))
        add(c, PREVIEW_TOTAL, Fraction(0), Fraction(1, 20), n, slots)
    for d in (Fraction(1, 2), Fraction(8, 10), Fraction(1)):
        add(10**15, PREVIEW_TOTAL, d, Fraction(1, 20), 3, 432000)
    for asc in (Fraction(1, 10), Fraction(1, 2), Fraction(1)):
        add(10**14, PREVIEW_TOTAL, Fraction(0), asc, 7, 86400)
    # No stake, all the stake, a pool making every block, and large n and m.
    add(0, PREVIEW_TOTAL, Fraction(0), Fraction(1, 20), 0, 432000)
    add(PREVIEW_TOTAL, PREVIEW_TOTAL, Fraction(0), Fraction(1, 20), 21600, 432000)
    add(10**16, PREVIEW_TOTAL, Fraction(0), Fraction(1, 20), 432000, 432000)
    add(10**12, PREVIEW_TOTAL, Fraction(0), Fraction(1, 20), 2**40, 2**52)
    add(1, PREVIEW_TOTAL, Fraction(0), Fraction(1, 20), 0, 2**52)
    return rows


def bulk_inputs(directory):
    """Arguments of every class: random bit patterns, the likelihood and query ranges, and tiny stake shares."""
    rng = random.Random(1003)
    logs = array("d")
    logs.extend(double(rng.getrandbits(64)) for _ in range(3_000_000))
    logs.extend(rng.random() for _ in range(1_000_000))
    logs.extend(1.0 - rng.random() * 0.0625 for _ in range(1_000_000))
    logs.extend(1.0 + rng.random() * 0.07 for _ in range(1_000_000))
    logs.extend(10 ** rng.uniform(-308, 308) for _ in range(1_000_000))
    for t in [1.0 - 0.95 ** (int(PREVIEW_TOTAL * 10 ** rng.uniform(-10, 0)) / PREVIEW_TOTAL) for _ in range(20_000)]:
        logs.extend(1.0 - t * ((i + 0.5) / 100.0) for i in range(100))
    pows = array("d")
    for _ in range(4_000_000):
        pows.extend((1.0 - 1.0 / 20.0, int(PREVIEW_TOTAL * 10 ** rng.uniform(-9, -3)) / PREVIEW_TOTAL))
    for _ in range(1_000_000):
        f = rng.choice([0.05, 0.1, 0.5, 0.9, 1e-6, 0.999999])
        pows.extend((1.0 - f, int(PREVIEW_TOTAL * 10 ** rng.uniform(-17, 0)) / PREVIEW_TOTAL))
    for _ in range(2_000_000):
        pows.extend((double(rng.getrandbits(64)), double(rng.getrandbits(64))))
    for _ in range(1_000_000):
        pows.extend((rng.uniform(0, 2), rng.uniform(-1100, 1100)))
    for _ in range(500_000):
        pows.extend((-rng.uniform(0, 3), float(rng.randint(-1200, 1200))))
    for _ in range(500_000):
        pows.extend((double(rng.getrandbits(52)), rng.uniform(-2, 2)))
    exps = array("d")
    exps.extend(double(rng.getrandbits(64)) for _ in range(3_000_000))
    exps.extend(rng.uniform(-760, 720) for _ in range(2_000_000))
    exps.extend(rng.uniform(-1e-3, 1e-3) for _ in range(1_000_000))
    exps.extend(struct.unpack("<f", struct.pack("<f", -rng.expovariate(0.01)))[0] for _ in range(2_000_000))
    for name, values in (("log", logs), ("pow", pows), ("exp", exps)):
        (directory / f"{name}.in").write_bytes(values.tobytes())


def run(command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode:
        raise SystemExit(f"{' '.join(command)}\n{result.stderr}")
    return result.stdout


def reference(image, probe_dir, work):
    """Run the probe in `image` on the case and likelihood inputs; return its outputs and tables."""
    digest = run(["docker", "run", "--rm", "--network", "none", "--entrypoint", "sha256sum", image, BINARY])
    mount = ["docker", "run", "--rm", "--network", "none", "--cpus", "1", "--user", "%d:%d" % uid(),
             "-v", f"{probe_dir}:/probe:ro", "-v", f"{work}:/work", image, "/probe/probe", BINARY]
    tag = image.replace(":", "_").replace("/", "_").replace("@", "_")
    run(mount + ["cases", "/work/cases.in", f"/work/cases.{tag}"])
    run(mount + ["lik", "/work/lik.in", f"/work/lik.{tag}"])
    run(mount + ["tables", f"/work/tables.{tag}"])
    read = lambda name: (work / f"{name}.{tag}").read_text()
    return digest.split()[0], read("cases"), read("lik"), read("tables")


def uid():
    return os.getuid(), os.getgid()


def musl_tables(probe_dir):
    """musl's own tables, compiled from the signature-checked release named by MUSL_SHA256."""
    raw = urllib.request.urlopen(MUSL_URL, timeout=120).read()
    if hashlib.sha256(raw).hexdigest() != MUSL_SHA256:
        raise SystemExit(f"{MUSL_URL}: unexpected digest")
    with tempfile.TemporaryDirectory(prefix="scarab-musl-") as directory, \
            tarfile.open(fileobj=io.BytesIO(raw)) as archive:
        math_dir = Path(directory) / "math"
        math_dir.mkdir()
        for name in MUSL_FILES:
            member = archive.extractfile(f"{MUSL}/src/math/{name}")
            (math_dir / name).write_bytes(member.read())
        return run(["docker", "run", "--rm", "--network", "none", "--cpus", "1", "--user", "%d:%d" % uid(),
                    "-v", f"{HERE}:/src:ro", "-v", f"{math_dir}:/musl:ro", "-v", f"{probe_dir}:/out",
                    COMPILER, "sh", "-c",
                    "gcc -O2 -Wall -I /musl /src/musl_tables.c -o /out/musl_tables && /out/musl_tables"])


def build_probe(work):
    """The probe, a static PIE without contraction, built in the pinned compiler image."""
    probe_dir = work / "probe"
    probe_dir.mkdir()
    run(["docker", "run", "--rm", "--network", "none", "--cpus", "2", "--user", "%d:%d" % uid(),
         "-v", f"{HERE}:/src:ro", "-v", f"{probe_dir}:/out", COMPILER,
         "gcc", "-O2", "-Wall", "-Wextra", "-fPIE", "-static-pie", "-ffp-contract=off",
         "/src/probe.c", "-o", "/out/probe"])
    return probe_dir


def corpus_rows(cases_out, lik_out):
    rows = []
    for line in cases_out.splitlines():
        fields = line.split()
        row = {"f": fields[0], "x": fields[1]}
        if fields[0] == "pow":
            row["y"] = fields[2]
        row["r"] = fields[-1]
        rows.append(row)
    for line in lik_out.splitlines():
        f = line.split()
        rows.append({"f": "lik", "stake": [int(f[6]), int(f[7])], "s": f[1], "d": f[2], "asc": f[3],
                     "n": int(f[4]), "m": int(f[5]), "samples": f[8:]})
    return "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--image", default="cardano-reference-11.1.3:local")
    parser.add_argument("--other-image", default="cardano-reference-11.2.1:local",
                        help="a second release that must agree; empty to skip")
    parser.add_argument("--skip-musl", action="store_true", help="do not compare the tables with musl's source")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--bulk", type=Path, help="write bulk arguments and the binary's results to this directory")
    args = parser.parse_args()
    if args.bulk:
        args.bulk.mkdir(parents=True, exist_ok=True)
        bulk_inputs(args.bulk)
        with tempfile.TemporaryDirectory(prefix="scarab-release-libm-") as directory:
            probe_dir = build_probe(Path(directory))
            for name in ("log", "exp", "pow"):
                run(["docker", "run", "--rm", "--network", "none", "--cpus", "1", "--user", "%d:%d" % uid(),
                     "-v", f"{probe_dir}:/probe:ro", "-v", f"{args.bulk.resolve()}:/bulk", args.image,
                     "/probe/probe", BINARY, "raw", name, f"/bulk/{name}.in", f"/bulk/{name}.ref"])
        print(f"wrote the bulk arguments and the binary's results to {args.bulk}")
        return
    rng = random.Random(1003)
    cases = log_cases(rng) + pow_cases(rng) + exp_cases(rng)
    liks = likelihood_records(rng)
    with tempfile.TemporaryDirectory(prefix="scarab-release-libm-") as directory:
        work = Path(directory)
        probe_dir = build_probe(work)
        (work / "cases.in").write_text("".join(
            f"{c['f']} {c['x']:016x}" + (f" {c['y']:016x}" if "y" in c else "") + "\n" for c in cases))
        (work / "lik.in").write_text("".join(
            f"lik {r['s']:016x} {r['d']:016x} {r['asc']:016x} {r['n']} {r['m']} {r['stake'][0]} {r['stake'][1]}\n"
            for r in liks))
        digest, cases_out, lik_out, tables = reference(args.image, probe_dir, work)
        if digest != BINARY_HASHES["cardano-node"]:
            raise SystemExit(f"{args.image}: {BINARY} is not the pinned 11.1.3 release binary")
        if args.other_image:
            _, other_cases, other_lik, other_tables = reference(args.other_image, probe_dir, work)
            if (other_cases, other_lik, other_tables) != (cases_out, lik_out, tables):
                raise SystemExit(f"{args.other_image} differs from {args.image}")
            print(f"{args.other_image} gives identical results and tables")
        if not args.skip_musl:
            if musl_tables(probe_dir) != tables:
                raise SystemExit(f"the binary's tables differ from {MUSL}'s")
            print(f"the binary's tables equal {MUSL}'s, compiled without FMA")
        print(f"{BINARY} sha256 {digest}")
    rows = corpus_rows(cases_out, lik_out)
    if args.write:
        CORPUS.mkdir(parents=True, exist_ok=True)
        (CORPUS / "cases.jsonl").write_text(rows)
        (CORPUS / "tables.txt").write_text(tables)
        print(f"wrote {len(cases)} function cases and {len(liks)} likelihood records")
        return
    if (CORPUS / "cases.jsonl").read_text() != rows or (CORPUS / "tables.txt").read_text() != tables:
        raise SystemExit("the release binary's results differ from the committed corpus")
    print(f"all {len(cases)} function cases, {len(liks)} likelihood records and the tables match")


if __name__ == "__main__":
    main()
