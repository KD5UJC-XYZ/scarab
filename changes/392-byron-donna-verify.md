### Fixed (Byron signatures, #392)

- Verify Byron-era signatures (block headers, delegation certificates, update proposals and votes, transaction and redeem witnesses) with the reference's Byron semantics (ed25519-donna, as cardano-crypto and crypton ship it): S is reduced modulo L, and keys of small order or with a non-canonical encoding are read as the reference reads them. Shelley and later eras keep the strict verifier. Verdicts are pinned against the released reference libraries in `fixtures/witness-byron`.
