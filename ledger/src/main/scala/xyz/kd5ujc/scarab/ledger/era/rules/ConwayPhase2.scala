// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.Chain
import xyz.kd5ujc.scarab.ledger.PlutusStats
import xyz.kd5ujc.scarab.ledger.era.{EraTxBody, EraTx, Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.state.Conway

/** What of Conway's BBODY events the node's phase-2 counters read ([[ConwayPhase2.counted]]):
  * UTXOS's scripts that passed and those that failed, and UTXO's change of the deposits, which it
  * tells for every transaction flagged valid, after its scripts (`updateUTxOState`,
  * `Babbage/Rules/Utxo.hs:449-486`, cardano-ledger f649f975, through Shelley's `updateUTxOState`,
  * `Shelley/Rules/Utxo.hs`) and never for one flagged invalid.
  */
enum ScriptsTold:
  /** `SuccessfulPlutusScriptsEvent`: the scripts of a transaction that passed. */
  case Passed(scripts: Int)

  /** `FailedPlutusScriptsEvent`: the scripts of a transaction flagged invalid that failed. */
  case Failed(scripts: Int)

  /** `TotalDeposits`: a transaction flagged valid changed the UTxO state. */
  case Applied

object ScriptsTold:
  /** The event of Conway's BBODY as the counters read it, if they read it. */
  def of(event: BbodyEvent[Conway]): Option[ScriptsTold] = event match
    case AlonzoBbodyEvent.ShelleyInAlonzoEvent(
          ShelleyBbodyEvent.LedgersEvent(ShelleyLedgersEvent.LedgerEvent(ledger))
        ) =>
      ledger match
        case ConwayLedgerEvent.UtxowEvent(
              AlonzoUtxowEvent.WrappedShelleyEraEvent(ShelleyUtxowEvent.UtxoEvent(utxo))
            ) =>
          utxo match
            case AlonzoUtxoEvent.UtxosEvent(ConwayUtxosEvent.SuccessfulPlutusScriptsEvent(ps)) =>
              Some(Passed(ps.size))
            case AlonzoUtxoEvent.UtxosEvent(ConwayUtxosEvent.FailedPlutusScriptsEvent(ps)) =>
              Some(Failed(ps.size))
            case AlonzoUtxoEvent.TotalDeposits(_, _) => Some(Applied)
            case AlonzoUtxoEvent.TxUTxODiff(_, _)    => None
        case ConwayLedgerEvent.CertsEvent(_) | ConwayLedgerEvent.GovEvent(_) => None

/** The node's phase-2 observations of a block Conway's era rules apply: Scarab's own, which the
  * reference has none of, and which the flat rules make from their evaluations (`TxChecks.phase2`,
  * `EraBlock.Plan`).
  */
object ConwayPhase2:
  /** The node's phase-2 counters ([[PlutusStats]]) of the block of transactions `txs` the rules
    * admitted, telling `told` of BBODY's events, in their order:
    *   - a transaction's scripts are told by its UTXOS, those that passed, then, for one flagged
    *     invalid, those that failed (`expectScriptsToPass` and `babbageEvalScriptsTxInvalid`,
    *     `Babbage/Rules/Utxos.hs:130-155` and `:192-222`, cardano-ledger f649f975), and one flagged
    *     valid changes the UTxO state after them ([[ScriptsTold.Applied]]): so the scripts told
    *     since the last transaction ended are the next one's, which ends at its failed scripts or
    *     at its change of the state;
    *   - `succeeded` counts the transactions flagged valid whose scripts passed and `failed` those
    *     flagged invalid whose scripts failed (in an admitted block, every one flagged invalid), as
    *     the flat rules count a verdict equal to the flag; `scripts` every script of either;
    *   - `integrityChecked` the transactions whose body declares a script integrity hash, which
    *     UTXOW recomputed and, the block admitted, found equal; one whose scripts ran declares one,
    *     since its redeemers are part of the hash it is checked against.
    */
  def counted(txs: Vector[Tx[TxLevel.Top, Conway]], told: Chain[ScriptsTold]): PlutusStats =
    final case class Tally(stats: PlutusStats, passed: Long)
    val t = EraTx.conway
    val b = EraTxBody.conway
    val integrity = txs.count(tx =>
      b.scriptIntegrityHash[TxLevel.Top](t.body[TxLevel.Top](tx)(using t.bodyOf.top))(using b.top)
        .isDefined
    )
    told
      .foldLeft(Tally(PlutusStats.zero.copy(integrityChecked = integrity.toLong), 0L)) {
        case (tally, ScriptsTold.Passed(n)) => tally.copy(passed = tally.passed + n)
        case (Tally(stats, passed), ScriptsTold.Failed(n)) =>
          Tally(stats.copy(failed = stats.failed + 1, scripts = stats.scripts + passed + n), 0L)
        case (tally @ Tally(stats, passed), ScriptsTold.Applied) =>
          if passed == 0 then tally
          else
            Tally(stats.copy(succeeded = stats.succeeded + 1, scripts = stats.scripts + passed), 0L)
      }
      .stats
