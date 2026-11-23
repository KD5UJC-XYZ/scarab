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
state, in every Shelley-based era, without the UTxO, which the reference leaves out too (`map(0)`). The UTxO
queries (`utxo` whole, by address and by input) are answered in every Shelley-based era as well, each
output in its era's encoding, and so is `GetRewardProvenance`, which the reference answers with the
default provenance whatever the state, written at the era's encoding version. So are the queries that read
nothing of the era's own: the ledger tip, the epoch, the proposed protocol-parameter updates (the empty map
whatever the state, as the reference answers), the genesis configuration (the legacy record below version
21, as in Conway), the treasury and reserves, and the maximum major protocol version, each with its
`GetCBOR` form. So are the current protocol parameters (`protocol-parameters`, in the era's encoding;
below version 21 in the legacy one the reference uses before Conway) and the governance state, each with
its `GetCBOR` form, and, from Shelley to Babbage as in Conway, the queries of the certificate state (the
delegations and reward accounts, the registered pools, their parameters (`pool-params`) and state
(`pool-state`), the stake snapshots (`stake-snapshot`) and the delegation deposits, each written in the
era's encoding), the pool distributions (`GetPoolDistr2`
and the retired `GetPoolDistr`, the pool distribution in force, every pool for an absent or empty set of
pools), the stake distributions of the current stake (`stake-distribution`, `GetStakeDistribution2` and
the retired `GetStakeDistribution`), the pools' reward information and the non-myopic member rewards
(`GetRewardInfoPools`, `GetNonMyopicMemberRewards`) and the ledger peer snapshots
(`GetLedgerPeerSnapshot`, both forms), each with its `GetCBOR` form, the ratios of the distributions
untagged before Conway as the reference writes them. The top-level `DebugLedgerConfig` is answered from
node-to-client version 20, in every era, with the ledger configuration the node read at start (the era
parameters and each era's genesis configuration, `Globals` and hard-fork trigger), as the reference
answers it; below version 20 it ends the connection, as the reference refuses it there.
The `ntc` gate requires the complete answer bytes to match at the same acquired point. It does not
mask historical state gaps: any remaining difference fails the comparison and must be resolved.
Earlier qualified measurements are retained in [compatibility.md](../compatibility.md). From Shelley to
Babbage every block query the reference's decoder takes in the era is answered, and `DebugLedgerConfig` in
every era. A query the reference's decoder refuses (a Conway-only query in an earlier era, `DebugLedgerConfig`
below version 20) ends the connection, as on the reference. Not answered yet (the connection ends, the log
line `inbound ... closed` names the query): the current protocol parameters, the governance state, the six
of the certificate state, the distributions, the wallet's queries, the ledger peer snapshots and the queries
of Conway's governance (the constitution, the DRep and committee queries, the proposals, the ratify state, the
future parameters and the pools' default votes) in Dijkstra (roadmap F1); under the Leios build
(`--reference-build leios-b004fe0c`) a Dijkstra state answers the current protocol parameters, the governance
state, the ratify state and the future parameters. Under that build a client that negotiates version
23 is answered as the Leios build answers it: the pools' state (`GetStakePoolState`) and the Leios voting
committees (`GetLeiosCommittee`) are answered, and the three queries it removed (tags 4, 5 and 21) and
`GetLedgerPeerSnapshot` without its peer kind end the connection; at versions 16 to 22 nothing changes.
A transaction refused over the socket carries the ledger's predicate failure, so `cardano-cli
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
chain, with the same intersection, roll-forward, roll-backward and await behaviour as on node-to-node. A client
that asks for the next block before any intersection is first told to roll back to Origin, with the tip, as the
reference's follower does; `cardano-cli query kes-period-info` reads the tip that way.
Without `--db` only the last `k` blocks can be served. A client that starts from Origin once the chain is
longer than that is told there is no intersection. A client whose next block has left the last `k` (one that asked
for the next block from Origin, such as `cardano-cli debug log-epoch-state`, or one more than `k` blocks behind) is
disconnected, and the node logs `the block after … is not kept`.

### The socket path

As in the reference node, whatever is at the socket path (`--local-socket`, or the configuration's
`SocketPath`, which the reference does not use; see the deviations below) is removed at start-up, once the
configuration, the genesis and, with `--forge`, the VRF
key's permissions and the credentials are read, and before the topology and the database are
(`removeStaleLocalSocket` in `gatherConfiguredSockets`, cardano-node 11.1.3). It is one `unlink(2)` of
the path, made through Java's foreign-function interface (the release JAR's `Enable-Native-Access` manifest
attribute allows it; started from a classpath, `--enable-native-access=ALL-UNNAMED` does), so the directory needs
write and search permission only: a socket left by an earlier run, a file or a symbolic link (the link,
not its target) goes, and nothing there is fine. A path that ends in `/` names a directory, as `unlink`
takes it: something other than a directory there refuses the start (`ENOTDIR`), and with nothing there
the bind fails later. Any other failure refuses the start: a directory at the path, empty or not, is
left, and the start ends with one `socketConfigError` line whose `error` is the reference's
(`LocalSocketError "<path>" <path>: removeLink: inappropriate type (Is a directory)`) and exit code 1.
A path that holds NUL refuses the start in the same way before anything is removed, with the check of
the reference's `removeLink` (`<path>: checkForInteriorNuls: invalid argument (POSIX filepaths must not
contain internal NUL octets.)`). Otherwise `unlink` would stop at the NUL and remove another file. The
path is used as written, not normalised.

The socket itself is set up once the node runs, as the reference's network layer sets it up
(`withLocalSocket`, ouroboros-network): created, bound, checked, then listened on with a backlog of 8,
with the reference's `Startup.DiffusionInit` lines (`createSystemdSocketForSnocketPath`,
`createdLocalSocket`, `configuringLocalSocket`, `configuredLocalSocket`, `listeningLocalSocket`,
`localSocketUp`, `runLocalServer`; in [logging](logging.md#log-events)). No mode is set on the socket,
so it has the one the process umask gives it; who can connect is decided by that mode and by the
directory that holds the socket. The check after the bind only warns, when others may read or write the
socket or when the group or others may write its directory (`checkLocalSocket`): the
`insecureLocalSocketPermissions` and `insecureLocalSocketDirectory` lines. A bind that fails (a
directory that is not there or that the node may not write, a path of 108 characters or more), or a
status of the socket or its directory that cannot be read, stops the node with one `diffusionErrored`
line carrying the reference's text (`Network.Socket.bind: does not exist (No such file or directory)`)
and exit code 1. A path that the JDK refuses and the reference binds (see the deviations below) stops it
the same way, but the line carries the JDK's reason in that form (`Network.Socket.bind: failed (Unix
domain path too long)`). Under a UTF-8 locale, a path beyond ASCII that is not refused for its length or a
trailing `/` stops it after `configuringLocalSocket` with the line observed for `/w/café...` on the reference's first start under
`LANG=C.UTF-8`
(`<path>: getFileStatus: does not exist (No such file or directory)`), and no socket is made. A start
that fails, here or in the removal above, also says why on standard error,
as the reference's top-level handler does. SIGINT while the node runs is a `diffusionErrored` line with
`user interrupt` and exit code 130, as the reference's `UserInterrupt`; SIGTERM ends the run without
it, as the reference's `ExitSuccess` does.

Five approved deviations, as the [decisions register](../decisions.md#networking-and-local-clients) states
them ([#949](https://github.com/KD5UJC-XYZ/scarab/issues/949), [#953](https://github.com/KD5UJC-XYZ/scarab/issues/953)):

- **Bind and listen.** The JDK binds and listens in one call. So the socket listens before the check after
  the bind rather than after it, and a client that connects in between waits in the backlog instead of being
  refused. A listen that fails is reported as the bind's failure, before that check. Whenever the listen
  succeeds, the lines and the failures keep the reference's order.
- **Path length.** The JDK binds at most 106 bytes of path, after collapsing repeated `/`, and the reference
  binds up to 107 characters. So a path of 107 ASCII characters with no repeated `/` is refused here and
  bound by the reference. From 108 characters on, both refuse an ASCII path with `pokeSockAddr`'s error.
- **A path beyond ASCII**: Scarab refuses every one, in every locale; under a locale that is not UTF-8, at
  start-up, as below. Such a path cannot work with the
  reference's own clients in every locale: their network library cuts each character down to a byte when it
  connects, as when it binds. With the socket bound at the UTF-8 bytes of `/w/café.socket`, `cardano-cli`
  11.1.3 reached it under `LANG=C` and not under `LANG=C.UTF-8` or with no `LANG`. So name the socket in ASCII.
  Runs of the reference node with `/w/café...` paths:
  - **Under `LANG=C.UTF-8` or no `LANG`:** it bound the socket under the path's characters truncated to a
    byte each, then found nothing at the path's own name and stopped, leaving the mis-named file. Scarab
    stops with the same line and makes no file. So where the reference's second start failed at its bind on
    the file its first start left (`Network.Socket.bind: resource busy (Address in use)`), or a directory
    that does not exist failed its bind, Scarab gives the first line again.
  - **Under `LANG=C`, or with a lone byte 0xE9 under either locale:** the reference kept the argument's
    bytes and served the path, where Scarab refuses it.
- **`SocketPath` in the configuration file** is the default of `--local-socket`, and the flag overrides it.
  The reference parses it and never uses it: its socket path comes from `--socket-path` only.
- **Other paths and the command line.** Any other path, such as `--config`, `--db` or a genesis file the
  configuration names, may be any valid UTF-8 under a UTF-8 locale, and must be ASCII under any other; the
  Docker image runs under a UTF-8 locale. A name holding U+FFFD is refused in every locale: the JVM decodes
  bytes it cannot read as U+FFFD, and the node reads its configuration the same way, so such a name may stand
  for another file. The node refuses to start, with exit code 2 and a line on standard error, on an argument
  it does not take, before it touches any file, and on a file name in its configuration (the genesis files,
  `SocketPath`, `DatabasePath`), before it removes or makes any file by them. Rename the path, or run the node under a UTF-8
  locale (such as `LANG=C.UTF-8`). The reference, under `LANG=C`, starts with such a path and keeps bytes that
  are not UTF-8. Standard output and error are UTF-8 whatever the locale; under `LANG=C` the reference's own
  output stops at the first character it cannot encode.

systemd socket activation (a socket passed in `LISTEN_FDS`) is not supported yet:
[#542](https://github.com/KD5UJC-XYZ/scarab/issues/542).

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
total (`slotsChecked`, `leaderSlots`, `forged`, `adopted`, `lostSlotBattles`, `failed`, `defects`, `skipped` and
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

The server builds one `GET /state` answer at a time, whatever the size of the state; the summary is never refused.
A request for the point and `utxo` view of an answer being built waits for that build and gets the same answer.
The answers at the tip are kept, rendered, until the tip moves, so asking again at the same tip builds nothing; an
answer at an explicit point that is not the tip is built for each request.

The slot clock starts at the Byron genesis' `startTime` when `byron-genesis.json` has one (as the
reference's does), else at the Shelley `systemStart`; the `started` line logs it as `systemStart`.
Write both identically in a network you make yourself.
