### Fixed (Dijkstra CBOR conformance, #54)

- Re-encode decoded certificates, proposals, parameter updates, scripts, datums, balance intervals, relays and outputs with production wire writers. Preserve hashed datum and native-script bytes while rebuilding output fields.
- Decode standalone DReps and pool metadata inside minimal certificates, and block bodies inside a minimal Leios block. Keep layout disagreements and missing encoders visible failures.
- Check the standalone auxiliary-scripts rule as its declared Timelock type while full Dijkstra auxiliary data keeps accepting guards. Keep reference-profile disagreements visible in the unchanged complete corpus.
