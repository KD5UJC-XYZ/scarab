// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.collection.immutable.SortedMap

/** The peer metrics board: which peer presented each of the last 180 slots first, as a header
  * (upstreamness) and as a block (fetchy-ness). This is the feedback from the chain to the
  * governor: the hot-demotion pick ranks hot peers by `upstreamness + fetchynessBlocks`
  * ([[PeerMetrics.hotScores]]), so that a churn round that lowers the active target drops the peers
  * that were never first.
  *
  * A port of `Ouroboros.Network.PeerSelection.PeerMetric` (ouroboros-network 1.2.0.0,
  * `ouroboros-network/lib/Ouroboros/Network/PeerSelection/PeerMetric.hs`; the lines below are those
  * of the file at that tag, which the copy of main in the workspace agrees with):
  *
  *   - two boards keyed by slot, each holding the earliest arrival of that slot (`SlotMetric`, 75;
  *     `metricsTracer`, 367-396): a later arrival never replaces an earlier one, a slot beyond
  *     [[PeerMetrics.MaxEntries]] entries evicts the smallest slot, and an arrival for a slot below
  *     all 180 of a full board changes nothing (the `minSlotNo /= slot` test, 388);
  *   - a registry of the slot at which a peer joined and one of the slot at which it was last seen
  *     (`peerRegistryTracer`, 316-358, `insertPeer`, 275-306): a peer that contributed nothing for
  *     a whole board is new again when it returns, and entries at or below the oldest slot of the
  *     boards are dropped, so the registries are as small as the boards;
  *   - the correction for peers that joined late (`adjustAvg`, 593-618) is the constant 0 in this
  *     release (`adjustAvg _ _ _ _ _ = 0`, the real formula is commented out), so it is not
  *     implemented, and the averages `insertPeer` records for it are not kept.
  *
  * The value is pure; the node holds it in a `Ref` and updates it once per batch of headers and
  * once per range of blocks ([[reportHeaders]]). `A` is the peer's address.
  */
final class PeerMetrics[A] private (
    private val headerBoard: SortedMap[Long, PeerMetrics.Header[A]],
    private val fetchBoard: SortedMap[Long, PeerMetrics.Fetch[A]],
    private val registry: Map[A, Long],
    private val lastSeen: Map[A, Long],
    private val limit: Int
):
  import PeerMetrics.*

  /** The oldest slot either board holds, or 0 when one of them is empty (`afterSlot`, 335-350: the
    * `min` of two `Maybe` s is `Nothing` if either is, and `fromMaybe 0` makes it slot 0).
    */
  private def oldest: Long =
    (headerBoard.headOption, fetchBoard.headOption) match
      case (Some((h, _)), Some((f, _))) => h.min(f)
      case _                            => 0L

  /** `peerRegistryTracer` (323-329): drop what the boards no longer cover, add the peer if it is
    * new, remember that it was seen at `slot`. The order matters: `insertPeer` reads the last-seen
    * registry as it was before this report.
    */
  private def witnessed(peer: A, slot: Long): (Map[A, Long], Map[A, Long]) =
    val floor = oldest
    val registry1 = registry.filter((_, joined) => joined > floor)
    val lastSeen1 = lastSeen.filter((_, seen) => seen > floor)
    val registry2 =
      if lastSeen1.contains(peer) || registry1.contains(peer) then registry1
      else registry1.updated(peer, slot)
    (registry2, lastSeen1.updated(peer, slot))

  /** The header of `slot` reached us from `peer` at `at` (`reportHeader`). */
  def reportHeader(peer: A, slot: Long, at: Time): PeerMetrics[A] =
    val (joined, seen) = witnessed(peer, slot)
    new PeerMetrics(
      board(headerBoard, slot, Header(peer, at), limit)(_.at),
      fetchBoard,
      joined,
      seen,
      limit
    )

  /** The block of `slot`, of `size` bytes, was delivered by `peer` at `at` (`reportFetch`). */
  def reportFetch(peer: A, slot: Long, size: Long, at: Time): PeerMetrics[A] =
    val (joined, seen) = witnessed(peer, slot)
    new PeerMetrics(
      headerBoard,
      board(fetchBoard, slot, Fetch(peer, size, at), limit)(_.at),
      joined,
      seen,
      limit
    )

  /** Several headers of one batch: they reached us together, so they share the arrival time. */
  def reportHeaders(peer: A, slots: Iterable[Long], at: Time): PeerMetrics[A] =
    slots.foldLeft(this)((m, slot) => m.reportHeader(peer, slot, at))

  /** Several blocks of one range, each as `(slot, size)`, delivered together. */
  def reportFetches(peer: A, blocks: Iterable[(Long, Long)], at: Time): PeerMetrics[A] =
    blocks.foldLeft(this) { case (m, (slot, size)) => m.reportFetch(peer, slot, size, at) }

  /** For each peer, the number of slots in the board for which it presented the header first
    * (`upstreamyness`, 428-478). Every registered peer is present, with 0 if it holds no slot.
    */
  def upstreamness: Map[A, Int] = counted(headerBoard.valuesIterator.map(_.peer))

  /** For each peer, the number of blocks it delivered first (`fetchynessBlocks`, 537-585). */
  def fetchynessBlocks: Map[A, Int] = counted(fetchBoard.valuesIterator.map(_.peer))

  /** For each peer, the bytes of the blocks it delivered first (`fetchynessBytes`, 483-531). */
  def fetchynessBytes: Map[A, Long] =
    val sums = fetchBoard.valuesIterator.foldLeft(Map.empty[A, Long]) { (m, f) =>
      m.updated(f.peer, m.getOrElse(f.peer, 0L) + f.size)
    }
    registry.keysIterator.foldLeft(sums)((m, p) => if m.contains(p) then m else m.updated(p, 0L))

  /** The slot at which each peer joined the board (`joinedPeerMetricAt`, 399-415). */
  def joinedAt: Map[A, Long] = registry

  /** The hot-demotion score of every peer the metrics know (`deadlineHotScores`, `Policies.hs`
    * 104-111 of main, `hotDemotionPolicy` 127-147 at the tag): `upstreamness + fetchynessBlocks`,
    * and the slot the peer joined at as the tie-break. A peer that is not here ranks as
    * [[HotScore.Unknown]], below every peer that is (`Map.findWithDefault (0, Nothing)`).
    */
  def hotScores: Map[A, HotScore] =
    val total = upstreamness.foldLeft(fetchynessBlocks) { case (m, (p, n)) =>
      m.updated(p, m.getOrElse(p, 0) + n)
    }
    total.map((p, v) => p -> HotScore(v, registry.get(p)))

  /** The number of slots on the headers board and on the fetched board. */
  def sizes: (Int, Int) = (headerBoard.size, fetchBoard.size)

  /** Peers with their counts; every registered peer is in it (the reference adds the registry with
    * the correction, which is 0).
    */
  private def counted(peers: Iterator[A]): Map[A, Int] =
    val counts =
      peers.foldLeft(Map.empty[A, Int])((acc, p) => acc.updated(p, acc.getOrElse(p, 0) + 1))
    registry.keysIterator.foldLeft(counts)((acc, p) =>
      if acc.contains(p) then acc else acc.updated(p, 0)
    )

object PeerMetrics:
  /** `maxEntriesToTrack`: one hour of blocks on mainnet (`Policies.hs` 52-61). */
  val MaxEntries = 180

  private final case class Header[A](peer: A, at: Time)
  private final case class Fetch[A](peer: A, size: Long, at: Time)

  /** Empty boards of `limit` slots each (the reference's `maxEntriesToTrack`). */
  def empty[A](limit: Int = MaxEntries): PeerMetrics[A] =
    new PeerMetrics(SortedMap.empty, SortedMap.empty, Map.empty, Map.empty, limit)

  /** `metricsTracer` (367-396) for either board: the earliest arrival of a slot wins, the board
    * keeps the newest `limit` slots, and an arrival for a slot older than all of a full board is
    * dropped.
    */
  private def board[V](current: SortedMap[Long, V], slot: Long, entry: V, limit: Int)(
      at: V => Time
  ): SortedMap[Long, V] =
    current.get(slot) match
      case Some(existing) =>
        if at(existing) > at(entry) then current.updated(slot, entry) else current
      case None =>
        val grown = current.updated(slot, entry)
        if grown.size > limit then grown - grown.firstKey else grown
