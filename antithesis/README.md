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
Its test command `singleton_driver_watch_tips`, which Antithesis runs once per timeline under faults, then reads
every pool's tip every 5 s over its local socket. Its properties, in the fallback SDK's format:

- reachable: the workload starts;
- sometimes: Scarab pool s1 extends its chain; a Haskell pool extends its chain; all three extend within one
  interval; all three report the same tip.

There is no `always`: at k = 5 a partition can split the chain for good, which is the protocol working.

Scarab is v0.2.0-alpha.1's JAR on the Java 25 runtime of `docker/candidate.Dockerfile`; the published
`0.2.0-alpha.1-antithesis` image runs it on Java 21, where forging fails on Java 25 class files. The JAR is linked
into `/opt/antithesis/catalog/` and the node starts from the classpath `/opt/scarab/*`, so Antithesis instruments
it for coverage and thread pausing.

## `pool/config-canary`: the writeBits canary

A positive control: the `pool` cluster set up so that a known, published divergence of alpha.1
([GHSA-vfw2-hhrc-87v5](https://github.com/KD5UJC-XYZ/scarab/security/advisories/GHSA-vfw2-hhrc-87v5), `writeBits`
on more than 4,096 bytes at protocol 11) happens, and two properties that must then fail:

- unreachable: a Haskell pool never refuses a block as invalid (its `ChainSync.Client.Exception` trace, read from
  the pools' logs on a shared volume);
- unreachable: Scarab never refuses a block as invalid (`scarab_validation_errors_total` on s1's metrics).

The genesis starts at protocol 11. cardano-node reads a Conway genesis only with create-env's 251 PlutusV3
parameters, which do not price `writeBits`, so the driver's setup enacts the reference's 361-parameter model at
protocol 11 by a governance parameter change, as mainnet did (a genesis overlay sets the technical group's DRep
threshold to 0). The first command `first_submit_writebits` then submits to s1 a transaction minting with the policy
`canary/writebits_script.py` writes from the reference fixture. Against alpha.1, s1 accepts it, the Haskell pools
take it as phase-2 invalid, and each side refuses the other's block. A fixed Scarab refuses it at its mempool.

```sh
antithesis/pool/scripts/build.sh                      # needs cardano-reference-11.1.3:local and the pinned bases
antithesis/pool/scripts/validate.sh                   # snouty validate: setup complete locally, then cleaned up
antithesis/pool/scripts/launch.sh resilient 15m       # validate, then a basic_test run
CONFIG=config-canary antithesis/pool/scripts/launch.sh none 15m   # the canary
python3 -I antithesis/pool/canary/writebits_script.py # regenerate the canary's policies and cost model
```

`launch.sh` takes the fault level and a duration: the tenant predates `--max-compute-hours`. A 15-minute
`resilient` run cost 18 to 20 compute hours, about 7 of them setup, so a longer run pays for setup once. Snouty pushes the images and the config image to the
tenant registry. Instead, `publish.sh` pushes them to a public registry (by default
`ghcr.io/kd5ujc-xyz/scarab-antithesis`, which must be public for Antithesis to pull), with the compose file naming
each image by digest, and prints the config image's reference for `launch.sh resilient 15m <reference>`.
