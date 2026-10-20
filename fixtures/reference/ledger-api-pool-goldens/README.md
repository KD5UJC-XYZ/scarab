# Published Conway pool-query vectors

Original files from cardano-ledger API goldens are retained byte-for-byte at released source
`f649f9751074d2ab3de033fc3912f29c9862c1f5` (`pin/`) and observed source
`7fa3963cce708eae06f2443820de7adfa5dd3ecf` (`head/`). Source paths are
[`libs/cardano-ledger-api/golden/conway`](https://github.com/IntersectMBO/cardano-ledger/tree/7fa3963cce708eae06f2443820de7adfa5dd3ecf/libs/cardano-ledger-api/golden/conway).
These public upstream examples are Apache-2.0; their original JSON describes the pool inputs.
`manifest.json` binds every retained file by SHA-256.

`UpstreamPoolGoldenSuite` constructs the empty and three-pool examples independently in native
`PState`, using the original JSON field values, role-indexed hashes and exact rational margins.
Relay input bytes come from production `PoolRelays.encode` over independently constructed typed
IPv4/IPv6/DNS addresses, not from decoding or copying the expected CBOR. Production
`QueryAnswers.GetStakePoolParams` supplies both complete result values. The test strips only the
node-to-client `IfCurrent` envelope and frames the two answers in the upstream example vector;
the expected file is unchanged and comparison covers every raw CBOR byte.

The original published IPv4 bytes at `pin/` were reversed relative to network order, while the
released node's actual encoding and Scarab already use network order. This is an upstream golden
correction, not a new production rule. The negative control reverses only the independently built
IPv4 address words and reproduces the **complete old published vector**. IPv6 retains the pinned
Haskell convention of four little-endian 32-bit words; it is independently built as
`2001:db8::1`, without treating its registration bytes as network-order IPv6.

The other two original CBOR vectors are retained with bounded applicability:

- `queryStakePoolRelays`: production `PoolRelays.decode` checks the original address fields,
  yielding `10.0.0.5` and `192.168.1.1` at `head/`; old octets instead yield `5.0.0.10` and
  `1.1.168.192`. Scarab does not expose that direct query. This is a relay-codec observation,
  not a claim that production answers an unsupported query.
- `queryPoolState`: the published nonempty result independently supplies current pool keys
  `{0d6a…, b779…}` but deposit keys `{b779…, e0a7…}`. Native `StakePoolState` stores each deposit
  with its registered pool. A whole-answer match would require inventing inconsistent state or
  changing the published expected result. No such normalization or complete query replay is
  claimed. Existing `PoolNetworkQuerySuite` continues testing applicable registered/future
  reward-account behavior.

No production code, persisted format, native snapshot fixture or operational database changes.
The ordinary node suite replays these public vectors without network or Haskell execution.
