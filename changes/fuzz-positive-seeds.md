### Fixed (decoder fuzz coverage)

- Require every registered fuzz target to accept an actual corpus seed, without exemptions. Add native-serialized Shelley, Allegra and Mary transaction seeds, preserve their original three-field envelopes, and compare Shelley block decoding with node preparation under their shared acceptance contract.
