# Native Genesis observations

**Status: Current** — differential evidence for #33 against ouroboros-consensus
`82ecba329d7d054340bf707d44fe6e9ac27cec40`.

`density.jsonl` contains 136 complete native observations: 128 unchanged `Arbitrary BlockTree`
inputs (seeds 330001–330128, sizes 10 + seed mod 55), plus eight explicit boundary inputs.
The observer calls the actual exported `sharedCandidatePrefix` and `densityDisconnect` functions.
The Scala suite compares the complete shared fragment, every candidate suffix, the ordered losing
peers, and every `DensityBounds` field. Unknown latest slots, known Origin, AwaitReply/idling,
headers beyond the forecast horizon, equal completed densities and disconnected candidates are
represented. No result fields are masked and no generated cases are discarded.

`bucket.jsonl` retains all 44 event prefixes from 12 leaky-bucket scenarios. The observer calls
`runAgainstBucket` in IOSim, retaining the complete rational bucket state, configuration generation,
paused flag, timestamp, configuration and all on-empty observations. Cases cover both overflow
policies, pause/resume, reconfiguration while paused, picosecond accounting, zero/negative rates,
disabled configuration, depletion a positive balance with less than one microsecond remaining, and the native timer that persists
across pause/resume rather than being rescheduled.
`FillResult` is opaque in the public native API; no constructors or production bodies are modified.

`loe.jsonl.gz` is a lossless, deterministic gzip of 84 full native peer-simulator observations.
All 21 original keys are included: LoE (2), DensityDisconnect (1), LoP (7), CSJ (4), Uniform (6),
and LongRangeAttack (1), with four fixed seeds per key. The wrapper applies each original
`ctAdjustMaxSize` to a base size of 25 before calling the unchanged `ctGenerator`; the resulting
sizes are 2, 5 or 25. No seed is resampled and no case family or negative control is removed.
The original native `ChainDB`, peer simulator and strict IOSim shutdown execute every schedule.
Every original `ctProperty` and the original harness's expected-exception law pass.

The full generated runtime configuration, all tree fragments, original peer schedules and event
timestamps, selected chain (including its immutable anchor), tip block and every component outcome
are retained. Closed native exception tags are exported by matching exception constructors, alongside
complete original result renderings. The compatibility adapter must honor each case's switches,
including tests that deliberately disable LoE, LoP, jumping or timeouts. The exporter writes all
outcomes and exits unsuccessfully if a native case throws or its property fails; it never filters a
failure out of the corpus.

The same 84 runs also retain 1,304 complete `TraceGDDDebug` observations and 45 ordered
`TraceGDDDisconnected` batches, with exact picosecond timestamps and original renderings. The
observer uses IOSim's passive `traceM` events, adding no STM scheduling points. All pre-existing
input and result fields remain identical for every case. `UpstreamGddTraceSuite` replays every
observed decision and compares its shared suffixes, LoE head, complete bounds and ordered refusals.
These decision batches remain distinct from independently scheduled component finalizers.

The separate `UpstreamGenesisReplaySuite` feeds the original schedules into Scarab's real protocol
engines, header admission, fetch coordination, Genesis controls and chain selection. Its restart
interpreter writes received original bodies and selected-chain updates through the production
`ReceivedBlocks` and `VolatileStore` journals, closes them during native downtime, and validates
persisted bytes when reopening. It compares complete selected fragments and every native
peer/component multiplicity. A single typed lifecycle stream also asserts exact cause-to-tag
attribution, actual production refusal decisions before policy termination, cleanup after that
peer's termination, and complete ordered GDD callback-batch consumption. Negative controls
reject omitted/duplicate/misattributed components, fabricated causes and reversed causal orders.

The terminal assertion follows the unchanged upstream contract, rather than promoting one
IOSim finalizer transcript into an extra law. `Genesis/Setup.hs:279–296` admits only the closed
expected exception set; all 21 `ctProperty` branches add their original constraints. In particular,
`Tests/DensityDisconnect.hs:580–591` explicitly permits either `DensityTooLow` or
`CandidateTooSparse`. CSJ constrains downloaded headers and Uniform constrains honest-peer
survival and immutable-chain safety. Neither imposes an additional adversarial refusal sequence.
`PeerSimulator/Run.hs:438–474` gives each peer an independent registry and waits for any of its
four components; cross-peer finalizer interleavings therefore are not a shared protocol ordering
contract. Full original terminal causes and chronology remain retained and printed for comparison.
This does not erase differences: a typed refusal without its actual production callback, a
wrong component, wrong enabled mechanism, early cleanup or a reordered GDD callback batch fails.
Normal no-intersection completion during downtime and a competing GDD refusal are distinguished,
not normalized into one generic failure. Native GDD decisions and their callback order remain
compared exactly in the separate trace replay.

The runtime adapter maps every original mock point bijectively to a Babbage CBOR envelope. The
native mock ledger has no transaction or signature predicates: its identity is committed through
auxiliary metadata and the original segregated body hashes, while all forks retain identical VRF
selection values. The envelope passes the production transport point/body-hash check. Both its
actual encoded length and the header-based fetch estimate are exactly the native 1,000-byte cost;
original slots, block numbers, parent links, schedules and acceptance laws remain unchanged. These
mock envelopes exercise the original mock-ledger scenario, not Cardano transaction or signature
admission. Separate tests check transport integrity against all 731 reference-adopted original
blocks across Byron through Conway, and the private all-era gate exercises ledger admission.

An initial observer-development probe incorrectly bypassed `ctAdjustMaxSize` and failed the mock
`TestBlock` hash's 100-word encoding limit. The corrected wrapper uses the unchanged upstream test's
size adjustment. That failed probe is retained in the private evidence receipt; it is not counted as
a passing reference run.

Mock `TestHash` values are encoded losslessly as the concatenated 64-bit big-endian words of
`unTestHash`, in their original stored order. Slot numbers and rational numerators/denominators
remain exact integers. Header points are sufficient for these pure rules, which never inspect
header signatures, payloads or block numbers. This representation does not replace signed Cardano
headers in production or runtime admission tests.

The native observers and fresh-build exporter are in
[`scripts/oracle/genesis-density`](../../../scripts/oracle/genesis-density/).
The exporter checks the source revision, archives unchanged sources, adds thin executable stanzas,
and records source, observer, executable, dependency-plan and corpus hashes. The immutable native
cache supplies the dependency solver configuration; the serial block-I/O flag only avoids requiring
Linux io_uring for these tests. Observer linking explicitly includes the image's VRF-enabled sodium
archive, because the system sodium library lacks the Cardano VRF ABI. Neither choice modifies any
consensus function or generator. Builds and native execution run inside Docker.

Ordinary Scala tests consume these immutable files without a Haskell toolchain. Regeneration:

```sh
python3 scripts/oracle/genesis-density/export.py \
  --source /path/to/ouroboros-consensus \
  --probe-dir /tmp/scarab/genesis-native-refresh \
  --output-dir fixtures/reference/genesis-density \
  --allow-download
```

The copied original generator inputs and observations retain the upstream
[license](../../licenses/block-fetch-consensus-LICENSE) and
[notice](../../licenses/block-fetch-consensus-NOTICE).

`history-window.jsonl` retains all 250 query outcomes from
`MainHistoryWindow.hs`, which calls the production
`State.Infra.reconstructSummary` and `runQuery (qryFromExpr
(slotToGenesisWindow ...))` directly. It includes Origin, the successor-of-tip
safe horizon on either side of epoch rounding, exact historical telescope
bounds, a known next era, zero-length eras and rejected queries beyond the
exclusive horizon. Both successful windows and complete native failure
renderings are retained. `GenesisHistorySuite` compares every window/refusal;
this is independent of the similarly named historicity policy corpus.

The runtime acceptance laws are the original `ctProperty` branches, rather than equality of a
single IOSim component-finalizer schedule. `UpstreamGenesisLaws` decodes all 21 original `SmallKey`
ordinals into closed cases and checks these conditions for every unchanged generated input:

| Original keys | Required outcome |
|---|---|
| LoE 0–1 | Trunk-tip equality iff timeouts are enabled; respectively zero or exactly one ChainSync timeout. |
| DensityDisconnect 0 | Exact trunk tip and exactly one density or sparse-candidate refusal; no refusal is allowed only with no adversaries. |
| LoP 0–4 | The original zero-or-one patience-expiry requirement for each waiting, horizon and serving case. |
| LoP 5–6 | Trunk-tip equality iff LoP is enabled; respectively zero or exactly one patience expiry. |
| CSJ 0–3 | Honest downloaded headers outside the final jump window total at most distinct headers plus one jump window. |
| Uniform 0–3, 5 | When the honest trunk reaches a Genesis window: no honest peer terminates, the immutable anchor is honest, and its age is at most the Genesis window plus delay plus one slot. |
| Uniform 4 | The immutable anchor remains honest during intentional LoE stalling. |
| LongRangeAttack 0 | The selected tip is adversarial, the original explicit negative control with Genesis disabled. |

Every key also applies `Setup.hs279–296`'s complete allowed-exception constraint to every component.
There are no omitted cases or reclassified runtime failures. The original laws deliberately permit
both density and sparse-candidate refusals, while requiring exact counts in the specific tests that
assert them. All raw typed component outcomes remain available for diagnostic comparison. Complete
selected fragments are additionally compared exactly, a stronger assertion that all 84 maintained
inputs currently satisfy. Independent pure decisions, full bounds and ordered GDD callback batches
remain exact comparisons against the native observations.

The CSJ observation point is the normal received-header callback before validation, matching
`ChainSync/Client.hs1452`; the drain handlers at lines 916–933 discard replies without emitting
`TraceDownloadedHeader`. Header identities and honest/adversarial attribution are retained. Its
boundary uses `headerSlot + jumpSize < succWithOrigin(trunkTipSlot)`, including the original Word64
addition. Uniform accounting preserves Word64 subtraction and the original single honest peer with
id 1 required by `Test/Util/PartialAccessors.hs36–42`.

Direct sparse-candidate refusal is a distinct observation from the asynchronous GDD/LoP
connection callback: native `MiniProtocol/ChainSync/Client.hs:1848` raises `CandidateTooSparse`
at the header gate. The test interpreter observes typed raises and `TimeForecast.Refused` at
that gate, before the owner's terminal result, preserving capture, graft and forecast semantics.
A direct refusal cannot impersonate another mechanism's callback. Negative controls reject
missing, late, misattributed and wrong-mechanism direct decisions. The retained rolling replay
first exposed this previously unexercised observer path at DensityDisconnect seed332002; the
original native property and selected chain passed, and its strong terminal assertion was retained.
