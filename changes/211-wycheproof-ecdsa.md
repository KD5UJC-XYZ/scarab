### Added (Plutus, #211)

- The Wycheproof ECDSA secp256k1/SHA-256 corpus (476 cases, commit `7a325932`, Apache-2.0) runs
  through the reference's `verifyEcdsaSecp256k1Signature` semantics (`WycheproofSecp256k1Suite`):
  96 verdicts `True`, 102 `False` — including the 72 valid-but-high-`s` signatures the reference
  refuses — 44 compact parse failures and 234 strict-DER rejections, the counts pinned.
