### Fixed (ledger, #1219)

- A Byron block the node replays from its own store, resumes from its volatile log or replays after importing it from a
  reference node's database (`import-haskell-db`) now has the verification keys of its body (a transaction witness's, a
  delegation certificate's issuer and delegate, an update proposal's issuer, a vote's voter) read in their shortest form,
  as the reference reads its own disk blocks; such a block was accepted there before. A stored block with such a key
  stops the start with its typed refusal, as any stored block the rules refuse does, and a logged one ends the resumed
  chain before it. The blocks of a public network's chain were accepted by the reference, whose decoder reads keys so.
