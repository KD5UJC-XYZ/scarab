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
topologies at setup and sets the start time a minute ahead. `s1`'s container starts with the pools, so that its name
resolves from their first DNS lookup, and Scarab waits inside it until both Haskell pools listen, as the devnet starts
it (issue 609). `config-late-start` keeps the earlier order as an experiment (below).

The `driver` reports setup complete once the cluster is healthy. All three pools must first report one tip at
block 2 or later. Then all three must answer and be on the longest chain, at fork depth 0. Since the gate began,
each Haskell pool's own chain must have taken a new block of s1, and s1's a new block of a Haskell pool, so that each
side is taking the other's blocks now, not only at some earlier time. At k = 5 this topology's start race can keep the
Haskell pools from taking s1's blocks until s1 has forked past k; a cardano-node in s1's seat does the same (#854).
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
- unreachable: Scarab never logs a `fatal` event, other than the stop for a clock moved back more than
  `Forger.MaxClockRewind`, which cardano-node makes too and Antithesis's clock skips can cause;
- unreachable: Scarab never traces the clock moved back on a wake over 1 µs before its slot, time moving
  forward (#629). The slot loop waits until the next slot's start, floored to the microsecond as the reference's
  `threadDelay` floors it, so only a wake earlier than that is #629's millisecond floor. A cardano-node pool on the
  platform also woke a fraction of a microsecond early and traced `SystemClockMovedBack`. A clock moved back for
  real is told apart: its traces move forward through earlier slots.

- sometimes: s1 accepts a payment; a Haskell pool accepts a payment; s1 forges a block with transactions;
- always (or unreachable): the cluster's fork depth stays below k. The depth is the deepest rollback any pool would
  need to follow the longest tip. `driver/forktree.py` builds the block tree from the pools' logs: every forged
  block with its parent, and every pool's tip changes.
- sometimes: a fork is observed (depth 1 or more), so that the fork-depth property cannot pass vacuously.

There is no `always` on tip agreement. A pool that has just forged is ahead by definition, so "all tips agree at
an instant" is no invariant: cardano-foundation/cardano-node-antithesis removed such probes as false positives
(issue 119) for the same fork-depth property. At k = 5 the fork depth fails whenever this topology's start race
splits the chain, and a cardano-node in s1's seat does the same (#854). `config-k200` asks whether the forks that
faults cause heal.

Scarab is a JAR assembled from a commit (`node/assembly` in `scarab-jdk:local`). It runs on the Java 25 runtime of
`docker/candidate.Dockerfile`, and the image's label records the commit. The JAR is linked into
`/opt/antithesis/catalog/`, and the node starts from the classpath `/opt/scarab/*`, so Antithesis instruments it for
coverage and thread pausing.

## `pool/config-k200`: the same cluster at k = 200

An experiment of its own, beside `pool`, never instead of it: k = 200 with epochs of 10k/f = 40,000 slots (about
67 minutes). At create-env's k = 5, a fork deeper than 5 blocks is permanent by protocol, which is about 10 s of
divergence at 0.5 blocks per second. So `pool` cannot ask whether the forks that faults cause heal, and this config
can. `pool` stays in every round, because it shows a fork at the start as a permanent split. The configurator takes
k and the epoch length from `SECURITY_PARAM` and `EPOCH_LENGTH`.

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
`pool/config-canary-scarab-vm` runs the canary with s1 on Scarab's own Plutus evaluator (`PLUTUS_VM: scarab`,
passed as `--plutus-vm scarab`). The advisory's fix was made in the Scalus path, and the own evaluator has a limit of
its own; this checks it end to end.

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
antithesis/pool/scripts/launch.sh resilient 15m       # validate, then a basic_test run
CONFIG=config-canary antithesis/pool/scripts/launch.sh none 15m   # the canary
CONFIG=config-canary-scarab-vm antithesis/pool/scripts/launch.sh none 15m   # the canary, own evaluator
CONFIG=config-k200 antithesis/pool/scripts/launch.sh resilient 15m  # k = 200
CONFIG=config-late-start antithesis/pool/scripts/launch.sh resilient 15m  # experiment: the earlier start order
python3 -I antithesis/pool/canary/writebits_script.py # regenerate the canary's policies and cost model
```

`launch.sh` takes the fault level and a duration, since the tenant predates `--max-compute-hours`. A 15-minute
`resilient` run cost 18 to 20 compute hours, about 7 of them setup, so a longer run pays for setup once. Snouty
pushes the images and the config image to the tenant registry.

**The trial's tenant did not apply the fault level or the exclusion (checked 2026-11-14).** Its runs record neither
`antithesis.fault_tolerance` nor `antithesis.fault_exclusions` among their parameters, although snouty sends both.
Runs launched at `none`, `resilient` and `high_availability` got the same faults: network partitions and clogs,
`Stopped` included, with clogs naming the driver, and no node pause, throttle or termination and no clock skip.
Read a result as one under those network faults, not under the level named. Before reading a run as a level's, check
its `parameters` (`snouty runs show <id> --json`) and count its fault events that are not network ones
(`snouty runs search <id> 'filter(ev => ev.fault && ev.fault.type != "network")' --count`).
