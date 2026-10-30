### Fixed (Byron update proposal metadata, #552)

- The Byron ledger decoder reads an update proposal's metadata and attributes as the reference does:
  each `SystemTag` key is definite text, the keys are strictly ascending, each value is an installer
  hash, and the attributes are empty. A fetched block was already checked this way at transport; a
  block read from storage or an import is now checked the same way.
