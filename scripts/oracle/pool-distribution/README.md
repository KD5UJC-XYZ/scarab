<!-- SPDX-License-Identifier: Apache-2.0 -->
# Pool-distribution reference

`Main.hs` calls unchanged `calculatePoolDistr`, `DecCBOR`/`DecShareCBOR` and `EncCBOR` APIs in
both the [stable image](../plutus/context/README.md) and the [Leios image](../leios-testnet/README.md).
No ledger transition or committee selection is implemented by the harness.

```sh
python3 scripts/oracle/pool-distribution/run.py --name my-pool-distribution
python3 scripts/oracle/pool-distribution/run.py --name my-pool-distribution --record
```

The first command compares all native results against the [recorded corpus](../../../fixtures/reference/pool-distribution/README.md).
The second replaces the corpus and provenance. Both compile in disposable, labelled Docker
containers with networking disabled and read-only source/input mounts. The public BLS keys and
proofs used by the cases come from the existing Leios devnet fixture.
