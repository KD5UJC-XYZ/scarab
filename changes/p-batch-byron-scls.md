### Fixed (Conway governance, #970)

- Adding a proposal whose id is already stored replaces that entry where it stands, as the
  reference's `OMap.||>` does, instead of storing it a second time. Valid transactions cannot reach
  this: a proposal's id is its transaction's id and an index.
