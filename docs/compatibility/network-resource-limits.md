<!-- SPDX-License-Identifier: Apache-2.0 -->
# Network resource limits (G5)

Part of the [compatibility matrix](../compatibility.md); the register entry is `network-resource-limits` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: per-state byte/time bounds, byte-counted nonblocking ingress, finite64-message egress queue, absolute partial-SDU/write deadlines and finite TxSubmission credit10; completed decodes cannot bypass byte limits; closed mux framing/limit errors retain their payloads through the network and node (#82)

## Evidence

`MuxDeadlinesSuite`, `TxSubmissionLimitsSuite`, `MuxSuite`, `ClientSuite`, `ServerSuite`, `PeerSuite`: deterministic slow-loris, writer/batch/handshake timeouts, cancellation, hostile generated request sequences, original-defect attribution, precise mux field/EOF/limit errors and preserved setup/write/framing causes with the existing node policy; required full check/fuzz/private soak/relay evidence below

## Explicitly unchecked or unconfirmed

[Reference matrix and explicit local profiles](../design/network-limits.md): native peer-trust/per-state MustReply deadlines, local Init/Idle deadlines and eight-item/524400-byte Conway TxSubmission profile; no new public run. Broader independent ownership/attribution follow-ups remain #180
