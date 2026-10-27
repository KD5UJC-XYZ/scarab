<!-- SPDX-License-Identifier: Apache-2.0 -->
# Node-to-client

Part of [Architecture](../architecture.md).

## Node-to-client (`--local-socket`)

One Unix socket serves four node-to-client mini-protocols: LocalTxSubmission and LocalStateQuery (what
`cardano-cli` uses), LocalTxMonitor (`cardano-cli query tx-mempool`, wallets) and ChainSync of whole blocks
(db-sync, Ogmios and the followers of `cardano-cli`).

**Rejections** (`ledger/PredicateFailure.scala`, `ntc/PredicateFailureCbor.scala`). The Conway rules
refuse a transaction with a `LedgerFailure.Rejected(txId, PredicateFailure)`: a closed tree that
mirrors `ConwayLedgerPredFailure` (UTXOW, UTXO, UTXOS, CERTS, CERT, DELEG, POOL, GOVCERT and GOV
failures and the LEDGER-level ones) with the payload the ledger reports (the offending inputs,
`Mismatch` pairs, credentials, outputs with their minimum, governance action ids). The rules of the earlier
eras raise cases of the same types (`UtxoFailure.ExpiredUTxO`, `PpupFailure`, the Shelley `DelegFailure`s,
`UtxowFailure.MIRInsufficientGenesisSigs`), which Conway sends as its free-text `ConwayMempoolFailure`
(text derived from the case) and `PreConwayFailureCbor` writes as each earlier era's own instances do; no
failure carries a rule name as its identity (`ruleName` is derived from the case). `PredicateFailureCbor`
writes the shapes of the ledger's `EncCBOR` instances (constructor tag first, a `Mismatch` as two
fields in line, reversed where the ledger swaps them, sets under tag 258, `StrictMaybe` as an empty or
one-element list), and `LocalSubmission.reasonItem` wraps the failures as the hard-fork combinator's
`ApplyTxErr`: `[[6, [failure, ...]]]`; a transaction of another era gets the era mismatch
`[[era, name], [6, "Conway"]]`. The LEDGER-level checks (treasury value, reference-script size,
withdrawals), CERTS and GOV run before UTXOW, as in the ledger. A submitted transaction is judged
by the ledger's MEMPOOL rule under the accumulating judgment (`rules/Judge.scala`,
`docs/design/rule-interpreter-as-built.md`): every rule boundary of the reference is a sub-rule
that always runs, so the failures of different rules are all reported, in the reference's order.
Within each rule every check records its failure and the rule goes on to the state the reference
makes, so a later certificate or sub-rule sees it (#412).

**LocalStateQuery** (`protocol/LocalStateQuery.scala`, `network/LocalStateQueryServer.scala`,
`node/ntc/`). The protocol codec is pure (states Idle, Acquiring, Acquired, Querying, Done; acquire at
a point, the volatile tip or the immutable tip; re-acquire; release). The server asks a `Source` to
acquire a target and gets a `Session`: the ledger state at that block, kept for as long as the client
holds it, whatever the chain does meanwhile (`StateQuerySource` reads the retained states of the
volatile chain: the tip, the anchor, or a block among the last `k`; an older point is `PointTooOld`,
one not on the chain `PointNotOnChain`). `NtcQuery` decodes the envelope (`Query`: system start, chain
block number, chain point; the hard-fork combinator's `QueryIfCurrent`, `QueryAnytime`,
`QueryHardFork`; the Shelley block queries 0 to 39 with sets under tag 258), `QueryAnswers` computes the
answer from the state of the chain as a pure function (the state is read through the `ChainRead` type class, in the vocabulary of the ledger-state mirror: `node/read/`, step 2 of the mirror, J2.7) and the Haskell state codecs (`storage/haskell/state`: `Wire`, the node-type codecs, `UtxoCodecs`) write the ledger's CBOR (the
31-element Conway protocol parameters, outputs in the canonical array or keyed form, the governance
state with its DRep pulser, stake and pool distributions, ...; containers of more than 23 elements are
indefinite as in the ledger's `variableListLenEncoding`). The only effect is reading UTxO entries not
in the retained state from the backing store (`UtxoNeeds`). Only the current era (Conway) is
answered; a query for another era gets the combinator's mismatch reply. The era history is built
from the configured hard-fork epochs, the transitions this run observed and the safe zone of the
current era (`EraHistory`); a version-triggered hard fork that happened before this run cannot be
placed and is assumed to start with the previous era. As on the reference, a result has no byte or item
limit beyond what a byte array holds (`maximumMiniProtocolLimits`, codec run without limits; #353): a
`GetCBOR DebugEpochState` answer of preprod, which `leadership-schedule --next` asks, is tens of megabytes.
A client's query has the same absence of a byte or item bound (#356); only its nesting is bounded (32,767 levels, `LocalStateQuery.MaxQueryDepth`), because the node reads queries on the heap with stack-safe walkers. Deviation approved by the owner on 2026-10-25: the nesting bound stays; trade-off: it guards the JVM against stack overflow, where Haskell would not overflow, on a local socket only, and real queries nest far less deeply. Queries the node does not answer end the connection with
a protocol error that names the query (`inbound ... closed` in the log), where the reference would
answer.

**LocalTxMonitor** (`protocol/LocalTxMonitor.scala`, `network/LocalTxMonitorServer.scala`,
`node/mempool/LocalMonitor.scala`). The codec is pure (states Idle, Acquiring, Acquired and Busy of four kinds;
`AwaitAcquire` has the wire form of `Acquire`, `[1]`, and the state tells them apart; `GetMeasures`, tag 11,
is answered on every version as the reference's codec does). The server asks a `Source` for a `Snapshot`
(slot, transactions in arrival order with their measures, membership, capacity) and serves `NextTx`, `HasTx`,
`GetSizes` and `GetMeasures` from that frozen value; `AwaitAcquire` waits until the pool's slot or
transactions differ (`Mempool.awaitChange`, woken by `add` and by `sync`). `LocalMonitor` is the reference's
server in numbers: the snapshot's slot is the one after the tip; a transaction counts for its ledger size (the
serialised transaction without the `is_valid` byte, `sizeTxF`) plus 4 bytes, its execution units and the bytes
of the reference scripts of its inputs and reference inputs (`Entry.refScriptBytes`, measured when it was
added); the capacity is the one the pool enforces (`MempoolState.capacity`, `Capacity.of`, which also does the
measures): `computeMempoolCapacity`, a whole number of units, a unit being `maxBlockBodySize -
1024` bytes, `maxBlockExUnits` and 1 MiB of reference scripts, and the number of units `ceil(--mempool-bytes /
unit)` (the reference's override rule, and 2 without one). `GetSizes` describes the whole snapshot,
`GetMeasures` only the transactions `NextTx` has not handed out (the reference's quirk). A transaction is
`[6, #6.24(tx)]`, an id `[6, h'32 bytes']`.

**Local ChainSync** (mini-protocol 5) is the `ChainSyncServer` of node-to-node with the payload
`#6.24(block)` (`CardanoBlockFetch.payloadCodec`, the block as stored, `[era, block]`) and the node-to-client
limits (`ChainSync.Limits.NodeToClient`: 2.5 MB messages, 1024 intersection candidates, where node-to-node
allows 65535 bytes). `ChainServer.blockSource` is the same cursor over the immutable store and the volatile
chain as `source`, projecting blocks instead of headers. As the reference's follower does, the first
instruction after an intersection is a roll-back to the point found (also on node-to-node); devnet `ntc` found
this by comparing the two servers.

Tests: servers against our own clients over in-memory bearers and loopback TCP (both directions,
duplex, pipelining, timeouts, refusals); a property that every follower reconstructs the served chain
across arbitrary extensions, rollbacks and reads; the chain server over a real immutable store;
mempool properties (held transactions always apply in order, byte accounting, tickets);
chain-selection properties (antisymmetric, longer wins, issue number, unsigned VRF compare,
never more than `k` rolled back, the winner among several candidates does not depend on arrival
order); the follower with several scripted peers; the local protocol over a Unix socket.
