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
import scala.collection.immutable.{SortedMap, TreeMap}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Block,
  CollectError as FlatCollectError,
  GenDelegPair,
  LedgerDefect,
  PlutusCase,
  PlutusStats,
  PosixClock,
  PpupFailure,
  PredicateFailure,
  ProtocolParams,
  TestLedger,
  TranslationError,
  Tx as FlatTx,
  TxChecks,
  TxEnv,
  TxIn,
  TxOut as FlatTxOut,
  Update as StoredUpdate,
  UtxoFailure,
  UtxosFailure,
  UtxowFailure,
  ParamUpdate,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.era.alonzo.{AlonzoEraUTxO, AlonzoPParams, AlonzoTestKit, AlonzoTx}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  AlonzoUtxoEvent,
  AlonzoUtxosEvent,
  AlonzoUtxosPredFailure,
  AlonzoUtxo,
  AlonzoUtxos,
  FailureDescription,
  PpupEnv,
  ShelleyPpup,
  ShelleyPpupEvent,
  ShelleyPpupPredFailure,
  TagMismatchDescription,
  TranslationOrigin,
  UtxoStateDefect,
  UtxosEnv
}
import xyz.kd5ujc.scarab.ledger.rules.{
  Counted,
  Globals,
  Judge,
  Judgment,
  PpupEnv as FlatPpupEnv,
  RuleT,
  ShelleyDeposits,
  ShelleyPpup as FlatPpup,
  ShelleyUtxo as FlatUtxo,
  ShelleyUtxos as FlatUtxos,
  StAnnTx as FlatStAnnTx,
  UtxoEnv as FlatUtxoEnv,
  contramap,
  focus
}
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Build,
  EpochNo,
  FuturePParams as StoredFuturePParams,
  Genesis,
  KeyHash,
  NewEpochState,
  ShelleyBasedEra,
  ShelleyCertState,
  ShelleyGovState as StoredGov,
  Sorted,
  UTxOState
}
import xyz.kd5ujc.scarab.consensus.state.MirrorStep
import xyz.kd5ujc.scarab.ledger.rules.{EraNes, Sts, Tick}
import xyz.kd5ujc.scarab.ledger.state.{SlotNo, UtxoTables}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.{LocalSubmission, Rejection}
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.{Family, RefusalEra}
import xyz.kd5ujc.scarab.node.read.ErasStates
import xyz.kd5ujc.scarab.plutus.{EvalRequest, ScarabEvaluator, ScriptEvaluator}
import xyz.kd5ujc.scarab.protocol.{Era as ProtocolEra, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Alonzo's PPUP and UTXOS on the era types (`era/rules/Ppup.scala`, `AlonzoUtxos.utxosTransition`;
  * cardano-ledger f649f975 `Shelley/Rules/Ppup.hs`, `Alonzo/Rules/Utxos.hs`), with the change of
  * the UTxO state that follows them in UTXO (`AlonzoUtxo.updateUTxOState`,
  * `Alonzo/Rules/Utxo.hs:751-784`), against the flat rules (`ShelleyPpup`, `ShelleyUtxos`,
  * `ShelleyUtxo.update`) and the reference's recorded verdicts:
  *
  *   - the 10 Alonzo submissions of `fixtures/reference/v1-context` (protocol 5 and 6), each with
  *     the outputs it spends and puts up as collateral: the same verdict, the same failures and,
  *     where accepted, the same UTxO state after (the set, deposits, fees, instant stake and
  *     governance state), and the reference's verdict;
  *   - the Alonzo cases of the refusal corpora, each with the reference's state ticked to the slot
  *     it was judged at and its UTxO set: the same UTXOS judgment and state after;
  *   - PPUP alone on every Alonzo state of the era-walking capture, its recorded proposals proposed
  *     again on either side of the point of no return, and on the capture's Alonzo transactions
  *     that propose an update;
  *   - crafted PPUP and UTXOS cases for each failure and boundary the corpora do not reach: Alonzo
  *     runs PPUP after the scripts, does not record a collection's translation failures, and takes
  *     a collateral with no return; a deregistration's refund.
  */
class AlonzoUtxosSuite extends FunSuite:
  private given era: ShelleyBasedEra[Alonzo] = ShelleyBasedEra.SAlonzo
  private val t = EraTx.alonzo

  // the counts the corpora give, asserted so that a loader that loses a case fails
  private val DepositCases = 2
  private val CertificateCases = 3
  private val DepositSigns: Vector[Int] = Vector(1, 1)
  private val WalkTally: Map[(String, Boolean), Int] =
    Map(("current", true) -> 131, ("next", true) -> 187, ("wrong", false) -> 187)
  private val WalkPredictions: Map[String, Int] = Map("voted" -> 131)
  private val CaptureProposals: Vector[Boolean] = Vector(true, true)

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private def slotNo(n: BigInt): SlotNo = right(s"slot $n")(SlotNo.fromBigInt(n))

  // ---- the era rules, run in one judgment

  private type PRec = ShelleyPpupPredFailure[Alonzo]
  private type PEvents = Chain[ShelleyPpupEvent[Alonzo]]
  private type PS = (ShelleyGovState[Alonzo], PEvents)
  private type P[A] = RuleT[PRec, LedgerDefect, PS, A]

  private object PpupRun:
    import RuleT.given
    given Stateful[P, ShelleyGovState[Alonzo]] =
      summon[Stateful[P, PS]].focus(Lens[PS, ShelleyGovState[Alonzo]](_._1)(g => s => (g, s._2)))
    given Tell[P, PEvents] = new Tell[P, PEvents]:
      val functor: Functor[P] = summon[Monad[P]]
      def tell(l: PEvents): P[Unit] = summon[Stateful[P, PS]].modify((g, e) => (g, e ++ l))

    def run(
        globals: Globals,
        env: PpupEnv[Alonzo],
        update: Option[KeptUpdate[Alonzo]],
        gov: ShelleyGovState[Alonzo]
    ): Either[LedgerDefect, Either[NonEmptyChain[PRec], PS]] =
      Judgment
        .judge(
          ShelleyPpup.ppupTransitionNonEmpty[P, Alonzo](env, update),
          RuleT.Policy.Accumulate
        )(globals, (gov, Chain.empty))
        .map(_.map(_._1))

  private type URec = AlonzoUtxosPredFailure[Alonzo]
  private type UEvents = Chain[AlonzoUtxosEvent[Alonzo]]
  private type UDefect = Either[ScriptVerdictUnavailable, LedgerDefect]
  private type US = (ShelleyGovState[Alonzo], UEvents)
  private type U[A] = RuleT[URec, UDefect, US, A]

  private object UtxosRun:
    import RuleT.given
    given Stateful[U, ShelleyGovState[Alonzo]] =
      summon[Stateful[U, US]].focus(Lens[US, ShelleyGovState[Alonzo]](_._1)(g => s => (g, s._2)))
    given Tell[U, UEvents] = new Tell[U, UEvents]:
      val functor: Functor[U] = summon[Monad[U]]
      def tell(l: UEvents): U[Unit] = summon[Stateful[U, US]].modify((g, e) => (g, e ++ l))
    given Raise[U, ScriptVerdictUnavailable] = summon[Raise[U, UDefect]].contramap(Left(_))
    given Raise[U, LedgerDefect] = summon[Raise[U, UDefect]].contramap(Right(_))

    def run(
        globals: Globals,
        env: UtxosEnv[Alonzo],
        stAnnTx: AlonzoStAnnTx[Alonzo],
        gov: ShelleyGovState[Alonzo],
        evaluator: ScriptEvaluator[Id],
        validation: TxEnv.Validation
    ): Either[UDefect, Either[NonEmptyChain[URec], US]] =
      given ScriptEvaluator[U] = request => Monad[U].unit.map(_ => evaluator.evaluate(request))
      given Phase2Deferral[U, Alonzo] = Phase2Deferral.now
      Judgment
        .judge(
          AlonzoUtxos.utxosTransition[U, Alonzo](env, stAnnTx, validation),
          RuleT.Policy.Accumulate
        )(globals, (gov, Chain.empty))
        .map(_.map(_._1))

    /** UTXOS judged with `ValidateNone` (`Judge.validateNone`): no predicate checked and no
      * labelled clause run, whatever `validation` says.
      */
    def runUnchecked(
        globals: Globals,
        env: UtxosEnv[Alonzo],
        stAnnTx: AlonzoStAnnTx[Alonzo],
        gov: ShelleyGovState[Alonzo],
        evaluator: ScriptEvaluator[Id],
        validation: TxEnv.Validation
    ): Either[UDefect, Either[NonEmptyChain[URec], US]] =
      given ScriptEvaluator[U] = request => Monad[U].unit.map(_ => evaluator.evaluate(request))
      given Phase2Deferral[U, Alonzo] = Phase2Deferral.now
      given Judge[U, URec] = Judge.validateNone[U, URec]
      Judgment
        .judge(
          AlonzoUtxos.utxosTransition[U, Alonzo](env, stAnnTx, validation),
          RuleT.Policy.Accumulate
        )(globals, (gov, Chain.empty))
        .map(_.map(_._1))

  private type HEvents = Chain[AlonzoUtxoEvent[Alonzo]]
  private type H[A] = RuleT[Nothing, UtxoStateDefect, HEvents, A]

  private object UpdateRun:
    import RuleT.given
    given Tell[H, HEvents] = new Tell[H, HEvents]:
      val functor: Functor[H] = summon[Monad[H]]
      def tell(l: HEvents): H[Unit] = summon[Stateful[H, HEvents]].modify(_ ++ l)

    /** Alonzo's `updateUTxOState` (`Alonzo/Rules/Utxo.hs:751-784`, cardano-ledger f649f975) from
      * `state`.
      */
    def run(
        pp: PParams[Alonzo],
        cert: ShelleyCertState,
        tx: AlonzoTx,
        state: UTxOState[Alonzo]
    ): UTxOState[Alonzo] =
      Judgment.judge(
        AlonzoUtxo.updateUTxOState[H, Alonzo](pp, cert, tx, state),
        RuleT.Policy.Accumulate
      )(TestLedger.globals, Chain.empty) match
        case Right(Right((_, next))) => next
        case other                   => fail(s"the change of the state: $other")

  // ---- what each side decided, compared as one type

  private enum Collect:
    /** The purpose that has no redeemer, by its tag and index. */
    case NoRedeemer(tag: Int, ix: BigInt)
    case NoWitness(hash: Bytes)
    case NoCostModel(language: xyz.kd5ujc.scarab.plutus.PlutusLanguage)
    case BadTranslation(error: TranslationError)

  /** A failure of PPUP or UTXOS: PPUP's as the flat failure (whose projection it is), the scripts'
    * by their verdict, each failing script by its cause and request.
    */
  private enum Seen:
    case Ppup(failure: PpupFailure)
    case Collected(errors: List[Collect])
    case PassedUnexpectedly(flag: Boolean)
    case FailedUnexpectedly(flag: Boolean, failures: List[(EraUtxoRun.Cause, Option[EvalRequest])])

  private def flatPpupOf(f: ShelleyPpupPredFailure[Alonzo]): PpupFailure = f match
    case ShelleyPpupPredFailure.NonGenesisUpdatePPUP(m) =>
      PpupFailure.NonGenesisUpdate(
        xyz.kd5ujc.scarab.ledger.Mismatch(m.supplied.map(_.bytes), m.expected.map(_.bytes))
      )
    case ShelleyPpupPredFailure.PPUpdateWrongEpoch(c, e, vp) =>
      PpupFailure.PPUpdateWrongEpoch(c.toBigInt, e.toBigInt, vp)
    case ShelleyPpupPredFailure.PVCannotFollowPPUP(pv) =>
      PpupFailure.PVCannotFollow(BigInt(pv.pvMajor.value), pv.pvMinor.value)

  /** A purpose's tag and index, of the scripts the body needs (`getScriptsNeeded`) whose item it
    * names, as `AlonzoPlutusContextRefusalsSuite` reads them.
    */
  private def purposeIx(
      needed: Vector[AlonzoPlutusPurpose[AsIxItem, Alonzo]],
      purpose: AlonzoPlutusPurpose[AsItem, Alonzo]
  ): Collect.NoRedeemer =
    val p = needed
      .find(p =>
        EraScript.alonzo.hoistPlutusPurpose[AsIxItem, AsItem](
          [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
        )(p) == purpose
      )
      .getOrElse(fail(s"$purpose is not needed"))
    val ix = EraScript.alonzo
      .hoistPlutusPurpose[AsIxItem, AsIx]([ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a))(
        p
      )
    Collect.NoRedeemer(ix.tag, ix.ix.value)

  private def seenOf(needed: Vector[AlonzoPlutusPurpose[AsIxItem, Alonzo]])(f: URec): Seen =
    f match
      case AlonzoUtxosPredFailure.UpdateFailure(p) => Seen.Ppup(flatPpupOf(p))
      case AlonzoUtxosPredFailure.CollectErrors(es) =>
        Seen.Collected(es.toList.map {
          case CollectError.NoRedeemer(purpose) => purposeIx(needed, purpose)
          case CollectError.NoWitness(h)        => Collect.NoWitness(h)
          case CollectError.NoCostModel(l)      => Collect.NoCostModel(l)
          case CollectError.BadTranslation(e) =>
            Collect.BadTranslation(AlonzoTestKit.flatContextError(e))
        })
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

  private def flatSeenOf(f: PredicateFailure): Seen = f match
    case PredicateFailure.Utxow(UtxowFailure.Utxo(UtxoFailure.Update(p))) => Seen.Ppup(p)
    case PredicateFailure.Utxow(UtxowFailure.Utxo(UtxoFailure.Utxos(u))) =>
      u match
        case UtxosFailure.CollectErrors(es) =>
          Seen.Collected(es.toVector.toList.map {
            case FlatCollectError.NoRedeemer(p)     => Collect.NoRedeemer(p.tag, BigInt(p.index))
            case FlatCollectError.NoWitness(h)      => Collect.NoWitness(h)
            case FlatCollectError.NoCostModel(l)    => Collect.NoCostModel(l)
            case FlatCollectError.BadTranslation(e) => Collect.BadTranslation(e)
          })
        case UtxosFailure.ValidationTagMismatch(flag, None) => Seen.PassedUnexpectedly(flag)
        case UtxosFailure.ValidationTagMismatch(flag, Some(fs)) =>
          Seen.FailedUnexpectedly(flag, fs.toList.map(p => EraUtxoRun.cause(p.cause) -> p.request))
    case other => fail(s"not a failure of UTXOS or PPUP: $other")

  /** The counters the flat UTXOS keeps (`PlutusStats`), as UTXOS's events give them. */
  private def counters(valid: Boolean, events: UEvents): (Long, Long, Long) =
    val passed = events.toList.collect { case AlonzoUtxosEvent.SuccessfulPlutusScriptsEvent(ps) =>
      ps.size
    }
    val failed = events.toList.collect { case AlonzoUtxosEvent.FailedPlutusScriptsEvent(fs) =>
      fs.size
    }
    assert(passed.size <= 1 && failed.size <= 1, s"events $events")
    (
      if valid && passed.nonEmpty then 1L else 0L,
      if failed.nonEmpty then 1L else 0L,
      (passed ++ failed).sum.toLong
    )

  // ---- one judgment of UTXOS, then the change of the state, both ways

  /** A transaction both ways, with the state and parameters it is judged on. */
  private final case class Judged(
      name: String,
      flat: FlatTx,
      typed: AlonzoTx,
      known: Map[TxIn, FlatTxOut],
      pp: ProtocolParams,
      cert: ShelleyCertState,
      state: UTxOState[Alonzo],
      globals: Globals,
      slot: BigInt,
      posix: BigInt => BigInt,
      clocked: Boolean = true
  ):
    /** The flat rules' environment at `slot`, of the same geometry the era rules ask (`Globals`).
      */
    def ctx: TxEnv =
      val epoch = globals.epochOf(slot)
      TestLedger.env.copy(
        slot = slot,
        epoch = epoch,
        network = globals.networkId.wire,
        firstSlotNextEpoch = globals.firstSlot(epoch + 1),
        stabilityWindow = globals.stabilityWindow,
        updateQuorum = globals.quorum,
        clock = Option.when(clocked)(PosixClock.Total(posix))
      )

  /** What a judgment came to: its failures in order, or the state after with the scripts' counters.
    */
  private enum Outcome:
    case Refused(failures: List[Seen])
    case Accepted(state: UTxOState[Alonzo], counters: (Long, Long, Long))

  private def flatOutcome(j: Judged, validation: TxEnv.Validation): Outcome =
    val ctx = j.ctx.copy(validation = validation)
    val ann = FlatStAnnTx(j.flat, Utxo(j.known))
    Judgment.run(FlatUtxos.sts[Alonzo], RuleT.Policy.Accumulate)(
      j.globals,
      FlatUtxoEnv(ctx, j.pp, j.cert),
      Counted(j.state, PlutusStats.zero),
      ann
    ) match
      case Left(defect)          => fail(s"${j.name}: flat defect $defect")
      case Right(Left(failures)) => Outcome.Refused(failures.toList.map(flatSeenOf))
      case Right(Right(after)) =>
        val (deposits, refunds) = ShelleyDeposits.of(j.pp, j.cert, j.flat.body)
        val collateral = TxChecks.collateral[Alonzo](ann.utxo, j.flat)
        val next = right(s"${j.name} flat update")(
          FlatUtxo.update[Alonzo](after.state, ann, collateral, deposits, refunds)
        )
        Outcome.Accepted(
          next,
          (after.plutus.succeeded, after.plutus.failed, after.plutus.scripts)
        )

  /** The era rule's UTXOS on `j`: the parameters and UTxO set it read, and its judgment. */
  private def typedRun(
      j: Judged,
      validation: TxEnv.Validation,
      evaluator: ScriptEvaluator[Id]
  ): (PParams[Alonzo], UTxO[Alonzo], Either[UDefect, Either[NonEmptyChain[URec], US]]) =
    val pp = PParams[Alonzo](right(s"${j.name} parameters")(AlonzoPParams.fromStored(j.pp)))
    val utxo = right(s"${j.name} UTxO")(AlonzoEraUTxO.fromStored(j.known))
    val gov = right(s"${j.name} governance state")(
      ShelleyGovStateRecord.fromStored[Alonzo](j.state.gov)
    )
    val stAnnTx = AlonzoStAnnTx.mkAlonzoStAnnTx[Alonzo](
      slot => Either.cond(j.clocked, j.posix(slot), TranslationError.NoEraHistory),
      pp,
      utxo,
      Map.empty,
      j.typed
    )
    (
      pp,
      utxo,
      UtxosRun.run(
        j.globals,
        UtxosEnv(slotNo(j.slot), pp, j.cert),
        stAnnTx,
        gov,
        evaluator,
        validation
      )
    )

  /** The era rule's UTXOS failures on `j`, as it records them, for their encoding. */
  private def typedFailures(j: Judged): NonEmptyChain[URec] =
    typedRun(j, TxEnv.Validation.All, ScarabEvaluator.default)._3 match
      case Right(Left(failures)) => failures
      case other                 => fail(s"${j.name}: not refused: $other")

  /** An era failure of UTXOS or PPUP as the flat failure the node's refusal encoder writes, as the
    * era encoder of Conway's failures projects a script's failure (`ConwayFailureCbor`): its cause
    * and its request.
    */
  private def flatOf(f: URec): PredicateFailure = f match
    case AlonzoUtxosPredFailure.UpdateFailure(p) =>
      PredicateFailure.utxo(UtxoFailure.Update(flatPpupOf(p)))
    case AlonzoUtxosPredFailure.ValidationTagMismatch(flag, description) =>
      PredicateFailure.utxo(
        UtxoFailure.Utxos(
          UtxosFailure.ValidationTagMismatch(
            flag == IsPhase2Valid.Phase2Valid,
            description match
              case TagMismatchDescription.PassedUnexpectedly => None
              case TagMismatchDescription.FailedUnexpectedly(fs) =>
                Some(fs.toList.toVector.map { case FailureDescription.PlutusFailure(cause, pwc) =>
                  xyz.kd5ujc.scarab.ledger
                    .PlutusFailure(cause, Bytes.empty, Some(PlutusWithContext.evalRequest(pwc)))
                })
          )
        )
      )
    case AlonzoUtxosPredFailure.CollectErrors(es) => fail(s"not encoded here: $es")

  private def typedOutcome(
      j: Judged,
      validation: TxEnv.Validation,
      evaluator: ScriptEvaluator[Id] = ScarabEvaluator.default
  ): Outcome =
    val (pp, utxo, judged) = typedRun(j, validation, evaluator)
    judged match
      case Left(defect) => fail(s"${j.name}: era defect $defect")
      case Right(Left(failures)) =>
        val needed = EraUTxO.alonzo
          .getScriptsNeeded[TxLevel.Top](utxo, t.body[TxLevel.Top](j.typed)(using t.bodyOf.top))(
            using t.bodyOf.top
          )
          .unAlonzoScriptsNeeded
          .map(_._1)
        Outcome.Refused(failures.toList.map(seenOf(needed)))
      case Right(Right((after, events))) =>
        val withGov =
          j.state.copy[Alonzo](gov = ShelleyGovStateRecord.toStored[Alonzo](after, j.state.gov))
        Outcome.Accepted(
          UpdateRun.run(pp, j.cert, j.typed, withGov),
          counters(t.isValid(j.typed) == IsPhase2Valid.Phase2Valid, events)
        )

  /** The two outcomes, compared whole: the state field by field, so that a difference names its
    * field.
    */
  private def same(j: Judged, validation: TxEnv.Validation = TxEnv.Validation.All): Outcome =
    val flat = flatOutcome(j, validation)
    val typed = typedOutcome(j, validation)
    (flat, typed) match
      case (Outcome.Accepted(f, fc), Outcome.Accepted(e, ec)) =>
        // the UTxO set's entries and the totals it keeps of them, then the record whole
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
      case _ => assertEquals(typed, flat, j.name)
    typed

  // ---- the recorded V1-context submissions

  private final case class Verdict(accepted: Boolean, text: String)

  private lazy val v1: Vector[(PlutusCase, Verdict)] =
    Vector("alonzo-pv5", "alonzo-pv6").flatMap { name =>
      val path = Path.of(s"fixtures/reference/v1-context/$name.json")
      if !Files.isRegularFile(path) then fail(s"missing committed fixture $path")
      right(name)(parse(Files.readString(path))).asArray
        .getOrElse(fail(s"$name: not an array"))
        .map { j =>
          val reference = j.hcursor.downField("reference")
          PlutusCase.fromJson(j) -> Verdict(
            right("accepted")(reference.get[Boolean]("accepted")),
            right("error")(reference.get[String]("error"))
          )
        }
    }

  private def v1Judged(c: PlutusCase): Judged =
    val witnesses = c.tx.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    val parts = Block.Transaction(c.tx.body.raw, witnesses, c.tx.auxiliary, c.tx.isValid)
    val known = c.spent ++ c.references ++ c.collateral
    val nes: NewEpochState[Alonzo] = Build.funded(Build.nes[Alonzo](5, c.params), known)
    val ls = nes.es.ls
    Judged(
      c.name,
      c.tx,
      right(s"${c.name} typed")(AlonzoTx.decode(parts, StateLayout.Node11_1)),
      known,
      c.params,
      ls.cert,
      ls.utxo,
      TestLedger.globals,
      TestLedger.env.slot,
      TestLedger.env.clock.getOrElse(fail("no clock")).posix(_).getOrElse(fail("no time"))
    )

  /** The cause the reference's refusal names, as the era rule gives it: every refused Alonzo
    * submission of the V1 contexts is a tag mismatch, with the description the reference recorded.
    */
  private def referenceCause(c: PlutusCase, v: Verdict, failures: List[Seen]): Unit =
    assert(v.text.contains("ValidationTagMismatch"), s"${c.name}: ${v.text}")
    // the description the reference recorded: the scripts passed against the flag, or failed
    val passed = v.text.contains("PassedUnexpectedly")
    assertNotEquals(passed, v.text.contains("FailedUnexpectedly"), s"${c.name}: ${v.text}")
    failures match
      case List(Seen.PassedUnexpectedly(flag)) if passed =>
        assertEquals(flag, c.tx.isValid, c.name)
      case List(Seen.FailedUnexpectedly(flag, _)) if !passed =>
        assertEquals(flag, c.tx.isValid, c.name)
      case other => fail(s"${c.name}: not the recorded validation tag mismatch: $other")

  test(
    "the V1-context submissions: the flat rules' judgment and state, and the reference's verdict"
  ) {
    val outcomes = v1.map { (c, v) =>
      val outcome = same(v1Judged(c))
      outcome match
        case Outcome.Accepted(_, _) =>
          assert(v.accepted, s"${c.name}: accepted what the reference refused")
        case Outcome.Refused(failures) =>
          assert(!v.accepted, s"${c.name}: refused what the reference accepted: $failures")
          referenceCause(c, v, failures)
      (c.tx.isValid, outcome)
    }
    // 10 rows; the reference accepted six, two of them flagged invalid, which take their
    // collateral; none proposes an update or carries a certificate
    assertEquals(outcomes.size, 10)
    assertEquals(
      outcomes
        .collect { case (valid, Outcome.Accepted(_, _)) => valid }
        .groupMapReduce(identity)(_ => 1)(_ + _),
      Map(true -> 4, false -> 2)
    )
    assertEquals(
      v1.count((c, _) => c.tx.body.update.nonEmpty || c.tx.body.certificates.nonEmpty),
      0
    )
  }

  test(
    "the V1-context submissions applied again (NonStatic) collect their scripts and evaluate none"
  ) {
    val compared = v1.map((c, _) => same(v1Judged(c), TxEnv.Validation.NonStatic))
    assertEquals(compared.size, 10)
  }

  // ---- PPUP: the crafted cases

  private val pp6: ProtocolParams = TestLedger.params(6)

  private def genesis(i: Int): KeyHash[Genesis] =
    right("genesis key")(KeyHash.fromBytes[Genesis](Bytes.fromArray(Array.fill(28)(i.toByte))))

  private def delegs(ids: Int*): SortedMap[KeyHash[Genesis], GenDelegPair] =
    Sorted.map(
      ids.toVector.map(i =>
        genesis(i) -> GenDelegPair(
          Bytes.fromArray(Array.fill(28)((i + 100).toByte)),
          Bytes.fromArray(Array.fill(32)(i.toByte))
        )
      )
    )

  private def emptyGov(pp: ProtocolParams): StoredGov =
    StoredGov(Sorted.empty, Sorted.empty, pp, pp, StoredFuturePParams.NoPParamsUpdate)

  private def hex(s: String): Bytes = right(s)(Bytes.fromHex(s))

  /** An update's field: its key and the bytes of its value. */
  private def field(key: Int, value: String): (Int, Bytes) = key -> hex(value)

  private def proposal(fields: (Int, Bytes)*): ParamUpdate = ParamUpdate(fields.toMap)

  private def update(epoch: BigInt, proposals: (Int, ParamUpdate)*): StoredUpdate =
    StoredUpdate(TreeMap.from(proposals.map((i, p) => genesis(i).bytes -> p)), epoch)

  /** A PPUP case: the update `u` proposed at `slot` on `gov`, the parameters `pp` in force. */
  private final case class PpupCase(
      name: String,
      globals: Globals,
      slot: BigInt,
      pp: ProtocolParams,
      genDelegs: SortedMap[KeyHash[Genesis], GenDelegPair],
      gov: StoredGov,
      u: StoredUpdate,
      kept: Option[KeptUpdate[Alonzo]] = None
  )

  /** PPUP's outcome: its failures, or the governance state after as the node stores it. */
  private type PpupOutcome = Either[List[PpupFailure], StoredGov]

  private def flatPpup(c: PpupCase): PpupOutcome =
    val epoch = c.globals.epochOf(c.slot)
    val ctx = TestLedger.env.copy(
      slot = c.slot,
      epoch = epoch,
      firstSlotNextEpoch = c.globals.firstSlot(epoch + 1),
      stabilityWindow = c.globals.stabilityWindow,
      updateQuorum = c.globals.quorum
    )
    Judgment.run(FlatPpup.sts[Alonzo], RuleT.Policy.Accumulate)(
      c.globals,
      FlatPpupEnv(ctx, Bytes.empty, c.pp, c.genDelegs),
      c.gov,
      Some(c.u)
    ) match
      case Left(defect) => fail(s"${c.name}: flat defect $defect")
      case Right(Left(failures)) =>
        Left(failures.toList.map {
          case PredicateFailure.Utxow(UtxowFailure.Utxo(UtxoFailure.Update(p))) => p
          case other => fail(s"${c.name}: not PPUP's: $other")
        })
      case Right(Right(after)) => Right(after)

  private def typedPpup(c: PpupCase): PpupOutcome =
    // the proposal as a body's decoder read it, or as read from its stored form
    val kept = c.kept.getOrElse(right(s"${c.name} update")(KeptUpdate.fromStored[Alonzo](c.u)))
    val gov = right(s"${c.name} governance")(ShelleyGovStateRecord.fromStored[Alonzo](c.gov))
    val pp = PParams[Alonzo](right(s"${c.name} parameters")(AlonzoPParams.fromStored(c.pp)))
    PpupRun.run(c.globals, PpupEnv(slotNo(c.slot), pp, c.genDelegs), Some(kept), gov) match
      case Left(defect)                  => fail(s"${c.name}: era defect $defect")
      case Right(Left(failures))         => Left(failures.toList.map(flatPpupOf))
      case Right(Right((after, events))) =>
        // `tellEvent $ PpupNewEpoch nextEpochNo`, once
        assertEquals(
          events.toList,
          List(
            ShelleyPpupEvent.PpupNewEpoch[Alonzo](
              right("epoch")(EpochNo.fromBigInt(c.globals.epochOf(c.slot) + 1))
            )
          ),
          c.name
        )
        Right(ShelleyGovStateRecord.toStored[Alonzo](after, c.gov))

  /** Both sides on `c`, which must agree: the era rule's outcome. */
  private def ppupBoth(c: PpupCase): PpupOutcome =
    val typed = typedPpup(c)
    assertEquals(typed, flatPpup(c), c.name)
    typed

  private def ok[A](a: A): Either[List[PpupFailure], A] = Right(a)

  private val g = TestLedger.globals
  // epochs of 100 slots, a stability window of 3k/f = 12: in epoch 5 (slots 500 to 599) the point of
  // no return is 600 - 24 = 576
  private val tooLate5 = BigInt(576)

  /** The protocol version `(major, minor)` as an update's value. */
  private def protVer(major: Int, minor: Long): (Int, Bytes) =
    14 -> Bytes(Vector(0x82.toByte) ++ uint(BigInt(major)).value ++ uint(BigInt(minor)).value)

  /** A word's canonical CBOR. */
  private def uint(n: BigInt): Bytes =
    if n < 24 then Bytes(Vector(n.toByte))
    else if n < 256 then Bytes(Vector(0x18.toByte, n.toByte))
    else if n < 65536 then Bytes(Vector(0x19.toByte, (n >> 8).toByte, n.toByte))
    else if n < (BigInt(1) << 32) then
      Bytes(Vector(0x1a.toByte) ++ (0 until 4).reverse.map(i => (n >> (8 * i)).toByte))
    else Bytes(Vector(0x1b.toByte) ++ (0 until 8).reverse.map(i => (n >> (8 * i)).toByte))

  private val maxTx = field(3, "194000") // 16384, its shortest form
  private val maxTxWide = field(3, "1a00004000") // 16384, a four-byte head

  private def crafted(name: String, slot: BigInt, u: StoredUpdate, gov: StoredGov = emptyGov(pp6))(
      using pp: ProtocolParams = pp6
  ): PpupCase =
    PpupCase(name, g, slot, pp, delegs(1, 2, 3, 4, 5, 6, 7), gov, u)

  test("PPUP: an update for this epoch before the point of no return, and for the next from it") {
    val p = proposal(maxTx)
    val before = ppupBoth(crafted("this epoch", tooLate5 - 1, update(5, 1 -> p)))
    val at = ppupBoth(crafted("the next epoch", tooLate5, update(6, 1 -> p)))
    assertEquals(before.map(_.curProposals.keySet), ok(Set(genesis(1))))
    assertEquals(before.map(_.futureProposals.isEmpty), ok(true))
    // one vote of a quorum of two: no prediction
    assertEquals(before.map(_.futurePParams), ok(StoredFuturePParams.PotentialPParamsUpdate(None)))
    assertEquals(at.map(_.futureProposals.keySet), ok(Set(genesis(1))))
    assertEquals(at.map(_.curProposals.isEmpty), ok(true))
    assertEquals(at.map(_.futurePParams), ok(StoredFuturePParams.NoPParamsUpdate))
  }

  test("PPUP: an update for the wrong epoch, on either side of the point of no return") {
    val p = proposal(maxTx)
    import xyz.kd5ujc.scarab.ledger.VotingPeriod.*
    assertEquals(
      ppupBoth(crafted("next before", tooLate5 - 1, update(6, 1 -> p))),
      Left(List(PpupFailure.PPUpdateWrongEpoch(5, 6, VoteForThisEpoch)))
    )
    assertEquals(
      ppupBoth(crafted("this at", tooLate5, update(5, 1 -> p))),
      Left(List(PpupFailure.PPUpdateWrongEpoch(5, 5, VoteForNextEpoch)))
    )
    // the first slot of the epoch and its last
    assertEquals(ppupBoth(crafted("first slot", 500, update(5, 1 -> p))).isRight, true)
    assertEquals(ppupBoth(crafted("last slot", 599, update(6, 1 -> p))).isRight, true)
  }

  test("PPUP: two stability windows longer than an epoch put the point of no return at slot 0") {
    // k = 40, f = 1/2: a window of 240 slots, so `firstSlotNextEpoch *- 480` stops at 0
    val geometry = right("geometry")(
      xyz.kd5ujc.scarab.ledger.EpochGeometry.checked(
        java.time.Instant.EPOCH,
        1000L,
        100L,
        xyz.kd5ujc.scarab.ledger.TestGeometry.k(40),
        xyz.kd5ujc.scarab.ledger.TestGeometry.f("0.5")
      )
    )
    val wide = g.copy(geometry = geometry)
    val p = proposal(maxTx)
    def at(slot: BigInt, epoch: BigInt) =
      ppupBoth(crafted(s"slot $slot", slot, update(epoch, 1 -> p)).copy(globals = wide))
    assertEquals(at(0, 1).map(_.futureProposals.keySet), ok(Set(genesis(1))))
    assertEquals(at(250, 3).map(_.futureProposals.keySet), ok(Set(genesis(1))))
    assert(at(0, 0).isLeft)
  }

  test("PPUP: a proposer that is not a genesis key") {
    val u = update(5, 1 -> proposal(maxTx), 9 -> proposal(maxTx))
    assertEquals(
      ppupBoth(crafted("non-genesis", 510, u).copy(genDelegs = delegs(1, 2, 3))),
      Left(
        List(
          PpupFailure.NonGenesisUpdate(
            xyz.kd5ujc.scarab.ledger.Mismatch(
              Set(genesis(1).bytes, genesis(9).bytes),
              Set(1, 2, 3).map(genesis(_).bytes)
            )
          )
        )
      )
    )
  }

  test("PPUP: the versions that follow the one in force, and the first that cannot, by key hash") {
    // the parameters' version is (6, 0)
    val follows = Vector(protVer(7, 0), protVer(6, 1))
    follows.foreach(v =>
      assert(ppupBoth(crafted(s"follows $v", 510, update(5, 1 -> proposal(v)))).isRight)
    )
    val cannot = Vector((6, 2L), (7, 1L), (6, 0L), (5, 0L), (5, 1L))
    cannot.foreach { (major, minor) =>
      assertEquals(
        ppupBoth(
          crafted(s"cannot ($major, $minor)", 510, update(5, 1 -> proposal(protVer(major, minor))))
        ),
        Left(List(PpupFailure.PVCannotFollow(major, minor)))
      )
    }
    // six delegates, two of them with versions that cannot follow: the first by key hash is named,
    // whatever order the proposal lists them in
    val mixed = update(
      5,
      6 -> proposal(protVer(7, 1)),
      1 -> proposal(protVer(7, 0)),
      4 -> proposal(protVer(6, 2)),
      2 -> proposal(maxTx),
      3 -> proposal(protVer(7, 0)),
      5 -> proposal(protVer(6, 1))
    )
    assertEquals(
      ppupBoth(crafted("two that cannot follow", 510, mixed)),
      Left(List(PpupFailure.PVCannotFollow(6, 2)))
    )
  }

  test("PPUP: every failure at once, in the reference's order") {
    import xyz.kd5ujc.scarab.ledger.VotingPeriod.*
    val u = update(7, 9 -> proposal(protVer(7, 1)))
    // run here as the root, whose failures the reference lists in the reverse of their recording
    // (`Judgment.flatten`); a submitted transaction's PPUP is five levels below MEMPOOL (LEDGER,
    // UTXOW, UTXO, UTXOS, PPUP), an odd depth, which lists them as recorded
    assertEquals(
      ppupBoth(crafted("all three", 510, u).copy(genDelegs = delegs(1))).leftMap(_.reverse),
      Left(
        List(
          PpupFailure.NonGenesisUpdate(
            xyz.kd5ujc.scarab.ledger.Mismatch(Set(genesis(9).bytes), Set(genesis(1).bytes))
          ),
          PpupFailure.PVCannotFollow(7, 1),
          PpupFailure.PPUpdateWrongEpoch(5, 7, VoteForThisEpoch)
        )
      )
    )
  }

  test(
    "PPUP: a minor version that wraps at Word32, which the reference's pvCanFollow follows (#1514)"
  ) {
    // the parameters at (6, 2^32 - 1): the reference's `curMinor + 1` is a `Word32` sum
    // (`Shelley/PParams.hs:305-307`, cardano-ledger f649f975), so (6, 0) follows, on the era rule
    // and on the flat check (`UpdateChecks.canFollow`, which calls `pvCanFollow` since #1514)
    val atMax = pp6.copy(protocolMinor = (BigInt(1) << 32) - 1)
    val c = crafted("wrap", 510, update(5, 1 -> proposal(protVer(6, 0))))(using atMax)
      .copy(gov = emptyGov(atMax))
    assert(ppupBoth(c).isRight, "both follow the reference")
    // (7, 0) follows on both
    assert(ppupBoth(c.copy(u = update(5, 1 -> proposal(protVer(7, 0))))).isRight)
  }

  test("PPUP: the votes of a quorum, however each value is written, and their stored bytes") {
    // two delegates propose 16384, one in the shortest form and one in a four-byte head: one
    // update by value, which has the quorum of two
    val two =
      ppupBoth(crafted("quorum", 510, update(5, 1 -> proposal(maxTx), 2 -> proposal(maxTxWide))))
    val voted = two.map(_.futurePParams)
    assertEquals(
      voted,
      ok(StoredFuturePParams.PotentialPParamsUpdate(Some(pp6.copy(maxTxSize = 16384))))
    )
    // each delegate's bytes are kept as proposed
    assertEquals(
      two.map(_.curProposals.view.mapValues(_.fields(3)).toMap),
      ok(Map(genesis(1) -> maxTx._2, genesis(2) -> maxTxWide._2))
    )
    // the mutant: the era record with the wide proposal written in its shortest form is equal as
    // the reference compares updates, and writes back other bytes
    val gov = right("read")(ShelleyGovStateRecord.fromStored[Alonzo](right("two")(two)))
    val canonical = right("canonical")(KeptPParamsUpdate.fromStored[Alonzo](proposal(maxTx)))
    val mutant = gov.copy(sgsCurProposals =
      KeptProposedPPUpdates(gov.sgsCurProposals.updates.updated(genesis(2), canonical))
    )
    assertEquals(mutant, gov)
    assertNotEquals(
      ShelleyGovStateRecord.toStored[Alonzo](mutant, right("two")(two)),
      right("two")(two)
    )
    // a ratio written as 1/2 and as 2/4 (the reference's `%` reduces, `boundRational`): one value
    // with the quorum, on both sides
    val half = ppupBoth(
      crafted(
        "ratios",
        510,
        update(5, 1 -> proposal(field(10, "d81e820102")), 2 -> proposal(field(10, "d81e820204")))
      )
    )
    assert(
      half.map(_.futurePParams) match
        case Right(StoredFuturePParams.PotentialPParamsUpdate(Some(p))) =>
          p.rho == xyz.kd5ujc.scarab.codec.Rational(1, 2)
        case _ => false
      ,
      s"$half"
    )
    // a quorum less one: no prediction; two different updates of one vote each: none either
    val one = ppupBoth(crafted("one vote", 510, update(5, 1 -> proposal(maxTx))))
    assertEquals(one.map(_.futurePParams), ok(StoredFuturePParams.PotentialPParamsUpdate(None)))
    val split = ppupBoth(
      crafted("split", 510, update(5, 1 -> proposal(maxTx), 2 -> proposal(field(3, "193000"))))
    )
    assertEquals(split.map(_.futurePParams), ok(StoredFuturePParams.PotentialPParamsUpdate(None)))
  }

  test("PPUP: the voted parameters need transaction and header sizes below the block body's") {
    // maxBlockBodySize 65536 and the header size 1100: a transaction size of 64435 is the largest
    // the guard lets through
    val header = pp6.maxBlockHeaderSize
    val largest = BigInt(65536) - header - 1
    def voted(size: BigInt) =
      val p = proposal(3 -> uint(size))
      ppupBoth(crafted(s"size $size", 510, update(5, 1 -> p, 2 -> p))).map(_.futurePParams)
    assertEquals(
      voted(largest),
      ok(StoredFuturePParams.PotentialPParamsUpdate(Some(pp6.copy(maxTxSize = largest))))
    )
    assertEquals(voted(largest + 1), ok(StoredFuturePParams.PotentialPParamsUpdate(None)))
  }

  test("PPUP: a delegate's new proposal replaces its earlier one, and the others' stay") {
    val earlier =
      ppupBoth(crafted("earlier", 510, update(5, 1 -> proposal(maxTx), 2 -> proposal(maxTx))))
    val later = ppupBoth(
      crafted("later", 520, update(5, 1 -> proposal(field(3, "193000"))), right("earlier")(earlier))
    )
    assertEquals(
      later.map(_.curProposals.view.mapValues(_.fields(3)).toMap),
      ok(Map(genesis(1) -> hex("193000"), genesis(2) -> maxTx._2))
    )
    // the vote is lost: one each
    assertEquals(later.map(_.futurePParams), ok(StoredFuturePParams.PotentialPParamsUpdate(None)))
    val future =
      ppupBoth(crafted("future", 580, update(6, 3 -> proposal(maxTx)), right("later")(later)))
    val replaced = ppupBoth(
      crafted("future again", 590, update(6, 3 -> proposal(maxTxWide)), right("future")(future))
    )
    assertEquals(
      replaced.map(_.futureProposals.view.mapValues(_.fields(3)).toMap),
      ok(Map(genesis(3) -> maxTxWide._2))
    )
    // the current proposals and the prediction stay as they were
    assertEquals(replaced.map(_.curProposals), later.map(_.curProposals))
    assertEquals(replaced.map(_.futurePParams), later.map(_.futurePParams))
  }

  test("PPUP: no update leaves the state as it is and tells nothing") {
    val gov = right("gov")(ShelleyGovStateRecord.fromStored[Alonzo](emptyGov(pp6)))
    val pp = PParams[Alonzo](right("pp")(AlonzoPParams.fromStored(pp6)))
    assertEquals(
      PpupRun.run(g, PpupEnv(slotNo(510), pp, delegs(1)), None, gov),
      Right(Right((gov, Chain.empty)))
    )
  }

  // ---- the refusal corpora's Alonzo cases, with their recorded states

  /** A recorded case: its judgment both ways and the reference's reason. */
  private final case class Refused(judged: Judged, reason: Bytes, families: Vector[Family])

  private lazy val refusals: Vector[Refused] =
    val loaded = right("the refusal corpus")(
      ReferenceRefusalSuite.load(Path.of("fixtures/reference/refusals"))
    )
    loaded.corpora.filter(_.era == RefusalEra.Alonzo).flatMap { c =>
      val setup = right(s"${c.path} genesis")(
        NodeSetup.fromFiles(c.build)(name => c.genesis.get(name).map(_.toArray))
      )
      val globals = Globals(setup.globals, setup.geometry)
      val codecs = right(s"${c.path} layout")(ReferenceRefusalSuite.stateCodecs(c.build))
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val nes = right(s"$what state")(
          codecs.newEpochState[Alonzo].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val known = right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
        val funded = NewEpochState
          .ledger[Alonzo]
          .modify(ls =>
            ls.copy[Alonzo](utxo = ls.utxo.copy(utxo = UtxoTables(Utxo.fromMap(known))))
          )(nes)
        val slot = rc.tipSlot + 1
        val ticked = right(s"$what tick")(
          Sts(Tick.sts[Alonzo])(globals, (), funded, right("slot")(SlotNo.fromBigInt(slot)))
        )
        val ls = ticked.es.ls
        val bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        Refused(
          Judged(
            what,
            right(s"$what flat")(FlatTx.fromEnvelope(bytes, ProtocolEra.Alonzo)),
            right(s"$what typed")(AlonzoTx.fromEnvelope(bytes, StateLayout.Node11_1)),
            known,
            ls.utxo.gov.curPParams,
            ls.cert,
            ls.utxo,
            globals,
            slot,
            _ * 1000
          ),
          rc.reason,
          rc.families
        )
      }
    }

  test(
    "the refusal corpora's Alonzo cases: the flat rules' judgment and state, deposits among it"
  ) {
    val outcomes = refusals.map(r => r -> same(r.judged))
    assertEquals(outcomes.size, 13)
    val accepted = outcomes.collect { case (r, Outcome.Accepted(after, _)) => r -> after }
    val refused = outcomes.collect { case (r, Outcome.Refused(fs)) => r.judged.name -> fs }
    // UTXOS passes every case its other rules refused but two, refused by UTXOS alone: the PPUP case
    // and a transaction flagged invalid with no script to fail, whose evaluation of none passes
    assertEquals(
      refused.map((name, fs) => name.split('/').last -> fs.size),
      Vector("ppup-non-genesis-proposer" -> 1, "utxos-empty-script-set-invalid" -> 1)
    )
    assertEquals(
      refused.collect { case (_, List(Seen.PassedUnexpectedly(flag))) => flag },
      Vector(false)
    )
    // those refused by UTXOS alone: the era rule's failures are the reference's, byte for byte
    val alone = refusals.filter(_.families.forall(f => f == Family.UTXOS || f == Family.PPUP))
    assertEquals(alone.map(_.judged.name.split('/').last), refused.map(_._1.split('/').last))
    alone.foreach { r =>
      val failures = typedFailures(r.judged).map(flatOf)
      assertEquals(
        LocalSubmission.reasonItem(
          Rejection.Ledger(
            xyz.kd5ujc.scarab.ledger.LedgerFailure.Rejected(r.judged.flat.body.id, failures)
          ),
          Some(ProtocolEra.Alonzo)
        ),
        Right(r.reason),
        r.judged.name
      )
    }
    // the cases whose certificates pay or collect deposits, and those that propose an update
    val deposits = accepted.filter((r, after) => after.deposited != r.judged.state.deposited)
    assertEquals(deposits.size, DepositCases)
    assertEquals(
      deposits.map((r, after) => (after.deposited.value - r.judged.state.deposited.value).signum),
      DepositSigns
    )
    assertEquals(refusals.count(_.judged.flat.body.update.nonEmpty), 1)
    assertEquals(refusals.count(_.judged.flat.body.certificates.nonEmpty), CertificateCases)
  }

  test(
    "the PPUP case: the reference's NonGenesisUpdatePPUP, its proposers and genesis keys, byte for byte"
  ) {
    val r =
      refusals.find(_.judged.name.endsWith("/ppup-non-genesis-proposer")).getOrElse(fail("no case"))
    assertEquals(r.families, Vector(Family.PPUP))
    val failure = typedOutcome(r.judged, TxEnv.Validation.All) match
      case Outcome.Refused(List(Seen.Ppup(f))) => f
      case other                               => fail(s"not one failure of PPUP: $other")
    failure match
      case PpupFailure.NonGenesisUpdate(m) =>
        assertEquals(m.supplied.size, 1)
        assertEquals(m.expected, r.judged.cert.dState.genDelegs.keySet.map(_.bytes))
      case other => fail(s"not NonGenesisUpdatePPUP: $other")
    val encoded = LocalSubmission.reasonItem(
      Rejection.Ledger(
        xyz.kd5ujc.scarab.ledger.LedgerFailure
          .Rejected(r.judged.flat.body.id, PredicateFailure.utxo(UtxoFailure.Update(failure)))
      ),
      Some(ProtocolEra.Alonzo)
    )
    assertEquals(encoded, Right(r.reason))
  }

  // ---- PPUP on the era-walking capture

  private lazy val walk: Vector[(ErasStates.Sample, NewEpochState[Alonzo])] =
    ErasStates.samples
      .filter(_.era == ProtocolEra.Alonzo)
      .flatMap(s => MirrorStep.nesOf(s.mirror).map(s -> _))
      .map { case (s, i: EraNes.InEra[e]) =>
        s -> NewEpochState
          .retype(i.era, ShelleyBasedEra.SAlonzo)(i.nes)
          .getOrElse(fail("not Alonzo"))
      }

  private lazy val walkGlobals = Globals(ErasStates.setup.globals, ErasStates.setup.geometry)

  /** PPUP on `nes` at `slot`, its parameters, genesis delegations and governance state. */
  private def onState(name: String, nes: NewEpochState[Alonzo], slot: BigInt, u: StoredUpdate) =
    val gov = nes.es.ls.utxo.gov
    PpupCase(name, walkGlobals, slot, gov.curPParams, nes.es.ls.cert.dState.genDelegs, gov, u)

  test(
    "PPUP on every Alonzo state of the capture that holds proposals: proposed again on either side of the point of no return"
  ) {
    val globals = walkGlobals
    // 192 Alonzo states, 187 of them holding the three delegates' proposals
    assertEquals(walk.size, 192)
    assertEquals(walk.count(_._2.es.ls.utxo.gov.curProposals.isEmpty), 5)
    val judged: Vector[(String, PpupOutcome)] = walk.flatMap { (s, nes) =>
      val gov = nes.es.ls.utxo.gov
      val epoch = globals.epochOf(s.anchor.slot)
      val tooLate = xyz.kd5ujc.scarab.ledger.EpochGeometry
        .slotOfNoReturn(globals.firstSlot(epoch + 1), globals.stabilityWindow)
      def proposed(ps: SortedMap[KeyHash[Genesis], ParamUpdate], target: BigInt) =
        StoredUpdate(TreeMap.from(ps.map((k, p) => k.bytes -> p)), target)
      val current = Option.when(gov.curProposals.nonEmpty && tooLate > s.anchor.slot)(
        ppupBoth(
          onState(s"${s.anchor} this epoch", nes, tooLate - 1, proposed(gov.curProposals, epoch))
        )
      )
      val next = Option.when(gov.curProposals.nonEmpty)(
        ppupBoth(
          onState(
            s"${s.anchor} next epoch",
            nes,
            tooLate.max(s.anchor.slot),
            proposed(gov.curProposals, epoch + 1)
          )
        )
      )
      val wrong = Option.when(gov.curProposals.nonEmpty)(
        ppupBoth(
          onState(
            s"${s.anchor} wrong epoch",
            nes,
            tooLate.max(s.anchor.slot),
            proposed(gov.curProposals, epoch)
          )
        )
      )
      current.toVector.map("current" -> _) ++ next.toVector.map("next" -> _) ++ wrong.toVector.map(
        "wrong" -> _
      )
    }
    val tally = judged.groupMapReduce { case (kind, o) => (kind, o.isRight) }(_ => 1)(_ + _)
    assertEquals(tally, WalkTally)
    // a proposal of this epoch keeps the state's proposals and predicts the parameters again
    val predictions = judged.collect { case ("current", Right(after)) => after.futurePParams }
    assertEquals(
      predictions.groupMapReduce {
        case StoredFuturePParams.PotentialPParamsUpdate(Some(_)) => "voted"
        case StoredFuturePParams.PotentialPParamsUpdate(None)    => "none"
        case other                                               => fail(s"$other")
      }(_ => 1)(_ + _),
      WalkPredictions
    )
  }

  test("PPUP on the capture's Alonzo transactions that propose an update, on the state before") {
    val pairs = walk.zip(walk.drop(1))
    val judged = pairs.flatMap { case ((_, before), (after, _)) =>
      after.block match
        case b: Block.Shelley =>
          b.transactions.flatMap { parts =>
            val flat = right("flat")(FlatTx.fromBlock(parts, ProtocolEra.Alonzo))
            val typed = right("typed")(AlonzoTx.decode(parts, StateLayout.Node11_1))
            assertEquals(
              t.bodyOf.update(t.body[TxLevel.Top](typed)(using t.bodyOf.top)).isEmpty,
              flat.body.update.isEmpty
            )
            // the era rule reads the proposal as Alonzo's body decoder kept it, the bytes the
            // flat decoder stores
            val kept = t.bodyOf.keptUpdate(t.body[TxLevel.Top](typed)(using t.bodyOf.top))
            assertEquals(kept.map(_.stored), flat.body.update)
            flat.body.update.map(u =>
              ppupBoth(
                onState(s"${after.anchor} ${flat.body.id.hex}", before, after.anchor.slot, u)
                  .copy(kept = kept)
              )
            )
          }
        case _ => Vector.empty
    }
    assertEquals(judged.map(_.isRight), CaptureProposals)
  }

  // ---- UTXOS: the crafted cases

  /** A V1-context case's body with an update proposal of delegate 1 added (key 6), read both ways.
    */
  private def withUpdate(
      c: PlutusCase,
      epoch: Int,
      valid: Boolean,
      maxTxSize: String = "194000"
  ): Judged =
    val base = v1Judged(c)
    val raw = c.tx.body.raw
    val head = raw.value.head & 0xff
    assert(head >= 0xa0 && head < 0xb7, s"${c.name}: a body of fewer than 23 keys")
    val update = hex("82a1581c" + genesis(1).bytes.hex + "a103" + maxTxSize) ++ uint(epoch)
    val body = Bytes(((head + 1).toByte +: raw.value.tail) ++ (0x06.toByte +: update.value))
    val witnesses = c.tx.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    val parts = Block.Transaction(body, witnesses, c.tx.auxiliary, valid)
    base.copy(
      name = s"${c.name} with an update",
      flat = right("flat")(FlatTx.fromBlock(parts, ProtocolEra.Alonzo)),
      typed = right("typed")(AlonzoTx.decode(parts, StateLayout.Node11_1))
    )

  private def caseNamed(name: String): PlutusCase =
    v1.map(_._1).find(_.name == name).getOrElse(fail(s"no case $name"))

  private def withGenesis(j: Judged): Judged =
    j.copy(cert = j.cert.copy(dState = j.cert.dState.copy(genDelegs = delegs(1, 2, 3))))

  /** The UTXOS judgment of `j` on the era rule alone, with `evaluator`. */
  private def typedJudgment(j: Judged, evaluator: ScriptEvaluator[Id]) =
    typedRun(j, TxEnv.Validation.All, evaluator)._3 match
      case Left(defect)  => fail(s"${j.name}: era defect $defect")
      case Right(judged) => judged

  private val nonGenesis = Seen.Ppup(
    PpupFailure.NonGenesisUpdate(
      xyz.kd5ujc.scarab.ledger.Mismatch(Set(genesis(1).bytes), Set.empty)
    )
  )

  test(
    "UTXOS: the scripts run before PPUP, which runs whatever they gave, its failure and event after theirs"
  ) {
    // the slot 1000 is in epoch 10, before its point of no return (1076)
    val c = caseNamed("alonzo-pv6-v1-plain")
    val j = withUpdate(c, 10, valid = true)
    // no genesis delegation: the proposer is not a genesis key, and the script still runs and passes
    assertEquals(same(j), Outcome.Refused(List(nonGenesis)))
    // a script that fails against the flag and a proposer that is not a genesis key: both are
    // recorded, the scripts' first, which the judgment run here as the root lists in the reverse
    // of their recording (`Judgment.flatten`), as the flat rules do
    val failing = withUpdate(caseNamed("alonzo-pv6-v1-plain-fail-valid-tag"), 10, valid = true)
    same(failing) match
      case Outcome.Refused(List(p, Seen.FailedUnexpectedly(true, List(_)))) =>
        assertEquals(p, nonGenesis)
      case other => fail(s"not the scripts' failure then PPUP's: $other")
    // with the genesis delegation, it records the proposal and its scripts run
    same(withGenesis(j)) match
      case Outcome.Accepted(after, (1, 0, 1)) =>
        assertEquals(after.gov.curProposals.keySet, Set(genesis(1)))
        assertEquals(after.gov.curProposals.get(genesis(1)).map(_.fields(3)), Some(hex("194000")))
      case other => fail(s"not accepted with its script passed: $other")
    // for the wrong epoch: PPUP's failure, the script having passed
    assertEquals(
      same(withGenesis(withUpdate(c, 11, valid = true))),
      Outcome.Refused(
        List(
          Seen.Ppup(
            PpupFailure.PPUpdateWrongEpoch(
              10,
              11,
              xyz.kd5ujc.scarab.ledger.VotingPeriod.VoteForThisEpoch
            )
          )
        )
      )
    )
    // the scripts' event, then PPUP's, embedded in UTXOS's
    typedJudgment(withGenesis(j), ScarabEvaluator.default) match
      case Right((_, events)) =>
        assertEquals(
          events.toList.map {
            case AlonzoUtxosEvent.SuccessfulPlutusScriptsEvent(ps) => s"passed ${ps.size}"
            case AlonzoUtxosEvent.AlonzoPpupToUtxosEvent(e)        => s"$e"
            case AlonzoUtxosEvent.FailedPlutusScriptsEvent(fs)     => s"failed ${fs.size}"
          },
          List(
            "passed 1",
            s"${ShelleyPpupEvent.PpupNewEpoch[Alonzo](right("epoch")(EpochNo.fromBigInt(11)))}"
          )
        )
      case other => fail(s"not accepted: $other")
  }

  test("UTXOS: an update whose value is written wide is recorded as written, also applied again") {
    val c = caseNamed("alonzo-pv6-v1-plain")
    val wide = withGenesis(withUpdate(c, 10, valid = true, maxTxSize = "1a00004000"))
    Vector(TxEnv.Validation.All, TxEnv.Validation.NonStatic).foreach { policy =>
      same(wide, policy) match
        case Outcome.Accepted(after, _) =>
          assertEquals(
            after.gov.curProposals.get(genesis(1)).map(_.fields(3)),
            Some(hex("1a00004000")),
            s"$policy"
          )
        case other => fail(s"$policy: not accepted: $other")
    }
  }

  /** `scriptsTransition` drops a collection's translation failures (`isNotBadTranslation`,
    * `Alonzo/Rules/Utxos.hs:175-187`, cardano-ledger f649f975), where the flat UTXOS records them:
    * a transaction whose spent input the UTxO set does not hold has a context Alonzo cannot build
    * (`TranslationLogicMissingInput`), which UTXOS records nothing for and evaluates no script for;
    * UTXO refuses the input (`BadInputsUTxO`), and a validity interval past the horizon is
    * `OutsideForecast`, so no transaction the reference accepts reaches this. A failure of the
    * collection that is not a translation's (a missing cost model) is recorded on both.
    */
  test("UTXOS: a translation failure of the collection is not recorded, the other failures are") {
    val c = caseNamed("alonzo-pv6-v1-plain")
    val base = v1Judged(c)
    val spent = c.spent.keySet
    assertEquals(spent.size, 1)
    val missing = base.copy(name = s"${c.name} without its input", known = base.known -- spent)
    val never: ScriptEvaluator[Id] = _ => fail("a script was evaluated")
    // the collection fails, with the one translation failure of the missing input alone
    val pp = PParams[Alonzo](right("pp")(AlonzoPParams.fromStored(missing.pp)))
    val stAnnTx = AlonzoStAnnTx.mkAlonzoStAnnTx[Alonzo](
      slot => Right(missing.posix(slot)),
      pp,
      right("utxo")(AlonzoEraUTxO.fromStored(missing.known)),
      Map.empty,
      missing.typed
    )
    assertEquals(
      EraUTxO.alonzo.plutusScriptsWithContextStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top),
      Left(
        cats.data.NonEmptyList.one(
          CollectError.BadTranslation[Alonzo](
            AlonzoContextError.TranslationLogicMissingInput[Alonzo](spent.head)
          )
        )
      )
    )
    typedJudgment(missing, never) match
      case Right((after, events)) =>
        assertEquals(events, Chain.empty)
        assertEquals(after, right("gov")(ShelleyGovStateRecord.fromStored[Alonzo](base.state.gov)))
      case other => fail(s"not passed: $other")
    // the flat UTXOS records the translation's failure (the Gap of the flat path)
    assertEquals(
      flatOutcome(missing, TxEnv.Validation.All),
      Outcome.Refused(
        List(
          Seen.Collected(List(Collect.BadTranslation(TranslationError.UnresolvedInput(spent.head))))
        )
      )
    )
    // with a PPUP failure beside it, PPUP's alone
    typedJudgment(withUpdate(c, 10, valid = true).copy(known = base.known -- spent), never) match
      case Left(failures) =>
        failures.toList match
          case List(AlonzoUtxosPredFailure.UpdateFailure(_)) => ()
          case other                                         => fail(s"not PPUP's alone: $other")
      case other => fail(s"not refused by PPUP: $other")
    // no cost model for Plutus V1: recorded on both, no script run
    val noModel =
      base.copy(name = s"${c.name} without a cost model", pp = base.pp.copy(costModels = Map.empty))
    assertEquals(
      same(noModel),
      Outcome.Refused(
        List(Seen.Collected(List(Collect.NoCostModel(xyz.kd5ujc.scarab.plutus.PlutusLanguage.V1))))
      )
    )
  }

  /** A translation failure only the node gives is recorded, as Babbage's rule records every one
    * (#1534): a transaction whose validity interval its context must translate, with no clock to
    * translate it, is refused with the clock's failure among the collection's and no script is
    * evaluated, on the era rule as on the flat one.
    */
  test("UTXOS: a translation failure only the node gives, a missing clock, is recorded (#1534)") {
    val c = caseNamed("alonzo-pv6-v1-plain")
    val ttl = withTtl(c)
    // with the clock, its script runs and passes
    same(ttl) match
      case Outcome.Accepted(_, (1, 0, 1)) => ()
      case other                          => fail(s"not accepted with its script passed: $other")
    // without it: the clock's failure recorded on both, and no script evaluated
    val unclocked = ttl.copy(name = s"${ttl.name} and no clock", clocked = false)
    val refused = Outcome.Refused(
      List(Seen.Collected(List(Collect.BadTranslation(TranslationError.NoEraHistory))))
    )
    assertEquals(same(unclocked), refused)
    val never: ScriptEvaluator[Id] = _ => fail("a script was evaluated")
    assertEquals(typedOutcome(unclocked, TxEnv.Validation.All, never), refused)
    // the classification: the reference's failures are dropped, the node's kept
    assertEquals(
      TranslationOrigin.ofAlonzo[Alonzo](
        AlonzoContextError.TranslationLogicMissingInput(c.spent.keys.head)
      ),
      TranslationOrigin.Reference
    )
    Vector(TranslationError.NoEraHistory, TranslationError.NoClockSample(1000)).foreach(cause =>
      assertEquals(
        TranslationOrigin.ofAlonzo[Alonzo](AlonzoContextError.TimeTranslationPastHorizon(cause)),
        TranslationOrigin.Node
      )
    )
  }

  /** `j` annotated with the clock `clock`, its parameters and governance state as the era reads
    * them.
    */
  private def annotated(j: Judged, clock: BigInt => Either[TranslationError, BigInt]) =
    val pp = PParams[Alonzo](right("pp")(AlonzoPParams.fromStored(j.pp)))
    val stAnnTx = AlonzoStAnnTx.mkAlonzoStAnnTx[Alonzo](
      clock,
      pp,
      right("utxo")(AlonzoEraUTxO.fromStored(j.known)),
      Map.empty,
      j.typed
    )
    (pp, stAnnTx, right("gov")(ShelleyGovStateRecord.fromStored[Alonzo](j.state.gov)))

  /** A V1-context case's body with a time to live at slot 1,000,000 (key 3), the case's slot being
    * 1000, so that its context translates the validity interval.
    */
  private def withTtl(c: PlutusCase): Judged =
    val base = v1Judged(c)
    val raw = c.tx.body.raw
    val head = raw.value.head & 0xff
    assert(head >= 0xa0 && head < 0xb7, s"${c.name}: a body of fewer than 23 keys")
    val body = Bytes(((head + 1).toByte +: raw.value.tail) ++ hex("031a000f4240").value)
    val witnesses = c.tx.witnesses.raw.getOrElse(fail(s"${c.name}: no witness bytes"))
    val parts = Block.Transaction(body, witnesses, c.tx.auxiliary, c.tx.isValid)
    base.copy(
      name = s"${c.name} with a time to live",
      flat = right("flat")(FlatTx.fromBlock(parts, ProtocolEra.Alonzo)),
      typed = right("typed")(AlonzoTx.decode(parts, StateLayout.Node11_1))
    )

  /** `ValidateNone` runs no labelled clause (`Extended.hs:709-712, 727-730`, cardano-ledger
    * f649f975), so the scripts are not evaluated even under the policy that validates every check;
    * PPUP still runs and changes the state.
    */
  test("UTXOS: a judgment of ValidateNone evaluates no script, and PPUP still records its update") {
    val never: ScriptEvaluator[Id] = _ => fail("a script was evaluated")
    Vector("alonzo-pv6-v1-plain", "alonzo-pv6-v1-plain-fail-valid-tag").foreach { name =>
      val j = withGenesis(withUpdate(caseNamed(name), 10, valid = true))
      val (pp, stAnnTx, gov) = annotated(j, slot => Right(j.posix(slot)))
      UtxosRun.runUnchecked(
        j.globals,
        UtxosEnv(slotNo(j.slot), pp, j.cert),
        stAnnTx,
        gov,
        never,
        TxEnv.Validation.All
      ) match
        case Right(Right((after, events))) =>
          assertEquals(after.sgsCurProposals.updates.keySet, Set(genesis(1)), name)
          assertEquals(
            events.toList,
            List(
              AlonzoUtxosEvent.AlonzoPpupToUtxosEvent[Alonzo](
                ShelleyPpupEvent.PpupNewEpoch(right("epoch")(EpochNo.fromBigInt(11)))
              )
            ),
            name
          )
        case other => fail(s"$name: not passed: $other")
    }
  }

  test(
    "UTXOS: a transaction flagged invalid runs no PPUP, and its collateral pays the fees with no return"
  ) {
    val c = caseNamed("alonzo-pv6-v1-plain-invalid-tag")
    val j = withUpdate(c, 10, valid = false)
    same(j) match
      case Outcome.Accepted(after, (0, 1, 1)) =>
        assertEquals(after.gov, j.state.gov)
        // its collateral taken whole as fees, and nothing put back
        val collateral = c.tx.body.collateral.toSet
        assert(collateral.nonEmpty)
        assertEquals(
          after.fees.value - j.state.fees.value,
          collateral.toVector.map(in => j.known(in).value.coin).sum
        )
        assertEquals(after.utxo.utxo.toMap, j.state.utxo.utxo.toMap -- collateral)
      case other => fail(s"not accepted with its script failed: $other")
  }

  test("UTXOS and the change of the state: a deregistration's refund, from the account's deposit") {
    // a recorded case with no certificate, its body given a deregistration of a registered account
    // whose credential is a key's (`[1, [0, keyhash]]`, key 4)
    val (r, credential) = refusals.iterator
      .filter(_.judged.flat.body.certificates.isEmpty)
      .flatMap(r =>
        r.judged.cert.dState.accounts.states.keysIterator.collectFirst {
          case xyz.kd5ujc.scarab.ledger.state.Credential.KeyHashObj(h) => r -> h
        }
      )
      .nextOption()
      .getOrElse(fail("no case with a key's account"))
    val raw = r.judged.flat.body.raw
    val head = raw.value.head & 0xff
    assert(head >= 0xa0 && head < 0xb7, s"${r.judged.name}: a body of fewer than 23 keys")
    val cert = hex("818201820058" + "1c" + credential.bytes.hex)
    val body = Bytes(((head + 1).toByte +: raw.value.tail) ++ (0x04.toByte +: cert.value))
    // UTXOS reads no witness: an empty witness set
    val parts = Block.Transaction(body, hex("a0"), None, isValid = true)
    val j = r.judged.copy(
      name = s"${r.judged.name} with a deregistration",
      flat = right("flat")(FlatTx.fromBlock(parts, ProtocolEra.Alonzo)),
      typed = right("typed")(AlonzoTx.decode(parts, StateLayout.Node11_1))
    )
    val deposit = r.judged.cert.dState.accounts.states
      .get(xyz.kd5ujc.scarab.ledger.state.Credential.KeyHashObj(credential))
      .map(_.deposit.toBigInt)
      .getOrElse(fail("no account"))
    assert(deposit > 0)
    same(j) match
      case Outcome.Accepted(after, _) =>
        assertEquals(after.deposited.value, r.judged.state.deposited.value - deposit)
      case other => fail(s"not accepted: $other")
  }
