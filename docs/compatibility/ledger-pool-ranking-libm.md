<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: pool-ranking floating point on the reference's libm (#1003)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-pool-ranking-libm` in [compatibility.json](../data/compatibility.json).

## Status as recorded

implemented: the pool-ranking likelihoods (`Rewards.likelihood`) and the performance estimates of the wallet queries (`RewardQueries`) call `MuslMath`, a port of musl 1.2.5's `log`, `pow` and `exp` on the path without FMA, which the statically linked cardano-node 11.1.3 release binary contains. Before it, JVM `Math` changed some stored `Float` samples for pools with a tiny stake share.

## Evidence

- `MuslMathSuite` against [the release binary's own functions](../../fixtures/reference/release-libm/README.md), measured by calling its machine code:
  - every one of 4,543 committed cases gives the binary's bits; the cases reach every table interval, each special path of the three functions and every return of `pow`'s `checkint`;
  - the tables equal the binary's word for word;
  - all 63 likelihood records give the binary's `Float` samples. Among them are the 11 stake shares of Preview's circulation where JVM `Math` changed 4 to 50 of the 100 samples, which now all match.
- A bulk comparison gave identical bytes on 26,000,000 arguments (`run.py --bulk`, `MuslMathMain raw`); JVM `Math` differed on 3,198 `log`, 9,019 `exp` and 1,855 `pow` results.
- `WalletPeerReferenceSuite` recomputes the completed reward updates of two native 11.1.3 states, likelihoods included, bit for bit.
- `RewardLikelihoodSuite` matches all 72,700 samples of the Preview reward corpus.
- The tables equal musl 1.2.5's source. The code of these functions is identical in the four musl releases compared, so the fixture pins 1.2.5.

## Explicitly unchecked or unconfirmed

- The likelihood records come from the binary's libm under a C copy of the formula, not from the node's own Haskell code.
- The node's own likelihoods are compared only for pools holding about a third of the stake. A capture of them for a tiny-stake pool is the follow-up in #1003.
- A NaN result is compared as a NaN; its payload is not promised, and no caller produces one.
- The 11.2.1 release binary gives identical results and tables. The cross-check is the fixture's.
