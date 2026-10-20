# Native wallet and peer snapshot query answers

These vectors are original observations from the supported Haskell reference, not Scala-generated
expected answers. `inputs.json` contains four explicit Conway new-epoch states and their requests;
`answers.json` records `getRewardInfoPools` and `getNonMyopicMemberRewards` from
`cardano-ledger-shelley` **1.19.0.1** (the ledger revision used by cardano-node **11.1.3**).

The states have four pools, current delegated UTxO/reward stake, self-delegated owners, different
performance histories, a pool failing its pledge, and empty historical mark/set/go snapshots. Requests
mix coins, a known credential, an unknown key credential and an unknown script credential. Variants
exercise missing history, zero reward pot and stable pool-id ordering at the desirability cutoff.
The complete output maps and binary64 performance estimates are compared without rounding or masks.
Each state also observes an explicitly constructed native `Coin` reward pot of 2^80, exercising the
unbounded Integer encoder (the native Coin decoder separately accepts only Word64).

`peer-snapshots.hex` contains eleven observations from the unchanged released
`ouroboros-network` **1.2.0.0**, commit **c45735a56c567fa977969173d18943bac6bb3821**:

1. Legacy V2, pre-V22 result encoding (SRV relays filtered after quota calculation).
2. Legacy V2 with SRV support.
3. Modern big-ledger snapshot.
4. Modern all-ledger snapshot.
5. Empty legacy snapshot at origin.
6. Empty modern big-ledger snapshot at origin.
7. Empty modern all-ledger snapshot at origin.
8. IPv4 and IPv6 relays, modern all-ledger snapshot.
9. IPv4 and IPv6 relays, modern big-ledger snapshot.
10. Quota crossing: all three raw pools.
11. Quota crossing: only the two pools crossing 90%.

The first distribution reaches 90% exactly, so the next pool is retained. The second crosses 90%
strictly, so the final pool is excluded only from the big-ledger result. Native
`accumulateBigLedgerStake` computes the accumulations. All fractions, indefinite containers, nested
legacy tuples, points, hashes, domain bytes, IP groups and ports retain the native encoding.

The observers are in [scripts/oracle/wallet-queries](../../../scripts/oracle/wallet-queries/README.md).
`manifest.json` records source hashes, observer hashes, fixture hashes and the native build images.
Ledger upstream is Apache-2.0; ouroboros-network upstream is Apache-2.0. No private network state or
signing material is included.
