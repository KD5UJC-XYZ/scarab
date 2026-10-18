### Changed (snapshot metadata errors, #82)

- Snapshot metadata parsing reports separate JSON syntax, root-shape, missing-field and wrong-field-type
  errors, retaining parser causes and offending JSON values. Import/export preserve those typed
  causes; a refused import leaves the database unchanged. Metadata bytes, checksum rules and stored
  formats are unchanged. A new `snapshot-meta` fuzz target uses the reference metadata fixtures.
