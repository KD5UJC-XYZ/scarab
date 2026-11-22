// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Value
import xyz.kd5ujc.scarab.ledger.{
  Block,
  EpochGeometry,
  Horizon,
  PosixClock,
  PredicateFailure,
  TxEnv,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoStAnnTx,
  EraLedgerRun,
  EraTx,
  EraTxBody,
  EraTxError,
  EraUTxO,
  PParams,
  PParamsError,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayGovPredFailure,
  ConwayUtxo,
  ConwayUtxowPredFailure,
  LedgerEnv,
  LedgerEvent,
  LedgerPredFailure,
  LedgerUnprojected,
  LedgersEnv,
  LedgersEvent,
  LedgersPredFailure,
  ShelleyLedgersEvent,
  ShelleyLedgersPredFailure,
  UtxoContext
}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{ChainAccountState, Conway, EpochNo, LedgerState, TxIx}
import xyz.kd5ujc.scarab.models.runtime.Node
import xyz.kd5ujc.scarab.plutus.ScarabEvaluator
import xyz.kd5ujc.scarab.protocol.StateLayout

/** Conway's LEDGER and LEDGERS on the era types (`EraLedgerRun`) on the inputs of the upstream
  * native judgments (`UpstreamNativeLedgerConformanceSuite`, `UpstreamMultiLedgerConformanceSuite`,
  * `UpstreamFreshLedgerConformanceSuite`), each as the root of its judgment, as the reference's
  * hook runs it: the transaction read by Conway's decoder from its original parts, the parameters
  * read into the era's, and, as the fixtures record no era history, an epoch information of one era
  * without an end at the suites' geometry, as `fixedEpochInfo` is.
  */
private[node] object UpstreamEraLedger:
  /** Why the era-typed inputs could not be made from a fixture: none of them is the rule's. */
  enum Unread:
    case Shape(node: Node)
    case Transaction(cause: EraTxError)
    case Parameters(cause: PParamsError)
    case Outputs(cause: EraTxError)
    case Index(index: Int)

  /** A transaction of four original parts (body, witnesses, validity flag, auxiliary data). */
  def transaction(n: Node): Either[Unread, ConwayTx] = n.value match
    case Value.Arr(Vector(body, witnesses, valid, auxiliary)) =>
      valid.value match
        case Value.Bool(flag) =>
          val aux = auxiliary.value match
            case Value.Null => None
            case _          => Some(auxiliary.original)
          ConwayTx
            .decode(
              Block.Transaction(body.original, witnesses.original, aux, flag),
              StateLayout.Node11_1
            )
            .leftMap(Unread.Transaction(_))
        case _ => Left(Unread.Shape(n))
    case _ => Left(Unread.Shape(n))

  /** The clock of the suites' geometry from their ledger's system start, which the annotation
    * translates slots with.
    */
  def clock(globals: Globals): PosixClock = PosixClock.total(globals.systemStart, globals.geometry)

  /** One era from the start at `geometry`, without an end, as `fixedEpochInfo` is. */
  def epochInfo(geometry: EpochGeometry): Horizon =
    val window = geometry.stabilityWindow.toBigInt
    Horizon(
      NonEmptyVector.one(
        Horizon.Era(
          Horizon.Edge(0, 0, 0),
          None,
          Horizon.Params(geometry.epochLength, geometry.slotLengthMillis, window, window)
        )
      )
    )

  private def context(globals: Globals): Either[Unread, UtxoContext[Conway]] =
    Right(
      UtxoContext[Conway](
        globals.networkId,
        globals.systemStart,
        epochInfo(globals.geometry),
        TxEnv.Validation.All,
        ConwayUtxo.injectShelleyFailure(globals.ledger.referenceBuild)
      )
    )

  private def params(flat: xyz.kd5ujc.scarab.ledger.ProtocolParams) =
    ConwayPParams.fromStored(flat).map(PParams[Conway](_)).leftMap(Unread.Parameters(_))

  private def utxoOf(state: LedgerState[Conway]): Either[Unread, UTxO[Conway]] =
    ConwayUTxO.fromStored(state.utxo.utxo.utxo.toMap).leftMap(Unread.Outputs(_))

  /** LEDGER on `tx` from `before` at the root: its environment the fixture's five fields. */
  def ledger(
      globals: Globals,
      slot: BigInt,
      epoch: Option[EpochNo],
      index: Int,
      flatPp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      account: ChainAccountState,
      before: LedgerState[Conway],
      tx: ConwayTx
  ): Either[Unread, EraLedgerRun.Judged[LedgerPredFailure[Conway], LedgerEvent[Conway]]] =
    for
      pp <- params(flatPp)
      ix <- TxIx.txIxFromIntegral(index).toRight(Unread.Index(index))
      ctx <- context(globals)
      utxo <- utxoOf(before)
    yield EraLedgerRun.ledger(
      globals,
      LedgerEnv[Conway](slot, epoch, ix, pp, account),
      AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](clock(globals).posix, pp, utxo, Map.empty, tx),
      ctx,
      before,
      ScarabEvaluator.default,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder
    )

  /** LEDGERS on `txs` from `before` at the root: its environment the fixture's four fields. */
  def ledgers(
      globals: Globals,
      slot: BigInt,
      epoch: EpochNo,
      flatPp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      account: ChainAccountState,
      before: LedgerState[Conway],
      txs: Vector[ConwayTx]
  ): Either[Unread, EraLedgerRun.Judged[LedgersPredFailure[Conway], LedgersEvent[Conway]]] =
    for
      pp <- params(flatPp)
      ctx <- context(globals)
    yield EraLedgerRun.ledgers(
      globals,
      LedgersEnv[Conway](slot, epoch, pp, account),
      txs,
      ctx,
      before,
      ScarabEvaluator.default,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder,
      clock(globals).posix
    )

  /** LEDGER's failures in the node's flat form (`EraLedgerRun.flat`), which the node encodes as the
    * reference does: a missing redeemer's purpose and a proposal's action read from whichever of
    * `txs` gives them (LEDGERS's failure does not say which transaction it is of), the outputs
    * `before` holds.
    */
  def stored(
      txs: Vector[ConwayTx],
      before: LedgerState[Conway],
      failures: Vector[LedgerPredFailure[Conway]]
  ): Either[Either[Unread, LedgerUnprojected], Vector[PredicateFailure]] =
    val t = EraTx.conway
    val b = EraTxBody.conway
    val u = EraUTxO.conway
    utxoOf(before).leftMap(Left(_)).flatMap { utxo =>
      val bodies = txs.map(tx => t.body[TxLevel.Top](tx)(using t.bodyOf.top))
      val purposes = bodies.map(body =>
        ConwayUtxowPredFailure.storedPurposes(
          u.getScriptsNeeded[TxLevel.Top](utxo, body)(using u.txOf.bodyOf.top),
          utxo
        )
      )
      val read = ConwayGovPredFailure.readOf(
        bodies.flatMap(body => b.proposalProcedures[TxLevel.Top](body)(using b.top).toVector)
      )
      failures.traverse(f =>
        EraLedgerRun.flat(f, p => purposes.collectFirstSome(_(p)), read).leftMap(Right(_))
      )
    }

  /** LEDGERS's failures as LEDGER's, which the reference encodes as they are. */
  def unwrapped(failures: Vector[LedgersPredFailure[Conway]]): Vector[LedgerPredFailure[Conway]] =
    failures.map { case ShelleyLedgersPredFailure.LedgerFailure(f) => f }

  /** What an era-typed judgment made of the inputs: LEDGER's failures in the reference's order, or
    * the state after with LEDGER's events.
    */
  enum Outcome:
    case Refused(failures: Vector[LedgerPredFailure[Conway]])
    case Admitted(state: LedgerState[Conway], events: Vector[LedgerEvent[Conway]])

  /** LEDGER's judgment as an [[Outcome]], or its defect. */
  def ofLedger(
      judged: EraLedgerRun.Judged[LedgerPredFailure[Conway], LedgerEvent[Conway]]
  ): Either[EraLedgerRun.Defect, Outcome] =
    judged.map(
      _.fold(
        fs => Outcome.Refused(fs.toChain.toVector),
        (state, events) => Outcome.Admitted(state, events.toVector)
      )
    )

  /** LEDGERS's judgment as an [[Outcome]], its failures and events LEDGER's unwrapped. */
  def ofLedgers(
      judged: EraLedgerRun.Judged[LedgersPredFailure[Conway], LedgersEvent[Conway]]
  ): Either[EraLedgerRun.Defect, Outcome] =
    judged.map(
      _.fold(
        fs => Outcome.Refused(unwrapped(fs.toChain.toVector)),
        (state, events) =>
          Outcome.Admitted(
            state,
            events.toVector.map { case ShelleyLedgersEvent.LedgerEvent(e) => e }
          )
      )
    )
