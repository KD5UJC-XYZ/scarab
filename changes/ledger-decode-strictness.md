### Fixed (ledger decoding)

- Read each era's certificates as its decoder does: a body of Shelley to Babbage takes tags 0 to 6 and refuses 7 to 18, and a Conway body refuses a genesis delegation (tag 5) or a move of instantaneous rewards (tag 6) when it is decoded.
- Read the native scripts of a Shelley witness set as `MultiSig`, without the time locks (tags 4 and 5) that Allegra added.
- Refuse a Conway block body whose auxiliary data names a transaction index twice; earlier eras keep the last entry, as before.
- Refuse an Alonzo to Conway block body whose invalid-transaction list repeats an index or decreases, which the reference cannot decode.
