// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Id, Monad}
import cats.data.Chain
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, MempoolProblem, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Sts}
import xyz.kd5ujc.scarab.ledger.state.{Conway, Dijkstra, EraTag, LedgerState}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

// MEMPOOL of Dijkstra as the reference writes it (cardano-ledger 9df9f98c,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Mempool.hs`): the rule a transaction
// submitted to the mempool is applied by, whose sub-rule is Dijkstra's LEDGER. Conway's mempool
// failure, which Conway's LEDGER no longer reports at Dijkstra, is MEMPOOL's own.

/** `data DijkstraMempoolPredFailure era` (`Dijkstra/Rules/Mempool.hs:79-83`, cardano-ledger
  * 9df9f98c), its constructors in the order of their tags (1 to 3, `:118-122`). The mempool's
  * failure is the node's typed cause ([[MempoolProblem]]), which the node renders to the
  * reference's `Text`, as Conway's `ConwayMempoolFailure` holds it.
  */
enum DijkstraMempoolPredFailure[E <: EraTag]:
  case LedgerFailure(failure: LedgerPredFailure[E])
  case MempoolFailure(problem: MempoolProblem)
  case AllInputsAreSpent()

object DijkstraMempoolPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraMempoolPredFailure[E], DijkstraMempoolPredFailure[E]] =
    CanEqual.derived

/** `newtype DijkstraMempoolEvent era` (`Dijkstra/Rules/Mempool.hs:136-137`, cardano-ledger
  * 9df9f98c).
  */
enum DijkstraMempoolEvent[E <: EraTag]:
  case LedgerEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.LedgerEvent[E])

object DijkstraMempoolEvent:
  given [E <: EraTag]: CanEqual[DijkstraMempoolEvent[E], DijkstraMempoolEvent[E]] =
    CanEqual.derived

/** `EraRuleFailure "MEMPOOL" era`: Conway's is LEDGER's (`type PredicateFailure (MEMPOOL era) =
  * ConwayLedgerPredFailure era`, `Conway/Rules/Mempool.hs:86`, cardano-ledger f649f975, whose
  * `EraRule "MEMPOOL" ConwayEra` is Conway's MEMPOOL, `Conway/Era.hs:232`), and Dijkstra's its own
  * (`Dijkstra/Rules/Mempool.hs:85`, cardano-ledger 9df9f98c).
  */
type MempoolPredFailure[E <: EraTag] = E match
  case Conway   => ConwayLedgerPredFailure[Conway]
  case Dijkstra => DijkstraMempoolPredFailure[Dijkstra]

/** `EraRuleEvent "MEMPOOL" era`: Conway's is LEDGER's (`type Event (MEMPOOL era) =
  * ConwayLedgerEvent era`, `Conway/Rules/Mempool.hs:87`, cardano-ledger f649f975), and Dijkstra's
  * its own (`Dijkstra/Rules/Mempool.hs:87`, cardano-ledger 9df9f98c).
  */
type MempoolEvent[E <: EraTag] = E match
  case Conway   => ConwayLedgerEvent[Conway]
  case Dijkstra => DijkstraMempoolEvent[Dijkstra]

/** `InjectRuleFailure "MEMPOOL" f era` (`Core/Era.hs:119-124`, cardano-ledger 9df9f98c): how era
  * `E` 's MEMPOOL reports a failure `F` of its own or of a rule below it. Contravariant in `F`, as
  * [[InjectLedgerFailure]] is, so that the instance over the constructors of Conway's LEDGER
  * failure it maps serves one of them.
  */
trait InjectMempoolFailure[-F, E <: EraTag]:
  def injectFailure(failure: F): MempoolPredFailure[E]

object InjectMempoolFailure:
  /** `Dijkstra/Rules/Mempool.hs:89`, cardano-ledger 9df9f98c: the identity. */
  given dijkstra: InjectMempoolFailure[DijkstraMempoolPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraMempoolPredFailure[Dijkstra]): MempoolPredFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/Mempool.hs:91-95`, cardano-ledger 9df9f98c, on the constructors it maps
    * ([[DijkstraMempool.ConwayFailure]]).
    */
  given dijkstraConway: InjectMempoolFailure[DijkstraMempool.ConwayFailure[Dijkstra], Dijkstra] with
    def injectFailure(
        failure: DijkstraMempool.ConwayFailure[Dijkstra]
    ): MempoolPredFailure[Dijkstra] =
      DijkstraMempool.conwayToDijkstraMempoolPredFailure(failure)

  /** `Dijkstra/Rules/Mempool.hs:97-98`, cardano-ledger 9df9f98c: `LedgerFailure`, after LEDGER's
    * injection of UTXO's failure ([[InjectLedgerFailure.dijkstraUtxow]]).
    */
  given dijkstraUtxo: InjectMempoolFailure[DijkstraUtxoPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraUtxoPredFailure[Dijkstra]): MempoolPredFailure[Dijkstra] =
      DijkstraMempoolPredFailure.LedgerFailure(
        InjectLedgerFailure.dijkstraUtxow[DijkstraUtxoPredFailure[Dijkstra]].injectFailure(failure)
      )

/** `Embed (LEDGER era) (MEMPOOL era)`: Conway's the identity (`Conway/Rules/Mempool.hs:140-172`,
  * cardano-ledger f649f975), Dijkstra's `LedgerFailure` and `LedgerEvent`
  * (`Dijkstra/Rules/Mempool.hs:209-217`, cardano-ledger 9df9f98c).
  */
trait EmbedLedgerInMempool[E <: EraTag]:
  def wrapFailed(failure: LedgerPredFailure[E]): MempoolPredFailure[E]
  def wrapEvent(event: LedgerEvent[E]): MempoolEvent[E]

object EmbedLedgerInMempool:
  /** `wrapFailed = id` and `wrapEvent = id` (`Conway/Rules/Mempool.hs:171-172`, cardano-ledger
    * f649f975): MEMPOOL's failure and event are LEDGER's.
    */
  given conway: EmbedLedgerInMempool[Conway] with
    def wrapFailed(failure: LedgerPredFailure[Conway]): MempoolPredFailure[Conway] = failure
    def wrapEvent(event: LedgerEvent[Conway]): MempoolEvent[Conway] = event

  given dijkstra: EmbedLedgerInMempool[Dijkstra] with
    def wrapFailed(failure: LedgerPredFailure[Dijkstra]): MempoolPredFailure[Dijkstra] =
      DijkstraMempoolPredFailure.LedgerFailure(failure)
    def wrapEvent(event: LedgerEvent[Dijkstra]): MempoolEvent[Dijkstra] =
      DijkstraMempoolEvent.LedgerEvent(event)

/** MEMPOOL of Dijkstra (`Dijkstra/Rules/Mempool.hs`, cardano-ledger 9df9f98c). */
object DijkstraMempool:
  /** The constructors of Conway's LEDGER failure that MEMPOOL's injection maps
    * (`Dijkstra/Rules/Mempool.hs:91-95`, cardano-ledger 9df9f98c): the mempool's failure, and those
    * `conwayToDijkstraLedgerPredFailure` maps ([[DijkstraLedger.ConwayFailure]]); the one it calls
    * impossible (`ConwayWdrlNotDelegatedToDRep`) left out, by D7a's decision on the reference's
    * partial injections (2026-11-20, #85).
    */
  type ConwayFailure[E <: EraTag] =
    ConwayLedgerPredFailure.ConwayMempoolFailure[E] | DijkstraLedger.ConwayFailure[E]

  /** The injection of `Dijkstra/Rules/Mempool.hs:91-95`, cardano-ledger 9df9f98c, on
    * [[ConwayFailure]]: the mempool's failure that all inputs are spent is `AllInputsAreSpent`, any
    * other `MempoolFailure`, and every other failure LEDGER's
    * (`conwayToDijkstraLedgerPredFailure`). The reference tells the first by its text,
    * `"All inputs are spent. Transaction has probably already been included"`, which the node's
    * typed cause renders exactly for [[MempoolProblem.AllInputsSpent]], so it is told by that
    * cause.
    */
  def conwayToDijkstraMempoolPredFailure(
      failure: ConwayFailure[Dijkstra]
  ): DijkstraMempoolPredFailure[Dijkstra] = failure match
    case ConwayLedgerPredFailure.ConwayMempoolFailure(MempoolProblem.AllInputsSpent) =>
      DijkstraMempoolPredFailure.AllInputsAreSpent()
    case ConwayLedgerPredFailure.ConwayMempoolFailure(problem) =>
      DijkstraMempoolPredFailure.MempoolFailure(problem)
    case other: DijkstraLedger.ConwayFailure[Dijkstra] =>
      DijkstraMempoolPredFailure.LedgerFailure(
        DijkstraLedger.conwayToDijkstraLedgerPredFailure(other)
      )

  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  /** `mempoolTransition` (`Dijkstra/Rules/Mempool.hs:180-207`, cardano-ledger 9df9f98c): a
    * transaction none of whose top-level inputs the UTxO set holds is probably a duplicate
    * (`AllInputsAreSpent`), and then nothing else is checked (`whenFailureFreeDefault`); otherwise
    * Dijkstra's LEDGER runs as a sub-rule on the same environment, state and signal, its failures
    * and events embedded ([[EmbedLedgerInMempool]]).
    */
  def mempoolTransition[F[_]: Monad](
      env: LedgerEnv[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      context: DijkstraUtxoContext,
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, LedgerState[Dijkstra]],
      proposals: Stateful[F, Proposals[Dijkstra]],
      judge: Judge[F, MempoolPredFailure[Dijkstra]],
      events: Tell[F, Chain[MempoolEvent[Dijkstra]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      govDefect: Raise[F, GovDefect]
  ): F[Unit] =
    val embed = EmbedLedgerInMempool.dijkstra
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)
    val inputs = b.inputs[TxLevel.Top](t.body[TxLevel.Top](tx))
    for
      ledgerState <- state.get
      utxo = ledgerState.utxo.utxo.utxo
      _ <- Sts.predicate[F, MempoolPredFailure[Dijkstra]](
        inputs.exists(utxo.contains),
        DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]()
      )
      _ <- Sts.whenFailureFree[F, MempoolPredFailure[Dijkstra]](
        judge.nest(
          DijkstraLedger.dijkstraLedgerTransition[F](env, stAnnTx, context, verdicts, decoder)(using
            summon[Monad[F]],
            ask,
            state,
            proposals,
            judge.contramap(embed.wrapFailed),
            told(events)(embed.wrapEvent),
            evaluator,
            unavailable,
            stateDefect,
            defect,
            poolDefect,
            govDefect
          )
        )
      )
    yield ()
