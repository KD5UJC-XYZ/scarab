<!-- SPDX-License-Identifier: Apache-2.0 -->
# Genesis pool JSON reference

`Main.hs` decodes each pool from its JSON text with aeson's `eitherDecode`, calls the unmodified
released `FromJSON StakePoolParams`, and emits every field: the hashes, Coins and account with
their `ToJSON`, the margin as a reduced numerator and denominator, and the relay sequence and each
present metadata payload encoded with `EncCBOR` at version 11. It does not execute a ledger
transition or compare whole genesis state.

Build the existing [context reference image](../plutus/context/README.md), then replay:

```sh
python3 scripts/oracle/genesis-pool-fields/run.py --name my-genesis-pool-fields
```

`--capture inputs.jsonl` (lines of `{"name", "poolJson"}`) instead writes the native results of
those inputs as the corpus.

The runner compiles in a disposable Docker container with no network, two CPUs, a private tmpfs,
and read-only source/input mounts. It removes its temporary input and container. Every captured
row, including native refusals and encoded bytes, must match. `--image` selects a compatible
image with the same pinned package set. The [corpus provenance](../../../fixtures/reference/genesis-pool-fields/provenance.json)
records the actual capture image, package units, source archive identities and harness identity.
