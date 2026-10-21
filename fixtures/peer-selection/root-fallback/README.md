# Native big-ledger public fallback

These nine explicit inputs call the unchanged pinned
`Ouroboros.Network.PeerSelection.PeerSelectionActions.requestPublicRootPeersImpl`
with `SingBigLedgerPeers`. The observer generates big-ledger requests directly; it does not
capture a broader corpus and remove outputs. Source and build identities are in
[manifest.json](manifest.json), with the maintained [observer](../../../scripts/oracle/root-fallback/Main.hs)
and [exporter](../../../scripts/oracle/root-fallback/export.py).

Inputs supply ledger `Nothing`, `Just empty`, or `Just {1,2}`, requested counts 0/1/4, and two
configured literal-IP roots with different advertise flags. `Nothing` discovers ordinary public
extras even on a big-ledger request. `Just empty` remains an empty ledger response. Configured
literal-IP fallback returns both roots regardless of the requested count. The constant resolver
needs no DNS lookup or network access.

The differential test checks the partitions, ordinary/big origins and advertise flags through
Scarab's production `BigLedgerRoots.fromLedger` adapter, including empty successful responses.
This is the classification boundary, not a claim of every provider interpreter's equivalence.
The native TTL is retained and checked against the supplied ledger TTL or native literal-root
minimum: fallback 60 seconds; the injected successful ledger response 30 seconds. Actual provider
tests independently check production fallback TTL and DNS integration. This does not compare
native and Scala ledger-worker random draws or use the fixed injected set as a worker expectation.

These are new explicit boundary cases; no original upstream generated cases or expectations are
changed. Regenerate with the existing pinned network build mounted read-only:

```sh
python3 scripts/oracle/root-fallback/export.py \
  --reference-build /tmp/scarab/148-fetch-oracle-final-2 \
  --probe-dir /tmp/scarab/native-root-fallback-regeneration \
  --output-dir fixtures/peer-selection/root-fallback
```
