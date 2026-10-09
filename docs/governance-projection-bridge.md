# Finite governance payload projection bridge

This prerequisite adds field-level parameter and pool decoding plus an explicitly
supplied fixed-epoch Globals representation. It does not compose a boundary, admit
native seed diagnostics, replace existing synthetic profiles, or change a runtime,
CLI, checkpoint or recovery guard.

## Complete original parameters, checked selected fields

`GovernanceParameterPayload.decode` requires bounded canonical original CBOR and
an independently expected SHA256, then reuses `NativeSeedParameters` to parse the
31-field Conway PV9.0 parameter record. Core `Core/PParams.hs` serializes the list
in Conway `eraPParams` order. Zero-based overlap positions are:

| Position | Meaning | Existing checked projection |
|---|---|---|
| 0 / 1 | Fee per byte / fixed fee | `FeeSize.Parameters` |
| 3 | Maximum transaction size | `FeeSize.Parameters` |
| 14 | Coin per UTxO byte | `MinimumOutput.Parameters` |
| 8 | Optimal pool count | `ConwayRewardStart.Parameters.pool.nOpt` |
| 9 / 10 / 11 | a0 / rho / tau | Reward pool and allocation parameters |
| 12 | Protocol major/minor | Must decode as 9.0 |

The complete original and all 31 original field spans survive. Current and previous
roles have distinct expected source hashes and an ordered combined identity. Fields
outside the selected projection remain byte-bound and shape-checked; full parameter
validity is not claimed. Cost models must be explicitly empty in this first finite
profile. A different protocol version, noncanonical encoding, duplicate map keys,
out-of-range or unsupported field shape, and nonempty cost models reject.

`checkProjections` compares the decoded values with the existing ledger/reward objects.
A caller cannot establish this check merely by supplying matching hashes or canonical
bytes. Tests alter each overlapping parameter while recomputing its correct hash and
still require rejection against the original expected projection.

`NativeSeedParameters.scala` and its existing tests are reused from committed
`9152fef` in the separate native-seed diagnostic branch. The only changes to that
source expose the four already parsed ledger scalars and the bounded parameter
decoding method. Its existing diagnostic contract stays unchanged. Integration with
that branch should retain its existing file and apply this small additive delta.

## Full pool record

`GovernancePoolPayload` decodes the source-defined ten-field `StakePoolState` record:
VRF, pledge, cost, margin, reward credential, owners, relays, metadata, deposit and
delegators. The reward account is a native credential record, not an address byte
string. Complete originals and field spans remain available alongside the typed pool
projection and the retained relay/metadata values. Native source pins and precise
finite restrictions are recorded with the decoder tests. The supported subset requires
PV9 tag-258 duplicate-free owner/delegator sets, definite canonical collections,
uint64 coin values, reduced unit-interval margin, credential tags 0/1 and fixed
hash widths, relay variants 0/1/2 with their exact arity/nullability, uint16 ports,
4/16-byte IP addresses and at most 128 UTF-8 bytes for URL/DNS. Metadata uses the
native StrictMaybe list form; its ByteArray has no invented 32-byte restriction.
Overall payload size is capped at 64 KiB, depth at 16 and CBOR items at 8,192;
individual collections have a 4,096-entry ceiling. These narrower limits do not
claim to accept every native encoding, including indefinite collections.

## Globals has no native wire format

Native `Globals` contains caller-supplied `EpochInfo` functions. Neither inventing
a CBOR encoding nor interpreting the old scalar placeholder as a native object is
valid. `GovernanceGlobals.Checked` is the replacement representation for the new
bridge: explicit typed fields derived from the exact pinned effective-genesis input,
an independent expected diagnostic binding/epoch/slot/network, and a versioned
identity. Its internal identity encoding is not a native Globals serialization.

It retains epoch and slot geometry, ASC, security parameter, maximum supply, KES
dimensions, stability/randomness windows, quorum, network and system start. Window
derivation reuses the existing exact-rational ceiling implementation. The interpretation
is fixed epochs from slot zero. The cached native active-slot logarithm and arbitrary
hard-fork-aware EpochInfo callbacks are not recreated or authenticated.

Projection checks compare reward/timing fields; an optional existing header-context
check additionally binds the exact genesis source and exposed header geometry. This
does not admit the old `ConwayEmptyGovernance.Globals.original` opaque placeholder.
Future composition must accept the typed checked representation explicitly instead
of treating that legacy value as decoded native Globals.

## Remaining composition work

This packet supplies the payload extraction prerequisite only. The one-boundary
coordinator still needs the explicit supported complete-state binding, accounts and
deposit/supply cross-checks, owned frozen non-myopic history and matching monetary
completion, old-mark leadership/new-mark governance separation, atomic attribution,
whole-boundary undo, compaction retention, and recovery/checkpoint refusal. It must
reject nonempty-go generation and a second governance boundary. The finite native
differentials do not authenticate these new hand-built parser test inputs.

## Verification

**31 focused tests passed** after formatting: six parameter bridge tests, six pool
bridge tests, five typed Globals tests and fourteen reused parameter/genesis diagnostic
tests. All fixtures were locally hand-built and labelled; no optional native-result
environment was enabled. This is source-derived decoder verification, not a new
native execution or captured-payload conformance result.

```text
app/Compile/scalafmt
app/Test/scalafmt
app/testOnly lab.GovernanceParameterPayloadSuite lab.GovernancePoolPayloadSuite lab.GovernanceGlobalsSuite lab.NativeSeedParametersSuite
```

The final run took 18.668 seconds using the inspected local
`cardano-public-v023-check:local` image with `--pull=never --network=none --cpus=2
--memory=2g --memory-swap=2g --pids-limit=256`, read-only container root, private
copied build/cache and JVM Xmx1200m. Owned-container removal was confirmed. Source
archives were read locally; no dependency download, native build, live cluster or
shared-cache mutation occurred.

Command, image, full log, receipt and final formatted source hashes are retained
in private execution evidence outside Git.
`governance-projection-source-pins.json` records the exact archive/member hashes.
Independent read-only review approved all three bridge components and their finite
scope. `git diff --check` passed before the local code/test commit.

## Standalone integration verification

Integration passed 77 focused tests and formatting checks, including the bridge,
reused parameter decoder, existing protocol bootstrap, synthetic epoch guard and
default node/coverage command safeguards. No native-result fixture was enabled.
Log SHA256: `794d770e9b1377f17157ea46bd25d356a4e07bb2e5ab20d3027a28fc92798604`. Execution used a private copied
cache, network disabled, 2 CPU and 2 GiB; owned container cleanup was verified.
All eleven archived native source pins were rechecked without native execution.

The shared parameter decoder remains single-source. The supported payload profile
requires empty cost models and its explicit canonical CBOR subset; typed Globals
uses supplied fixed epochs from slot zero, not arbitrary native EpochInfo callbacks.
No coordinator composition, native payload parity, seed admission or runtime/CLI
activation is included in this publication.
