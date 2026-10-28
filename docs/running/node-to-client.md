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

### The socket path

As in the reference node, whatever is at the socket path (`--local-socket`, or the configuration's
`SocketPath`) is removed at start-up, once the configuration, the genesis and, with `--forge`, the VRF
key's permissions and the credentials are read, and before the topology and the database are
(`removeStaleLocalSocket` in `gatherConfiguredSockets`, cardano-node 11.1.3). It is an `unlink` of the
path, so the directory needs write and search permission only: a socket left by an earlier run, a file or
a symbolic link (the link, not its target) goes, and nothing there is fine. A path that ends in `/` names
a directory, as `unlink` takes it: something other than a directory there refuses the start (`ENOTDIR`),
and with nothing there the bind fails later. Any other failure refuses the start: a directory at the path, empty or not, is
left, and the start ends with one `socketConfigError` line whose `error` is the reference's
(`LocalSocketError "<path>" <path>: removeLink: inappropriate type (Is a directory)`) and exit code 1.
The path is used as written, not normalised.

The socket itself is set up once the node runs, as the reference's network layer sets it up
(`withLocalSocket`, ouroboros-network): created, bound, checked, then listened on with a backlog of 8,
with the reference's `Startup.DiffusionInit` lines (`createSystemdSocketForSnocketPath`,
`createdLocalSocket`, `configuringLocalSocket`, `configuredLocalSocket`, `listeningLocalSocket`,
`localSocketUp`, `runLocalServer`; in [logging](logging.md#log-events)). No mode is set on the socket,
so it has the one the process umask gives it; who can connect is decided by that mode and by the
directory that holds the socket. The check after the bind only warns, when others may read or write the
socket or when the group or others may write its directory (`checkLocalSocket`): the
`insecureLocalSocketPermissions` and `insecureLocalSocketDirectory` lines. A bind that fails (a
directory that is not there or that the node may not write, a path of more than 108 characters), or a
status of the socket or its directory that cannot be read, stops the node with one `diffusionErrored`
line carrying the reference's text (`Network.Socket.bind: does not exist (No such file or directory)`)
and exit code 1. A start that fails, here or in the removal above, also says why on standard error,
as the reference's top-level handler does.

One approved deviation (owner decision recorded on [#474](https://github.com/KD5UJC-XYZ/scarab/issues/474)):
the JDK binds and listens in one call, so the socket listens before the check after the bind rather than
after it, and a client that connects in between waits in the backlog instead of being refused; the lines
and the failures keep the reference's order. systemd socket activation (a socket passed in `LISTEN_FDS`)
is not supported yet: [#542](https://github.com/KD5UJC-XYZ/scarab/issues/542).

Clients are accepted without a limit on their number (the reference's local
`AcceptedConnectionsLimit maxBound maxBound 0`), each named `<path>@<n>` as the reference names them
(`acceptConnection`, a Debug line). An accept that fails is an `acceptError` line: after `ECONNABORTED`
(the client gave up first) the server accepts again half a second later; any other failure stops the
server (`serverStopped`, `serverError`) and the node (`diffusionErrored`, exit code 1). Whatever ends a
client's connection ends that connection only, never the node: the reference's policy for local
connections (`rethrowPolicy <> mempty`) stops the node only for a Windows I/O manager error. A storage
failure, an internal failure or an unexpected exception while serving a client closes it with an
`inbound ... closed` line naming the cause. When the node
stops, the socket is closed and its file is left where it is, as the reference leaves it; the next start
removes it. The error texts are the reference's: GHC's kind of the `errno` and musl's text for it (the
reference's release binary is a static musl build), whatever C library the JVM runs on.

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
