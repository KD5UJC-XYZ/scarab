# Upstream conformance adapters

Status: current implementation and bounded evidence; [K2 / #49](https://github.com/KD5UJC-XYZ/scarab/issues/49) remains in progress.

The Haskell implementations and their formal adapters are independent observations.
A native acceptance, a formal acceptance, equality of their translated outputs, and a
Scarab native-state match are separate results. Original inputs, outputs, source pins,
observer patches, runner arguments and hashes stay in each fixture manifest. Failures,
unsupported inputs and unobserved judgments are never counted as passes.

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
| EPOCH | One full native EpochState | Both accept, but native/formal outputs differ and upstream exits 1 |
| NEWEPOCH | Three full native NewEpochState outcomes from unchanged integration hooks | All three native/formal comparisons pass. Separate generated size-zero case is unsupported: zero stake inside NonZero Coin; larger probes fail upstream proposal construction |
| LEDGER | Three full native states and original signed transactions from integration hooks | Native/formal comparisons pass. Inline MemPack UTxO is compared independently, excluding only unavailable raw-output bytes and Scala change-history bookkeeping |
| UTXO / UTXOW | One full native UTxOState each, genuine supplemental judgments of the integration's payment transaction | Both native/formal comparisons pass. Original contexts retained; payment has no certificate/proposal pretransitions |
| LEDGERS | Original singleton plus two accepted dependent multi-transaction batches and two rejected batches | Complete accepted native state matches; typed fail-fast rejection payload must occur among native accumulated failures. Original signed bytes retained. Supplemental cases are native-only; original formal evaluator SIGSEGV/139 remains explicit |
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
within-window pulse partitions are allowed. The pre-existing J3.6 exclusion of newly
generated non-myopic likelihood vectors remains explicit; all monetary fields stay
compared. No formal RUPD verdict or exact internal pulse-prefix parity is claimed.

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

## Network, Plutus and remaining scope

The [network inventory](../../fixtures/reference/ouroboros-network-protocol-codecs/COVERAGE.md)
retains exact registered names and family dispositions. It distinguishes the bounded
generic Handshake and ordinary-codec adapters from remaining BlockFetch pipeline,
runtime/interpreter, broader mux transport/starvation and portable timer work, broader seeds, typed
mock-domain gaps and unsupported Peras ObjectDiffusion.
Peras remains unadvertised; it is not silently treated as a passing protocol.

The existing [Plutus evidence](../plutus-conformance.md) remains 996/1009 passing,
with thirteen documented harness exceptions. No new all-pass claim is introduced.
Further applicable adapter coverage, broader seeded campaigns and rejection branches,
portable timer laws, upstream failure investigation and final issue completion
remain tracked by #49. No persisted native format is changed; maintained native
snapshots remain V5. Raw upstream trace CBOR is not a native snapshot version.
