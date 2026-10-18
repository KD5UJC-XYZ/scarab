### Changed (KES signing-key errors, #82)

- KES signing-key validation now reports specific error cases with length, period and tree-depth
  information. Key-file loading and evolution retain the typed cause through the forging boundary;
  operator messages are rendered at presentation. Key formats, validation and signing are unchanged.
