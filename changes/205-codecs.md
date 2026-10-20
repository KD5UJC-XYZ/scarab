### Changed (snapshot codecs, #205)

- Derive typed field accessors and result tuples, with subtype-checked sum cases: generic arguments and phantom roles stay connected to their codecs. Field order, variant tags, schema fingerprints and persisted bytes are unchanged.
- Avoid intermediate product tuples on encoding and specialize the final record pair on decoding. Representative V5 snapshots and restart pass; the remaining tiny-record encode cost is tracked in #257.
