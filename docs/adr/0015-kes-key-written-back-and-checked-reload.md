# 0015. The evolved KES key is written back, and credentials are reloaded on SIGHUP only after a check

- **Status**: Accepted
- **Date**: 2026-10-17
- **Issue**: [#116](https://github.com/KD5UJC-XYZ/scarab/issues/116)

## Context

The Haskell node reads the period-0 KES key file at every start and evolves the key in memory only
(ouroboros-consensus-protocol `HotKey.hs`, `evolveKey`); the file is never rewritten, so the period-0 key
stays on disk for ever and the forward security that key-evolving signatures exist for is lost to anyone who
reads the disk. On SIGHUP it reads the credentials again and hands them to the consensus layer without
looking at them (cardano-node `Run.hs`, `updateBlockForging` and `setBlockForging`); a certificate that the
chain will refuse shows up later as rejected blocks. The owner wants KES rotation fully automated for the
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
