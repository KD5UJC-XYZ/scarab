// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.{Chain, NonEmptyList, NonEmptyVector}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Block, LedgerFailure, PlutusStats, Redeemer, ScriptLocation, TxIn}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraStAnnTx
import xyz.kd5ujc.scarab.ledger.rules.TxDependencies
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.plutus.{EvalFailure, EvalRequest, EvalResult}

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

  /** A script of a batch its top level's UTXOS ran, as the node's phase-2 observer is told of it
    * ([[Observation]]): where it is in the batch ([[located]]: the top level's by its redeemer, a
    * sub-transaction's by the sub-transaction's id and its redeemer), the request the evaluator was
    * asked, which holds the script, its arguments (its context, and the datum and the redeemer
    * where its language passes them), the cost model, the protocol version and the redeemer's
    * units, and the evaluator's result.
    */
  final case class Evaluation(location: ScriptLocation, request: EvalRequest, result: EvalResult)

  /** What the scripts of a batch came to, as the node's phase-2 observer is told of them. */
  enum Outcome:
    /** Every script UTXOS collected ran, in the order it runs them; the batch's scripts pass if
      * each passed.
      */
    case Ran(evaluations: NonEmptyVector[Evaluation])

    /** The evaluator gave no verdict for the script at `location`, asked `request`, after those
      * that ran before it (`evaluations`): UTXOS ran no script after it, and the block has no
      * verdict.
      */
    case Unavailable(
        evaluations: Vector[Evaluation],
        location: ScriptLocation,
        request: EvalRequest,
        reason: EvalFailure.Unsupported
    )

    /** UTXOS could not collect the batch's scripts with their contexts, and ran none: the failures
      * it records (`CollectErrors`).
      */
    case Uncollected(errors: NonEmptyList[CollectError[Dijkstra]])

  /** The node's phase-2 observation of a batch of a Dijkstra block (`--plutus-cases`), Scarab's
    * own, as the other eras' (`Phase2.Observation`): the batch's id, its bytes as the block holds
    * them, the block's slot, and what its scripts came to. Each script is observed with the request
    * its context was evaluated with, which holds what an offline evaluation of it needs; the
    * outputs the batch spends and reads are not held, since its sub-transactions' rules spend
    * theirs before its top level's UTXOS runs the scripts.
    */
  final case class Observation(
      id: Bytes,
      transaction: Block.Transaction,
      slot: BigInt,
      outcome: Outcome
  )

  /** The node's observation of the batch `tx` of a block at `slot` whose scripts came to `outcome`.
    */
  def observation(tx: Tx[TxLevel.Top, Dijkstra], slot: BigInt, outcome: Outcome): Observation =
    Observation(
      txId(tx),
      Block.Transaction(
        tx.body.raw,
        tx.wits.raw,
        tx.auxData.map(_.raw),
        t.isValid(tx) == IsPhase2Valid.Phase2Valid
      ),
      slot,
      outcome
    )

  /** What the scripts `located` of a batch ([[located]], in the order UTXOS runs them) came to,
    * once those that ran gave `results` in turn, each with the request it was asked: none while a
    * script of them is still to run, or where the results are more than the scripts; otherwise
    * every script with its result ([[Outcome.Ran]]), or those before the first the evaluator gave
    * no verdict for, and that one ([[Outcome.Unavailable]]), after which UTXOS runs none
    * (`evalPlutusScripts`, `Alonzo/Plutus/Evaluate.hs:184-203`, cardano-ledger 9df9f98c).
    */
  def outcome(
      located: Vector[(ScriptLocation, PlutusWithContext)],
      results: Vector[(EvalRequest, EvalResult)]
  ): Option[Outcome] =
    val evaluations = located.zip(results).map { case ((location, _), (request, result)) =>
      Evaluation(location, request, result)
    }
    if results.sizeIs > located.size then None
    else
      evaluations.lastOption.flatMap {
        case Evaluation(
              location,
              request,
              EvalResult.Failure(reason: EvalFailure.Unsupported, _, _)
            ) =>
          Some(Outcome.Unavailable(evaluations.dropRight(1), location, request, reason))
        case Evaluation(_, _, EvalResult.Success(_, _) | EvalResult.Failure(_, _, _)) =>
          Option
            .when(results.sizeIs == located.size)(NonEmptyVector.fromVector(evaluations))
            .flatten
            .map(Outcome.Ran(_))
      }

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
        val subs = top.dsattSubTransactions.value.toVector.traverse {
          case sub: DijkstraStAnnTx.SubTx =>
            given BothLevels[TxLevel.Sub] = BothLevels.Sub
            val id = b.id[TxLevel.Sub](t.body[TxLevel.Sub](sub.dsastTx))
            level[TxLevel.Sub](
              sub.dsastTx,
              sub.dsastScriptsProvided.value,
              sub.dsastScriptsNeeded.value,
              sub.dsastPlutusScriptsWithContext.value,
              ScriptLocation.SubPhase2(id, _)
            )
        }
        (
          level[TxLevel.Top](
            top.dsattTx,
            top.dsattScriptsProvided.value,
            top.dsattScriptsNeeded.value,
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

  /** The id of the batch `tx`, its top level's body's hash. */
  def txId(tx: Tx[TxLevel.Top, Dijkstra]): Bytes =
    given BothLevels[TxLevel.Top] = BothLevels.Top
    b.id[TxLevel.Top](t.body[TxLevel.Top](tx))

  /** The place of each batch of `txs` among them by its id ([[txId]]), the first where two share
    * one, as Conway's ([[ConwayPhase2.places]]): a block whose rules reach the second's scripts
    * never has one, its inputs and collateral spent by the first.
    */
  def places(txs: Vector[Tx[TxLevel.Top, Dijkstra]]): Map[Bytes, Int] =
    txs.zipWithIndex.foldLeft(Map.empty[Bytes, Int]) { case (places, (tx, index)) =>
      val id = txId(tx)
      if places.contains(id) then places else places.updated(id, index)
    }

  /** Whether a level of the batch `tx`, its top level or a sub-transaction, holds a redeemer in its
    * own witnesses: a batch whose scripts a block's preparation can defer, which its top level's
    * UTXOS runs together.
    */
  def redeeming(tx: Tx[TxLevel.Top, Dijkstra]): Boolean =
    given BothLevels[TxLevel.Top] = BothLevels.Top
    def holds[L <: TxLevel](level: Tx[L, Dijkstra])(using BothLevels[L]): Boolean =
      t.witsOf.rdmrsTxWits(t.wits[L](level)).entries.nonEmpty
    holds[TxLevel.Top](tx) || b
      .subTransactions(t.body[TxLevel.Top](tx))
      .toVector
      .exists(sub => holds[TxLevel.Sub](sub)(using BothLevels.Sub))

  /** What the batch `tx` does to the UTxO set, as the dependency graph of a prepared block reads it
    * (`TxDependencies.Footprint`, as Conway's, [[ConwayPhase2.footprint]], at every level). Every
    * level's script context is built from the set before the batch (`mkStAnnTx` on `utxoL` before
    * LEDGER, `Babbage/Rules/Ledgers.hs:132-133`; `ltiUTxO = utxo` at the top level and each
    * sub-transaction, `mkDijkstraStAnnTopTx` and `mkDijkstraStAnnSubTx`,
    * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:111-219`, cardano-ledger 9df9f98c), and of
    * the ledger's state the translations read only that set (`ltiUTxO`, beside the parameters'
    * version, the era history and the system start, which a block does not change: `toPlutusTxInfo`
    * of V1 to V4 and the spending datum, `Dijkstra/TxInfo.hs:338-345, 397-405, 438-448, 583-589,
    * 945`). So it reads `allInputs` (`dijkstraAllInputsTxBodyF`): the top level's inputs,
    * collateral inputs and reference inputs, and each sub-transaction's inputs and reference
    * inputs. Flagged valid, it consumes the top level's inputs and each sub-transaction's, and
    * creates the top level's outputs and each sub-transaction's under the sub-transaction's own id
    * (`updateUTxOState`, `Babbage/Rules/Utxo.hs:449-486`; `updateUTxOAndInstantStake`,
    * `Dijkstra/Rules/SubUtxo.hs:255-259`); flagged invalid, it consumes the top level's collateral
    * and creates its collateral return (`mkCollateralTxIn`), and no sub-transaction changes the set
    * (`Phase2Invalid -> pure utxoState`, `SubUtxo.hs:260-261`).
    */
  def footprint(tx: Tx[TxLevel.Top, Dijkstra]): TxDependencies.Footprint =
    given BothLevels[TxLevel.Top] = BothLevels.Top
    val body = t.body[TxLevel.Top](tx)
    val id = b.id[TxLevel.Top](body)
    val outputs = b.outputs[TxLevel.Top](body)
    val subs =
      b.subTransactions(body).toVector.map(sub => t.body[TxLevel.Sub](sub)(using BothLevels.Sub))
    val reads: Set[TxIn] = b.allInputs(body)
    t.isValid(tx) match
      case IsPhase2Valid.Phase2Valid =>
        TxDependencies.Footprint(
          reads,
          b.inputs[TxLevel.Top](body) ++ subs.flatMap(sub =>
            b.inputs[TxLevel.Sub](sub)(using BothLevels.Sub)
          ),
          TxIn.txouts(id, outputs).map(_._1) ++ subs.flatMap { sub =>
            given BothLevels[TxLevel.Sub] = BothLevels.Sub
            TxIn.txouts(b.id[TxLevel.Sub](sub), b.outputs[TxLevel.Sub](sub)).map(_._1)
          }
        )
      case IsPhase2Valid.Phase2Invalid =>
        TxDependencies.Footprint(
          reads,
          b.collateralInputs(body),
          TxIn.collOuts(id, outputs.size, b.collateralReturn(body)).toVector.map(_._1)
        )

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
