### Fixed (pre-Conway witness sets and reference scripts, #374)
- **A Shelley, Allegra or Mary witness set with a key outside 0 to 2 is refused, as the reference refuses
  it.** Scarab read the Alonzo key set (0 to 7) in every era.
- **A Plutus script of a language newer than the era's is refused, as the reference refuses it.** That
  covers a PlutusV2 or PlutusV3 script in an Alonzo witness set, a PlutusV3 script in a Babbage witness set,
  and a PlutusV3 reference script in a Babbage output (`eraUnsupportedLanguage`, the new typed
  `UnsupportedLanguage`). An empty list of such scripts is still accepted, as in the reference.
- The pre-Conway decoding corpus gains 125 reference verdicts (227 cases). They cover empty collections and
  tag 258 below decoder version 9, the witness key sets, the script languages and a repeated proposer in an
  update, which keeps the last update.
