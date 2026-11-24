// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.Chain
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, PlutusStats, Redeemer, ScriptLocation}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraStAnnTx
import xyz.kd5ujc.scarab.ledger.state.Dijkstra

/** The node's phase-2 observations of a block Dijkstra's era rules apply ([[DijkstraBlockRun]]):
  * Scarab's own, which the reference has none of, as Conway's are ([[ConwayPhase2]]). A Dijkstra
  * transaction is a batch, its top level with its sub-transactions, whose scripts its top level's
  * UTXOS collects and runs together, the top level's first, then each sub-transaction's in order
  * (`plutusScriptsWithContextStAnnTx`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/UTxO.hs:
  * 258-276`, cardano-ledger 9df9f98c); the sub-transactions' own rules run none.
  */
object DijkstraPhase2:
  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra

  /** The event of Dijkstra's BBODY as the counters read it, if they read it: UTXOS's scripts that
    * passed and those that failed, and the change of the deposits a valid transaction's UTXO tells
    * after them (`updateUTxOState`, Babbage's), as Conway's ([[ScriptsTold.of]]). SUBLEDGERS tells
    * no script: a sub-transaction's run with its top level's.
    */
  def told(event: BbodyEvent[Dijkstra]): Option[ScriptsTold] = event match
    case AlonzoBbodyEvent.ShelleyInAlonzoEvent(
          ShelleyBbodyEvent.LedgersEvent(ShelleyLedgersEvent.LedgerEvent(ledger))
        ) =>
      ledger match
        case DijkstraLedgerEvent.UtxowEvent(
              AlonzoUtxowEvent.WrappedShelleyEraEvent(ShelleyUtxowEvent.UtxoEvent(utxo))
            ) =>
          utxo match
            case AlonzoUtxoEvent.UtxosEvent(ConwayUtxosEvent.SuccessfulPlutusScriptsEvent(ps)) =>
              Some(ScriptsTold.Passed(ps.size))
            case AlonzoUtxoEvent.UtxosEvent(ConwayUtxosEvent.FailedPlutusScriptsEvent(ps)) =>
              Some(ScriptsTold.Failed(ps.size))
            case AlonzoUtxoEvent.TotalDeposits(_, _) => Some(ScriptsTold.Applied)
            case AlonzoUtxoEvent.TxUTxODiff(_, _)    => None
        case DijkstraLedgerEvent.EntitiesEvent(_) | DijkstraLedgerEvent.GovEvent(_) |
            DijkstraLedgerEvent.SubLedgersEvent(_) =>
          None

  /** The node's phase-2 counters ([[PlutusStats]]) of the block of transactions `txs` the rules
    * admitted, telling `told` of BBODY's events, in their order, as Conway's are counted
    * ([[ScriptsTold.counted]]): `succeeded` and `failed` count the transactions, each with its
    * sub-transactions, and `scripts` every script of either, its sub-transactions' among them;
    * `integrityChecked` the bodies, top level and sub-transactions, that declare a script integrity
    * hash, each of which UTXOW or SUBUTXOW recomputed whatever the flag and, the block admitted,
    * found equal (`checkScriptIntegrityHash`, `Dijkstra/Rules/Utxow.hs:310` and
    * `Dijkstra/Rules/SubUtxow.hs:258`, cardano-ledger 9df9f98c).
    */
  def counted(txs: Vector[Tx[TxLevel.Top, Dijkstra]], told: Chain[ScriptsTold]): PlutusStats =
    val integrity = txs.foldLeft(0L) { (count, tx) =>
      val body = t.body[TxLevel.Top](tx)(using BothLevels.Top)
      val subs = b
        .subTransactions(body)
        .toVector
        .map(sub =>
          b.scriptIntegrityHash[TxLevel.Sub](t.body[TxLevel.Sub](sub)(using BothLevels.Sub))(using
            BothLevels.Sub
          )
        )
      (b.scriptIntegrityHash[TxLevel.Top](body)(using BothLevels.Top) +: subs)
        .foldLeft(count)((n, declared) => if declared.isDefined then n + 1 else n)
    }
    ScriptsTold.counted(told, integrity)

  /** The scripts of the batch `stAnnTx`, in the order its top level's UTXOS runs them, each with
    * where it is ([[ScriptLocation]]): a script of the top level by its redeemer, one of a
    * sub-transaction by the sub-transaction's id and its redeemer, which its own witnesses hold.
    * Each level's scripts are those it collected (`dsattPlutusScriptsWithContext`,
    * `dsastPlutusScriptsWithContext`), with the purposes it resolved
    * (`resolveNeededPlutusScriptsWithPurpose`, `Alonzo/UTxO.hs:431-460`, cardano-ledger 9df9f98c)
    * at the parameters `pp`, the last first, as `merge` puts each in front
    * (`scriptsWithContextFromLedgerTxInfoWithResult`, `Alonzo/Plutus/Evaluate.hs:131-182`); none
    * where a level's scripts were not collected, which no batch whose scripts run has, or do not
    * line up with its purposes and redeemers (each script's hash its purpose's, its units its
    * redeemer's).
    */
  def located(
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      pp: PParams[Dijkstra]
  ): Option[Vector[(ScriptLocation, PlutusWithContext)]] =
    val protVer = EraPParams.dijkstra.ppProtocolVersionL.get(pp)
    def level[L <: TxLevel](
        tx: Tx[L, Dijkstra],
        provided: ScriptsProvided[Dijkstra],
        needed: AlonzoScriptsNeeded[Dijkstra],
        collected: Either[?, List[PlutusWithContext]],
        at: Redeemer => ScriptLocation
    )(using BothLevels[L]): Option[Vector[(ScriptLocation, PlutusWithContext)]] =
      collected.toOption.flatMap { pwcs =>
        val used = AlonzoPlutusEvaluate
          .resolveNeededPlutusScriptsWithPurpose(protVer, provided, needed, Map.empty)
          ._2
        val redeemers = t.witsOf.rdmrsTxWits(t.wits[L](tx)).entries
        Option
          .when(used.sizeIs == pwcs.size)(used.reverse.zip(pwcs).toVector)
          .flatMap(_.traverse { case ((purpose, runnable), pwc) =>
            val key = EraScript.dijkstra.hoistPlutusPurpose[AsIxItem, AsIx](
              [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a)
            )(purpose)
            redeemers
              .get(key)
              .filter((_, units) =>
                runnable.plutusRunnable.plutusRunnableScriptHash ==
                  pwc.pwcScript.plutusRunnableScriptHash && units == pwc.pwcExUnits
              )
              .map((data, units) =>
                at(Redeemer(key.tag, key.ix.value, data.originalBytes, units)) -> pwc
              )
          })
      }
    stAnnTx match
      case top: DijkstraStAnnTx.TopTx =>
        val subs = top.dsattSubTransactions.toVector.traverse { case sub: DijkstraStAnnTx.SubTx =>
          given BothLevels[TxLevel.Sub] = BothLevels.Sub
          val id = b.id[TxLevel.Sub](t.body[TxLevel.Sub](sub.dsastTx))
          level[TxLevel.Sub](
            sub.dsastTx,
            sub.dsastScriptsProvided,
            sub.dsastScriptsNeeded,
            sub.dsastPlutusScriptsWithContext.value,
            ScriptLocation.SubPhase2(id, _)
          )
        }
        (
          level[TxLevel.Top](
            top.dsattTx,
            top.dsattScriptsProvided,
            top.dsattScriptsNeeded,
            top.dsattPlutusScriptsWithContext.value,
            ScriptLocation.Phase2(_)
          )(using BothLevels.Top),
          subs
        ).mapN((ours, theirs) => ours ++ theirs.flatten)

  /** The id of the batch `stAnnTx`, its top level's body's hash: the transaction the block holds
    * and its peers name.
    */
  def txId(stAnnTx: StAnnTx[TxLevel.Top, Dijkstra]): Bytes =
    given BothLevels[TxLevel.Top] = BothLevels.Top
    b.id[TxLevel.Top](t.body[TxLevel.Top](t.txStAnnTx[TxLevel.Top](stAnnTx)))

  /** The script `cause` the evaluator gave no verdict for, named as the other eras name it
    * ([[LedgerFailure.Unavailable]]): by its batch's id and its location ([[located]]), the script
    * at `place` among the batch's, in the order UTXOS runs them (`evalPlutusScripts`,
    * `Alonzo/Plutus/Evaluate.hs:184-203`, cardano-ledger 9df9f98c), which two scripts of one batch
    * with equal contexts are told apart by. None where the script there is not it, which the script
    * the rules ran never is.
    */
  def named(
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      pp: PParams[Dijkstra],
      cause: ScriptVerdictUnavailable,
      place: Int
  ): Option[LedgerFailure.Unavailable] =
    located(stAnnTx, pp).flatMap(_.lift(place)).collect {
      case (location, pwc) if pwc == cause.plutus =>
        LedgerFailure.Unavailable(txId(stAnnTx), location, cause.reason)
    }
