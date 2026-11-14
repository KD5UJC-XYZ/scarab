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

The reference does (1) only. Its agent hands the key over (`PraosCredentialsAgent`, then the `HotKey` module's
`mkDynamicHotKey`), and the node signs. Other implementations chose differently: Blink Labs ship a Go
replica of the agent (`bursa kes-agent`) with a JSON protocol of their own and an added `sign` mode for their
Go node (dingo), and Amaru has no agent at all, signing inside its forging process.

The memory question decides nothing here, because it is the same in all three: with `serve-key` the node holds
the key to sign, whatever language the agent is written in. A Rust agent would still push the key into the
JVM. Only (3) removes it, and (3) is a different protocol from the upstream one.

## Decision

H2 is **(1) only**.

- **Adopt the upstream agent as it is**: `input-output-hk/kes-agent`, pinned by ~~tag and~~ commit ([amendment](#amendment-2026-11-07-the-pin-and-the-jvm-flags)), **republished
  by our own dispatch workflow** to `ghcr.io/kd5ujc-xyz/scarab-kes-agent` and pulled by digest, with the
  two-stage recipe under `scripts/` as the fallback for hosts that cannot pull and as the
  reproducibility check. The release assets are unusable: 1.4.0.0 has none, and the 1.1.0.0 binary is a Nix
  closure that does not run on an ordinary host. Scarab writes no agent; it redistributes an unmodified
  Apache-2.0 build with the licence and notice carried in the image, which is not the same as shipping a
  supported agent.
- **No remote signing** (`sign` mode) for now. It is too large a deviation from the upstream protocol: a
  protocol of our own, a per-block round trip, and a change in availability — a node whose agent dies keeps
  forging on the key it holds today, and could not forge at all under remote signing.
- **The node's in-memory copy is accepted and documented.** It is the current evolution, in the JVM heap,
  replaced rather than accumulated and forgotten on a drop. It is not mlocked, and the design claims no
  parity with the agent's mlocked buffers. The mitigation is the operational hardening in the design:
  swap, hibernation and core dumps disabled, ~~`-XX:-HeapDumpOnOutOfMemoryError`~~ `-XX:-CreateCoredumpOnCrash`
  ([amendment](#amendment-2026-11-07-the-pin-and-the-jvm-flags)) and `-XX:+DisableAttachMechanism` in the shipped image.

## Consequences

- The node interoperates with any agent release that speaks `Service:2.0` (every `kes-agent` since 1.0.0.0),
  and the operator uses upstream `kes-agent-control` for key management.
- Our security posture is bounded by the reference's design on the node side. We say so in the design and in
  the operator documentation rather than implying the key is protected in memory.
- Revisit if the threat model (O1/#74) finds the in-process copy unacceptable, or if upstream adds a signing
  mode we could conform to. Writing an agent of our own would be a new decision, not a change to this one.

## Amendment (2026-11-07): the pin and the JVM flags

- **The pin is a commit, not a tag.** cardano-node 11.1.3 requires `kes-agent ^>=1.3`, which resolves to
  1.3.0.0, a CHaP release with no git tag. CHaP records the commit it was cut from
  (`19ffbb01d4a6514b67af62d59b3cd4e0b4682beb`, `_sources/kes-agent/1.3.0.0/meta.toml`), and the republished
  image is built from that commit. The `kes-agent-1.4.0.0` tag the design first pinned is outside the range;
  the design still cites its file paths, whose wire-relevant files match 1.3.0.0's.
- **The JVM flag.** `-XX:+HeapDumpOnOutOfMemoryError` is off by default, so disabling it does nothing; the
  flag the shipped image sets is `-XX:-CreateCoredumpOnCrash`, beside `-XX:+DisableAttachMechanism`, as
  [the design](../design/kes-agent.md#security-what-conformance-does-and-does-not-buy) already says.

The rest of the decision stands: the client only, the upstream agent unmodified, no remote signing, and the
in-process copy accepted and documented.
