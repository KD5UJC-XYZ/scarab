// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, TestLedger, Utxo, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.{
  EraTxBody,
  Phase2Deferral,
  ScriptVerdictUnavailable,
  StoredTxOut,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  AlonzoUtxowEvent,
  ConwayUtxos,
  DijkstraSubUtxow,
  DijkstraSubUtxowEvent,
  DijkstraUtxoContext,
  DijkstraUtxoEnv,
  DijkstraUtxow,
  SubUtxoEnv,
  SubUtxowPredFailure,
  UtxoStateDefect,
  UtxosEvent,
  UtxosPredFailure,
  UtxowPredFailure
}
import xyz.kd5ujc.scarab.ledger.rules.{Judge, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, UTxOState}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

/** Dijkstra's UTXOW on the era types (`DijkstraUtxow.dijkstraUtxowTransition`, with its UTXO and
  * Conway's UTXOS) run in one judgment, for the suites that compare it with the flat rule and with
  * the reference's verdicts (`DijkstraRulesOracleSuite` in the node, `DijkstraUtxowSuite`), and
  * SUBUTXOW with its SUBUTXO ([[Sub]], `DijkstraSubUtxowSuite`).
  */
object DijkstraUtxoRun extends munit.Assertions:
  type Rec = UtxowPredFailure[Dijkstra]
  type Events = Chain[AlonzoUtxowEvent[Dijkstra]]
  type Defect = Either[Either[ScriptVerdictUnavailable, UtxoStateDefect], LedgerDefect]
  type S = (UTxOState[Dijkstra], Events)
  type W[A] = RuleT[Rec, Defect, S, A]
  import RuleT.given

  private given Stateful[W, UTxOState[Dijkstra]] =
    summon[Stateful[W, S]].focus(Lens[S, UTxOState[Dijkstra]](_._1)(u => s => (u, s._2)))

  private given Tell[W, Events] = new Tell[W, Events]:
    val functor: Functor[W] = summon[Monad[W]]
    def tell(events: Events): W[Unit] = summon[Stateful[W, S]].modify((u, e) => (u, e ++ events))

  private given Raise[W, ScriptVerdictUnavailable] =
    summon[Raise[W, Defect]].contramap(e => Left(Left(e)))

  private given Raise[W, UtxoStateDefect] = summon[Raise[W, Defect]].contramap(e => Left(Right(e)))

  private given Raise[W, LedgerDefect] = summon[Raise[W, Defect]].contramap(Right(_))

  /** The UTxO set before a transaction as LEDGER gives it to UTXOW (`ueOriginalUtxo`): the outputs
    * `stored` holds of the batch's inputs, reference inputs and collateral inputs, read into the
    * era. An output the era does not read fails the suite.
    */
  def originalUtxo(stored: Utxo, tx: DijkstraTx[TxLevel.Top]): UTxO[Dijkstra] =
    UTxO[Dijkstra](
      EraTxBody.dijkstra
        .allInputs(tx.body)
        .toVector
        .flatMap(in => stored.get(in).map(in -> _))
        .map((in, out) =>
          in -> StoredTxOut.dijkstra.fromStored(out).fold(e => fail(s"$in: $e"), identity)
        )
        .toMap
    )

  /** The annotated transaction, as `mkDijkstraStAnnTopTx` makes it with an empty cache. */
  def annotate(
      clock: BigInt => Either[xyz.kd5ujc.scarab.ledger.TranslationError, BigInt],
      env: DijkstraUtxoEnv[Dijkstra],
      tx: DijkstraTx[TxLevel.Top]
  ): DijkstraStAnnTx.TopTx =
    DijkstraStAnnTx.mkDijkstraStAnnTopTx(clock, env.pp, env.originalUtxo, Map.empty, tx)

  /** The transition from `state`, every failure accumulated, `depth` levels below the root: the
    * reference reports the failures of the rule it applies in the reverse of their order, as the
    * node's judgment does at the root (`Judgment.flatten`).
    */
  def run(
      env: DijkstraUtxoEnv[Dijkstra],
      stAnnTx: DijkstraStAnnTx.TopTx,
      context: DijkstraUtxoContext,
      state: UTxOState[Dijkstra],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      depth: Int = 0
  ): Either[Defect, Either[NonEmptyChain[Rec], S]] =
    given ScriptEvaluator[W] = request => Monad[W].unit.map(_ => evaluator.evaluate(request))
    val judge = summon[Judge[W, Rec]]
    val transition =
      DijkstraUtxow.dijkstraUtxowTransition[W](env, stAnnTx, context, verdicts, decoder)
    Judgment
      .judge(
        (0 until depth).foldLeft(transition)((fa, _) => judge.nest(fa)),
        RuleT.Policy.Accumulate
      )(TestLedger.globals, (state, Chain.empty))
      .map(_.map(_._1))

  /** Conway's UTXOS at Dijkstra (`ConwayUtxos.utxosTransition`) alone, as [[run]] runs UTXOW: a
    * script with no verdict raised, the failures in the reference's order, or the events told.
    */
  object Utxos:
    type URec = UtxosPredFailure[Dijkstra]
    type UEvents = Chain[UtxosEvent[Dijkstra]]
    type U[A] = RuleT[URec, ScriptVerdictUnavailable, UEvents, A]

    private given Tell[U, UEvents] = new Tell[U, UEvents]:
      val functor: Functor[U] = summon[Monad[U]]
      def tell(events: UEvents): U[Unit] = summon[Stateful[U, UEvents]].modify(_ ++ events)

    def run(
        stAnnTx: DijkstraStAnnTx.TopTx,
        validation: xyz.kd5ujc.scarab.ledger.TxEnv.Validation,
        evaluator: ScriptEvaluator[Id]
    ): Either[ScriptVerdictUnavailable, Either[NonEmptyChain[URec], UEvents]] =
      given ScriptEvaluator[U] = request => Monad[U].unit.map(_ => evaluator.evaluate(request))
      given Phase2Deferral[U, Dijkstra] = Phase2Deferral.now
      Judgment
        .judge(
          ConwayUtxos.utxosTransition[U, Dijkstra](stAnnTx, validation),
          RuleT.Policy.Accumulate
        )(TestLedger.globals, Chain.empty)
        .map(_.map(_._1))

  /** Dijkstra's SUBUTXOW on the era types (`DijkstraSubUtxow.dijkstraSubUtxowTransition`, with its
    * SUBUTXO) on one sub-transaction, as [[run]] runs UTXOW: a defect raised, the failures in the
    * reference's order, or the state and the events told.
    */
  object Sub:
    type SRec = SubUtxowPredFailure[Dijkstra]
    type SEvents = Chain[DijkstraSubUtxowEvent[Dijkstra]]
    type SDefect = Either[UtxoStateDefect, LedgerDefect]
    type SS = (UTxOState[Dijkstra], SEvents)
    type V[A] = RuleT[SRec, SDefect, SS, A]

    private given Stateful[V, UTxOState[Dijkstra]] =
      summon[Stateful[V, SS]].focus(Lens[SS, UTxOState[Dijkstra]](_._1)(u => s => (u, s._2)))

    private given Tell[V, SEvents] = new Tell[V, SEvents]:
      val functor: Functor[V] = summon[Monad[V]]
      def tell(events: SEvents): V[Unit] =
        summon[Stateful[V, SS]].modify((u, e) => (u, e ++ events))

    private given Raise[V, UtxoStateDefect] = summon[Raise[V, SDefect]].contramap(Left(_))

    private given Raise[V, LedgerDefect] = summon[Raise[V, SDefect]].contramap(Right(_))

    def run(
        env: SubUtxoEnv[Dijkstra],
        stAnnTx: DijkstraStAnnTx[TxLevel.Sub],
        context: DijkstraUtxoContext,
        state: UTxOState[Dijkstra],
        verdicts: Verdicts,
        decoder: ScriptDecoder[Id]
    ): Either[SDefect, Either[NonEmptyChain[SRec], SS]] =
      Judgment
        .judge(
          DijkstraSubUtxow.dijkstraSubUtxowTransition[V](env, stAnnTx, context, verdicts, decoder),
          RuleT.Policy.Accumulate
        )(TestLedger.globals, (state, Chain.empty))
        .map(_.map(_._1))
