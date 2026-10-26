// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.ledger.ForgeAssemblyProblem
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, ForgeError, Header, LedgerFailure, VrfCert}
import xyz.kd5ujc.scarab.ledger.{LedgerGlobals, PosixClock, ProtocolParams, RefScripts, TxEnv}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.plutus.{ExUnits, ScalusEvaluator, ScriptDecoder, ScriptEvaluator}
import cats.Id
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.{Capacity, Entry, MempoolLedger}

/** Conway block assembly, pure: choose the transactions that fit the protocol limits, encode the
  * body, the header body and the block. Every function is deterministic in its arguments; the
  * effects (clock, mempool, KES evolution, adoption) live in [[Forger]].
  *
  * The encodings are the ones a `cardano-node` accepts: the block is `[7, [header, [tx_body],
  * [witness_set], {index: auxiliary_data}, [invalid_index]]]`, the header `[header_body, kes_sig]`
  * and `header_body` the ten-element Praos array. The transactions' original bytes are copied,
  * never re-encoded, so transaction ids and the body hash stay exact.
  */
object BlockBuilder:
  val ConwayEra: Int = 7

  // ---- minimal CBOR writer (definite lengths, canonical minimal heads)

  private def head(major: Int, n: Long): Array[Byte] =
    val m = major << 5
    if n < 24 then Array((m | n.toInt).toByte)
    else if n < 256 then Array((m | 24).toByte, n.toByte)
    else if n < 65536 then Array((m | 25).toByte, (n >> 8).toByte, n.toByte)
    else if n < 4294967296L then
      Array((m | 26).toByte, (n >> 24).toByte, (n >> 16).toByte, (n >> 8).toByte, n.toByte)
    else (m | 27).toByte +: Array.tabulate[Byte](8)(i => (n >> (8 * (7 - i))).toByte)

  private def uint(n: BigInt): Array[Byte] = head(0, n.toLong)
  private def bstr(b: Bytes): Array[Byte] = head(2, b.size.toLong) ++ b.toArray
  private def arr(n: Int): Array[Byte] = head(4, n.toLong)
  private val Null: Array[Byte] = Array(0xf6.toByte)

  private def headLength(n: Int): Long = head(0, n.toLong).length.toLong

  /** One transaction split into the pieces a block carries (original bytes). */
  final case class Piece(
      id: Bytes,
      body: Bytes,
      witnesses: Bytes,
      aux: Option[Bytes],
      valid: Boolean
  )

  private val limits =
    Cbor.Limits(maxInputBytes = 1048576, maxItems = 100000, maxStringBytes = 1048576)

  def piece(entry: Entry): Either[ForgeError, Piece] =
    for
      root <- Cbor
        .decode(entry.raw, limits)
        .leftMap(e => ForgeError.Assembly(ForgeAssemblyProblem.Cbor(e)))
      parts <- root.value match
        case Value.Arr(xs) if xs.size == 4 => Right(xs)
        case _ =>
          Left(ForgeError.Assembly(ForgeAssemblyProblem.TransactionArity(entry.id)))
      valid <- parts(2).value match
        case Value.Bool(b) => Right(b)
        case _             => Left(ForgeError.Assembly(ForgeAssemblyProblem.ValidityFlag))
      aux = parts(3).value match
        case Value.Null => None
        case _          => Some(parts(3).original)
    yield Piece(entry.id, parts(0).original, parts(1).original, aux, valid)

  /** The four encoded body components and what the header commits to. */
  final case class Body(
      bodies: Bytes,
      witnesses: Bytes,
      aux: Bytes,
      invalid: Bytes,
      ids: Vector[Bytes]
  ):
    def size: Long = bodies.size.toLong + witnesses.size + aux.size + invalid.size
    def hash: Bytes =
      Blake2b.hash256.hash(
        Bytes(
          Vector(bodies, witnesses, aux, invalid).flatMap(c => Blake2b.hash256.hash(c).value)
        )
      )

  def body(pieces: Vector[Piece]): Body =
    def seq(items: Vector[Array[Byte]]): Bytes =
      Bytes.fromArray(arr(items.size) ++ items.flatten.toArray)
    val auxEntries = pieces.zipWithIndex.collect { case (Piece(_, _, _, Some(a), _), i) =>
      uint(i) ++ a.toArray
    }
    Body(
      seq(pieces.map(_.body.toArray)),
      seq(pieces.map(_.witnesses.toArray)),
      Bytes.fromArray(head(5, auxEntries.size.toLong) ++ auxEntries.flatten.toArray),
      seq(pieces.zipWithIndex.collect { case (p, i) if !p.valid => uint(i) }),
      pieces.map(_.id)
    )

  /** The size of the body of `pieces` without assembling it (checked against [[body]] in tests). */
  def bodySize(pieces: Vector[Piece]): Long =
    val n = pieces.size
    val aux = pieces.zipWithIndex.flatMap((p, i) => p.aux.map(a => (i, a)))
    val invalid = pieces.zipWithIndex.collect { case (p, i) if !p.valid => i }
    headLength(n) + pieces.map(_.body.size.toLong).sum +
      headLength(n) + pieces.map(_.witnesses.size.toLong).sum +
      head(5, aux.size.toLong).length + aux.map((i, a) => headLength(i) + a.size).sum +
      headLength(invalid.size) + invalid.map(i => headLength(i)).sum

  /** What ends selection instead of dropping a transaction: missing evaluator support or a crypto
    * provider that failed on a witness (no ledger verdict), or a quantity of the ledger state outside its vocabulary type, which the reference's
    * `compactCoinOrError` throws on in the forging thread (the node's stop).
    */
  type Stop = LedgerFailure.Unavailable | LedgerFailure.Unverifiable | LedgerFailure.StateOutOfRange

  /** The protocol's block limits for a block on top of `ticked`: its maximum body size and its
    * maximum execution units.
    */
  final case class Limits(maxBodyBytes: Long, maxExUnits: ExUnits):
    /** What the transactions of one block may count for, `blockCapacityConwayMeasure` (consensus
      * 82ecba329 `Shelley/Ledger/Mempool.hs`): the body size less `fixedBlockBodyOverhead`, the
      * execution units, and the reference-script bytes of a block, in the measure of
      * [[Capacity.measure]].
      */
    def capacity: TxMeasure =
      TxMeasure(
        (maxBodyBytes - Capacity.FixedBlockBodyOverhead).max(0L),
        maxExUnits.mem,
        maxExUnits.steps,
        RefScripts.MaxRefScriptSizePerBlock.toLong
      )

  object Limits:
    def of(params: ProtocolParams): Limits =
      Limits(params.maxBlockBodySize.toLong, params.maxBlockExUnits)

  /** The transactions of the block, as the reference takes them from its mempool (consensus
    * 82ecba329 `NodeKernel.hs` 693-715): the held transactions are revalidated in order against the
    * ticked ledger in the block's own environment (`getSnapshotFor`, `Mempool/Query.hs` 29-50, and
    * `computeSnapshot`, `Mempool/Impl/Common.hs` 459-484, which drops the ones that no longer apply
    * without counting them), and of those that still apply the longest prefix whose measures add up
    * to no more than the block's capacity in every component is taken (`snapshotTake`,
    * `splitAfterTxSize` of `Mempool/TxSeq.hs` 205-215): the first one that does not fit ends the
    * selection. Each transaction counts for the measure it was admitted with ([[Capacity.measure]],
    * the ticket's measure the reference keeps).
    *
    * A transaction was validated when the pool admitted it, so it is applied again as the
    * reference's forging snapshot applies it (`revalidateTxsFor` and `reapplyTx`,
    * [[MempoolLedger.reapply]]): MEMPOOL and LEDGER of Conway on the era-indexed state without
    * their static checks, so no signature is verified and no script is evaluated again. A result
    * without a ledger verdict, and a quantity of the state out of range ([[Stop]]), stop the
    * selection instead of dropping the transaction: the forger raises it and the node stops.
    */
  def select(
      ticked: MempoolLedger,
      slot: BigInt,
      epoch: BigInt,
      geometry: EpochGeometry,
      globals: LedgerGlobals,
      candidates: Vector[Entry],
      limit: Limits,
      clock: Option[PosixClock] = None
  )(using
      decoder: ScriptDecoder[Id] = ScalusEvaluator.defaultDecoder,
      evaluator: ScriptEvaluator[Id] = ScalusEvaluator.default
  ): Either[Stop, Vector[(Entry, Piece)]] =
    import Capacity.fits
    final case class Acc(state: MempoolLedger, chosen: Vector[(Entry, Piece)], used: TxMeasure)
    val rules = Globals(globals, geometry)
    val capacity = limit.capacity
    @tailrec
    def go(rest: List[Entry], acc: Acc): Either[Stop, Acc] = rest match
      case Nil => Right(acc)
      case entry :: more =>
        val env = TxEnv(
          slot,
          epoch,
          globals.network,
          firstSlotNextEpoch = geometry.firstSlot(epoch + 1),
          stabilityWindow = geometry.stabilityWindow.toBigInt,
          txIndex = acc.chosen.size,
          updateQuorum = globals.updateQuorum,
          clock = Some(clock.getOrElse(PosixClock.total(geometry))),
          decoder = decoder,
          evaluator = evaluator
        )
        val applied = piece(entry).toOption match
          case None => Right(None)
          case Some(p) =>
            acc.state
              .reapply(entry.tx, env, rules)
              .fold(
                {
                  // no verdict on the transaction (evaluator, crypto provider) or a quantity of the
                  // state out of range: selection stops
                  case unavailable: LedgerFailure.Unavailable    => Left(unavailable)
                  case unverifiable: LedgerFailure.Unverifiable  => Left(unverifiable)
                  case outOfRange: LedgerFailure.StateOutOfRange => Left(outOfRange)
                  case _                                         => Right(None)
                },
                next => Right(Some((next, p)))
              )
        applied match
          case Left(unavailable) => Left(unavailable)
          case Right(None)       => go(more, acc)
          case Right(Some((next, p))) =>
            val used = acc.used + Capacity.measure(entry)
            if used.fits(capacity) then go(more, Acc(next, acc.chosen :+ (entry -> p), used))
            else Right(acc)
    go(candidates.toList, Acc(ticked, Vector.empty, TxMeasure.zero)).map(_.chosen)

  /** The ten-element Praos `header_body`; its exact bytes are what the KES key signs. */
  def headerBody(
      blockNo: BigInt,
      slot: BigInt,
      prevHash: Option[Bytes],
      issuerVkey: Bytes,
      vrfVkey: Bytes,
      vrf: VrfCert,
      bodySize: Long,
      bodyHash: Bytes,
      opCert: Header.OpCert,
      protocolVersion: (BigInt, BigInt)
  ): Bytes =
    Bytes.fromArray(
      arr(10) ++ uint(blockNo) ++ uint(slot) ++ prevHash.fold(Null)(bstr(_)) ++ bstr(issuerVkey) ++
        bstr(vrfVkey) ++ arr(2) ++ bstr(vrf.output) ++ bstr(vrf.proof) ++ uint(bodySize) ++
        bstr(bodyHash) ++ arr(4) ++ bstr(opCert.hotVkey) ++ uint(opCert.sequence) ++
        uint(opCert.kesPeriod) ++ bstr(opCert.sigma) ++ arr(2) ++ uint(protocolVersion._1) ++
        uint(protocolVersion._2)
    )

  /** `[era, [header, bodies, witnesses, aux, invalid]]` with `header = [header_body, kes_sig]`. */
  def block(headerBody: Bytes, kesSignature: Bytes, body: Body): Bytes =
    Bytes.fromArray(
      arr(2) ++ uint(ConwayEra) ++ arr(5) ++ arr(2) ++ headerBody.toArray ++ bstr(kesSignature) ++
        body.bodies.toArray ++ body.witnesses.toArray ++ body.aux.toArray ++ body.invalid.toArray
    )
