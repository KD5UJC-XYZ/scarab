# 0023. Two KES credential sources: the file path stays as ADR 0015 defines it, and the agent path is the forward-secure one

- **Status**: Accepted
- **Date**: 2026-10-25
- **Issue**: [#38](https://github.com/KD5UJC-XYZ/scarab/issues/38)

## Context

The upcoming KES agent work (H2, [#38](https://github.com/KD5UJC-XYZ/scarab/issues/38)) raised whether a node
needs two ways to hold a KES signing key at all. The reference has two, and it labels them very differently:
`PraosCredentialsSource` (ouroboros-consensus `release-ouroboros-consensus-4.2.1.0`,
`Ouroboros/Consensus/Protocol/Praos/Common.hs`) has `PraosCredentialsUnsound` for a key file and
`PraosCredentialsAgent` for a socket. The file constructor's own documentation says it "does not provide
mlocking guarantees, violating the rule that KES secrets must never be stored on disk, but allows the sign key
to be loaded from a local file. This method is provided for backwards compatibility."

Scarab's file path is not the reference's file path. [ADR 0015](0015-kes-key-written-back-and-checked-reload.md)
writes the evolved key back and erases its predecessor, so the file holds the current evolution rather than the
period-0 seed the reference keeps for ever. A reader of our file can sign for the current and future periods;
a reader of the reference's file can sign for every period, past and future.

## Decision

Two credential sources stay, and they are not equal in standing.

1. **Both paths are kept.** The file path (`--kes-skey` with `--opcert`) and the agent path
   (`--kes-agent-socket` with `--opcert`) are both supported, as they are in the reference. Passing both is a
   configuration error in Scarab rather than a silent precedence.
2. **The file path stays exactly as ADR 0015 defines it.** We keep the write-back and the erasure of the
   predecessor. We do **not** adopt the reference's period-0 retention: it is strictly weaker at rest, and
   upstream's own name for it (`Unsound`) is the reason the agent path exists.
3. **The agent path is the forward-secure path and the recommended one** for any pool that runs longer than
   one KES key. The file path is documented as the compatibility path and logs a one-time warning on a public
   network. Recommendation by documentation, not by refusing the configuration, because the reference accepts
   it and parity (#46, #222) is a requirement.
4. **Neither path is removed** by this decision, and there is no automatic fallback between them. Removing
   either is a new decision with its own record.

## Consequences

- Two paths to test and document. The file path keeps its existing suites and the `kes-rotate` scenario; the
  agent path adds its own, specified in [the design](../design/kes-agent.md).
- The demotion is a documentation change, so it lands when the agent path exists rather than before: a node
  cannot be told to prefer a mode it does not have.
- A node in agent mode has no KES material on disk, but makes no mlocked-memory claim; the file path's
  durability protocol remains as ADR 0015 describes, including its typed failure modes.
- Revisit if the reference deprecates its file constructor, or if a public-network deployment shows the file
  path being used where an agent was available. A future removal would supersede this record rather than edit
  it.
