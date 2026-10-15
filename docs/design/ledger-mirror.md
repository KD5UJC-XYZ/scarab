# Ledger-state mirror, step 1

**Status**: Historical. Done 2026-10-15 (stages S0 to S9; S6 folded into S4 and S5). This is the plan as approved; what was built, and every deviation, is in the CHANGELOG sections "ledger-state mirror, stage S0" to "S9". Decision: [ADR 0007](../adr/0007-era-indexed-state.md).

Produced by a Plan agent from (local working data) and the code on refactor/cleanup (fe66c6e0).
Correction to the inventory: Haskell Preview snapshots on disk are `ledger/123932130` and `ledger/125366331` (not 120614390).

## 0. Invariants for every stage
1. Snapshot fingerprint unchanged: no type reachable from `NodeCodecs.nodeSnapshot` changes; golden `2293c5cf7bb77492` (node/src/test/.../storage/CodecSuite.scala:163). New givens/`derives` in companions OK (Codec.auto reads only the Mirror).
2. No rule reads the mirror in step 1; mirror built only from our state. Devnet gates are regressions, except S3 (EraHistory move) and S9 (new NtC answers).
3. `LedgerEncoders.scala` untouched (byte-proven 763/763 NtC answers). New codec checked "new == old bytes"; replaces it in step 2.

## 1. Placement and dependencies
- Ledger types: `ledger/.../ledger/state/`. Consensus/HFC: `consensus/.../consensus/state/`. (Not `models`: models depends on ledger test->compile; ledger could never depend on models.)
- Haskell CBOR codec: `storage/.../storage/haskell/state/` (where step D lives), reusing `models.runtime.{Codec,Writer,DecodeError}`. New edge `storage.dependsOn(models)` (no cycle).
- Deps: monocle-core/macro 3.3.0 (cached), kittens 3.5.0 (verify latest for 3.3; not cached), monocle-law 3.3.0 test (not cached). `-Xmax-inlines:512` already set.

## 2. Type mapping
Leaf vocabulary (`ledger/state/Vocabulary.scala`): opaque Coin, DeltaCoin, CompactCoin, EpochNo, SlotNo, BlockNo, KeyHash[R], ScriptHash, VRFVerKeyHash, Hash32, NonZero[A]; smart constructors -> typed VocabError. Role phantoms StakePool, Staking, Genesis, GenesisDelegate, BlockIssuer, DRepRole, ColdCommittee, HotCommittee. `enum Credential[R]`, `enum WithOrigin[+A]`. Hand-written cats.Order matching Haskell Ord (script creds before key creds, Credential.hs:98-101; DRep KeyHash<ScriptHash<AlwaysAbstain<AlwaysNoConfidence, DRep.hs:67-72) — NOT derived; kittens for Eq/Show only. Maps are SortedMap so iteration = Haskell encoding order.

Eras (`ledger/state/Eras.scala`): phantoms Shelley..Conway; closed witness `enum ShelleyBasedEra[E]`; match types CertState[E], Accounts[E], InstantStake[E], GovState[E], StashedAVVM[E]; GADT refinement reduces them.

| Haskell | Scala mirror | From ours | How |
|---|---|---|---|
| NewEpochState | NewEpochState[E](epoch, bprev, bcur, es, ru: Option[PulsingRewUpdate], pd: PoolDistr, avvm) | epoch, blocksPrevious/Current, rewardUpdate, poolDistribution | BlocksMade Iso; nesPd from `set` snapshot |
| EpochState | EpochState[E](chainAccount, ls, snapshots, nonMyopic) | treasury, reserves | ChainAccountState Iso |
| LedgerState | LedgerState[E](cert, utxo) | flat state | |
| UTxOState | UTxOState[E](utxo: UtxoTables, deposited, fees, gov, instantStake, donation) | Utxo, fees, deposited, donation | UtxoTables opaque handle over our Utxo, encoded map(0) as in Haskell's `state` file |
| Conway/ShelleyCertState | (v,p,d) / (p,d) | | |
| DState | DState[E](accounts, futureGenDelegs: SortedMap, genDelegs, iRewards) | accounts, ptrs, futureGenDelegs, genDelegs, mir | Shelley per-account ptr by inverting ptrs |
| PState | PState(vrfKeyHashes, stakePools: SortedMap[KeyHash[StakePool], StakePoolState], future, retiring) | pools, futurePools, retiring | vrfKeyHashes derived; StakePoolState + derived delegators |
| VState, DRepState | VState(dreps, committeeState, dormant) | dreps, committeeState, dormantEpochs | Iso |
| ConwayGovState | (proposals, committee, constitution, curPP, prevPP, future: FuturePParams, drepPulsing Complete) | gov, params, prevParams | FuturePParams enum moved from QueryAnswers.future (node/.../ntc/QueryAnswers.scala:365-394) |
| ShelleyGovState | (curProposals, futureProposals, curPP, prevPP, futurePP) | ppup, params, prevParams | |
| SnapShots/SnapShot | SnapShots(mark,set,go,fee); SnapShot(active, pools: StakePoolSnapShot) | Snapshot | numDelegators lower bound (gap) |
| PoolDistr | PoolDistr(map IndividualPoolStake, total) | poolDistribution | |
| NonMyopic | NonMyopic(likelihoods, rewardPot) | — | gap, empty |
| PulsingRewUpdate | enum Pulsing / Complete(RewardUpdateH) | rewardUpdate | per-pool attribution missing (gap) |

Reused as-is: ProtocolParams, GovActionState, Proposals, EnactState, RatifyState, PulsingSnapshot, Committee, Constitution, GenDelegPair, Ptr. Monocle Focus lenses in companions.

Consensus/HFC (`consensus/state/`): ShelleyLedgerState[E](tip, nes, transition, peras=None); Bound, Past, Current[A], Telescope[A] (smart ctor: past.size == era index); CardanoLedgerState = Telescope[EraLedgerState] (ByronCurrent(ours) | InShelleyEra[E]); HeaderState(tip: WithOrigin[AnnTip], chainDep: Telescope[ChainDepState]); ChainDepState enum PBft | TPraos | Praos (8 fields, Praos.hs:270-318); ExtLedgerState; top MirrorState(ext, tables, extras: NodeExtras).

NodeExtras (node-only, never exported): plutus, rewardDraft, rewardRegistered, Utxo counters, BootstrapState, ByronState remainder, previousEpochNonce in TPraos eras, snapshot pool residue, prevParamsExplicit, `residue: Residue` (fields with no place in current era; test asserts empty on real states).

## 3. Conversions (`consensus/state/StateMirror.scala`)
```scala
final case class MirrorEnv(geometry: EpochGeometry, forks: HardForkConfig, byron: EraHistory.ByronParams, network: NetworkId)
enum Gap { NonMyopic; RewardAttribution; NumDelegatorsLowerBound; PendingPulsingUpdate; ShelleyTransition }
final case class Mirrored[A](value: A, gaps: Set[Gap])
enum MirrorError { DerivedMismatch(...); EraShape(...); TelescopeLength(...); ... }
trait StateMirror[F[_]]:
  def toMirror(anchor: Option[BlockRef], s: NodeState): F[Mirrored[MirrorState]]
  def fromMirror(m: MirrorState): F[(Option[BlockRef], NodeState)]
object StateMirror:
  def apply[F[_]: Monad](using Ask[F, MirrorEnv], Raise[F, MirrorError]): StateMirror[F]
```
Pure core F = Kleisli[Either[MirrorError, *], MirrorEnv, *]. Era from forks.eraAt. Derived to-mirror: vrfKeyHashes, spsDelegators, StakePoolSnapShot, PoolDistr, FuturePParams, lastSlot/tips from anchor, telescope Bounds from EraHistory.summary. fromMirror recomputes derived fields, raises DerivedMismatch. Confirm on real states first: poolDistribution == snapshots.set contents; futureGenDelegs sorted. EraHistory Bound/EraSummary/summary move to consensus/state with an export alias.

## 4. Laws and tests
StateMirrorLaws (discipline RuleSet): retraction from(to(s)) == Right(s); normalisation; tamper rejection; exact gaps. monocle-law Iso/Lens tests for true bijections. cats-kernel-laws OrderTests for Credential/DRep/KeyHash, plus agreement with LedgerEncoders orderings. Generators (new stage): leaves, PoolParams, GovActionState, ProtocolParams perturbing fixtures/eras genesis, consistent Gen[LedgerState] per era, Gen[NodeState]+BlockRef. Corpus: round-trip at every block of fixtures/eras and Preview/Preprod rule suite states.

## 5. CBOR
Mirror codec = Haskell ledger-state CBOR minus snapshot envelope (field orders per inventory §1.2–1.7). Writer additions: indefinite array/map/break, float64, length policy (definite ≤23 else indefinite, as LedgerEncoders.listHead/mapHead); default stays canonical (models goldens guard). `using ProtVer` (tag 258 ≥9, accounts shape, PParams fields). Envelopes: Shelley ledger state [2,…], Praos [0,…], TPraos v1; [1, ExtLedgerState], CRC, meta, tables are step D. CSLSNAP1 untouched; #83 may later adopt this codec so Haskell version numbers become the schema version.

## 6. Stages (≈1 agent-day each; gated by check + e2e/soak/relay/ntc)
| # | Stage | Done when |
|---|---|---|
| S0 | deps, storage→models edge, vocabulary, eras+witness, Haskell Orders; match-type spike | Order laws; fingerprint unchanged |
| S1 | ledger tree types + Focus optics; FuturePParams moved | compiles; ntc byte-equal |
| S2 | SnapShots, PoolDistr, NonMyopic, PulsingRewUpdate, EpochState, NewEpochState | Iso/lens laws |
| S3 | consensus types, telescope, HeaderState, ChainDepState, ExtLedgerState; EraHistory moved | ntc gate |
| S4 | ledger-side conversion, NodeExtras, Residue, Gap | retraction on eras corpus |
| S5 | consensus-side conversion, StateMirror[F] Ask/Raise | full laws on corpus; residue empty |
| S6 | generators + discipline RuleSet | property laws |
| S7 | ledger-part codec (Writer ext, ProtVer) | decode∘encode; bytes == LedgerEncoders on overlap; goldens unchanged |
| S8 | envelope codecs; fixture-gated byte round trip of Haskell Preview `state`; commit small devnet `state` fixtures (Conway + one pre-Conway) | encode(decode(state)) == state |
| S9 | DebugEpochState (8) / DebugNewEpochState (12) via mirror codec; `--mirror-check` debug flag in soak | ntc equal except documented gap mask |
S0–S2 and S3 independent; S7–S8 parallel to S4–S6.

## 7. Deliberate differences from Haskell
1. UTxO in RocksDB; mirror encodes map(0) like Haskell's `state` file. 2. One flat ProtocolParams (codec selects era fields). 3. StrictMaybe = Option. 4. DRep pulser always Complete (Haskell encodes it so too). 5. Rewards eager at boundary; exporting a Pulsing state refused. 6. Byron current era wraps our ByronState. 7. Role phantoms only in the mirror until step 2. 8. Node bookkeeping in NodeExtras. 9. Improvements kept: typed Gap/Residue; derived fields checked on decode (Haskell trusts them).

## 8. Risks
- Match types under GADT refinement may not reduce in 3.3 → S0 spike; fallback `EraFamily[E] { type Cert; … }`.
- Derivation mismatches (psVRFKeyHashes incl. retiring/future?, numDelegators bound, nesPd == set, previousEpochNonce at TPraos→Praos) → check in S8 against K1 Scala Preview snapshots ((local working data); Preview has 715 psVRFKeyHashes).
- Byte-exact export blockers for step 2: NonMyopic likelihoods (floats), per-pool reward attribution (RewardUpdate.hs:111-117), ShelleyTransition.
- Memory: 40–46 MB state decodes to hundreds of MB → fork JVM for S8; step D needs streaming decoder.
- Writer change vs generated codecs → models golden + cuddle suites.
- kittens/monocle-law fetch; compile time (keep instances in companions).

Critical files: ledger/.../LedgerState.scala; consensus/.../NodeState.scala + Praos.scala; storage/.../NodeCodecs.scala (+ node/src/test/.../CodecSuite.scala); node/.../ntc/{LedgerEncoders,QueryAnswers,EraHistory}.scala; models/.../runtime/{Writer,Codec}.scala; build.sbt.

## Addendum: S0 risk spike result (2026-10-15, scratch only)
Compiled in docker on Scala 3.3.8 ((local working data), RESULTS.md). Match types plus the closed enum witness **work**: GADT refinement reduces `CertState[E]`/`GovState[E]` in each `era match` branch with no casts, including or-patterns, `using` witnesses, existential packs (telescope case), opaque types and bounded `E`. Wrong-era field access fails to compile, as wanted. EraFamily fallback not needed (costs 3 type params, no exhaustiveness).
Conventions it imposes:
1. `copy`/`apply` on era-generic case classes need the explicit era: `LedgerState[Conway](...)`, `ls.copy[Conway](...)`.
2. Monocle: one `Focus` per concrete case class, composed with `andThen` (a path crossing a match-type field is refused).
3. `Eq`/`Show` for era-generic composites are hand-written givens via the witness; leaf types `derives Eq, Show` (kittens 3.5.0, fetched from Maven Central).
4. One match-type case per era.
