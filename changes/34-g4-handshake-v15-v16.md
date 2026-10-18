### Changed (handshake versions, G4, #34)
- **Both sides offer node-to-node versions 14 and 15** where the client offered 14 only and the server accepted 14 and 15,
  the reference's default (`latestReleasedNodeVersion` of cardano-node 11.1.3 is 15). A connection to a mainnet or preprod
  peer now comes out as 15 where it was 14; version 15 changes nothing on the wire (it added SRV records to the topology).
- **`ExperimentalProtocolsEnabled: true` raises the maximum to 16**, as in the reference (`limitToLatestReleasedVersion`).
  Version 16 adds the Peras flag to the version data; the node always sends it as false (it has no Peras mini-protocols), so
  the agreed flag is false. The public Leios testnet sets the key, so the node offers its relay 14 to 16 and the relay
  accepts 16. The Haskell relay of the devnet `relay` scenario needs the same key to agree on 16
  (`devnet.py relay --max-ntn-version 16` sets it in both configurations).
- The versions are typed: `NtnVersion` (14 to 16) and `NtcVersion` (16 to 23) in `protocol`, with the latest released
  version of each; `Handshake` recognises exactly these; `NodeToNode.Settings.maxVersion`, `NodeToNodeServer.Settings.maxVersion`
  and `ConfigSettings.maxNtnVersion` carry the maximum. `HandshakeClient.Agreed.ntnVersion` is the agreed one, typed.
- Node-to-client: versions 16 to 23 are the reference's whole range. LocalStateQuery retains the negotiated version
  for query availability and answer encoding ([#168](https://github.com/KD5UJC-XYZ/scarab/issues/168)).

### Added (CDDL, fuzzing, X12)
- **A `leios-fetch` module in the models pipeline**: the Blueprint's `leios-fetch/messages.cddl` (`fe4cceb9`; no consensus
  or network branch has a LeiosFetch CDDL), with `base` plus `word16` and a local `tx` module (`tx = any`); the generated
  reader is compared with the hand-written codec (`LeiosFetchCddlSuite`), and `leios-fetch:leiosBlockFetchMessage` is a
  cuddle cross-check root (one allowlisted float divergence).
- Fuzz targets `leios-fetch` and `leios-notify`.
- Tests: negotiation over every pair of version sets and flags (`HandshakeNegotiationSuite`), the bytes of our proposal
  and the testnet relay's recorded answers (`LeiosTestnetHandshakeSuite`), a connection for each pair of maxima
  (`NodeToNodeVersionSuite`).

### Fixed
- **The Leios writers copy transactions and announced headers as they came.** `MsgLeiosBlockTxs` and
  `MsgLeiosBlockAnnouncement` re-encoded the item through the value tree, so a non-shortest integer changed the bytes and the
  hash (found by the new `leios-fetch` fuzz target). Nothing sent these yet.

### Known gaps
- The Blueprint and the prototype changed LeiosFetch on 2026-10-16 (`MsgLeiosBlockTxs` is `[4, tx_list]`, tag-24
  transactions): the codec and the pinned copy follow the form of the testnet release ([#174](https://github.com/KD5UJC-XYZ/scarab/issues/174)).
