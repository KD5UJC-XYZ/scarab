// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.leios

import cats.Order
import cats.data.NonEmptySet
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.FiniteDuration
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

import LeiosOrders.given

/** The image of the elections' focus as a multiset of endorser blocks (`focusedEbs`'s `MultiSet`,
  * `LeiosDemoTypes.hs` 553-562 of ouroboros-consensus 2416493b): an election moving away from an
  * endorser block does not speak for another election that focuses the same one. Every count is
  * positive.
  */
opaque type FocusedEbs = Map[EbHash, Int]

object FocusedEbs:
  val empty: FocusedEbs = Map.empty

  extension (m: FocusedEbs)
    def contains(h: EbHash): Boolean = m.contains(h)
    def count(h: EbHash): Int = m.getOrElse(h, 0)
    def inserted(h: EbHash): FocusedEbs = m.updated(h, count(h) + 1)
    def deleted(h: EbHash): FocusedEbs =
      count(h) match
        case n if n > 1 => m.updated(h, n - 1)
        case _          => m - h

/** The state of the Leios fetch logic (`LeiosOutstanding`, `LeiosDemoTypes.hs` 523-593 at
  * 2416493b), over the peers' identifiers `P`:
  *
  *   - `ebState`: every tracked endorser block, from the announcement that takes an election's
  *     focus, a verified certificate or the start-up seeding, never from an offer;
  *     `ebsPerMaxAnnouncementSlot` is its reverse index by greatest slot, for pruning;
  *   - `elFocus`: the endorser block each election fetches, and `focusedEbs` its image: a peer is
  *     asked for nothing of an endorser block no election focuses;
  *   - `outstandingPrunedSlot`: the slot everything has been pruned up to;
  *   - `numMissingBodies`: how many tracked endorser blocks have no body and are not being forged;
  *   - `requestedEbPeers`, `requestedBytesSizePerPeer` and `requestedJobsPerPeer`: what is in
  *     flight, per endorser block and per peer, the only bound on what a peer is asked for; a
  *     peer's jobs in flight are keyed by the pool they were picked from as well as the endorser
  *     block ([[LeiosJobPoolOf]], part of the Improvement of [[LeiosJobPoolId]]), its bytes are one
  *     sum, as the reference's;
  *   - `prng`: the decision loop's generator, which shuffles the job a peer is given;
  *   - `nextJobPool`: the id the next pool of jobs installed takes, which no prune resets, so a
  *     request in flight across a prune never names a pool installed after it ([[LeiosJobPoolId]],
  *     an Improvement over the reference, which has no such count).
  */
final case class LeiosOutstanding[P] private[leios] (
    ebState: Map[EbHash, EbState],
    ebsPerMaxAnnouncementSlot: SortedMap[Word64, NonEmptySet[EbHash]],
    elFocus: SortedMap[ElId, EbHash],
    focusedEbs: FocusedEbs,
    outstandingPrunedSlot: Word64,
    numMissingBodies: Int,
    requestedEbPeers: Map[EbHash, NonEmptySet[P]],
    requestedBytesSizePerPeer: SortedMap[P, BytesSize],
    requestedJobsPerPeer: SortedMap[P, Map[LeiosJobPoolOf, NonEmptySet[Int]]],
    prng: StdGen,
    nextJobPool: LeiosJobPoolId
):
  /** `isFocusedEb`: whether some election is fetching this endorser block. */
  def isFocused(h: EbHash): Boolean = focusedEbs.contains(h)

  /** `acquireEbBody` (`LeiosDemoTypes.hs` 812-824 at 2416493b): the body's write is durable, so it
    * is held, with the jobs the write still leaves to fetch. An endorser block pruned meanwhile is
    * left untracked, and one already held keeps its pool. A pool installed takes the next id
    * ([[nextJobPool]]).
    */
  def acquireEbBody(h: EbHash, jobs: LeiosJobPool): LeiosOutstanding[P] =
    ebState.get(h) match
      case Some(EbState(slot, onset, EbFetchState.NoBody | EbFetchState.BodyImminent)) =>
        alterEbState(h)(_ =>
          Some(EbState(slot, onset, EbFetchState.BodyAcquired(jobs, nextJobPool)))
        ).copy(nextJobPool = nextJobPool.next)
      case Some(EbState(_, _, EbFetchState.BodyAcquired(_, _))) | None => this

  /** `markBodyImminent` (`LeiosDemoTypes.hs` 827-837): the node's own forge is producing this
    * endorser block. A held body's pool is emptied, keeping its id: nothing of it is fetched.
    */
  def markBodyImminent(h: EbHash, slot: Word64): LeiosOutstanding[P] =
    alterEbState(h) {
      case None => Some(EbState(slot, None, EbFetchState.BodyImminent))
      case Some(EbState(oldSlot, onset, fetch)) =>
        fetch match
          case EbFetchState.NoBody       => Some(EbState(oldSlot, onset, EbFetchState.BodyImminent))
          case EbFetchState.BodyImminent => None
          case EbFetchState.BodyAcquired(_, pool) =>
            Some(EbState(oldSlot, onset, EbFetchState.BodyAcquired(LeiosJobPool.empty, pool)))
    }

  /** `recordMaxAnnouncementSlot` (`LeiosDemoTypes.hs` 849-865): the endorser block is referenced at
    * this slot; the greatest slot is kept, so it is not pruned early, and the earliest onset, so
    * its age counts from its first announcement. No onset never replaces one.
    */
  def recordMaxAnnouncementSlot(
      h: EbHash,
      slot: Word64,
      onset: Option[FiniteDuration]
  ): LeiosOutstanding[P] =
    alterEbState(h) {
      case None => Some(EbState(slot, onset, EbFetchState.NoBody))
      case Some(EbState(oldSlot, oldOnset, fetch)) =>
        val newSlot = if slot.toBigInt > oldSlot.toBigInt then slot else oldSlot
        val newOnset = (onset, oldOnset) match
          case (None, y)          => y
          case (x, None)          => x
          case (Some(a), Some(b)) => Some(if a <= b then a else b)
        if newSlot.toBigInt == oldSlot.toBigInt && newOnset == oldOnset then None
        else Some(EbState(newSlot, newOnset, fetch))
    }

  /** `focusElectionIfUnfocused` (`LeiosDemoTypes.hs` 939-942): the first announcement of an
    * election takes its focus.
    */
  def focusElectionIfUnfocused(el: ElId, h: EbHash): LeiosOutstanding[P] =
    if elFocus.contains(el) then this else focusElection(el, h)

  /** `focusCertifiedEb` (`LeiosDemoTypes.hs` 952-955): a verified certificate moves its election's
    * focus onto the endorser block it names, and tracks that endorser block.
    */
  def focusCertifiedEb(fields: AnnouncementFields): LeiosOutstanding[P] =
    trackCertifiedEb(fields.point).focusElection(fields.election, fields.ebHash)

  /** `trackCertifiedEb` (`LeiosDemoTypes.hs` 969-974): tracked unless too old. */
  def trackCertifiedEb(point: EbPoint): LeiosOutstanding[P] =
    if point.slot.toBigInt < outstandingPrunedSlot.toBigInt then this
    else recordMaxAnnouncementSlot(point.hash, point.slot, None)

  /** `focusElection` (`LeiosDemoTypes.hs` 996-1005): this election fetches this endorser block,
    * whatever it fetched before.
    */
  def focusElection(el: ElId, h: EbHash): LeiosOutstanding[P] =
    elFocus.get(el) match
      case Some(old) if old == h => this
      case old =>
        copy(
          elFocus = elFocus.updated(el, h),
          focusedEbs = old.fold(focusedEbs)(focusedEbs.deleted).inserted(h)
        )

  /** The pure part of `recordAnnouncedEb` (`LeiosDemoLogic.hs` 2097-2138 at 2416493b): an
    * announcement focuses its election if nothing does yet and it is not too old, and tracks its
    * endorser block. Also whether that gives the fetch logic anything new to do: the reference
    * wakes the loop only then.
    */
  def announced(
      fields: AnnouncementFields,
      onset: Option[FiniteDuration]
  ): (LeiosOutstanding[P], Boolean) =
    val el = fields.election
    val tooOld = el.slot.toBigInt < outstandingPrunedSlot.toBigInt
    val introducedFocus = !elFocus.contains(el)
    val next =
      if tooOld || !introducedFocus then this
      else
        recordMaxAnnouncementSlot(fields.ebHash, el.slot, onset)
          .focusElectionIfUnfocused(el, fields.ebHash)
    val skip = tooOld || !introducedFocus || ebState.get(fields.ebHash).exists(_.hasBody)
    (next, !skip)

  /** `pruneOutstandingToImmTip` (`LeiosDemoTypes.hs` 1070-1101 at 2416493b): drop the endorser
    * blocks whose greatest slot is below the immutable tip's and the elections below it, returning
    * the hashes dropped.
    */
  def prunedToImmTip(immTip: Word64): (Set[EbHash], LeiosOutstanding[P]) =
    val tip = immTip.toBigInt
    val (below, atOrAbove) = ebsPerMaxAnnouncementSlot.span((s, _) => s.toBigInt < tip)
    val pruned = below.valuesIterator.flatMap(_.toSortedSet).toSet
    val (stale, kept) = elFocus.span((el, _) => el.slot.toBigInt < tip)
    val prunedMissing = pruned.iterator.map(h => ebState.get(h).fold(0)(_.wantsBody)).sum
    (
      pruned,
      copy(
        ebState = ebState -- pruned,
        ebsPerMaxAnnouncementSlot = atOrAbove,
        elFocus = kept,
        focusedEbs = stale.valuesIterator.foldLeft(focusedEbs)(_.deleted(_)),
        outstandingPrunedSlot =
          if tip > outstandingPrunedSlot.toBigInt then immTip else outstandingPrunedSlot,
        numMissingBodies = numMissingBodies - prunedMissing
      )
    )

  /** `alterEbState` (`LeiosDemoTypes.hs` 1022-1061 at 2416493b): change an entry, keeping the
    * reverse index and [[numMissingBodies]] in step. `f` gives `None` to change nothing, and never
    * lowers the entry's greatest slot.
    */
  private def alterEbState(h: EbHash)(f: Option[EbState] => Option[EbState]): LeiosOutstanding[P] =
    val old = ebState.get(h)
    f(old).fold(this) { updated =>
      val oldSlot = old.map(_.slot.toBigInt)
      val index =
        if oldSlot.contains(updated.slot.toBigInt) then ebsPerMaxAnnouncementSlot
        else
          val without = old.fold(ebsPerMaxAnnouncementSlot) { o =>
            ebsPerMaxAnnouncementSlot.get(o.slot).fold(ebsPerMaxAnnouncementSlot) { set =>
              NonEmptySet
                .fromSet(set.toSortedSet - h)
                .fold(ebsPerMaxAnnouncementSlot - o.slot)(
                  ebsPerMaxAnnouncementSlot.updated(o.slot, _)
                )
            }
          }
          without.updated(
            updated.slot,
            without.get(updated.slot).fold(NonEmptySet.one(h))(_.add(h))
          )
      copy(
        ebState = ebState.updated(h, updated),
        numMissingBodies = numMissingBodies + updated.wantsBody - old.fold(0)(_.wantsBody),
        ebsPerMaxAnnouncementSlot = index
      )
    }

object LeiosOutstanding:
  /** `emptyLeiosOutstanding` (`LeiosDemoTypes.hs` 600-613 at 2416493b): nothing tracked or in
    * flight, pruned up to `prunedSlot`, and no pool of jobs installed yet.
    */
  def empty[P: Order](prng: StdGen, prunedSlot: Word64): LeiosOutstanding[P] =
    given Ordering[P] = Order[P].toOrdering
    LeiosOutstanding[P](
      Map.empty,
      SortedMap.empty,
      SortedMap.empty,
      FocusedEbs.empty,
      prunedSlot,
      0,
      Map.empty,
      SortedMap.empty,
      SortedMap.empty,
      prng,
      LeiosJobPoolId.first
    )

  /** `initializeLeiosOutstanding` (`LeiosDemoTypes.hs` 922-931 at 2416493b): every endorser block
    * whose whole closure the store holds, announced no earlier than the immutable tip, is held with
    * nothing left to fetch; everything else is left to fresh announcements and offers.
    */
  def initialized[P: Order](
      prng: StdGen,
      points: Iterable[EbPoint],
      immTip: Word64
  ): LeiosOutstanding[P] =
    points.foldLeft(empty[P](prng, immTip)) { (o, p) =>
      o.recordMaxAnnouncementSlot(p.hash, p.slot, None).acquireEbBody(p.hash, LeiosJobPool.empty)
    }
