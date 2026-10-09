# Offline synthetic reward differential: Scala side

Status: the Scala projection/comparison harness passed its synthetic-expectation
checks and subsequently matched actual native output for the exact eleven-case
matrix. See [native comparison evidence](synthetic-reward-native-comparison.md)
for source/input/output identities, phase counts and scope limits.

All implementation is in `app/src/test/scala/lab`, with no runtime entry point,
build dependency, native compiler invocation or production source change:

- `SyntheticRewardProjection` instantiates the approved five-credential/two-pool
  supplied-input profile and projects existing allocation, pool, pulser,
  completion and application values.
- `SyntheticRewardDifferential` reads the bounded exact input schema and compares
  every declared result field. `SyntheticRewardDifferentialMain` is an explicit
  Test-classpath command which writes a new output file and optionally compares
  a supplied result file. It never starts a native process.
- `SyntheticRewardDifferentialSuite` tests the full finite matrix and rejection
  behavior. Expected vectors are independently hand-derived constants, labelled
  `synthetic-expectation`, not generated native output.

## Contract for native-worker relay

Use [the complete result contract](synthetic-reward-result-contract.md), including
the reward-relevant `initial.pools[].snapshot` projection. The case input proposal
and expectations are:

```text
app/src/test/resources/synthetic-reward/cases-proposed.json
app/src/test/resources/synthetic-reward/expectations-proposed.json
```

Input SHA256:
`14d229647bcf67312d5979b4632d137c23177ac3dcf20356963b6cf193e97fb9`.
The native worker adopted these exact canonical bytes and aligned result schema.
The native comparison used one input identity; it did not translate independent
case files or alter expected values.

The fixed profile uses rho=tau=a0=0 and nOpt=k=1. Eleven cases cover base
progression, empty input, partial force, initial force, late RUPD start, both
timing windows, fees100, fees10, A3/B1 uncapped pool performance, and registration
application. The profile constants match the approved plan and prior Scala
tests; historical go is supplied and not a derivation from current UTxO.

All integer/rational values are decimal strings, preserving arbitrary-precision
calculations without JSON floats. Rewards preserve credential constructor, role,
pool, zero leaders and omitted zero members. Native remaining-credential order
is compared exactly. Internal Scala/native identity hashes are not compared.
Expected input identity is SHA256 of the exact canonical case-file bytes.

Result JSON object ordering is irrelevant. Unknown/missing fields, changed types,
array ordering, duplicates, field values and case/step domains cannot pass exact
projection equality. Input JSON must be canonical sorted-key compact bytes plus
one LF. Input limit is64 KiB, at most32 cases and32 steps/case. Result limit is
1 MiB and the existing JSON reader additionally bounds nesting/nodes and rejects
duplicate keys and malformed UTF-8. Result schema bounds are enforced by exact
equality to the generated bounded projection. At most16 mismatch paths are
reported. Conservation is recomputed from the supplied result after equality:
completed reward/delta conservation, registered credit totals, unregistered
totals and application pots plus balances.

## Timing and provenance separation

Window100 is explicitly synthetic; 4k/f for k1 and f1/20 is80. The separate
derived80 case preserves that distinction. `start`, `pulse` and `force` actions
describe the low-level native calls on this finite valid schedule. Direct start
is restricted to S < slot <= F so the Scala pulser cannot silently force a
late start while native startStep would still be Pulsing.

`rupdFresh` means the native worker must independently invoke exported RUPD via
applySTS from SNothing at each supplied slot. The Scala projection maps the
waiting boundary to Absent and uses the existing bounded pulser for later
signals. Scala expectation agreement does not test native timing dispatch.
Native direct pulse/completeStep calls cannot establish RUPD timing agreement.

The required producer field prevents accidentally accepting expectation files
in a requested native comparison. It is still a caller-supplied label, not
native-output authentication. Before describing native evidence, separately
record exact source/build/image/compiler identities, invocation, input bytes
hash, executable hash and raw output hash. This harness does not infer those
facts from JSON or grant provenance to a label.

## Focused commands

```text
app/testOnly lab.SyntheticRewardDifferentialSuite
app/Test/runMain lab.SyntheticRewardDifferentialMain app/src/test/resources/synthetic-reward/cases-proposed.json 14d229647bcf67312d5979b4632d137c23177ac3dcf20356963b6cf193e97fb9 .cache/scala-proposed.json synthetic-expectation app/src/test/resources/synthetic-reward/expectations-proposed.json
```

Arguments are input path, expected input SHA256, new Scala output path, then
optional comparison producer and result path. Omit the last two to emit only
the Scala projection. The output file must not exist, preventing overwrite of
source input or another artifact. Select `native` only with separately verified
native output from the exact input bytes and aligned schema.

Testing uses existing offline Docker image `cardano-public-v023-check:local`,
network none, at most2 CPUs/2 GiB and a private worktree cache. Formatting and
diff checks are included. Log `.cache/synthetic-reward-scala-tests.log` and CLI
projection `.cache/scala-proposed.json` remain outside Git.

No native build, live node, captured or blocked fixture, public fetch, host
installation, keys or runtime publication was used. This finite matrix does not
establish general reward arithmetic parity, event/non-myopic equality, native
go provenance, full RUPD/NEWEPOCH validation or runtime authority.

Validation: all 4 focused tests passed across the 11-case matrix, and the CLI
comparison against synthetic expectations passed. Independent read-only review
approved the Scala-side deliverable. The later actual native comparison is recorded separately in the linked evidence.


The default public suite also checks the separately labelled recorded native
golden, with exact input/output hash pins. This does not replace the four original
synthetic-expectation/rejection tests or invoke native code. See the comparison
record for provenance and the finite-profile limits.
