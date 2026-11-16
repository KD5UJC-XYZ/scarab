Complete Dijkstra CBOR round trips for certificates, proposals, parameter updates, scripts, datums, balance
intervals, relays and outputs with production wire writers. Decode standalone DReps and pool metadata inside
minimal certificates. Re-encode output fields instead of replaying the whole output, preserving hashed datum
and native-script bytes. Check the standalone auxiliary-scripts rule as its declared Timelock type while full
Dijkstra auxiliary data keeps accepting guards. Keep reference-profile disagreements visible in the unchanged
complete corpus.
