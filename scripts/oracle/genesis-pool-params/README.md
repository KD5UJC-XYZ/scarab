<!-- SPDX-License-Identifier: Apache-2.0 -->
# Full genesis pool JSON reference

`Main.hs` runs the unchanged released `FromJSON StakePoolParams` on each original `poolJson`
string, retaining acceptance/refusal and every accepted field. Relay/metadata values use native
`EncCBOR` at protocol version 11. No ledger transition is run.

Using the existing [context reference image](../plutus/context/README.md):

```sh
python3 scripts/oracle/genesis-pool-params/run.py --name my-genesis-pool-params
```

The runner uses a disposable two-CPU Docker container, read-only inputs, no network and a private
build tmpfs. Every complete output row must equal the [captured corpus](../../../fixtures/reference/genesis-pool-params/README.md).
The corpus provenance pins the actual native package sources and harness.

Numeric tokens are compared with their complete original spelling and a distinct numeric type.
This covers native signed-64-bit Scientific exponents beyond Python Decimal's construction range
and keeps numbers distinct from JSON strings. The native replay includes the four raw-ingress
controls; their separate Scala gate remains incomplete pending #497.
