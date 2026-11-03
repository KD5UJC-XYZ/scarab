# Node-to-client query arguments

Evidence for [#709](https://github.com/KD5UJC-XYZ/scarab/issues/709), measured on 2026-11-03 against
the unchanged released decoders used by node 11.1.3. `cases.jsonl` holds one case per line: the
bytes of one argument of a ledger query, the decoder the reference's query decoder reads it with,
the verdict and, for an accepted one, the values read (sorted text: key hashes and addresses as
hex, credentials as `key:` or `script:` and the hash, an `Either` as `left:` or `right:`, inputs as
`txid#index`, DReps and member statuses by name). The [oracle source and recipe](../../../scripts/oracle/ntc-query-arguments/cases.py)
are committed; `provenance.json` records the package versions and source hashes.

`decodeShelleyQuery` (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Query.hs:916-995`,
ouroboros-consensus `82ecba32`) reads each argument with one of two decoders:

- `fromCBOR` of cardano-binary (`plain-*`): `GetNonMyopicMemberRewards` (2), `GetStakePoolParams`
  (17), `GetPoolState`, `GetStakeSnapshots`, `GetPoolDistr`, `GetPoolDistr2` (19, 20, 21, 36),
  `GetStakeDelegDeposits` (22), `GetDRepState` (25) and the credentials of
  `GetCommitteeMembersState` (27). A set is tag 258 around a definite list in strictly ascending
  order (`decodeSetSkel`), a `Maybe` a definite list of none or one, an `Either` a definite list of
  two. The cases: tagged and ascending, empty, untagged, indefinite, descending, a duplicate, a
  short hash, a script credential before a key one and the reverse, a credential written as an
  indefinite list, coins before credentials and the reverse, an `Either` of an unknown side.
- `LC.fromEraCBOR @era` (`era-*`), the ledger's `decCBOR` at Conway's lowest protocol version, 9,
  for the others: a set definite or indefinite, tagged or not, in any order, without two elements
  alike (`decodeSetEnforceNoDuplicates`). The cases: credentials, inputs, DReps, pools, member
  statuses (and a status of 3), a single key hash, addresses (and one a byte short), each with a
  duplicate.

`NtcQueryArgumentsSuite` puts each argument in its query, in Conway's `QueryIfCurrent`, and requires
the node to read the same values or refuse it.

Not covered here: the envelopes of a query (the query, the hard-fork combinator's block query and
`decodeNS`, the ledger query), which the reference reads with `decodeListLen` or `enforceSize`, a
definite length only. The `ntc` devnet scenario sends them written indefinite to both nodes, which
must both close the connection (`scripts/devnet/ntcsafety.py`), and `NtcQueryArgumentsSuite` checks
the node's refusal at each level.
