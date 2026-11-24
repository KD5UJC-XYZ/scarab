// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import cats.data.NonEmptyVector
import xyz.kd5ujc.scarab.ledger.{HeaderDefect, HeaderError, ProviderDefect}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity

/** Header-only validation of a chain, the work upstream's ChainSync client does before a block is
  * wanted (`Ouroboros.Consensus.MiniProtocol.ChainSync.Client`): each header is checked against a
  * forecast of the ledger view of its slot, taken from `anchor`, a ledger state of the same chain
  * that is not newer than the header's parent, and against `H`, the header state (the Praos
  * chain-dependent state: nonces and operational-certificate counters) after its parent.
  *
  * Pure: the gate that drives it ([[xyz.kd5ujc.scarab.node.HeaderGate]]) owns the effects.
  */
trait HeaderRules[S, H]:
  /** The header state at the tip of `state`. */
  def at(state: S): H

  /** Check `header`, whose parent has header state `prev`. `anchor` is the newest ledger state
    * known to be on the header's chain (`tip` its block, if any). The forecast is from `anchor`, so
    * a header further than the stability window beyond it is [[HeaderRules.Outcome.Later]].
    */
  def validate(anchor: S, tip: Option[BlockRef], prev: H, header: Header): HeaderRules.Outcome[H]

object HeaderRules:
  /** The outcome of a header check that ran: valid, refused, or no verdict for a provider defect.
    */
  def outcome[H](checked: Either[HeaderError | ProviderDefect, H]): Outcome[H] = checked match
    case Right(next)                  => Outcome.Valid(next)
    case Left(error: HeaderError)     => Outcome.Invalid(error)
    case Left(defect: ProviderDefect) => Outcome.Unverifiable(defect)

  enum TimeForecast:
    case Known(onsetPicos: BigInt)
    case Unknown(reason: ForecastReason)
    case Invalid(error: HeaderError)

  /** A forecast ledger view is captured before resuming the patience bucket; header predicates and
    * cryptographic verification run afterwards (`ChainSync/Client.hs` 1881–1889).
    */
  enum Prepared[+H]:
    case Check(run: () => Outcome[H])
    case Unknown(reason: ForecastReason)
    case Invalid(error: HeaderError)

    /** No forecast for a defect of the node's own ([[Outcome.Undecided]]). */
    case Undecided(defect: HeaderDefect)

    def result: Outcome[H] = this match
      case Check(run)        => run()
      case Unknown(reason)   => Outcome.Later(reason)
      case Invalid(error)    => Outcome.Invalid(error)
      case Undecided(defect) => Outcome.Undecided(defect)

    def map[J](f: H => J): Prepared[J] = this match
      case Check(run) =>
        Check(() =>
          run() match
            case Outcome.Valid(next)          => Outcome.Valid(f(next))
            case Outcome.Invalid(error)       => Outcome.Invalid(error)
            case Outcome.Unverifiable(defect) => Outcome.Unverifiable(defect)
            case Outcome.Later(reason)        => Outcome.Later(reason)
            case Outcome.Undecided(defect)    => Outcome.Undecided(defect)
        )
      case Unknown(reason)   => Unknown(reason)
      case Invalid(error)    => Invalid(error)
      case Undecided(defect) => Undecided(defect)

  /** The reference time query uses the same intersection ledger as header validation. */
  trait Timing[S, H]:
    def tiebreaker(anchor: S): ChainSelection.Tiebreaker
    def forecastTime(anchor: S, tip: Option[BlockRef], previous: H, header: Header): TimeForecast
    def prepareValidation(
        anchor: S,
        tip: Option[BlockRef],
        previous: H,
        header: Header
    ): Prepared[H]

  enum ForecastReason:
    case ShelleyLedgerNotReady
    case Horizon(slot: BigInt, tipSlot: BigInt)
    def message: String = this match
      case ShelleyLedgerNotReady =>
        "the Shelley ledger exists once the first Shelley block is applied"
      case Horizon(slot, tip) =>
        s"slot $slot is beyond the forecast horizon of the ledger at slot $tip"
  enum Outcome[+H]:
    /** Every header rule holds; the header state after the header. */
    case Valid(next: H)

    /** A rule is violated. */
    case Invalid(error: HeaderError)

    /** A crypto provider failed while a rule verified the header: there is no verdict on it, and
      * its sender is not at fault ([[xyz.kd5ujc.scarab.ledger.ProviderDefect]]).
      */
    case Unverifiable(defect: ProviderDefect)

    /** The ledger view of the header's slot cannot be forecast from this anchor yet: ask again once
      * the node's chain has advanced.
      */
    case Later(reason: ForecastReason)

    /** A defect of the node's own state or configuration stops the rules short of a verdict, and
      * the chain's advance does not lift it: its sender is not at fault ([[HeaderDefect]]).
      */
    case Undecided(defect: HeaderDefect)

/** The candidate chain of one peer as header validation sees it: the point where it joined ours,
  * the header state there and every header announced since with the state after it (`None` where no
  * state could be derived: a base outside our retained chain).
  *
  * Its points ([[points]]: the local prefix, the base and the headers) are kept as the reference
  * keeps a candidate, an [[AnchoredFragment]] anchored at the first of them, with the states beside
  * it. So appending a header and looking up a point cost O(1), and rolling back or trimming costs
  * what it drops, where they rebuilt or searched the whole candidate for each header (issue 810).
  *
  * A chain holds a block once, and Origin, which precedes every block, at most once: the lookups
  * rely on its points being distinct. Two points with one hash would take a header whose hash is
  * its own or an ancestor's. The gate builds every chain with Origin, if it is there, first; one
  * built with Origin after other points keeps those points aside, and searches them in turn.
  *
  * @param leading
  *   the points before an Origin that follows them: none on a chain the gate builds, where Origin,
  *   if it is there, comes first
  * @param fragment
  *   the points from the first after `leading` on, anchored at it
  */
final class HeaderChain[H] private (
    val base: ChainSync.Point,
    val baseState: Option[H],
    val entries: Vector[HeaderChain.Entry[H]],
    val prefix: Vector[HeaderChain.Known[H]],
    val baseView: Option[HeaderChain.TipView],
    leading: Vector[ChainSync.Point],
    fragment: AnchoredFragment[ChainSync.Point.Block]
):
  def map[J](f: H => J): HeaderChain[J] =
    new HeaderChain(
      base,
      baseState.map(f),
      entries.map(e => HeaderChain.Entry(e.header, e.state.map(f))),
      prefix.map(p => HeaderChain.Known(p.point, p.state.map(f), p.view)),
      baseView,
      leading,
      fragment
    )

  /** The same chain with these fields; the points are indexed again only when they change. */
  def copy(
      base: ChainSync.Point = this.base,
      baseState: Option[H] = this.baseState,
      entries: Vector[HeaderChain.Entry[H]] = this.entries,
      prefix: Vector[HeaderChain.Known[H]] = this.prefix,
      baseView: Option[HeaderChain.TipView] = this.baseView
  ): HeaderChain[H] =
    if base == this.base && (entries eq this.entries) && (prefix eq this.prefix) then
      new HeaderChain(base, baseState, entries, prefix, baseView, leading, fragment)
    else HeaderChain(base, baseState, entries, prefix, baseView)

  /** The local immutable-to-intersection prefix needs points and validated states, never
    * synthesized headers. Accepted CSJ searches may rewind into this captured local prefix.
    */
  def points: Vector[ChainSync.Point] =
    val fromAnchor = fragment.anchorPoint +: fragment.toOldestFirst
    if leading.isEmpty then fromAnchor else leading ++ fromAnchor

  /** [[points]], which are never empty: the base is always there. */
  def nonEmptyPoints: NonEmptyVector[ChainSync.Point] =
    leading.headOption.fold(NonEmptyVector(fragment.anchorPoint, fragment.toOldestFirst))(first =>
      NonEmptyVector(first, leading.drop(1) ++ (fragment.anchorPoint +: fragment.toOldestFirst))
    )

  /** [[points]] after the first. On a chain the gate builds, the same vector while the chain's
    * points are unchanged, so comparing two of them costs nothing then.
    */
  def pointsAfterFirst: Vector[ChainSync.Point] =
    if leading.isEmpty then fragment.toOldestFirst
    else leading.drop(1) ++ (fragment.anchorPoint +: fragment.toOldestFirst)

  /** The newest of [[points]] on our chain `ours` (its anchor or a block), searched from our newest
    * block, as `AF.intersectionPoint` finds it: both are chains, so the first of our points from
    * the newest that is on this one is this chain's newest point on ours. A search from this
    * chain's tip would visit every header the peer is ahead of us by, for each header. A tip on our
    * chain is the answer at once, as a peer behind us or rolled back to our anchor has it. A chain
    * with points before an Origin that follows them is no chain, and is searched from its tip.
    */
  def intersectionPoint(ours: ChainSelection.Local): Option[ChainSync.Point] =
    if ours.contains(tipPoint) then Some(tipPoint)
    else if leading.isEmpty then
      (ours.blocks.reverseIterator.map(_.point) ++ Iterator.single(ours.anchor))
        .find(withinFragmentBounds)
    else pointsNewestFirst.find(ours.contains)

  /** [[points]] from the newest. */
  def pointsNewestFirst: Iterator[ChainSync.Point] =
    fragment.pointsNewestFirst ++ leading.reverseIterator

  /** [[points]] as the density rule's fragment: the first point, then every block after it. */
  def densityFragment: GenesisDensity.Fragment =
    leading.headOption.fold(GenesisDensity.Fragment(fragment.anchorPoint, fragment.toOldestFirst))(
      first =>
        GenesisDensity.Fragment(
          first,
          (leading.drop(1) ++ (fragment.anchorPoint +: fragment.toOldestFirst)).collect {
            case point: ChainSync.Point.Block => point
          }
        )
    )

  /** The point is one of [[points]] (`withinFragmentBounds`). */
  def withinFragmentBounds(point: ChainSync.Point): Boolean = offsetOf(point).isDefined

  /** The point after `point` in [[points]], if `point` is one of them and not the last. */
  def successorPoint(point: ChainSync.Point): Option[ChainSync.Point] =
    offsetOf(point).flatMap(at => pointAt(at + 1))

  def tipHash: Option[Bytes] = entries.lastOption.map(_.header.hash).orElse(baseHash)

  private def baseHash: Option[Bytes] = base match
    case ChainSync.Point.Origin      => None
    case ChainSync.Point.Block(_, h) => Some(h)

  /** The header state after the newest header, if it is known. */
  def tipState: Option[H] = entries.lastOption.fold(baseState)(_.state)

  def tipPoint: ChainSync.Point = entries.lastOption.fold(base)(_.header.point)

  /** The state after the entry with this hash (`Some(None)`: known to be on the chain but its state
    * is not known).
    */
  def stateAfter(hash: Bytes): Option[Option[H]] =
    offsetOfHash(hash).flatMap(at =>
      if at < prefix.size then prefix.lift(at).map(_.state)
      else if at == prefix.size then Some(baseState)
      else entries.lift(at - prefix.size - 1).map(_.state)
    )

  def contains(hash: Bytes): Boolean = offsetOfHash(hash).isDefined

  /** The header can follow the tip. The first header of a chain that starts at Origin names the
    * chain's origin (the Byron genesis hash on a chain with a Byron prefix) as its parent, which
    * `VolatileChain` checks against the node's own origin; any parent is accepted there.
    */
  def canExtend(header: Header): Boolean =
    header.prevHash == tipHash || (base == ChainSync.Point.Origin && entries.isEmpty)

  /** Append a header that extends the tip. */
  def extend(header: Header, state: Option[H]): Either[HeaderChain.Error, HeaderChain[H]] =
    Either.cond(
      canExtend(header),
      new HeaderChain(
        base,
        baseState,
        entries :+ HeaderChain.Entry(header, state),
        prefix,
        baseView,
        leading,
        fragment.addBlock(header.blockPoint)
      ),
      HeaderChain.Error.DoesNotExtend(header.hash, tipPoint)
    )

  /** The chain as it was at `point` (the base or one of the headers), if the chain passes there. */
  def rollback(point: ChainSync.Point): Option[HeaderChain[H]] =
    offsetOf(point).flatMap { at =>
      if at >= prefix.size then
        Some(
          new HeaderChain(
            base,
            baseState,
            entries.take(at - prefix.size),
            prefix,
            baseView,
            leading,
            fragment.headAt(at - leading.size)
          )
        )
      else
        prefix
          .lift(at)
          .map(known =>
            if at >= leading.size then
              new HeaderChain(
                known.point,
                known.state,
                Vector.empty,
                prefix.take(at),
                known.view,
                leading,
                fragment.headAt(at - leading.size)
              )
            // before an Origin that follows other points: indexed again
            else HeaderChain(known.point, known.state, Vector.empty, prefix.take(at), known.view)
          )
    }

  /** The chain from `anchor` on, when it passes there: the reference trims its candidate to the
    * anchor of our current chain whenever our chain changes, as the headers before it can never be
    * adopted or rolled back to (`intersectsWithCurrentChain`, `AF.splitAfterPoint theirFrag
    * (AF.anchorPoint ourFrag')`, ouroboros-consensus 82ecba329 `MiniProtocol/ChainSync/Client.hs`
    * 942-992). A header at `anchor` becomes the base, as in [[pruned]]; an anchor in the local
    * prefix drops the prefix before it; a chain that does not pass through `anchor` is kept, for
    * the caller's reintersection to decide (issue 810: without this, a candidate grew to the
    * retention cap and every header's checks grew with it).
    */
  def trimmedTo(anchor: ChainSync.Point): HeaderChain[H] =
    offsetOf(anchor).fold(this) { at =>
      // already the first point kept: nothing to drop
      if at == 0 then this
      else if at < leading.size then
        // before an Origin that follows other points: indexed again
        HeaderChain(base, baseState, entries, prefix.drop(at), baseView)
      else if at <= prefix.size then
        new HeaderChain(
          base,
          baseState,
          entries,
          prefix.drop(at),
          baseView,
          Vector.empty,
          fragment.anchoredAt(at - leading.size)
        )
      else
        entries
          .lift(at - prefix.size - 1)
          .fold(this)(last => baseAt(last, at - prefix.size, at - leading.size))
    }

  /** At most `max` of the newest headers: the oldest are folded into the base. */
  def pruned(max: Int): HeaderChain[H] =
    val dropped = (entries.size - max).min(entries.size)
    // the newest dropped header, if any, becomes the base
    entries
      .lift(dropped - 1)
      .fold(this)(last => baseAt(last, dropped, prefix.size + dropped - leading.size))

  /** The chain whose base is the header `last`, the newest of the `dropped` oldest entries, at
    * `offset` from the fragment's anchor.
    */
  private def baseAt(last: HeaderChain.Entry[H], dropped: Int, offset: Int): HeaderChain[H] =
    new HeaderChain(
      last.header.point,
      last.state,
      entries.drop(dropped),
      Vector.empty,
      Some(
        HeaderChain
          .TipView(SelectView.of(last.header), ChainSelection.tiebreakerFor(last.header.era))
      ),
      Vector.empty,
      fragment.anchoredAt(offset)
    )

  /** The position of `point` in [[points]]. */
  private def offsetOf(point: ChainSync.Point): Option[Int] =
    fragment
      .offsetOf(point)
      .map(leading.size + _)
      .orElse(Some(leading.indexOf(point)).filter(_ >= 0))

  /** The position in [[points]] of the point with this hash. */
  private def offsetOfHash(hash: Bytes): Option[Int] =
    fragment
      .offsetOfHash(hash)
      .map(leading.size + _)
      .orElse(Some(leading.indexWhere {
        case ChainSync.Point.Block(_, h) => h == hash
        case ChainSync.Point.Origin      => false
      }).filter(_ >= 0))

  /** The point at this position of [[points]]. */
  private def pointAt(at: Int): Option[ChainSync.Point] =
    if at < leading.size then leading.lift(at) else fragment.pointAt(at - leading.size)

  override def equals(other: Any): Boolean = other match
    case that: HeaderChain[?] =>
      (this eq that) || (base == that.base && baseState == that.baseState &&
        entries == that.entries && prefix == that.prefix && baseView == that.baseView)
    case _ => false

  override def hashCode: Int = (base, baseState, entries, prefix, baseView).##

  override def toString: String = s"HeaderChain($base, $baseState, $entries, $prefix, $baseView)"

object HeaderChain:
  enum Error:
    case DoesNotExtend(header: Bytes, tip: ChainSync.Point)
    def message: String = this match
      case DoesNotExtend(h, _) => s"header ${h.hex.take(16)} does not extend the candidate chain"

  final case class Entry[H](header: Header, state: Option[H])
  final case class TipView(value: SelectView, tiebreaker: ChainSelection.Tiebreaker)
  final case class Known[+H](point: ChainSync.Point, state: Option[H], view: Option[TipView] = None)

  /** The chain of these points and states, indexed. Origin comes before every block, so the
    * fragment is anchored at the last Origin among the prefix and the base, if there is one, and
    * the points before it are kept aside; otherwise at the first point.
    */
  def apply[H](
      base: ChainSync.Point,
      baseState: Option[H],
      entries: Vector[Entry[H]],
      prefix: Vector[Known[H]] = Vector.empty,
      baseView: Option[TipView] = None
  ): HeaderChain[H] =
    val local = prefix.map(_.point) :+ base
    val anchorIndex = local.lastIndexOf(ChainSync.Point.Origin).max(0)
    new HeaderChain(
      base,
      baseState,
      entries,
      prefix,
      baseView,
      local.take(anchorIndex),
      AnchoredFragment.fromOldestFirst(
        local.lift(anchorIndex).getOrElse(base),
        local.drop(anchorIndex + 1).collect { case point: ChainSync.Point.Block =>
          point
        } ++ entries.map(_.header.blockPoint)
      )
    )

  def at[H](base: ChainSync.Point, state: Option[H]): HeaderChain[H] =
    HeaderChain(base, state, Vector.empty)
