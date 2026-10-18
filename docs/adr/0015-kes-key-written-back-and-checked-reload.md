# 0015. The evolved KES key is written back, and credentials are reloaded on SIGHUP only after a check

- **Status**: Accepted
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
   that period, and the key it replaces is overwritten: stage the new key beside the file, force it, overwrite
   the old key with zeros, rename atomically, force the directory. The file stays a `cardano-cli` envelope;
   the period is read off the key (the erased `r1` seeds spell it in binary), so no persisted format changes
   and [ADR 0006](0006-versioned-formats-migrate-on-open.md) needs no migration. A crash at any step leaves
   a complete key and the next start finishes or discards the interrupted replacement.
2. On SIGHUP the node reads the key and certificate again and switches only after checks that mirror the
   chain's own rules (the cold key is the pool's, the certificate names the key and carries the cold
   signature, the period is inside the window, the key is not past the period, the counter is the chain's or
   one above, by the same `OCertRules.checkCounter` the header validation uses). A refusal keeps the old
   credentials and is a typed event. Signing and reloading share a lock.
3. The cold key is used only by the rotation driver (`scripts/pool/rotate_kes.py`), never by the node. A test
   network may keep it online for the driver; a mainnet pool runs the same steps by hand offline.

## Consequences

- Forward security holds on disk for the node's key. A copy-on-write or journalling file system may keep
  overwritten blocks; the docs say to use an encrypted volume where that matters.
- The key file must be writable by the node. A write failure is reported (`kesPersistFailed`) and does not stop
  forging.
- A bad rotation cannot take the pool off the air: the node keeps what it has and the driver restores the
  previous pair. The Haskell node would have switched.
- Revisit if a KES agent (H2) supplies the key: the agent owns evolution and the file is not written then.

## Error representation (2026-10-18, #82)

KES signing-key validation returns closed cases for missing material, incorrect lengths, periods,
seed state and verification-key consistency. Consistency failures carry the tree depth and, when
relevant, the local period and seed state. `ForgeError.KesKey` retains the cause and file name when
loading; `ForgeError.KesEvolution` retains it when advancing the key. Messages are rendered for
presentation. Key bytes, validation order, erasure and evolution are unchanged.

The rest of the string-error migration is tracked by #82; infrastructure failures and defects of
the forger remain the separate G7-F3 follow-up in [#180](https://github.com/KD5UJC-XYZ/scarab/issues/180).
