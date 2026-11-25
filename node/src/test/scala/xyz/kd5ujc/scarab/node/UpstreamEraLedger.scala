// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.data.{NonEmptyChain, NonEmptyVector}
import cats.syntax.all.*
import munit.Location
import xyz.kd5ujc.scarab.codec.{Bytes, Value}
import xyz.kd5ujc.scarab.ledger.{Block, EpochGeometry, Horizon, PosixClock, TxEnv, Verdicts}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoStAnnTx,
  EraLedgerRun,
  EraTxError,
  EraUtxoRun,
  PParams,
  PParamsError,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayLedgerPredFailure,
  ConwayUtxo,
  ConwayUtxowPredFailure,
  LedgerEnv,
  LedgerEvent,
  LedgerPredFailure,
  LedgersEnv,
  LedgersEvent,
  LedgersPredFailure,
  ShelleyLedgersEvent,
  ShelleyLedgersPredFailure,
  UtxoContext,
  UtxoEnv,
  UtxoPredFailure,
  UtxowPredFailure
}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.ledger.state.{
  ChainAccountState,
  Conway,
  ConwayCertState,
  EpochNo,
  LedgerState,
  TxIx,
  UTxOState
}
import xyz.kd5ujc.scarab.models.runtime.Node
import xyz.kd5ujc.scarab.node.ntc.ConwayFailureCborCheck
import xyz.kd5ujc.scarab.plutus.ScarabEvaluator
import xyz.kd5ujc.scarab.protocol.StateLayout

/** Conway's LEDGER and LEDGERS on the era types (`EraLedgerRun`), and its UTXOW and UTXO
  * (`EraUtxoRun`), on the inputs of the upstream native judgments
  * (`UpstreamNativeLedgerConformanceSuite`, `UpstreamMultiLedgerConformanceSuite`,
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

  /** Whether `tx` keeps the four original parts of `n` as signed: its body's, its witnesses' and
    * its auxiliary data's bytes, and its validity flag. None of them is re-encoded.
    */
  def retains(n: Node, tx: ConwayTx): Boolean = n.value match
    case Value.Arr(Vector(body, witnesses, valid, auxiliary)) =>
      val aux = auxiliary.value match
        case Value.Null => None
        case _          => Some(auxiliary.original)
      tx.body.raw == body.original && tx.wits.raw == witnesses.original &&
      tx.auxData.map(_.raw) == aux &&
      (valid.value match
        case Value.Bool(flag) => tx.isValid == xyz.kd5ujc.scarab.ledger.era.IsPhase2Valid.of(flag)
        case _                => false)
    case _ => false

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

  private def utxoOf(state: UTxOState[Conway]): Either[Unread, UTxO[Conway]] =
    ConwayUTxO.fromStored(state.utxo.utxo.toMap).leftMap(Unread.Outputs(_))

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
      utxo <- utxoOf(before.utxo)
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

  /** UTXO on `tx` from `before` at the root: its environment the fixture's three fields. */
  def utxo(
      globals: Globals,
      slot: BigInt,
      flatPp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      cert: ConwayCertState,
      before: UTxOState[Conway],
      tx: ConwayTx
  ): Either[
    Unread,
    Either[EraUtxoRun.Defect, Either[NonEmptyChain[UtxoPredFailure[Conway]], UTxOState[Conway]]]
  ] =
    for
      pp <- params(flatPp)
      ctx <- context(globals)
      utxo <- utxoOf(before)
    yield EraUtxoRun
      .run(
        UtxoEnv[Conway](slot, pp, cert),
        AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](clock(globals).posix, pp, utxo, Map.empty, tx),
        ctx,
        before,
        ScarabEvaluator.default,
        globals = globals
      )
      .map(_.map(_._1))

  /** UTXOW on `tx` from `before` at the root: its environment the fixture's three fields. */
  def utxow(
      globals: Globals,
      slot: BigInt,
      flatPp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      cert: ConwayCertState,
      before: UTxOState[Conway],
      tx: ConwayTx
  ): Either[
    Unread,
    Either[
      EraUtxoRun.Utxow.WDefect,
      Either[NonEmptyChain[UtxowPredFailure[Conway]], UTxOState[Conway]]
    ]
  ] =
    for
      pp <- params(flatPp)
      ctx <- context(globals)
      utxo <- utxoOf(before)
    yield EraUtxoRun.Utxow
      .run(
        UtxoEnv[Conway](slot, pp, cert),
        AlonzoStAnnTx.mkAlonzoStAnnTx[Conway](clock(globals).posix, pp, utxo, Map.empty, tx),
        ctx,
        before,
        ScarabEvaluator.default,
        Verdicts.Direct,
        ScarabEvaluator.defaultDecoder,
        globals = globals
      )
      .map(_.map(_._1))

  /** UTXOW's failures as LEDGER's, which LEDGER embeds as they are (`ConwayUtxowFailure`,
    * `Conway/Rules/Ledger.hs:118,139`, cardano-ledger f649f975).
    */
  def ofUtxow(failures: Vector[UtxowPredFailure[Conway]]): Vector[LedgerPredFailure[Conway]] =
    failures.map(ConwayLedgerPredFailure.ConwayUtxowFailure(_))

  /** UTXO's failures as LEDGER's, through UTXOW's embedding and LEDGER's. */
  def ofUtxo(failures: Vector[UtxoPredFailure[Conway]]): Vector[LedgerPredFailure[Conway]] =
    ofUtxow(failures.map(ConwayUtxowPredFailure.UtxoFailure(_)))

  /** LEDGER's failures as the node writes a refusal of them, the era's own encoder
    * (`ConwayFailureCbor`): the hard-fork combinator's `ApplyTxErr`, which holds each failure as
    * the reference encodes it.
    */
  def refusal(failures: Vector[LedgerPredFailure[Conway]])(using Location): Bytes =
    ConwayFailureCborCheck.direct(failures)

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
