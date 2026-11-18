<!-- SPDX-License-Identifier: Apache-2.0 -->
# Leios node-to-client queries: stake pool state and Leios committee

**Status: Current** — optional offline reference oracle for [#826](https://github.com/KD5UJC-XYZ/scarab/issues/826).

Build the [Leios testnet oracle image](../leios-testnet/README.md) first, then:

```sh
docker build --label xyz.kd5ujc.scarab.kind=oracle \
  -f scripts/oracle/leios-ntc-queries/Dockerfile -t scarab-leios-ntc-queries-oracle:local .
python3 scripts/oracle/leios-ntc-queries/run.py            # offline: inputs, program and fixture match the provenance
python3 scripts/oracle/leios-ntc-queries/run.py --replay   # run the image (no network, read-only) and compare
python3 scripts/oracle/leios-ntc-queries/run.py --record   # rewrite the fixture and its provenance
python3 scripts/oracle/leios-ntc-queries/run.py --record --accept-build   # ... for a build reviewed anew
```

The inputs are the three [Leios build state files](../../../fixtures/leios/state/README.md): `conway-195`
and `dijkstra-203` of a local devnet, and `testnet-2168639` of the public Leios testnet. `run.py` takes the
ledger's `NewEpochState` out of each (the extended state `[1, [ledger, header, Peras state]]`, the
telescope's current era, `encodeShelleyLedgerState`'s `[2, [tip, NewEpochState, transition, cumulative
transaction bytes]]`, ouroboros-consensus `2416493b` `Shelley/Ledger/Ledger.hs:885-903`), and the
telescope's length gives the era: seven eras for Conway, eight for Dijkstra. `Main.hs` then runs only
unchanged cardano-ledger `9df9f98c` code, the revision cardano-node `b004fe0c` pins:

- it reads the state with `fromCBOR` (the era's `fromEraCBOR`, at version 9 for Conway and 12 for
  Dijkstra), as consensus does (`Shelley/Ledger/Ledger.hs:918`), and refuses to go on unless `toCBOR`
  gives the state's bytes back;
- it answers `queryStakePoolState` (`libs/cardano-ledger-api/src/Cardano/Ledger/Api/State/Query.hs:627-660`)
  and `queryLeiosCommittee` (`:1019-1026`) on it, the functions consensus answers `GetStakePoolState mset b`
  and `GetLeiosCommittee` with (`ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Query.hs:467-470`),
  and writes each answer with `serialize'` at the era's lowest version, which is what consensus's
  `LC.toEraCBOR @era` writes (`Query.hs:969-970`).

The arguments of `GetStakePoolState` are, for each state: every pool with and without its delegators; the
two smallest registered pool ids and one no pool is registered under (28 bytes of `0xff`), without and with
delegators; and the empty set, with delegators. `Main.hs` refuses a state where `0xff…` is registered; with
fewer than two pools the restricted pair would be left out (no input has fewer). It also reports, from the
answer for every pool, how many pools have a voting key, a retirement, future parameters or metadata, the
delegators in all, and the seats of the current and future committees, so a reader knows which branches
of the encoding the bytes exercise. `run.py` checks the answers against the arguments asked: each
restricted answer holds exactly the asked pools that the full answer holds, in key order.

Since no input holds a retiring pool or future pool parameters, `Main.hs` also derives a state from each
input with the ledger's own lenses (`psRetiringL`, `psFutureStakePoolParamsL`): its smallest registered pool
P retiring at the state's epoch + 1, and P's future parameters `stakePoolStateToStakePoolParams Testnet P`
of P's own state (`libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:325-343`), Testnet being
the `networkId` of every input's Shelley genesis file, which `run.py` checks. It answers
`GetStakePoolState` for P alone on that state, with and without delegators. `run.py` checks that each such
answer is P's plain entry in its first 12 fields, with the retirement and future parameters present.

Consensus is not compiled in the image (it needs native libraries the base image lacks; see the
[Leios testnet harness](../leios-testnet/README.md)), so the dispatch of the query, the hard-fork
combinator's era wrapping of the result and the node-to-client message around it are cited above, not
run. `Main.hs` is a second package of the Leios testnet harness's cabal project
(`scarab-leios-ntc-queries.cabal`), built by cabal against that project's dependencies;
`cardano-ledger-api`, which the project's own harness does not use, is built in this image from the same
pinned checkout. The build is not `--offline`, as the chain harness's is not: offline, cabal refused to
build `cardano-ledger-api`, whose plan entry is that source-repository package, as a download
(`Cabal-7125`). The measurement below shows the result: the checkouts at their pins and unchanged, and
cabal, offline, finding the executable up to date.

`run.py` does not trust the image's tag. It resolves the image once to its immutable ID, which the
measurement, the runs and the provenance all use, and measures that image as the
[chain oracle](../leios-testnet/chain/run.py) measures its own (whose image resolution, cabal-project
readers and CBOR reader it imports): the image's `Main.hs`, package file and cabal project are the
repository's; each `source-repository-package` checkout is at the commit the cabal project pins
(cardano-ledger `9df9f98c` among them) and unchanged; cabal, offline, finds the executable up to date; the
entry point is that executable; and the ledger sources the answers depend on have their pinned digests.
The provenance binds the image's immutable ID and the executable's and the build files' digests, and
`--replay` and `--record` refuse an image with another ID (its unmeasured layers may differ) or whose build
differs from them; `--record --accept-build` accepts an image reviewed anew. The
[fixture](../../../fixtures/reference/leios-ntc-queries/README.md) records the answers, the pins and the
scope. No public-network connection, node or key is needed.
