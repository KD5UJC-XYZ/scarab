# Deterministic adversarial schedules

- **Status**: As built for #78 (2026-10-18)
- **Scope**: bounded node-to-node schedule tests; they are regression evidence, not a proof of the consensus protocol; the last section covers the nightly randomized devnet fault run

`node/src/test/scala/xyz/kd5ujc/scarab/node/AdversarialScheduleSuite.scala` runs sixteen fixed
`TestControl` seeds against three independently configured `Follower`, `PeerNetwork` and
`BlockPool` runtimes. Each runtime has its own volatile chain and `ChainServer`; blocks move through
the production ChainSync and BlockFetch servers and clients over `InMemoryPeers` and real in-memory
bearers. The fixture uses signed Praos blocks accepted by `MirrorRules`, with a shared prefix and
two independently signed, bounded suffixes. The active suffix fork is no deeper than the fixture's
`securityParam` (`k`).

For each seed, one runtime's ChainSync replies are withheld for a bounded virtual delay and then
released. The suite records named observations while withheld, after release/convergence, and after
endpoint recovery. At each observation it checks a common history prefix of at least
`min(history lengths) - k`, using the fixture's `securityParam` for `k`; it also rechecks fresh tips
after recovery. A node started at genesis must grow while honest sources are available. The schedule
closes and restores one peer endpoint and checks that the real governor opens another bearer to it.
Each node uses the production `HeaderClock` wrapper with an injected `SlotClock`; the clocks differ
by 0, -1000 and -500 milliseconds. Failure output includes the seed, checkpoint, node observations
and schedule actions.

The suite asserts only these outcomes for the checked schedules: all three observed histories
converge after release and recovery; the common-prefix bound holds at each recorded checkpoint; a
node started at genesis grows while honest sources are available; peer close/recovery succeeds; the
opened in-memory bearer ends are closed by their resources; each runtime reports no `Fatal` outcomes;
no uncaught fiber failure escapes `FailsOnUncaught`; and retained completed blocks stay under
`BlockPool.Retained`. After network scopes close, `BlockPool.take` is bounded for every distinct
known fixture hash, then a fresh session must claim all of them, checking that these candidate hashes
have no orphaned pending claim while draining any completed retained value. A negative-control test
injects a typed fatal into the exact capture sink and confirms the no-fatal invariant rejects it.
The existing malformed-block network regression in `FollowerStallSuite` also runs under fixed-seed
`TestControl` and checks the exact invalid bytes, typed peer rejection, cleanup and continued progress
from the honest peer.

Transport regressions in `MuxSuite` and `MuxDeadlinesSuite` complement the node schedules with
oversized ingress, fully decoded messages exceeding their state byte limit, and slow-loris progress
against fixed deadlines over real in-memory bearers. They run as separate transport tests in `check`.

The schedules do not claim that every adversarial strategy is modeled, establish a security
theorem, or test an Antithesis run. The Cardano Foundation's
[public Antithesis assets](https://github.com/cardano-foundation/cardano-node-antithesis) are
inspectable, but this work had no verified Antithesis tenant or run access. The local harness remains
the reproducible gate.

## Nightly randomized fault run

- **Status**: Adopted for [#224](https://github.com/KD5UJC-XYZ/scarab/issues/224) (2026-10-22)
- **Decision**: The hosted Antithesis service (a deterministic hypervisor that explores schedules and replays any failure
  exactly) is deferred by owner decision. In its place, a seeded, randomized network-fault run of the Docker devnet runs
  nightly with free tools. The two are not equivalent, see "What it does not cover".

`python3 scripts/devnet.py --name <name> faults --seed N --duration S` and
[`.github/workflows/faults.yml`](../../.github/workflows/faults.yml) (own workflow, so it neither slows nor disturbs the
`e2e` jobs; 45 minute hard limit, the run itself takes the schedule length plus about two minutes) run the Scala node as pool 1 of a three-pool cluster next to
two Haskell reference pools (`cardano-node` 11.1.3) as in the `pool` scenario. The Scala node forges, listens and keeps a
database. There is no transaction load; the chain is blocks only.

### Faults

All nodes of a devnet share one network namespace and talk over loopback, so a fault cannot be addressed to an interface.
Every link between two of the three nodes therefore runs through a proxy port of its own per direction
([`lagproxy.py`](../../scripts/devnet/lagproxy.py), several listeners in one process), and the nodes' topologies name the proxy
ports. A `prio` queueing discipline on `lo` sends the packets of one link's ports (a `u32` filter on source or destination
port) to a band with its own `netem`; everything else, including the node's query port and the proxies' connections to the
nodes, stays in the default band. The `tc` commands run in a short-lived helper container with `NET_ADMIN` that joins the
reference container's namespace (the reference image ships `tc`), so no node image changes
([`netfaults.py`](../../scripts/devnet/netfaults.py)). The host needs the kernel modules `sch_netem`, `sch_prio` and
`cls_u32`; the workflow loads them and the scenario fails at once with a clear message if the setup is refused.

| Fault | Draw |
|---|---|
| latency and jitter | on a random nonempty subset of the three links: 50 to 400 ms delay, jitter up to half of it, 10 to 40 s |
| packet loss | on a random subset of links: 2, 5, 10 or 20 percent, 10 to 40 s |
| partition, then heal | one node cut from the other two (both of its links at 100 percent loss), 3 s up to the bound below |
| crash, then restart | `docker kill` of the Scala container, or `kill -9` of a Haskell pool by its recorded pid, down 5 to 20 s; the Scala node restarts from its database and keys, the Haskell pool from its database |

Episodes never overlap. After each one comes a grace period (20 s, 40 s after a crash) and then a quiet window of at least 25 s
with no fault. A partition lasts at most the time in which the whole network is expected to grow `k` blocks
(`k * slotLength / activeSlotsCoeff`, 10 s for the devnet's k = 5, f = 0.05, 0.1 s slots). A node with a third of the stake
grows a third of that on its own side, so a reunion costs it about `k/3` blocks of rollback. A branch deeper than `k` cannot
rejoin under Praos, which is the protocol working and not a defect; the bound keeps the schedule inside what the protocol
promises, and a rare Poisson excess can still reach it (about one partition in a hundred), which would show as a failure of
the rollback check and is to be judged by hand, not ignored.

### Seeds, schedules and replay

[`faultplan.py`](../../scripts/devnet/faultplan.py) draws the schedule from `random.Random(seed).random()` alone and writes
it, with the seed, the duration and the genesis parameters it used (`k`, f, slot length), to `fault-schedule.json` in the
run directory before the first fault; the explicit action list (`shape`, `heal`, `kill`, `start` at a second) is part of the
file. The schedule is a pure function of those inputs (a prefix does not change with a longer duration), so:

- the nightly run uses the run number as its seed and prints it, the replay command and the job summary;
- `workflow_dispatch` takes a `seed` (and a `duration`) to rerun one;
- locally, the same seed over the same duration regenerates the same file: `python3 scripts/devnet.py --name mine faults --seed N --duration S`;
- `--schedule FILE` runs a recorded file as it is, whatever the generator has become since.

Replay is of the schedule, not of the execution: real containers, real time and a real kernel are not deterministic, so a
failure that depended on timing may need a few reruns of its seed; the failure output carries the schedule and the event logs.

### Properties

Read from the nodes' own state, with no tolerance beyond the protocol's `k`:

1. After the last heal and a settle window (90 s), all three tips become the same block.
2. In every quiet window, each node's block number goes up (the Scala node's from its event log, the Haskell pools' from
   `cardano-cli query tip`). A restarted node must rejoin, and the Scala node's log must show `extended` after each restart.
3. The Scala node rolls back no deeper than `k`, logs no `error` event, no output the log format does not parse, and no
   cats-mtl `Submarine`.
4. No Haskell pool exits unless the schedule killed it, and none reports a `DecoderFailure` with `ExceptionExit`.

The job fails on a violation, prints the replay command, and uploads `fault-schedule.json` and `faults-summary.json` (small, kept
30 days); a failure also uploads the Scala event log, the Haskell pool logs and the container output.

### What it does not cover

- It is not Antithesis. Its schedules are sampled at random once a night over real time. It does not explore the space
  systematically, does not control thread scheduling or the clock, and cannot replay an execution exactly. It finds what a
  random walk over network faults finds, and a night that passes is not evidence about other schedules.
- Faults are on the network of one Scala node and two Haskell pools; there is no second Scala node, no Haskell relay, no
  transaction load, and no adversarial peer (malformed or withheld data stays with `AdversarialScheduleSuite`, `MuxSuite` and
  the peer-cut scenarios).
- Clock skew is not covered: all nodes read the host's clock and the devnet has no per-node clock offset. (`AdversarialScheduleSuite`
  covers header-clock offsets in process.)
- Disk faults, memory pressure and CPU starvation are not injected. Only the Scala node and the Haskell pools crash; the proxies do not.
- Partitions are bounded by `k` as described; a deeper partition is not tested, because the outcome is the protocol's.
- Packet corruption, reordering as such (jitter does reorder) and bandwidth limits are not injected.
