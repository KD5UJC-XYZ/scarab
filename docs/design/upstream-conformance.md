# Upstream conformance adapters

Status: current implementation and bounded evidence; the owner-requested source-refresh closeout of [K2 / #49](https://github.com/KD5UJC-XYZ/scarab/issues/49) is being integrated in [PR #273](https://github.com/KD5UJC-XYZ/scarab/pull/273). This is not whole-suite or every-interpreter equivalence.

The Haskell implementations and their formal adapters are independent observations.
A native acceptance, a formal acceptance, equality of their translated outputs, and a
Scarab native-state match are separate results. Original inputs, outputs, source pins,
observer patches, runner arguments and hashes stay in each fixture manifest. Failures,
unsupported inputs and unobserved judgments are never counted as passes.

## Generator execution, replay and freshness

The Scala suites replay committed original outputs of the pinned upstream generators.
They do not contain a Scala translation of QuickCheck's generators, nor do ordinary
PR builds download moving upstream sources. The Docker-based exporters under
`scripts/oracle/` execute the actual pinned Haskell generators and properties in
disposable source copies; manifests retain the original source, observer patch,
dependency plan, executable, seeds, sizes and independently observed outcomes.
Regenerating inputs is an explicit operation, separate from updating expected results.

The [source-freshness workflow](../../.github/workflows/conformance-sources.yml),
tracked by [#272](https://github.com/KD5UJC-XYZ/scarab/issues/272), runs weekly and on
manual dispatch. It downloads GitHub source-tree metadata and compares watched
paths at the existing pins with immutable snapshots of upstream default branches.
The released reward API has its own source pin and is reported separately from
the newer ledger-conformance source pin.
Formal generated Haskell is compared with `master-artifacts`, rather than the
source-only default branch. The JSON artifact lists exact commits, watched paths
and added/removed/changed files. API or incomplete-tree failures fail the check;
source drift is reported without automatically changing pins or declaring a
behavioral discrepancy. Every declared watch prefix must contain files at the pin
or observed head: a typo cannot silently pass as unchanged alongside valid roots.
The artifact records each prefix's endpoint file counts. API tests, test libraries
and goldens and the actual network/Cardano diffusion test roots are included.
Changes outside watched paths remain unassessed.

Run the same read-only check locally:

```sh
python3 scripts/check_conformance_sources.py \
  --output /tmp/conformance-sources.json --summary /tmp/conformance-sources.md
```

This report is not a generator execution or a compatibility verdict. New upstream
inputs still need the actual exporter and Scala replay before adopting a new pin.
The supported Haskell release remains authoritative even when upstream HEAD moves.

The [2026-10-20 source review](../conformance-source-review.md) classifies all 291
watched path changes in the first observation. It confirms released ledger/reward
and Plutus source alignment, separates generator repairs and formal-rule changes
from future-era work, and records missing network watch roots and a DNS reload
publication follow-up. It is a static review, not a new oracle or replay verdict.
Corrected watches subsequently expose **341** events: the historical 291 plus
46 ledger-API and four network events. The
[additional per-file review](../data/expanded-source-watch-review-20261020.json)
retains endpoint hashes and dispositions. The implementation and replay below
address applicable changes without replacing released reference semantics.

### Source-refresh implementation

Topology startup and reload publish literal local roots, retained DNS answers,
flags and valencies before waiting for new DNS answers, following upstream
`RootPeersDNS/LocalRootPeers.hs` at commit `6a49b6f6`. The existing owned refresh
worker handles subsequent lookups. Reconfiguration preserves answers across
source/group moves, cancels obsolete batches and fences late results by generation
and token. Removed public/bootstrap source sets lose old attribution without a
synchronous lookup. Resolver resources resolve through contextual givens;
allocation defects propagate unchanged. Controlled production-runtime tests
cover blocked startup/reload, canceled callers, obsolete lookups, late results
and cleanup; restoring the old code fails the corresponding regressions.
Completed due lookup results retain the existing atomic batch publication
contract; identical upstream per-domain result timing is not claimed.

The [PV11 ledger refresh](../../node/src/test/resources/upstream/conway-pv11-generators/README.md)
executes distinct native UTXO, UTXOW, LEDGER and LEDGERS rules after backporting
the upstream generator/context and bootstrap repairs. Upstream's four property
labels previously all called the UTXO bundle; the exporter invokes the actual
typed rules. Full LEDGER contexts and deposit obligations are explicitly derived.
Generated instant-stake caches were unrelated to their UTxO: the first Scala run
retains nine precise CompactCoin underflows. Separate lawful samples use native
`smartUTxOState` over the unchanged UTxO and signed transactions, with independently
checked cache consistency. No vocabulary bound is weakened or state field masked.

The lawful corpus retains all fixed draws, including rejected LEDGERS attempts,
bootstrap witness controls and native Plutus overlap controls. Its 41 observations
contain **39 distinct rule inputs: 28 accept and 11 refuse**; two duplicate funding
hooks do not increase that count. Accepted complete states and typed refusal
membership compare through production transitions, retaining original signed
bytes. Only unavailable MemPack output-original bytes and Scala change-history
bookkeeping are excluded, as in the earlier adapter.

One unchanged original generated UTXOW input is accepted natively but refused by
the old formal overlap guard. Its pre/post context, environment, state, signal
and native output remain byte-identical in the retained control. The published
correction removes the unconditional UTXO overlap ban and excludes V3 in UTXOW's
allowed languages when inputs overlap. A backport to the pinned generated Haskell
runtime restores translated-state equality; native/formal V2-overlap acceptance,
V3-overlap refusal and disjoint-V3 acceptance remain separate controls. This is
an executable reference repair with the pinned ABI, not a regenerated Agda proof
or a Scarab production-rule change. Original crashes and failed comparisons remain
explicitly retained.

The [published Conway pool vectors](../../fixtures/reference/ledger-api-pool-goldens/README.md)
add a complete 740-byte production `GetStakePoolParams` answer comparison against
the corrected upstream golden. Independently constructed typed relay addresses
reproduce the complete old vector when only IPv4 words are reversed. Scarab and
the released native encoder already use the corrected network order. Direct
relay-query bytes additionally exercise production address decoding; the direct
query remains unsupported. The arbitrary published PoolState example has
inconsistent current-pool/deposit keys, so no fabricated native-state whole-answer
match is claimed.

The [Plutus boundary corpus](../../fixtures/reference/plutus-upstream-boundaries/README.md)
adds 54 released-native requests over six original-byte probes, three languages
and PV9/10/11. All production decoder admissions and failure stages match.
The exact published historical-bounds patch is also compiled on the same full-crypto
released source: 12 older-protocol admissions change in that separately labeled
counterfactual. This is not a whole newer-release execution or a reason to change
the supported historical policy. Published free-variable inputs reach the
released API's scope/setup/evaluation stages, which differ from generic harness
decode expectations. No Plutus production discrepancy is observed.

| Input or generator boundary | Current disposition |
|---|---|
| Generated BlockFetch pipeline policies, ChainSync collect/request choices and TxSubmission2 scheduling choices | Bounded observations and some local wire regressions exist; not every original generated policy is replayed. This is separate from requiring identical thread schedules |
| Mock integer point hashes, generic transaction values and integer peers | Strict Cardano codecs reject incompatible mock domains. Generic production-engine adapters cover some original values separately; no fabricated Cardano replacements count as matches |
| Mux `Uneven` replies over the 2.5 MB production ceiling | Four original native passes are retained as size exclusions; eighteen applicable original passes are replayed unchanged |
| Zero-length generated SDUs | Six original encoder-policy differences remain explicit in the SDU corpus |
| Peras ObjectDiffusion | No adapter or advertised production support; not interchangeable with Leios or TxSubmission2 |
| Generated NEWEPOCH states and proposal-constructor failures | Invalid/failed originals remain unchanged; [#269](https://github.com/KD5UJC-XYZ/scarab/issues/269) adds twelve separately constructed PV10 no-action samples from the upstream parameter specification |
| Other eras, broader seeds and Haskell-specific interpreters | The fixtures establish their named bounded domains, not an exhaustive generator or backend campaign |

The EPOCH generator classification is tracked separately by
[#267](https://github.com/KD5UJC-XYZ/scarab/issues/267), the local formal LEDGERS
conversion repair by [#270](https://github.com/KD5UJC-XYZ/scarab/issues/270), and
implemented reward-likelihood generation by [#268](https://github.com/KD5UJC-XYZ/scarab/issues/268).
The formal crash is not a crash of the running native Haskell node. The thirteen
remaining Plutus exceptions are six constants without a Flat representation, five
counting-versus-restricting budget observations, and two protocol-gated generic
`case` expectations; their [classification](../plutus-conformance.md#3-the-22-cases)
is distinct from the nine reachable cases already addressed.

## Ledger boundary

The source is `cardano-ledger` `f649f9751074d2ab3de033fc3912f29c9862c1f5`, formal
specification `b3a4fd47a1144ff1fb994f89b1045e78159e07ae`, constrained generators
`966f65f3c28cb22fc45220dbd1408baea93b53d8`. These are bounded Conway/PV9 rule samples and PV11 lawful RATIFY integrations,
not a complete property campaign or evidence for every era.

| Native family | Sample and Scarab comparison | Independent upstream result / limit |
|---|---|---|
| GOVCERT | 21 full ConwayCertState outcomes | Native/Agda accept and compare equal |
| DELEG | Five full ConwayCertState outcomes | Native/Agda accept and compare equal |
| POOL | Five full PState outcomes | Native/Agda accept and compare equal |
| CERT | One complete POOL substate from pool registration | Native/Agda accept and compare equal; not every CERT branch |
| CERTS | One complete ConwayCertState, all five actual environment fields decoded | Native/Agda accept and compare equal |
| ENACT | One full EnactState | Native/Agda accept and compare equal |
| GOV | Original empty signal, ten lawful generated nonempty outcomes, a real cast-vote integration and a deposit-refusal control | Native/formal comparisons pass. The refusal retains the native structured deposit mismatch. Original generated environments with zero NonZero VRF counts remain unsupported; they are not normalized or counted as matches |
| EPOCH | Original full native EpochState; separate parameter-consistent controls | Original generator inconsistency retained as exit 1; #267 adds minimal negative/positive controls and eight parameter-consistent PV9 samples |
| NEWEPOCH | Three full native NewEpochState outcomes from unchanged integration hooks | All three native/formal comparisons pass. Separate generated size-zero case is unsupported: zero stake inside NonZero Coin; larger probes fail upstream proposal construction |
| LEDGER | Three full native states and original signed transactions from integration hooks | Native/formal comparisons pass. Inline MemPack UTxO is compared independently, excluding only unavailable raw-output bytes and Scala change-history bookkeeping |
| UTXO / UTXOW | One full native UTxOState each, genuine supplemental judgments of the integration's payment transaction | Both native/formal comparisons pass. Original contexts retained; payment has no certificate/proposal pretransitions |
| LEDGERS | Original singleton plus two accepted dependent multi-transaction batches and two rejected batches | Complete accepted native state matches; typed fail-fast rejection payload must occur among native accumulated failures. Original signed bytes retained. Supplemental cases are native-only; original formal evaluator SIGSEGV/139 remains explicit; #270 separately repairs foreign input conversion and observes accepted-state equality |
| RATIFY | Four original full-state samples, five lawful non-delayed integrations and six original approval-property samples | Native/formal comparisons pass. New integrations exercise successful nonzero DRep/SPO thresholds, missing votes and opposite supplied action orders. Complete RatifyState and native approval booleans are compared |

Fixtures and exact limitations:
[governance](../../node/src/test/resources/upstream/conway-govcert/README.md),
[epoch probes](../../node/src/test/resources/upstream/conway-epochs/README.md),
[lawful native integration and supplemental rules](../../node/src/test/resources/upstream/conway-native-refund/README.md),
[RATIFY](../../node/src/test/resources/upstream/conway-ratify/README.md).
The initial adapter stage retained 49 unique native matching judgments across the
fourteen families (CERT is its named substate), six independent approval cases and
one unsupported NEWEPOCH judgment. Additional functional-stage cases below remain
separate from that historical count. Two repeated LEDGER records in the supplemental run are byte
identical to the integration records and do not add coverage.

Disabled registration is not an applicability exclusion. The pinned NEWEPOCH label
actually invokes the EPOCH bundle; the observer corrects it to constrainedNewEpoch.
UTXO's generated execution context is retained rather than replaced. Lawful native
integration traces supply the transactions and reward boundaries that unconstrained
initial state generation cannot safely establish. All three integration reward
updates are already Complete, not incomplete pulser prefixes.

## Observable functional equivalence

The comparison requires matching acceptance/rejection, meaningful typed failure
payloads, resulting state, wire bytes/order and required completion/cleanup.
Different internal queues, pulse partitions and thread schedules are allowed.
Native Haskell and formal outcomes remain separate observations.

The [governance functional corpus](../../node/src/test/resources/upstream/conway-governance-functional/README.md)
adds nonempty GOV, successful DRep/SPO ratification, missing-vote and supplied-order
controls. The generated GOV samples exercise named voters and proposals but contain
no actual cast votes. A separate lawful integration submits a real vote and compares
its full native GOV output with both formal evaluation and the resulting native ledger
proposal state; Scarab must insert that vote and match the complete result. It exposed a production GOV mismatch: an authorized named committee voter
with an empty inner vote map was incorrectly unknown when all flattened votes were
empty. Authorization lookup now depends on the presence of named voters, matching
`Conway/Rules/Gov.hs` at the pin. Five original generated environments whose `NonZero Word64`
VRF-reference counts serialize as zero remain explicit unsupported inputs, consistent
with the upstream reader's constraint; lawful replacement samples are separate.

The [native batch corpus](../../node/src/test/resources/upstream/conway-native-ledgers/README.md)
executes the real native LEDGERS rule over original signed transactions. Two accepted
batches contain actual output dependencies; reversed order and a repeated spend are
rejected. Accepted comparisons cover complete native state and every inline MemPack
UTxO field, excluding only unavailable output-original bytes and Scala change-history
bookkeeping. Haskell accumulates failures while Scarab fails fast; complete structured
failure membership is checked without requiring identical error order/multiplicity.

The [reward timing corpus](../../storage/src/test/resources/rewards-timing-1.19.0.1/README.md)
uses the unchanged released `cardano-ledger-shelley 1.19.0.1` RUPD and `applyRUpd` APIs
from the node-11.1.3 reference dependency set, a separate source pin from the newer
formal corpus. Fifty-five deterministic schedules retain 341 slot observations and
174 complete/credited-state observations. Tests check completed monetary accounting,
required availability, sparse slots and forced epoch-boundary delivery. Eight genuine
pending prefixes are checkpointed/restored through current native V5 storage before
completion. This is storage continuation, not a reference-process restart. Different
within-window pulse partitions are allowed. #268 removes the former newly generated
likelihood mask: complete encoded API/timing results now compare those vectors too.
The [likelihood corpus](../../storage/src/test/resources/reward-likelihood-1.19.0.1/README.md)
adds eight full-state cases and 72,700 exact Float samples from 727 real Preview pools.
This does not reconstruct historical missing observations; per-state gap provenance
remains #129. No formal RUPD verdict or exact internal pulse-prefix parity is claimed.

The [Mux lifecycle corpus](../../network/src/test/resources/upstream/mux-lifecycle/README.md)
retains 24 close and 24 trailing-byte experiments, each passing original IO and IOSim
properties (96 native observations). Scarab replays the observed response prefixes and
trailing bytes through production Mux. Controlled local checks additionally verify
blocked send/flush/receive release, first typed cause, resource finalizers and cancelled
partial-receive continuation. Native start/stop/restart and pure-exception properties
are also executed, but remain native-only evidence. Scarab's resource/fiber lifecycle
does not need Haskell's internal mini-protocol activation API or identical scheduling.

## Shared production rules and protocols

DELEG/POOL/GOVCERT and CERTS/ENACT adapters call production transitions, rather than
parallel test implementations. RATIFY exposes the native full-state, ordered-signal
transition; its existing pulser wrapper still starts fresh and applies the same stable
priority ordering. Approval predicates are unchanged. Native RATIFY itself does not
sort its supplied signal (`Conway/Rules/Ratify.hs:318–363` at the pin).

ChainSync, BlockFetch and TxSubmission2 use generic typed engines and network runtimes
shared by their strict Cardano facades. Caller-provided codecs preserve original mock
CBOR rather than fabricate Cardano identifiers. Strict Cardano validation, byte limits
and transaction accounting remain policy at the facade. The Genesis intersection
oracle uses upstream's actual findFirstPoint behavior; origin is a real intersection,
not the absence of one. TxSubmission2 keeps the configured Cardano ID request
count independent of the reply-list batch ceiling; the generic runtime separately
applies its caller's outstanding-credit bound. Invalid runtime settings fail with a
typed configuration error before waiting for Init, rather than failing later during
wire encoding. Valid default settings retain the reference's indefinite Init wait.

PeerSharing uses a peer-polymorphic conversation shared by the production address
client/server and the original integer-domain adapter. Its Cardano wire interpreters
retain address validation, amount/byte clamping, over-response refusal and the exact
receive-only deadline. The generic adapter retains unconstrained upstream responses
and uses real linked Mux; it does not fabricate addresses.

Handshake shares its bounded parser and wire codec through contextual `VersionCodec[V]`
and `Ordering[V]` instances. `WireMessage[V]` and `WireReason[V]` keep the version domain
in their types: node-to-node, node-to-client and the original closed mock 0/1/2 domain
cannot select each other's codec. Existing Cardano integer facades convert once through
their closed `NtnVersion`/`NtcVersion` profiles; only the node-to-client wire codec applies
bit 15. Its highest-common selector uses a contextual `DataCodec[V, A, E]` and returns
closed `SelectionError[V, E]`, preserving version values and typed decode/agreement causes
until an explicit wire-refusal conversion. Missing or mismatched codec instances are
compile-time errors, covered alongside the original negotiation regressions. It mirrors `ouroboros-network/framework/lib/Ouroboros/Network/Protocol/Handshake/Client.hs:191–227`
at the pinned revision. Original mock versions 0/1/2 and version-dependent data are
retained in 792 original property observations plus nine controlled cross-version
judgments. Native checks compare the shared pure wire/selection contracts; original
ST/IO/pipe/SimNet verdicts do not establish native interpreter or scheduling parity.

The ordinary BlockFetch/ChainSync codec adapters retain 64 original mock-domain
messages each and all five original property verdicts (640 invocations). They compare
independently exported fields as structural CBOR through the production generic
engines, including every two/three-part partition. Ordinary inline payloads remain
distinct from the tag-24 wrapped and serialised binary-compatibility corpora. These
checks do not establish Cardano payload semantics or independent mock `Serialise`
implementation equivalence. The linked fixture inventories retain exact scope.

Every retained network fiber owns its error scope and returns an Either; its owner
lands the value in the caller's scope, following [ADR 0017](../adr/0017-fibers-end-with-values.md).
The tests use production Mux/runtime paths and FailsOnUncaught. A final ordered result
does not establish pipeline scheduling equivalence.

The practical local follow-up adds eight seeded duplex Mux transport profiles (320 complete messages)
and explicit writer-finalizer/receive-lock cleanup checks. Controlled bulk/control workloads first reproduced
a direct KeepAlive timeout while full BlockFetch delivery progressed. The #258 fair writer now
requires successful control responses under those same loads, preserving full bulk delivery. These local results are distinct
from exported upstream verdicts; virtual service times are not host benchmarks or observed background
disconnects. [Network limits](network-limits.md#bulkcontrol-scheduling-characterization-49) records the
measurements and the implemented writer contract under
[#258](https://github.com/KD5UJC-XYZ/scarab/issues/258); exact scheduler parity is not required.

The subsequent [original starvation-generator replay](../../network/src/test/resources/upstream/mux-starvation/README.md)
adds 32 consecutive native samples: 22 actual property passes, ten implication discards and no native
failures. Scarab replays the 18 size-applicable passing conversations with the original first-100-SDU
ordering assertion. Four native passes exceed its unchanged message limit and remain explicit
exclusions. Native wiring uses response1 for both protocols; twelve unequal original pairs are
separately labelled local controls. A controlled overlap barrier and extra nonvacuity assertion ensure
Scarab actually services both admitted lanes. Neither trace identity nor identical runtime scheduling
is required. Original bytes, native verdicts, observed headers and executable/source provenance are
retained; broader generator and transport campaigns are optional K2 follow-ups in the [roadmap](../roadmap.md).

Local wire regressions complement the exported observations. `ChainSyncBatchSuite`
requires all three requests to arrive before any response, then collects forward,
AwaitReply/rollback and forward instructions before checking the next request's
alignment. `BlockFetchQueuedRangesSuite` queues available/unavailable ranges while
the first batch is delayed, then checks complete ordered batches before sending
ClientDone and checking responder termination. These exercise production client/server
and Mux paths; they are local contract assertions, not additional native property
invocations.

## Network, Plutus and remaining scope

The [network inventory](../../fixtures/reference/ouroboros-network-protocol-codecs/COVERAGE.md)
retains exact registered names and family dispositions. A missing exported upstream
observation does not imply missing local behavior coverage. Existing local tests
cover FIFO batches and backpressure, transaction credit, AwaitAcquire, backend
failures, Mux fragmentation/duplex/bounds, deadlines and cancellation/cleanup.
Scarab's BlockFetch client and transaction requester deliberately serialize their
work; Haskell pipeline policies and ST/IO/pipe interpreters are not separate wire
requirements under [ADR 0008](../adr/0008-networking-as-implemented.md).
Peras remains unadvertised; it is not silently treated as a passing protocol.

The existing [Plutus evidence](../plutus-conformance.md) remains 996/1009 passing,
with thirteen documented harness exceptions. No new all-pass claim is introduced.
The [risk-based closeout audit](https://github.com/KD5UJC-XYZ/scarab/issues/49#issuecomment-6293250618)
distinguishes the remaining reference limitations from optional broader seeded and
rejection campaigns. The retained EPOCH failure is now classified by
[#267](https://github.com/KD5UJC-XYZ/scarab/issues/267): its generated completed
pulser has parameter values inconsistent with the current parameters despite no
enacted actions. Native Haskell keeps current parameters; the formal rule trusts
the inconsistent completed state. The [parameter controls](../../node/src/test/resources/upstream/conway-epoch-parameters/README.md)
retain the original and minimal failures, a matched positive and eight separately
constructed parameter-consistent no-action inputs. No native rule is changed.

The [lawful NEWEPOCH corpus](../../node/src/test/resources/upstream/conway-lawful-newepoch/README.md)
adds twelve fixed-size-10 original parameter draws from upstream `pparamsSpec`
(PV10), wrapped in separately constructed native no-action states with correct
nonzero sentinels and correlated enactment parameters. Same, next and skipped epoch
signals use four recorded seeds each. All native/formal translated states compare
equal; Scala compares complete applicable native states without a field mask.
This is a narrow generator-domain correction, not a claim all unconstrained
NEWEPOCH states or all proposal families are supported. Original invalid/sized
constructor failures remain unchanged.

The original formal LEDGERS observation still supplies no verdict. The local
[#270](https://github.com/KD5UJC-XYZ/scarab/issues/270)
[foreign-conversion repair](../../node/src/test/resources/upstream/formal-ledgers-repair/README.md)
now executes the same formal computation successfully on the unchanged singleton
and two dependent batches; accepted translated states compare equal. Two deliberate
bad batches reject on both sides, with differing failure text retained explicitly.
These repaired-executable observations do not relabel the original crash as a pass.
The bounded operational fairness and timeout-cleanup checks above satisfy the
agreed scope. Reproducing exact Haskell schedules or timer internals is not the
acceptance criterion; broader seeds, action families and backend campaigns remain
optional K2 roadmap follow-ups. Historical missing-state provenance is #129.
No persisted native format is changed; maintained native
snapshots remain V5. Raw upstream trace CBOR is not a native snapshot version.
