### Changed (ledger, #96)

- Order voters by the constructor, the credential (script before key) and the raw hash bytes, as the reference's derived `Ord Voter` does, instead of building a hex string for every comparison. The order itself is unchanged: lower-case hex already sorted as the unsigned bytes do.
