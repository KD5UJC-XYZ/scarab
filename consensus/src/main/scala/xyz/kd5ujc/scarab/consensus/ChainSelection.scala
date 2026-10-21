// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

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
      SelectView(h.blockNo, h.slot, Some(h.issuerVkey), h.opCert.sequence, Some(vrf), era = Some(h.era))
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
    * `Shelley/Ledger/Config.hs` lines 86-93 of ouroboros-consensus-cardano 4.2.0.x
    * (`isBeforeConway` gives `UnrestrictedVRFTiebreaker`, else `RestrictedVRFTiebreaker 5`).
    */
  val ConwayRestriction: BigInt = 5

  def tiebreakerFor(era: Era): Tiebreaker =
    if era.ordinal >= Era.Conway.ordinal then Tiebreaker.Restricted(ConwayRestriction)
    else Tiebreaker.Unrestricted

  /** Unsigned lexicographic order of two byte strings of the same length. */
  private def lower(a: Bytes, b: Bytes): Boolean =
    a.value
      .zip(b.value)
      .collectFirst {
        case (x, y) if (x & 0xff) != (y & 0xff) => (x & 0xff) < (y & 0xff)
      }
      .getOrElse(false)

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
    // PBFT.hs:137–170: an EBB repeats its predecessor's difficulty, but extends its chain.
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

  /** HFC Protocol/ChainSel.hs:128–155 and Cardano/CanHardFork.hs:176–181 at
    * consensus 82ecba329: shared cross-era tie-breakers use the later era's configuration;
    * Byron/Shelley has no cross-era tie-breaker. Raw Ord remains unrestricted for ranking.
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
    */
  final case class Local(anchor: Point, anchorView: SelectView, blocks: Vector[LocalBlock]):
    def tipView: SelectView = blocks.lastOption.fold(anchorView)(_.view)
    def contains(point: Point): Boolean = point == anchor || blocks.exists(_.point == point)

    /** Blocks that rolling back to `point` removes. */
    def depthTo(point: Point): Int =
      if point == anchor then blocks.size else blocks.size - 1 - blocks.indexWhere(_.point == point)

    /** The window after rolling back to `point` (the anchor or one of the blocks), none if it is
      * neither.
      */
    def rolledBackTo(point: Point): Option[Local] =
      if point == anchor then Some(copy(blocks = Vector.empty))
      else
        blocks.indexWhere(_.point == point) match
          case -1 => None
          case i  => Some(copy(blocks = blocks.take(i + 1)))

    /** The window after a block is appended at the tip and the `immutable` oldest blocks (counting
      * the new one, which only happens for a `k` of zero) become the anchor: the same window
      * [[Local.of]] builds from the chain that `VolatileChain.extend` returns.
      */
    def extended(block: LocalBlock, immutable: Int): Local =
      val grown = blocks :+ block
      if immutable <= 0 then copy(blocks = grown)
      else
        val (gone, kept) = grown.splitAt(immutable)
        Local(gone.last.point, gone.last.view, kept)

  object Local:
    /** Build the window from the whole chain: one pass over its `k` blocks. This is the reference
      * that the incremental updates [[Local.rolledBackTo]] and [[Local.extended]] must equal.
      */
    def of[S](chain: VolatileChain[S], view: BlockRef => SelectView): Local =
      Local(
        chain.anchor.point,
        chain.anchor.block.fold(SelectView(-1, 0, None, 0, None))(view),
        chain.blocks.map((r, _) => LocalBlock(r.point, view(r)))
      )

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
      case None                                   => Decision.Detached
      case Some(common) if common == headers.size => Decision.Keep
      case Some(common) =>
        val take = headers.drop(common)
        val view = SelectView.of(take.last)
        // AnchoredFragment.hs:195–201 compares an empty selected fragment by point, not
        // its anchor's selection view. In particular a boundary block may repeat blockNo.
        if local.blocks.isEmpty || prefersCandidate(tiebreaker, local.tipView, view) then
          val at = pointAt(common)
          Decision.Switch(at, local.depthTo(at), take)
        else Decision.Keep
