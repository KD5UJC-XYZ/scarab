# Synthetic native reward differential reference

The executed Haskell source produced a finite synthetic result that matched the
Scala projection across 11 cases and 33 steps. See the
[comparison record](../../docs/synthetic-reward-native-comparison.md) and
[portable provenance](../../app/src/test/resources/synthetic-reward/native-provenance.json).
This is recorded monetary/progression agreement, not valid-chain evidence or
general ledger/reward parity. No runtime epoch guard changes follow from it.

## Public packet

`Main.hs` is byte-identical to the executed source at
`3c8a83dd7ad92e91135adef91cb7bfe25d0f81d0`; SHA256
`0852605d2644fb855709fa5d895dd8baced1a1e756f9821fa39ceab2a51a58ca`.
It constructs state entirely from fixed constants and the pinned `cases.json`.
Repeated-byte credential/pool hashes are synthetic identifiers, not keys. There
is no captured-chain input, network service, socket or transaction submission.

Included files are the source, Cabal package, Apache-2.0 license, exact cases,
schema and schema/conservation validator. The Cabal file preserves all 16 direct
dependency versions; added license metadata and synopsis do not alter Haskell
source. Machine-specific launchers, closure/project plans, package stores,
compiler paths, operational logs and binaries are retained privately. They are
not part of this portable source packet or the public golden regression.

Native execution used GHC 9.6.7, Cabal 3.16, cardano-ledger-core 1.21.0.0,
cardano-ledger-shelley 1.19.0.1 and cardano-ledger-conway 1.23.0.0. Dependencies
include native libraries; this reference is not all-JVM. Upstream libraries and
this harness use Apache-2.0. Upstream source and dependency binaries are not
redistributed here. This is not a standalone frozen dependency environment;
fresh dependency resolution or byte-reproducible native builds are not claimed.

## Reuse

The default public Scala suite checks the exact recorded golden without a native
compiler. The dependency-free Python transport check can be run from repo root:

```sh
python3 -B reference/synthetic-reward-diff/validate_result.py app/src/test/resources/synthetic-reward/native-result.json
```

In a separately provisioned compatible native environment with dependencies
already available, build `exe:synthetic-reward-diff` with Cabal offline, then run
the resulting binary with `cases.json` as its sole argument and retain stdout.
The equivalent invocation is `synthetic-reward-diff cases.json`. Building and
executing it are separate from public CI and are not triggered by Scala tests.
The original attempt's source/input/output/binary/image hashes are recorded in
the linked provenance; execution logs remain private. No new native run was
needed to integrate this exact golden.

The fixed profile has five credentials, two pools, rho=tau=a0=0 and nOpt=k=1.
Synthetic timing window100 is distinct from derived4k/f=80. Native fresh-RUPD
timing probes use exported applySTS independently at each signal; low-level
pulse/force probes have their narrower scope. Events, non-myopic state, native
snapshot provenance, valid-chain history, full RUPD/NEWEPOCH and runtime safety
are excluded. The separate denied genesis fixture is not used or unblocked.
