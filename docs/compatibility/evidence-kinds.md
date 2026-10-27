<!-- SPDX-License-Identifier: Apache-2.0 -->
# Evidence kinds

Part of the [compatibility matrix](../compatibility.md#evidence-kinds).

- **Unit/property**: munit, ScalaCheck and cats-laws suites under `*/src/test`.
- **Golden/oracle**: fixtures under `fixtures/` (see their READMEs) and a Scalus oracle for script contexts.
- **Devnet**: `scripts/devnet.py` scenarios against a Haskell cluster, see [running.md](../running.md).
- **Preview sync**: following the public Preview testnet from genesis (every block checked against
  our rules; the chain is the oracle, so it shows we do not reject valid blocks, not that our ledger
  state is identical; the next kind does).
- **Ledger oracle** (K1): at the first block of every epoch the Scala ledger state is compared field
  by field (large maps by digest) with the state the Haskell `db-analyser` stores after the same block,
  on a devnet cluster and on Preview. See [running.md](../running.md) and the evidence log.

Status values: **implemented**, **partial**, **not implemented**.

The [comparison and coverage register](../data/test-coverage-register.json) records bounded claims and
remaining gaps with owners and resolution criteria. The [fixture contracts](../../fixtures/README.md)
describe exact committed identities and mandatory corpus documentation. These inventories supplement
the executable assertions; they do not waive failed comparisons.

External candidate acceptance ([#294](https://github.com/KD5UJC-XYZ/scarab/issues/294)) passed
**12/12 mandatory cases and 66/66 additional controls, with zero skips or failures**, on
2026-10-21 UTC. The [exact-source/runtime receipt](../data/external-acceptance-294.json) binds original
inputs, report hashes, actual heap/CPU settings and completed native Preview comparison. The
[acceptance contract](../../scripts/candidate_acceptance/README.md) records setup, corrected harness
failures and limits. This closes the external-data gate, not every audit or equivalence obligation.
Its 66 additional controls are test cases of that campaign. They are not the 66 devnet ledger
comparisons the evidence log reports for `e2e`.

Devnet ledger comparison counts in this file are projected comparisons. That covers the
`e2e` counts such as "66/66" and the per-era Shelley 12 … Conway 30, plus the `pool` and `soak` counts.
They compare the reference ledger projected onto the Scala node's `/state` shape at one block.
They do not show whole-state equality. Cost models, Conway protocol parameters other than the version,
`ParameterChange` values, reward-account networks, the key-or-script kind of pool reward
credentials, pool relays, metadata and delegators, account pointers, DRep anchors and delegators,
`nonMyopic`, individual reward entries and AVVM/Byron state are among the fields not compared. `scripts/devnet/compare.py` (`PROJECTION_SCOPE`)
lists the scope, each `e2e-summary.json` records it, and the register entry `devnet-ledger-projection`
tracks it under [#433](https://github.com/KD5UJC-XYZ/scarab/issues/433). Runs before 2026-10-25 also
left datums and reference-script hashes out of the Conway UTxO comparison, and hashed reference scripts
over a re-encoding rather than their original bytes. In the `ntc` refusal
comparison, every case is compared exactly (raw bytes and text), `withdrawal-network-flag-true` included:
the reference lists two failures and the Scala rules record both
([#412](https://github.com/KD5UJC-XYZ/scarab/issues/412)); runs before the rules accumulated compared
that case by its first failure. Runs before 2026-10-25 also masked the current slot in the two
validity-interval refusals.