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
topologies at setup and sets the start time a minute ahead. `s1` starts once both Haskell pools listen, as the
devnet starts it (issue 609).

The `driver` waits until the three pools report the same tip at block 2 or later and reports setup complete.
Antithesis then runs its test commands under faults, many times per timeline:

- `parallel_driver_watch_tips` reads every pool's tip over its local socket every 5 s for a minute;
- `anytime_check_divergence` makes one pass of the divergence detectors, which the watcher also makes at every
  check.

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
- unreachable: Scarab never reports the clock moved back while its readings move forward (a
  `systemClockMovedBack` whose `newTime` is later than its `prevTime`, #629).

There is no `always` on the tips: at k = 5 a partition can split the chain for good, which is the protocol working.

Scarab is a JAR assembled from a commit (`node/assembly` in `scarab-jdk:local`). It runs on the Java 25 runtime of
`docker/candidate.Dockerfile`, and the image's label records the commit. The JAR is linked into
`/opt/antithesis/catalog/`, and the node starts from the classpath `/opt/scarab/*`, so Antithesis instruments it for
coverage and thread pausing.

## `pool/config-canary`: the writeBits canary

A regression check, and on a build with the fix reverted a positive control for the detectors: the `pool` cluster set
up so that a known, published divergence
([GHSA-vfw2-hhrc-87v5](https://github.com/KD5UJC-XYZ/scarab/security/advisories/GHSA-vfw2-hhrc-87v5), `writeBits`
on more than 4,096 bytes at protocol 11) would happen.

The genesis starts at protocol 11. cardano-node reads a Conway genesis only with create-env's 251 PlutusV3
parameters, which do not price `writeBits`. So the driver's setup enacts the reference's 361-parameter model at
protocol 11 by a governance parameter change, as mainnet did; a genesis overlay sets the technical group's DRep
threshold to 0. The first command `first_submit_writebits` then submits to s1 a transaction minting with the policy
that `canary/writebits_script.py` writes from the reference fixture.

- A fixed Scarab refuses the transaction at its mempool, and no detector fires. The canary's sometimes property
  records s1's verdict (accepted, refused or no answer), and holds only where s1 answered.
- Against Scarab before the fix (v0.2.0-alpha.1, locally), s1 accepts it, and the Haskell pools take it as phase-2
  invalid. Each side then refuses the other's block, and the first two detectors fail.

```sh
# from the checkout root; the JAR comes from node/assembly
SCARAB_JAR=node/target/scala-3.9.0/scarab-node-<version>-assembly.jar \
  antithesis/pool/scripts/build.sh                    # needs cardano-reference-11.1.3:local and the pinned JRE
antithesis/pool/scripts/validate.sh                   # snouty validate: setup complete locally, then cleaned up
antithesis/pool/scripts/launch.sh resilient 15m       # validate, then a basic_test run
CONFIG=config-canary antithesis/pool/scripts/launch.sh none 15m   # the canary
python3 -I antithesis/pool/canary/writebits_script.py # regenerate the canary's policies and cost model
```

`launch.sh` takes the fault level and a duration, since the tenant predates `--max-compute-hours`. A 15-minute
`resilient` run cost 18 to 20 compute hours, about 7 of them setup, so a longer run pays for setup once. Snouty
pushes the images and the config image to the tenant registry.
