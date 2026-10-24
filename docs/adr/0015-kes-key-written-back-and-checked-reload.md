# 0015. The evolved KES key is written back, and credentials are reloaded on SIGHUP only after a check

- **Status**: Accepted; file-backed writeback retained by owner review on 2026-10-19. A separate KES agent is tracked in [H2 / #38](https://github.com/KD5UJC-XYZ/scarab/issues/38).
- **Date**: 2026-10-17
- **Issue**: [#116](https://github.com/KD5UJC-XYZ/scarab/issues/116)

## Context

The Haskell node reads the period-0 KES key file at every start and evolves the key in memory only
(ouroboros-consensus-protocol `HotKey.hs`, `evolveKey`, at the release
`release-ouroboros-consensus-4.2.1.0` that cardano-node 11.1.3 requires); the file is never rewritten, so the period-0 key
stays on disk for ever and the forward security that key-evolving signatures exist for is lost to anyone who
reads the disk. On SIGHUP it reads the credentials again, checks only that the KES key is the one the certificate names
(`opCertKesKeyCheck`) and hands them to the consensus layer (cardano-node 11.1.3 `Run.hs`,
`updateBlockForging` and `setBlockForging`); a certificate that the chain will refuse, for its signature,
window or counter, shows up later as rejected blocks. The owner wants KES rotation fully automated for the
test-network pool (#116).

## Decision

1. When the KES key moves to a new period it is written back to the `--kes-skey` file before it signs in
   that period, and the key it replaces is overwritten: create the staged file beside it, write every byte,
   force the file, decode and validate its structure, verification key, period and serialized bytes, then
   force the containing directory. Only then overwrite every predecessor byte with zeros and force that
   file, atomically rename the staged file over it, and force the directory again. Directory-sync failures
   are typed persistence failures; the current terminal key-update policy prevents further signing
   and stops the node. Recovery is performed on a subsequent start, not by continuing to forge. The file stays a
   `cardano-cli` envelope;
   the period is read off the key (the erased `r1` seeds spell it in binary), so no persisted format changes
   and [ADR 0006](0006-versioned-formats-migrate-on-open.md) needs no migration. For a process crash, the
   validated stage and its directory entry are durable before erasure, so after erasure recovery still has
   the replacement until rename. The next start finishes a valid replacement or discards a torn or foreign
   stage. If rename succeeded but its directory force failed, a retry forces the directory even when the
   requested key is already at the main path.
2. On SIGHUP the node reads the key and certificate again and switches only after checks that mirror the
   chain's own rules (the cold key is the pool's, the certificate names the key and carries the cold
   signature, the period is inside the window, the key is not past the period, the counter is the chain's or
   one above, by the same `OCertRules.checkCounter` the header validation uses). A refusal keeps the old
   credentials and is a typed event. Signing and reloading share a lock.
3. The cold key is used only by the rotation driver (`scripts/pool/rotate_kes.py`), never by the node. A test
   network may keep it online for the driver; a mainnet pool runs the same steps by hand offline.

## Consequences

- The process-crash guarantee follows from the operation order and recovery checks. Power-loss durability
  depends on the file system, mount, device and storage controller honoring file and directory force and
  same-filesystem atomic rename. A failed or unsupported directory force is reported; persistence is not
  called durable in that case. Injected tests cover process-crash boundaries and sync failures, not physical
  power loss.
- After successful persistence, the live file contains the latest evolved state rather than the
  provisioned period-0 key. Under the assumption that earlier material has not been retained, this
  reduces what a later read of that path reveals. It is not an unconditional forward-security or
  physical secure-erasure guarantee: copies, backups, filesystem snapshots and retained blocks are
  outside that live-path replacement. The owner accepts this file-backed tradeoff for now.
- Use an encrypted filesystem and restrict access to the key directory. Encryption protects stored
  data under its key-management assumptions; it does not prevent a reader of the mounted filesystem
  from copying plaintext keys, or make every earlier copy inaccessible. A separate upstream KES
  agent process is the planned way to keep generated/evolved signing keys out of persistent storage
  ([#38](https://github.com/KD5UJC-XYZ/scarab/issues/38)).
- The key file must be writable by the node. A write failure is reported (`kesPersistFailed`) and prevents further signing.
- A bad rotation cannot take the pool off the air: the node keeps what it has and the driver restores the
  previous pair. The Haskell node would have switched.
- Revisit if a KES agent (H2) manages secret lifecycle and distribution. The node still performs KES
  evolution and block signing locally; remote signing is not part of the plan. Any agent-backed
  persistence boundary must be reviewed explicitly before changing this file-backed policy.

## Error representation (2026-10-18, #82)

KES signing-key validation returns closed cases for missing material, incorrect lengths, periods,
seed state and verification-key consistency. Consistency failures carry the tree depth and, when
relevant, the local period and seed state. `ForgeError.KesKey` retains the cause and file name when
loading; `ForgeError.KesEvolution` retains it when advancing the key. Messages are rendered for
presentation. Key bytes, validation order, erasure and evolution are unchanged.

The rest of the string-error migration is tracked by #82; infrastructure failures and defects of
the forger were the G7-F3 follow-up in [#180](https://github.com/KD5UJC-XYZ/scarab/issues/180), which is closed: the
forger stops on storage failures, evaluator unavailability and defects, and a KES persistence failure
(`ForgeError.KesFileFailure`) prevents further signing (roadmap G7).
