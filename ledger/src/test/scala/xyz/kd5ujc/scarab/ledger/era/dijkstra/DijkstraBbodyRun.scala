// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, TranslationError, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.{
  Block,
  EraBlockHeader,
  LeiosEraBlockHeader,
  Phase2Deferral,
  Proposals,
  ScriptVerdictUnavailable,
  Tx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  BbodyEnv,
  BbodyEvent,
  BbodyPredFailure,
  DijkstraBbody,
  DijkstraClosure,
  DijkstraUtxoContext,
  GovDefect,
  LedgerEnv,
  PoolDefect,
  ShelleyBbodyState,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, LedgerState}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

/** Dijkstra's BBODY on the era types (`DijkstraBbody`) run in one judgment, every failure
  * accumulated, from a BBODY state and the era's proposals, with the node's evaluator, for
  * `DijkstraBbodySuite` and the node's `DijkstraRulesOracleSuite`; and the application of an
  * endorser block's closure (`DijkstraClosure`), which records no failure, for
  * `DijkstraClosureSuite`.
  */
object DijkstraBbodyRun:
  /** What a judgment ends with short of a verdict, as [[DijkstraLedgerRun.Defect]]. */
  type Defect = DijkstraLedgerRun.Defect

  /** The state after, the proposals after and the events told. */
  type After = (ShelleyBbodyState[Dijkstra], Proposals[Dijkstra], Chain[BbodyEvent[Dijkstra]])

  type Judged = Either[Defect, Either[NonEmptyChain[BbodyPredFailure[Dijkstra]], After]]

  private type F[A] = RuleT[BbodyPredFailure[Dijkstra], Defect, After, A]
  import RuleT.given

  private given Stateful[F, ShelleyBbodyState[Dijkstra]] =
    summon[Stateful[F, After]].focus(
      Lens[After, ShelleyBbodyState[Dijkstra]](_._1)(b => s => (b, s._2, s._3))
    )

  private given Stateful[F, Proposals[Dijkstra]] =
    summon[Stateful[F, After]].focus(
      Lens[After, Proposals[Dijkstra]](_._2)(p => s => (s._1, p, s._3))
    )

  private given Tell[F, Chain[BbodyEvent[Dijkstra]]] = new Tell[F, Chain[BbodyEvent[Dijkstra]]]:
    val functor: Functor[F] = summon[Monad[F]]
    def tell(events: Chain[BbodyEvent[Dijkstra]]): F[Unit] =
      summon[Stateful[F, After]].modify((b, p, e) => (b, p, e ++ events))

  private given Raise[F, ScriptVerdictUnavailable] =
    summon[Raise[F, Defect]].contramap(e => Left(Left(e)))
  private given Raise[F, UtxoStateDefect] =
    summon[Raise[F, Defect]].contramap(e => Left(Right(Left(e))))
  private given Raise[F, LedgerDefect] =
    summon[Raise[F, Defect]].contramap(e => Left(Right(Right(e))))
  private given Raise[F, PoolDefect] = summon[Raise[F, Defect]].contramap(e => Right(Left(e)))
  private given Raise[F, GovDefect] = summon[Raise[F, Defect]].contramap(e => Right(Right(e)))

  /** BBODY on `block` from `state` and `proposals`, at the root. */
  def bbody[H](
      globals: Globals,
      env: BbodyEnv[Dijkstra],
      block: Block[H, Dijkstra],
      context: DijkstraUtxoContext,
      state: ShelleyBbodyState[Dijkstra],
      proposals: Proposals[Dijkstra],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  )(using EraBlockHeader[H, Dijkstra], LeiosEraBlockHeader[H, Dijkstra]): Judged =
    given ScriptEvaluator[F] = request => Monad[F].unit.map(_ => evaluator.evaluate(request))
    given Phase2Deferral[F, Dijkstra] = Phase2Deferral.now
    Judgment
      .judge(
        DijkstraBbody.dijkstraBbodyTransition[F, H](
          env,
          block,
          context,
          verdicts,
          decoder,
          slotToPOSIXTime
        ),
        RuleT.Policy.Accumulate
      )(globals, (state, proposals, Chain.empty))
      .map(_.map(_._1))

  /** The closure `txs` applied (`DijkstraClosure.applyLeiosClosure`) with `env` to `state` and
    * `proposals`: the state after, the proposals after, or a defect. It records no failure.
    */
  def closure(
      globals: Globals,
      env: LedgerEnv[Dijkstra],
      txs: Vector[Tx[TxLevel.Top, Dijkstra]],
      context: DijkstraUtxoContext,
      state: LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  ): Judged =
    given ScriptEvaluator[F] = request => Monad[F].unit.map(_ => evaluator.evaluate(request))
    given Stateful[F, LedgerState[Dijkstra]] =
      summon[Stateful[F, ShelleyBbodyState[Dijkstra]]].focus(ShelleyBbodyState.ls[Dijkstra])
    Judgment
      .judge(
        DijkstraClosure.applyLeiosClosure[F](
          env,
          txs,
          context,
          verdicts,
          decoder,
          slotToPOSIXTime
        ),
        RuleT.Policy.Accumulate
      )(
        globals,
        (
          ShelleyBbodyState[Dijkstra](state, xyz.kd5ujc.scarab.ledger.state.BlocksMade.empty),
          proposals,
          Chain.empty
        )
      )
      .map(_.map(_._1))
