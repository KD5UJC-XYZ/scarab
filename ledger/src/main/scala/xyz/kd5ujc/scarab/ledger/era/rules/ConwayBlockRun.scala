// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Id, Monad}
import cats.data.{Chain, EitherT, NonEmptyChain, NonEmptyList, Writer}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{
  Horizon,
  LedgerDefect,
  SystemStart,
  LedgerFailure,
  Phase2,
  PlutusStats,
  PosixClock,
  Redeemer,
  TranslationError,
  TxEnv,
  TxIn,
  TxOut as FlatTxOut,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.{
  Block,
  EraBlockBody,
  EraBlockHeader,
  EraTx,
  IsPhase2Valid,
  LanguageViews,
  PParams,
  Phase2Deferral,
  PlutusEvaluation,
  PlutusWithContext,
  PraosEraBlockHeader,
  Proposals,
  ProposalsReadError,
  ScriptResult,
  ScriptVerdictUnavailable,
  StAnnTx,
  Tx,
  TxLevel,
  TxOut,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, RuleT, TxDependencies, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{Conway, LedgerState, UTxOState}
import xyz.kd5ujc.scarab.plutus.{
  EvalFailure,
  EvalRequest,
  EvalResult,
  ScriptDecoder,
  ScriptEvaluator
}

/** What ends a run of Conway's rules on a block without a verdict: the defects the rules raise,
  * each typed as its rule raises it, GOV's proposals the node's record cannot give among them
  * ([[ProposalsRecord]]).
  */
enum ConwayRuleDefect:
  case Unavailable(cause: ScriptVerdictUnavailable)

  /** A script of the block's the evaluator gave no verdict for ([[ScriptVerdictUnavailable]]),
    * named as the flat rules name it, by its transaction and its redeemer ([[ConwayPhase2.named]]),
    * so that the node withholds the block as it withholds the flat rules'
    * (`BlockNoVerdict.Evaluation`).
    */
  case ScriptUnavailable(failure: LedgerFailure.Unavailable)
  case UtxoState(cause: UtxoStateDefect)
  case Ledger(cause: LedgerDefect)
  case Gov(cause: GovDefect)
  case ProposalsRead(cause: ProposalsReadError)

/** Conway's rules on a block, run as the node applies the block (`applyBlockEither` with
  * `ValidateAll`, cardano-ledger f649f975 `Shelley/API/Validation.hs`): BBODY in one judgment
  * ([[ConwayBbody.conwayBbody]]), every failure accumulated (`RuleT.Policy.Accumulate`), every
  * check of its transactions validated (`TxEnv.Validation.All`, the `ValidateAll` of
  * `applyBlockEither`); of the events, only what the node's phase-2 counters read is kept
  * ([[ScriptsTold]], [[ConwayPhase2.counted]]). The proposals are held beside the BBODY state, read
  * from the node's record in the governance state once, where a rule first reads them, and written
  * back once, after the block ([[Held]]), as Dijkstra's run holds its own ([[DijkstraBlockRun]]).
  *
  * The run is serial ([[bbody]]), the executable reference, or a body program ([[program]]) whose
  * scripts the node can evaluate in bounded parallel workers after an ordered preparation (ADR
  * 0018): the same rules run once, in order, deferring each transaction's scripts where UTXOS
  * collects them ([[Phase2Deferral]]), then the plan lands their verdicts in order ([[Plan]]), and
  * its result is the serial run's. The node applies Conway blocks with it (`MirrorRules`, #85).
  */
object ConwayBlockRun:
  /** The proposals of a run: not yet read from the node's record, or the era's, as the rules left
    * them.
    */
  private enum Held:
    case Unread
    case Read(proposals: Proposals[Conway])

  /** The scripts of the transaction a run observes ([[Observing]]): none between two transactions,
    * or the transaction's, located, with the evaluations of those run so far; or none ever again,
    * once a transaction whose scripts UTXOS could not collect was observed ([[uncollecting]]),
    * after which the judgment is failing, and so no script runs (`whenFailureFree`).
    */
  private enum Observed:
    case Between
    case In(located: ConwayPhase2.Located, done: Vector[Phase2.Evaluation])
    case Ended

  /** What of a run's events its result reads, in their order: those the counters read
    * ([[ScriptsTold]]), and, in a block the node prepares, the place of each transaction whose
    * scripts it deferred, where the events of its scripts land once the plan has their verdict.
    */
  private enum Told:
    case Event(told: ScriptsTold)
    case At(job: Deferred)

  /** The state of a run: BBODY's, the proposals it holds, the stored outputs it read into the era
    * (`read`, each with the stored output it read, [[StoredOutputs]]), what of its events its
    * result reads ([[Told]]), the scripts it observes, how many scripts of the transaction whose
    * scripts run now ran so far (`ran`), and the results it is answered with (`answers`): a
    * transaction's UTXOS runs its scripts in the order its annotation collects them
    * (`evalPlutusScripts`, `Alonzo/Plutus/Evaluate.hs:188-207`, cardano-ledger f649f975) and then
    * tells them ([[ScriptsTold]]), which ends them; one whose scripts it does not tell records a
    * failure, after which no script runs (`whenFailureFree`). So the `ran` th script of the
    * transaction is the one running, which two scripts with the same request tell apart.
    */
  private final case class S(
      bbody: ShelleyBbodyState[Conway],
      held: Held,
      read: Map[TxIn, (FlatTxOut, TxOut[Conway])],
      told: Chain[Told],
      observed: Observed,
      ran: Int,
      answers: List[(EvalRequest, EvalResult)]
  )

  /** How a run evaluates its scripts: where UTXOS collects them, each answered in turn by `answers`
    * where its request is the one answered (a prepared block's results, which the evaluator, a
    * function of the request, gives again) and by the evaluator otherwise; or, a block prepared,
    * deferred into the plan's recorder ([[Recorded]]).
    */
  private enum Mode:
    case Evaluating(answers: List[(EvalRequest, EvalResult)])
    case Preparing(recorder: Phase2.Recorder[Recorded])

  /** What a prepared block's run records, in the order the rules reach it: the scripts of a
    * transaction it deferred ([[Deferred]]), and, the block observed, the observation of a
    * transaction whose scripts UTXOS could not collect ([[uncollecting]]), which the plan tells at
    * its place among the deferred scripts' observations.
    */
  private[ConwayBlockRun] enum Recorded:
    case Scripts(job: Deferred)
    case Uncollected(observation: Phase2.Observation)

  /** The node's phase-2 observer (`--plutus-cases`) of a run, as the flat rules call it
    * (`EraBlock.Context.observer`, `Phase2.Observation`), with the clock its observations hold:
    * told of each transaction whose scripts the run evaluates ([[observed]]) and of one whose
    * scripts UTXOS could not collect ([[uncollecting]]), as the flat rules tell it of each.
    */
  final case class Observing(observer: Phase2.Observation => Unit, clock: Option[PosixClock])

  private type F[A] = RuleT[BbodyPredFailure[Conway], ConwayRuleDefect, S, A]
  import RuleT.given

  private given Stateful[F, ShelleyBbodyState[Conway]] =
    summon[Stateful[F, S]].focus(
      Lens[S, ShelleyBbodyState[Conway]](_.bbody)(b => _.copy(bbody = b))
    )

  // the events of a block applied by the node are not kept (`ComputeLedgerEvents` off) but for
  // what the node's phase-2 counters read of them
  private given Tell[F, Chain[BbodyEvent[Conway]]] = new Tell[F, Chain[BbodyEvent[Conway]]]:
    val functor: Functor[F] = summon[Monad[F]]
    def tell(events: Chain[BbodyEvent[Conway]]): F[Unit] =
      summon[Stateful[F, S]].modify(s =>
        val now = events.flatMap(e => Chain.fromOption(ScriptsTold.of(e)))
        val ended = now.exists {
          case ScriptsTold.Passed(_) | ScriptsTold.Failed(_) => true
          case ScriptsTold.Applied                           => false
        }
        s.copy(told = s.told ++ now.map(Told.Event(_)), ran = if ended then 0 else s.ran)
      )

  /** What a block the rules admitted comes to: the BBODY state after it, and the node's phase-2
    * counters of its scripts, from zero ([[ConwayPhase2.counted]]).
    */
  final case class Applied(state: ShelleyBbodyState[Conway], plutus: PlutusStats)

  /** Why the rules refused a block: its failures, every one the rules recorded, or those recorded
    * before a defect of this implementation stopped the run.
    */
  enum Refusal:
    def failures: NonEmptyChain[BbodyPredFailure[Conway]]

    /** The rules ran to the end of the block and recorded `failures`. */
    case Failed(failures: NonEmptyChain[BbodyPredFailure[Conway]])

    /** The rules recorded `failures`, then raised `defect`, which ended the run (#85): the block is
      * refused on those failures, as the reference, which has no such defect, refuses it. The
      * failures end where the defect stopped the run; what the rules would have recorded after it
      * is not known. The defect is this implementation's, kept to be logged, not a failure of the
      * block. A defect with nothing recorded before it leaves no verdict ([[Result]]'s `Left`). The
      * node refuses the block as any invalid block (`BlockFailure.ConwayBbody`), the peer that
      * served it treated as for any invalid block, and the defect is in the refusal's reason.
      */
    case Stopped(failures: NonEmptyChain[BbodyPredFailure[Conway]], defect: ConwayRuleDefect)

  /** What a run on a block comes to: a defect with no failure recorded before it, the refusal
    * ([[Refusal]]), or the block admitted.
    */
  type Result = Either[ConwayRuleDefect, Either[Refusal, Applied]]

  /** A run before its counters: the BBODY state after an admitted block with its events. */
  private type Ran =
    Either[ConwayRuleDefect, Either[Refusal, (ShelleyBbodyState[Conway], Chain[Told])]]

  private given Raise[F, UtxoStateDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.UtxoState(_))
  private given Raise[F, LedgerDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Ledger(_))
  private given Raise[F, GovDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Gov(_))
  private given Raise[F, ProposalsReadError] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.ProposalsRead(_))

  /** One more script of the transaction whose scripts run now ran ([[S]]). */
  private val oneRan: F[Unit] = summon[Stateful[F, S]].modify(s => s.copy(ran = s.ran + 1))

  /** The result of the script whose request is `request`: the next of the run's answers if it
    * answers that request, the evaluator's otherwise ([[Mode.Evaluating]]).
    */
  private def answered(evaluator: ScriptEvaluator[Id])(request: EvalRequest): F[EvalResult] =
    val state = summon[Stateful[F, S]]
    state.get.flatMap { s =>
      s.answers match
        case (asked, result) :: rest if asked == request =>
          state.set(s.copy(answers = rest)).as(result)
        case _ => Monad[F].unit.map(_ => evaluator.evaluate(request))
    }

  private def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
    request => oneRan *> answered(evaluator)(request)

  /** The redeemer of the script at `place` among the scripts of `located`, if its request is
    * `request`.
    */
  private def redeemerOf(
      located: ConwayPhase2.Located,
      place: Int,
      request: EvalRequest
  ): Option[Redeemer] =
    located.scripts.lift(place).collect {
      case (redeemer, pwc) if PlutusWithContext.evalRequest(pwc) == request => redeemer
    }

  /** The node's phase-2 observation of the transaction `located`, in the ledger state `ls`, whose
    * scripts came to `outcome`: with the parameters as the node records them, the block's slot and
    * the outputs its inputs and reference inputs spend and read.
    */
  private def observation(
      observing: Observing,
      located: ConwayPhase2.Located,
      ls: LedgerState[Conway],
      slot: BigInt,
      outcome: Phase2.Outcome
  ): Option[Phase2.Observation] =
    ConwayPhase2
      .flatTx(located.tx)
      .map(tx =>
        Phase2.Observation(
          tx,
          ls.utxo.gov.curPParams,
          slot,
          located.spent,
          located.references,
          observing.clock,
          Right(outcome)
        )
      )

  /** `evaluator` with each script it runs of the block of transactions `txs` observed by
    * `observing`, as the flat rules observe a transaction's (`TxChecks.phase2`): the script's
    * transaction located in the ledger state the rules run it in ([[ConwayPhase2.locate]]), its
    * script the one at its place among them ([[S]]`.ran`, its request the one it is asked to run),
    * its evaluations held until the last of its scripts or the one the evaluator gives no verdict
    * for, then its observation told ([[observation]]). A script of no transaction of the block,
    * which the rules never run, is not observed.
    */
  private def observed(
      evaluator: ScriptEvaluator[Id],
      observing: Observing,
      txs: Vector[Tx[TxLevel.Top, Conway]],
      pp: PParams[Conway],
      slot: BigInt,
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  ): ScriptEvaluator[F] =
    val state = summon[Stateful[F, S]]
    def told(located: ConwayPhase2.Located, s: S, outcome: Phase2.Outcome): F[Unit] =
      Monad[F].unit.map(_ =>
        observation(observing, located, s.bbody.ls, slot, outcome).foreach(observing.observer)
      )
    request =>
      (state.get <* oneRan).flatMap { s =>
        answered(evaluator)(request).flatMap { result =>
          def fresh: Option[(ConwayPhase2.Located, Vector[Phase2.Evaluation], Redeemer)] =
            ConwayPhase2
              .locate(txs, s.bbody.ls, pp, slotToPOSIXTime)(pwc =>
                PlutusWithContext.evalRequest(pwc) == request
              )
              .flatMap(located =>
                redeemerOf(located, s.ran, request).map(r => (located, Vector.empty, r))
              )
          val current = s.observed match
            case Observed.In(located, done) =>
              redeemerOf(located, s.ran, request).map(r => (located, done, r)).orElse(fresh)
            case Observed.Between => fresh
            case Observed.Ended   => None
          current
            .fold(Monad[F].pure(result)) { (located, done, redeemer) =>
              val between = state.modify(_.copy(observed = Observed.Between))
              result match
                case EvalResult.Failure(reason: EvalFailure.Unsupported, _, _) =>
                  told(located, s, ConwayPhase2.outcome(done, Some(redeemer -> reason)))
                    *> between.as(result)
                case EvalResult.Success(_, _) | EvalResult.Failure(_, _, _) =>
                  val now = done :+ Phase2.Evaluation(redeemer, request, result)
                  if now.sizeIs == located.scripts.size then
                    told(located, s, ConwayPhase2.outcome(now, None)) *> between.as(result)
                  else state.modify(_.copy(observed = Observed.In(located, now))).as(result)
            }
        }
      }

  /** UTXOS's notice of a transaction `tx` whose scripts it could not collect
    * ([[Phase2Deferral.uncollected]]), observed by `observing` as the flat rules observe it
    * (`TxChecks.phase2`, [[ConwayPhase2.uncollected]]), in the ledger state the rules judge it in,
    * and told by `tell`: once, where the run observes no transaction's scripts ([[Observed]]), and
    * only if nothing failed so far in the judgment, which the flat rules evaluate a transaction's
    * scripts under (`whenFailureFree`) and observe it only then. Its failures recorded after it,
    * the judgment is failing, so no script runs after it, and the run observes nothing more
    * ([[Observed.Ended]]).
    */
  private def uncollecting(
      observing: Option[Observing],
      slot: BigInt,
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      tell: Phase2.Observation => Unit
  )(tx: Tx[TxLevel.Top, Conway]): F[Unit] =
    val state = summon[Stateful[F, S]]
    observing.fold(Monad[F].unit) { o =>
      (summon[Judge[F, BbodyPredFailure[Conway]]].failing, state.get).tupled.flatMap {
        (failing, s) =>
          s.observed match
            case Observed.Between if !failing =>
              Monad[F].unit.map(_ =>
                ConwayPhase2
                  .uncollected(tx, s.bbody.ls, slot, slotToPOSIXTime, o.clock)
                  .foreach(tell)
              ) *> state.set(s.copy(observed = Observed.Ended))
            case Observed.Between | Observed.In(_, _) | Observed.Ended => Monad[F].unit
      }
    }

  /** The scripts of a transaction a prepared block deferred ([[Mode.Preparing]]): its place among
    * the block's transactions, its flag, its scripts in the order UTXOS runs them, and, the block
    * observed, how its observation is made once they ran, from the ledger state the rules deferred
    * them in, as [[observed]] makes it then. Identity is intentional, as the flat rules' jobs'
    * (`Phase2.Job`): completed values cannot be reused for another block.
    */
  final class Deferred private[ConwayBlockRun] (
      val index: Int,
      private[ConwayBlockRun] val flag: IsPhase2Valid,
      private[ConwayBlockRun] val scripts: NonEmptyList[PlutusWithContext],
      private[ConwayBlockRun] val observation: Option[Evaluated => Option[Phase2.Observation]]
  ):
    private[ConwayBlockRun] def requests: List[EvalRequest] =
      scripts.toList.map(PlutusWithContext.evalRequest)

  /** A deferred transaction's scripts run, as `evalPlutusScripts` runs them: each one's result, in
    * order, to the first the evaluator gave no verdict for, and what they come to.
    */
  private[ConwayBlockRun] final case class Evaluated(
      results: Vector[EvalResult],
      verdict: Either[ScriptVerdictUnavailable, ScriptResult]
  )

  /** The scripts of `job` run by `evaluator` with the reference's `evalPlutusScripts`, which stops
    * at the first with no verdict, every result kept.
    */
  private def evaluated(
      job: Deferred,
      evaluator: ScriptEvaluator[Id]
  ): Either[Phase2.Defect, Evaluated] =
    type W[A] = Writer[Vector[EvalResult], A]
    type G[A] = EitherT[W, ScriptVerdictUnavailable, A]
    given ScriptEvaluator[G] = request =>
      val result = evaluator.evaluate(request)
      EitherT.liftF[W, ScriptVerdictUnavailable, EvalResult](Writer(Vector(result), result))
    given Raise[G, ScriptVerdictUnavailable] = new Raise[G, ScriptVerdictUnavailable]:
      val functor: Functor[G] = summon[Monad[G]]
      def raise[E2 <: ScriptVerdictUnavailable, A](cause: E2): G[A] =
        EitherT.leftT[W, A](cause)
    Either
      .catchNonFatal {
        val (results, verdict) =
          PlutusEvaluation.evalPlutusScripts[G](job.scripts.toList).value.run
        Evaluated(results, verdict)
      }
      .leftMap(Phase2.Defect(_))

  /** The verdict of `job` 's scripts if it is the one its flag announces: they pass, flagged valid,
    * or fail, flagged invalid (`expectScriptsToPass` and `babbageEvalScriptsTxInvalid`,
    * `Babbage/Rules/Utxos.hs:130-155` and `:192-222`, cardano-ledger f649f975).
    */
  private def agreed(job: Deferred, evaluated: Evaluated): Option[ScriptResult] =
    (job.flag, evaluated.verdict) match
      case (IsPhase2Valid.Phase2Valid, Right(passes @ ScriptResult.Passes(_)))    => Some(passes)
      case (IsPhase2Valid.Phase2Invalid, Right(fails @ ScriptResult.Fails(_, _))) => Some(fails)
      case (_, Right(ScriptResult.Passes(_)) | Right(ScriptResult.Fails(_, _)) | Left(_)) => None

  /** What UTXOS tells of scripts whose verdict is the one their flag announces, as the counters
    * read it ([[ScriptsTold]]): those that passed, if any did, then, failing, those that failed
    * (`expectScriptsToPass` and `babbageEvalScriptsTxInvalid`).
    */
  private def toldOf(verdict: ScriptResult): Chain[ScriptsTold] =
    def passed(ps: List[PlutusWithContext]): Chain[ScriptsTold] =
      Chain.fromOption(Option.when(ps.nonEmpty)(ScriptsTold.Passed(ps.size)))
    verdict match
      case ScriptResult.Passes(ps)    => passed(ps)
      case ScriptResult.Fails(ps, fs) => passed(ps) :+ ScriptsTold.Failed(fs.size)

  /** A run's result, its counters from its events, each deferred transaction's scripts told where
    * they were deferred, by the verdict `landed` gives them.
    */
  private def settled(
      txs: Vector[Tx[TxLevel.Top, Conway]],
      ran: Ran,
      landed: Map[Deferred, ScriptResult]
  ): Result =
    ran.map(_.map { (state, told) =>
      Applied(
        state,
        ConwayPhase2.counted(
          txs,
          told.flatMap {
            case Told.Event(event) => Chain.one(event)
            case Told.At(job)      => landed.get(job).fold(Chain.empty[ScriptsTold])(toldOf)
          }
        )
      )
    })

  /** Where a block's preparation defers the scripts of a transaction of `txs`: recorded into
    * `recorder` and at their place among the run's events, none run, so that the rules go on as its
    * flag announces; observed by `observing`, with how its observation is made. A transaction the
    * block does not hold, which the rules never run the scripts of, has them run there. A
    * transaction whose scripts UTXOS could not collect has its observation recorded into `recorder`
    * ([[uncollecting]]), for the plan to tell in order.
    */
  private def deferring(
      recorder: Phase2.Recorder[Recorded],
      txs: Vector[Tx[TxLevel.Top, Conway]],
      observing: Option[Observing],
      pp: PParams[Conway],
      slot: BigInt,
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  ): Phase2Deferral[F, Conway] =
    val state = summon[Stateful[F, S]]
    val places = ConwayPhase2.places(txs)
    new Phase2Deferral[F, Conway]:
      def evalScripts(
          stAnnTx: StAnnTx[TxLevel.Top, Conway],
          scripts: NonEmptyList[PlutusWithContext]
      )(now: F[Unit]): F[Unit] =
        val tx = EraTx.conway.txStAnnTx[TxLevel.Top](stAnnTx)(using EraTx.conway.bodyOf.top)
        places.get(ConwayPhase2.txId(tx)).fold(now) { index =>
          state.get.flatMap { s =>
            val ls = s.bbody.ls
            val job = Deferred(
              index,
              EraTx.conway.isValid(tx),
              scripts,
              observing
                .map(o => deferredObservation(o, txs, ls, pp, slot, slotToPOSIXTime, scripts))
            )
            Monad[F].unit.map(_ => recorder.append(Recorded.Scripts(job))) *>
              state.set(s.copy(told = s.told :+ Told.At(job)))
          }
        }
      def uncollected(tx: Tx[TxLevel.Top, Conway]): F[Unit] =
        uncollecting(
          observing,
          slot,
          slotToPOSIXTime,
          o => recorder.append(Recorded.Uncollected(o))
        )(
          tx
        )

  /** How the observation of deferred scripts is made once they ran, as [[observed]] makes it: the
    * transaction located in `ls` by its first script, each result named by its script's place.
    */
  private def deferredObservation(
      observing: Observing,
      txs: Vector[Tx[TxLevel.Top, Conway]],
      ls: LedgerState[Conway],
      pp: PParams[Conway],
      slot: BigInt,
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      scripts: NonEmptyList[PlutusWithContext]
  ): Evaluated => Option[Phase2.Observation] = evaluated =>
    val first = PlutusWithContext.evalRequest(scripts.head)
    ConwayPhase2
      .locate(txs, ls, pp, slotToPOSIXTime)(pwc => PlutusWithContext.evalRequest(pwc) == first)
      .flatMap { located =>
        scripts.toList
          .map(PlutusWithContext.evalRequest)
          .zip(evaluated.results)
          .zipWithIndex
          .traverse { case ((request, result), place) =>
            redeemerOf(located, place, request).map(Phase2.Evaluation(_, request, result))
          }
          .flatMap { evaluations =>
            val all = evaluations.toVector
            all.lastOption
              .collect {
                case Phase2.Evaluation(
                      r,
                      _,
                      EvalResult.Failure(reason: EvalFailure.Unsupported, _, _)
                    ) =>
                  ConwayPhase2.outcome(all.dropRight(1), Some(r -> reason))
              }
              .orElse(
                Option.when(all.sizeIs == located.scripts.size)(ConwayPhase2.outcome(all, None))
              )
              .flatMap(observation(observing, located, ls, slot, _))
          }
      }

  /** The proposals the rules judge: read from the ledger state's record at the first read
    * ([[ProposalsRecord.read]]; one the era cannot read is raised, [[ConwayRuleDefect]]), then
    * held, so that a block reads them once and a block whose rules never reach GOV not at all.
    */
  private given Stateful[F, Proposals[Conway]] = new Stateful[F, Proposals[Conway]]:
    val monad: Monad[F] = summon[Monad[F]]
    def get: F[Proposals[Conway]] = summon[Stateful[F, S]].get.flatMap { s =>
      s.held match
        case Held.Read(proposals) => Monad[F].pure(proposals)
        case Held.Unread =>
          ProposalsRecord.conway
            .read(s.bbody.ls.utxo.gov.proposals)
            .fold(
              summon[Raise[F, ProposalsReadError]].raise[ProposalsReadError, Proposals[Conway]],
              proposals => set(proposals).as(proposals)
            )
    }
    def set(proposals: Proposals[Conway]): F[Unit] =
      summon[Stateful[F, S]].modify(_.copy(held = Held.Read(proposals)))

  /** The stored outputs the rules read, each read into the era once in a run: a stored output the
    * run read before is given as it was read, where the set holds the very output it read; the
    * others are read ([[ShelleyUtxo.readStored]]), the first, in input order, the era does not read
    * raised, which is the first of all of them, since those read before were read. A block's rules
    * read a transaction's inputs at BBODY, LEDGERS, LEDGER, UTXOW, UTXO and the set's update, each
    * read the same (#85).
    */
  private given StoredOutputs[F, Conway] with
    def read(stored: Map[TxIn, FlatTxOut]): F[UTxO[Conway]] = kept(stored)

    // the set's entries at the keys looked up as they are sorted into those read and the others,
    // not first gathered into a map of their own; a view, so each run of the step looks them up
    // again
    override def readAt(
        utxo: xyz.kd5ujc.scarab.ledger.Utxo,
        keys: Iterable[TxIn]
    ): F[UTxO[Conway]] =
      kept(keys.view.flatMap(in => utxo.get(in).map(in -> _)))

    private def kept(stored: Iterable[(TxIn, FlatTxOut)]): F[UTxO[Conway]] =
      val state = summon[Stateful[F, S]]
      state.get.flatMap { s =>
        val (known, unread) =
          stored.foldLeft((Map.empty[TxIn, TxOut[Conway]], Map.empty[TxIn, FlatTxOut])) {
            case ((known, unread), (in, out)) =>
              s.read.get(in) match
                case Some((held, read)) if held eq out => (known.updated(in, read), unread)
                case _                                 => (known, unread.updated(in, out))
          }
        if unread.isEmpty then Monad[F].pure(UTxO[Conway](known))
        else
          ShelleyUtxo.readStored[F, Conway](unread).flatMap { fresh =>
            val kept =
              unread.flatMap((in, out) => fresh.unUTxO.get(in).map(read => in -> (out, read)))
            state
              .modify(now => now.copy(read = now.read ++ kept))
              .as(UTxO[Conway](fresh.unUTxO ++ known))
          }
      }

  /** The BBODY state with the proposals the run holds written back into the node's record
    * ([[ProposalsRecord.write]]), the record as it was if the rules never read them.
    */
  private def written(after: S): ShelleyBbodyState[Conway] =
    val bbody = after.bbody
    after.held match
      case Held.Unread => bbody
      case Held.Read(proposals) =>
        ShelleyBbodyState
          .ls[Conway]
          .andThen(LedgerState.utxo[Conway])
          .andThen(UTxOState.gov[Conway])
          .modify(_.copy(proposals = ProposalsRecord.conway.write(proposals)))(bbody)

  /** BBODY on `block` from `state`: a defect, the refusal ([[Refusal]]), or the BBODY state after
    * with the block's phase-2 counters ([[Applied]]). Every failure is accumulated, and a defect
    * raised after a failure was recorded refuses the block on the failures recorded before it
    * ([[Refusal.Stopped]]); one raised before any leaves no verdict. The context of its
    * transactions' rules is made here from `globals`, as the reference's rules read it from theirs:
    * the network (`networkId`, the Shelley genesis's) and the build's injection of Shelley's UTXO
    * failures ([[ConwayUtxo.injectShelleyFailure]]), with the system start and the epoch
    * information the caller gives (`systemStart`, `epochInfo`), and every check validated, so that
    * no caller runs the block's rules under another policy or on another network. A script the
    * evaluator gives no verdict for is named by its transaction and redeemer where the rules met it
    * ([[ConwayRuleDefect.ScriptUnavailable]]).
    */
  def bbody[H](
      globals: Globals,
      env: BbodyEnv[Conway],
      block: Block[H, Conway],
      systemStart: SystemStart,
      epochInfo: Horizon,
      state: ShelleyBbodyState[Conway],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      observing: Option[Observing] = None
  )(using
      h: EraBlockHeader[H, Conway],
      pv: PraosEraBlockHeader[H, Conway]
  ): Result =
    program(
      globals,
      env,
      block,
      systemStart,
      epochInfo,
      state,
      evaluator,
      verdicts,
      decoder,
      slotToPOSIXTime,
      observing
    ).serial

  /** [[bbody]] as a body program ([[Body]]): run serially, or prepared for the node's bounded
    * parallel evaluation of its scripts (ADR 0018), each to the same result.
    */
  def program[H](
      globals: Globals,
      env: BbodyEnv[Conway],
      block: Block[H, Conway],
      systemStart: SystemStart,
      epochInfo: Horizon,
      state: ShelleyBbodyState[Conway],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      observing: Option[Observing] = None
  )(using
      h: EraBlockHeader[H, Conway],
      pv: PraosEraBlockHeader[H, Conway]
  ): Body =
    val txs = EraBlockBody.conway.txSeqBlockBody(block.blockBody)
    val slot = h.slotNoBlockHeader(block).toBigInt
    def run(mode: Mode): Ran =
      given ScriptEvaluator[F] = mode match
        case Mode.Evaluating(_) =>
          observing.fold(lifted(evaluator))(
            observed(evaluator, _, txs, env.bbodyPp, slot, slotToPOSIXTime)
          )
        case Mode.Preparing(_) => lifted(evaluator)
      given Phase2Deferral[F, Conway] = mode match
        case Mode.Evaluating(_) =>
          new Phase2Deferral[F, Conway]:
            def evalScripts(
                stAnnTx: StAnnTx[TxLevel.Top, Conway],
                scripts: NonEmptyList[PlutusWithContext]
            )(now: F[Unit]): F[Unit] = now
            def uncollected(tx: Tx[TxLevel.Top, Conway]): F[Unit] =
              uncollecting(observing, slot, slotToPOSIXTime, o => observing.foreach(_.observer(o)))(
                tx
              )
        case Mode.Preparing(recorder) =>
          deferring(recorder, txs, observing, env.bbodyPp, slot, slotToPOSIXTime)
      // a script with no verdict, named by its transaction and redeemer in the ledger state the
      // rules judged it in, the state at the raise, at its place among its transaction's scripts:
      // it ran, and was counted, so `ran` less one ran before it
      given Raise[F, ScriptVerdictUnavailable] = new Raise[F, ScriptVerdictUnavailable]:
        val functor: Functor[F] = summon[Monad[F]]
        def raise[E2 <: ScriptVerdictUnavailable, A](cause: E2): F[A] =
          summon[Stateful[F, S]].get.flatMap(s =>
            summon[Raise[F, ConwayRuleDefect]].raise[ConwayRuleDefect, A](
              ConwayPhase2
                .named(
                  txs,
                  s.bbody.ls,
                  env.bbodyPp,
                  slotToPOSIXTime,
                  cause,
                  s.ran - 1
                )
                .fold(ConwayRuleDefect.Unavailable(cause))(ConwayRuleDefect.ScriptUnavailable(_))
            )
          )
      val netId = globals.networkId
      val context = UtxoContext[Conway](
        netId,
        systemStart,
        epochInfo,
        TxEnv.Validation.All,
        ConwayUtxo.injectShelleyFailure(globals.ledger.referenceBuild),
        LanguageViews.of(env.bbodyPp)
      )
      val answers = mode match
        case Mode.Evaluating(answers) => answers
        case Mode.Preparing(_)        => Nil
      ConwayBbody
        .conwayBbody[F, H](env, block, context, verdicts, decoder, slotToPOSIXTime)
        .judge(
          RuleT.Policy.Accumulate,
          globals,
          S(state, Held.Unread, Map.empty, Chain.empty, Observed.Between, 0, answers)
        ) match
        case RuleT.Outcome.Raised(defect) => Left(defect)
        // an established refusal wins over a later defect of ours (#85)
        case RuleT.Outcome.Halted(failures, defect) =>
          Right(Left(Refusal.Stopped(failures, defect)))
        case RuleT.Outcome.Refused(failures) => Right(Left(Refusal.Failed(failures)))
        case RuleT.Outcome.Passed(after, _)  => Right(Right(written(after) -> after.told))
    Body(txs, evaluator, observing, run)

  /** Conway's run on a block as a body program (`Phase2.Program`): [[serial]] is the run with every
    * script evaluated where UTXOS collects it; [[prepare]] runs the same rules once, in order, with
    * the scripts of each transaction deferred, and its [[Plan]] lands their verdicts.
    */
  final class Body private[ConwayBlockRun] (
      txs: Vector[Tx[TxLevel.Top, Conway]],
      evaluator: ScriptEvaluator[Id],
      observing: Option[Observing],
      run: Mode => Ran
  ) extends Phase2.Program[Result]:
    def serial: Result = settled(txs, run(Mode.Evaluating(Nil)), Map.empty)
    def hasScripts: Boolean = txs.exists(ConwayPhase2.redeeming)
    def worthwhile: Boolean = txs.count(ConwayPhase2.redeeming) > 1
    def prepare: Plan =
      val (tentative, recorded) =
        Phase2.captured[Recorded, Ran](recorder => run(Mode.Preparing(recorder)))
      Plan(
        tentative,
        recorded,
        txs,
        evaluator,
        observing,
        answers => settled(txs, run(Mode.Evaluating(answers)), Map.empty)
      )

  /** Where the landing of a plan's deferred scripts stops: at a raw defect, or at the first
    * transaction whose scripts' verdict is not the one its flag announces, or gives none.
    */
  private enum Stop:
    case Defect(defect: Phase2.Defect, landed: Vector[(Deferred, Evaluated)])
    case Disagreed(landed: Vector[(Deferred, Evaluated)])

  /** A prepared block: its deferred scripts ([[jobs]]) in dependency batches, and the only route to
    * its result, which lands each job's verdict in order:
    *   - while every verdict is the one its flag announces, the preparation went as the serial run
    *     goes, so its result is the serial run's: its counters from each job's verdict told where
    *     it was deferred, its observations made in the order the preparation recorded them
    *     ([[Recorded]]), a transaction whose scripts UTXOS could not collect among them, its
    *     refusal, with the defect that stopped it if one did ([[Refusal.Stopped]]), or the raw
    *     defect it ended in;
    *   - a raw defect a job's scripts ended in is the serial run's, which reaches them: what was
    *     recorded before it observed, the defect raised as it is, never evaluated again;
    *   - at the first verdict that is not the one its flag announces, or a script with no verdict,
    *     the serial run's result differs from the preparation's (the failure recorded, every later
    *     script left to `whenFailureFree`, the script named in the state of the raise): the serial
    *     run itself is the result, each script answered by the jobs' results to that one, in order,
    *     which the evaluator, a function of the request, gives again.
    *
    * Missing or foreign completed values are evaluated directly, never treated as a success.
    */
  final class Plan private[ConwayBlockRun] (
      tentative: Either[Phase2.Defect, Ran],
      recorded: Vector[Recorded],
      txs: Vector[Tx[TxLevel.Top, Conway]],
      evaluator: ScriptEvaluator[Id],
      observing: Option[Observing],
      replay: List[(EvalRequest, EvalResult)] => Result
  ) extends Phase2.Plan[Result]:
    type Job = Deferred

    /** The deferred scripts, in the order the preparation deferred them. */
    val jobs: Vector[Deferred] = recorded.collect { case Recorded.Scripts(job) => job }

    /** A worker's value: a deferred transaction's scripts run by this plan's evaluator, or the raw
      * defect that ended them. It is this plan's own type: a value another plan executed, with its
      * own evaluator, does not type-check here.
      */
    final class Completed private[Plan] (
        private[Plan] val job: Deferred,
        private[Plan] val outcome: Either[Phase2.Defect, Evaluated]
    )

    def batches: Vector[Vector[Deferred]] =
      TxDependencies.scheduled(txs.map(ConwayPhase2.footprint), jobs)(_.index)
    def execute(job: Deferred): Completed = Completed(job, evaluated(job, evaluator))

    def resolve(completed: Vector[Completed]): Either[Phase2.Defect, Result] =
      val values = completed.iterator.map(c => c.job -> c.outcome).toMap
      // what the preparation recorded, observed in its order up to the first job not landed
      def observe(landed: Vector[(Deferred, Evaluated)]): Unit =
        observing.foreach { o =>
          val evaluated = landed.toMap
          recorded.iterator
            .takeWhile {
              case Recorded.Scripts(job)   => evaluated.contains(job)
              case Recorded.Uncollected(_) => true
            }
            .flatMap {
              case Recorded.Scripts(job) =>
                evaluated.get(job).flatMap(e => job.observation.flatMap(_(e)))
              case Recorded.Uncollected(observation) => Some(observation)
            }
            .foreach(o.observer)
        }
      type Landing[A] = Either[Stop, A]
      jobs
        .foldLeftM[Landing, Vector[(Deferred, Evaluated, ScriptResult)]](Vector.empty) {
          (landed, job) =>
            val before = landed.map((j, e, _) => j -> e)
            values.getOrElse(job, evaluated(job, evaluator)) match
              case Left(defect) => Left(Stop.Defect(defect, before))
              case Right(e) =>
                agreed(job, e)
                  .toRight(Stop.Disagreed(before :+ (job -> e)))
                  .map(v => landed :+ (job, e, v))
        } match
        case Left(Stop.Defect(defect, landed)) =>
          observe(landed)
          Left(defect)
        case Left(Stop.Disagreed(landed)) =>
          Either
            .catchNonFatal(replay(landed.toList.flatMap((job, e) => job.requests.zip(e.results))))
            .leftMap(Phase2.Defect(_))
        case Right(landed) =>
          observe(landed.map((j, e, _) => j -> e))
          tentative.map(settled(txs, _, landed.iterator.map((j, _, v) => j -> v).toMap))
