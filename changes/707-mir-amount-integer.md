### Fixed (ledger decoding, #707)

- The amount a move-instantaneous-rewards certificate pays a stake credential is read as the reference reads a `DeltaCoin`, an `Integer` (`decodeInteger`): a bignum (tags 2 and 3) is accepted, as an unsigned or negative integer was (`Shelley/TxCert.hs:341-353`, `Coin.hs:115-119`, cardano-ledger f649f975). A Shelley-to-Babbage block whose MIR certificate wrote an amount as a bignum was accepted by the reference and refused by Scarab.
