// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import monocle.Lens
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Block,
  CollectError as FlatCollectError,
  Horizon,
  LedgerDefect,
  LedgerFailure,
  PlutusFailure,
  PlutusStats,
  PosixClock,
  PredicateFailure,
  ProtocolParams,
  RewardAccount,
  TestLedger,
  Tx as FlatTx,
  TxEnv,
  TxIn,
  TxOut as FlatTxOut,
  Utxo,
  UtxoFailure,
  UtxosFailure,
  UtxowFailure
}
import xyz.kd5ujc.scarab.ledger.era.babbage.{
  BabbageEraUTxO,
  BabbagePParams,
  BabbageTestKit,
  BabbageTx
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  AlonzoUtxoEvent,
  AlonzoUtxoPredFailure,
  AlonzoUtxosEvent,
  AlonzoUtxosPredFailure,
  BabbageUtxo,
  BabbageUtxoPredFailure,
  FailureDescription,
  TagMismatchDescription,
  UtxoContext,
  UtxoEnv,
  UtxoPredFailure,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.ledger.rules.{
  Counted,
  Globals,
  Judge,
  Judgment,
  RuleT,
  ShelleyUtxo as FlatUtxo,
  StAnnTx as FlatStAnnTx,
  Sts,
  Tick,
  UtxoEnv as FlatUtxoEnv,
  contramap,
  focus
}
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  Build,
  FlatView,
  NewEpochState,
  ShelleyBasedEra,
  UTxOState,
  UtxoTables
}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.{LocalSubmission, Rejection}
import xyz.kd5ujc.scarab.node.ntc.{PastHorizonReferenceSuite, PreConwayFailureCbor}
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.Family
import xyz.kd5ujc.scarab.plutus.{EvalRequest, ScriptEvaluator}
import xyz.kd5ujc.scarab.protocol.{Era as ProtocolEra, ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Babbage's UTXO on the era types whole (`BabbageUtxo.utxoTransition`, cardano-ledger f649f975
  * `Babbage/Rules/Utxo.hs:415-447`): Babbage's predicates (`babbageUtxoValidation`), Babbage's
  * UTXOS as its sub-rule and the change of the UTxO state, with Babbage's failure family and its
  * injections, held to the flat UTXO (`ShelleyUtxo.sts` at Babbage, with UTXOS and PPUP under it)
  * and to the reference's recorded verdicts. Both rules run two levels below the root, where a
  * submitted transaction's UTXO runs (LEDGER, UTXOW, UTXO), so the failures are in the order the
  * reference reports them ([[underLedger]]):
  *
  *   - the 22 Babbage V1-context submissions, the 32 Babbage cases of the refusal corpora with
  *     their states, under each reference build: the same verdict, the same failures whole and in
  *     order, the same state after (the UTxO set and its totals, deposits, fees, instant stake,
  *     governance state, donation) and the same scripts' counters; the reference's verdict where
  *     UTXO decides it, and its refusal byte for byte where UTXO alone refused;
  *   - the three Babbage cases of the forecast-horizon corpora with the era history of their
  *     cluster at the recorded tip, both for the forecast and the scripts' clock: the same, and the
  *     reference's refusal byte for byte;
  *   - the 17 Babbage bodies of `fixtures/reference/pre-conway-utxo` under each build: the same,
  *     and the reference's recorded verdict of each predicate it measured;
  *   - crafted cases for each predicate the corpora do not reach, every predicate of the parameters
  *     at once, and a phase-1 failure beside a script that contradicts its flag.
  *
  * The flat UTXO has no `validateOutsideForecast` (`Alonzo/Rules/Utxo.hs:362-385`): the V1-context
  * and refusal cases are judged with one era that never ends ([[BabbageRecorded.covering]]), the
  * forecast-horizon cases with their cluster's, and the predicate is held to the reference's
  * semantics on a crafted era history that ends before the slot.
  */
class BabbageUtxoSuite extends FunSuite:
  import BabbageRecorded.*

  private given ShelleyBasedEra[Babbage] = ShelleyBasedEra.SBabbage
  private val t = EraTx.babbage
  private val builds = ReferenceBuild.values.toVector

  // the counts the corpora give, asserted so that a loader that loses a case fails
  private val AloneCases = 15
  private val MeasuredCases = 17

  // ---- the era rule, one level down, in one judgment

  private type Rec = UtxoPredFailure[Babbage]
  private type Events = Chain[AlonzoUtxoEvent[Babbage]]
  private type Defect = Either[Either[ScriptVerdictUnavailable, UtxoStateDefect], LedgerDefect]
  private type S = (UTxOState[Babbage], ShelleyGovState[Babbage], Events)
  private type F[A] = RuleT[Rec, Defect, S, A]

  private object EraRun:
    import RuleT.given
    given Stateful[F, UTxOState[Babbage]] =
      summon[Stateful[F, S]].focus(Lens[S, UTxOState[Babbage]](_._1)(u => s => (u, s._2, s._3)))
    given Stateful[F, ShelleyGovState[Babbage]] =
      summon[Stateful[F, S]]
        .focus(Lens[S, ShelleyGovState[Babbage]](_._2)(g => s => (s._1, g, s._3)))
    given Tell[F, Events] = new Tell[F, Events]:
      val functor: Functor[F] = summon[Monad[F]]
      def tell(l: Events): F[Unit] = summon[Stateful[F, S]].modify((u, g, e) => (u, g, e ++ l))
    given Raise[F, ScriptVerdictUnavailable] =
      summon[Raise[F, Defect]].contramap(e => Left(Left(e)))
    given Raise[F, UtxoStateDefect] = summon[Raise[F, Defect]].contramap(e => Left(Right(e)))
    given Raise[F, LedgerDefect] = summon[Raise[F, Defect]].contramap(Right(_))

    def run(
        j: Judged,
        context: UtxoContext[Babbage],
        evaluator: ScriptEvaluator[Id],
        policy: RuleT.Policy = RuleT.Policy.Accumulate
    ): Either[Defect, Either[NonEmptyChain[Rec], S]] =
      judged(j, context, evaluator, policy)

    /** UTXO judged with `ValidateNone` (`Judge.validateNone`): no predicate checked and no labelled
      * clause run.
      */
    def runUnchecked(
        j: Judged,
        context: UtxoContext[Babbage],
        evaluator: ScriptEvaluator[Id]
    ): Either[Defect, Either[NonEmptyChain[Rec], S]] =
      judged(j, context, evaluator, RuleT.Policy.Accumulate)(using Judge.validateNone[F, Rec])

    private def judged(
        j: Judged,
        context: UtxoContext[Babbage],
        evaluator: ScriptEvaluator[Id],
        policy: RuleT.Policy
    )(using Judge[F, Rec]): Either[Defect, Either[NonEmptyChain[Rec], S]] =
      given ScriptEvaluator[F] = request => Monad[F].unit.map(_ => evaluator.evaluate(request))
      given Phase2Deferral[F, Babbage] = Phase2Deferral.now
      val pp = paramsOf(j)
      val stAnnTx =
        AlonzoStAnnTx.mkAlonzoStAnnTx[Babbage](j.clock.posix, pp, eraUtxo(j), Map.empty, j.typed)
      val gov = right(s"${j.name} governance state")(
        ShelleyGovStateRecord.fromStored[Babbage](j.state.gov)
      )
      Judgment
        .judge(
          underLedger[F, Rec](
            BabbageUtxo.utxoTransition[F, Babbage](UtxoEnv(j.slot, pp, j.cert), stAnnTx, context)
          ),
          policy
        )(j.globals, (j.state, gov, Chain.empty))
        .map(_.map(_._1))

  /** `fa` two levels below the root, where a submitted transaction's UTXO runs: Babbage's mempool
    * applies LEDGER (`defaultApplyTxWithValidation @"LEDGER"`, `eras/babbage/impl/src/Cardano/
    * Ledger/Babbage.hs:67`, which runs `ruleApplyTxValidation`, `Shelley/API/Mempool.hs:283-293`,
    * cardano-ledger f649f975), whose UTXOW runs UTXO. At that even depth the reference reports
    * UTXO's failures in the reverse of their order and UTXOS's in theirs (`Judgment.flatten`).
    */
  private def underLedger[M[_], R](fa: M[Unit])(using judge: Judge[M, R]): M[Unit] =
    judge.nest(judge.nest(fa))

  private def paramsOf(j: Judged): PParams[Babbage] =
    PParams[Babbage](right(s"${j.name} parameters")(BabbagePParams.fromStored(j.pp)))

  private def eraUtxo(j: Judged): UTxO[Babbage] =
    right(s"${j.name} UTxO")(BabbageEraUTxO.fromStored(j.known))

  /** The time of slot 0 on the case's clock: the system start UTXO's forecast is taken from. */
  private def systemStartOf(clock: PosixClock): xyz.kd5ujc.scarab.ledger.SystemStart =
    clock match
      case PosixClock.Forecast(start, _) => start
      case other =>
        xyz.kd5ujc.scarab.ledger.SystemStart(
          java.time.Instant.ofEpochMilli(right("the system start")(other.posix(0)).toLong)
        )

  /** UTXO's context for `j`: its network, its clock's system start and era history, the policy, and
    * the build's injection of Shelley's failures.
    */
  private def contextOf(
      j: Judged,
      validation: TxEnv.Validation,
      build: ReferenceBuild
  ): UtxoContext[Babbage] =
    UtxoContext[Babbage](
      j.globals.networkId,
      systemStartOf(j.clock),
      j.epochInfo,
      validation,
      BabbageUtxo.injectShelleyFailure(build)
    )

  // ---- the flat rule, one level down

  private type G = RuleT.Judged[PredicateFailure, LedgerFailure, Counted[UTxOState[Babbage]]]

  private def flatRun(
      j: Judged,
      validation: TxEnv.Validation,
      build: ReferenceBuild,
      evaluator: ScriptEvaluator[Id]
  ): RuleT.Outcome[PredicateFailure, LedgerFailure, Counted[UTxOState[Babbage]], Unit] =
    import RuleT.given
    val ctx = j.ctx.copy(validation = validation, referenceBuild = build, evaluator = evaluator)
    underLedger[G, PredicateFailure](
      FlatUtxo
        .sts[Babbage]
        .transition[G](FlatUtxoEnv(ctx, j.pp, j.cert), FlatStAnnTx(j.flat, Utxo(j.known)))
    )
      .judge(RuleT.Policy.Accumulate, j.globals, Counted(j.state, PlutusStats.zero))

  // ---- what each side decided, compared as one type

  /** A failure of UTXO: one with no script in it as the flat failure, whose projection it is;
    * UTXOS's collection and tag mismatches by their contents, each failing script by its cause and
    * request.
    */
  private enum Seen:
    case Utxo(failure: UtxoFailure)
    case Collected(errors: List[Collect])
    case PassedUnexpectedly(flag: Boolean)
    case FailedUnexpectedly(flag: Boolean, failures: List[(EraUtxoRun.Cause, Option[EvalRequest])])

  private val stored: StoredTxOut[Babbage] = StoredTxOut.babbage

  private def mismatch[A, B](m: Mismatch[?, A])(f: A => B): xyz.kd5ujc.scarab.ledger.Mismatch[B] =
    xyz.kd5ujc.scarab.ledger.Mismatch(f(m.supplied), f(m.expected))

  private def rewardAccount(a: xyz.kd5ujc.scarab.ledger.state.AccountAddress): RewardAccount =
    RewardAccount(a.networkId.wire, FlatView.credBack(a.id.credential))

  /** An era failure of UTXO as the flat failure it projects to, or the failure of UTXOS with a
    * script in it, which [[seenOf]] compares by its contents. Every constructor is named.
    */
  private def flatUtxoOf(f: Rec): Either[AlonzoUtxosPredFailure[Babbage], UtxoFailure] = f match
    case BabbageUtxoPredFailure.AlonzoInBabbageUtxoPredFailure(a) => flatAlonzoOf(a)
    case BabbageUtxoPredFailure.IncorrectTotalCollateralField(provided, declared) =>
      Right(UtxoFailure.IncorrectTotalCollateralField(provided.value, declared.value))
    case BabbageUtxoPredFailure.BabbageOutputTooSmallUTxO(outs) =>
      Right(
        UtxoFailure.BabbageOutputTooSmall(
          outs.toList.toVector.map((o, c) => (stored.toStored(o), c.value))
        )
      )
    case BabbageUtxoPredFailure.BabbageNonDisjointRefInputs(ins) =>
      Right(UtxoFailure.BabbageNonDisjointRefInputs(ins.toList.toVector))

  private def flatAlonzoOf(
      f: AlonzoUtxoPredFailure[Babbage]
  ): Either[AlonzoUtxosPredFailure[Babbage], UtxoFailure] = f match
    case AlonzoUtxoPredFailure.UtxosFailure(AlonzoUtxosPredFailure.UpdateFailure(p)) =>
      Right(UtxoFailure.Update(flatPpupOf(p)))
    case AlonzoUtxoPredFailure.UtxosFailure(u) => Left(u)
    case other =>
      Right(other match
        case AlonzoUtxoPredFailure.BadInputsUTxO(ins) =>
          UtxoFailure.BadInputs(ins.toSortedSet.unsorted)
        case AlonzoUtxoPredFailure.OutsideValidityIntervalUTxO(vi, slot) =>
          UtxoFailure.OutsideValidityInterval(vi.invalidBefore, vi.invalidHereafter, slot)
        case AlonzoUtxoPredFailure.MaxTxSizeUTxO(m) => UtxoFailure.MaxTxSize(mismatch(m)(identity))
        case AlonzoUtxoPredFailure.InputSetEmptyUTxO() => UtxoFailure.InputSetEmpty
        case AlonzoUtxoPredFailure.FeeTooSmallUTxO(m) =>
          UtxoFailure.FeeTooSmall(mismatch(m)(_.value))
        case AlonzoUtxoPredFailure.ValueNotConservedUTxO(m) =>
          UtxoFailure.ValueNotConserved(mismatch(m)(stored.storedValue))
        case AlonzoUtxoPredFailure.WrongNetwork(n, as) =>
          UtxoFailure.WrongNetwork(n.wire, as.toSortedSet.unsorted.map(_.raw))
        case AlonzoUtxoPredFailure.WrongNetworkWithdrawal(n, as) =>
          UtxoFailure.WrongNetworkWithdrawal(n.wire, as.toSortedSet.unsorted.map(rewardAccount))
        case AlonzoUtxoPredFailure.OutputTooSmallUTxO(outs) =>
          UtxoFailure.OutputTooSmall(outs.toList.toVector.map(stored.toStored))
        case AlonzoUtxoPredFailure.OutputBootAddrAttrsTooBig(outs) =>
          UtxoFailure.OutputBootAddrAttrsTooBig(outs.toList.toVector.map(stored.toStored))
        case AlonzoUtxoPredFailure.OutputTooBigUTxO(outs) =>
          UtxoFailure.OutputTooBig(
            outs.toList.toVector.map((a, b, o) => (a, b, stored.toStored(o)))
          )
        case AlonzoUtxoPredFailure.InsufficientCollateral(balance, required) =>
          UtxoFailure.InsufficientCollateral(balance.value, required.value)
        case AlonzoUtxoPredFailure.ScriptsNotPaidUTxO(outs) =>
          UtxoFailure.ScriptsNotPaid(outs.toSortedMap.toMap.view.mapValues(stored.toStored).toMap)
        case AlonzoUtxoPredFailure.ExUnitsTooBigUTxO(m) =>
          UtxoFailure.ExUnitsTooBig(mismatch(m)(identity))
        case AlonzoUtxoPredFailure.CollateralContainsNonADA(v) =>
          UtxoFailure.CollateralContainsNonAda(stored.storedValue(v))
        case AlonzoUtxoPredFailure.WrongNetworkInTxBody(m) =>
          UtxoFailure.WrongNetworkInTxBody(mismatch(m)(_.wire))
        case AlonzoUtxoPredFailure.OutsideForecast(slot) => UtxoFailure.OutsideForecast(slot)
        case AlonzoUtxoPredFailure.TooManyCollateralInputs(m) =>
          UtxoFailure.TooManyCollateralInputs(mismatch(m)(_.value.toInt))
        case AlonzoUtxoPredFailure.NoCollateralInputs() => UtxoFailure.NoCollateralInputs
        case AlonzoUtxoPredFailure.UtxosFailure(u) => fail(s"UTXOS's failure, matched above: $u"))

  private def utxosSeenOf(
      needed: Vector[AlonzoPlutusPurpose[AsIxItem, Babbage]]
  )(u: AlonzoUtxosPredFailure[Babbage]): Seen = u match
    case AlonzoUtxosPredFailure.CollectErrors(es) =>
      Seen.Collected(es.toList.map(collectOf(needed)))
    case AlonzoUtxosPredFailure.ValidationTagMismatch(
          flag,
          TagMismatchDescription.PassedUnexpectedly
        ) =>
      Seen.PassedUnexpectedly(flag == IsPhase2Valid.Phase2Valid)
    case AlonzoUtxosPredFailure.ValidationTagMismatch(
          flag,
          TagMismatchDescription.FailedUnexpectedly(fs)
        ) =>
      Seen.FailedUnexpectedly(
        flag == IsPhase2Valid.Phase2Valid,
        fs.toList.map { case FailureDescription.PlutusFailure(cause, pwc) =>
          EraUtxoRun.cause(cause) -> Some(PlutusWithContext.evalRequest(pwc))
        }
      )
    case AlonzoUtxosPredFailure.UpdateFailure(p) => Seen.Utxo(UtxoFailure.Update(flatPpupOf(p)))

  private def seenOf(needed: Vector[AlonzoPlutusPurpose[AsIxItem, Babbage]])(f: Rec): Seen =
    flatUtxoOf(f).fold(utxosSeenOf(needed), Seen.Utxo(_))

  private def flatSeenOf(f: PredicateFailure): Seen = f match
    case PredicateFailure.Utxow(UtxowFailure.Utxo(UtxoFailure.Utxos(u))) =>
      u match
        case UtxosFailure.CollectErrors(es) => Seen.Collected(es.toVector.toList.map(flatCollectOf))
        case UtxosFailure.ValidationTagMismatch(flag, None) => Seen.PassedUnexpectedly(flag)
        case UtxosFailure.ValidationTagMismatch(flag, Some(fs)) =>
          Seen.FailedUnexpectedly(flag, fs.toList.map(p => EraUtxoRun.cause(p.cause) -> p.request))
    case PredicateFailure.Utxow(UtxowFailure.Utxo(other)) => Seen.Utxo(other)
    case other                                            => fail(s"not a failure of UTXO: $other")

  /** An era failure of UTXO as the flat failure the node's refusal encoder writes, a script's
    * failure by its cause and request as the era encoder of Conway's failures projects it. A
    * missing redeemer, which the flat failure names by a purpose of its own, is not encoded here.
    */
  private def flatOf(f: Rec): PredicateFailure =
    PredicateFailure.utxo(flatUtxoOf(f).fold(u => UtxoFailure.Utxos(flatUtxosOf(u)), identity))

  private def flatUtxosOf(u: AlonzoUtxosPredFailure[Babbage]): UtxosFailure = u match
    case AlonzoUtxosPredFailure.ValidationTagMismatch(flag, description) =>
      UtxosFailure.ValidationTagMismatch(
        flag == IsPhase2Valid.Phase2Valid,
        description match
          case TagMismatchDescription.PassedUnexpectedly => None
          case TagMismatchDescription.FailedUnexpectedly(fs) =>
            Some(fs.toList.toVector.map { case FailureDescription.PlutusFailure(cause, pwc) =>
              PlutusFailure(cause, Bytes.empty, Some(PlutusWithContext.evalRequest(pwc)))
            })
      )
    case AlonzoUtxosPredFailure.CollectErrors(es) =>
      UtxosFailure.CollectErrors(es.toNev.map {
        case CollectError.BadTranslation(e) =>
          FlatCollectError.BadTranslation(BabbageTestKit.flatContextError(e))
        case CollectError.NoWitness(h)   => FlatCollectError.NoWitness(h)
        case CollectError.NoCostModel(l) => FlatCollectError.NoCostModel(l)
        case CollectError.NoRedeemer(p)  => fail(s"not encoded here: $p")
      })
    case AlonzoUtxosPredFailure.UpdateFailure(p) => fail(s"UTXO's own failure, not UTXOS's: $p")

  // ---- one judgment of UTXO both ways

  /** What a judgment came to: its failures in order, or the state after with the scripts' counters.
    */
  private enum Outcome:
    case Refused(failures: List[Seen])
    case Accepted(state: UTxOState[Babbage], counters: (Long, Long, Long))

  private def flatOutcome(
      j: Judged,
      validation: TxEnv.Validation,
      build: ReferenceBuild,
      evaluator: ScriptEvaluator[Id]
  ): Outcome =
    flatRun(j, validation, build, evaluator) match
      case RuleT.Outcome.Refused(failures) => Outcome.Refused(failures.toList.map(flatSeenOf))
      case RuleT.Outcome.Passed(after, _) =>
        Outcome.Accepted(
          after.state,
          (after.plutus.succeeded, after.plutus.failed, after.plutus.scripts)
        )
      case other => fail(s"${j.name}: flat defect $other")

  /** The counters the flat UTXOS keeps (`PlutusStats`), as UTXOS's events, in UTXO's, give them. */
  private def counters(valid: Boolean, events: Events): (Long, Long, Long) =
    val utxos = events.toList.collect { case AlonzoUtxoEvent.UtxosEvent(e) => e }
    val passed = utxos.collect { case AlonzoUtxosEvent.SuccessfulPlutusScriptsEvent(ps) => ps.size }
    val failed = utxos.collect { case AlonzoUtxosEvent.FailedPlutusScriptsEvent(fs) => fs.size }
    assert(passed.size <= 1 && failed.size <= 1, s"events $events")
    (
      if valid && passed.nonEmpty then 1L else 0L,
      if failed.nonEmpty then 1L else 0L,
      (passed ++ failed).sum.toLong
    )

  private def eraJudgment(
      j: Judged,
      validation: TxEnv.Validation,
      build: ReferenceBuild,
      evaluator: ScriptEvaluator[Id]
  ): Either[NonEmptyChain[Rec], S] =
    EraRun.run(j, contextOf(j, validation, build), evaluator) match
      case Left(defect)  => fail(s"${j.name}: era defect $defect")
      case Right(judged) => judged

  private def eraOutcome(
      j: Judged,
      validation: TxEnv.Validation,
      build: ReferenceBuild,
      evaluator: ScriptEvaluator[Id]
  ): Outcome =
    eraJudgment(j, validation, build, evaluator) match
      case Left(failures) =>
        Outcome.Refused(failures.toList.map(seenOf(needed(j, eraUtxo(j)))))
      case Right((after, gov, events)) =>
        // UTXO leaves the stored governance state as it was; UTXOS's record is written back over it
        assertEquals(after.gov, j.state.gov, s"${j.name}: the stored governance state")
        Outcome.Accepted(
          after.copy[Babbage](gov = ShelleyGovStateRecord.toStored[Babbage](gov, j.state.gov)),
          counters(t.isValid(j.typed) == IsPhase2Valid.Phase2Valid, events)
        )

  /** The two outcomes, compared whole: the state field by field, so that a difference names its
    * field.
    */
  private def same(
      j: Judged,
      validation: TxEnv.Validation = TxEnv.Validation.All,
      build: ReferenceBuild = ReferenceBuild.Release11_1,
      evaluator: Option[ScriptEvaluator[Id]] = None
  ): Outcome =
    val ev = evaluator.getOrElse(j.ctx.evaluator)
    val flat = flatOutcome(j, validation, build, ev)
    val era = eraOutcome(j, validation, build, ev)
    (flat, era) match
      case (Outcome.Accepted(f, fc), Outcome.Accepted(e, ec)) =>
        val (eu, fu) = (e.utxo.utxo, f.utxo.utxo)
        assertEquals(eu.toMap, fu.toMap, s"${j.name}: UTxO")
        assertEquals(
          (eu.coin, eu.count, eu.stake, eu.ptrStake),
          (fu.coin, fu.count, fu.stake, fu.ptrStake),
          s"${j.name}: UTxO totals"
        )
        assertEquals(eu, fu, s"${j.name}: UTxO record")
        assertEquals(e.deposited, f.deposited, s"${j.name}: deposits")
        assertEquals(e.fees, f.fees, s"${j.name}: fees")
        assertEquals(e.instantStake, f.instantStake, s"${j.name}: instant stake")
        assertEquals(e.gov, f.gov, s"${j.name}: governance state")
        assertEquals(e.donation, f.donation, s"${j.name}: donation")
        assertEquals(ec, fc, s"${j.name}: counters")
      case _ => assertEquals(era, flat, s"${j.name} ($validation, $build)")
    era

  /** [[same]] under every build and both policies; the outcome under 11.1 and `All`. */
  private def everyWay(j: Judged, evaluator: Option[ScriptEvaluator[Id]] = None): Outcome =
    val all = for
      build <- builds
      validation <- Vector(TxEnv.Validation.All, TxEnv.Validation.NonStatic)
    yield same(j, validation, build, evaluator)
    all.head

  /** The era rule's failures on `j`, refused under 11.1 and `All`, as it records them. */
  private def eraFailures(j: Judged): NonEmptyChain[Rec] =
    eraJudgment(j, TxEnv.Validation.All, ReferenceBuild.Release11_1, j.ctx.evaluator) match
      case Left(failures) => failures
      case Right(_)       => fail(s"${j.name}: not refused")

  private def refusalItem(j: Judged): Either[LocalSubmission.NoReply, Bytes] =
    LocalSubmission.reasonItem(
      Rejection.Ledger(LedgerFailure.Rejected(j.flat.body.id, eraFailures(j).map(flatOf))),
      Some(ProtocolEra.Babbage)
    )

  // ---- the recorded V1-context submissions

  test(
    "the V1-context submissions: the flat UTXO's judgment and state, and the reference's verdict"
  ) {
    val outcomes = v1.map((c, v) => (c, v, everyWay(v1Judged(c))))
    assertEquals(outcomes.size, 22)
    // UTXO refuses what the reference refused for the scripts or their context alone, and accepts
    // the other four: two flagged valid, two flagged invalid, whose collateral is taken
    outcomes.foreach { (c, v, o) =>
      o match
        case Outcome.Accepted(_, _) =>
          assert(v.accepted, s"${c.name}: accepted what the reference refused")
        case Outcome.Refused(fs) =>
          assert(!v.accepted, s"${c.name}: refused what the reference accepted")
          fs.foreach {
            case Seen.Utxo(f) => fail(s"${c.name}: a predicate of UTXO refused it: $f")
            case _            => ()
          }
    }
    assertEquals(
      outcomes
        .collect { case (c, _, Outcome.Accepted(_, _)) => c.tx.isValid }
        .groupMapReduce(identity)(_ => 1)(_ + _),
      Map(true -> 2, false -> 2)
    )
  }

  // ---- the refusal corpora's Babbage cases, with their recorded states

  test(
    "the refusal corpora's Babbage cases: the flat UTXO's judgment and state, and the reference's bytes"
  ) {
    val outcomes = refusals.map(r => r -> everyWay(r.judged))
    assertEquals(outcomes.size, 32)
    // UTXO refuses 17, each as the flat UTXO does, and accepts the others, three of them paying or
    // refunding deposits as it does
    val refused = outcomes.collect { case (r, Outcome.Refused(_)) => r.judged.name.split('/').last }
    assertEquals(
      refused,
      Vector(
        "utxo-bootstrap-attributes-too-big",
        "utxo-collateral-script-locked",
        "utxo-collateral-non-ada",
        "utxo-body-network-id-wrong",
        "fee-too-small",
        "value-not-conserved",
        "expired",
        "output-too-small",
        "utxo-fee-interval-balance",
        "utxo-small-and-wrong-network-outputs",
        "utxo-bad-input-missing-witness",
        "utxos-script-fails-flag-true",
        "utxos-script-passes-flag-false",
        "utxo-mint-no-collateral",
        "utxo-total-collateral-wrong",
        "utxow-spend-without-datum-or-redeemer",
        "ppup-non-genesis-proposer"
      )
    )
    // those refused by UTXO, UTXOS or PPUP alone: the era rule's failures are the reference's,
    // byte for byte
    val alone = refusals.filter(r =>
      r.families.forall(f => f == Family.UTXO || f == Family.UTXOS || f == Family.PPUP)
    )
    assert(alone.map(_.judged.name.split('/').last).toSet.subsetOf(refused.toSet))
    alone.foreach(r => assertEquals(refusalItem(r.judged), Right(r.reason), r.judged.name))
    assertEquals(alone.size, AloneCases)
  }

  // ---- the forecast-horizon corpora's Babbage cases

  /** A recorded case of the forecast-horizon corpora: the transaction, the state ticked to the slot
    * after the tip and the UTxO set, with the clock and era history of the cluster at the tip; and
    * the reference's refusal, if it refused.
    */
  private lazy val horizon: Vector[(Judged, Option[Bytes])] =
    val root = Path.of("fixtures/reference/plutus-forecast-horizon")
    Vector("babbage-horizon", "babbage-known-horizon").flatMap { name =>
      val dir = root.resolve(name)
      if !Files.isDirectory(dir) then fail(s"missing committed fixture $dir")
      val setup = right(s"$name genesis")(
        NodeSetup.fromDirectory(ReferenceBuild.Default)(dir.resolve("genesis"))
      )
      val globals = Globals(setup.globals, setup.geometry)
      Files.list(dir.resolve("cases")).iterator.asScala.toVector.sorted.map { file =>
        val json = right(s"$file")(parse(Files.readString(file))).hcursor
        def text(k: String) = right(s"$file $k")(json.get[String](k))
        def bytes(k: String) = Bytes.fromArray(Files.readAllBytes(dir.resolve(text(k))))
        val label = s"$name/${text("label")}"
        val nes =
          right(s"$label state")(HaskellState.decode[Babbage](bytes("state")).leftMap(_.render))
        val known = right(s"$label UTxO")(UtxoCodecs.utxo.decodeBytes(bytes("utxo")))
        val tip = right(s"$label tip")(json.downField("tip").get[BigInt]("slot"))
        val funded = NewEpochState
          .ledger[Babbage]
          .modify(ls =>
            ls.copy[Babbage](utxo = ls.utxo.copy(utxo = UtxoTables(Utxo.fromMap(known))))
          )(nes)
        val ticked =
          right(s"$label tick")(Sts(Tick.sts[Babbage])(globals, (), funded, slotNo(tip + 1)))
        val clock =
          PastHorizonReferenceSuite.clockAt(setup, ProtocolEra.Babbage, nes.epoch.toBigInt, tip)
        val epochInfo = clock match
          case PosixClock.Forecast(_, h) => h
          case other                     => fail(s"$label: not the era history's clock: $other")
        val raw = right(s"$label item")(LocalSubmission.unwrap(hex(text("item")))).raw
        val accepted = right(s"$label accepted")(json.get[Boolean]("accepted"))
        val ls = ticked.es.ls
        Judged(
          label,
          right(s"$label flat")(FlatTx.fromEnvelope(raw, ProtocolEra.Babbage)),
          right(s"$label typed")(BabbageTx.fromEnvelope(raw, StateLayout.Node11_1)),
          known,
          ls.utxo.gov.curPParams,
          ls.cert,
          ls.utxo,
          globals,
          tip + 1,
          clock,
          epochInfo
        ) -> Option.unless(accepted)(hex(text("reason")))
      }
    }

  test(
    "the forecast-horizon corpora's Babbage cases: the flat UTXO's judgment, and the reference's bytes"
  ) {
    val outcomes = horizon.map((j, reason) => (j, reason, everyWay(j)))
    assertEquals(
      outcomes.map((j, reason, o) => (j.name, reason.isDefined, o.isInstanceOf[Outcome.Refused])),
      Vector(
        ("babbage-horizon/plutus-upper-bound-inside-horizon-babbage", false, false),
        ("babbage-horizon/plutus-upper-bound-past-horizon-babbage", true, true),
        ("babbage-known-horizon/plutus-upper-bound-past-horizon-babbage", true, true)
      )
    )
    outcomes.foreach { (j, reason, _) =>
      reason.foreach { bytes =>
        // the end past the horizon is the scripts' context's refusal, never OutsideForecast: the slot
        // judged at is inside the history the end is extended from
        assertEquals(
          PreConwayFailureCbor
            .applyTxError(ProtocolEra.Babbage, eraFailures(j).toList.toVector.map(flatOf)),
          Right(Some(bytes)),
          j.name
        )
        assert(j.epochInfo.timePs(j.slot).isDefined, s"${j.name}: the slot is in the history")
      }
    }
  }

  // ---- the pre-Conway UTXO predicate corpus's Babbage bodies

  /** A deterministic key's enterprise address, and a script's (`0x60`, `0x70`, network 0). */
  private def keyAddress(b: Int, network: Int = 0): Address =
    right("address")(Address.decode(Bytes(((0x60 | network).toByte) +: Vector.fill(28)(b.toByte))))
  private def scriptAddress(b: Int): Address =
    right("address")(Address.decode(Bytes(0x70.toByte +: Vector.fill(28)(b.toByte))))

  private final case class Measured(
      name: String,
      network: Int,
      attributes: Boolean,
      addresses: Boolean,
      collateral: Boolean,
      body: Bytes
  )

  private lazy val measured: Vector[Measured] =
    val path = Path.of("fixtures/reference/pre-conway-utxo/predicates.json")
    if !Files.isRegularFile(path) then fail(s"missing committed fixture $path")
    right("pre-conway-utxo")(parse(Files.readString(path))).asArray
      .getOrElse(fail("not an array"))
      .map(_.hcursor)
      .filter(c => right("major")(c.get[Int]("protocolMajor")) == 7)
      .map { c =>
        def accepted(k: String) = right(s"$k")(c.downField(k).get[Boolean]("accepted"))
        Measured(
          right("name")(c.get[String]("name")),
          right("network")(c.get[Int]("network")),
          accepted("attributes"),
          accepted("addresses"),
          accepted("collateral"),
          hex(right("body")(c.get[String]("body")))
        )
      }

  /** A measured body with no witnesses, judged at protocol 7 on the network it was measured on,
    * each input and collateral input held by a key's output that pays for every output and the fee.
    */
  private def measuredJudged(m: Measured): Judged =
    val parts = Block.Transaction(m.body, hex("a0"), None, isValid = true)
    val flat = right(s"${m.name} flat")(FlatTx.fromBlock(parts, ProtocolEra.Babbage))
    val pp = TestLedger.params(7)
    val inputs = (flat.body.inputs ++ flat.body.collateral).distinct
    val total = flat.body.outputs.map(_.value.coin).sum + flat.body.fee
    val known = inputs.zipWithIndex.map { (in, i) =>
      in -> output(keyAddress(1), if i == 0 then total else BigInt(5000000))
    }.toMap
    val ls = Build.funded(Build.nes[Babbage](5, pp), known).es.ls
    val network = right("network")(
      xyz.kd5ujc.scarab.ledger.state.Network.of(BigInt(m.network)).toRight(m.network)
    )
    val globals =
      TestLedger.globals.copy(ledger = TestLedger.globals.ledger.copy(network = network))
    Judged(
      m.name,
      flat,
      right(s"${m.name} typed")(BabbageTx.decode(parts, StateLayout.Node11_1)),
      known,
      pp,
      ls.cert,
      ls.utxo,
      globals,
      TestLedger.env.slot,
      TestLedger.env.clock.getOrElse(fail("no clock")),
      covering(globals)
    )

  test(
    "the pre-Conway UTXO corpus's Babbage bodies: the flat UTXO's failures, and the reference's verdicts"
  ) {
    assertEquals(measured.size, MeasuredCases)
    val refused = for
      m <- measured
      build <- builds
      validation <- Vector(TxEnv.Validation.All, TxEnv.Validation.NonStatic)
    yield
      val j = measuredJudged(m)
      val failures = same(j, validation, build) match
        case Outcome.Refused(fs)    => fs.collect { case Seen.Utxo(f) => f }
        case Outcome.Accepted(_, _) => Nil
      // each predicate the reference measured, as the era's UTXO reports it under the build
      val attributes = failures.exists {
        case UtxoFailure.OutputTooBig(outs) =>
          build == ReferenceBuild.Release11_1 && outs.forall((a, b, _) => a == 0 && b == 0)
        case UtxoFailure.OutputBootAddrAttrsTooBig(_) => build != ReferenceBuild.Release11_1
        case _                                        => false
      }
      val addresses = failures.exists {
        case UtxoFailure.WrongNetwork(_, _) => true; case _ => false
      }
      val collateral = failures.exists {
        case UtxoFailure.TooManyCollateralInputs(_) => true; case _ => false
      }
      // the bootstrap attributes and the networks are checked on the signal alone
      // (`runTestOnSignal`, `Babbage/Rules/Utxo.hs:392, 397`, cardano-ledger f649f975), the number of
      // collateral inputs always (`runTest`, `:412`)
      val static = validation == TxEnv.Validation.All
      assertEquals(
        (attributes, addresses, collateral),
        (static && !m.attributes, static && !m.addresses, !m.collateral),
        s"${m.name} $build $validation"
      )
      (validation, (attributes, addresses, collateral))
    // the corpus refuses each predicate at least once, and the collateral count applied again
    val all = refused.collect { case (TxEnv.Validation.All, r) => r }
    assert(all.exists(_._1) && all.exists(_._2) && all.exists(_._3))
    assert(refused.exists { case (TxEnv.Validation.NonStatic, (_, _, c)) => c; case _ => false })
    // `7-attributes-before-networks-before-collateral` refuses all three, and applied again only the
    // collateral count
    val every = measured.indexWhere(_.name == "7-attributes-before-networks-before-collateral")
    assert(every >= 0)
    assertEquals(
      refused.slice(every * builds.size * 2, every * builds.size * 2 + 2).map(_._2),
      Vector((true, true, true), (false, false, true))
    )
  }

  // ---- crafted cases

  private val plain = "babbage-pv8-v1-plain"

  /** What an evaluator that must not be asked raises when it is. */
  private final class Asked extends RuntimeException("a script was evaluated")

  /** `j` with the UTxO set `known`, which both rules read: the flat one from the annotated
    * transaction, the era one from the state.
    */
  private def withKnown(j: Judged, known: Map[TxIn, FlatTxOut]): Judged =
    val ls = Build.funded(Build.nes[Babbage](5, j.pp), known).es.ls
    j.copy(known = known, state = ls.utxo, cert = ls.cert)

  /** An output's bytes, the address and the coin with, if `asset`, one unit of an asset. */
  private def output(address: Address, coin: BigInt, asset: Boolean = false): FlatTxOut =
    val value =
      if !asset then uint(coin)
      else
        val one = leaf(CborValue.Map(Vector(bytesOf(Bytes(Vector(1.toByte))) -> uint(1))))
        leaf(
          CborValue.Arr(
            Vector(
              uint(coin),
              leaf(CborValue.Map(Vector(bytesOf(Bytes(Vector.fill(28)(3.toByte))) -> one)))
            )
          )
        )
    val raw = right("output")(
      Cbor.encode(CborValue.Arr(Vector(bytesOf(address.raw), value)), Cbor.Limits.Unbounded)
    )
    right("output")(FlatTxOut.fromBytes(raw)(using xyz.kd5ujc.scarab.ledger.testByteStrings))

  /** A V1-context case with its parameters edited by `f`, both forms. */
  private def withParams(j: Judged)(f: ProtocolParams => ProtocolParams): Judged =
    j.copy(pp = f(j.pp))

  /** A V1-context case's body with its fields edited by `f` and its flag `valid`, read both ways.
    * UTXO reads no witness, so a body its witnesses no longer sign still tells the rules apart.
    */
  private def withBody(name: String, valid: Option[Boolean] = None)(
      f: Vector[(Node, Node)] => Vector[(Node, Node)]
  ): Judged =
    val c = caseNamed(name)
    val base = v1Judged(c)
    val body = right("body")(Cbor.decode(c.tx.body.raw)).value match
      case CborValue.Map(fields) =>
        right("re-encode")(Cbor.encode(CborValue.Map(f(fields)), Cbor.Limits.Unbounded))
      case other => fail(s"a body that is not a map: $other")
    val witnesses = c.tx.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    val parts = Block.Transaction(body, witnesses, c.tx.auxiliary, valid.getOrElse(c.tx.isValid))
    base.copy(
      name = s"${c.name} edited",
      flat = right("flat")(FlatTx.fromBlock(parts, ProtocolEra.Babbage)),
      typed = right("typed")(BabbageTx.decode(parts, StateLayout.Node11_1))
    )

  private def leaf(v: CborValue): Node = Node(v, Bytes.empty)
  private def uint(n: BigInt): Node = leaf(CborValue.UInt(n))
  private def bytesOf(b: Bytes): Node = leaf(CborValue.ByteString(b))
  private def key(k: Int): Node = uint(k)
  private def isKey(k: Int)(n: Node): Boolean = n.value == CborValue.UInt(k)
  private def put(k: Int, v: Node)(fields: Vector[(Node, Node)]): Vector[(Node, Node)] =
    fields.filterNot(f => isKey(k)(f._1)) :+ (key(k) -> v)
  private def field(fields: Vector[(Node, Node)], k: Int): Node =
    fields.find(f => isKey(k)(f._1)).map(_._2).getOrElse(fail(s"no field $k"))

  /** The names of the failures, in their order. */
  private def names(o: Outcome): List[String] = o match
    case Outcome.Refused(fs) =>
      fs.map {
        case Seen.Utxo(f)                  => f.productPrefix
        case Seen.Collected(_)             => "CollectErrors"
        case Seen.PassedUnexpectedly(_)    => "PassedUnexpectedly"
        case Seen.FailedUnexpectedly(_, _) => "FailedUnexpectedly"
      }
    case Outcome.Accepted(_, _) => Nil

  test("every predicate of the parameters and the UTxO set at once, in the reference's order") {
    val base = v1Judged(caseNamed(plain))
    val spent = base.flat.body.inputs.head
    val j = withParams(withKnown(base, base.known - spent))(
      _.copy(
        minFeeA = BigInt(10).pow(9),
        collateralPercentage = 10000,
        coinsPerUTxOByte = BigInt(10).pow(9),
        maxValueSize = 1,
        maxTxSize = 1,
        maxTxExUnits = xyz.kd5ujc.scarab.plutus.ExUnits.zero,
        maxCollateralInputs = 0
      )
    )
    val o = everyWay(j)
    // `babbageUtxoValidation`'s order (`Babbage/Rules/Utxo.hs:325-412`, cardano-ledger f649f975):
    // the fee and the collateral (`feesOK`), the inputs the set does not hold, the value, the
    // outputs too small and too big, the size, the units and the number of collateral inputs; then
    // UTXOS, which still collects the scripts (the spent input is gone) and evaluates none
    // (`whenFailureFree`). At LEDGER's depth the reference reports UTXOS's failure first and
    // UTXO's own last first ([[underLedger]])
    assertEquals(
      names(o),
      List(
        "FeeTooSmall",
        "InsufficientCollateral",
        "BadInputs",
        "ValueNotConserved",
        "BabbageOutputTooSmall",
        "OutputTooBig",
        "MaxTxSize",
        "ExUnitsTooBig",
        "TooManyCollateralInputs"
      ).reverse.prepended("CollectErrors")
    )
  }

  test("a phase-1 failure beside a script that contradicts its flag: UTXOS evaluates no script") {
    val never: ScriptEvaluator[Id] = _ => throw new Asked
    Vector("babbage-pv8-v1-plain-fail-valid-tag", "babbage-pv8-v1-plain-pass-invalid-tag").foreach {
      name =>
        // under every build and both policies: the fee's failure alone, no script asked for
        val j = withParams(v1Judged(caseNamed(name)))(_.copy(minFeeA = BigInt(10).pow(9)))
        assertEquals(names(everyWay(j, Some(never))), List("FeeTooSmall"), name)
        // without it, UTXOS's tag mismatch alone, as the flag says the scripts went: flagged valid,
        // they failed; flagged invalid, they passed
        val c = caseNamed(name)
        same(v1Judged(c)) match
          case Outcome.Refused(List(Seen.FailedUnexpectedly(true, failures))) if c.tx.isValid =>
            assert(failures.nonEmpty, name)
          case Outcome.Refused(List(Seen.PassedUnexpectedly(false))) if !c.tx.isValid => ()
          case other => fail(s"$name: not the tag mismatch its flag gives: $other")
    }
  }

  test(
    "the collateral: locked by a script, holding an asset, a declared total that differs, none"
  ) {
    val base = v1Judged(caseNamed(plain))
    val collateral = base.flat.body.collateral.distinct
    assert(collateral.nonEmpty)
    val in = collateral.head
    val coin = base.known(in).value.coin
    def withCollateral(out: FlatTxOut) = withKnown(base, base.known.updated(in, out))
    assertEquals(
      names(everyWay(withCollateral(output(scriptAddress(9), coin)))),
      List("ScriptsNotPaid")
    )
    assertEquals(
      names(everyWay(withCollateral(output(keyAddress(1), coin, asset = true)))),
      List("CollateralContainsNonAda")
    )
    val declared = withBody(plain)(put(17, uint(coin + 1)))
    assertEquals(names(everyWay(declared)), List("IncorrectTotalCollateralField"))
    val none = withBody(plain)(_.filterNot(f => isKey(13)(f._1)))
    assertEquals(
      names(everyWay(none)),
      List("InsufficientCollateral", "NoCollateralInputs").reverse
    )
  }

  test("the validity interval, an empty input set and the reference inputs that are inputs too") {
    val slot = TestLedger.env.slot
    assertEquals(
      names(everyWay(withBody(plain)(put(8, uint(slot + 1))))),
      List("OutsideValidityInterval")
    )
    assertEquals(
      names(everyWay(withBody(plain)(put(3, uint(slot))))),
      List("OutsideValidityInterval")
    )
    assert(
      names(everyWay(withBody(plain)(put(0, leaf(CborValue.Arr(Vector.empty))))))
        .contains("InputSetEmpty")
    )
    // an input also a reference input: refused at protocol 9 and 10 only (`disjointRefInputs`,
    // `Babbage/Rules/Utxo.hs:200-214`, cardano-ledger f649f975)
    val shared =
      withBody("babbage-pv8-v1-refinput-plain")(fields => put(18, field(fields, 0))(fields))
    Vector(BigInt(8) -> false, BigInt(9) -> true, BigInt(10) -> true).foreach { (major, refused) =>
      val o = everyWay(withParams(shared)(_.copy(protocolMajor = major)))
      assertEquals(names(o).contains("BabbageNonDisjointRefInputs"), refused, s"$major")
    }
  }

  test("the network of the outputs, the body and the withdrawals, each predicate on its own") {
    val base = withBody(plain)(put(15, uint(0)))
    val mainnet = base.copy(globals =
      base.globals.copy(ledger =
        base.globals.ledger.copy(network = xyz.kd5ujc.scarab.ledger.state.Network.Mainnet)
      )
    )
    // UTXO's own failures, last first at LEDGER's depth
    assertEquals(names(everyWay(mainnet)), List("WrongNetwork", "WrongNetworkInTxBody").reverse)
    // a withdrawal of nothing from a testnet account, judged on mainnet
    val account = leaf(CborValue.ByteString(Bytes((0xe0.toByte) +: Vector.fill(28)(4.toByte))))
    val withdrawn = withBody(plain)(put(5, leaf(CborValue.Map(Vector(account -> uint(0))))))
    val onMainnet = withdrawn.copy(globals = mainnet.globals)
    assertEquals(names(everyWay(onMainnet)), List("WrongNetwork", "WrongNetworkWithdrawal").reverse)
  }

  /** `validateOutsideForecast` (`Alonzo/Rules/Utxo.hs:362-385`, cardano-ledger f649f975): a
    * transaction with redeemers and an end, judged at a slot past the era history, is refused with
    * its end, as the reference's linear extension from the slot fails there. The flat UTXO has no
    * such predicate (its scripts' context refuses the end instead), so the era rule alone is held
    * to it here.
    */
  test("the forecast: an end judged from a slot past the era history is OutsideForecast") {
    val slot = TestLedger.env.slot
    val end = slot + 10
    val j = withBody(plain)(put(3, uint(end)))
    val g = j.globals.geometry
    val window = g.stabilityWindow.toBigInt
    val params = Horizon.Params(g.epochLength, g.slotLengthMillis, window, window)
    def bounded(last: BigInt) =
      Horizon(
        cats.data.NonEmptyVector.one(
          Horizon.Era(Horizon.Edge(0, 0, 0), Some(Horizon.Edge(0, last, 0)), params)
        )
      )
    val failures =
      eraJudgment(
        j.copy(epochInfo = bounded(slot)),
        TxEnv.Validation.All,
        ReferenceBuild.Release11_1,
        j.ctx.evaluator
      )
    failures match
      case Left(fs) =>
        assertEquals(
          fs.toList.map(flatUtxoOf).collect { case Right(UtxoFailure.OutsideForecast(s)) => s },
          List(end)
        )
      case Right(_) => fail("not refused")
    // with the slot inside the history, the end past it alone passes
    assertEquals(
      eraJudgment(
        j.copy(epochInfo = bounded(slot + 1)),
        TxEnv.Validation.All,
        ReferenceBuild.Release11_1,
        j.ctx.evaluator
      ).isRight,
      true
    )
  }

  /** UTXOS's environment holds the slot as a `SlotNo` (`Alonzo/Rules/Utxos.hs:118-122`,
    * cardano-ledger f649f975); a slot outside one is the state's defect, raised before any
    * predicate, so under either way of recording failures, and not a refusal.
    */
  test("a slot outside a SlotNo is raised before any predicate, under each policy of failures") {
    val beyond = BigInt(1) << 64
    val j = withBody(plain)(put(3, uint(100))).copy(slot = beyond)
    Vector(RuleT.Policy.Accumulate, RuleT.Policy.FirstFailure).foreach { policy =>
      EraRun.run(
        j,
        contextOf(j, TxEnv.Validation.All, ReferenceBuild.Release11_1),
        j.ctx.evaluator,
        policy
      ) match
        case Left(Left(Right(UtxoStateDefect.Vocab(_)))) => ()
        case other => fail(s"$policy: not the slot's defect: $other")
    }
  }

  /** Under `ValidateNone` the outputs of the body's inputs are read only for the predicates, which
    * do not run (Dijkstra's UTXO reads its as well): a reference input's stored output the era
    * cannot read is not reached, and the transaction's outputs are spent and created; with its
    * predicates checked, the same output is the state's defect.
    */
  test(
    "ValidateNone reads no output for the predicates: an unreadable reference input is not reached"
  ) {
    val base = v1Judged(caseNamed("babbage-pv8-v1-refinput-plain"))
    val ref = base.flat.body.referenceInputs.headOption.getOrElse(fail("no reference input"))
    assert(!base.flat.body.inputs.contains(ref) && !base.flat.body.collateral.contains(ref))
    // the stored form with a reference script its bytes do not hold, which the era reads from them
    val script = v1.iterator
      .flatMap((c, _) => (c.spent ++ c.references ++ c.collateral).valuesIterator)
      .flatMap(_.scriptRef)
      .nextOption()
      .getOrElse(fail("no recorded output with a reference script"))
    val bad = base.known(ref).copy(scriptRef = Some(script))
    assert(StoredTxOut.babbage.fromStored(bad).isLeft)
    val j = base.copy(state =
      base.state.copy[Babbage](utxo = UtxoTables(Utxo.fromMap(base.known.updated(ref, bad))))
    )
    val context = contextOf(j, TxEnv.Validation.All, ReferenceBuild.Release11_1)
    EraRun.runUnchecked(j, context, _ => throw new Asked) match
      case Right(Right((after, _, _))) =>
        assert(after.utxo.utxo.get(ref).contains(bad), "the reference input is left as it was")
        assert(base.flat.body.inputs.forall(in => after.utxo.utxo.get(in).isEmpty))
      case other => fail(s"not applied: $other")
    EraRun.run(j, context, j.ctx.evaluator) match
      case Left(Left(Right(UtxoStateDefect.StoredOutput(in, _)))) => assertEquals(in, ref)
      case other => fail(s"not the stored output's defect: $other")
  }
