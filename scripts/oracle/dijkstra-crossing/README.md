<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra crossing: transactions

**Status: Current** — optional offline reference oracle for [#54](https://github.com/KD5UJC-XYZ/scarab/issues/54).

Build the [Leios testnet oracle image](../leios-testnet/README.md) first, then:

```sh
docker build -f scripts/oracle/dijkstra-crossing/Dockerfile -t scarab-dijkstra-crossing-oracle:local .
python3 scripts/oracle/dijkstra-crossing/run.py            # offline: the generated cases match the fixture
python3 scripts/oracle/dijkstra-crossing/run.py --replay   # run the image (no network, read-only) and compare
python3 scripts/oracle/dijkstra-crossing/run.py --record   # rewrite the fixture and its provenance
```

`Main.hs` decodes each whole Conway transaction `run.py` builds with the unchanged cardano-ledger
`9df9f98c`, the revision cardano-node `b004fe0c` pins, and carries it into Dijkstra in two ways: the
ledger's `TranslateEra DijkstraEra (Tx TopTx)` (`Dijkstra/Translation.hs:53-68`), with each part also
carried alone, and what the hard-fork mempool of ouroboros-consensus `2416493b` does
(`rematchValidatedTxs`, `HardFork/Combinator/InjectTxs.hs`): Conway's `toCBOR` read whole by
Dijkstra's decoder. The [fixture](../../../fixtures/reference/dijkstra-crossing/README.md) records the
cases, the pins and the scope. No public-network connection, node or key is needed.
