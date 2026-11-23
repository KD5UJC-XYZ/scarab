// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.{Chain, NonEmptyVector}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Block,
  LedgerFailure,
  Phase2,
  PlutusStats,
  Redeemer,
  ScriptLocation,
  TranslationError,
  TxIn,
  Tx as FlatTx,
  TxOut as FlatTxOut
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPlutusPurpose
import xyz.kd5ujc.scarab.ledger.rules.TxDependencies
import xyz.kd5ujc.scarab.ledger.state.{Conway, LedgerState}
import xyz.kd5ujc.scarab.plutus.{EvalFailure, EvalResult}
import xyz.kd5ujc.scarab.protocol.Era

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

  /** A script of a block, located among its transactions ([[locate]]): its transaction, the
    * transaction's id, and every script UTXOS runs of it, in the order it runs them, each with its
    * redeemer as the flat rules name it and its request to the evaluator, and the outputs its
    * inputs and reference inputs spend and read as the node records them, which the flat rules
    * observe a transaction's scripts with (`Phase2.Observation`).
    */
  final case class Located(
      tx: Tx[TxLevel.Top, Conway],
      txId: Bytes,
      scripts: Vector[(Redeemer, PlutusWithContext)],
      spent: Map[TxIn, FlatTxOut],
      references: Map[TxIn, FlatTxOut]
  )

  /** The transaction of the block of transactions `txs` one of whose scripts `is` holds of, with
    * its scripts as UTXOS collects them in the ledger state `ls` the rules judge it in, with the
    * parameters `pp` and the clock `slotToPOSIXTime` (`collectPlutusScriptsWithContext`,
    * `Alonzo/Plutus/Evaluate.hs:83-118`, cardano-ledger f649f975, one purpose at a time, so that
    * each comes with its purpose), in the order it runs them (`merge` puts each in front,
    * `:168-182`). A script with its context is that of one transaction and one purpose: it is equal
    * to one script alone of the block collected against `ls`, since a transaction of the block
    * applied before it spent its inputs and a later one's context names another transaction. None
    * where `is` holds of no script.
    */
  def locate(
      txs: Vector[Tx[TxLevel.Top, Conway]],
      ls: LedgerState[Conway],
      pp: PParams[Conway],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  )(is: PlutusWithContext => Boolean): Option[Located] =
    val t = EraTx.conway
    val b = EraTxBody.conway
    val u = summon[AlonzoEraUTxO[Conway]]
    val c = summon[EraPlutusContext[Conway]]
    given t.bodyOf.Level[TxLevel.Top] = t.bodyOf.top
    val protVer = AlonzoEraPParams.conway.ppProtocolVersionL.get(pp)
    val costModels =
      PParams.lensHKD[Conway].andThen(AlonzoEraPParams.conway.hkdCostModelsL[Identity]).get(pp)
    val stored = ls.utxo.utxo.utxo
    def outputs(ins: Iterable[TxIn]): Map[TxIn, FlatTxOut] = ShelleyUtxo.restrictKeys(stored, ins)
    txs.iterator
      .flatMap { tx =>
        val body = t.body[TxLevel.Top](tx)
        val redeemers = t.witsOf.rdmrsTxWits(t.wits[TxLevel.Top](tx)).entries
        outputs(b.allInputs(body)).toVector
          .traverse((in, out) => StoredTxOut.conway.fromStored(out).toOption.map(in -> _))
          .map { outs =>
            val utxo = UTxO[Conway](outs.toMap)
            val used = AlonzoPlutusEvaluate
              .resolveNeededPlutusScriptsWithPurpose(
                protVer,
                u.getScriptsProvided[TxLevel.Top](utxo, tx)(using u.txOf.bodyOf.top),
                u.getScriptsNeeded[TxLevel.Top](utxo, body)(using u.txOf.bodyOf.top),
                Map.empty
              )
              ._2
            val lti = LedgerTxInfo[TxLevel.Top, Conway](
              protVer,
              slotToPOSIXTime,
              utxo,
              tx,
              used,
              LedgerLevelTxInfo.LedgerTopTxInfo(Map.empty)
            )
            val result = c.mkTxInfoResult(lti)
            val scripts = used.reverse.toVector.flatMap { one =>
              val key = ConwayPlutusPurpose.hoistPlutusPurpose[AsIxItem, AsIx](
                [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a)
              )(one._1)
              (
                redeemers.get(key),
                AlonzoPlutusEvaluate
                  .scriptsWithContextFromLedgerTxInfoWithResult(
                    lti.copy[TxLevel.Top, Conway](ltiScriptsUsed = List(one)),
                    result,
                    costModels
                  )
                  .toOption
                  .flatMap(_.headOption)
              ).mapN { case ((data, exUnits), pwc) =>
                Redeemer(key.tag, key.ix.value, data.originalBytes, exUnits) -> pwc
              }
            }
            Located(
              tx,
              b.id[TxLevel.Top](body),
              scripts,
              outputs(b.inputs[TxLevel.Top](body)),
              outputs(b.referenceInputs[TxLevel.Top](body))
            )
          }
      }
      .find(_.scripts.exists((_, pwc) => is(pwc)))

  /** The script `cause` the evaluator gave no verdict for, named as the flat rules name it
    * ([[LedgerFailure.Unavailable]]: the transaction's id and its redeemer,
    * `TxChecks.checkedPhase2`), among the block's transactions `txs`, from the ledger state `ls`
    * the rules judged it in ([[locate]]): the script at `place` among its transaction's, as UTXOS
    * runs them (`evalPlutusScripts`, `Alonzo/Plutus/Evaluate.hs:188-207`, cardano-ledger f649f975),
    * which two scripts of one transaction with equal contexts are told apart by (a Plutus V1 or V2
    * context names a certificate by its form, so two certificates of the same form have one). None
    * where the script there is not it, which the script the rules ran never is.
    */
  def named(
      txs: Vector[Tx[TxLevel.Top, Conway]],
      ls: LedgerState[Conway],
      pp: PParams[Conway],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      cause: ScriptVerdictUnavailable,
      place: Int
  ): Option[LedgerFailure.Unavailable] =
    locate(txs, ls, pp, slotToPOSIXTime)(_ == cause.plutus).flatMap(located =>
      located.scripts.lift(place).collect {
        case (redeemer, pwc) if pwc == cause.plutus =>
          LedgerFailure.Unavailable(located.txId, ScriptLocation.Phase2(redeemer), cause.reason)
      }
    )

  /** The transaction `located` as the flat rules read it, from its bytes (`Tx.fromBlock`), which
    * the node's phase-2 observation holds; none for bytes the flat decoder refuses, which a
    * transaction the era decoder read never has.
    */
  def flatTx(located: Located): Option[FlatTx] =
    val tx = located.tx
    FlatTx
      .fromBlock(
        Block.Transaction(
          tx.body.raw,
          tx.wits.raw,
          tx.auxData.map(_.raw),
          tx.isValid == IsPhase2Valid.Phase2Valid
        ),
        Era.Conway
      )
      .toOption

  /** What the node's phase-2 observation of a transaction holds of its scripts' evaluations
    * (`Phase2.Outcome`, as `Phase2.run` makes it): those run, in order, their failures, or the one
    * the evaluator gave no verdict for after them.
    */
  def outcome(
      evaluations: Vector[Phase2.Evaluation],
      unavailable: Option[(Redeemer, EvalFailure.Unsupported)]
  ): Phase2.Outcome =
    val report = Phase2.Report(evaluations)
    unavailable match
      case Some((redeemer, reason)) => Phase2.Outcome.Unavailable(report, redeemer, reason)
      case None =>
        NonEmptyVector
          .fromVector(evaluations.collect {
            case Phase2.Evaluation(r, request, EvalResult.Failure(reason, _, _)) =>
              Phase2.Failure(r, request, reason)
          })
          .fold[Phase2.Outcome](Phase2.Outcome.Passed(report))(Phase2.Outcome.Failed(report, _))

  /** The id of `tx`, its body's hash. */
  def txId(tx: Tx[TxLevel.Top, Conway]): Bytes =
    val t = EraTx.conway
    EraTxBody.conway.id[TxLevel.Top](t.body[TxLevel.Top](tx)(using t.bodyOf.top))(using
      t.bodyOf.top
    )

  /** The place of each transaction of `txs` among them by its id, the first where two share one,
    * which a block whose rules defer the second's scripts never has: its inputs were spent.
    */
  def places(txs: Vector[Tx[TxLevel.Top, Conway]]): Map[Bytes, Int] =
    txs.zipWithIndex.foldLeft(Map.empty[Bytes, Int]) { case (places, (tx, index)) =>
      val id = txId(tx)
      if places.contains(id) then places else places.updated(id, index)
    }

  /** Whether `tx` 's witnesses hold a redeemer: a transaction whose scripts a block's preparation
    * can defer.
    */
  def redeeming(tx: Tx[TxLevel.Top, Conway]): Boolean =
    val t = EraTx.conway
    t.witsOf.rdmrsTxWits(t.wits[TxLevel.Top](tx)(using t.bodyOf.top)).entries.nonEmpty

  /** What `tx` does to the UTxO set, as the dependency graph of a prepared block reads it
    * (`TxDependencies.Footprint`, the flat transaction's `Footprint.of`): its inputs, reference
    * inputs and collateral inputs read; flagged valid, its inputs consumed and its outputs created,
    * flagged invalid its collateral consumed and its collateral return created
    * (`mkCollateralTxIn`).
    */
  def footprint(tx: Tx[TxLevel.Top, Conway]): TxDependencies.Footprint =
    val t = EraTx.conway
    val b = EraTxBody.conway
    given t.bodyOf.Level[TxLevel.Top] = t.bodyOf.top
    val body = t.body[TxLevel.Top](tx)
    val id = b.id[TxLevel.Top](body)
    val (inputs, collateral) = (b.inputs[TxLevel.Top](body), b.collateralInputs(body))
    val outputs = b.outputs[TxLevel.Top](body)
    val reads = inputs ++ b.referenceInputs[TxLevel.Top](body) ++ collateral
    t.isValid(tx) match
      case IsPhase2Valid.Phase2Valid =>
        TxDependencies.Footprint(reads, inputs, TxIn.txouts(id, outputs).map(_._1))
      case IsPhase2Valid.Phase2Invalid =>
        TxDependencies.Footprint(
          reads,
          collateral,
          TxIn.collOuts(id, outputs.size, b.collateralReturn(body)).toVector.map(_._1)
        )
