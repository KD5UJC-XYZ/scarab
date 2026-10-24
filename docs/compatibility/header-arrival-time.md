<!-- SPDX-License-Identifier: Apache-2.0 -->
# Header arrival time (wall-clock eligibility)

Part of the [compatibility matrix](../compatibility.md); the register entry is `header-arrival-time` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented (AUD-01, #153): a header whose slot begins within 2 s of its arrival (the reference's `defaultClockSkew`) is held until its onset and then validated; one that begins later is `PeerError.InvalidHeader(HeaderError.FarFuture)`, classified `Misbehaved`, so the peer is forgotten; applies to live ChainSync with or without `--header-first`; the check is the reference's strict `skew < onset - arrival`

## Evidence

`HeaderClockSuite` (boundaries at the skew in ms, property over the verdict, clock steps both ways, reconnection, restored and old headers, overflow-safe slots, derived Byron span), `InboundGovernorSuite` (a peer serving future headers is dropped, a healthy peer followed); devnet `relay`, `peercut`, `pool`, `e2e` unchanged; read against ouroboros-consensus 4.2.1.0 `InFutureCheck.hs` 134-211, `Client.hs` 1491-1518 and 1759-1776, `Node.hs` 589, 1107 (identical on master 5.0.0.0)

## Explicitly unchecked or unconfirmed

arrival is read once per batch, not per header; the headers before a waiting one are released with it (at most 2 s later); while the Byron span is derived a Shelley header is not judged; no scenario with a skewed producer (needs a test-only forger flag); the Genesis historicity check is G3
