<!-- SPDX-License-Identifier: Apache-2.0 -->
# Network: handshake, mux, KeepAlive, ChainSync, BlockFetch (client)

Part of the [compatibility matrix](../compatibility.md); the register entry is `network-handshake-mux-client` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented (node-to-node: both sides offer 14 and 15, the latest release of cardano-node 11.1.3, and 16 with `ExperimentalProtocolsEnabled` (G4: typed `NtnVersion`, negotiation as the reference's `Acceptable`: the same magic, initiator-only wins, peer sharing and Peras are conjunctions, query a disjunction; the Peras flag is always off); one connection per peer address, pipelined; headers validated before their blocks are fetched, G6; connections kept by the connection manager, G2)

## Evidence

unit/property over in-memory bearers and loopback TCP; structured fuzzing of every mini-protocol codec and the mux (`fuzz`, K3: mutated fixtures, CDDL-sampled messages, 2^60 declared lengths, decode-encode round trips); `fixtures/network` captured envelopes; Preview sync; devnet; `HeaderRulesSuite`, `HeaderGateSuite`, `ConnectionManagerSuite`, `ConnectionStateSuite`; #318 `PraosHeaderValidationSuite` uses original seven-era headers over real clients and rejects tampered Byron/first-Shelley signatures and an encoded epoch-boundary envelope before fetching bodies

## Explicitly unchecked or unconfirmed

Genesis coverage is recorded in the separate G3 row; version 16 negotiates and changes only the version data, the Peras mini-protocols do not exist; explicit stricter local resource profiles in [G5](../design/network-limits.md); all enabled header-first modes now share canonical all-era validation and native exclusive forecasts ([#318](https://github.com/KD5UJC-XYZ/scarab/issues/318), [source correspondence](../design/genesis.md#shared-early-header-validation-318)); `--header-first off` remains a Praos operational option; outbound connections configured as InitiatorAndResponder negotiate duplex and serve the peer on the same bearer; each outbound address has generation-checked link registration and cleanup; the manager recognises a peer's inbound connection as the peer we dial only when it dials from its listening port
