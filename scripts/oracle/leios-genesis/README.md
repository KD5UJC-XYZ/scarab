<!-- SPDX-License-Identifier: Apache-2.0 -->
# Leios genesis reference

`Main.hs` calls the unchanged `FromJSON` instances of the Shelley, Alonzo and Conway genesis files,
`createInitialState` and Conway's `injectIntoTestState` of the [Leios image](../leios-testnet/README.md)
(cardano-ledger 9df9f98c, cardano-base c1e1ed8d), with a transition configuration composed as
cardano-ledger-api's `mkLatestTransitionConfig` composes it, one era short of Dijkstra. Every
injection source is embedded, so the file system the injection is given is never read. The harness
runs no other ledger code.

```sh
python3 scripts/oracle/leios-genesis/run.py --name my-leios-genesis
python3 scripts/oracle/leios-genesis/run.py --name my-leios-genesis --record
```

The first command compares all native results with the [recorded corpus](../../../fixtures/reference/leios-genesis/README.md).
The second replaces the corpus and its provenance, which records the digests of the genesis files
and of the pinned sources found in the image. Both commands compile in disposable, labelled Docker
containers with networking disabled and read-only mounts for the sources and inputs. The public BLS
keys and proofs come from the existing Leios devnet fixture.
