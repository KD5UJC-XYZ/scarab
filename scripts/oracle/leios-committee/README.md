<!-- SPDX-License-Identifier: Apache-2.0 -->
# Leios committee reference

`Main.hs` calls the unchanged `DecCBOR (SnapShots era)`, `mkSetSnapShot` and `EncCBOR` APIs of the
[Leios image](../leios-testnet/README.md) (cardano-ledger 9df9f98c, cardano-base c1e1ed8d). Decoding
a mark snapshot runs `mkMarkSnapShot`, which selects the committee with `selectLeiosCommittee`;
the rotation then moves that committee into the set position. The harness implements no ledger
transition.

```sh
python3 scripts/oracle/leios-committee/run.py --name my-leios-committee
python3 scripts/oracle/leios-committee/run.py --name my-leios-committee --record
```

The first command compares all native results with the [recorded corpus](../../../fixtures/reference/leios-committee/README.md).
The second replaces the corpus and its provenance, which records the digests of the pinned sources
found in the image. Both commands compile in disposable, labelled Docker containers with
networking disabled and read-only mounts for the sources and inputs. The public BLS keys and
proofs come from the existing Leios devnet fixture.
