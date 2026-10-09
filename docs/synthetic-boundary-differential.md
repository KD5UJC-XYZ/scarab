# Recorded synthetic native boundary comparison

All seven declared cases agree exactly between the independently prepared Scala
harness `fe07b5b8f2926d1daf7b44fc46bff0fb97c16da7` and native packet
`d3ab6dd276ae582733395f3f1db87bad4028649d`. Every JSON field and array entry
matched after changing only the root producer label. No implementation or expected
value changed for comparison: Scala output remained byte-for-byte identical to
the output produced before the native result arrived. Independent comparison
review confirmed the match, unchanged sources and owned-container cleanup.

## Portable evidence

| Artifact | SHA-256 |
|---|---|
| Exact synthetic case input | `b3dc3877984319078273937888395c51354dd293442005c9830c10ff3d36fc06` |
| Original native output | `0398f64618fe0d16377d8ff8bc9cd65991e6333ca03407c119b6b64e3dea5ed5` |
| Pre-native and compared Scala output | `c8bf5b2548142558e4bcef4027dbf9d0066cd556218f6c85a452332b5bb563e5` |
| Native executable | `a4b2db0247b166af479d5b2530550426afba864c7a5d576f22c2e07b2ade5187` |

The [native golden](../app/src/test/resources/synthetic-boundary/native-result.json)
preserves the exact original bytes; the [provenance manifest](../app/src/test/resources/synthetic-boundary/native-provenance.json)
records portable identities. These are deterministic self-generated synthetic
values using repeated-byte identifiers, not captured chain data, private keys or
provider corpus. Raw logs, machine-specific execution metadata and binaries stay
private. Producer labels alone are not authentication.

## Compared scope

The profile has five original stake rows, two pools, one additional registered
reward recipient, empty current UTxO and total supply 2200. Historical mark/set/go
snapshots differ; previous counts differ from current counts. An incoming update
uses historical fees 200, current fees start at 1100, and the new pre-tick reward
freeze uses snapshot fees 1000. Pool parameters have rho=tau=a0=0 and nOpt=k=1.
Epoch length 500 and randomness window 100 are explicitly synthetic geometry.

| Case | Successor slot | Old reward phase | New phase |
|---|---:|---|---|
| early | 500 | Complete | Absent |
| start-edge | 600 | Complete | Absent |
| start | 601 | Complete | Pulsing |
| force-edge | 700 | Complete | Pulsing |
| late | 701 | Complete | Complete |
| old-pulsing | 500 | Pulsing | Absent |
| old-absent | 500 | Absent | Absent |

The native executable calls NEWEPOCH, TICK and separate pre-tick/post-boundary
RUPD lanes. Compared values include nonzero application balances/pots, SNAP and
fee/count rotation, old-mark leadership, initial allocation, and actual-slot
reward phase/cursor/rewards/deltas. Slots 601, 700 and 701 distinguish immutable
pre-tick calculation inputs from the deliberately separate post-boundary diagnostic.

**The Scala harness composes pure APIs and attaches its pre-tick reward computation
to the boundary projection. It does not independently exercise the coherent
successor coordinator.** No claim follows about full-block validation, cryptography,
governance/non-myopic fields, rollback or persistence. Governance fields are
excluded from comparison, not asserted unchanged. Synthetic omitted-effect
assumptions do not admit arbitrary native states or prove valid chain ancestry.
All runtime epoch guards and checkpoint refusals remain intact.

## Regression and reproduction

The default Scala test suite pins input, native output and pre-native Scala output
hashes, then compares the declared projection. CI replays the recorded golden;
it does not build or execute native code. Relabelled Scala data is used only for
comparator mechanics and mutation tests, never as a native oracle.

Run on the test classpath, selecting a new output file:

```text
app/Test/runMain lab.SyntheticBoundaryDifferentialMain app/src/test/resources/synthetic-boundary/cases.json b3dc3877984319078273937888395c51354dd293442005c9830c10ff3d36fc06 .cache/boundary-scala.json native app/src/test/resources/synthetic-boundary/native-result.json
```

The comparator bounds results to one MiB and requires exact fields, ordered arrays
and decimal-string precision. Prior candidate checks passed 39 targeted tests
(30 ledger, nine app), formatting and the packet's schema/invariant validator.
The later actual-output comparison had zero mismatches without source changes.
Both checks used network-disabled containers limited to two CPUs and 2 GiB,
private caches/outputs, and verified owned cleanup. No private fixtures were
mounted, and no retained-fixture tests ran in these checks.

Recovery remains a separate integration slice. Review approved its original-byte
accounting and historical-scalar preflight fixes; integration is pending. Its
reference-dependent opaque in-memory handoff budgets existing objects, not decoder
allocation or JVM heap use. It is not serialization, disk/crash recovery or durable
controller authentication.
