// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import cats.Order
import cats.data.NonEmptyMap
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

/** A size in bytes on the Leios protocols: the reference's `BytesSize`, a `Word32`
  * (`Ouroboros/Consensus/Leios/Types.hs` 194 of ouroboros-consensus 2416493b). Its sum and
  * difference are modulo 2^32^, as on a `Word32`; the fetch logic's sums stay far below that (a
  * peer's budget is at most 60 MiB and one request adds at most the 600,000 bytes of a body or the
  * 500,000 of a transaction request), so the wrap is the reference's and never reached.
  */
opaque type BytesSize = Long

object BytesSize:
  private val Mask = 0xffffffffL

  /** `fromIntegral` into a `Word32`: the low 32 bits. */
  def wrap(n: Long): BytesSize = n & Mask

  val zero: BytesSize = 0L

  extension (a: BytesSize)
    def toLong: Long = a
    def +(b: BytesSize): BytesSize = (a + b) & Mask
    def -(b: BytesSize): BytesSize = (a - b) & Mask
    def <=(b: BytesSize): Boolean = a <= b
    def >(b: BytesSize): Boolean = a > b

  given Ordering[BytesSize] = Ordering.Long
  given CanEqual[BytesSize, BytesSize] = CanEqual.derived

/** The orders of the reference's keys: an endorser-block hash as `PackedBytes 32` orders, which is
  * its bytes' unsigned lexicographic order (as the `cardano-crypto-class-2.6.0.0` unit of the Leios
  * image compares them, `scripts/oracle/leios-stdgen`); a point by its slot and then its hash
  * (`LeiosPoint`'s derived `Ord`, `Leios/Types.hs` 172-174 at 2416493b).
  */
object LeiosOrders:
  given ebHashOrder: Order[EbHash] = Order.from((a, b) => a.bytes.compareTo(b.bytes))
  given ebPointOrder: Order[EbPoint] =
    Order.from((a, b) =>
      val bySlot = a.slot.toBigInt.compare(b.slot.toBigInt)
      if bySlot != 0 then bySlot else ebHashOrder.compare(a.hash, b.hash)
    )
  given ebHashOrdering: Ordering[EbHash] = ebHashOrder.toOrdering
  given ebPointOrdering: Ordering[EbPoint] = ebPointOrder.toOrdering
  given slotOrdering: Ordering[Word64] = Ordering.by(_.toBigInt)

/** An election: its slot and the pool's id, the hash of its cold key (`ElId`,
  * `LeiosDemoLogic/Announcements/ElBimap.hs` 24-46 at 2416493b), ordered by slot first and then by
  * the id's bytes.
  */
final case class ElId(slot: Word64, pool: Bytes) derives CanEqual

object ElId:
  given Order[ElId] = Order.from((a, b) =>
    val bySlot = a.slot.toBigInt.compare(b.slot.toBigInt)
    if bySlot != 0 then bySlot else a.pool.compareTo(b.pool)
  )
  given Ordering[ElId] = Order[ElId].toOrdering

/** What an announcement names: its election, the endorser block and the size it announced the body
  * at (`AnnouncementFields`, `LeiosDemoTypes.hs` 1756-1770 at 2416493b).
  */
final case class AnnouncementFields(election: ElId, ebHash: EbHash, ebBodySize: BytesSize)
    derives CanEqual:
  /** `announcementLeiosPoint`: the election's slot and the endorser block. */
  def point: EbPoint = EbPoint(election.slot, ebHash)

/** Whether a peer has offered an endorser block's transaction closure (`WhetherTxsClosureOffered`,
  * `LeiosDemoTypes.hs` 380-392 at 2416493b): offered if either of two offers says so.
  */
enum WhetherTxsClosureOffered derives CanEqual:
  case Offered, NotOffered

  def combine(other: WhetherTxsClosureOffered): WhetherTxsClosureOffered = this match
    case Offered    => Offered
    case NotOffered => other

/** What one peer has offered for one point (`PeerOffer`, `LeiosDemoTypes.hs` 402-441 at 2416493b):
  * the bound on the closure's size, which an offer of ChainSync brings and one of LeiosNotify does
  * not; the size the peer offered the body at, if it offered the body; whether it offered the
  * closure.
  */
final case class PeerOffer(
    maxEbTxsSize: Option[BytesSize],
    offeredBody: Option[BytesSize],
    closure: WhetherTxsClosureOffered
) derives CanEqual:
  /** The `Semigroup`: each field on its own, the leftmost size winning. */
  def combine(older: PeerOffer): PeerOffer =
    PeerOffer(
      maxEbTxsSize.orElse(older.maxEbTxsSize),
      offeredBody.orElse(older.offeredBody),
      closure.combine(older.closure)
    )

object PeerOffer:
  val empty: PeerOffer = PeerOffer(None, None, WhetherTxsClosureOffered.NotOffered)

/** Whether the node holds an endorser block's body (`EbFetchState`, `LeiosDemoTypes.hs` 631-659 at
  * 2416493b).
  */
enum EbFetchState derives CanEqual:
  /** The node's own forge is producing it: never requested, and any offer of it is dead. */
  case BodyImminent

  /** Only announced. */
  case NoBody

  /** The body is held; the jobs of its closure not yet requested, and which pool they are: a
    * request names it, so a reply is held to the jobs of the pool that issued it
    * ([[LeiosJobPoolId]]).
    */
  case BodyAcquired(jobs: LeiosJobPool, pool: LeiosJobPoolId)

/** The state of one tracked endorser block (`EbState`, `LeiosDemoTypes.hs` 616-628 at 2416493b):
  * the greatest slot it was announced at, the onset of its earliest announcement's slot if one
  * supplied it, and its fetch state. The onset is a `RelativeTime` there, picoseconds since the
  * system start; here a duration since it, which only the arrival's age reads.
  */
final case class EbState(slot: Word64, onset: Option[FiniteDuration], fetch: EbFetchState)
    derives CanEqual:
  /** `ebStateHasBody`. */
  def hasBody: Boolean = fetch match
    case EbFetchState.BodyAcquired(_, _)                 => true
    case EbFetchState.NoBody | EbFetchState.BodyImminent => false

  /** `wantsBody`: whether the body is still to be fetched, this entry's part of
    * [[LeiosOutstanding.numMissingBodies]].
    */
  def wantsBody: Int = fetch match
    case EbFetchState.NoBody                                         => 1
    case EbFetchState.BodyImminent | EbFetchState.BodyAcquired(_, _) => 0

/** A request the fetch logic decided to send (`LeiosFetchRequest`, `LeiosDemoTypes.hs` 328-353 at
  * 2416493b).
  */
enum LeiosFetchRequest derives CanEqual:
  /** A body, with the size the peer offered it at and the bound on its closure, neither sent but
    * both checked on its arrival.
    */
  case Block(point: EbPoint, offeredSize: BytesSize, maxEbTxsSize: BytesSize)

  /** Transactions of a body: the pool the jobs were picked from, and the jobs the request covers by
    * id, each with its commitment. Only the union of their offsets goes on the wire
    * ([[LeiosFetchLogic.offsetsToBitmap]]); the pool is the node's own ([[LeiosJobPoolId]]).
    */
  case BlockTxs(point: EbPoint, pool: LeiosJobPoolId, jobs: NonEmptyMap[Int, LeiosJob])

/** The fetch logic's limits (`LeiosFetchStaticEnv`, `LeiosDemoTypes.hs` 1149-1176 at 2416493b). */
final case class LeiosFetchStaticEnv(
    maxRequestedBytesSizePerPeer: BytesSize,
    maxRequestBytesSize: BytesSize,
    maxJobBytesSize: BytesSize,
    maxJobTxCount: Int,
    fetchPriorityWindowSlots: BigInt,
    maxLeiosNotifyIngressQueue: BytesSize,
    maxLeiosFetchIngressQueue: BytesSize
):
  /** `maxRequestedBytesSizePerBigLedgerPeer` (`LeiosDemoTypes.hs` 1209-1210): as many bytes as the
    * LeiosFetch ingress queue holds.
    */
  def maxRequestedBytesSizePerBigLedgerPeer: BytesSize = maxLeiosFetchIngressQueue

object LeiosFetchStaticEnv:
  /** `maxLeiosTxsRequestBytesSize` (`LeiosDemoOnlyTestFetch.hs` 228-229 at 2416493b). */
  val MaxLeiosTxsRequestBytesSize: BytesSize = BytesSize.wrap(500L * 1000L)

  /** `demoLeiosFetchStaticEnv` (`LeiosDemoTypes.hs` 1178-1195 at 2416493b), the limits the node
    * runs with: five million bytes per peer, the request bound, jobs of 64 KiB and at most 20,000
    * transactions, a window of 10 slots, ingress queues of 1 MiB and 60 MiB.
    */
  val demo: LeiosFetchStaticEnv = LeiosFetchStaticEnv(
    maxRequestedBytesSizePerPeer = BytesSize.wrap(5L * 1000000L),
    maxRequestBytesSize = MaxLeiosTxsRequestBytesSize,
    maxJobBytesSize = BytesSize.wrap(64L * 1024L),
    maxJobTxCount = 20000,
    fetchPriorityWindowSlots = BigInt(10),
    maxLeiosNotifyIngressQueue = BytesSize.wrap(1L << 20),
    maxLeiosFetchIngressQueue = BytesSize.wrap(5L * 12L * (1L << 20))
  )

/** Whether a peer is a big-ledger peer (`IsBigLedgerPeer`, ouroboros-network 2f1c154f): the fetch
  * logic gives one a larger budget.
  */
enum IsBigLedgerPeer derives CanEqual:
  case BigLedgerPeer, NotBigLedgerPeer
