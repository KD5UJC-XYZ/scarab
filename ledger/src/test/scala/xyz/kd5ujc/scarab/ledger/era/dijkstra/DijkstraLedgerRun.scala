// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, TranslationError, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.{
  EraTx,
  Proposals,
  ScriptVerdictUnavailable,
  StAnnTxCache,
  Tx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  DijkstraLedger,
  DijkstraLedgers,
  DijkstraMempool,
  DijkstraUtxoContext,
  GovDefect,
  LedgerEnv,
  LedgerEvent,
  LedgerPredFailure,
  LedgersEnv,
  LedgersEvent,
  LedgersPredFailure,
  MempoolEvent,
  MempoolPredFailure,
  PoolDefect,
  ShelleyLedgers,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, LedgerState}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

/** Dijkstra's LEDGER, LEDGERS and MEMPOOL on the era types (`DijkstraLedger`, `DijkstraLedgers`,
  * `DijkstraMempool`) run in one judgment, every failure accumulated, from a ledger state and the
  * era's proposals, with the node's evaluator, for `DijkstraLedgerSuite` and the node's
  * `DijkstraRulesOracleSuite`.
  */
object DijkstraLedgerRun:
  /** What a judgment ends with short of a verdict: a script with no verdict, SUBUTXO's or UTXO's
    * state, every rule's defect, POOL's or GOV's.
    */
  type Defect = Either[
    Either[ScriptVerdictUnavailable, Either[UtxoStateDefect, LedgerDefect]],
    Either[PoolDefect, GovDefect]
  ]

  /** The state after, the proposals after and the events told. */
  type After[Ev] = (LedgerState[Dijkstra], Proposals[Dijkstra], Chain[Ev])

  type Judged[Rec, Ev] = Either[Defect, Either[NonEmptyChain[Rec], After[Ev]]]

  private final class Run[Rec, Ev]:
    type S = After[Ev]
    type F[A] = RuleT[Rec, Defect, S, A]
    import RuleT.given

    given Stateful[F, LedgerState[Dijkstra]] =
      summon[Stateful[F, S]].focus(Lens[S, LedgerState[Dijkstra]](_._1)(l => s => (l, s._2, s._3)))

    given Stateful[F, Proposals[Dijkstra]] =
      summon[Stateful[F, S]].focus(Lens[S, Proposals[Dijkstra]](_._2)(p => s => (s._1, p, s._3)))

    given Tell[F, Chain[Ev]] = new Tell[F, Chain[Ev]]:
      val functor: Functor[F] = summon[Monad[F]]
      def tell(events: Chain[Ev]): F[Unit] =
        summon[Stateful[F, S]].modify((l, p, e) => (l, p, e ++ events))

    given Raise[F, ScriptVerdictUnavailable] =
      summon[Raise[F, Defect]].contramap(e => Left(Left(e)))
    given Raise[F, UtxoStateDefect] = summon[Raise[F, Defect]].contramap(e => Left(Right(Left(e))))
    given Raise[F, LedgerDefect] = summon[Raise[F, Defect]].contramap(e => Left(Right(Right(e))))
    given Raise[F, PoolDefect] = summon[Raise[F, Defect]].contramap(e => Right(Left(e)))
    given Raise[F, GovDefect] = summon[Raise[F, Defect]].contramap(e => Right(Right(e)))

    def evaluator(of: ScriptEvaluator[Id]): ScriptEvaluator[F] =
      request => Monad[F].unit.map(_ => of.evaluate(request))

    /** `program` from `state` and `proposals`, `depth` levels below the root. */
    def run(
        globals: Globals,
        state: LedgerState[Dijkstra],
        proposals: Proposals[Dijkstra],
        depth: Int
    )(program: F[Unit]): Judged[Rec, Ev] =
      val nested = (0 until depth).foldLeft(program)((fa, _) => summon[Judge[F, Rec]].nest(fa))
      Judgment
        .judge(nested, RuleT.Policy.Accumulate)(globals, (state, proposals, Chain.empty))
        .map(_.map(_._1))

  private val ledgerRun = Run[LedgerPredFailure[Dijkstra], LedgerEvent[Dijkstra]]
  private val ledgersRun = Run[LedgersPredFailure[Dijkstra], LedgersEvent[Dijkstra]]
  private val mempoolRun = Run[MempoolPredFailure[Dijkstra], MempoolEvent[Dijkstra]]
  private val cachesRun = Run[LedgersPredFailure[Dijkstra], StAnnTxCache[Dijkstra]]

  /** MEMPOOL on `stAnnTx` from `state` and `proposals`, at the root. */
  def mempool(
      globals: Globals,
      env: LedgerEnv[Dijkstra],
      stAnnTx: DijkstraStAnnTx.TopTx,
      context: DijkstraUtxoContext,
      state: LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  ): Judged[MempoolPredFailure[Dijkstra], MempoolEvent[Dijkstra]] =
    import mempoolRun.given
    import RuleT.given
    given ScriptEvaluator[mempoolRun.F] = mempoolRun.evaluator(evaluator)
    mempoolRun.run(globals, state, proposals, 0)(
      DijkstraMempool.mempoolTransition[mempoolRun.F](env, stAnnTx, context, verdicts, decoder)
    )

  /** LEDGER on `stAnnTx` from `state` and `proposals`, `depth` levels below the root. */
  def ledger(
      globals: Globals,
      env: LedgerEnv[Dijkstra],
      stAnnTx: DijkstraStAnnTx.TopTx,
      context: DijkstraUtxoContext,
      state: LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      depth: Int = 0
  ): Judged[LedgerPredFailure[Dijkstra], LedgerEvent[Dijkstra]] =
    import ledgerRun.given
    import RuleT.given
    given ScriptEvaluator[ledgerRun.F] = ledgerRun.evaluator(evaluator)
    ledgerRun.run(globals, state, proposals, depth)(
      DijkstraLedger.dijkstraLedgerTransition[ledgerRun.F](env, stAnnTx, context, verdicts, decoder)
    )

  /** LEDGERS on `txs` from `state` and `proposals`, `depth` levels below the root. */
  def ledgers(
      globals: Globals,
      env: LedgersEnv[Dijkstra],
      txs: Vector[Tx[TxLevel.Top, Dijkstra]],
      context: DijkstraUtxoContext,
      state: LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      depth: Int = 0
  ): Judged[LedgersPredFailure[Dijkstra], LedgersEvent[Dijkstra]] =
    import ledgersRun.given
    import RuleT.given
    given ScriptEvaluator[ledgersRun.F] = ledgersRun.evaluator(evaluator)
    ledgersRun.run(globals, state, proposals, depth)(
      DijkstraLedgers.ledgersTransition[ledgersRun.F](
        env,
        txs,
        context,
        verdicts,
        decoder,
        slotToPOSIXTime
      )
    )

  /** LEDGERS's fold over `txs` from `state` with Dijkstra's annotation
    * ([[DijkstraLedgers.mkStAnnTx]]), each annotation's cache told in turn where LEDGER would run:
    * the caches the fold hands on.
    */
  def ledgersCaches(
      globals: Globals,
      env: LedgersEnv[Dijkstra],
      txs: Vector[Tx[TxLevel.Top, Dijkstra]],
      state: LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  ): Judged[LedgersPredFailure[Dijkstra], StAnnTxCache[Dijkstra]] =
    import cachesRun.given
    import RuleT.given
    val t = EraTx.dijkstra
    cachesRun.run(globals, state, proposals, 0)(
      ShelleyLedgers.ledgersFold[cachesRun.F, Dijkstra](env, txs, Map.empty)(
        DijkstraLedgers.mkStAnnTx(slotToPOSIXTime, env.ledgersPp)
      )((_, stAnnTx) =>
        summon[Tell[cachesRun.F, Chain[StAnnTxCache[Dijkstra]]]]
          .tell(Chain.one(t.cacheStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)))
      )
    )
