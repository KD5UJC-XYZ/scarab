# Ledger-state mirror, step 2

**Status**: Accepted 2026-10-15 with the recommended decisions D1 to D10 ([ADR 0009](../adr/0009-ledger-mirror-step-2.md)); in progress (jump 1). Tracked in [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82); step 1 is [ledger-mirror.md](ledger-mirror.md).

This plan was written against `integrate/batch7` at 1f7ea8ef (worktree (local working data)). It draws on the step-1 plan, the inventory, the CHANGELOG sections S0–S9, `docs/findings.md`, the code, and the pinned upstream sources in (local working data).

## 0. One constraint changes the shape of jump 1

As agreed, jump 1 says the node holds the mirror state, the rules do not change, and the conversion layer is retired. Those three cannot all be true at once. If the flat rules (`ConwayRules`, `PreConwayRules`, `Epoch`) keep running on `node.LedgerState` while the node holds a `NewEpochState[E]`, every block has to do `fromMirror → rules → toMirror`. That costs O(state) per block: `toMirror` derives `psVRFKeyHashes`, `spsDelegators`, `StakePoolSnapShot`, `nesPd` and the instant stake from about 112k accounts, and `fromMirror` re-derives them and checks them. A 3M-block preprod replay would not survive it.

The differential harness of jump 2 also needs the conversion, because it compares the old flat side with the new side through `toMirror`. So the conversion can only be retired at the end of jump 2.

**Recommendation (decision D1):**
- **Jump 1** swaps the edges and the consensus half:
  - NtC answers move to the mirror codec, piece by piece, with no per-query whole-state conversion.
  - The snapshot payload becomes the Haskell bytes plus a sidecar (§2.4).
  - The chain-dependent state becomes the live `consensus/state` types.
  - The pulser `EnactState` is aligned.
  - The flat ledger stays the live ledger payload. The conversion leaves the query path and runs only at snapshot write and read.
- **Jump 2** makes the ledger payload `NewEpochState[E]`, behind the harness. The conversion retires only at its last commit, into a `legacy` module used for V2-snapshot migration and test fixtures.
- **Alternative (b):** in jump 1, swap the field types of the flat `LedgerState` to the mirror records mechanically. That pulls the rule changes forward (the derived fields `spsDelegators`, `numDelegators`, the registry and DRep delegations must then be *maintained*), so it is jump 2 under a different name. Not recommended.

## 1. Decisions for the owner (my recommendation first)

| # | Decision | Recommendation |
|---|---|---|
| D1 | Shape of jump 1 | As in §0: edges, consensus half and snapshot payload in jump 1; the ledger payload in jump 2. |
| D2 | When the snapshot payload switches to Haskell bytes | **In jump 1** (V3 = Haskell `[1, ExtLedgerState]` bytes plus a Scarab sidecar), not jump 3. One migration, written with the live `toMirror` (no frozen code is needed yet). The jump-2 flip then does not touch persistence. From jump 1 on, a Scala snapshot can be compared byte for byte with a Haskell `state` file. Jump 3 keeps the #83 value migrations and gap flags; the Haskell *directory* layout (`ledger/<slot>/{state,tables,meta}`, CRC) is step D. |
| D3 | Decoding V2 snapshots after the flat types are deleted | Freeze the flat types, `NodeCodecs` (golden `2293c5cf7bb77492`) and the `to` direction of `LedgerMirror`/`StateMirror` in a new sbt module `legacy`, which `storage` (migration) and tests depend on. The owner then picks a sunset for it, for example two releases after jump 2. Without it, a database that skips the jump-1/2 builds replays. |
| D4 | How deep MTL goes in the rules | The rule *algebras* take `Ask[F, Globals]` (Haskell's `ShelleyBase = ReaderT Globals`), `Stateful[F, S]` and `Raise[F, LedgerFailure]`. The rule's environment and signal are arguments, as in a `TRC`. Leaf predicates stay pure `Either`/`Boolean`. One interpreter on the hot path, measured (§2.3). |
| D5 | `Tx[E]`, `TxOut[E]`, `TxCert[E]` | **Defer to #85 (step 3).** `Tx`, `Prepared`, `Verdicts`, `VerificationCache`, the mempool, the forger and header-first validation all share the flat `Tx`. Jump 2 indexes the *state* only. |
| D6 | Close the String failures in jump 2? | Yes, for the block level: `NodeRules.applyBlock` returns `Either[(String, String), …]` today and becomes a closed `BlockFailure` ADT in the first commit of jump 2 (both sides; the JSON log renders the same `rule`/`reason`). The new rules raise typed `PredicateFailure`s for every era; `PredicateFailure.Unmapped(rule, detail)` gains constructors as each rule is ported and is deleted with the old rules. Collecting several failures, as Haskell's `runTest` does, is out of scope: it only changes rejection lists. |
| D7 | Harness sampling on Preview/preprod | Touched entries compared after every transaction; a full comparison at every epoch boundary and every 2,160 blocks; `halt` on devnet and the fixture; `reseed-and-continue` on the preprod replay, so one 5 h run finds every divergence. |
| D8 | Representing the pulsing reward update | Keep the eager calculation and add a progress counter. `Pulsing(snapshot, pulser)` is derived from the eager result plus the number of blocks pulsed, because `pulseSize = max 1 ⌈n/4k⌉` (`PulsingReward.hs:114`) makes the pulser state a prefix of the credentials in `Map` order. Do not port the incremental pulser. |
| D9 | NonMyopic if the floats cannot be matched byte for byte | Fallback ladder: `StrictMath` → glibc `log` through FFM (the image is JDK 21, where FFM is still preview; it is final from 22) → leave `Gap.NonMyopic` documented. It affects no validation, only the debug answers and `GetNonMyopicMemberRewards`. |
| D10 | VRF registry rule (jump 3) | Adopt Haskell's behaviour. It *changes a verdict*: on a chain that starts at protocol 11, a new pool may take a genesis pool's VRF key (`HardFork.hs:75-78,107` populates only at the HARDFORK into 11). The current `ConwayRules.vrfAvailable` refuses that. |

## 2. Cross-cutting design

### 2.1 Target state type (end of jump 2)

```scala
// consensus/state/NodeState.scala (replaces consensus/NodeState.scala)
final case class NodeState(ext: ExtLedgerState, extras: ConsensusExtras)
//   ExtLedgerState = (CardanoLedgerState = Telescope[EraLedgerState], HeaderState)   (S3 types, now live)
//   EraLedgerState = ByronCurrent(ByronState) | InShelleyEra[E](era: ShelleyBasedEra[E], ShelleyLedgerState[E])
//   ConsensusExtras = boot (BootstrapState, LiteLedger), Byron stats, ProtocolExtras(epoch, TPraos prevEpochNonce),
//                     NodeExtras(plutus, utxo bookkeeping, rewardDraft [until J3.5])
```

- `NodeExtras` shrinks to node bookkeeping:
  - `rewardAmounts`, `prevParamsExplicit`, `pulserAbsent`, `snapshots` (pool residue), `poolDistribution`, `params` (`DroppedParams`) and `Residue` go away. The new rules hold Haskell's own fields, so a residue becomes impossible by type.
  - `Utxo.stake`/`ptrStake` move out of `Utxo` into `UTxOState.instantStake: InstantStake[E]`.
  - `UtxoTables` keeps diff, resident, coin, count, stamp and flushed over RocksDB.
- **Era dispatch has exactly one unpack point:** `NodeRules.applyFull` does `case i: EraLedgerState.InShelleyEra[e] => EraRules[e](using i.era)`, the pattern `MirrorAnswers.mirrored` already uses. Below it, all code is `[E <: EraTag](using ShelleyBasedEra[E])` or concrete `Conway`.
  - Era-range checks of the form `pv >= 9` / `>= 7` / `< 5` that Haskell expresses as *era* type classes become witness methods, for example `ShelleyBasedEra.atLeast(SBabbage)`.
  - Checks Haskell expresses as `hardfork*` protocol-version predicates (`pv >= 11` for the VRF and committee rules, pv 10 `updateDRepDelegations`) stay as protocol-version checks.
  - `TxRules.apply`'s `protocolMajor >= 9` becomes a match on the witness. The equivalence (era ↔ major-version range) is checked by the harness on the eras fixture.
- **Era transitions:** `trait Translate[From <: EraTag, To <: EraTag] { def apply(s: NewEpochState[From], g: Globals): NewEpochState[To] }`, with one instance per adjacent pair as in inventory §1.9:
  - Shelley→Allegra: AVVM to reserves, `StashedAVVM` becomes `Unit`.
  - Allegra→Mary: identity.
  - Mary→Alonzo: PParams upgrade from the Alonzo genesis.
  - Alonzo→Babbage: `d`/`extraEntropy` dropped, `coinsPerUTxOWord`→`coinsPerUTxOByte`.
  - Babbage→Conway: today's `Translate.toConway` plus account/instant-stake/cert-state/gov translation and the initial pulser.
  - A tick crossing several eras (the eras fixture does this at epoch 0) folds `SomeEra.next`.
  - The target era comes from `HardForkConfig.eraAt(epoch, major)`.
  - Order as in Haskell's HFC: translate, then the *new* era's NEWEPOCH. Today `Epoch.preConwayBoundary` runs the pre-Conway boundary and translates afterwards, with a pointer special case; the harness settles whether the two orders agree (risk R3).
- **Byron:** `ByronRules` and `ByronState` are unchanged. `rules.handover` (`LedgerRules.scala:227`) produces `NewEpochState[Shelley]` (Haskell's `translateLedgerStateByronToShelley`).

### 2.2 Rule algebras (Haskell STS rendered with cats-mtl)

`ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules/` (new package):

- `Sts.scala`:
  - the capabilities (`Ask[F, Globals]`, `Stateful[F, S]`, `Raise[F, LedgerFailure]`);
  - a `Stateful.focus(lens)` combinator, so CERTS runs on `Stateful[F, CertState[E]]` focused from `LedgerState[E]` by `LedgerState.cert[E]` (Haskell's `embed`);
  - the interpreter `RuleT`.
- One file per rule family, each an algebra `trait X[F[_], E]` with an instance per era family (Shelley..Babbage share one generic implementation; Conway has its own):
  - `Utxo.scala` (UTXO/UTXOS/UTXOW)
  - `Certs.scala` (DELEG/POOL/GOVCERT; registry and `spsDelegators` maintenance)
  - `Gov.scala` (Conway)
  - `Ledger.scala` (LEDGER/LEDGERS)
  - `Tick.scala` (TICK, RUPD, genesis-delegation adoption)
  - `NewEpoch.scala` (NEWEPOCH/EPOCH/SNAP/POOLREAP/MIR, PPUP adoption, Conway ratify/enact through the existing `Ratify.scala`, `Governance.scala` and `ConwayEpoch` functions on the shared governance types)
  - `Rewards.scala` (attributed `RewardSet`)
  - `Translate.scala`
  - `Bbody.scala` (blocks made, ref-script and ex-units block limits, today in `LedgerRules.scala:474-529`)
- Pure helpers (`tierRefScriptFee`, `minUTxO`, `scriptsNeeded`, `collateralChecks`, `Phase2`, `ScriptContexts`) are reused unchanged. They take `TxOut` and `ProtocolParams`, which are unchanged.
- **Interpreter on the hot path:** the reference is `Kleisli[StateT[Either[LedgerFailure, *], S, *], Globals, *]`. If it costs more than 10% of block-apply time against today's `Either` on a Preview epoch (measured with the existing `Compute.timed(stepLabel)`), replace it by a fused `opaque type RuleT[S, A] = (Globals, S) => Either[LedgerFailure, (S, A)]` with hand-written cats-mtl instances. The algebras do not change.
- **Mempool and forger:** both are Conway-only today (`Rejection.WrongEra`, `ForgeError.UnsupportedProtocol`). They become `MempoolState(base, current: LedgerState[Conway], …)` and `BlockBuilder.select(ticked: NewEpochState[Conway], …)`, and the era is refused at the unpack point by the witness.
- **Header-first validation:** `HeaderRules[S, P]` is already generic. `NodeHeaderRules` builds its `LedgerView` from `nesPd`, the governance-state parameters and `genDelegs`; the forecast stays within 3k/f.

### 2.3 Performance and memory
- **Dispatch:** one witness match per block. Generic code matches on enum ordinals, which costs nothing measurable.
- **Maps:** the real cost is `SortedMap` (byte-wise `Order` on `Credential[R]`) against the HashMap/CHAMP of today. Lookups are O(log n) with byte comparisons, and SNAP builds `ActiveStake` sorted (O(n log n); preprod about 200k accounts, mainnet about 1.3M).
- **Gate per jump:**
  - Preview replay wall time within +15%;
  - `epochBoundary` p99 within +25%;
  - `--memory-report-seconds` peak heap within +20%;
  - `VolatileChain` keeps k states, and persistent trees share structure as today.
- **Fallback (an improvement with a documented reason):** hash maps live, with a sorted view only at encode time. Taken only if the gate fails.

### 2.4 Persisted format (#83)
- **Mechanism fix first (J1.2):**
  - The snapshot (`CSLSNAP1 | u32 2 | fingerprint | payload | sha256`, `LedgerSnapshots.scala:43-120`, with its own `FormatVersion = 2` integer) is not a `FileKind`. Add `FileKind.SnapshotFile` (families `CSLSNAP`, `SCRBSNP`) and `FormatVersion.V3`.
  - `Migrate` steps *every* kind through each shared version, so a V3 bump would rewrite all 29k index files for nothing. Add `FileKind.current: FormatVersion` (the newest version at which the kind's layout changed) and make `Migrate` compare with it; `IndexFile`/`FormatFile`/`IdentityFile` stay at V2.
- **V3 layout (J1.3):** `SCRBSNP3 | sidecar length | sidecar | Haskell state bytes | sha256`.
  - The sidecar is `SnapshotSidecar(extras: ConsensusExtras, gaps: Set[Gap])` under `Codec.auto`, with its own fingerprint golden.
  - The Haskell section is `HaskellLedgerFile.encode` of the `ExtLedgerState` (S8 codec, byte-proven).
  - `Codec.auto` cannot carry the mirror (no `SortedMap` with a custom `Order`, no opaque types, no match-type fields), which is why the Haskell codec carries it.
  - In Byron the Haskell section is absent; the sidecar holds `ByronState`, because our own Byron state has no original `ByronItem` bytes.
  - The UTxO is `map(0)` and stays in RocksDB under the existing `StateBacking` checkpoints.
- **V2→V3 migration (J1.4):** a `SnapshotMigration[F]` in `storage` with `Ask[F, ConsensusMirrorEnv]`. It decodes V2 with `NodeCodecs`, runs `StateMirror.toMirror`, and writes V3 atomically through `AtomicFile`, logged as `formatMigrated`. It runs in `Main` after `NodeSetup` (the environment needs the geometry, forks and Byron parameters). An unknown version refuses the open. No replay.
- **Jump 2:** no format change. At the flip the snapshot is read without conversion, and at J2.9 the V2 reader moves to `legacy` (D3).
- **Jump 3:**
  - V4 when the sidecar shape changes (`rewardDraft` → pulse counter). Migration V3→V4 maps the sidecar and carries `gaps`.
  - **Migrations never invent data.** A state carries the gaps under which it was written:
    - `NumDelegatorsLowerBound` heals itself after two rotations;
    - `PendingPulsingUpdate` heals at the next RUPD;
    - `NonMyopic` and `VrfKeyHashes` (genesis pools) do not heal; they persist and are reported, and resyncing is the operator's choice, never forced.
  - A node synced from genesis on a jump-3 build has an empty gap set: that is the finish line.

### 2.5 Differential harness (jump 2)
- **Where:**
  - `node/src/main/scala/xyz/kd5ujc/scarab/node/differential/` (temporary, in main sources so the preprod replay can run it):
    - `DifferentialRules[F] extends BlockRules[F, Paired]`, with `Paired(old: legacy.NodeState, neu: NodeState)`;
    - `VolatileChain[Paired]`;
    - `--rules old|new|differential` (closed `RulesMode` in `NodeConfig`).
  - Tests: `DifferentialErasSuite` (eras fixture) and `PreviewDifferentialMain` (from Scala Preview snapshot e, replays epoch e's blocks out of the K1 Scala immutable DB and compares with snapshot e+1).
- **Staging, so a divergence names the rule:**
  - `NodeRules` exposes stages `Tick(epochsCrossed)`, `Header`, `Tx(index, txId)` and `Bbody`.
  - Both sides run stage by stage. After each transaction, compare the *touched* entries (inputs and outputs; credentials, pools, DReps and proposals named by certificates, withdrawals and votes) with the per-entry converters of `LedgerMirror` (made public in the package), plus the scalar pots.
  - After `Tick` and at the sampling points, compare the whole state: `toMirror(old)` against `neu` with a new `Diff[A]` type class over the mirror records, written by hand over the witness like `EraInstances`. It returns the first differing path (`nes.es.ls.cert.dState.accounts[<cred>].balance`).
  - Verdicts are compared too: both `Right`, or the same `BlockFailure`/`PredicateFailure`.
- **Gap-aware relations:** `Relation = Exact | NewRefines | Ignored` per `Gap`/`Derived`:
  - `numDelegators` of set/go: new ≥ old;
  - reward attribution: equal per-credential sums;
  - era-lacking parameters: ignored.
  - This lets the Haskell-shaped rules be *more exact* in jump 2 without failing the harness.
- **Reporting:**
  - Closed `Divergence = StateDiffers(block, era, stage, path, old, new) | VerdictDiffers(block, stage, old, new) | Unconvertible(block, StateMirrorError) | NotPorted(era, stage)`.
  - JSON log event `rulesDiverged`, plus a *repro bundle* (pre-state as V3, block bytes, stage), which `DifferentialReplayMain` reruns in seconds. Small bundles are committed under `fixtures/differential/` as regression tests.
  - Policy: `halt`, or `reseed` (adopt the old side, re-seed the new side with `toMirror(old)`, continue).
  - `NotPorted` reseeds and counts, so the harness is on from the first port and its coverage report shows what is left.
- **Removal:** at J2.9 the flag, the package, the `legacy.NodeState` pairing and `MirrorCheck` go. `follow --mirror-check` becomes `--snapshot-check` (V3 encode, decode, `Eq` at each boundary), and `devnet.py soak --mirror-check` is renamed with it.

### 2.6 Feature work that must survive (gate owners)
- **Relay, ChainServer, InboundGovernor:** they read only `VolatileChain` points, so the type parameter changes and nothing else. Gate: devnet `relay`.
- **Forging, mempool, NtC LocalTxSubmission/Monitor:** J2.8. Gates: `pool`, `e2e`, `ntc`.
- **NtC queries:**
  - J1.6 (codec) and J2.7 (reading the mirror directly).
  - The query-specific views stay in `QueryAnswers`: the dormant-epoch shift in `drepStates`, `restrict`, `future`; `pulsed`/`beforeUpgrade` move into the ledger in J1.1.
  - Gate: `ntc` 987/987 and `HaskellStateOverlapSuite`.
- **Byron:** `ByronPreprodSuite`, eras fixture, preprod from genesis.
- **Plutus caches and `Verdicts`/`Prepared`:** tx-level, untouched. Gate: Preview replay plus the `Preprod*` and `Preview*` rule suites.
- **Header-first:** J2.7. Gates: `HeaderRulesSuite`, devnet with `--header-first on`.
- **Scheduling, leader schedule:** `PoolDistribution` comes from `nesPd`.
- **K1 digest:** `StateDump`'s `Projection.node` is re-implemented over the mirror in J2.7, gated by identical digest JSON on Preview before and after.
- **Memory report and `MALLOC_ARENA_MAX`:** unchanged.

## 3. Jump 1: state edges and encoder swap (no behaviour change), about 5–7 agent-days

Goal: NtC answers from the mirror codec; the snapshot payload in Haskell bytes (V3) with migration on open; chain-dependent state on the `consensus/state` types; pulser parameters aligned. The ledger rules and the flat ledger payload stay as they are.

1. **J1.1 Pulser `EnactState` (S5 follow-up).**
   - `LedgerMirror.pulser` (`LedgerMirror.scala:565-585`: node params, treasury 0) and `QueryAnswers.pulsed/initialRatify/beforeUpgrade` (`QueryAnswers.scala:150-195`: PlutusV2 cost model trimmed before the first boundary, real treasury) disagree.
   - Move one `ConwayEpoch.initialRatify` into `ledger`, used by both, and drop `NodeExtras.pulserAbsent` if the corpus allows.
   - Test: `GetRatifyState`/`GetGovState` bytes, mirror codec against `LedgerEncoders`, on all Conway states of the eras capture.
2. **J1.2 `FileKind.SnapshotFile`, `FormatVersion.V3`, per-kind current version** (`ledger/PersistedFormat.scala`, `storage/Migrate.scala`). `FileLedgerSnapshots.FormatVersion` folds into the enum.
3. **J1.3 V3 writer and reader** (`storage/LedgerSnapshots.scala`, new `storage/SnapshotSidecar.scala`, `storage/haskell/state/HaskellLedgerFile.scala`): `toMirror` at write, `fromMirror` at read. A `toMirror` refusal at write is a typed `snapshotRefused` log event and the snapshot is skipped; a snapshot is an optimisation.
4. **J1.4 V2→V3 `SnapshotMigration`**, wired into `Main` after `NodeSetup`.
   - Keep the `2293c5cf7bb77492` golden as the V2-reader golden and add V3 sidecar goldens.
   - `PreviewMirrorMain` becomes a gate: each of the 1,451 V2 snapshots is migrated, read, and compared `Eq` with the direct V2 read.
5. **J1.5 Consensus half live.**
   - `NodeState(header: HeaderState, ledger: node.LedgerState, extras: ConsensusExtras)`.
   - `Praos.tick/validate`, `TPraos.update` and the PBFT window in `ByronRules` move onto `state.ChainDepState` (`PraosState` 8 fields, `TPraosState`, `PBftState`), with the `epoch` and TPraos previous nonce in `ProtocolExtras`. This removes the duplicate `PraosState` name.
   - Adapt `HeaderCheck.forChain[IO, NodeState, ChainDepState]` (`Main.scala:449`), `NodeHeaderRules.at`, `VerificationCache.noteEpochNonce` (`Main.scala:309`), `Forger` (`crossed.praos.epochNonce`) and `HeaderGate`.
   - The consensus side of `StateMirror` becomes an identity, plus the telescope bounds from `EraHistory`.
   - This step can be split off into #85 if the owner prefers.
6. **J1.6 `LedgerEncoders` retired.**
   - `QueryAnswers` writes through `storage/haskell/state` (`Wire`, `HaskellStateCodecs` instances over the shared node types). Shape-different pieces (pools, DReps, accounts, snapshots) go through `LedgerMirror`'s per-component converters (`pState`, `vState`, `conwayAccounts`, `snapShot`), never a whole-state `toMirror`. `QueryView` memoises them per acquired state.
   - `txOut/value/utxo` (UTxO answers; not in the state codec, since the UTxO there is `map(0)`) move to a new `storage/haskell/state/UtxoCodecs.scala`. `PredicateFailureCbor` stays (submission rejections).
   - Delete `node/ntc/LedgerEncoders.scala`. `HaskellStateOverlapSuite` becomes a golden suite over captured reference answers.
7. **J1.7 Docs:** CHANGELOG, compatibility (snapshot format row), running.md (migration note).

**Per-commit gates:** `check`; eras fixture (`EraMirrorSuite`, rule suites); Preview sample (`PreviewMirrorMain` stride 72); for J1.6, the ntc byte equality on the eras capture.

**Jump gates:**
- the full 1,451-state Preview corpus;
- K1 digest against Haskell unchanged;
- preprod genesis-to-tip, plus a restart from a migrated V2 snapshot;
- devnet e2e, soak (`--snapshot-check`), relay, pool and ntc (987/987 with the S9 mask).

## 4. Jump 2: rules onto the era-indexed types (all eras) behind the harness, about 12–16 agent-days

Goal: `NodeState(ext: ExtLedgerState, extras)` is live; new era-indexed rules everywhere; old rules, the flat state and the conversion removed only after agreement on the eras fixture, every Preview state and a preprod replay.

1. **J2.1 Infrastructure.**
   - `ledger/rules/Sts.scala` (capabilities, `focus`, `RuleT`), `Globals` (from `LedgerGlobals` plus geometry).
   - `BlockFailure` ADT in `NodeRules` (D6).
   - `Diff[A]` instances beside the `Eq` givens in `ledger/state/*` and `consensus/state/*`.
2. **J2.2 Harness skeleton.**
   - `node/differential/*`, `--rules`, `DifferentialErasSuite`, `PreviewDifferentialMain`, repro bundles.
   - The new side starts as "reseed everything" (`NotPorted`), which shows zero divergences and measures the conversion cost.
3. **J2.3 Tick for all eras:** `Tick`, `NewEpoch`, `Rewards` (attributed `RewardSet`; `rewardDraft`/`rewardRegistered` stay in `NodeExtras`), `Translate` chain, Byron handover to `NewEpochState[Shelley]`, Conway HARDFORK at pv 10 (`updateDRepDelegations`; pv 11 `populateVRFKeyHashes` is J3.3).
4. **J2.4 Conway LEDGER:** `Utxo`, `Certs`, `Gov`, `Ledger` (port of `ConwayRules.scala`, maintaining `spsDelegators` and DRep `delegs` incrementally), `Bbody`.
5. **J2.5 Shelley..Babbage LEDGER:** one generic instance over the witness (port of `PreConwayRules.scala`): MIR, genesis delegation, PPUP (`PpupRules`), pointer stake in `ShelleyInstantStake`.
6. **J2.6 Rule suites run on both sides.**
   - The 41 test files over the flat `LedgerState` take a `RuleSet` parameter, `Old` or `New` through `toMirror` of the test's input and expectation. Every existing assertion becomes a differential test.
   - Add test builders `ledger/src/test/.../state/Build.scala` for `NewEpochState[E]`.
7. **J2.7 Read paths from the mirror:** `QueryAnswers`/`MirrorAnswers`/`StateQuerySource` with no conversion; `NodeHeaderRules` `LedgerView` forecast; `Projection` (K1 digest); `Scheduling`; `FollowerEvent.LedgerEpoch` from `nes`.
8. **J2.8 Flip:** `--rules new` is the default. `VolatileChain[NodeState]` is the mirror state; `MempoolState`, `BlockBuilder` and `Forger` move to `LedgerState[Conway]`; V3 snapshots are read without conversion.
9. **J2.9 Removal:**
   - Old rules (`ConwayRules`, `PreConwayRules`, `Epoch`, `Stake`, `TxRules`), flat `ledger/LedgerState.scala` and `NodeCodecs` move to `legacy` with `LedgerMirror.to`/`StateMirror.toMirror` (V2 migration and test fixtures).
   - `fromMirror`, `MirrorCheck`, the harness and the `RuleSet.Old` parameter are deleted.
   - The `NodeExtras` fields listed in §2.1 are dropped (sidecar stays V3: the sidecar shape changes, so its golden is bumped and an identity-plus-drop V3a step goes in the same migration chain).

**Per-commit gates:** `check`; `DifferentialErasSuite` with zero `StateDiffers`/`VerdictDiffers` (`NotPorted` allowed until J2.6); Preview differential stride 72.

**Jump gates (before J2.8 and again after J2.9):**
- `PreviewDifferentialMain` over all 1,451 epochs with zero divergences;
- preprod genesis-to-tip with `--rules differential` (reseed mode; zero divergences, and the run must reach the tip);
- then preprod with `--rules new`;
- the perf and memory gates of §2.3;
- K1 digest identical;
- devnet e2e, soak, relay, pool and ntc.

**Bisect protocol:** every commit passes the cheap gates; the repro bundle of the first divergence is the bisect predicate (seconds per step).

## 5. Jump 3: close the gaps, #83 migrations, byte equality, about 7–10 agent-days (NonMyopic float risk flagged)

Goal: debug-query answers byte-equal to the reference with no mask, on a node synced from genesis.

1. **J3.1 Gap ledger:** `gaps` in the sidecar, written per state and carried by migrations. `MirrorAnswers` reports them, and the devnet mask (`scripts/devnet/gapmask.py`) masks only the gaps the state *reports*.
2. **J3.2 `numDelegators` as of snapshot time.**
   - SNAP takes it from `spsDelegators` size, zero-stake delegators included (findings: 727/727 for `mark`). Rotation carries `StakePoolSnapShot` unchanged, so set and go are exact.
   - The migrated lower bound heals after two boundaries, and the flag clears itself.
3. **J3.3 VRF registry tracked, not derived** (D10):
   - POOL inserts and deletes at pv ≥ 11 (`Pool.hs:262-306`, including the re-registration swap);
   - POOLREAP removes dangling and retired keys (`PoolReap.hs:138-235`);
   - HARDFORK into 11 populates (`HardFork.hs:107`);
   - `vrfAvailable` reads the registry.
   - Devnet test: a registration that takes a genesis pool's key is accepted, as the reference accepts it.
4. **J3.4 Pool relays:** `StakePoolState` keeps the registration's relay items as original bytes, so the 11.1.3 IPv4 decode-order fix cannot alter them. Genesis pools take theirs from the Shelley genesis staking section.
5. **J3.5 Representable pulsing update and per-pool attribution** (D8):
   - `RewardPulser(item: Bytes)` becomes typed (`pulseSize`, free vars, remaining credentials, accumulated `RewardAnswer`), derived from the eager result plus a pulse counter;
   - `RewardSnapShot` filled (`rewDeltaR1`, `rewR`, `rewLikelihoods`, `rewLeaders`, …);
   - `rewardRegistered` moves into it;
   - sidecar V4 and the V3→V4 migration.
   - Test: decode and re-encode the mid-epoch Haskell Preview `state` files (slots 125383670 and 125400924), and the Scala state at the same slot.
6. **J3.6 NonMyopic** (D9):
   - likelihoods and reward pot at NEWEPOCH per `PoolRank.hs` (`LogWeight` is a `Float` from `log :: Double`, `applyDecay` in Float, 100 sample positions);
   - oracle test against the 727×100 real floats of the Preview state, computed from Haskell state e plus the blocks of e;
   - the fallback ladder if not bit-equal.
7. **J3.7 ShelleyTransition:** `afterVoting` counts the blocks at or after the voting deadline and resets at the new-epoch tick (`Ledger.hs:565-575,700-720`).
8. **J3.8 Byte equality:**
   - devnet `ntc` compares the four ledger-state answers with **no mask** (gapmask kept only for states that report gaps);
   - `MirrorAnswersSuite` asserts empty gaps from genesis;
   - the K1 comparison becomes a byte diff of the V3 Haskell section against the Haskell `state` at the same slot (`db-analyser --store-ledger`).
9. **J3.9 #83 write-up:** the snapshot migration registry documented (V2→V3→V4, goldens per version, the "never invent, carry gaps" rule); CHANGELOG, compatibility, findings.

**Jump gates:** Preview corpus (byte diff at the Haskell snapshot slots); preprod from genesis; devnet e2e, soak, relay, pool and ntc with no mask.

## 6. Invariants for every commit
1. `check` is green (`-Werror`, scalafmt). Closed ADTs only; no new String control flow.
2. Persisted format: either the goldens are unchanged, or a new `FormatVersion`/sidecar golden arrives *with* a migration and a test that reads the previous version. No forced replay.
3. NtC: 987/987 (masked until J3.8). Jump 1 leaves NtC bytes unchanged.
4. Through J2.8, the harness on the eras fixture shows zero state and verdict divergences.
5. A CHANGELOG entry per commit group, recording every deviation.

## 7. Risks and mitigations
- **R1. Per-block conversion cost.** Avoided by D1; the harness samples (D7).
- **R2. `SortedMap` cost.** The §2.3 gates, with the HashMap fallback.
- **R3. Era-transition order** (Haskell's translate-then-tick against our boundary-then-translate; the pointer special case in `Epoch.scala:118-124`). The eras fixture's Byron→Shelley and Alonzo→Babbage boundaries and preprod's real hard forks, under the harness.
- **R4. Test-suite port size** (41 files, about 214 `.copy` calls in the ledger tests). Running them on both sides through `RuleSet` defers the rewrite to J2.9, helped by the builders.
- **R5. NonMyopic floats** may be unattainable bit for bit. D9.
- **R6. Doubled memory in differential mode** (two states for each of the k volatile states). Run preprod differential with `-Xmx` raised; differential is never the default.
- **R7. Snapshot write cost in jump 1** (`toMirror` plus 40 MB encode, about 1–2 s per snapshot on Preview, measured by S8). Snapshots stay per boundary, as today.
- **R8. Behaviour change hidden inside "refinement"** (VRF registry). Verdict changes are confined to jump 3, where they are flagged and tested on the devnet.

## 8. Hand-offs
- **Step D (snapshots in Haskell layout, import and export):**
  - directory `ledger/<slot>[_suffix]/{state,tables,meta}`;
  - `tables` = `[ {34-byte key → MemPack TxOut} ]` sorted, streamed from RocksDB (the keys are already Haskell's `txid ++ be16 ix`); a MemPack encoder with `scripts/oracle/mempack.py` as the reference;
  - CRC32 of `state` and `tables`; `meta` JSON whose checksum is the CRC32 of the two decimal strings;
  - import: `state` → V3 Haskell section, empty `NodeExtras`, gaps none; `tables` → RocksDB batch load;
  - the Byron `ChainValidationState` codec (we keep `ByronItem` raw today);
  - acceptance with `db-analyser` on a Scala-written directory.
  - V3 stays as Scarab's fast per-boundary snapshot, or step D replaces it (an owner call then).
- **Step 3 (#85, era-indexed consensus):**
  - `Tx[E]`/`TxBody[E]`/`TxOut[E]`/`TxCert[E]`/`Value[E]` match types, and certificate decoding per era (Haskell rejects Conway certificates in Babbage at decode);
  - `Block[E]`, headers per protocol in an HFC-style block;
  - `HeaderState`/`ChainDepState` indexed by protocol;
  - an era-indexed mempool (`GenTx[E]`) and `ApplyTxErr` per era for LocalTxSubmission;
  - `LedgerView` forecast per era;
  - plus J1.5 if the owner moves it there.

## 9. Size
- Jump 1: about 5–7 agent-days.
- Jump 2: about 12–16 (about 3,000 lines of state-touching rules, the harness, the translations, the test port).
- Jump 3: about 7–10 (+3 if NonMyopic needs FFM or a JDK change).
- Total about 24–33. Step 1 took about 10 for S0–S9.

### Critical files for implementation
- (local working data)
- (local working data) (and ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/state/LedgerMirror.scala)
- (local working data) (with storage/Migrate.scala and ledger/PersistedFormat.scala)
- (local working data) (with PreConwayRules.scala and Epoch.scala)
- (local working data) (with LedgerEncoders.scala and MirrorAnswers.scala)

