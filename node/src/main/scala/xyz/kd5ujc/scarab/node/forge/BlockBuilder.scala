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
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTx
import xyz.kd5ujc.scarab.crypto.bls.Certificate
import xyz.kd5ujc.scarab.plutus.{ExUnits, ScriptDecoder, ScriptEvaluator}
import cats.Id
import xyz.kd5ujc.scarab.protocol.Era
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.{Capacity, EbMeasure, Entry, Held, MempoolLedger}
import xyz.kd5ujc.scarab.ledger.{EbAnnouncement, ForgeDefect}
import xyz.kd5ujc.scarab.protocol.LeiosFetch
import cats.data.NonEmptyVector

/** Block assembly, pure: choose the transactions that fit the protocol limits, encode the body, the
  * header body and the block. Conway's as below; in the Leios build also Dijkstra's ranking block,
  * its transactions taken from the pool's snapshot with the endorser block of those after them
  * ([[partition]], [[Assembly]], [[leiosHeaderBody]]). Every function is deterministic in its
  * arguments; the effects (clock, mempool, KES evolution, adoption) live in [[Forger]].
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
      case (entry @ Entry(Held.Flat(Era.Conway, _, tx), _, _, _)) :: more =>
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
              .reapply(tx, env, rules)
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
      // the transactions of the other eras, which a pool behind or past the crossing holds
      case Entry(Held.Flat(_, _, _) | Held.InDijkstra(_), _, _, _) :: more => go(more, acc)
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
    * of the body (`isJust fbMayLeiosCert`, `Shelley/Ledger/Forge.hs:104-105`) and the announcement
    * of the endorser block forged with it, if one is (`mkEb`, `Forge.hs:172-212`): none when no
    * transaction is left for one.
    */
  def leiosHeaderBody(
      blockNo: BigInt,
      slot: BigInt,
      prevHash: Option[Bytes],
      issuerVkey: Bytes,
      vrfVkey: Bytes,
      vrf: VrfCert,
      body: LeiosBlockBody,
      announcement: Option[EbAnnouncement],
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
        announcement
      )
    )

  /** What a block is assembled of, by the era of the ledger of its slot: Conway's four-part body
    * under the ten-element Praos header, or, in the Leios build only, Dijkstra's body under the
    * Leios header of Praos2 (`forgeShelleyBlock`, ouroboros-consensus 2416493b
    * `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Forge.hs:90-142`).
    */
  enum Assembly:
    case InConway(body: Body)

    /** A Dijkstra ranking block's body, and the endorser block its header announces, if one is
      * forged with it.
      */
    case InDijkstra(body: LeiosBlockBody, endorsing: Option[Endorsing])

    /** The ids of the block's transactions, in order. */
    def ids: Vector[Bytes] = this match
      case InConway(b)      => b.ids
      case InDijkstra(b, _) => b.transactions.map(_.id)

    /** The ids of the transactions of the endorser block forged with the block, in order: the
      * block's `fbEbTxs`, which leave the pool with its own when the block is refused
      * (`addBlockToChainDB`, `NodeKernel/Forge.hs:543-592` of ouroboros-consensus-diffusion at
      * 2416493b).
      */
    def endorsedIds: Vector[Bytes] = this match
      case InConway(_)      => Vector.empty
      case InDijkstra(_, e) => e.fold(Vector.empty[Bytes])(_.transactions.toVector.map(_.id))

    /** `encodeLeiosEbSize` of the endorser block forged with the block within `maxLeiosEbBytesSize`
      * ([[LeiosFetch.MaxEbBytes]]), the reference's `assert` in `mkEb`
      * (`Shelley/Ledger/Forge.hs:189-197` of ouroboros-consensus-cardano at 2416493b): the capacity
      * plans within it ([[Capacity.endorserBlockCapacity]]), so only a defect of the measure or of
      * the encoder breaks it, a defect of the block just built ([[ForgeDefect.EndorserBlockSize]]).
      */
    def endorserBlockOverWire: Option[ForgeDefect] = this match
      case InDijkstra(_, Some(e)) if e.forged.body.encodedSize > LeiosFetch.MaxEbBytes =>
        Some(ForgeDefect.EndorserBlockSize(e.forged.body.encodedSize, LeiosFetch.MaxEbBytes))
      case InDijkstra(_, _) | InConway(_) => None

    /** The body size the header commits to. */
    def size: Long = this match
      case InConway(b)      => b.size
      case InDijkstra(b, _) => b.size

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
      case InDijkstra(b, e) =>
        leiosHeaderBody(
          blockNo,
          slot,
          prevHash,
          issuerVkey,
          vrfVkey,
          vrf,
          b,
          e.map(_.forged.announcement),
          opCert,
          protocolVersion
        ).encode

    /** The block of `headerBody`, signed with `kesSignature`: Conway's [[BlockBuilder.block]], or
      * the Dijkstra block `[8, [[header_body, kes_sig], body]]` ([[LeiosHeader.signed]],
      * [[LeiosBlock.encode]]).
      */
    def block(headerBody: Bytes, kesSignature: Bytes): Bytes = this match
      case InConway(b) => BlockBuilder.block(headerBody, kesSignature, b)
      case InDijkstra(b, _) =>
        LeiosBlock.encode(LeiosHeader.signed(headerBody, kesSignature), b.encode)

  object Assembly:
    /** The body of a Dijkstra ranking block, `mkBody` (ouroboros-consensus 2416493b
      * `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Forge.hs:146-160`):
      * a block that certifies an endorser block carries the certificate and no transactions, as the
      * certified closure is its transaction sequence at application; any other carries the
      * transactions taken for it ([[ranking]]) and no certificate. No Peras certificate is forged
      * (`fbPerasCert = Nothing`, `NodeKernel/Forge.hs:238`).
      */
    def dijkstra(
        transactions: Vector[DijkstraTx],
        leiosCertificate: Option[Certificate],
        endorsing: Option[Endorsing] = None
    ): Assembly =
      InDijkstra(
        LeiosBlockBody(
          leiosCertificate.fold(transactions)(_ => Vector.empty),
          leiosCertificate,
          None
        ),
        endorsing
      )

    /** The body of the minimal Dijkstra block: no transactions and neither certificate, as the
      * reference forges it with an empty mempool and nothing to certify.
      */
    val minimalDijkstra: Assembly = dijkstra(Vector.empty, None)

  /** `Data.Measure` 's operations a split of the pool's sequence needs (`zero`, `plus` and the
    * pointwise `<=`, `Data.Measure` of the `measures` package that ouroboros-consensus 2416493b
    * builds on, `Mempool/TxSeq.hs:39-40`).
    */
  trait Measure[M]:
    def zero: M
    def plus(a: M, b: M): M

    /** Within `limit` in every component. */
    def within(m: M, limit: M): Boolean

  object Measure:
    given Measure[TxMeasure] with
      def zero: TxMeasure = TxMeasure.zero
      def plus(a: TxMeasure, b: TxMeasure): TxMeasure = a + b
      def within(m: TxMeasure, limit: TxMeasure): Boolean = Capacity.fits(m)(limit)

    /** `DijkstraEbMeasure` 's, pointwise (`Measure via InstantiatedAt Generic`). */
    given Measure[EbMeasure] with
      def zero: EbMeasure = EbMeasure.zero
      def plus(a: EbMeasure, b: EbMeasure): EbMeasure = a + b
      def within(m: EbMeasure, limit: EbMeasure): Boolean = m.within(limit)

  /** `splitAfterTxSize` (ouroboros-consensus 2416493b
    * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/TxSeq.hs:211-231`, by
    * its specification `splitAfterTxSizeSpec`, `:239-263`): the longest prefix of `items` whose
    * measures add up to no more than `limit` in every component, the rest, and the prefix's total.
    * The first item that would take the total past `limit` ends the prefix, however small the items
    * after it.
    */
  def splitAfterSize[A, M](items: Vector[A], limit: M)(measure: A => M)(using
      m: Measure[M]
  ): (Vector[A], Vector[A], M) =
    @tailrec
    def go(i: Int, total: M): (Int, M) =
      if i == items.size then (i, total)
      else
        val next = m.plus(total, measure(items(i)))
        if m.within(next, limit) then go(i + 1, next) else (i, total)
    val (taken, total) = go(0, m.zero)
    (items.take(taken), items.drop(taken), total)

  /** The transactions of a Dijkstra ranking block and what they count for. */
  final case class Ranking(chosen: Vector[DijkstraHeld], used: TxMeasure):
    def transactions: Vector[DijkstraTx] = chosen.map(_.tx)

  /** The transactions a Dijkstra ranking block takes from the pool's snapshot of its slot: the
    * first part of `snapshotPartition` (`implSnapshotPartition`, ouroboros-consensus 2416493b
    * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/Impl/Common.hs:760-773`),
    * the longest prefix of the snapshot, oldest first, whose measures fit `capacity`, the block's
    * `blockCapacityTxMeasure` ([[Capacity.dijkstraBlockUnit]]), as the forge loop takes it when it
    * certifies nothing (`partitionMempool`, `NodeKernel/Forge.hs:786-803`). The run of transactions
    * after it, for an endorser block, is the second part, not taken here.
    */
  def ranking(snapshot: DijkstraSnapshot, capacity: TxMeasure): Ranking =
    val (chosen, _, used) = splitAfterSize(snapshot.held, capacity)(_.measure)
    Ranking(chosen, used)

  /** The transactions of the endorser block forged with a ranking block, and the block made of them
    * ([[ForgedLeiosEb.forge]]).
    */
  final case class Endorsing private (
      transactions: NonEmptyVector[DijkstraTx],
      forged: ForgedLeiosEb
  )

  object Endorsing:
    /** The endorser block of `transactions`, forged in `slot`. */
    def apply(slot: BigInt, transactions: NonEmptyVector[DijkstraTx]): Endorsing =
      new Endorsing(transactions, ForgedLeiosEb.forge(slot, transactions))

  /** What a Dijkstra snapshot is partitioned into: the ranking block's transactions, and the run
    * after them an endorser block takes, oldest first, with what it counts for.
    */
  final case class Partition(
      ranking: Ranking,
      endorsed: Vector[DijkstraHeld],
      endorsedUsed: EbMeasure
  ):
    def endorsedTransactions: Vector[DijkstraTx] = endorsed.map(_.tx)

    /** `mkEb` 's endorser block of the run, forged in `slot` (`Shelley/Ledger/Forge.hs:172-212` of
      * ouroboros-consensus-cardano at 2416493b): none when the run is empty, so that a block with
      * nothing left for an endorser block announces none.
      */
    def endorsing(slot: BigInt): Option[Endorsing] =
      NonEmptyVector.fromVector(endorsedTransactions).map(Endorsing(slot, _))

  /** `snapshotPartition` (`implSnapshotPartition`, ouroboros-consensus 2416493b
    * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/Impl/Common.hs:760-773`):
    * the longest prefix of the snapshot whose measures fit `blockLimit` ([[ranking]]), then the
    * longest run after it whose endorser-block measures ([[EbMeasure.of]]) fit `ebLimit`. Each
    * split ends at the first transaction that does not fit (`splitAfterTxSizeOn`), so the run is
    * empty when the first transaction after the prefix does not fit `ebLimit`, however many follow
    * it. The forge loop partitions with the block's capacity and the scaled endorser-block capacity
    * when it certifies nothing, and with a zero block limit when it certifies (`partitionMempool`,
    * `NodeKernel/Forge.hs:784-803, 805-858`).
    */
  def partition(snapshot: DijkstraSnapshot, blockLimit: TxMeasure, ebLimit: EbMeasure): Partition =
    val (inBlock, afterBlock, used) = splitAfterSize(snapshot.held, blockLimit)(_.measure)
    val (inEb, _, ebUsed) = splitAfterSize(afterBlock, ebLimit)(h => EbMeasure.of(h.measure))
    Partition(Ranking(inBlock, used), inEb, ebUsed)
