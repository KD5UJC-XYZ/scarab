// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayLedger,
  ConwayLedgerEvent,
  GovDefect,
  LedgerEnv,
  LedgerEvent,
  LedgerPredFailure,
  LedgersEnv,
  LedgersEvent,
  LedgersPredFailure,
  ProposalsRecord,
  ShelleyLedgers,
  UtxoContext,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{Conway, LedgerState}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

/** Conway's LEDGER and LEDGERS on the era types (`ConwayLedger.conwayLedgerTransition`,
  * `ShelleyLedgers.ledgersTransition`) run in one judgment, its failures kept as a policy keeps
  * them, for the suites that judge them (`ConwayTestKit`, `ConwayLedgerChecksSuite` in the node).
  */
object EraLedgerRun:
  /** Why a judgment reached no verdict: a defect of one of the rules below LEDGER. */
  enum Defect:
    case Script(cause: ScriptVerdictUnavailable)
    case Utxo(cause: UtxoStateDefect)
    case Ledger(cause: LedgerDefect)
    case Gov(cause: GovDefect)
    case Proposals(cause: ProposalsReadError)

  private final class Run[Rec, Ev]:
    type Events = Chain[Ev]
    type S = (LedgerState[Conway], Events)
    type F[A] = RuleT[Rec, Defect, S, A]
    import RuleT.given

    given Stateful[F, LedgerState[Conway]] =
      summon[Stateful[F, S]].focus(Lens[S, LedgerState[Conway]](_._1)(l => s => (l, s._2)))

    given Tell[F, Events] = new Tell[F, Events]:
      val functor: Functor[F] = summon[Monad[F]]
      def tell(events: Events): F[Unit] = summon[Stateful[F, S]].modify((l, e) => (l, e ++ events))

    given Raise[F, ScriptVerdictUnavailable] = summon[Raise[F, Defect]].contramap(Defect.Script(_))
    given Raise[F, UtxoStateDefect] = summon[Raise[F, Defect]].contramap(Defect.Utxo(_))
    given Raise[F, LedgerDefect] = summon[Raise[F, Defect]].contramap(Defect.Ledger(_))
    given Raise[F, GovDefect] = summon[Raise[F, Defect]].contramap(Defect.Gov(_))
    given Raise[F, ProposalsReadError] = summon[Raise[F, Defect]].contramap(Defect.Proposals(_))

    // one transaction's or one block's LEDGER: the proposals read from the node's record on every
    // read and written back on every write
    given Stateful[F, Proposals[Conway]] = ProposalsRecord.stateful[F, Conway]

    def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
      request => Monad[F].map(Monad[F].unit)(_ => evaluator.evaluate(request))

    def run(
        globals: Globals,
        state: LedgerState[Conway],
        depth: Int,
        policy: RuleT.Policy = RuleT.Policy.Accumulate
    )(
        program: F[Unit]
    ): Either[Defect, Either[NonEmptyChain[Rec], (LedgerState[Conway], Events)]] =
      val nested = (0 until depth).foldLeft(program)((fa, _) => summon[Judge[F, Rec]].nest(fa))
      Judgment
        .judge(nested, policy)(globals, (state, Chain.empty))
        .map(_.map(_._1))

  private val ledgerRun = Run[LedgerPredFailure[Conway], LedgerEvent[Conway]]
  private val ledgersRun = Run[LedgersPredFailure[Conway], LedgersEvent[Conway]]

  /** A judgment's outcome: a defect, the failures in the reference's order, or the state after with
    * the events.
    */
  type Judged[Rec, Ev] =
    Either[Defect, Either[NonEmptyChain[Rec], (LedgerState[Conway], Chain[Ev])]]

  /** LEDGER on `stAnnTx` from `state`, `depth` levels below the root: a submitted transaction's
    * LEDGER is one level down, under MEMPOOL (`Conway/Rules/Mempool.hs:138`, cardano-ledger
    * f649f975), where the reference reports its failures in their order (`Judgment.flatten`). Its
    * failures are kept as `policy` keeps them: every one, or the first alone.
    */
  def ledger(
      globals: Globals,
      env: LedgerEnv[Conway],
      stAnnTx: AlonzoStAnnTx[Conway],
      context: UtxoContext[Conway],
      state: LedgerState[Conway],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      depth: Int = 0,
      policy: RuleT.Policy = RuleT.Policy.Accumulate
  ): Judged[LedgerPredFailure[Conway], LedgerEvent[Conway]] =
    import ledgerRun.given
    import RuleT.given
    given ScriptEvaluator[ledgerRun.F] = ledgerRun.lifted(evaluator)
    given Phase2Deferral[ledgerRun.F, Conway] = Phase2Deferral.now
    ledgerRun.run(globals, state, depth, policy)(
      ConwayLedger
        .conwayLedgerTransition[ledgerRun.F, Conway](env, stAnnTx, context, verdicts, decoder)
    )

  /** LEDGERS on `txs` from `state`, at the root, each annotated with `slotToPOSIXTime`, its
    * failures kept as `policy` keeps them.
    */
  def ledgers(
      globals: Globals,
      env: LedgersEnv[Conway],
      txs: Vector[Tx[TxLevel.Top, Conway]],
      context: UtxoContext[Conway],
      state: LedgerState[Conway],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[xyz.kd5ujc.scarab.ledger.TranslationError, BigInt],
      policy: RuleT.Policy = RuleT.Policy.Accumulate
  ): Judged[LedgersPredFailure[Conway], LedgersEvent[Conway]] =
    import ledgersRun.given
    import RuleT.given
    given ScriptEvaluator[ledgersRun.F] = ledgersRun.lifted(evaluator)
    given Phase2Deferral[ledgersRun.F, Conway] = Phase2Deferral.now
    ledgersRun.run(globals, state, 0, policy)(
      ShelleyLedgers.ledgersTransition[ledgersRun.F, Conway](
        env,
        txs,
        context,
        verdicts,
        decoder,
        slotToPOSIXTime
      )
    )

  /** A collection's error in the node's flat form, where it names it: a missing redeemer's purpose
    * by `purpose`, and a translation's failure where the flat form has the reference's constructor
    * with the same contents (a missing input, a time past the horizon by the clock's `PastHorizon`,
    * the outputs a Plutus V1 or V2 context cannot show, the reference inputs of a V1 context and
    * those that are inputs too). Any other is none, so that a comparison fails rather than encode
    * it as something else.
    */
  def flatCollect(
      error: CollectError[Conway],
      purpose: EraUtxoRun.Purposes
  ): Option[xyz.kd5ujc.scarab.ledger.CollectError] =
    import xyz.kd5ujc.scarab.ledger.{CollectError as Flat, TranslationError as T}
    error match
      case CollectError.NoRedeemer(p)  => purpose(p).map(Flat.NoRedeemer(_))
      case CollectError.NoWitness(h)   => Some(Flat.NoWitness(h))
      case CollectError.NoCostModel(l) => Some(Flat.NoCostModel(l))
      case CollectError.BadTranslation(cause) =>
        val translation: Option[T] = cause match
          case conway.ConwayContextError.ReferenceInputsNotDisjointFromInputs(common) =>
            Some(T.ReferenceInputsNotDisjointFromInputs(common))
          case conway.ConwayContextError.BabbageContextError(babbage) =>
            babbage match
              case BabbageContextError.AlonzoContextError(
                    AlonzoContextError.TranslationLogicMissingInput(input)
                  ) =>
                Some(T.UnresolvedInput(input))
              // a time past the horizon by the clock's own failure, which the node encodes as
              // the reference's text; no other cause is the clock's, and none is projected
              case BabbageContextError.AlonzoContextError(
                    AlonzoContextError.TimeTranslationPastHorizon(cause @ T.PastHorizon(_, _))
                  ) =>
                Some(cause)
              case BabbageContextError.ByronTxOutInContext(source) =>
                Some(T.ByronTxOutInContext(source))
              case BabbageContextError.InlineDatumsNotSupported(source) =>
                Some(T.InlineDatumsNotSupported(source))
              case BabbageContextError.ReferenceScriptsNotSupported(source) =>
                Some(T.ReferenceScriptsNotSupported(source))
              case BabbageContextError.ReferenceInputsNotSupported(inputs) =>
                Some(T.ReferenceInputsNotSupported(inputs))
              case _ => None
          case _ => None
        translation.map(Flat.BadTranslation(_))

  /** The counters the flat rules keep (`PlutusStats`), as LEDGER's events give them: UTXOS's
    * events, through UTXO's and UTXOW's embeddings and LEDGER's (`EraUtxoRun.Utxow.counters`).
    */
  def counters(valid: Boolean, events: Chain[LedgerEvent[Conway]]): Option[(Long, Long, Long)] =
    EraUtxoRun.Utxow.counters(
      valid,
      events.collect { case ConwayLedgerEvent.UtxowEvent(e) => e }
    )
