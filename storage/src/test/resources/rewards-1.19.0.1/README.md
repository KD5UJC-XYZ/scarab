# Pinned Haskell reward-pulser results

Generated 2026-10-19 by the unchanged released `cardano-ledger-shelley 1.19.0.1` APIs,
source revision `4c81e909df7555b6a58a000db8f8f4f89c7acdc4`, with core 1.21.0.0 and binary
1.9.1.0 in the frozen node-11.1.3 oracle environment. The wrapper and reproduction commands
are [scripts/oracle/rewards](../../../../../scripts/oracle/rewards/README.md).

`inputs.json.gz` and `results.json.gz` use deterministic gzip (`mtime = 0`). The decompressed
SHA-256 values are:

- inputs: `e150785cf68a3aa2d480a87451ee00088aaa3e46b17e08888a43a473e3fa998d`
- results: `4b03159526f2aaf3edde8fb3f70c1338cd59467d7759976838ecaa0910402c4d`

Fifteen deterministic synthetic cases cover Shelley v2/v3 reward aggregation and prefiltering,
Babbage v8, Conway v10, empty stake, positive member rewards, an unregistered member, a script
credential, an owner, shared leader accounts, imported mismatched pool identifiers, unbounded
pool block counts, a full positive Haskell `Int` pulse size and non-myopic completion. Every case
records the initial state and eleven operations, including the empty-source completion call and
completion of an already completed update. `RewardOracleSuite` compares encoded states and
normal pulse events; invalid codec writes fail the comparison.

Only newly generated likelihood vectors are masked for locally started states, because their
calculation remains the existing `Gap.NonMyopic` / J3.6 work. The reference-derived snapshot's
other fields, including captured registration/pool inputs and final pots, compare. Imported cases
are compared without this mask, including empty-new/nonempty-old likelihood maps, a 101-entry
new vector with the reference's 100-zero identity, and a pool count above 2^64.

These are generated API inputs and outputs, not transactions, signatures, chain admission or a
security proof. No keys or chain data are included. The fixture producer and wrapper are original
Scarab Apache-2.0 code. Upstream package notices and licenses remain in fetched distributions
and [fixtures/licenses](../../../../../fixtures/licenses).
