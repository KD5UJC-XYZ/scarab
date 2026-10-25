# 0024. H2 adopts the upstream KES agent as it is: the client only, and no remote signing

- **Status**: Accepted
- **Date**: 2026-10-25
- **Issue**: [#38](https://github.com/KD5UJC-XYZ/scarab/issues/38)

## Context

Integrating the KES agent (H2) can mean three different pieces of work, and they are often conflated:

1. **A client** in the node that speaks the agent's service protocol and signs locally with the key it
   receives.
2. **An agent of our own**, in Scala, Rust or anything else, replacing the upstream Haskell process.
3. **Remote signing**, where the agent signs block headers and the key never enters the node.

The reference does (1) only. Its agent hands the key over (`PraosCredentialsAgent`, then
`HotKey.mkDynamicHotKey`), and the node signs. Other implementations chose differently: Blink Labs ship a Go
replica of the agent (`bursa kes-agent`) with a JSON protocol of their own and an added `sign` mode for their
Go node (dingo), and Amaru has no agent at all, signing inside its forging process.

The memory question decides nothing here, because it is the same in all three: with `serve-key` the node holds
the key to sign, whatever language the agent is written in. A Rust agent would still push the key into the
JVM. Only (3) removes it, and (3) is a different protocol from the upstream one.

## Decision

H2 is **(1) only**.

- **Adopt the upstream agent as it is**: `input-output-hk/kes-agent`, pinned by release and digest. Scarab
  writes no agent and ships no agent.
- **No remote signing** (`sign` mode) for now. It is too large a deviation from the upstream protocol: a
  protocol of our own, a per-block round trip, and a change in availability — a node whose agent dies keeps
  forging on the key it holds today, and could not forge at all under remote signing.
- **The node's in-memory copy is accepted and documented.** It is the current evolution, in the JVM heap,
  replaced rather than accumulated and forgotten on a drop. It is not mlocked, and the design claims no
  parity with the agent's mlocked buffers. The mitigation is the operational hardening in the design:
  swap, hibernation and core dumps disabled, `-XX:-HeapDumpOnOutOfMemoryError` and
  `-XX:+DisableAttachMechanism` in the shipped image.

## Consequences

- The node interoperates with any agent release that speaks `Service:2.0` (every `kes-agent` since 1.0.0.0),
  and the operator uses upstream `kes-agent-control` for key management.
- Our security posture is bounded by the reference's design on the node side. We say so in the design and in
  the operator documentation rather than implying the key is protected in memory.
- Revisit if the threat model (O1/#74) finds the in-process copy unacceptable, or if upstream adds a signing
  mode we could conform to. Writing an agent of our own would be a new decision, not a change to this one.
