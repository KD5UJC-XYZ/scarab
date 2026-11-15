<!-- SPDX-License-Identifier: Apache-2.0 -->
# Leios epoch reference

`Main.hs` builds Conway's state for the Leios testnet's genesis files as the
[genesis harness](../leios-genesis/README.md) does: the unchanged `FromJSON` instances,
`createInitialState` and Conway's `injectIntoTestState` of the [Leios image](../leios-testnet/README.md)
(cardano-ledger 9df9f98c, cardano-base c1e1ed8d). It then changes the state as each case says and
ticks it with Conway's unchanged `TICK` (`applyTickNoEvents`) to each slot of the case, in order.
The globals are `mkShelleyGlobals` of the Shelley genesis, with a fixed epoch size, which is what
the hard-fork combinator's epoch information gives a chain whose eras all have that size. The
harness runs no other ledger code.

A case's change is not a lawful chain's history: the genesis pool re-registered with its own
parameters, the cost one lovelace higher and no voting key (the form a certificate registers up to
Conway), and the pool's retirement at an epoch. Each is a state a chain in Conway can reach, set
directly instead of by a transaction.

```sh
python3 scripts/oracle/leios-epoch/run.py --name my-leios-epoch
python3 scripts/oracle/leios-epoch/run.py --name my-leios-epoch --record
```

The first command compares all native results with the [recorded corpus](../../../fixtures/reference/leios-epoch/README.md).
The second replaces the corpus and its provenance, which records the digests of the genesis files
and of the pinned sources found in the image. Both commands compile in disposable, labelled Docker
containers with networking disabled and read-only mounts for the sources and inputs. The public BLS
key and proof come from the existing Leios devnet fixture.
