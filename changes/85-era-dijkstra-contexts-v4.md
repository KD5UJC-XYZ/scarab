### Added (era types, #85)

- Dijkstra's Plutus V4 script contexts on the era types, as the Leios testnet's build translates them: the
  information of a transaction or a sub-transaction, a guard's view of the whole transaction with each
  sub-transaction's information, and each purpose with the hash of its script; with Dijkstra's script contexts of
  every language and the makers of its annotated transactions, which collect a level's contexts only when they
  are read. Every context the Leios testnet's chain context fixture holds (273, Plutus V1 to V4) is built, its
  serialized arguments the reference's byte for byte and the rest of its `PlutusWithContext` record field by
  field, and every context and refusal of the refusals fixture and of the hand-written Plutus V4 cases is the
  reference's, every field of the recorded error compared.
  The node still builds contexts with the existing path.
