<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra genesis

**Status: Current** — optional offline reference oracle for [#54](https://github.com/KD5UJC-XYZ/scarab/issues/54).

Build the [Leios testnet oracle image](../leios-testnet/README.md) first, then:

```sh
docker build -f scripts/oracle/dijkstra-genesis/Dockerfile -t scarab-dijkstra-genesis-oracle:local .
python3 scripts/oracle/dijkstra-genesis/run.py            # offline: the generated cases and the base files match the fixture
python3 scripts/oracle/dijkstra-genesis/run.py --replay   # run the image (no network, read-only) and compare every verdict
python3 scripts/oracle/dijkstra-genesis/run.py --record   # rewrite the fixture and its provenance
```

`Main.hs` runs the unchanged `FromJSON DijkstraGenesis` of cardano-ledger-dijkstra 0.5.0.0 at cardano-ledger
`9df9f98c`, the revision cardano-node `b004fe0c` pins, on the files `run.py` builds: each Dijkstra genesis
file of the repository as it is, and the Leios testnet file with one field removed or replaced. A refusal
keeps aeson's error; an acceptance keeps the genesis as the ledger writes it back. The
[fixture](../../../fixtures/reference/dijkstra-genesis/README.md) records the cases, the pins and the scope.
No public-network connection, node or key is needed.
