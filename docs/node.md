# Scala node (`node` module)

A Cardano node core written as tagless-final cats-effect programs. It follows a peer from genesis,
validates Praos headers and applies the Conway ledger rules. Every retained state can be queried,
so it can be compared byte for byte against a reference `cardano-node`.

## Architecture

| Layer | Where | Style |
|---|---|---|
| Errors | `NodeError.scala` | Closed hierarchy: `PeerError` (recoverable), `ChainError`, `ValidationError`, `ConfigError`. Raised via cats-mtl `Raise`, handled via lexically scoped `Handle.allow` |
| Peer | `Peer.scala` | `Peer[F]` algebra (intersect / next / fetch) with a node-to-node v14 interpreter over `network-runtime` |
| Chain | `VolatileChain.scala` | Pure last-k chain of `(block, state)`; rollback restores the exact retained state |
| Follower | `Follower.scala` | Program over `Stateful[F, VolatileChain[S]]` and `BlockRules[F, S]`; `supervise` reconnects on `PeerError` |
| Consensus | `consensus/Praos.scala` | VRF leader check, KES, opcert counters, nonce evolution (evolving / candidate / epoch / lab / last-epoch-block) |
| Ledger | `ledger/` | Pure functions: transaction decoding, UTXOW/UTXO/CERTS rules, deposits, withdrawals, the EPOCH rule (rewards, SNAP, POOLREAP, obligation, dormant epochs), the reward calculation |
| Block rule | `LedgerRules.scala` (`NodeRules`) | Tick ledger and Praos to the block's epoch, validate the header, apply the body |
| Query | `Query.scala` | `ChainView[F, S]` plus a loopback http4s server: `GET /tip`, `GET /state?slot=&hash=` |

All configuration (epoch geometry, k, f, KES parameters, protocol parameters, initial UTxO and
staking) is read from the genesis files. Nothing assumes a particular network. Ledger arithmetic
uses an exact `Rational` with Haskell `floor` semantics.

Laws and properties: the `Ref`-backed `Stateful` passes the cats-mtl Stateful laws, `Rational`
passes the cats-kernel Order laws, and the ScalaCheck properties cover rollback, reward bounds and
exactness. Scripted-peer tests cover forks, reconnects and fatal rule violations.

## Running a local soak (Docker only)

Prerequisite: the `cardano-reference-11.1.3:local` image (see [reference image](reference-image.md)).

```sh
bash scripts/sbtw node/compile node/runtimeClasspathFile
python3 scripts/devnet.py soak --seconds 480     # up + follow + load + compare
python3 scripts/devnet.py down                   # remove containers and run data
```

`devnet.py` starts a 3-pool `cardano-testnet` cluster (k = 5, f = 0.05, 500-slot epochs of 0.1 s)
and the Scala node, which shares the reference container's network namespace. It also runs a
load generator that sends ADA transfers, stake registration with pool and DRep delegation,
re-delegation, full-balance reward withdrawals and deregistration. Every `--every` seconds it
reads the reference tip, queries `ledger-state`, `utxo --whole-utxo` and `protocol-state`, then
re-reads the tip. When the tip is unchanged, it fetches the Scala node's state at exactly that
block and diffs everything. Run data lives in `${CSL_DATA_DIR:-/tmp/cardano-scala-lab}/<name>`.
The node runs from a frozen copy of the compiled classes.

## Verified result (2026-10-10)

An 8-minute soak from genesis matched the reference exactly at **23/23** comparison points.
Details:

- **Chain:** epochs 0–9, 270 blocks, 2 fork rollbacks and 4 reconnects.
- **Transactions:** 432 accepted: 402 transfers, 6 registrations, 13 delegations,
  8 withdrawals and 3 deregistrations.
- **What each comparison covers:**
  - whole UTxO, fees, deposits, treasury and reserves
  - accounts, pools and DReps
  - mark/set/go snapshots and the fee snapshot
  - block counts, the reward update and the pool distribution
  - all five Praos nonces and the opcert counters

## Not yet implemented

The following are rejected as `Unsupported`, so the node stops rather than diverging:

- **Scripts:** Plutus and native scripts.
- **Assets and governance:** minting, governance proposals and votes, committee certificates and treasury donations.
- **Byron:** spending Byron-address outputs.

Also not implemented:

- **Persistence:** none; the node replays from genesis on start.
- **Peers:** a single upstream peer only.
- **Rewards and governance:** non-myopic pool ranking, the DRep pulser and ratification state.
- **Production:** block production and a mempool.
