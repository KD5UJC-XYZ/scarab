### Added (Leios certifying, #54)

- Under `--reference-build leios-b004fe0c` the forger certifies: a block forged on a parent that
  announced an endorser block at least the certification gap earlier, whose closure the store holds
  and for whose parent the vote state holds a certificate, carries the certificate and no
  transaction; the endorser block it announces, if any, is made of the pool's transactions
  revalidated on the parent with the certified closure applied.
- A block built ahead of its slot without a certificate the node holds at the slot's onset is forged
  again (`forgeDiscarded` with reason `certificate`).
- The log lines `leiosBlockCertified`, `leiosBlockForged`, `leiosBlockAnnounced`,
  `leiosCertifiedAndAnnounced`, and `leiosKernelMsg` for an announced endorser block not yet
  downloaded or without a certificate; a certification that cannot be carried out stops the node.
- `devnet.py leios-traffic --follow --follow-forge POOL`: the Scala node is pool POOL of the
  reference Leios cluster, forging and voting, with that pool's reference node not started.
