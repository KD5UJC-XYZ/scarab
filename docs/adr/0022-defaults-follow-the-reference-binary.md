# 0022. Defaults follow the reference binary

- **Status**: Accepted
- **Date**: 2026-10-23
- **Issue**: [#394](https://github.com/KD5UJC-XYZ/scarab/issues/394)

## Context

[ADR 0001](0001-haskell-node-as-reference.md) makes the Haskell node the reference design. It does
not say what Scarab does with a setting that nothing supplies, nor what it does with a setting an
operator's configuration file does supply. `ConsensusMode` is the first place the question mattered,
because the two answers in the reference differ:

- **Code default.** `defaultConsensusMode = PraosMode`, in ouroboros-network
  `cardano-diffusion/api/lib/Cardano/Network/ConsensusMode.hs` at the pinned revision `2fb69829`.
  cardano-node 11.1.3 (`cardano-node/src/Cardano/Node/Configuration/POM.hs`, the default
  `PartialNodeConfiguration`) sets `pncConsensusMode = Last (Just defaultConsensusMode)`, so a
  node started without the key runs in Praos mode. The in-tree `configuration/cardano/mainnet-config.json`
  of 11.1.3 also says `PraosMode`.
- **Published operator configurations.** The files published on `book.world.dev.cardano.org` for
  mainnet, preprod and preview say `GenesisMode` (as of 2026-10-23; these files are not pinned and
  can change).

Scarab reads `ConsensusMode` from `--config` and defaults to `PraosMode` when it is absent
(`NodeConfig`). The README quick start passes `--genesis-dir --peer`, which has no configuration file
and so runs in Praos mode. No record stated that this was intended.

## Decision

The general rule: **a code default mirrors what cardano-node 11.1.3 does when nothing sets the
value; a supplied operator configuration is honoured as written.** A default is not tuned toward
what the published files happen to say, and a supplied value is not overridden to match a default.

`ConsensusMode` is the first instance. The owner decided on 2026-10-23 to match the reference:
`PraosMode` when no configuration sets it, and the file's `ConsensusMode` when `--config` is given.

Because Praos mode from an empty chain, with nothing marked trusted, is a weaker way to start than
the published files' Genesis mode, the node says so once at start-up: a `praosSyncUntrusted` event
(`Node.PraosSyncUntrusted`, Warning, [ADR 0014](0014-typed-log.md)) when the mode is Praos, the chain
is empty or more than an epoch behind the clock, and the topology names no bootstrap peer and no
`trustable` local root (`SyncAdvisory`). The reference has no equivalent: its start-up topology
checks (`cardano-node/src/Cardano/Node/Configuration/TopologyP2P.hs`, `readTopologyFile`) warn about
ledger peers, peer snapshots and bootstrap peers incompatible with Genesis mode, and none concerns
syncing from an empty chain in Praos mode. The warning is therefore Scarab's own
and informs only; it changes no behaviour.

## Consequences

- A user who copies a published configuration gets `GenesisMode`; one who runs the quick start gets
  `PraosMode` and, from an empty database, the warning. `docs/running/configuration.md` ("Choosing the consensus
  mode") says how to pick.
- The README quick start keeps its current form until the published configuration can be used there.
  Related work: [#383](https://github.com/KD5UJC-XYZ/scarab/issues/383) (the Genesis runtime replay
  must be deterministic) and [#343](https://github.com/KD5UJC-XYZ/scarab/issues/343) (the GSM oracle)
  bear on how far the Genesis path is relied on; the quick start moves to the published configuration
  when that work allows it.
- Later settings with a reference default and a differing published value are decided under the
  same rule; today `ConsensusMode` is the only instance.
- A change of the reference's default in a later cardano-node release is a change here only through
  a new pin ([ADR 0001](0001-haskell-node-as-reference.md)) and a note that supersedes this one.
