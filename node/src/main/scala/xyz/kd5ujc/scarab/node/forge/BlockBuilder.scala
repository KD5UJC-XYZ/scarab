// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.ProtVerParity
import xyz.kd5ujc.scarab.ledger.{Header, VrfCert}
import xyz.kd5ujc.scarab.ledger.{HeaderBodyEncoding, HeaderVersionInfo, HeaderVrf}
import xyz.kd5ujc.scarab.ledger.{LeiosBlock, LeiosBlockBody, LeiosHeader}
import xyz.kd5ujc.scarab.ledger.{LeiosHeaderBody, LeiosHeaderFields}
import xyz.kd5ujc.scarab.ledger.{ProtocolParams, RefScripts}
import xyz.kd5ujc.scarab.ledger.era.{Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.state.Dijkstra
import xyz.kd5ujc.scarab.crypto.bls.Certificate
import xyz.kd5ujc.scarab.plutus.ExUnits
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.{Capacity, ConwayAdmitted, EbMeasure}
import xyz.kd5ujc.scarab.ledger.{EbAnnouncement, ForgeDefect}
import xyz.kd5ujc.scarab.protocol.LeiosFetch
import cats.data.NonEmptyVector

/** Block assembly, pure: choose the transactions that fit the protocol limits, encode the body, the
  * header body and the block. Conway's as below, its transactions taken from the pool's snapshot
  * ([[take]]); in the Leios build also Dijkstra's ranking block, its transactions taken from the
  * pool's snapshot with the endorser block of those after them ([[partition]], [[Assembly]],
  * [[leiosHeaderBody]]). Every function is deterministic in its arguments; the effects (clock,
  * mempool, KES evolution, adoption) live in [[Forger]].
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

  /** The parts of a held Conway transaction as its block takes them: the transaction as it was
    * validated (`extractTx` of the snapshot's `Validated` transaction, ouroboros-consensus 82ecba32
    * `Shelley/Ledger/Forge.hs:85-92`; `extractValidatedTx` at 2416493b, `:146-160`), whose parts
    * the block body writes as their original bytes (`mkBasicBlockBody`, cardano-ledger f649f975
    * `Alonzo/BlockBody/Internal.hs:128-149`): the bytes they were received in, with the flag of its
    * verdict ([[ConwayRead.withIsValid]]), never read again.
    */
  def piece(held: ConwayAdmitted): Piece =
    val parts = held.read.parts
    Piece(held.read.id, parts.body, parts.witnesses, parts.auxiliaryData, parts.isValid)

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
      Bytes.fromArray(container(4, items.size, Array.concat(items*)))
    val auxEntries = pieces.zipWithIndex.collect { case (Piece(_, _, _, Some(a), _), i) =>
      uint(i) ++ a.toArray
    }
    Body(
      seq(pieces.map(_.body.toArray)),
      seq(pieces.map(_.witnesses.toArray)),
      Bytes.fromArray(container(5, auxEntries.size, Array.concat(auxEntries*))),
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

  /** The transactions of a Conway block, from the pool's snapshot of its slot ([[ConwaySnapshot]]):
    * `snapshotTake` (ouroboros-consensus 82ecba32 `NodeKernel.hs:697-707`, `splitAfterTxSize` of
    * `Mempool/TxSeq.hs:205-215`), the longest prefix of the snapshot, oldest first, whose measures,
    * each the one its transaction was admitted with ([[Capacity.measure]]), add up to no more than
    * `capacity` in every component, the block's `blockCapacityTxMeasure` ([[Limits.capacity]]). The
    * first transaction that does not fit ends the block, however small the ones after it.
    */
  def take(snapshot: ConwaySnapshot, capacity: TxMeasure): Vector[ConwayHeld] =
    splitAfterSize(snapshot.held, capacity)(_.measure)._1

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
        transactions: Vector[Tx[TxLevel.Top, Dijkstra]],
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
    def transactions: Vector[Tx[TxLevel.Top, Dijkstra]] = chosen.map(_.tx)

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
      transactions: NonEmptyVector[Tx[TxLevel.Top, Dijkstra]],
      forged: ForgedLeiosEb
  )

  object Endorsing:
    /** The endorser block of `transactions`, forged in `slot` and revalidated under a protocol
      * version of parity `parity`.
      */
    def apply(
        slot: BigInt,
        parity: ProtVerParity,
        transactions: NonEmptyVector[Tx[TxLevel.Top, Dijkstra]]
    ): Endorsing =
      new Endorsing(transactions, ForgedLeiosEb.forge(slot, parity, transactions))

  /** What a Dijkstra snapshot is partitioned into: the ranking block's transactions, and the run
    * after them an endorser block takes, oldest first, with what it counts for.
    */
  final case class Partition(
      ranking: Ranking,
      endorsed: Vector[DijkstraHeld],
      endorsedUsed: EbMeasure
  ):
    def endorsedTransactions: Vector[Tx[TxLevel.Top, Dijkstra]] = endorsed.map(_.tx)

    /** `mkEb` 's endorser block of the run, forged in `slot` (`Shelley/Ledger/Forge.hs:172-212` of
      * ouroboros-consensus-cardano at 2416493b): none when the run is empty, so that a block with
      * nothing left for an endorser block announces none. `parity` is that of the protocol version
      * of the ticked state the pool revalidated the run on (`protVerParity`, `:182-185`).
      */
    def endorsing(slot: BigInt, parity: ProtVerParity): Option[Endorsing] =
      NonEmptyVector.fromVector(endorsedTransactions).map(Endorsing(slot, parity, _))

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
