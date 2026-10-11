// SPDX-License-Identifier: Apache-2.0
package lab.node.consensus

import lab.cbor.Bytes
import lab.network.Era
import lab.network.ChainSync.Point
import lab.node.{Header, HeaderVrf}

/** What chain selection looks at in the tip block of a chain. */
final case class SelectView(
    blockNo: BigInt,
    slot: BigInt,
    /** The block issuer's cold key; Byron blocks have none. */
    issuer: Option[Bytes],
    /** The operational certificate's issue number. */
    issueNo: BigInt,
    /** The VRF output that breaks ties (TPraos: the leader certificate's, Praos: the single one).
      */
    tieBreak: Option[Bytes]
)

object SelectView:
  def of(header: Header): SelectView = header match
    case h: Header.Shelley =>
      val vrf = h.vrf match
        case HeaderVrf.Praos(result)  => result.output
        case HeaderVrf.TPraos(_, ldr) => ldr.output
      SelectView(h.blockNo, h.slot, Some(h.issuerVkey), h.opCert.sequence, Some(vrf))
    case h: Header.Byron => SelectView(h.blockNo, h.slot, None, 0, None)

/** The Praos chain selection rule (the `ChainOrder` of ouroboros-consensus for Praos and TPraos): a
  * candidate chain replaces ours only if it is strictly preferred, and only if the switch rolls
  * back no more than `k` blocks.
  *
  * Preference, in order: the longer chain; for chains of equal length whose tips were issued by the
  * same pool, the higher operational-certificate issue number; otherwise the lower VRF output of
  * the tips. Ties that none of these decides keep the current chain.
  */
object ChainSelection:
  /** How the VRF tie-breaker applies. The unrestricted flavor compares any two tips; the restricted
    * flavor (Conway) only when their slots are at most `maxSlotDistance` apart.
    */
  enum Tiebreaker:
    case Unrestricted
    case Restricted(maxSlotDistance: BigInt)

  /** Conway restricts the VRF tie-breaker to tips within 5 slots of each other. */
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

  /** Is `candidate` strictly preferred to `ours`? */
  def prefers(tiebreaker: Tiebreaker, ours: SelectView, candidate: SelectView): Boolean =
    if candidate.blockNo != ours.blockNo then candidate.blockNo > ours.blockNo
    else if ours.issuer.isDefined && ours.issuer == candidate.issuer &&
      ours.issueNo != candidate.issueNo
    then candidate.issueNo > ours.issueNo
    else
      val allowed = tiebreaker match
        case Tiebreaker.Unrestricted => true
        case Tiebreaker.Restricted(distance) =>
          (ours.slot - candidate.slot).abs <= distance
      (ours.tieBreak, candidate.tieBreak) match
        case (Some(o), Some(c)) if allowed => lower(c, o)
        case _                             => false

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
    val points = candidate.base +: candidate.headers.map(_.point)
    points.lastIndexWhere(local.contains) match
      case -1                                  => Decision.Detached
      case common if common == points.size - 1 => Decision.Keep
      case common =>
        val take = candidate.headers.drop(common)
        val view = SelectView.of(take.last)
        if prefers(tiebreaker, local.tipView, view) then
          val at = points(common)
          Decision.Switch(at, local.depthTo(at), take)
        else Decision.Keep
