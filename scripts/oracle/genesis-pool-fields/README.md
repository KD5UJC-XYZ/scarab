<!-- SPDX-License-Identifier: Apache-2.0 -->
# Genesis pool JSON reference

`Main.hs` calls the unmodified released `FromJSON StakePoolParams` and encodes its relay sequence
and each present metadata payload with `EncCBOR` at version 11. It does not execute a ledger
transition or compare whole genesis state.

Build the existing [context reference image](../plutus/context/README.md), then replay:

```sh
python3 scripts/oracle/genesis-pool-fields/run.py --name my-genesis-pool-fields
```

The runner compiles in a disposable Docker container with no network, two CPUs, a private tmpfs,
and read-only source/input mounts. It removes its temporary input and container. Every captured
row, including native refusals and encoded bytes, must match. `--image` selects a compatible
image with the same pinned package set. The [corpus provenance](../../../fixtures/reference/genesis-pool-fields/provenance.json)
records the actual capture image, package units, source archive identities and harness identity.
