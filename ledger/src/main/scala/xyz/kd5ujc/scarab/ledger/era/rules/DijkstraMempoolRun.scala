// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyChain}
import cats.mtl.{Raise, Stateful, Tell}
import cats.syntax.all.*
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, TranslationError, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.{
  BothLevels,
  EraTx,
  PParams,
  Proposals,
  ScriptVerdictUnavailable,
  StAnnTx,
  Tx,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judgment, RuleT, contramap, focus}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, LedgerState}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

/** Dijkstra's MEMPOOL on a transaction, run as the node's mempool applies one:
  * `ruleApplyTxValidation` of MEMPOOL
  * (`eras/shelley/impl/src/Cardano/Ledger/Shelley/API/Mempool.hs:230-262`, cardano-ledger
  * 9df9f98c), through `defaultApplyTxWithValidation` and `defaultReapplyValidatedTx` (`:267-323`)
  * of `instance ApplyTx DijkstraEra` (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:87-96`):
  * one judgment, every failure accumulated (`RuleT.Policy.Accumulate`), the events discarded
  * (`asoEvents = EPDiscard`). The validation is the context's ([[DijkstraUtxoContext.validation]]):
  * every check for `applyTx` (`ValidateAll`), the static ones skipped for `reapplyTx`
  * (`ValidateSuchThat (notElem lblStatic)`). The proposals are their own state beside the ledger's,
  * as [[DijkstraBlockRun]] keeps them, and the defects its ([[DijkstraRuleDefect]]).
  */
object DijkstraMempoolRun:
  private type After = (LedgerState[Dijkstra], Proposals[Dijkstra])

  private type F[A] = RuleT[MempoolPredFailure[Dijkstra], DijkstraRuleDefect, After, A]
  import RuleT.given

  private given Stateful[F, LedgerState[Dijkstra]] =
    summon[Stateful[F, After]].focus(Lens[After, LedgerState[Dijkstra]](_._1)(l => s => (l, s._2)))

  private given Stateful[F, Proposals[Dijkstra]] =
    summon[Stateful[F, After]].focus(Lens[After, Proposals[Dijkstra]](_._2)(p => s => (s._1, p)))

  // `asoEvents = EPDiscard` (`Shelley/API/Mempool.hs:249`, cardano-ledger 9df9f98c)
  private given Tell[F, Chain[MempoolEvent[Dijkstra]]] = new Tell[F, Chain[MempoolEvent[Dijkstra]]]:
    val functor: Functor[F] = summon[Monad[F]]
    def tell(events: Chain[MempoolEvent[Dijkstra]]): F[Unit] = Monad[F].unit

  private given Raise[F, ScriptVerdictUnavailable] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Unavailable(_))
  private given Raise[F, UtxoStateDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.UtxoState(_))
  private given Raise[F, LedgerDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Ledger(_))
  private given Raise[F, PoolDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Pool(_))
  private given Raise[F, GovDefect] =
    summon[Raise[F, DijkstraRuleDefect]].contramap(DijkstraRuleDefect.Gov(_))

  private def lifted(evaluator: ScriptEvaluator[Id]): ScriptEvaluator[F] =
    request => Monad[F].unit.map(_ => evaluator.evaluate(request))

  /** MEMPOOL on `stAnnTx` with `env` from `state` and `proposals`: a defect, the refusal's
    * failures, or the ledger state and the proposals after.
    */
  def mempool(
      globals: Globals,
      env: LedgerEnv[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      context: DijkstraUtxoContext,
      state: LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra],
      evaluator: ScriptEvaluator[Id],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  ): Either[DijkstraRuleDefect, Either[NonEmptyChain[MempoolPredFailure[Dijkstra]], After]] =
    given ScriptEvaluator[F] = lifted(evaluator)
    Judgment
      .judge(
        DijkstraMempool.mempoolTransition[F](env, stAnnTx, context, verdicts, decoder),
        RuleT.Policy.Accumulate
      )(globals, (state, proposals))
      .map(_.map(_._1))

  /** The stored outputs of the transaction's inputs at every level, its reference inputs and its
    * collateral (`allInputsTxBodyF`, the keys `getTransactionKeySets` reads, ouroboros-consensus
    * 2416493b `Shelley/Ledger/Mempool.hs:226-230`) in `state`, read into the era: the part of the
    * UTxO set (`utxoG`) that the annotation and the reference-script measure read
    * (`defaultApplyTxWithValidation`, `Shelley/API/Mempool.hs:283-296`, cardano-ledger 9df9f98c;
    * `txMeasureRefScripts`, `Shelley/Ledger/Mempool.hs:775-798`). A stored output the era cannot
    * read is the state's defect.
    */
  def utxoOf(
      state: LedgerState[Dijkstra],
      tx: Tx[TxLevel.Top, Dijkstra]
  ): Either[UtxoStateDefect, UTxO[Dijkstra]] =
    type G[A] = Either[UtxoStateDefect, A]
    given Raise[G, UtxoStateDefect] = new Raise[G, UtxoStateDefect]:
      val functor: Functor[G] = summon[Monad[G]]
      def raise[E2 <: UtxoStateDefect, A](e: E2): G[A] = Left(e)
    ShelleyUtxo.readStored[G, Dijkstra](
      ShelleyUtxo
        .extractKeys(
          state.utxo.utxo.utxo,
          EraTx.dijkstra.bodyOf
            .allInputs(EraTx.dijkstra.body[TxLevel.Top](tx)(using BothLevels.Top))
        )
        ._2
    )

  /** `mkStAnnTx` of Dijkstra (`mkDijkstraStAnnTopTx`, `Dijkstra.hs:90, 111-169`, cardano-ledger
    * 9df9f98c) as `defaultApplyTxWithValidation` calls it (`Shelley/API/Mempool.hs:283-296`): the
    * transaction annotated against `utxo` with the parameters of the environment and an empty cache
    * (`mempty`).
    */
  def annotate(
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      pp: PParams[Dijkstra],
      utxo: UTxO[Dijkstra],
      tx: Tx[TxLevel.Top, Dijkstra]
  ): StAnnTx[TxLevel.Top, Dijkstra] =
    DijkstraLedgers.mkStAnnTx(slotToPOSIXTime, pp)(utxo, Map.empty, tx)
