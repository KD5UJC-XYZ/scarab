<!-- SPDX-License-Identifier: Apache-2.0 -->
# Node-to-client and the query server

Part of [Running the node and the devnet scenarios](../running.md).

## Node-to-client

With `--local-socket <path>` the node serves four node-to-client protocols on a Unix socket: LocalStateQuery,
LocalTxSubmission, LocalTxMonitor and ChainSync of whole blocks. The reference image's `cardano-cli` can talk
to it:

```sh
docker run --rm -v <socket dir>:/sock --entrypoint /opt/reference/bin/cardano-cli cardano-reference-11.1.3:local \
  conway query tip --testnet-magic <magic> --socket-path /sock/node.socket
```

Answered, with CBOR equal to the reference node's at the same block (the `ntc` scenario below):
`tip`, `protocol-parameters`, `utxo` (whole, by address, by input), `stake-distribution`, `stake-pools`,
`pool-params`, `pool-state`, `stake-snapshot`, `stake-address-info`, `gov-state`, `drep-state`,
`drep-stake-distribution`, `committee-state`, `constitution`, `ratify-state`, `future-pparams`,
`treasury`, `spo-stake-distribution`, `protocol-state`, `era-history`, `slot-number`, and the queries
behind them (see [architecture.md](../architecture/node-to-client.md#node-to-client---local-socket)). `ledger-state`
(`DebugNewEpochState` and `DebugEpochState`, also as `GetCBOR`) is answered from the mirror of the Haskell
state, in every Shelley-based era, without the UTxO, which the reference leaves out too (`map(0)`).
The `ntc` gate requires the complete answer bytes to match at the same acquired point. It does not
mask historical state gaps: any remaining difference fails the comparison and must be resolved.
Earlier qualified measurements are retained in [compatibility.md](../compatibility.md). Not answered yet
(the connection ends, the log line `inbound ... closed` names the query): `GetRewardInfoPools`, `GetNonMyopicMemberRewards`,
`GetRewardProvenance`, `GetLedgerPeerSnapshot`, `DebugLedgerConfig`, and every query but `ledger-state` in an era other than
Conway (roadmap F1). A transaction refused over the socket carries the ledger's predicate failure, so `cardano-cli
transaction submit` prints the same error as against the Haskell node.

Fresh native genesis retains injected pool records with an empty VRF registry, matching the
reference's genesis injection. The same-point `ntc` regression for this initialization fix compares
all four ledger-state wrappers as raw bytes and checks CLI `ledger-state` output. Existing database
state is restored as stored; historical provenance remains [#129](https://github.com/KD5UJC-XYZ/scarab/issues/129).

If this build cannot decode or evaluate a script, it has no ledger rejection to return. Local submission
ends that connection with a typed service-unavailable outcome and sends no `ApplyTxErr` bytes. The
listener and node continue serving fresh clients. Unavailable validations are not remembered in the
recent-refusal cache, so recovery of the backend permits a fresh admission attempt. This differs from adopting a block: the node cannot
continue its chain without that block's consensus verdict, so the same implementation limit there stops
it with exit 8 (see "Failures that stop the node").

LocalTxMonitor (mini-protocol 9) gives `cardano-cli conway query tx-mempool info | next-tx | tx-exists` and
the protocol's `GetMeasures` (no CLI command): a client acquires a snapshot of the mempool (the slot after
the tip, the transactions in arrival order), walks it with `NextTx`, asks `HasTx`, `GetSizes` and
`GetMeasures`, and `AwaitAcquire` waits until the pool differs. The sizes are those of the reference's server:
each transaction counts for its size plus 4 bytes, and the capacity is a whole number of block-sized units
(`2 * (maxBlockBodySize - 1024)` without an override, as the reference; `ceil(--mempool-bytes / unit)` units with
one, so the same `--mempool-bytes` gives the same `capacityInBytes`). The pool enforces that capacity in all four
components (bytes, execution-unit memory and steps, reference-script bytes). A transaction that fits an empty pool
but not the room left waits for the pool to change instead of being refused, as the reference does, whether it
comes from a peer (TxSubmission2: the peer's replies are held back meanwhile, which a peer's own timeouts allow) or
from a local client (`cardano-cli transaction submit` waits for its answer); the `mempoolWaiting` log line marks
the start of a wait. Local clients are served before peers that queue, a closed client or lost peer leaves the line,
and one transaction larger than the whole capacity is refused at once as "mempool full" (the reference would wait for
ever, [IN-82](../implementer-notes.md)).
Local ChainSync (mini-protocol 5, what db-sync, Ogmios and `cardano-cli`'s followers use) serves each block as
`#6.24(bytes)` of the block as stored (`[era, block]`), from the immutable store (with `--db`) and the volatile
chain, with the same intersection, roll-forward, roll-backward and await behaviour as on node-to-node.
Without `--db` only the last `k` blocks can be served, so a client that starts from Origin once the chain is
longer than that is told there is no intersection.

## Query server

`GET /tip` and `GET /state?slot=<n>&hash=<hex>` serve the node's projection of the ledger and
consensus state at a block; the devnet comparator diffs this against the Haskell node. It binds to
loopback and is a development interface, not a stable API. `GET /scheduling` serves the scheduling
measurements and, on a node started with `--forge`, `GET /forging` the forger's counters per epoch and in
total (`slotsChecked`, `leaderSlots`, `forged`, `adopted`, `lostSlotBattles`, `failed`, `skipped` and
`skippedByReason`, `forgeLatencyMs` from the slot's start to adoption); the same counters of an epoch are
logged as a `forgeEpoch` line when the next epoch begins. `GET /metrics` serves the Prometheus text format
(next section).

`GET /state` without point parameters reads the tip. An explicit point requires one unsigned 64-bit
`slot` and one 32-byte hex `hash`: missing, duplicate or malformed point parameters return HTTP 400.
A well-formed point outside the retained chain returns HTTP 404.

`GET /state` carries no UTxO entries by default: `utxoSummary` has the entry count and total lovelace, read from the
state's own totals without touching the store. `utxo=full` (the one explicit opt-in, as `utxo=summary` is the default)
adds every entry under `utxo` for a set of at most 100,000 entries; a larger set returns HTTP 413 rather than being
built in memory. Any other or repeated `utxo` value returns HTTP 400. The devnet comparator asks for `utxo=full`.
`--query-listen off` leaves the server off.

The slot clock starts at the Byron genesis' `startTime` when `byron-genesis.json` has one (as the
reference's does), else at the Shelley `systemStart`; the `started` line logs it as `systemStart`.
Write both identically in a network you make yourself.
