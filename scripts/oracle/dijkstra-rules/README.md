<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra rules: BBODY, UTXOW, CERTS and GOV

**Status: Current** — optional offline reference oracle for [#54](https://github.com/KD5UJC-XYZ/scarab/issues/54).

Build the [Leios testnet oracle image](../leios-testnet/README.md) first, then:

```sh
docker build -f scripts/oracle/dijkstra-rules/Dockerfile -t scarab-dijkstra-rules-oracle:local .
python3 scripts/oracle/dijkstra-rules/run.py            # offline: the generated cases match the fixture
python3 scripts/oracle/dijkstra-rules/run.py --replay   # run the image (no network, read-only) and compare
python3 scripts/oracle/dijkstra-rules/run.py --record   # rewrite the fixture and its provenance
```

`Main.hs` applies one Dijkstra rule of the unchanged cardano-ledger `9df9f98c`, the revision cardano-node
`b004fe0c` pins, to each case `run.py` builds, with `applySTS` and every check run, and writes the rule's
acceptance or each predicate failure (its `EncCBOR` at version 12 and its `show`). The
[fixture](../../../fixtures/reference/dijkstra-rules/README.md) records the cases, the pins and the scope. No
public-network connection, node or key is needed.
