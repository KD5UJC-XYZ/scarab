// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.ledger.{DetachedRecords, ForgeAssemblyProblem, Tx}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, ForgeError, Header, LedgerFailure, VrfCert}
import xyz.kd5ujc.scarab.ledger.{HeaderBodyEncoding, HeaderVersionInfo, HeaderVrf}
import xyz.kd5ujc.scarab.ledger.{LeiosBlock, LeiosBlockBody, LeiosHeader}
import xyz.kd5ujc.scarab.ledger.{LeiosHeaderBody, LeiosHeaderFields}
import xyz.kd5ujc.scarab.ledger.{LedgerGlobals, PosixClock, ProtocolParams, RefScripts, TxEnv}
import xyz.kd5ujc.scarab.ledger.rules.Globals
import xyz.kd5ujc.scarab.plutus.{ExUnits, ScriptDecoder, ScriptEvaluator}
import cats.Id
import xyz.kd5ujc.scarab.protocol.Era
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.{Capacity, Entry, MempoolLedger}

/** Block assembly, pure: choose the transactions that fit the protocol limits, encode the body, the
  * header body and the block. Conway's as below; in the Leios build also Dijkstra's minimal block
  * ([[Assembly]], [[leiosHeaderBody]]). Every function is deterministic in its arguments; the
  * effects (clock, mempool, KES evolution, adoption) live in [[Forger]].
  *
  * The encodings are the reference's: the block is `[7, [header, [tx_body], [witness_set], {index:
  * auxiliary_data}, [invalid_index]]]`, the header `[header_body, kes_sig]` and `header_body` the
  * ten-element Praos array. The four body parts are definite up to 23 entries and indefinite above,
  * as the reference writes them ([[container]]). The transactions' original bytes are copied, never
  * re-encoded, so transaction ids and the body hash stay exact.
  */
object BlockBuilder:
  val ConwayEra: Int = 7

  // ---- minimal CBOR writer (canonical minimal heads)

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

  private def headLength(n: Int): Long = head(0, n.toLong).length.toLong

  /** The most entries a body part is written with a definite length (`lengthThreshold`). */
  private val LengthThreshold = 23

  private val Break: Byte = 0xff.toByte

  /** A body part of `n` entries (`items`, encoded) as the reference writes it: definite up to
    * [[LengthThreshold]] entries, indefinite above. The transaction bodies and the witness sets go
    * through `encodeFoldableEncoder`, the auxiliary data through `encodeFoldableMapEncoder` and the
    * invalid indices through `encodeList` (cardano-ledger f649f975
    * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/BlockBody/Internal.hs:130-149`), which all end in
    * that rule (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Encoding/Encoder.hs:361-385`,
    * `:433-443`, `:458-468`, `:499-508`).
    */
  private def container(major: Int, n: Int, items: Array[Byte]): Array[Byte] =
    if n <= LengthThreshold then head(major, n.toLong) ++ items
    else Array(((major << 5) | 31).toByte) ++ items ++ Array(Break)

  /** The bytes [[container]] adds to its entries: the head, or the indefinite head and the break.
    */
  private def containerOverhead(n: Int): Long = if n <= LengthThreshold then 1L else 2L

  /** One transaction split into the pieces a block carries (original bytes). */
  final case class Piece(
      id: Bytes,
      body: Bytes,
      witnesses: Bytes,
      aux: Option[Bytes],
      valid: Boolean
  )

  // a held transaction is read again as the mempool read it, with no budget but its size (#749)
  private val limits = Cbor.Limits.Unbounded

  /** The parts of a held transaction, read as the mempool read it: one holding a `RecD` record
    * written as an empty indefinite list spans the fields after it (`DetachedRecords`).
    */
  def piece(entry: Entry): Either[ForgeError, Piece] =
    DetachedRecords.read(entry.raw, limits, DetachedRecords.Shapes.transaction(Era.Conway))(e =>
      ForgeError.Assembly(ForgeAssemblyProblem.Cbor(e))
    )(parts(entry, _))

  private def parts(entry: Entry, root: Node): Either[ForgeError, Piece] =
    Tx.envelopeParts(root, Era.Conway)
      .bimap(
        e => ForgeError.Assembly(ForgeAssemblyProblem.Envelope(entry.id, e)),
        t => Piece(entry.id, t.body, t.witnesses, t.auxiliaryData, t.isValid)
      )

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
      Bytes.fromArray(container(4, items.size, items.flatten.toArray))
    val auxEntries = pieces.zipWithIndex.collect { case (Piece(_, _, _, Some(a), _), i) =>
      uint(i) ++ a.toArray
    }
    Body(
      seq(pieces.map(_.body.toArray)),
      seq(pieces.map(_.witnesses.toArray)),
      Bytes.fromArray(container(5, auxEntries.size, auxEntries.flatten.toArray)),
      seq(pieces.zipWithIndex.collect { case (p, i) if !p.valid => uint(i) }),
      pieces.map(_.id)
    )

  /** The size of the body of `pieces` without assembling it (checked against [[body]] in tests). */
  def bodySize(pieces: Vector[Piece]): Long =
    val n = pieces.size
    val aux = pieces.zipWithIndex.flatMap((p, i) => p.aux.map(a => (i, a)))
    val invalid = pieces.zipWithIndex.collect { case (p, i) if !p.valid => i }
    containerOverhead(n) + pieces.map(_.body.size.toLong).sum +
      containerOverhead(n) + pieces.map(_.witnesses.size.toLong).sum +
      containerOverhead(aux.size) + aux.map((i, a) => headLength(i) + a.size).sum +
      containerOverhead(invalid.size) + invalid.map(i => headLength(i)).sum

  /** No verdict of ours on a held transaction: missing evaluator support or a crypto provider that
    * failed on a witness, or a quantity of the ledger state outside its vocabulary type, which the
    * reference's `compactCoinOrError` throws on in the forging thread (the reference's stop).
    */
  type NoVerdict =
    LedgerFailure.Unavailable | LedgerFailure.Unverifiable | LedgerFailure.StateOutOfRange

  /** The transactions of the block, and the held ones it was built without because the node has no
    * verdict on them ([[NoVerdict]]): those leave the pool.
    */
  final case class Selection(chosen: Vector[(Entry, Piece)], withheld: Vector[(Entry, NoVerdict)])

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
    * their static checks, so no signature is verified and no script is evaluated again.
    *
    * A transaction the node has no verdict on ([[NoVerdict]]) is withheld: the block is built from
    * the rest and the transaction leaves the pool with its reason. The reference has no such case:
    * the exception ends its linked forging thread and the node stops (consensus 82ecba32
    * `NodeKernel.hs` `forkBlockForging`). This is the owner's explicit decision of 2026-10-28
    * (issue 584, ADR 0017).
    *
    * `decoder` and `evaluator` are the node's backend (`PlutusBackend`), passed on every path; the
    * static checks they serve do not run on a reapplication.
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
  )(
      decoder: ScriptDecoder[Id],
      evaluator: ScriptEvaluator[Id]
  ): Selection =
    import Capacity.fits
    final case class Acc(
        state: MempoolLedger,
        chosen: Vector[(Entry, Piece)],
        withheld: Vector[(Entry, NoVerdict)],
        used: TxMeasure
    )
    val rules = Globals(globals, geometry)
    val capacity = limit.capacity
    @tailrec
    def go(rest: List[Entry], acc: Acc): Acc = rest match
      case Nil => acc
      // a Conway block takes Conway transactions: the pool's ledger is of the era of the slot
      // after the tip, and the forger forges only on a Conway tip, so the pool it reads is Conway's
      case entry :: more if entry.era != Era.Conway => go(more, acc)
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
          evaluator = evaluator,
          referenceBuild = globals.referenceBuild
        )
        val applied: Either[NoVerdict, Option[(MempoolLedger, Piece)]] = piece(entry).toOption match
          case None => Right(None)
          case Some(p) =>
            acc.state
              .reapply(entry.tx, env, rules)
              .fold(
                {
                  // no verdict on the transaction (evaluator, crypto provider) or a quantity of the
                  // state out of range: it is withheld and the selection goes on
                  case unavailable: LedgerFailure.Unavailable    => Left(unavailable)
                  case unverifiable: LedgerFailure.Unverifiable  => Left(unverifiable)
                  case outOfRange: LedgerFailure.StateOutOfRange => Left(outOfRange)
                  case _                                         => Right(None)
                },
                next => Right(Some((next, p)))
              )
        applied match
          case Left(noVerdict) =>
            go(more, acc.copy(withheld = acc.withheld :+ (entry -> noVerdict)))
          case Right(None) => go(more, acc)
          case Right(Some((next, p))) =>
            val used = acc.used + Capacity.measure(entry)
            if used.fits(capacity) then
              go(more, acc.copy(state = next, chosen = acc.chosen :+ (entry -> p), used = used))
            else acc
    val done = go(candidates.toList, Acc(ticked, Vector.empty, Vector.empty, TxMeasure.zero))
    Selection(done.chosen, done.withheld)

  /** The ten-element Praos `header_body`, encoded as the reference encodes it to check the KES
    * signature ([[HeaderBodyEncoding]]): the bytes the KES key signs, and the block holds them as
    * they are, so the header's original bytes are that encoding too.
    */
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
    HeaderBodyEncoding.encode(
      HeaderBodyEncoding.Fields(
        blockNo,
        slot,
        prevHash,
        issuerVkey,
        vrfVkey,
        HeaderVrf.Praos(vrf),
        BigInt(bodySize),
        bodyHash,
        opCert,
        protocolVersion
      )
    )

  /** `[era, [header, bodies, witnesses, aux, invalid]]` with `header = [header_body, kes_sig]`. */
  def block(headerBody: Bytes, kesSignature: Bytes, body: Body): Bytes =
    Bytes.fromArray(
      arr(2) ++ uint(ConwayEra) ++ arr(5) ++ arr(2) ++ headerBody.toArray ++ bstr(kesSignature) ++
        body.bodies.toArray ++ body.witnesses.toArray ++ body.aux.toArray ++ body.invalid.toArray
    )

  /** The Leios header body of a Dijkstra block on `body`, as Praos2's `mkHeader` fills it
    * (`extendHeaderBodyWithLeios`, ouroboros-consensus 2416493b
    * `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Protocol/Praos.hs:275-300`,
    * from `mkHeaderPolyPraos`, `:162-185`): the Praos fields, the version info of the node's own
    * protocol version (`BlockHeaderVersionInfo (getVersion32 major) minor`), the certificate flag
    * of the body (`isJust fbMayLeiosCert`, `Shelley/Ledger/Forge.hs:104-105`) and no announcement:
    * the reference announces an endorser block only when it forges one, from transactions it has
    * for it (`mkEb`, `Forge.hs:172-178`), and this forger forges none yet (#54).
    */
  def leiosHeaderBody(
      blockNo: BigInt,
      slot: BigInt,
      prevHash: Option[Bytes],
      issuerVkey: Bytes,
      vrfVkey: Bytes,
      vrf: VrfCert,
      body: LeiosBlockBody,
      opCert: Header.OpCert,
      protocolVersion: (BigInt, BigInt)
  ): LeiosHeaderBody =
    LeiosHeaderBody(
      blockNo,
      slot,
      prevHash,
      issuerVkey,
      vrfVkey,
      vrf,
      BigInt(body.size),
      body.hash,
      opCert,
      LeiosHeaderFields(
        HeaderVersionInfo(protocolVersion._1, protocolVersion._2),
        body.leiosCertificate.isDefined,
        None
      )
    )

  /** What a block is assembled of, by the era of the ledger of its slot: Conway's four-part body
    * under the ten-element Praos header, or, in the Leios build only, Dijkstra's body under the
    * Leios header of Praos2 (`forgeShelleyBlock`, ouroboros-consensus 2416493b
    * `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Forge.hs:90-142`).
    */
  enum Assembly:
    case InConway(body: Body)
    case InDijkstra(body: LeiosBlockBody)

    /** The ids of the block's transactions, in order. */
    def ids: Vector[Bytes] = this match
      case InConway(b)   => b.ids
      case InDijkstra(b) => b.transactions.map(_.id)

    /** The body size the header commits to. */
    def size: Long = this match
      case InConway(b)   => b.size
      case InDijkstra(b) => b.size

    /** The header body the KES key signs, as the block holds it. */
    def headerBody(
        blockNo: BigInt,
        slot: BigInt,
        prevHash: Option[Bytes],
        issuerVkey: Bytes,
        vrfVkey: Bytes,
        vrf: VrfCert,
        opCert: Header.OpCert,
        protocolVersion: (BigInt, BigInt)
    ): Bytes = this match
      case InConway(b) =>
        BlockBuilder.headerBody(
          blockNo,
          slot,
          prevHash,
          issuerVkey,
          vrfVkey,
          vrf,
          b.size,
          b.hash,
          opCert,
          protocolVersion
        )
      case InDijkstra(b) =>
        leiosHeaderBody(
          blockNo,
          slot,
          prevHash,
          issuerVkey,
          vrfVkey,
          vrf,
          b,
          opCert,
          protocolVersion
        ).encode

    /** The block of `headerBody`, signed with `kesSignature`: Conway's [[BlockBuilder.block]], or
      * the Dijkstra block `[8, [[header_body, kes_sig], body]]` ([[LeiosHeader.signed]],
      * [[LeiosBlock.encode]]).
      */
    def block(headerBody: Bytes, kesSignature: Bytes): Bytes = this match
      case InConway(b) => BlockBuilder.block(headerBody, kesSignature, b)
      case InDijkstra(b) =>
        LeiosBlock.encode(LeiosHeader.signed(headerBody, kesSignature), b.encode)

  object Assembly:
    /** The body of the minimal Dijkstra block: no transactions and neither certificate, as the
      * reference forges it with an empty mempool and nothing to certify (`mkBody`,
      * ouroboros-consensus 2416493b `Shelley/Ledger/Forge.hs:152-160`). Dijkstra's transaction
      * selection is not built yet (#54).
      */
    val minimalDijkstra: Assembly = InDijkstra(LeiosBlockBody(Vector.empty, None, None))
