<!-- SPDX-License-Identifier: Apache-2.0 -->
# Scheduling under a CPU limit

Part of the [compatibility matrix](../compatibility.md); the register entry is `cpu-limit-scheduling` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: the follower runs on its own work-stealing pool (`--cpu-threads`), apart from the pool that serves the network, timers and the forger; timer lateness and the longest CPU-bound step are measured per epoch and served at `/scheduling`

## Evidence

`SchedulingSuite` (pool vs compute pool: a 20 ms timer under CPU-bound work, thread names, monoid laws), `FollowerSuite` (sessions applied on the pool); Preview from genesis for 31 min at 6 CPUs and at 1 CPU against the base build, started together: -3.2% and +0.2% blocks, 0 starvation warnings in both (at 1 CPU only with G1 and a 20 ms pause goal); devnet `pool` with the Scala node on 2 CPUs and on 1 CPU: 0 missed leader slots of 72 and 88 scheduled

## Explicitly unchecked or unconfirmed

the collector, not the rules, caused the 1-CPU starvation warnings: the default serial collector needs `-XX:+UseG1GC -XX:MaxGCPauseMillis=20 -Xms=-Xmx`; one `pool` run at 1 CPU on a host busy with four syncs missed 17 of 71 leader slots; a CPU-bound step is one uninterrupted pure call (longest 167 ms at 6 CPUs, 512 ms at 1 CPU, Plutus); mempool revalidation and the forger still run on the compute pool; the measurements are exported as `scarab_scheduling_*` and `scarab_cpu_*` at `/metrics` (J3, see the operational parity row)
