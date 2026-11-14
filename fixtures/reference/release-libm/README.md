# The release node's own log, pow and exp

Results of the cardano-node 11.1.3 release binary's own `log`, `pow` and `exp`, measured on 2026-11-14 for
[#1003](https://github.com/KD5UJC-XYZ/scarab/issues/1003). The release binaries are static executables linked
against musl, and GHC 9.6.7 compiles `log`, `exp` and `(**)` on `Double` to calls of them, so the pool-ranking
likelihoods (`PoolRank.hs:126-172`, cardano-ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5`) and the
performance estimates of the wallet queries are computed with musl's code. `MuslMath`
(`ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules`) ports that code, and `MuslMathSuite` compares it with
this corpus.

## Files

- `cases.jsonl`, one JSON object per line:
  - 4,524 function cases, `{"f": "log"|"exp"|"pow", "x": X, "y": Y, "r": R}` (`y` for `pow` only), every value
    an IEEE-754 binary64 bit pattern in hex. `R` is what the binary's function returned for `X` (and `Y`). The
    arguments are the 100 sample positions; every table interval of each function at several exponents, at its
    start, middle and end; subnormals, zeros, infinities, NaNs, negatives, 1 and the ulps around it, the bounds
    of `log`'s path near 1; `pow(1 - f, s)` for stake shares from 1 lovelace to the whole circulation and for
    several `f`; results that overflow, underflow, are subnormal or negative; tiny and huge exponents; and the
    `exp` and `log` arguments of the wallet queries' normalisation (Floats at or below 0, areas in [0.01, 1]).
  - 63 likelihood records, `{"f": "lik", "stake": [C, T], "s": S, "d": D, "asc": F, "n": N, "m": M,
    "samples": [...]}`: the 100 `Float` samples, as bit patterns, of
    `likelihood N (leaderProbability F (C/T) D) (N + M)` computed on the binary's `pow` and `log`. `S`, `D` and
    `F` are the Doubles the reference converts `C/T`, `d` and `f` to. Among them are the 11 stake shares of
    Preview's circulation (37,511,237,419,704,189 lovelace) whose samples JVM `Math` rounded differently, at
    86,400 and 432,000 slots, and records with blocks made, `d` of 1/2, 0.8 and 1, `f` of 1/10, 1/2 and 1, no
    stake, all the stake, every slot made, and `M` up to 2^52.
- `tables.txt`: the bytes of the binary's `__log_data`, `__pow_log_data` and `__exp_data`, one line each (name,
  size, hex).
- `provenance.json`: the images, binary digests, compiler, musl release and the bulk comparison.

## How it was measured

[`probe.c`](../../../scripts/oracle/release-libm/probe.c) maps the binary's `PT_LOAD` segments at their own
addresses, finds `log`, `pow`, `exp` and the tables by name in its symbol table, and calls the binary's machine
code; it computes the likelihood formula itself with basic IEEE operations, built without contraction. It is not
the node's Haskell code path. [`run.py`](../../../scripts/oracle/release-libm/run.py) generates the arguments with
exact rationals and a fixed seed, builds the probe in `gcc:14.2.0` (pinned by digest), and runs it with no
network inside `cardano-reference-11.1.3:local`, whose `/opt/reference/bin/cardano-node` must have the SHA-256
`scripts/prepare_reference_image.py` pins (`ee396604...a336c3`). It runs the same probe inside the 11.2.1
reference image too, whose results and tables are identical. Replay from the repository root, or regenerate
with `--write`:

```sh
python3 scripts/oracle/release-libm/run.py
```

## Which musl, and which path

- The tables are musl's: `run.py` downloads the musl 1.2.5 release (SHA-256 in `provenance.json`; its signature
  checked against the musl release key when it was pinned), compiles its `log_data.c`, `pow_data.c` and
  `exp_data.c` with [`musl_tables.c`](../../../scripts/oracle/release-libm/musl_tables.c), and requires the bytes
  to equal the binary's. The code of these functions and tables is byte-identical in musl 1.1.24, 1.2.3, 1.2.4
  and 1.2.5, so the version cannot be told from the code; 1.2.5 is pinned.
- The path is the one without FMA. `__log_data` is 4,240 bytes, so it holds `tab2`, which musl compiles only
  when `__FP_FAST_FMA` is not set (`log_data.h:21-25`); the binary's `log` subtracts `tab2[i].chi` and
  `tab2[i].clo` and multiplies by `tab[i].invc` (`log.c:94`); its `pow` splits `z` with `+ 0x80000000` and
  `& 0xffffffff00000000` (`pow.c:64`); and the three functions contain no FMA or AVX instruction.

## Bulk comparison

`run.py --bulk DIR` writes 26,000,000 arguments (9,000,000 for `log` and `pow`, 8,000,000 for `exp`: random
bit patterns of every class, the likelihood and query ranges, 4,000,000 tiny stake shares) and the binary's
results. `MuslMathMain raw` (in `tools`) gave the same bytes for all of them, NaNs included. JVM `Math` differed
on 3,198 `log`, 9,019 `exp` and 1,855 `pow` results. The arguments and results are regenerated, not published.

## Limits

The likelihood records come from the binary's libm under a C copy of the formula, not from the node's own
Haskell code. The node's own likelihoods are compared in `WalletPeerReferenceSuite`, which recomputes the
completed reward updates of two native 11.1.3 states (`fixtures/reference/ntc-wallet-peers-11.1.3`) bit for bit,
but there every pool holds about a third of the stake; no capture holds the node's own likelihoods for a pool
with a tiny stake share, where the libm matters.

Generated data; Apache-2.0. The tables are musl's (MIT, `ledger/licenses/LICENSE-musl`).
