<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native Leios genesis injections

Captured on 2026-11-14 from the pinned Leios ledger image by [the harness](../../../scripts/oracle/leios-genesis/Main.hs).
The image ID, the ledger and cardano-base revisions, the digests of the genesis files, the harness,
the cases and the seven source files the result depends on are in [provenance.json](provenance.json).
The runner refuses an image whose copies of those files differ from the pinned upstream files.

The first row (`start`) is the state the injection is applied to; each other case is the Leios testnet's genesis files (`fixtures/leios/testnet/config`) with one change to
its Shelley genesis (`op`: none, `remove` or `replace`, at `path`, with `value`; `legacy` moves the pools to `staking.pools` and replaces the voting key there). The base sets the
Shelley genesis's protocol version to Conway's 10: the file names 12, Dijkstra's, which
`createInitialState` refuses for Conway, and the node reaches Conway through the era translations
instead. The injection does not read the version. The harness reads the three files with their
unchanged `FromJSON` instances, builds Conway's state with `createInitialState`, recorded once as
the `start` row, and applies Conway's unchanged `injectIntoTestState` to it: the cost models, the
funds, the pools with their voting keys, the stake, the DReps and the delegations, then
`resetStakeDistribution` (cardano-ledger 9df9f98c `Conway/Transition.hs:161-185`,
`Shelley/Transition.hs:526-618`). An accepted case records the SHA-256 of the resulting
`NewEpochState` without its UTxO (as a ledger snapshot holds it, the UTxO in its tables), and the
exact bytes of its UTxO, its snapshots and its pool states.

The cases cover the pool's voting key (`blsKey`) as the testnet writes it (`null`), absent, the key
of a devnet pool, in upper-case hex, with the proof of another key (accepted: genesis does not
check the proof), with an unknown field, and in the legacy `staking.pools` instead of
`extraConfig.stakePools`. They also cover what the reference refuses: a key that is not an object,
a missing or non-text field, hex that is odd, not hex, prefixed `0x` or empty, a key or proof of
another length, the point at infinity, a point not on the curve, and an uncompressed form.

`LeiosGenesisReferenceSuite` reads each case's files with `GenesisInjections.read` in the Leios
layout and injects them into the decoded start state with `GenesisStart.inject`. It compares every
acceptance verdict and, for every accepted case, the state's digest and the bytes of its UTxO,
snapshots and pool states. Native messages are kept for diagnosis; they are not compared with
Scala's typed errors. No fields or cases are excluded.

The corpus does not cover the Byron to Conway translations the node applies before the injection,
a registration epoch other than zero, a Dijkstra start, or the consensus layer's parts of the start
state; `LeiosTestnetConfigSuite` checks that the testnet's start state is in the Leios layout
throughout. The image uses GHC 9.10.3 and the source packages pinned by its recipe, not the official
node executable. Raw build logs are not published.

Regenerate or replay with [the runner](../../../scripts/oracle/leios-genesis/run.py).
