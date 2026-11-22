// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
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
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.{
  Block,
  EraBlockBody,
  EraBlockHeader,
  PParams,
  PlutusWithContext,
  PraosEraBlockHeader,
  Proposals,
  ProposalsReadError,
  ProposalsWriteError,
  ScriptVerdictUnavailable,
  Tx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{Conway, LedgerState, UTxOState}
import xyz.kd5ujc.scarab.plutus.{
  EvalFailure,
  EvalRequest,
  EvalResult,
  ScriptDecoder,
  ScriptEvaluator
}

/** What ends a run of Conway's rules on a block without a verdict: the defects the rules raise,
  * each typed as its rule raises it, GOV's proposals the node's record cannot give or hold among
  * them ([[ProposalsRecord]]).
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
  case ProposalsWrite(cause: ProposalsWriteError)

/** Conway's rules on a block, run as the node applies the block (`applyBlockEither` with
  * `ValidateAll`, cardano-ledger f649f975 `Shelley/API/Validation.hs`): BBODY in one judgment
  * ([[ConwayBbody.conwayBbody]]), every failure accumulated (`RuleT.Policy.Accumulate`), every
  * check of its transactions validated (`TxEnv.Validation.All`, the `ValidateAll` of
  * `applyBlockEither`); of the events, only what the node's phase-2 counters read is kept
  * ([[ScriptsTold]], [[ConwayPhase2.counted]]). The proposals are held beside the BBODY state, read
  * from the node's record in the governance state once, where a rule first reads them, and written
  * back once, after the block ([[Held]]), as Dijkstra's run holds its own ([[DijkstraBlockRun]]).
  * The node does not run it yet: its blocks run the flat BBODY until the Conway switch (#85).
  */
object ConwayBlockRun:
  /** The proposals of a run: not yet read from the node's record, or the era's, as the rules left
    * them.
    */
  private enum Held:
    case Unread
    case Read(proposals: Proposals[Conway])

  /** The scripts of the transaction a run observes ([[Observing]]): none between two transactions,
    * or the transaction's, located, with the evaluations of those run so far.
    */
  private enum Observed:
    case Between
    case In(located: ConwayPhase2.Located, done: Vector[Phase2.Evaluation])

  /** The state of a run: BBODY's, the proposals it holds, what of its events the counters read, in
    * their order, and the scripts it observes.
    */
  private final case class S(
      bbody: ShelleyBbodyState[Conway],
      held: Held,
      told: Chain[ScriptsTold],
      observed: Observed
  )

  /** The node's phase-2 observer (`--plutus-cases`) of a run, as the flat rules call it
    * (`EraBlock.Context.observer`, `Phase2.Observation`), with the clock its observations hold.
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
        s.copy(told = s.told ++ events.flatMap(e => Chain.fromOption(ScriptsTold.of(e))))
      )

  /** What a block the rules admitted comes to: the BBODY state after it, and the node's phase-2
    * counters of its scripts, from zero ([[ConwayPhase2.counted]]).
    */
  final case class Applied(state: ShelleyBbodyState[Conway], plutus: PlutusStats)

  private given Raise[F, UtxoStateDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.UtxoState(_))
  private given Raise[F, LedgerDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Ledger(_))
  private given Raise[F, GovDefect] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.Gov(_))
  private given Raise[F, ProposalsReadError] =
    summon[Raise[F, ConwayRuleDefect]].contramap(ConwayRuleDefect.ProposalsRead(_))

  private def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
    request => Monad[F].unit.map(_ => evaluator.evaluate(request))

  /** `evaluator` with each script it runs of the block of transactions `txs` observed by
    * `observing`, as the flat rules observe a transaction's (`TxChecks.phase2`): the script's
    * transaction located in the ledger state the rules run it in ([[ConwayPhase2.locate]]), its
    * evaluations held until the last of its scripts or the one the evaluator gives no verdict for,
    * then its observation told, with the parameters as the node records them, the block's slot and
    * the outputs its inputs and reference inputs spend and read. A script of no transaction of the
    * block, which the rules never run, is not observed.
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
    def redeemerOf(located: ConwayPhase2.Located, request: EvalRequest): Option[Redeemer] =
      located.scripts.collectFirst {
        case (redeemer, pwc) if PlutusWithContext.evalRequest(pwc) == request => redeemer
      }
    def told(located: ConwayPhase2.Located, s: S, outcome: Phase2.Outcome): F[Unit] =
      Monad[F].unit.map(_ =>
        ConwayPhase2
          .flatTx(located)
          .foreach(tx =>
            observing.observer(
              Phase2.Observation(
                tx,
                s.bbody.ls.utxo.gov.curPParams,
                slot,
                located.spent,
                located.references,
                observing.clock,
                Right(outcome)
              )
            )
          )
      )
    request =>
      state.get.flatMap { s =>
        val result = evaluator.evaluate(request)
        val current = s.observed match
          case Observed.In(located, done) =>
            redeemerOf(located, request).map(r => (located, done, r))
          case Observed.Between => None
        current
          .orElse(
            ConwayPhase2
              .locate(txs, s.bbody.ls, pp, slotToPOSIXTime)(pwc =>
                PlutusWithContext.evalRequest(pwc) == request
              )
              .flatMap(located => redeemerOf(located, request).map(r => (located, Vector.empty, r)))
          )
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

  /** The BBODY state with the proposals the run holds written back into the node's record
    * ([[ProposalsRecord.write]]; ones it cannot hold are a defect), the record as it was if the
    * rules never read them.
    */
  private def written(after: S): Either[ConwayRuleDefect, ShelleyBbodyState[Conway]] =
    val bbody = after.bbody
    after.held match
      case Held.Unread => Right(bbody)
      case Held.Read(proposals) =>
        ProposalsRecord.conway
          .write(proposals)
          .bimap(
            ConwayRuleDefect.ProposalsWrite(_),
            stored =>
              ShelleyBbodyState
                .ls[Conway]
                .andThen(LedgerState.utxo[Conway])
                .andThen(UTxOState.gov[Conway])
                .modify(_.copy(proposals = stored))(bbody)
          )

  /** BBODY on `block` from `state`: a defect, the refusal's failures, or the BBODY state after with
    * the block's phase-2 counters ([[Applied]]). The context of its transactions' rules is made
    * here from `globals`, as the reference's rules read it from theirs: the network (`networkId`, the Shelley genesis's) and the build's injection of
    * Shelley's UTXO failures ([[ConwayUtxo.injectShelleyFailure]]), with the system start and the
    * epoch information the caller gives (`systemStart`, `epochInfo`), and every
    * check validated, so that no caller runs the block's rules under another policy or on another
    * network. A script the evaluator gives no verdict for is named by its transaction and redeemer
    * where the rules met it ([[ConwayRuleDefect.ScriptUnavailable]]).
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
  ): Either[ConwayRuleDefect, Either[NonEmptyChain[BbodyPredFailure[Conway]], Applied]] =
    val txs = EraBlockBody.conway.txSeqBlockBody(block.blockBody)
    given ScriptEvaluator[F] = observing.fold(lifted(evaluator))(
      observed(
        evaluator,
        _,
        txs,
        env.bbodyPp,
        h.slotNoBlockHeader(block).toBigInt,
        slotToPOSIXTime
      )
    )
    // a script with no verdict, named by its transaction and redeemer in the ledger state the
    // rules judged it in, the state at the raise
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
                cause
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
      ConwayUtxo.injectShelleyFailure(globals.ledger.referenceBuild)
    )
    Judgment
      .judge(
        ConwayBbody.conwayBbody[F, H](env, block, context, verdicts, decoder, slotToPOSIXTime),
        RuleT.Policy.Accumulate
      )(globals, S(state, Held.Unread, Chain.empty, Observed.Between))
      .flatMap(
        _.traverse((after, _) =>
          written(after).map(
            Applied(
              _,
              ConwayPhase2.counted(txs, after.told)
            )
          )
        )
      )
