<!-- SPDX-License-Identifier: Apache-2.0 -->
# Scale and scheduling

Part of [Architecture](../architecture.md).

## Scale: the UTxO set on disk and a pipelined sync

Syncing Preview with the whole ledger in memory fell from 350 blocks/s to 20 blocks/s by block 527k
(UTxO of 1.25M entries, 2.7 GB live, 335 MB snapshots). The measurements are in
[performance.md](../performance.md); the changes, in the order they paid off:

**Compact bytes.** `xyz.kd5ujc.scarab.codec.Bytes` was a `Vector[Byte]` (boxed, about 6 times the size, hashing and
equality walked the elements). It is now a window onto an immutable `Array[Byte]` with a cached hash;
the CBOR decoder gives every node a zero-copy window of the block (`original`) instead of a copy, and
integers are read as `Long` when they fit. The API is unchanged (`value` still gives a `Vector`).

**The UTxO set behind an algebra (UTxO-HD).** `UtxoStore[F]` has `read(keys)`, `write(changes, stamp)`,
`flushed`, `checkpoint(dir)`, `restore(dir)`, `all` and `reset`; `MemoryUtxoStore` (tests, the devnet,
`follow` without `--db`) and `RocksUtxoStore` (RocksDB through its JNI, Maven `org.rocksdb:rocksdbjni`,
native library bundled for linux x86_64) are the interpreters. The ledger state holds a pure `Utxo`:

- `diff`: the changes made since the store's flushed point, newest per key, each stamped with the
  block number. The states of the last `k` blocks each hold one (persistent maps, shared structure),
  which is the DbChangelog: a rollback is dropping states, the store is never touched, and it only ever
  holds immutable state.
- `resident`: the read set. `NodeRules.prepare` decodes the block once, collects the inputs, reference inputs
  and collateral of its transactions that neither the diff nor the block itself decides, and fetches
  them from the store before the pure rules run (`Utxo.missing`, `withResident`); afterwards the read
  set is dropped. The rules keep their shape: `state.utxo.get(in)`, `utxo -- inputs ++ created`.
- `coin`, `count`, `stake` (lovelace by stake credential) and `ptrStake` (by certificate pointer),
  updated as outputs are created and spent. The epoch boundary's SNAP no longer walks the UTxO set
  (`Stake.instant` adds the accounts and resolves the pointers), and the supply invariant reads
  `utxo.coin`.

When a block becomes immutable (the follower's anchor moves) the changes of the anchor state newer than
the store's stamp are written in one batch (`UtxoStore.advance`, through `StateBacking`); the diffs are
trimmed lazily (`Utxo.settled`). A ledger snapshot is written for the anchor state with its diff
trimmed, and a checkpoint of the store (RocksDB `Checkpoint`: memtable flushed, SST files
hard-linked) is taken at the same block under `<db>/utxo/checkpoints/<snapshot name>`, so a snapshot
and its UTxO are always the same block; pruning follows `--keep-snapshots`. A restart restores the
snapshot and rebuilds `<db>/utxo/live` from its checkpoint (SST files hard-linked, the rest copied).
A snapshot without its checkpoint is skipped like a damaged one. There is no write-ahead log: the live
database is disposable.

Properties (`UtxoStoreSuite`, `StakeSuite`): random blocks, rollbacks of up to 4 and flushes with
`k = 3` leave both interpreters equal to a `Map` model at every step, and the incremental stake,
total and count equal a recomputation; checkpoint/restore returns to the checkpointed set; the
Byron-to-Conway capture reaches the same state through the store with verification warmed ahead.

**Verification ahead of the rules.** Most of the time of a block is signature checks: transaction
witnesses (Ed25519), the operational certificate and KES signature, and the VRF proof. They are pure
functions of the block (the VRF also of the epoch nonce). `VerificationCache.warm` runs them for a whole
fetched batch in parallel and keeps the verdicts; the rules ask for the same verdicts through
`Verdicts.witness`, `opCert`, `praosVrf` and `tpraosVrf`, which return the remembered one (once)
or compute it (`Verdicts.Direct` never remembers; a property test checks both agree). The VRF is warmed with the epoch nonce the rules last saw, keyed by the derived alpha
string, so a stale nonce is only a miss. Replay of the stored blocks warms the next 64 while the
current ones are applied; the peer warms each fetched range.

**A pipelined peer.** `Peer.nodeToNode` (and `Peer.over` for any negotiated connection) runs a
producer fiber that keeps up to three batches of `--read-ahead` (default 128) in flight: it requests
the headers of the next batch, validates them (header-first, see above) while the blocks of earlier
ones are being fetched and verified, and publishes them in order; the consumer applies a batch
meanwhile. The depth was measured from 64 to 256 against the public relay (performance.md section 7):
no timeout at any of them, 128 and above are 6 to 10% faster than 64.

```sh
follow ... --db <dir> [--utxo-store memory|rocksdb] [--utxo-cache-mb N] [--read-ahead N]
```

`rocksdb` is the default with `--db`; without it the node uses the memory store. The query server
completes the UTxO from the store for `/state`.

## Scheduling: CPU-bound work off the timer path (D8)

cats-effect gives the compute pool one thread per CPU. A pure step that holds one of them for
hundreds of milliseconds (the rules of a block full of Plutus scripts, an epoch boundary over all
accounts) delays every timer on the pool: KeepAlive, protocol timeouts, the forger's slot clock
(0.1 s slots on the devnet). With one CPU there is a single such thread. RocksDB calls and the
file stores already use `blocking`; the CPU-bound part was the follower: header handling, chain
selection, the pure rules, persisting.

- `Compute[F]` (`Scheduling.scala`) has `within(fa)`, which runs an effect on a second cats-effect
  work-stealing pool (`IORuntime.createWorkStealingComputeThreadPool`, workers `scarab-cpu-N`, default one
  per CPU, `--cpu-threads`) allocated and shut down as a `Resource`, and `timed(label)(step)`, which
  runs a pure step where it is and records its duration. `Main` wires the outbound and inbound
  session callbacks through `compute.within(follower.session(...))`; the connection itself
  (multiplexer, KeepAlive, the pipelined ChainSync and BlockFetch producer) is opened outside and stays
  on the compute pool, as do the relay servers, the forger loop and the timer monitor. The replay of
  stored blocks and the resume of the volatile chain run within the pool too, and so does the
  application of a block this node forged. `MirrorRules` times `applyBlock` through `timed`.
- Where the boundary is drawn matters. A first version moved each block's rules to a plain fixed
  thread pool and cost a third of the throughput; the second moved each block to a work-stealing pool
  and cost a fifth: every hand-off is a thread wake-up, and on a busy host each wake-up is a
  scheduler delay in the middle of a sequential chain of 400 blocks a second. A session that stays on
  the pool across blocks pays a hand-off only when it waits for the network (performance.md section 6).
- A step is timed on the thread it ran on and recorded under a label computed from its result:
  `block`, `plutus` (the block evaluated scripts), `epochBoundary` (it crossed an epoch), `rejected`.
  `--cpu-threads 0` runs everything on the compute pool as before, still measured.
- `Scheduling.monitor` is a fiber that sleeps 20 ms over and over and records how late it wakes: the
  lateness of every timer on the compute pool, which is what the cats-effect starvation warning
  reports. `SchedulingStats` keeps a window (read and reset at each epoch line of the log) and the
  totals (a commutative monoid, law-tested).
- The epoch lines `epochValidated` (Byron, bootstrap) and `ledgerEpoch` carry a `scheduling` object:
  `maxTimerLatenessMs`, `timersLateOver50Ms`, `timersLateOver100Ms`, `cpuSteps`, `longestStepMs`,
  `longestStep`. `GET /scheduling` on the query port returns the run totals with every label.
- Long loops yield with `cede`: the replay of stored blocks and the resume of the volatile chain.
- Batch verification (`Verification.warm`) stays on `blocking` threads beside the session. Putting it
  on the CPU pool as well queued the rules behind it and cost a third of the throughput.

A step is still one uninterrupted call of a pure function, so the longest step is reported, not
bounded; what the pool buys is that the compute pool is not the thread that runs it. It does not
bound what the kernel does: a container limited to one CPU is throttled by the cgroup whenever its
threads together use more than the quota, and a JVM that finds one CPU picks the serial collector,
whose pauses stop every thread. Both showed up as late timers in the measurements, and the second is
fixed by JVM flags, not code (`-Xms` equal to `-Xmx` and `-XX:+UseG1GC`, see
[running.md](../running.md) and [performance.md](../performance.md)).
