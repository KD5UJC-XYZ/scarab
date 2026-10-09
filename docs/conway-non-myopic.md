# Restricted pure non-myopic completion and application

Status: Scala source compiles and nine focused tests pass on Java21 in the existing offline Docker image. The bounded native differential and independent Scala comparison now pass for10 admitted raw-bit cases,2 Scala overflow rejections and2 empty-go probes;3 generation probes remain diagnostic-only. This does not remove runtime guards, add a native dependency, authenticate source state, or establish full-state/live-boundary conformance.

## Fidelity blockers identified before implementation

Native PoolRank uses binary64 `(**)` and `log`, then converts each result to Float. Java Math/StrictMath cannot currently be asserted bit-identical to the pinned native GHC/libm implementation. The difference may vanish at Float rounding for many inputs, but no universal or tested-domain proof exists. The model therefore has no approximate likelihood generator: nonempty frozen go pool domains reject generation explicitly. Native Word64 epoch-size subtraction and Natural-to-Word64 conversion have their own out-of-domain behavior; no approximate bounded implementation of those paths is enabled.

Current Scala monetary completion and pulser state omit non-myopic history. The coordinator cannot infer an empty history from zero rewards, an empty body, empty active stake, or epoch0 alone. It needs source-proven `esNonMyopic`, the frozen go **pool-snapshot map**, frozen reward allocation and exact old phase. The new module accepts supplied finite log weights or derives the mathematically empty new-map path from an existing Frozen whose go.pools is empty. This is supplied-state algebra, not an authenticated native import.

## Exact source mapping

Primary authority: `cardano-ledger-shelley-1.19.0.1` in the established warm archive. Exact raw member hashes are in `reference/non-myopic-diagnostic/source-pins.json`.

| Native location | Behavior | Scala mapping |
|---|---|---|
| PoolRank.LogWeight / Likelihood | Stored Float log weights; 100 samples generated | Likelihood stores immutable 100 raw Int bit patterns |
| PoolRank.applyDecay / Semigroup | Float0.9 multiply, Float addition, subtract minimum of sums | completeSupplied; no preliminary normalization, Double intermediate or fused operation |
| PoolRank.Likelihood.Eq | Normalize both, compare Float values | equivalentModuloOffset; raw storage identity remains separate |
| PulsingReward.startStep | Generate new likelihoods for **every** go pool, including zero-block pools | completeFrozen requires exact go pool key domain; nonempty generation remains blocked |
| PulsingReward.completeRupd | updateNonMyopic with frozen old history, rewR and new likelihoods | completion replaces rewardPot with allocation.rewardPot, not paid rewards |
| PulsingReward.updateNonMyopic | Map only new keys; missing history uses100 positive zeros; old-only keys drop | completeSupplied |
| IncrementalStake.applyRUpdFiltered | Replace epoch-state non-myopic field with completed update | applyAtBoundary; absent update preserves the original object |

Pool ordering is unsigned byte order represented by fixed-width lowercase hex. Each likelihood preserves 100 raw binary32 values including signed zero. Arithmetic uses strict Java21 Float operations, separately rounded after multiplication, addition and subtraction. Minimum uses numeric <= with stable tie selection, not Java total-order Float.compare or Math.min. NaN/infinity inputs, intermediate overflow and normalization overflow reject explicitly. This finite100 subset is narrower than native decoding and native zipWith truncation behavior. Raw-bit native differential evidence now covers the ten admitted synthetic vectors only; it does not establish arbitrary-domain agreement.

States and histories are immutable, bounded to4096 pool entries and uint64 coin pots. Hashes include raw bits and pot in deterministic pool order; they are internal supplied-object identities, not native CBOR hashes or authentication. `completeFrozen` binds the pool domain and reward allocation to a checked frozen reward context. `applyAtBoundary` replaces current state from the completed *frozen* history; it does not wrongly require the application-time history to equal the frozen history. It does not itself establish that the caller completed the matching monetary phase.

## Concrete epoch0 to1 path and coordinator handoff

For an authenticated genesis-origin predecessor whose frozen go pool map is empty, `generateForFrozen` yields the exact empty new map without pow/log. Completing that update clears any old likelihood history and replaces the reward pot with frozen rewR, including when monetary rewards are zero. If the old reward phase is absent, preserve the current state; do not synthesize completion. If the phase is pulsing, the coordinator must complete the matching old monetary and non-myopic inputs before application. If go contains a zero-stake/zero-block pool, generation is still required and this restricted generator rejects.

Required retained fields: current non-myopic State; frozen history identity/value; existing frozen context/allocation; exact go pool domain; old Absent/Pulsing/Complete phase; completed non-myopic replacement, linked by the caller to the same monetary completion. Undo/recovery must retain the original object/capsule. No coordinator, codec, runtime admission gate, nonMyopicUpdated flag or seven-effect check is modified in this commit.

The empty-governance worker owns governance effects independently. Native empty EPOCH still updates dormant/future-parameter/DRep-pulser bookkeeping; non-myopic updates are produced at monetary completion and applied before EPOCH. These effects must not be conflated.

The separate native seed diagnostic exposes opaque `nonMyopic`,
`nonMyopicLikelihoods` and `nonMyopicRewardPot` components. This standalone module
does not decode or admit those bytes. A future decoder must preserve raw stored
Float bits rather than exponentiated native likelihood JSON. Pending completed
updates need their own non-myopic state; pulsing updates need the original frozen
history, likelihoods and reward pot. No automatic effect is inferred absent.

## Validation

Nine focused tests cover empty replacement, new/shared/old-only domains, Float rounding, signed-zero storage versus equality, absent/replacement application, identity/shape/coin/domain rejection and overflow, plus checked frozen/allocation binding and nonempty go with empty active stake. They passed with Scala3.3.8, Java21, existing image `sha256:ce5dd881ba207fb485aaebd9bb065ac79a808f26ff52467eca938dd064994203`, network none,2CPU,2GiB/no extra swap, JVM Xmx1200m, private cache/build directory. No host install or new dependency. Only the two owned Scala files were copied back after formatting; unrelated formatted files in the disposable checkout were excluded.

Private test/execution receipts are preserved outside the public tree.
The [portable native manifest](../ledger/src/test/resources/non-myopic/native-provenance.json)
records exact identities. General likelihood generation, native CBOR ingestion,
authenticated seeding and coordinator publication remain unsupported.

Independent review by `review_scalus_packet` found no implementation blocker after adding checked Frozen/Allocation tests and both signed-zero minimum-order probes. The initial expanded-suite attempt passed8/9 and exposed an invalid synthetic delegator index; correcting that fixture produced9/9. At that initial review, native source remained uncompiled/unexecuted; the subsequent bounded execution is recorded below.

## Executed bounded differential

Source `10d30a3a4639c5c42167c870de8bfd4772bdec92` compiled in the approved
offline native attempt. Independent audit verified the 235-unit closure, actual
Scala comparison and owned cleanup. Ten admitted vectors match exact raw bits;
two overflow cases are rejected by the restricted Scala profile; two empty-go
completion/application probes match. Three native generation observations are
diagnostics only. Binary identity is receipt-backed after cleanup.

The exact [native JSON](../ledger/src/test/resources/non-myopic/native-result.json)
and [validated TSV projection](../ledger/src/test/resources/non-myopic/native-comparison.tsv)
are curated synthetic data. `prepare_comparison.py` regenerates that TSV from the
strictly validated original JSON and pinned cases, without implementing floating-point
arithmetic in Python. Curation reproduced the independently compared TSV byte-for-byte.
The default Scala suite pins both files and runs the actual supplied-state model.
Fabricated comparator tests remain separately labelled. CI replays this recorded
golden; it does not build native code or execute a cluster.

No general-generation, authenticated-freeze, coordinator, runtime, full-state or
live-boundary parity claim follows. No guard is relaxed.

## Integration verification

Formatting and 70 focused tests passed: 54 ledger and 16 app checks. This includes
all nine model tests, four comparator tests including the default native golden,
and adjacent governance/boundary/reward regressions. The curated TSV was regenerated
from the validated original native JSON and matched the independent comparison
transport byte-for-byte. A network-disabled two-CPU, 2 GiB container used a private
cache and no private fixtures; owned cleanup was confirmed. No native rebuild or
live run occurred during integration.
