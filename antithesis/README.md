<!-- SPDX-License-Identifier: Apache-2.0 -->
# Antithesis workloads

Workloads for the [Antithesis](https://antithesis.com/docs/) trial. Each runs Scarab beside the Haskell
`cardano-node` reference in a Docker Compose cluster that Antithesis faults deterministically. A run is an
observation; the Haskell node decides ([ADR 0001](../docs/adr/0001-haskell-node-as-reference.md)), and a
divergence is reproduced locally before it is reported.

## `pool`

Two Haskell cardano-node 11.1.3 pools (`h2`, `h3`, from the [reference image](../docs/reference-image.md)) and
one Scarab pool (`s1`, pool 1's keys), on the genesis `cardano-testnet create-env` writes with its defaults
(k = 5, f = 0.05, 0.1 s slots, 500-slot epochs). A one-shot `configurator` writes the genesis, keys and
topologies at setup and sets the start time a minute ahead. `s1`'s container starts just before the pools', so that
its name resolves at their first DNS lookup, and Scarab waits inside it until both Haskell pools listen, as the devnet
starts it (issue 609). `config-late-start` keeps the earlier order as an experiment (below).

The `driver` reports setup complete once the cluster is healthy. All three pools must first report one tip at
block 2 or later. Then all three must answer and be on the longest chain, at fork depth 0. Since the gate began,
each Haskell pool's own chain must have taken a new block of s1, and s1's a new block of a Haskell pool, so that each
side is taking the other's blocks now, not only at some earlier time. At k = 5 a split at the start, above all the
late start's DNS window below, can keep the Haskell pools from taking s1's blocks until s1 has forked past k; a
cardano-node in s1's seat does the same (#854).
Every timeline would inherit that split, so such a setup fails after 1200 s and says so. The gate waits that long
because, in `config-late-start` and its control, the pools' first DNS lookup of `s1` fails, and the reference then
waits 900 s before it looks again: until then the pools take none of s1's blocks.
Antithesis then runs its test commands under faults, many times per timeline:

- `parallel_driver_watch_tips` reads every pool's tip over its local socket every 5 s for a minute;
- `anytime_check_divergence` makes one pass of the divergence detectors, which the watcher also makes at every
  check;
- `parallel_driver_submit_payments` makes five payments, each submitted to a random pool. Half go from create-env's
  `utxo3` key to four wallets of the workload's own in one transaction; the other half go from one wallet to another
  or back to `utxo3`. Every pool's blocks then carry transactions, so both implementations' ledgers run under the
  faults, and the detectors judge them. A payment holds its source wallet's lock while it reads the wallet's outputs
  and submits, since copies run at once.

The detectors read the pools' logs, which every pool also writes to a shared volume at `/logs`, and s1's metrics.
Each reads on from where the last pass in the timeline stopped. The properties, in the fallback SDK's format:

- reachable: the workload starts;
- sometimes: Scarab pool s1 extends its chain; a Haskell pool extends its chain; all three extend within one
  interval; all three report the same tip;
- unreachable: a Haskell pool never refuses a block as invalid (its `ChainSync.Client.Exception` trace
  `InvalidBlock (Point ...`). Before blaming s1, check which pool forged the block;
- unreachable: Scarab never refuses a block as invalid (`scarab_validation_errors_total` on s1's metrics);
- sometimes: s1 accepts a payment; a Haskell pool accepts a payment;
- always (or unreachable): the cluster's fork depth stays below k. The depth is the deepest rollback any pool would
  need to follow the longest tip. `driver/forktree.py` builds the block tree from the pools' logs: every forged
  block with its parent, and every pool's tip changes.
- sometimes: a fork is observed (depth 1 or more), so that the fork-depth property cannot pass vacuously.
  Each pass also prints the fork tree's result (`forktree: ...`), and the first pass past k prints
  `forks: first past k ...` at most once along each history: a branch taken after it inherits it, one taken before
  prints its own; a signal between its marker and its line loses that line, so the count can fall short, never
  over. A run's search then counts the distinct crossings in the run's tree of histories against the
  distinct forge events in it (runs explore different lengths of chain in the same time), and samples each pool's
  share of the longest chain from the `forktree:` lines; those samples are cumulative, so they are averaged, never
  summed.
- sometimes: every pool's log is present and not empty, so that the detectors that read logs cannot pass unseen on
  a broken path. It is declared in every config, and fails where `/logs` is not mounted; it says the logs were
  there, not that each detector read them through.

What Scarab knows itself, it asserts itself (#1032): `scarab/scarab-node.sh` starts s1 with
`--assertions-file "$ANTITHESIS_OUTPUT_DIR/sdk.jsonl"` (`docs/running/flags.md`), and the node declares its catalogue
of properties at each start (`Property`, in `node/src/main/scala/xyz/kd5ujc/scarab/node/assertions/`) and evaluates
each one from the typed event at the site that meets it, so the driver no longer reads s1's log for these. They
include the three the driver made before:

- unreachable: Scarab never stops on a fault other than a clock moved back too far. The stop for a clock moved back
  more than `Forger.MaxClockRewind`, which cardano-node makes too and Antithesis's clock skips can cause, is left out
  by its type;
- unreachable: Scarab never traces the clock moved back on a wake 1 µs to 1 ms before its slot, time moving on
  (#629). The slot loop waits until the next slot's start, floored to the microsecond as the reference's
  `threadDelay` floors it, so only a wake earlier than that is #629's millisecond floor, which woke less than 1 ms
  early. A cardano-node pool on the platform also woke a fraction of a microsecond early and traced
  `SystemClockMovedBack`. A clock moved back by more than a slot is told apart: its readings move forward through
  earlier slots. By less, during the wait, the wake mostly comes earlier than the window (20 to 80 ms in a run with
  the platform's clock skips, which the cardano-node pools traced as `SystemClockMovedBack` too), but a skip back
  with a late wake can still land inside it: the bound is a heuristic, and a hit in a run with clock faults is
  checked against the skips and the pools' traces before it is called #629;
- sometimes: Scarab forges a block with transactions.

The others cover forks and rollbacks deeper than 1 block and than k / 2, peer demotions, a peer's chain that no
longer intersects s1's, mempool drops on revalidation, forging, ledger snapshots, a forged block s1's own rules
refuse, a forge defect and a block validation without a verdict. In the configs where cardano-node sits in s1's seat
none of them is declared. A Scarab build without the flag (before #1032, such as v0.2.0-alpha.1) runs only with
`SCARAB_ASSERTIONS: "off"` in s1's environment, since the platform sets `ANTITHESIS_OUTPUT_DIR` in every container.

The test commands append each assertion line under a lock, waited on for up to a minute and never bypassed (a line
that cannot be locked or encoded fails its command, and a once-per-timeline line is then tried again), bound every
call to a node
with `timeout`, and exit 0 when a signal stops them, so that the platform's "Commands finish with zero exit code" is
about the workload's own failures (after cardano-foundation/cardano-node-antithesis, whose commands meet killed
nodes). A command stopped by a signal therefore also exits 0: that code alone does not say it finished.

There is no `always` on tip agreement. A pool that has just forged is ahead by definition, so "all tips agree at
an instant" is no invariant: cardano-foundation/cardano-node-antithesis removed such probes as false positives
(issue 119) for the same fork-depth property. At k = 5 the fork depth fails in this topology under the platform's
network faults, in a third or more of the timelines, and as often with a cardano-node in s1's seat (#854).
`config-k200` asks whether the forks that faults cause heal.

Scarab is a JAR assembled from a commit (`node/assembly` in `scarab-jdk:local`). It runs on the Java 25 runtime of
`docker/candidate.Dockerfile`, and the image's label records the commit. The JAR is linked into
`/opt/antithesis/catalog/`, and the node starts from the classpath `/opt/scarab/*`, so Antithesis instruments it for
coverage and thread pausing. `scarab/scarab-node.sh` turns cats-effect's fiber tracing off
(`-Dcats.effect.tracing.mode=none`), in this harness only: its default cost s1 about 18% of its CPU in a local probe,
which slows the platform's simulation of the whole cluster. The price is that s1's error stack traces have no fiber
frames and a stall dump no fiber snapshot. A node run elsewhere keeps the default (#1024).

## `pool/config-k200`: the same cluster at k = 200

An experiment of its own, beside `pool`, never instead of it: k = 200 with epochs of 10k/f = 40,000 slots (about
67 minutes). At create-env's k = 5, a fork deeper than 5 blocks is permanent by protocol, which is about 10 s of
divergence at 0.5 blocks per second. So `pool` cannot ask whether the forks that faults cause heal, and this config
can. `pool` stays in every round, because it shows a fork at the start as a permanent split. The configurator takes
k and the epoch length from `SECURITY_PARAM` and `EPOCH_LENGTH`.

## `pool/config-short-kes`: KES periods of 250 slots

An experiment beside `pool`: the genesis' `slotsPerKESPeriod` is 250 (25 s), as `scripts/devnet.py pool` sets it. At
create-env's 129,600 slots a timeline reaches its first KES period change only 3.6 hours of virtual time after the
system start, so a run of `pool` reaches the forger's update of the key at a period's first slot, and the rule that
only an attempt admitted at its slot changes the key (#1101, the leader schedule's §5.3), only in a timeline that
long. This config reaches them about every 25 s, under node kills, pauses and clock skips; the refusal detectors then
say whether the Haskell pools take every block s1 forges across a period change. The pools' certificates allow
create-env's 60 evolutions, so they expire 1,500 s after the system start, every pool's alike. The configurator takes
the period from `SLOTS_PER_KES_PERIOD`. Nothing in the workload sends SIGHUP, so a reload is not exercised.

## `pool/config-reload`: s1 reloaded by SIGHUP under faults

An experiment beside `pool`: `config-short-kes`'s cluster, with s1 reloaded by SIGHUP while the faults run. Three
commands run inside s1's container, where the signal reaches the node (`pool/scarab/reload/`, in the image
`scarab-at-scarab:reload`, which only this config uses; Antithesis takes s1's `main` template and the driver's for
one):

- `parallel_driver_reload_kes` rotates the KES key with `scripts/pool/rotate_kes.py`'s layout: the next of 16
  rotations the configurator wrote becomes a generation in `/data/keys`, and the `current` link moves to it by rename.
  The link stays whatever the node answers (below).
- `parallel_driver_reload_topology` moves s1's local roots between `hotValency` 2 and 1. At 1, a governor holding two
  hot local roots demotes one, which the node's own "Scarab's governor demotes a peer" records.
- `parallel_driver_reload_unchanged` sends SIGHUP with nothing changed.

It asks whether a reload under node, clock and network faults keeps every block s1 forges one the Haskell pools take,
and whether the governor demotes a peer when a reload lowers its target. Each rotation is a fresh KES key with a
certificate at counter 1 for the same window: Praos takes a counter equal to the chain's last one or one more, so no
rotation gives s1 a counter the pools refuse, on any fork, and the node's next start may take any generation, which is
why a refused one is not put back. Counter increments are not exercised.

The commands signal only a ready start: `pool/scarab/scarab-node.sh` removes `/tmp/node.boot` when it starts and
writes it just before the JVM starts, with the JVM's PID and the log's length; a command goes on only when that PID
runs the node's Main and the log has `forgeStarted` past that length, so the start has read its credentials and
installed its SIGHUP handler (a JVM ends on a SIGHUP that comes before the node's handler). Nothing is changed before
that, and a kill of s1 kills the commands too. One reload is outstanding at a time: a signal whose answer has not come
is recorded, and the next command settles it before it sends another; one unanswered for five minutes is dropped, and
since the node's answer does not name the generation it read, that start's later rotations are then recorded as
uncorrelated, which the KES control does not count.

s1 starts from a topology file (a reload keeps the roots `--peer` gives), and its key directory is a named volume, so
that a container made again from its image keeps the credentials its reloads installed. The commands record what they
did in `/logs/reload.log`, and the driver's two positive controls, "s1 accepts a KES rotation sent by SIGHUP" (a
change of generation the node took) and "s1 reads a changed topology on SIGHUP", fail unless the steps ran.
`hotValency` 1 changes how s1 follows the pools, so the fork rates are not comparable with `pool`'s.

## `pool/config-late-start`: the earlier start order

An experiment beside `pool`: s1's container starts only once both Haskell pools listen, as `pool` started it until
2026-11-14. Docker's DNS knows a container's name only while it runs, so the pools' first local-root lookup of `s1`
fails with NXDOMAIN, and the reference waits the longest interval, 900 s, before it looks again (`ttlForDnsError`,
ouroboros-network `RootPeersDNS/LocalRootPeers.hs`; Scarab's `LocalRootDnsCache` does the same). Until then the pools
take none of s1's blocks, and at k = 5 s1's fork often passes k first. It asks how the cluster fares when a pool's name
does not resolve at first, as for a relay whose record is not yet published. On the platform, at k = 5 and under the
same faults, the share of timelines forking past k was 83% with this order and 40% with `pool`'s, and 76% and 34%
with cardano-node in s1's seat (`config-reference-late-start` and `config-reference`). `pool` moved to the new order
on that result, the owner's decision.

## `pool/config-canary`: the writeBits canary

A regression check, and on a build with the fix reverted a positive control for the detectors: the `pool` cluster set
up so that a known, published divergence
([GHSA-vfw2-hhrc-87v5](https://github.com/KD5UJC-XYZ/scarab/security/advisories/GHSA-vfw2-hhrc-87v5), `writeBits`
on more than 4,096 bytes at protocol 11) would happen.

The canary starts s1's container with the pools, as `pool` does, and runs at create-env's k = 5 with 500-slot
epochs: its setup needs two epoch boundaries for the governance change. Until 2026-11-14 it ran at k = 20 with
4,000-slot epochs, against a start split that came from the late start. That setup took about 875 s of virtual time,
and none of those runs on the platform ended with a property report; the k = 5 runs complete in about 30 minutes, with
the report. The owner made k = 5 the canary then. At k = 5 this topology forks past k under the platform's network
faults, a cardano-node in s1's seat as well, so the canary's verdict is s1's answer and the two refusal detectors,
not the fork depth.

The genesis starts at protocol 11. cardano-node reads a Conway genesis only with create-env's 251 PlutusV3
parameters, which do not price `writeBits`. So the driver's setup enacts the reference's 361-parameter model at
protocol 11 by a governance parameter change, as mainnet did; a genesis overlay sets the technical group's DRep
threshold to 0. The first command `first_submit_writebits` then submits to s1 a transaction minting with the policy
that `canary/writebits_script.py` writes from the reference fixture.

- A fixed Scarab refuses the transaction at its mempool, and no detector fires. The canary's sometimes property
  records s1's verdict (accepted, refused or no answer), and holds only where s1 answered.
- Against Scarab before the fix (v0.2.0-alpha.1, locally), s1 accepts it, and the Haskell pools take it as phase-2
  invalid. Each side then refuses the other's block, and the first two detectors fail.
- Which detector fails depends on who forges the transaction into the longest chain first. Every timeline starts from
  one setup snapshot, so a platform run takes one path in all of them. The platform positive control of 2026-11-14
  (main with the fix reverted) took this one in all of them: h2 forged it with `is_valid` false and s1 refused h2's
  block. s1's own block was on the shorter fork, so the pools never validated it. The Haskell-side detector was
  checked locally instead, with the pools paused while s1 forged the transaction: they refused s1's block
  (`ChainSync.Client.Exception`, `InvalidBlock (Point ...`).

```sh
# from the checkout root; the JAR comes from node/assembly
SCARAB_JAR=node/target/scala-3.9.0/scarab-node-<version>-assembly.jar \
  antithesis/pool/scripts/build.sh                    # needs cardano-reference-11.1.3:local and the pinned JRE
antithesis/pool/scripts/validate.sh                   # snouty validate: setup complete locally, then cleaned up
antithesis/pool/scripts/launch.sh on 15m              # validate, then a run with faults
CONFIG=config-canary antithesis/pool/scripts/launch.sh off 15m   # the canary
CONFIG=config-k200 antithesis/pool/scripts/launch.sh on 15m  # k = 200
CONFIG=config-late-start antithesis/pool/scripts/launch.sh on 15m  # experiment: the earlier start order
CONFIG=config-short-kes SETUP_TIMEOUT=15m antithesis/pool/scripts/launch.sh on 15m  # 250-slot KES periods
CONFIG=config-reload SETUP_TIMEOUT=15m antithesis/pool/scripts/launch.sh on 15m  # the same, s1 reloaded by SIGHUP
python3 -I antithesis/pool/canary/writebits_script.py # regenerate the canary's policies and cost model
```

`launch.sh` takes faults `on` or `off` and a duration, which still bounds a run on the tenant's release 66; there
`MAX_COMPUTE_HOURS` bounds it by its cost instead (`--max-compute-hours`, setup included; snouty takes one or the
other) and `SETUP_TIMEOUT` fails it at once if setup takes longer (`--setup-timeout`; the server's default is 5m). A
15-minute run cost 15 to 20 compute hours, about 7 of them setup, so a longer run pays for setup once. Snouty pushes
the images and the config image to the tenant registry.

It launches with the tenant's own launcher, `amaru-cardano`, as cardano-foundation/moog does
(`src/User/Agent/PushTest.hs`, spec 107). That launcher applies node faults (kill, stop, pause), network faults and
clock skips when `custom.faults_enabled` is true, and leaves out of each class the compose services that
`custom.network_fault_exclusion` and `custom.container_faults_{kill,pause,stop}_exclusion` list; `launch.sh` lists the
driver in all four. Its report has two cardano-node properties of its own ("Never: Cardano Node Critical", "Never:
Cardano Node Errors") and leaves out the instrumentation and thread-pausing properties, although the build
instruments the Scarab JAR as before. `LAUNCHER=basic_test` launches the platform's generic launcher instead, for
comparison.

**`basic_test` did not apply the fault level or the exclusion on the trial's tenant (checked 2026-11-14).** Its runs
record neither `antithesis.fault_tolerance` nor `antithesis.fault_exclusions` among their parameters, although snouty
sends both.
Runs launched at `none`, `resilient` and `high_availability` got the same faults: network partitions and clogs,
`Stopped` included, with clogs naming the driver, and no node pause, throttle or termination and no clock skip.
Read a result as one under those network faults, not under the level named. Before reading a run as a level's, check
its `parameters` (`snouty runs show <id> --json`) and count its fault events that are not network ones
(`snouty runs search <id> 'filter(ev => ev.fault && ev.fault.type != "network")' --count`).
