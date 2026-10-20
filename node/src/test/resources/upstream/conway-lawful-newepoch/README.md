# Supplemental lawful NEWEPOCH inputs

**Current.** Twelve bounded, original native inputs generated from the pinned ledger source,
compared independently with its native and Agda rules, and replayed through Scarab's production
`NewEpoch.sts[Conway]`. All twelve upstream properties pass; Scala compares every field of each
applicable native `NewEpochState[Conway]` using the existing native state codec and `Diff`.

The [manifest](manifest.json) records seeds, arguments, fixed parameter-generator size, every
input/output hash, upstream property exit codes, the patched executable and formal archive hashes,
and the complete attempted sample count. Each `result.txt` records the independently evaluated
native/formal acceptance; a zero property exit code also establishes the upstream comparison of
translated outputs. No failing samples were filtered from this set. Earlier setup attempts are separately recorded:
missing exporter directories prevented property completion, and build/link errors occurred before
execution. A preliminary smoke comparison passed; the final batch then exported all twelve
predeclared cases using only the declared observer and generator patches.

## Construction and limits

The original constrained generator has `newEpochStateSpec = trueSpec`
(`libs/cardano-ledger-test/src/Test/Cardano/Ledger/Constrained/Conway/NewEpoch.hs:8-9`). Its
`HasSimpleRep (NonZero Coin)` uses `unsafeNonZero` without an added positive constraint
(`.../Instances/Basic.hs:452-457`). Thus seed 498101 places zero in `pdTotalActiveStake` while the
native `Default PoolDistr` constructor explicitly uses `knownNonZeroCoin @1`
(`libs/cardano-ledger-core/src/Cardano/Ledger/State/PoolDistr.hs:108-109`). The native empty
snapshot likewise uses this sentinel (`.../SnapShots.hs:391`). The old
[invalid input and sized failures](../conway-epochs/manifest.json) remain unchanged and retain
their original classifications. The existing `UpstreamEpochConformanceSuite` still asserts the
precise `pd/totalActiveStake` domain failure on those original bytes.

The separate [generator patch](../../../../../../scripts/oracle/ledger-govcert/lawful-newepoch.patch)
adds three properties without changing the original disabled registration or its generator.
It constructs each input using native defaults, preserving native positive sentinels instead of
repairing serialized data. Parameters come from upstream `pparamsSpec @ConwayEra` at **fixed size
10**, including its positive-field and protocol-version-10 constraints
(`.../Constrained/Conway/PParams.hs:18-20`, `.../Instances/PParams.hs:537-553`). Current and
previous parameters are also installed in the completed ratification state through native
`mkEnactState` (`eras/conway/impl/src/Cardano/Ledger/Conway/Governance.hs:325-335`). This maintains
the native linkage between governance and pending enactment.

Four recorded seeds each select an epoch in 0..50000. Signals are that epoch, its successor, or
three epochs ahead. The two non-successor cases take the non-boundary branch; only the
successor takes EPOCH (`eras/conway/impl/src/Cardano/Ledger/Conway/Rules/NewEpoch.hs:154-185`).
These are constructor-coherent no-action states with no pending rewards, accounts, pools, UTxO,
blocks, or proposals. They establish neither reachability from genesis nor coverage of non-empty
states, reward updates, or an unfinished DRep pulser. Broader family coverage remains tracked by
[#49](https://github.com/KD5UJC-XYZ/scarab/issues/49).

## Reproduction

Use source commit `f649f9751074d2ab3de033fc3912f29c9862c1f5`, formal artifact commit
`b3a4fd47a1144ff1fb994f89b1045e78159e07ae`, and constrained-generators commit
`966f65f3c28cb22fc45220dbd1408baea93b53d8`. Apply
[exporter.patch](../../../../../../scripts/oracle/ledger-govcert/exporter.patch) for observation
and dependency paths, then `lawful-newepoch.patch` for generation. The captured executable also
links the formal artifact's separately declared
[LEDGERS FFI patch](../../../../../../scripts/oracle/ledger-govcert/formal-ledgers-conversion.patch);
that correction does not change the NEWEPOCH computation. Rebuild the formal artifact after
applying that patch before linking the conformance executable. The manifest names this dependency
explicitly; this is not an unmodified formal executable.

Build the Haskell test executable in Docker with GHC 9.10.3 and the installed Cardano VRF
libsodium selected by the linker. The manifest records the actual build command and environment.
For each `kind` in `same`, `next`, `skip` and each seed from 269101 through 269104, first create a
**separate empty directory**, then run the built executable in the reference container:

```sh
SCARAB_CONFORMANCE_EXPORT_DIR="<directory>" <tests-executable> \
  --match "/NEWEPOCH-lawful-<kind>/" --seed=<seed> \
  --qc-max-success=1 --qc-max-size=30 +RTS -N2 -RTS
```

`--qc-max-size=30` is only a QuickCheck upper bound; the supplemental generator explicitly fixes
its parameter subgenerator at size 10 even for the first sample. Preserve every attempted seed,
including failures. Raw build/run logs remain outside the checkout; their hashes are recorded.

Run `node/testOnly *UpstreamLawfulNewEpochSuite *UpstreamEpochConformanceSuite` with
`scripts/sbtw` inside the Docker JDK image. This checks all twelve complete results and retains
the original invalid-input regression.
