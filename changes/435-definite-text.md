### Fixed (ledger decoding, #435)

- Read a block body's text fields as the reference does. Every text field is read with `decodeString`, which takes a definite-length string in every decoder version, so a chunked pool metadata url, relay DNS name or governance anchor url is now refused with a typed error (`IndefiniteLength`, `IndefiniteDnsName`) instead of being joined and accepted. Transaction metadata and Plutus `Data` keep their chunked readers, and the original bytes that are hashed are unchanged.
