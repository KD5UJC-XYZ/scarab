# Fixture licenses

Upstream licenses and notices for the test vectors under `fixtures/`:

- `network/`: handshake, mux, KeepAlive and BlockFetch byte vectors derived from
  IntersectMBO ouroboros-network, network-mux, cardano-diffusion and
  ouroboros-consensus (`ouroboros-network-*`, `network-mux-NOTICE`,
  `ouroboros-framework-NOTICE`, `cardano-diffusion-NOTICE`, `block-fetch-*`).
  The `scalus-*.hex` vectors come from Scalus-node (`scalus-node-LICENSE`).
- `opcert/`, `praos/`, `sum6/`, `vrf/`: header, VRF, KES and operational-certificate
  vectors from public Cardano chain data and IntersectMBO cardano-base and
  cardano-ledger (`cardano-ledger-LICENSE`, `cardano-ledger-NOTICE`).
- `witness/`: the strict Ed25519 corpus. It keeps its own license and provenance
  files under `fixtures/witness/licenses` and `fixtures/witness/provenance`
  (sodium ISC, speccheck/Wycheproof, Cardano Apache-2.0 and the Weavechain
  arithmetic attribution).

All upstream licenses are retained verbatim. No private corpus or native oracle
binary is included.
