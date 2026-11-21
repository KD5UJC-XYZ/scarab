<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra protocol-parameter updates

**Status: Current** — optional offline reference oracle for [#85](https://github.com/KD5UJC-XYZ/scarab/issues/85).

Build the [Leios testnet oracle image](../leios-testnet/README.md) first, then:

```sh
docker build -f scripts/oracle/dijkstra-pparams/Dockerfile -t scarab-dijkstra-pparams-oracle:local .
python3 scripts/oracle/dijkstra-pparams/run.py            # offline: the generated cases match the fixture
python3 scripts/oracle/dijkstra-pparams/run.py --replay   # run the image (no network, read-only) and compare
python3 scripts/oracle/dijkstra-pparams/run.py --record   # rewrite the fixture and its provenance
```

`Main.hs` decodes each update `run.py` builds as a `PParamsUpdate DijkstraEra` of the unchanged cardano-ledger
`9df9f98c`, the revision cardano-node `b004fe0c` pins, at version 12 (`decodeFull'`). It writes the decoder's
refusal, or `ppuWellFormed` at versions 12.0 and 10.0, `modifiedPPGroups` and the decoded update as the build
writes it (`serialize'` at version 12). The [fixture](../../../fixtures/reference/dijkstra-pparams/README.md)
records the cases, the pins and the scope. No public-network connection, node or key is needed. The oracle's
container is named and labelled by `--name` (`container_labels` of `scripts/cleanup.py`; default
`dijkstra-pparams`) and removed when it exits.
