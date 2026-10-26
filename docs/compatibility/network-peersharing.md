<!-- SPDX-License-Identifier: Apache-2.0 -->
# Network: PeerSharing (mini-protocol 10, client and server)

Part of the [compatibility matrix](../compatibility.md); the register entry is `network-peersharing` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented (G1.4a/b): reference codec, 5760-byte/60-second wire limits, negotiated willingness, server publication of advertised successful zero-failure peers, 823-second salt and ten-peer cap; governor activation300/retry900/failure3600, at most two requests and objective-bounded 3/10-second completions; relay defaults on and producer defaults off, with explicit override

## Evidence

Unit/property, fixed-seed real-governor simulation, four-seed decoder fuzz, CDDL cross-check and actual nonempty two-way private Haskell discovery (evidence below and design7f). Original IPv4 words are converted to socket octets; IPv6 words retain network order

## Explicitly unchecked or unconfirmed

Decoder array arities are stricter than upstream; platform-specific address hashing uses Scala hashing with the same sticky-salt policy. Governor requests use the outbound live table; accepted-connection reuse for an outbound job remains a separate G2 question. Historical Preview predates the new policy; no public run of new sharing is claimed
