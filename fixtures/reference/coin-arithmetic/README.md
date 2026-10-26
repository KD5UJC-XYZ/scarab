# Signed Coin and compact account/stake arithmetic

Eleven cases measured on 2026-10-26 through unchanged native Haskell APIs for
[#492](https://github.com/KD5UJC-XYZ/scarab/issues/492). Each JSON line retains its decimal
inputs and native Coin sum/encoding/compaction. Where both inputs compact, it also records
Word64 addition/subtraction, account balance/deposit sums, account credit, credential stake
resolution and total active stake. Null compaction results are measured refusals, not skipped cases.

[Provenance](provenance.json) records the immutable image, GHC/package units, harness/corpus
hashes and six installed source files verified byte-for-byte against cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`. The installed binary package version is explicit;
this does not assert equivalence of the entire dependency graph with the pinned checkout.

Replay from the repository root with:

```sh
python3 scripts/oracle/coin-arithmetic/run.py --image sha256:0d7d3971a5ff78c8a6786459d191f39b166d69a4d71a6eb310c9d8338d6e035e
```

The [harness](../../../scripts/oracle/coin-arithmetic/Main.hs) calls the native implementations;
the [replay](../../../scripts/oracle/coin-arithmetic/run.py) compares every captured result.
`CoinArithmeticReferenceSuite` checks ledger arithmetic and `HaskellStateCodecSuite` checks
exact Coin bytes and bounded decoding. Scope is these native APIs, not whole-ledger transitions
or accumulated MIR failure/post-state behavior. Additional rule tests are source-derived and
listed in the [compatibility page](../../../docs/compatibility/ledger-coin-arithmetic.md).
