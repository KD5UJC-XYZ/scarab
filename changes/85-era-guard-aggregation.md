### Added (era types, #85)

- The Leios testnet build's answers on four more crafted transactions, each with a Plutus V4 guard whose view of the
  whole transaction combines its levels' votes and current treasury amounts: one voter's votes on an action at
  three levels, votes by other voters and on other actions at two levels, a treasury amount given by a
  sub-transaction only, and amounts at two levels. Dijkstra's Plutus V4 script contexts on the era types build each
  guard's arguments byte for byte as the reference does: the votes by its right-biased union, the
  sub-transactions' in order and then the top level's, and the first treasury amount present.
