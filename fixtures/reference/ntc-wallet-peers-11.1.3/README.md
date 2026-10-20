# Native wallet and peer-query answers

These are original query replies from `cardano-node` 11.1.3, revision
`938cba990357ae7c4b7f95c8f75dd9d31174bbeb`, on isolated, synthetic test networks.
There are no signing keys. Public genesis files, acquired points and native ledger states are
retained so the inputs to the pure query functions can be reconstructed independently of Scarab's
ledger execution.

Each case has the original native `DebugNewEpochState` result, the exact genesis/configuration
whitelist from that cluster, and 23 original request/reply pairs across node-to-client versions
19, 22 and 23. The debug-state bytes were identical across those versions; one copy is retained.
The manifest hashes the inputs and query file. `WalletPeerReferenceSuite` decodes the original
requests and compares complete `MsgResult` bytes, including hard-fork and `GetCBOR` wrappers.
Expected replies are never regenerated from the Scala implementation.

- `peer-relays`: fresh native genesis with only the three generated pools' relay lists changed
  before startup (`input-changes.json`). Three equal-stake pools exercise stable order, IPv4
  preference when both address families are registered, IPv6, DNS bytes and SRV. Version 19
  omits SRV relays and empty pools **after** big-stake accumulation; version 22 retains them.
  The reward history is neutral, with native performance estimate `0.885` and zero reward pot.
- `reward-history`: an actual native Byron-to-Conway era walk with credited rewards and stored
  likelihood history. At the captured Conway point the reward pot is `8339698728223`; all three
  performance estimates are `0.985`, and the member query includes nonzero reward results.
  This is an observation on that point, not a guarantee for incomplete historical imports.
- `reward-later`: a later point from the same actual era walk. It has four pools, a reward pot of
  `7480859708335`, and performance estimates `0.995`, `0.995`, `0.975`, `0.885`. The newly
  registered pool with absent history exercises the reference's neutral estimate and exclusion
  from the history-backed top-pool set.

The implementation correspondence is pinned to cardano-ledger
`4c81e909df7555b6a58a000db8f8f4f89c7acdc4` (`Shelley/API/Wallet.hs` and `PoolRank.hs`),
ouroboros-consensus `82ecba329d7d054340bf707d44fe6e9ac27cec40`
(`Shelley/Ledger/Query.hs`), and ouroboros-network `ouroboros-network-1.2.0.0`
(`LedgerPeers/Type.hs`, `Utils.hs`, and `RelayAccessPoint.hs`). The actual runtime image is
`sha256:63bb366ad306366653fd9dfb23e16cceadd1ab68de6556c1e761358a5364bb1e`.

[The capture tools](../../../scripts/oracle/ntc-queries/) retain raw requests/replies at one
acquired point. The peer producer changes only its own newly generated input before starting
the reference cluster; it never changes a running cluster. The first peer attempt stopped before
its socket existed; the completed capture used bounded startup polling. The completed capture's
final version command used an unsupported spelling, after all query files had been written;
version metadata was recovered with a separate read-only invocation of the same image. Neither
failure produced a passing query verdict or caused a returned answer to be discarded.

These fixtures prove the bounded Conway query cases represented here. They do not establish
all-era RPC support, arbitrary-state numerical equivalence, or completeness of old likelihood
histories. The latter remains roadmap J3.1 / issue #129. Scarab bootstrap currently drops
injected genesis pool relays and metadata ([#283](https://github.com/KD5UJC-XYZ/scarab/issues/283));
decoding these native states directly does not establish parity for that genesis conversion path.

Generated test data; Apache-2.0.
