// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.ledger.ForgeAssemblyProblem
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, ForgeError, Header, LedgerFailure, VrfCert}
import xyz.kd5ujc.scarab.ledger.{LedgerGlobals, PosixClock, RefScripts, TxEnv}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.plutus.{ExUnits, ScalusEvaluator, ScriptDecoder, ScriptEvaluator}
import cats.Id
import xyz.kd5ujc.scarab.node.mempool.{Entry, MempoolLedger}

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

  /** What fits: the protocol's block limits for a block at `slot` on top of `ticked`. */
  final case class Limits(maxBodyBytes: Long, maxExUnits: ExUnits)

  /** Choose, in order, the candidates that still apply one after another to the ticked ledger in
    * the block's own environment and fit the body size, execution unit and reference script limits.
    * A candidate that does not apply (its inputs were spent by the block, it expired) is skipped,
    * not an error: the pool of a mempool is advisory. A candidate was validated when the pool
    * admitted it, so it is applied again as the reference's forging snapshot applies it
    * (`getSnapshotFor`, `revalidateTxsFor` and `reapplyTx`, [[MempoolLedger.reapply]]): MEMPOOL and
    * LEDGER of Conway on the era-indexed state without their static checks, so no signature is
    * verified and no script is evaluated again. A result without a ledger verdict would stop
    * selection instead of skipping the transaction: the forger raises it and the node stops.
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
  ): Either[LedgerFailure.Unavailable | LedgerFailure.Unverifiable, Vector[(Entry, Piece)]] =
    final case class Acc(
        state: MempoolLedger,
        chosen: Vector[(Entry, Piece)],
        units: ExUnits,
        refBytes: Long
    )
    val rules = Globals(globals, geometry)
    candidates
      .foldM(Acc(ticked, Vector.empty, ExUnits.zero, 0L)) { (acc, entry) =>
        piece(entry).toOption.fold(
          Right(acc): Either[LedgerFailure.Unavailable | LedgerFailure.Unverifiable, Acc]
        ) { p =>
          val units = acc.units + entry.tx.witnesses.totalExUnits
          val size = bodySize(acc.chosen.map(_._2) :+ p)
          val refUsed =
            if acc.state.params.protocolMajor >= 9 then
              acc.refBytes + RefScripts.refScriptsSize(
                if acc.state.params.protocolMajor <= 10 then ticked.utxo else acc.state.utxo,
                entry.tx.body
              )
            else 0L
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
          if size > limit.maxBodyBytes || !units.fits(limit.maxExUnits) ||
            refUsed > RefScripts.MaxRefScriptSizePerBlock
          then Right(acc)
          else
            acc.state
              .reapply(entry.tx, env, rules)
              .fold(
                {
                  // no verdict on the transaction (evaluator, crypto provider): selection stops
                  case unavailable: LedgerFailure.Unavailable   => Left(unavailable)
                  case unverifiable: LedgerFailure.Unverifiable => Left(unverifiable)
                  case _                                        => Right(acc)
                },
                next => Right(Acc(next, acc.chosen :+ (entry -> p), units, refUsed))
              )
        }
      }
      .map(_.chosen)

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
