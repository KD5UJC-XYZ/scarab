<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: signed Coin and compact arithmetic (#492)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-coin-arithmetic` in [compatibility.json](../data/compatibility.json).

## Status as recorded

`Coin` represents a signed, arbitrary-precision integer, as cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5` defines it in `Coin.hs:93–113`.
Construction and native pot arithmetic do not add sign checks. Existing Coin CBOR readers
still require Word64; compact conversion still checks the Word64 range. Writers and persisted
format versions are unchanged. Withdrawals carry Coin amounts at the decoded/native API
boundary, retaining the unique-key map, explicit consumer ordering and original body bytes.
The amount a MIR certificate pays a stake credential is a `DeltaCoin`, an `Integer` read by
`decodeInteger` (`Shelley/TxCert.hs:341-353`, `Coin.hs:115-119`): an integer item of any head width
or a bignum (tags 2 and 3 with the one-byte heads `0xc2`/`0xc3` over a definite byte string), read
by `CborRead.integer` (#707). `MirAmountsReferenceSuite` holds each form in a body of each era from
Shelley to Babbage against the reference's verdict ([fixtures](../../fixtures/reference/mir-amounts/README.md));
before #707 the bignum forms were refused.

Compact account additions and subtotals are checked: where the reference's `Word64` arithmetic
wraps modulo 2^64, the node's sum is a `VocabError` and the epoch rules give no verdict
([overflow improvements](overflow-improvements.md#compact-coin-arithmetic)); below 2^64 every
result is the reference's. MIR combines Coin amounts before compaction; reward delivery compacts
each reward before grouping. POOLREAP combines compact refund subtotals before subtracting from
deposited Coin. Account, pool and DRep obligation subtotals are summed separately before their
Coin sum. The reference boundaries are `State/Account.hs:119–147`, `Shelley/Rules/Mir.hs:122–135`,
`Shelley/Rules/PoolReap.hs:179–221`, `Shelley/Rewards.hs:205–210` and
`Conway/Rules/Epoch.hs:181–240`.

Stake resolution keeps the reference's unchecked NonZero values, which a checked sum of amounts of
which one is not zero never makes zero (`State/Stake.hs:220–245`, `Shelley/State/Stake.hs:148–172`,
`BaseTypes/NonZero.hs:140–141`); where the reference's sum wraps, even to zero, the node has no
verdict. Pool/owner and DRep subtotals are checked the same way; total active stake remains an
unbounded Coin, with zero replaced by one (`State/Stake.hs:157–166`,
`State/SnapShots.hs:187–226`, `Conway/Governance/DRepPulser.hs:209–232`). A proposal deposit that
does not compact, which the reference makes zero before credential grouping
(`Conway/Governance/Proposals.hs:566–580`), is no verdict too; a deposit equals the parameters'
compact `govActionDeposit`, so none is reached.

## Evidence

The [eleven native API cases](../../fixtures/reference/coin-arithmetic/cases.jsonl) and
[provenance](../../fixtures/reference/coin-arithmetic/provenance.json) record actual signed Coin
encoding/compaction, compact arithmetic, account sums/credit and credential stake resolution. A
case where the reference wraps is told by the exact sum: its result is the exact one modulo 2^64,
and the node's is no verdict.
Six installed reference source files were compared byte-for-byte with the pinned Git objects.
`CoinArithmeticReferenceSuite` compares native results, and `HaskellStateCodecSuite` compares
exact Coin bytes while retaining bounded decoder refusal. The portable
[oracle replay](../../scripts/oracle/coin-arithmetic/run.py) compares every captured case.

`CompactCoinRulesSuite`, `CompactStakeSuite` and `TickRulesSuite` additionally cover source-derived
rule boundaries, both account families, sparse/dense credit, pointer stake, pool owners,
proposal deposits and DRep sums. The balanced reward-update test preserves treasury -1.
Each boundary is held below 2^64, where the result is the reference's, and at it, where it is no
verdict; clamping signed Coin fails three assertions. These are scoped arithmetic checks, not
full-ledger equivalence.

## Explicitly unchecked or unconfirmed

The accumulated MIR negative-update state regression is in the
[reference-refusal corpus](../../fixtures/reference/refusals/README.md) (`babbage-certificate-order`,
#411). Compact-conversion defect classification is separately tracked by
[#483](https://github.com/KD5UJC-XYZ/scarab/pull/483). The remaining reward-account payload
decoding alignment belongs to [#477](https://github.com/KD5UJC-XYZ/scarab/issues/477).
The native genesis initial-funds regression for signed reserves is part of
[#502](https://github.com/KD5UJC-XYZ/scarab/pull/502).
