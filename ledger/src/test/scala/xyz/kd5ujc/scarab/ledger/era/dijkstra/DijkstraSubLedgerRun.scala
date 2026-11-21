// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.{Proposals, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  DijkstraSubLedger,
  DijkstraSubLedgers,
  DijkstraUtxoContext,
  GovDefect,
  PoolDefect,
  SubLedgerEnv,
  SubLedgerEvent,
  SubLedgerPredFailure,
  SubLedgersEvent,
  SubLedgersPredFailure,
  UtxoStateDefect
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, LedgerState}
import xyz.kd5ujc.scarab.plutus.ScriptDecoder

/** Dijkstra's SUBLEDGERS and SUBLEDGER on the era types (`DijkstraSubLedgers`, `DijkstraSubLedger`)
  * run in one judgment, every failure accumulated, from a ledger state and the era's proposals, for
  * `DijkstraSubLedgerSuite`.
  */
object DijkstraSubLedgerRun:
  /** What a judgment ends with short of a verdict: SUBUTXO's or every rule's defect, POOL's or
    * GOV's.
    */
  type Defect = Either[Either[UtxoStateDefect, LedgerDefect], Either[PoolDefect, GovDefect]]

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

    given Raise[F, UtxoStateDefect] = summon[Raise[F, Defect]].contramap(e => Left(Left(e)))
    given Raise[F, LedgerDefect] = summon[Raise[F, Defect]].contramap(e => Left(Right(e)))
    given Raise[F, PoolDefect] = summon[Raise[F, Defect]].contramap(e => Right(Left(e)))
    given Raise[F, GovDefect] = summon[Raise[F, Defect]].contramap(e => Right(Right(e)))

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

  private val subLedgersRun = Run[SubLedgersPredFailure[Dijkstra], SubLedgersEvent[Dijkstra]]
  private val subLedgerRun = Run[SubLedgerPredFailure[Dijkstra], SubLedgerEvent[Dijkstra]]

  /** SUBLEDGERS on `subTxs` from `state` and `proposals`, `depth` levels below the root. */
  def subLedgers(
      globals: Globals,
      env: SubLedgerEnv[Dijkstra],
      subTxs: List[DijkstraStAnnTx[TxLevel.Sub]],
      context: DijkstraUtxoContext,
      state: LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      depth: Int = 0
  ): Judged[SubLedgersPredFailure[Dijkstra], SubLedgersEvent[Dijkstra]] =
    import subLedgersRun.given
    import RuleT.given
    subLedgersRun.run(globals, state, proposals, depth)(
      DijkstraSubLedgers.dijkstraSubLedgersTransition[subLedgersRun.F](
        env,
        subTxs,
        context,
        verdicts,
        decoder
      )
    )

  /** SUBLEDGER on `subTx` from `state` and `proposals`, at the root. */
  def subLedger(
      globals: Globals,
      env: SubLedgerEnv[Dijkstra],
      subTx: DijkstraStAnnTx[TxLevel.Sub],
      context: DijkstraUtxoContext,
      state: LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  ): Judged[SubLedgerPredFailure[Dijkstra], SubLedgerEvent[Dijkstra]] =
    import subLedgerRun.given
    import RuleT.given
    subLedgerRun.run(globals, state, proposals, 0)(
      DijkstraSubLedger.dijkstraSubLedgerTransition[subLedgerRun.F](
        env,
        subTx,
        context,
        verdicts,
        decoder
      )
    )
