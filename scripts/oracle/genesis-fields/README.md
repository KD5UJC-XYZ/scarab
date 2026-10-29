<!-- SPDX-License-Identifier: Apache-2.0 -->
# Genesis fields

**Status: Current** — optional offline reference oracle for [#497](https://github.com/KD5UJC-XYZ/scarab/issues/497).

Build the [existing context-reference image](../plutus/context/README.md) first, then:

```sh
docker build -f scripts/oracle/genesis-fields/Dockerfile -t scarab-genesis-fields497-oracle:local .
python3 scripts/oracle/genesis-fields/run.py            # offline: the generated cases and the base files match the fixture
python3 scripts/oracle/genesis-fields/run.py --replay   # run the image (no network, read-only) and compare every verdict
python3 scripts/oracle/genesis-fields/run.py --record   # rewrite the fixture and its provenance
```

`Main.hs` runs the unchanged released libraries (cardano-ledger-shelley, -alonzo 1.16.0.0 and
-conway 1.23.0.0 at cardano-ledger `f649f975`). It applies `FromJSON ShelleyGenesis`, `FromJSON AlonzoGenesis` and
`FromJSON ConwayGenesis` to the files `run.py` builds: each genesis file of the repository as it
is, and the preview, devnet and Leios files with one field removed or replaced. A refusal keeps
aeson's error. An acceptance of a Shelley file keeps the protocol parameters and the update quorum
the ledger read. The [fixture](../../../fixtures/reference/genesis-fields/README.md) records the
cases, the pins and the scope. No public-network connection, node or key is needed.
