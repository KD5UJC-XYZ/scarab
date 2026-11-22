// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.crypto.bls.RbHash
import xyz.kd5ujc.scarab.protocol.{EbHash, LeiosTxHash}

/** The protocol version a transaction was validated under, modulo 2 (`ProtVerParity`,
  * `LeiosDemoTypes.hs:1248-1258`, ouroboros-consensus 2416493b): a transaction stays cached for
  * less than an epoch, so it sees at most one version change, and the parity tells whether its
  * validation still holds.
  */
enum ProtVerParity derives CanEqual:
  case Even, Odd

object ProtVerParity:
  /** `protVerParity` of a major protocol version. */
  def of(major: BigInt): ProtVerParity = if major.testBit(0) then Odd else Even

/** A body from which the transactions it references can be enumerated, by hash, in body order, each
  * at most once (`ReferencesTxsByHash`, `LeiosTxCache/API.hs:133-139` at ouroboros-consensus
  * 2416493b).
  */
trait ReferencesTxsByHash[B]:
  def txReferences(body: B): Iterable[LeiosTxHash]

/** A reference count, at least one: an entry that would reach zero is removed instead (`RefCount`,
  * `LeiosTxCache/API.hs:180-183` at 2416493b).
  */
opaque type RefCount = Int

object RefCount:
  val one: RefCount = 1
  extension (rc: RefCount)
    def value: Int = rc
    def inc: RefCount = rc + 1

    /** `decRefCount`: none once it would reach zero. */
    def dec: Option[RefCount] = Option.when(rc > 1)(rc - 1)

/** `BodyState` (`LeiosTxCache/API.hs:185-190` at 2416493b). */
enum BodyState[+B]:
  /** An announcement of this endorser block is held, but not its body. */
  case NotYetInserted(refs: RefCount)

  /** Its body is held. */
  case AlreadyInserted(refs: RefCount, body: B)

  def refs: RefCount

/** `TxState` (`LeiosTxCache/Reference.hs:85-94` at 2416493b). */
enum TxState[+A, +V]:
  /** A held body refers to it, but it is not inserted. */
  case NotYetInserted(refs: RefCount)

  /** Inserted (fetched), but applied neither by the mempool nor the voting thread. */
  case AlreadyInserted(refs: RefCount, payload: A)

  /** Applied, with what it was applied under. */
  case AlreadyValidated(refs: RefCount, validated: V)

  def refs: RefCount

  def withRefs(rc: RefCount): TxState[A, V] = this match
    case NotYetInserted(_)      => NotYetInserted(rc)
    case AlreadyInserted(_, a)  => AlreadyInserted(rc, a)
    case AlreadyValidated(_, v) => AlreadyValidated(rc, v)

/** A transaction's state before an unapplied insert (`TxArrivalPrior`,
  * `LeiosTxCache/API.hs:189-199` at 2416493b).
  */
enum TxArrivalPrior derives CanEqual:
  case WasUntracked, WasNotYetInserted, WasAlreadyHeld

/** What inserting a body found (`LeiosTxCacheInsertBodySummary`, built by
  * `mkLeiosTxCacheInsertBodySummary`, `LeiosTxCache/API.hs:226-240` at 2416493b;
  * `LeiosDemoTypes.hs:1605`): the body's transactions, how many of them were tracked, acquired and
  * validated before, and the transactions tracked after.
  */
final case class InsertBodySummary(
    txsInEb: Int,
    tracked: Int,
    acquired: Int,
    validated: Int,
    cacheTxCount: Int
)

/** The LeiosTxCache's index (`LeiosTxCacheIndex`, `LeiosTxCache/Reference.hs:96-125` at
  * ouroboros-consensus 2416493b), the reference implementation's pure index: the endorser blocks
  * the [[LeiosTxCacheIndex.MaxAnnouncementCount]] newest announcements name, the bodies held of
  * them and the transactions those bodies reference, each with a reference count, so that evicting
  * an announcement cascades through its body to the transactions no other held body references. `A`
  * is what is recorded of a transaction inserted (fetched) but not applied, `V` of one applied, `B`
  * of a body. The transactions' durable locations (`txLocState`, `locRing`, `locNext`) are not
  * kept: they serve cross-EB fills, which the node does not make (#54).
  *
  * Invariants, as the reference's: `announcementCount` is the number of announcements and at most
  * [[LeiosTxCacheIndex.MaxAnnouncementCount]] after every operation; a body's count is the number
  * of announcements of its hash; a transaction's count is the number of held bodies that reference
  * it. The constructor is private, so only the operations below make an index.
  */
final case class LeiosTxCacheIndex[A, V, B] private (
    announcementState: SortedMap[BigInt, SortedMap[RbHash, EbHash]],
    announcementCount: Int,
    bodyState: Map[EbHash, BodyState[B]],
    txState: Map[LeiosTxHash, TxState[A, V]],
    prunedSlot: BigInt
):
  import LeiosTxCacheIndex.given

  /** `insertAnnouncement` (`Reference.hs:186-236` at 2416493b): the announcement of `eb` by the
    * header `rb` of `slot`, bumping the body's count; a no-op for one held already or one strictly
    * older than [[prunedSlot]]. Past [[MaxAnnouncementCount]] the oldest is evicted, cascading. The
    * bodies and transactions evicted.
    */
  def insertAnnouncement(slot: BigInt, rb: RbHash, eb: EbHash)(using
      ReferencesTxsByHash[B]
  ): (LeiosTxCacheIndex[A, V, B], Set[EbHash], Set[LeiosTxHash]) =
    val present = announcementState.get(slot).exists(_.contains(rb))
    if slot < prunedSlot || present then (this, Set.empty, Set.empty)
    else
      copy(
        announcementState = announcementState.updated(
          slot,
          announcementState.getOrElse(slot, SortedMap.empty[RbHash, EbHash]).updated(rb, eb)
        ),
        announcementCount = announcementCount + 1,
        bodyState = bodyState.updated(
          eb,
          bodyState.get(eb) match
            case None                                   => BodyState.NotYetInserted(RefCount.one)
            case Some(BodyState.NotYetInserted(rc))     => BodyState.NotYetInserted(rc.inc)
            case Some(BodyState.AlreadyInserted(rc, b)) => BodyState.AlreadyInserted(rc.inc, b)
        )
      ).evictWhile(_.announcementCount > LeiosTxCacheIndex.MaxAnnouncementCount)

  /** `evictOlderThan` (`Reference.hs:260-275` at 2416493b): every announcement strictly older than
    * `boundary` evicted, cascading, and [[prunedSlot]] advanced to it, monotonically. The bodies
    * and transactions evicted.
    */
  def evictOlderThan(boundary: BigInt)(using
      ReferencesTxsByHash[B]
  ): (LeiosTxCacheIndex[A, V, B], Set[EbHash], Set[LeiosTxHash]) =
    val advanced = copy(prunedSlot = prunedSlot.max(boundary))
    advanced.evictWhile(_.announcementState.headOption.exists(_._1 < advanced.prunedSlot))

  /** `evictWhile` (`Reference.hs:235-249` at 2416493b): `evictOldest` while `shouldEvict` holds, by
    * iteration over at most the announcements held.
    */
  private def evictWhile(shouldEvict: LeiosTxCacheIndex[A, V, B] => Boolean)(using
      ReferencesTxsByHash[B]
  ): (LeiosTxCacheIndex[A, V, B], Set[EbHash], Set[LeiosTxHash]) =
    Iterator
      .iterate((this, Set.empty[EbHash], Set.empty[LeiosTxHash])) { (idx, ebs, txs) =>
        val (next, ebs1, txs1) = idx.evictOldest
        (next, ebs ++ ebs1, txs ++ txs1)
      }
      .find((idx, _, _) => idx.announcementState.isEmpty || !shouldEvict(idx))
      .getOrElse((this, Set.empty, Set.empty))

  /** `evictOldest` (`Reference.hs:277-319` at 2416493b): the announcement of the least slot, then
    * the least header, removed, and its body's count decremented ([[decBody]]).
    */
  private def evictOldest(using
      ReferencesTxsByHash[B]
  ): (LeiosTxCacheIndex[A, V, B], Set[EbHash], Set[LeiosTxHash]) =
    announcementState.headOption
      .flatMap((slot, byHeader) => byHeader.headOption.map(oldest => (slot, byHeader, oldest)))
      .fold((this, Set.empty[EbHash], Set.empty[LeiosTxHash])) { case (slot, byHeader, (rb, eb)) =>
        val rest = byHeader - rb
        val (bodies, txs, ebs, evicted) = decBody(eb)
        (
          copy(
            announcementState =
              if rest.isEmpty then announcementState - slot
              else announcementState.updated(slot, rest),
            announcementCount = announcementCount - 1,
            bodyState = bodies,
            txState = txs
          ),
          ebs,
          evicted
        )
      }

  /** `decBody` (`Reference.hs:319-349` at 2416493b): the body's count decremented; at zero it is
    * removed and, if it was held, each transaction it references decremented, those reaching zero
    * removed.
    */
  private def decBody(eb: EbHash)(using
      refs: ReferencesTxsByHash[B]
  ): (Map[EbHash, BodyState[B]], Map[LeiosTxHash, TxState[A, V]], Set[EbHash], Set[LeiosTxHash]) =
    bodyState.get(eb) match
      case None => (bodyState, txState, Set.empty, Set.empty)
      case Some(body) =>
        body.refs.dec match
          case Some(rc) =>
            val kept = body match
              case BodyState.NotYetInserted(_)     => BodyState.NotYetInserted(rc)
              case BodyState.AlreadyInserted(_, b) => BodyState.AlreadyInserted(rc, b)
            (bodyState.updated(eb, kept), txState, Set.empty, Set.empty)
          case None =>
            val (txs, evicted) = body match
              case BodyState.NotYetInserted(_) => (txState, Set.empty[LeiosTxHash])
              case BodyState.AlreadyInserted(_, b) =>
                refs.txReferences(b).foldLeft((txState, Set.empty[LeiosTxHash])) {
                  case ((ts, ev), txh) =>
                    ts.get(txh) match
                      case None => (ts, ev)
                      case Some(tx) =>
                        tx.refs.dec.fold((ts - txh, ev + txh))(rc =>
                          (ts.updated(txh, tx.withRefs(rc)), ev)
                        )
                }
            (bodyState - eb, txs, Set(eb), evicted)

  /** `insertBody` (`Reference.hs:351-412` at 2416493b, without the fold over locations): the body
    * of `eb` held, bumping the count of each transaction it references, with the summary of what
    * they were before; none, and nothing changed, where no announcement names `eb` or its body is
    * held already.
    */
  def insertBody(eb: EbHash, body: B)(using
      refs: ReferencesTxsByHash[B]
  ): (LeiosTxCacheIndex[A, V, B], Option[InsertBodySummary]) =
    bodyState.get(eb) match
      case None | Some(BodyState.AlreadyInserted(_, _)) => (this, None)
      case Some(BodyState.NotYetInserted(rc)) =>
        val ((n, tracked, acquired, validated), txs) =
          refs.txReferences(body).foldLeft(((0, 0, 0, 0), txState)) {
            case (((n, t, a, v), ts), txh) =>
              val (dt, da, dv) = ts.get(txh) match
                case None                                 => (0, 0, 0)
                case Some(TxState.NotYetInserted(_))      => (1, 0, 0)
                case Some(TxState.AlreadyInserted(_, _))  => (1, 1, 0)
                case Some(TxState.AlreadyValidated(_, _)) => (1, 1, 1)
              val bumped = ts
                .get(txh)
                .fold(TxState.NotYetInserted(RefCount.one): TxState[A, V])(tx =>
                  tx.withRefs(tx.refs.inc)
                )
              ((n + 1, t + dt, a + da, v + dv), ts.updated(txh, bumped))
          }
        (
          copy(
            bodyState = bodyState.updated(eb, BodyState.AlreadyInserted(rc, body)),
            txState = txs
          ),
          Some(InsertBodySummary(n, tracked, acquired, validated, txs.size))
        )

  /** `lookupBody` (`Reference.hs:455-458` at 2416493b): the body of `eb`, if held. */
  def lookupBody(eb: EbHash): Option[B] = bodyState.get(eb) match
    case Some(BodyState.AlreadyInserted(_, b))    => Some(b)
    case Some(BodyState.NotYetInserted(_)) | None => None

  /** `insertUnappliedTx` (`Reference.hs:414-425` at 2416493b): a fetched transaction recorded with
    * `a`, its count unchanged, and its state before; a transaction no held body references is not
    * tracked.
    */
  def insertUnappliedTx(txh: LeiosTxHash, a: A): (LeiosTxCacheIndex[A, V, B], TxArrivalPrior) =
    txState.get(txh) match
      case None => (this, TxArrivalPrior.WasUntracked)
      case Some(tx) =>
        val prior = tx match
          case TxState.NotYetInserted(_) => TxArrivalPrior.WasNotYetInserted
          case TxState.AlreadyInserted(_, _) | TxState.AlreadyValidated(_, _) =>
            TxArrivalPrior.WasAlreadyHeld
        (copy(txState = txState.updated(txh, TxState.AlreadyInserted(tx.refs, a))), prior)

  /** `insertAppliedTx` (`Reference.hs:427-443` at 2416493b): an applied transaction recorded with
    * `v`, its count unchanged; a no-op for a transaction no held body references.
    */
  def insertAppliedTx(txh: LeiosTxHash, v: V): LeiosTxCacheIndex[A, V, B] =
    txState
      .get(txh)
      .fold(this)(tx => copy(txState = txState.updated(txh, TxState.AlreadyValidated(tx.refs, v))))

  /** `lookupTx` (`Reference.hs:445-453` at 2416493b): the payload of a transaction held, `Left`
    * when inserted but not applied, `Right` when applied; none when it is not tracked or only
    * referenced.
    */
  def lookupTx(txh: LeiosTxHash): Option[Either[A, V]] = txState.get(txh) match
    case Some(TxState.AlreadyInserted(_, a))    => Some(Left(a))
    case Some(TxState.AlreadyValidated(_, v))   => Some(Right(v))
    case Some(TxState.NotYetInserted(_)) | None => None

object LeiosTxCacheIndex:
  /** `maxAnnouncementCount` (`LeiosTxCache/API.hs:150-155` at ouroboros-consensus 2416493b): the
    * announcements retained.
    */
  val MaxAnnouncementCount: Int = LeiosVoteState.Window

  private given Ordering[RbHash] = Ordering.by[RbHash, xyz.kd5ujc.scarab.codec.Bytes](_.bytes)

  /** `emptyLeiosTxCacheIndex` (`Reference.hs:127-138` at 2416493b). */
  def empty[A, V, B]: LeiosTxCacheIndex[A, V, B] =
    LeiosTxCacheIndex(SortedMap.empty, 0, Map.empty, Map.empty, BigInt(0))
