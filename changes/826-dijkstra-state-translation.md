### Added (the Leios build, #826)

- An offline oracle records the Leios build's translation of a captured Conway ledger state into
  Dijkstra, and `DijkstraStateTranslationSuite` checks Scarab's `Translate.conwayToDijkstra` against
  it: the translated `NewEpochState`, written at version 12, and each upgraded output of the UTxO
  table are the reference's byte for byte. The consensus parts of the crossing are not compared.
