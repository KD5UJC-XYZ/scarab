// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.ledger.{Header, HeaderVrf}

/** What chain selection looks at in the tip block of a chain.
  *
  * @param issuer
  *   The block issuer's cold key; Byron blocks have none.
  * @param issueNo
  *   The operational certificate's issue number.
  * @param tieBreak
  *   The VRF output that breaks ties (TPraos: the leader certificate's, Praos: the single one).
  */
final case class SelectView(
    blockNo: BigInt,
    slot: BigInt,
    issuer: Option[Bytes],
    issueNo: BigInt,
    tieBreak: Option[Bytes],
    boundary: Boolean = false,
    era: Option[Era] = None
)

object SelectView:
  def of(header: Header): SelectView = header match
    case h: Header.Shelley =>
      val vrf = h.vrf match
        case HeaderVrf.Praos(result)  => result.output
        case HeaderVrf.TPraos(_, ldr) => ldr.output
      SelectView(
        h.blockNo,
        h.slot,
        Some(h.issuerVkey),
        h.opCert.sequence,
        Some(vrf),
        era = Some(h.era)
      )
    case h: Header.Byron => SelectView(h.blockNo, h.slot, None, 0, None, h.boundary, Some(h.era))

/** The Praos chain selection rule (the `ChainOrder` of ouroboros-consensus for Praos and TPraos): a
  * candidate chain replaces ours only if it is strictly preferred, and only if the switch rolls
  * back no more than `k` blocks.
  *
  * Preference, in order: the longer chain; for chains of equal length whose tips were issued by the
  * same pool in the same slot, the higher operational-certificate issue number; otherwise the lower
  * VRF output of the tips. Ties that none of these decides keep the current chain.
  */
object ChainSelection:
  /** How the VRF tie-breaker applies. The unrestricted flavor compares any two tips; the restricted
    * flavor (Conway) only when their slots are at most `maxSlotDistance` apart.
    */
  enum Tiebreaker:
    case Unrestricted
    case Restricted(maxSlotDistance: BigInt)

  /** Conway restricts the VRF tie-breaker to tips within 5 slots of each other:
    * `Shelley/Ledger/Config.hs` lines 86-93 of ouroboros-consensus-cardano 4.2.0.x, at
    * ouroboros-consensus 82ecba32 (`isBeforeConway` gives `UnrestrictedVRFTiebreaker`, else
    * `RestrictedVRFTiebreaker 5`).
    */
  val ConwayRestriction: BigInt = 5

  def tiebreakerFor(era: Era): Tiebreaker =
    if era.ordinal >= Era.Conway.ordinal then Tiebreaker.Restricted(ConwayRestriction)
    else Tiebreaker.Unrestricted

  /** The `Ord` that `OutputVRF` derives from its `ByteArray` (cardano-crypto-class 2.5.1.0
    * `Cardano/Crypto/VRF/Class.hs:253-254`): `Ord ByteArray` of GHC 9.10's base (`Data.Array.Byte`)
    * compares the lengths first and only arrays of one length unsigned lexicographically (checked
    * with GHC 9.10.3: `[1,2,3]` is above `[2]`). A validated output is 64 bytes, but a header
    * decodes with one of any length (#712).
    */
  private def lower(a: Bytes, b: Bytes): Boolean =
    if a.size != b.size then a.size < b.size else a.compareTo(b) < 0

  /** Is `candidate` strictly preferred to `ours`? Tips of equal length are compared as upstream
    * `comparePraos` does (ouroboros-consensus 4.2.0.x, as in cardano-node 11.1.3:
    * `ouroboros-consensus-protocol/.../Protocol/Praos/Common.hs` lines 125-168, `issueNoArmed` at
    * 154, `vrfArmed` at 159, `slotDist` at 164):
    *   - the opcert issue numbers are compared only when both tips have the same issuer AND the
    *     same slot (then the VRF outputs coincide); the higher number wins;
    *   - otherwise, if the VRF comparison is armed (always for the unrestricted flavor, for the
    *     restricted one when the slot distance is `<= maxSlotDistance`, inclusive), the strictly
    *     lower VRF output wins;
    *   - everything else keeps the current chain.
    */
  def prefers(tiebreaker: Tiebreaker, ours: SelectView, candidate: SelectView): Boolean =
    if candidate.blockNo != ours.blockNo then candidate.blockNo > ours.blockNo
    // PBFT.hs:138–168 (ouroboros-consensus 82ecba32): an EBB repeats its predecessor's difficulty,
    // but extends its chain.
    else if noAcrossEraTiebreaker(ours, candidate) then false
    else if ours.issuer.isEmpty && candidate.issuer.isEmpty && ours.boundary != candidate.boundary
    then candidate.boundary
    else
      val issueNoArmed =
        ours.slot == candidate.slot && ours.issuer.isDefined && ours.issuer == candidate.issuer
      if issueNoArmed && ours.issueNo != candidate.issueNo then candidate.issueNo > ours.issueNo
      else
        val vrfArmed = tiebreaker match
          case Tiebreaker.Unrestricted => true
          case Tiebreaker.Restricted(distance) =>
            (ours.slot - candidate.slot).abs <= distance
        (ours.tieBreak, candidate.tieBreak) match
          case (Some(o), Some(c)) if vrfArmed => lower(c, o)
          case _                              => false

  private def noAcrossEraTiebreaker(ours: SelectView, candidate: SelectView): Boolean =
    (ours.era, candidate.era) match
      case (Some(a), Some(b)) => (a == Era.Byron) != (b == Era.Byron)
      case _                  => false

  /** HFC Protocol/ChainSel.hs:128–155 and Cardano/CanHardFork.hs:176–181 at consensus 82ecba329:
    * shared cross-era tie-breakers use the later era's configuration; Byron/Shelley has no
    * cross-era tie-breaker. Raw Ord remains unrestricted for ranking.
    */
  def prefersCandidate(config: Tiebreaker, ours: SelectView, candidate: SelectView): Boolean =
    val effective = (ours.era, candidate.era) match
      case (Some(a), Some(b)) if a != b =>
        tiebreakerFor(if a.ordinal > b.ordinal then a else b)
      case _ => config
    prefers(effective, ours, candidate)

  /** A block of our volatile chain. */
  final case class LocalBlock(point: Point, view: SelectView)

  /** Our chain as selection sees it: the immutable tip (never rolled back) and the volatile blocks
    * after it, oldest first.
    *
    * The places of the blocks by the hash of their point are looked up by a header's search for its
    * intersection with our chain (issue 1394), by chain selection for each block it walks (#810),
    * and by the Genesis projection of each candidate (#1499). A window of distinct blocks keeps
    * them in an [[AnchoredFragment]] of its points, which [[extended]] and [[rolledBackTo]] carry
    * along at the cost of the blocks they add and remove, as the reference's selection is an
    * `AnchoredFragment` that ChainSel extends and rolls back. A window built whole, by
    * [[Local.apply]] or [[copy]], indexes its blocks in one pass on its first lookup, and one that
    * holds a hash twice, or Origin as a block, keeps the places of every such block aside.
    */
  final class Local private (
      val anchor: Point,
      val anchorView: SelectView,
      val blocks: Vector[LocalBlock],
      carried: Option[AnchoredFragment[Point.Block]]
  ):
    def tipView: SelectView = blocks.lastOption.fold(anchorView)(_.view)

    private lazy val index: Local.Index =
      carried.fold(Local.Index.of(anchor, blocks))(Local.Index.Chain(_))

    def contains(point: Point): Boolean =
      point == anchor || (index match
        case Local.Index.Chain(fragment) => fragment.pointOnFragment(point)
        case irregular: Local.Index.Irregular =>
          irregular.placesOf(pointHash(point)).exists(i => blocks.lift(i).exists(_.point == point)))

    /** The indices in `blocks` of the blocks whose point has `hash` (`None`: Origin), oldest first.
      */
    def placesOf(hash: Option[Bytes]): Vector[Int] = index match
      case Local.Index.Chain(fragment) =>
        hash.flatMap(fragment.offsetOfHash).filter(_ > 0).map(_ - 1).toVector
      case irregular: Local.Index.Irregular => irregular.placesOf(hash)

    /** The index in `blocks` of the newest block at `point`, if one is. */
    private def lastPlace(point: Point): Option[Int] =
      placesOf(pointHash(point)).findLast(i => blocks.lift(i).exists(_.point == point))

    /** The first index in `blocks` of a block at `point`, if one is. */
    private def firstPlace(point: Point): Option[Int] = index match
      case Local.Index.Chain(fragment) => fragment.offsetOf(point).filter(_ > 0).map(_ - 1)
      case _: Local.Index.Irregular    => Option(blocks.indexWhere(_.point == point)).filter(_ >= 0)

    /** The points of the blocks that are blocks, oldest first: the selection as a Genesis fragment
      * reads it.
      */
    def blockPoints: Vector[Point.Block] = index match
      case Local.Index.Chain(fragment) => fragment.toOldestFirst
      case _: Local.Index.Irregular =>
        blocks.map(_.point).collect { case block: Point.Block => block }

    /** Blocks that rolling back to `point` removes. */
    def depthTo(point: Point): Int =
      if point == anchor then blocks.size else blocks.size - 1 - firstPlace(point).getOrElse(-1)

    /** The window after rolling back to `point` (the anchor or one of the blocks), none if it is
      * neither.
      */
    def rolledBackTo(point: Point): Option[Local] =
      if point == anchor then Some(firstBlocks(0))
      else firstPlace(point).map(i => firstBlocks(i + 1))

    /** This window with only its first `count` blocks, its index cut with them. */
    private def firstBlocks(count: Int): Local =
      new Local(
        anchor,
        anchorView,
        blocks.take(count),
        index match
          case Local.Index.Chain(fragment) => Some(fragment.headAt(count))
          case _: Local.Index.Irregular    => None
      )

    /** The window after a block is appended at the tip and the `immutable` oldest blocks (counting
      * the new one, which only happens for a `k` of zero) become the anchor: the same window
      * [[Local.of]] builds from the chain that `VolatileChain.extend` returns.
      */
    def extended(block: LocalBlock, immutable: Int): Local =
      // nothing leaves the window unless `immutable` is positive (then `gone` holds the new anchor)
      val (gone, kept) = (blocks :+ block).splitAt(immutable)
      val carriedOn = (index, block.point) match
        // a block already in the window, or at its anchor, makes the window irregular
        case (Local.Index.Chain(fragment), added: Point.Block)
            if fragment.offsetOfHash(added.hash).isEmpty =>
          Some(fragment.addBlock(added).anchorNewest(kept.size))
        case _ => None
      gone.lastOption.fold(new Local(anchor, anchorView, kept, carriedOn))(newAnchor =>
        new Local(newAnchor.point, newAnchor.view, kept, carriedOn)
      )

    /** The newest point of `candidate` on this window, as its index in the candidate's points (0
      * the base, `i + 1` the header at index `i`) and its place in the window (0 the anchor,
      * `i + 1` the block at index `i`, the newest such block); none when no point of the candidate
      * is on it. Searched from the candidate's tip, each point looked up in the window's index, so
      * the search costs the candidate's headers after the window, not the window (#1499).
      */
    def intersection(candidate: Candidate): Option[(Int, Int)] =
      def pointAt(i: Int): Point =
        if i == 0 then candidate.base else candidate.headers(i - 1).point
      Iterator
        .range(candidate.headers.size, -1, -1)
        .find(i => contains(pointAt(i)))
        .map(common => (common, lastPlace(pointAt(common)).fold(0)(_ + 1)))

    /** A window like this one, its index built afresh on its first lookup. */
    def copy(
        anchor: Point = this.anchor,
        anchorView: SelectView = this.anchorView,
        blocks: Vector[LocalBlock] = this.blocks
    ): Local = Local(anchor, anchorView, blocks)

    override def equals(other: Any): Boolean = other match
      case that: Local =>
        (this eq that) ||
        (anchor == that.anchor && anchorView == that.anchorView && blocks == that.blocks)
      case _ => false

    override def hashCode: Int = (anchor, anchorView, blocks).##

    override def toString: String = s"Local($anchor, $anchorView, $blocks)"

  object Local:
    def apply(anchor: Point, anchorView: SelectView, blocks: Vector[LocalBlock]): Local =
      new Local(anchor, anchorView, blocks, None)

    /** Build the window from the whole chain: one pass over its `k` blocks. This is the reference
      * that the incremental updates [[Local.rolledBackTo]] and [[Local.extended]] must equal.
      */
    def of[S](chain: VolatileChain[S], view: BlockRef => SelectView): Local =
      Local(
        chain.anchor.point,
        chain.anchor.block.fold(SelectView(-1, 0, None, 0, None))(view),
        chain.blocks.map((r, _) => LocalBlock(r.point, view(r)))
      )

    /** Where a window's blocks are by hash. */
    private sealed trait Index

    private object Index:
      /** Distinct blocks, none at Origin, none at the anchor's hash: the fragment of their points.
        */
      final case class Chain(fragment: AnchoredFragment[Point.Block]) extends Index

      /** Any other window: the first place of each hash, the places of a hash held more than once,
        * and those of Origin.
        */
      final case class Irregular(
          first: Map[Bytes, Int],
          origins: Vector[Int],
          repeated: Map[Bytes, Vector[Int]]
      ) extends Index:
        def placesOf(hash: Option[Bytes]): Vector[Int] =
          hash.fold(origins)(h =>
            repeated.get(h).orElse(first.get(h).map(Vector(_))).getOrElse(Vector.empty)
          )

      /** Index `blocks` in one pass. */
      def of(anchor: Point, blocks: Vector[LocalBlock]): Index =
        val points = blocks.collect { case LocalBlock(point: Point.Block, _) => point }
        val fragment = AnchoredFragment.fromOldestFirst(anchor, points)
        if points.sizeIs == blocks.size && fragment.indexSize == points.size + anchorKeys(anchor)
        then Chain(fragment)
        else irregular(blocks)

      private def anchorKeys(anchor: Point): Int = anchor match
        case Point.Block(_, _) => 1
        case Point.Origin      => 0

      private def irregular(blocks: Vector[LocalBlock]): Irregular =
        val first: Map[Bytes, Int] = Map.from(
          blocks.indices.reverseIterator.flatMap(i =>
            blocks.lift(i).flatMap(b => pointHash(b.point)).map(_ -> i)
          )
        )
        val origins: Vector[Int] =
          blocks.iterator.zipWithIndex.collect {
            case (b, i) if b.point == Point.Origin => i
          }.toVector
        val repeated: Map[Bytes, Vector[Int]] =
          if blocks.sizeIs == first.size || blocks.sizeIs == first.size + origins.size then
            Map.empty
          else
            blocks.iterator.zipWithIndex
              .flatMap((b, i) => pointHash(b.point).map(_ -> i))
              .toVector
              .groupMap(_._1)(_._2)
              .filter(_._2.sizeIs > 1)
        Irregular(first, origins, repeated)

  /** What a peer's chain looks like to us: the point where it joined our chain and the headers it
    * announced after it. The headers are contiguous.
    */
  final case class Candidate(base: Point, headers: Vector[Header]):
    def tip: Point = headers.lastOption.fold(base)(_.point)
    def tipHash: Option[Bytes] = headers.lastOption.map(_.hash).orElse(pointHash(base))

    /** `None` unless the header continues the candidate. */
    def extend(header: Header): Option[Candidate] =
      Option.when(header.prevHash.isDefined && header.prevHash == tipHash)(
        copy(headers = headers :+ header)
      )

    /** Back to `point`, if it is the base or one of the headers. */
    def rollback(point: Point): Option[Candidate] =
      if point == base then Some(copy(headers = Vector.empty))
      else
        headers.indexWhere(_.point == point) match
          case -1 => None
          case i  => Some(copy(headers = headers.take(i + 1)))

    /** Forget the leading part up to `point` (a point of the candidate that is now our chain). */
    def rebase(point: Point): Candidate =
      if point == base then this
      else
        headers.indexWhere(_.point == point) match
          case -1 => this
          case i  => Candidate(point, headers.drop(i + 1))

  private def pointHash(point: Point): Option[Bytes] = point match
    case Point.Origin         => None
    case Point.Block(_, hash) => Some(hash)

  enum Decision:
    /** Stay on our chain. */
    case Keep

    /** Roll our chain back to `rollbackTo` (`depth` blocks) and adopt `take`, the candidate's
      * headers after it.
      */
    case Switch(rollbackTo: Point, depth: Int, take: Vector[Header])

    /** No point of the candidate is on our chain any more: the peer's chain forked from ours before
      * our immutable tip, or ours moved away from where the candidate joined it.
      */
    case Detached

  def decide(tiebreaker: Tiebreaker, local: Local, candidate: Candidate): Decision =
    val headers = candidate.headers
    // The points of the candidate are its base, then its headers: point `i` is the base for 0 and
    // the header `i - 1` after it. The last one on our chain is searched from the tip, because the
    // candidate of a peer that is behind us is entirely ours and the first probe says so; building
    // the point of every header, and probing each against our window, made the follower's time per
    // block grow with the length of such a candidate (issue 133).
    def pointAt(i: Int): Point = if i == 0 then candidate.base else headers(i - 1).point
    Iterator.range(headers.size, -1, -1).find(i => local.contains(pointAt(i))) match
      case None         => Decision.Detached
      case Some(common) =>
        // no headers after the common point: the whole candidate is on our chain
        NonEmptyVector.fromVector(headers.drop(common)) match
          case None       => Decision.Keep
          case Some(take) =>
            // AnchoredFragment.hs:192–196 (ouroboros-consensus 82ecba32) compares an empty selected
            // fragment by point, not its anchor's selection view. In particular a boundary block
            // may repeat blockNo.
            if local.blocks.isEmpty || prefersCandidate(
                tiebreaker,
                local.tipView,
                SelectView.of(take.last)
              )
            then
              val at = pointAt(common)
              Decision.Switch(at, local.depthTo(at), take.toVector)
            else Decision.Keep
