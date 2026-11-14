<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra translation: outputs

**Status: Current** — optional offline reference oracle for [#54](https://github.com/KD5UJC-XYZ/scarab/issues/54).

Build the [Leios testnet oracle image](../leios-testnet/README.md) first, then:

```sh
docker build -f scripts/oracle/dijkstra-translation/Dockerfile -t scarab-dijkstra-translation-oracle:local .
python3 scripts/oracle/dijkstra-translation/run.py            # offline: the generated cases match the fixture
python3 scripts/oracle/dijkstra-translation/run.py --replay   # run the image (no network, read-only) and compare
python3 scripts/oracle/dijkstra-translation/run.py --record   # rewrite the fixture and its provenance
```

`Main.hs` decodes each Conway output `run.py` builds with the unchanged cardano-ledger `9df9f98c`, the
revision cardano-node `b004fe0c` pins, applies Dijkstra's `upgradeTxOut` and writes both outputs and the
reference script's bytes and hash in each era. The [fixture](../../../fixtures/reference/dijkstra-translation/README.md)
records the cases, the pins and the scope. No public-network connection, node or key is needed.
