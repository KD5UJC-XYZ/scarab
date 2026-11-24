// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import scala.annotation.tailrec
import scala.collection.immutable.TreeSet
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.network.selection.BlockFetchPolicy
import xyz.kd5ujc.scarab.network.selection.BlockFetchPolicy.Refusal
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Praos candidate boundary of BlockFetch, without Peras weights or Genesis selection. Reference:
  * consensus 82ecba329 MiniProtocol/BlockFetch/ClientInterface.hs 256–284 and
  * Util/AnchoredFragment.hs 167–210; network c45735a5 BlockFetch/Decision.hs
  * filterPlausibleCandidates, selectForkSuffixes and filterNotAlreadyFetched. A candidate whose
  * point slots never decrease takes a bounded intersection search; any other is scanned from its
  * tip ([[SlotOrder]]). The owner supplies one coherent selected-chain snapshot and any additional
  * downloaded blocks retained outside that chain.
  */
private[node] object FetchCandidates:

  /** The selected chain of one decision and its newest slot. Membership is the window's own
    * ([[ChainSelection.Local.contains]]: its anchor and blocks), whose point set is built once per
    * window, so a decision that sees the same window as the one before builds nothing.
    */
  final class Selected private (val local: ChainSelection.Local, val newestSlot: Option[BigInt]):
    def holds(point: Point): Boolean = local.contains(point)

  object Selected:
    def of(local: ChainSelection.Local): Selected =
      new Selected(
        local,
        (Iterator.single(local.anchor) ++ local.blocks.iterator.map(_.point))
          .flatMap(slotOf)
          .maxOption
      )

  private def slotOf(point: Point): Option[BigInt] = point match
    case Point.Block(slot, _) => Some(slot.value)
    case Point.Origin         => None

  /** Whether a candidate's header slots never decrease, as every candidate that passed the header
    * gate's slot check has them, or nothing is known of their order (a candidate the gate did not
    * check, `--header-first off`).
    */
  enum SlotOrder:
    case NonDecreasing, Unchecked

  /** How an [[Announced]] was found: by a scan of its whole candidate, or carried from the one
    * before it through the headers a batch added and removed.
    */
  enum Derivation:
    case Scanned, Carried

  /** The headers of a candidate from position `from` until (not including) `until`. */
  final case class Span(from: Int, until: Int)

  /** A candidate with what the decisions read of it besides its headers, kept as the candidate
    * changes rather than found again at each announcement or decision (issue 810): the order of its
    * slots, each header as BlockFetch sizes it, where a header does not extend the one before it,
    * and its points (base and headers) with how often each occurs. A session carries it from batch
    * to batch through [[forward]] and [[backward]], which change the candidate exactly as a
    * ChainSync batch does and touch only the headers that batch adds or removes.
    *
    * Positions of headers that do not extend their predecessor are counted from the first header
    * the index was scanned from (`offset` is the position of the current first header), so that
    * dropping the oldest header leaves the others where they are.
    */
  final class Announced private (
      val candidate: ChainSelection.Candidate,
      disorder: Int,
      blocks: Vector[BlockFetchPolicy.Header],
      offset: Long,
      breaks: TreeSet[Long],
      points: Map[Point, Int],
      val derivation: Derivation
  ):
    def order: SlotOrder = if disorder == 0 then SlotOrder.NonDecreasing else SlotOrder.Unchecked

    /** Whether `point` is the candidate's base or the point of one of its headers. */
    def retains(point: Point): Boolean = points.contains(point)

    /** The candidate after `header` rolls forward and the window keeps the newest `retained`
      * headers (at least one): the oldest it drops leave, and the last of them becomes the base, as
      * `Peer` keeps its candidate. The points that left: the old base and every dropped header but
      * the last.
      */
    def forward(header: Header, retained: Int): Announced.Step =
      val headers = candidate.headers
      val size = headers.size
      val grown = headers :+ header
      val dropping = (size + 1 - retained.max(1)).max(0)
      val dropped = grown.take(dropping)
      val joined = headers.lastOption
      val left =
        if dropping == 0 then Vector.empty else candidate.base +: dropped.dropRight(1).map(_.point)
      val first = offset + dropping
      val linked =
        joined.fold(breaks)(last =>
          if Announced.extendsHeader(header, last) then breaks else breaks + (offset + size)
        )
      Announced.Step.Moved(
        new Announced(
          ChainSelection
            .Candidate(dropped.lastOption.fold(candidate.base)(_.point), grown.drop(dropping)),
          disorder + joined.fold(0)(last => Announced.disordered(last, header)) -
            (0 until dropping).iterator.map(j => Announced.disordered(grown(j), grown(j + 1))).sum,
          (blocks :+ Announced.sized(header)).drop(dropping),
          first,
          if dropping == 0 then linked else linked.rangeFrom(first + 1),
          left.foldLeft(Announced.counted(points, header.point))(Announced.uncounted),
          Derivation.Carried
        ),
        left
      )

    /** The candidate after a rollback to `point`: back to the base or to the first header at
      * `point`, the headers after it leaving; a point it does not hold starts the candidate again
      * there, empty, as `ChainSelection.Candidate.rollback` and `Peer` do.
      */
    def backward(point: Point): Announced.Step =
      val headers = candidate.headers
      val at =
        if point == candidate.base then Some(-1)
        else
          points.get(point).map { occurrences =>
            // a point that occurs once, and is not the base, is one header's: the newest headers
            // are the likeliest target, so it is searched from the tip
            if occurrences == 1 then headers.lastIndexWhere(_.point == point)
            else headers.indexWhere(_.point == point)
          }
      at.fold[Announced.Step](
        Announced.Step.Reset(Announced.of(ChainSelection.Candidate(point, Vector.empty)))
      ) { i =>
        val kept = i + 1
        val removed = headers.drop(kept)
        Announced.Step.Moved(
          new Announced(
            candidate.copy(headers = headers.take(kept)),
            disorder - (kept.max(1) until headers.size).iterator
              .map(j => Announced.disordered(headers(j - 1), headers(j)))
              .sum,
            blocks.take(kept),
            offset,
            breaks.rangeUntil(offset + kept),
            removed.iterator.map(_.point).foldLeft(points)(Announced.uncounted),
            Derivation.Carried
          ),
          removed.map(_.point)
        )
      }

    /** The fragments of the headers `spans` select, in their order, as `fragmentsOf` makes them
      * from those headers: one for each run in which every header extends the one before. A run is
      * cut where a header does not extend its predecessor in the candidate, and a span joins the
      * fragment before it when its first header extends that fragment's last. The fragments'
      * headers are slices of the sized headers, not built again.
      */
    def fragments(spans: Vector[Span]): Vector[BlockFetchPolicy.Fragment] =
      val headers = candidate.headers
      spans
        .foldLeft((Vector.empty[BlockFetchPolicy.Fragment], Option.empty[Int])) {
          case ((done, last), span) =>
            if span.from >= span.until then (done, last)
            else
              val cuts = breaks
                .range(offset + span.from + 1, offset + span.until)
                .iterator
                .map(p => (p - offset).toInt)
                .toVector
              val pieces = (span.from +: cuts).lazyZip(cuts :+ span.until).map { (from, until) =>
                BlockFetchPolicy.Fragment(headers(from).prevHash, blocks.slice(from, until))
              }
              val joins = last.exists(l => Announced.extendsHeader(headers(span.from), headers(l)))
              val merged = (done.lastOption, pieces.headOption) match
                case (Some(previous), Some(next)) if joins =>
                  done.dropRight(1) :+ previous.copy(headers = previous.headers ++ next.headers)
                case _ => done ++ pieces.take(1)
              (merged ++ pieces.drop(1), Some(span.until - 1))
        }
        ._1

  object Announced:
    /** The index of `candidate`, from a scan of the whole of it. */
    def of(candidate: ChainSelection.Candidate): Announced =
      val headers = candidate.headers
      new Announced(
        candidate,
        headers.lazyZip(headers.drop(1)).count((a, b) => disordered(a, b) == 1),
        headers.map(sized),
        0L,
        TreeSet.from(
          (1 until headers.size)
            .filterNot(j => extendsHeader(headers(j), headers(j - 1)))
            .map(_.toLong)
        ),
        headers.iterator.map(_.point).foldLeft(counted(Map.empty, candidate.base))(counted),
        Derivation.Scanned
      )

    /** What one rolled forward or rolled back header did to an [[Announced]]. */
    enum Step:
      /** The index after the step, and the points that stopped being retained by it. */
      case Moved(next: Announced, left: Vector[Point])

      /** A rollback to a point the candidate did not hold: the candidate starts again there. */
      case Reset(next: Announced)

      def announced: Announced = this match
        case Moved(next, _) => next
        case Reset(next)    => next

    /** 1 when `b` after `a` breaks the order of slots (or either has no slot), else 0, as the check
      * of [[Announced.of]] counts them.
      */
    private def disordered(a: Header, b: Header): Int =
      (slotOf(a.point), slotOf(b.point)) match
        case (Some(x), Some(y)) if x <= y => 0
        case _                            => 1

    private def extendsHeader(header: Header, previous: Header): Boolean =
      header.prevHash.contains(previous.hash)

    private def sized(header: Header): BlockFetchPolicy.Header =
      BlockFetchPolicy.Header(header.hash, header.slot, FetchCoordinator.estimatedBytes(header))

    private def counted(points: Map[Point, Int], point: Point): Map[Point, Int] =
      points.updatedWith(point)(n => Some(n.fold(1)(_ + 1)))

    private def uncounted(points: Map[Point, Int], point: Point): Map[Point, Int] =
      points.updatedWith(point)(_.filter(_ > 1).map(_ - 1))

  final case class Selection(
      intersection: Point,
      suffix: Vector[Header],
      missing: Vector[Vector[Header]]
  ):
    def headers: Vector[Header] = missing.flatten

  /** What stages 1 and 2 find of a candidate against the selected chain, which no fetched block
    * changes: whether it is plausible, and its intersection with the count of its headers up to it.
    */
  final case class Weighed(plausible: Boolean, intersection: Option[(Point, Int)])

  def weigh(
      tiebreaker: ChainSelection.Tiebreaker,
      selected: Selected,
      announced: Announced
  ): Weighed =
    val local = selected.local
    val found = intersection(selected, announced)
    Weighed(
      found.isDefined && announced.candidate.headers.lastOption.exists { tip =>
        if local.blocks.isEmpty then tip.point != local.anchor
        else ChainSelection.prefersCandidate(tiebreaker, local.tipView, SelectView.of(tip))
      },
      found
    )

  /** Stages 1–3 for Praos from a candidate's weighing: the runs of its suffix whose blocks are not
    * fetched.
    */
  def chosen(
      weighed: Weighed,
      announced: Announced,
      alreadyFetched: Header => Boolean
  ): Either[Refusal, Vector[Span]] =
    if !weighed.plausible then Left(Refusal.ChainNotPlausible)
    else
      weighed.intersection
        .toRight(Refusal.IntersectionTooDeep)
        .map((_, count) => missing(announced, count, alreadyFetched))
        .filterOrElse(_.nonEmpty, Refusal.AlreadyFetched)

  /** Stages 1 and 2 for Genesis, which selects from the plausible suffix before removing fetched
    * blocks (Decision/Genesis.hs): the whole suffix.
    */
  def plausibleSuffix(weighed: Weighed, announced: Announced): Either[Refusal, Vector[Span]] =
    if !weighed.plausible then Left(Refusal.ChainNotPlausible)
    else
      weighed.intersection
        .toRight(Refusal.IntersectionTooDeep)
        .map((_, count) =>
          Vector(Span(count, announced.candidate.headers.size)).filter(s => s.from < s.until)
        )

  /** The runs of the candidate's headers from position `from` whose blocks are not fetched. Only
    * fetched blocks split the suffix after an intersection: the intersection is the last header on
    * the selected chain at or before the scan's start, and no header after that start is on it (its
    * slot is past the selection's newest, or the scan started at the tip).
    */
  def missing(
      announced: Announced,
      from: Int,
      alreadyFetched: Header => Boolean
  ): Vector[Span] =
    val headers = announced.candidate.headers
    def closed(done: Vector[Span], start: Int, at: Int): Vector[Span] =
      if start < at then done :+ Span(start, at) else done
    @tailrec def go(i: Int, start: Int, done: Vector[Span]): Vector[Span] =
      if i >= headers.size then closed(done, start, i)
      else if alreadyFetched(headers(i)) then go(i + 1, i + 1, closed(done, start, i))
      else go(i + 1, start, done)
    go(from, from, Vector.empty)

  /** The combined native boundary declines a detached chain at the plausibility stage first. */
  /** @param alreadyFetched
    *   Whether a header's block is downloaded outside the selected chain; the selected chain's own
    *   blocks always count as fetched.
    */
  def select(
      tiebreaker: ChainSelection.Tiebreaker,
      selected: Selected,
      announced: Announced,
      alreadyFetched: Header => Boolean = _ => false
  ): Either[Refusal, Selection] =
    val weighed = weigh(tiebreaker, selected, announced)
    val headers = announced.candidate.headers
    chosen(weighed, announced, alreadyFetched).flatMap { spans =>
      weighed.intersection.toRight(Refusal.IntersectionTooDeep).map { (point, count) =>
        Selection(point, headers.drop(count), spans.map(s => headers.slice(s.from, s.until)))
      }
    }

  /** Empty current fragments are anchored at an immutable block. A strict extension is plausible
    * even when a Byron boundary header repeats that anchor's chain difficulty.
    */
  def plausible(
      tiebreaker: ChainSelection.Tiebreaker,
      selected: Selected,
      announced: Announced
  ): Boolean = weigh(tiebreaker, selected, announced).plausible

  /** Latest common point, bounded by the selected immutable anchor. */
  def suffix(
      selected: Selected,
      announced: Announced
  ): Either[Refusal, (Point, Vector[Header])] =
    intersection(selected, announced).toRight(Refusal.IntersectionTooDeep).map { (point, count) =>
      (point, announced.candidate.headers.drop(count))
    }

  /** The newest header of the candidate on the selected chain. No point of the selected chain is
    * past its newest slot, so when the candidate's slots never decrease the newest header on the
    * selected chain is at or before the last header within that slot: found by halving, and the
    * scan back starts there instead of at the candidate's tip (issue 810: a dynamo far ahead of the
    * selection was scanned whole, for each peer, at every decision). A candidate of unchecked order
    * is scanned from its tip.
    */
  private def intersection(selected: Selected, announced: Announced): Option[(Point, Int)] =
    val candidate = announced.candidate
    val headers = candidate.headers
    val start = announced.order match
      case SlotOrder.NonDecreasing => selected.newestSlot.fold(-1)(lastWithin(headers, _))
      case SlotOrder.Unchecked     => headers.size - 1
    lastHeld(selected, headers, start) match
      case -1 => Option.when(selected.holds(candidate.base))(candidate.base -> 0)
      case i  => Some(headers(i).point -> (i + 1))

  /** The index of the last header at or before `from` that is on the selected chain, or -1. */
  @tailrec
  private def lastHeld(selected: Selected, headers: Vector[Header], from: Int): Int =
    if from < 0 then -1
    else if selected.holds(headers(from).point) then from
    else lastHeld(selected, headers, from - 1)

  /** The index of the last header whose slot is at most `bound`, or -1: by halving, for headers
    * whose slots never decrease.
    */
  private def lastWithin(headers: Vector[Header], bound: BigInt): Int =
    @tailrec
    def go(low: Int, high: Int): Int =
      // invariant: every index below `low` is within `bound`, every index from `high` on is past it
      if low >= high then low - 1
      else
        val middle = (low + high) >>> 1
        if slotOf(headers(middle).point).exists(_ <= bound) then go(middle + 1, high)
        else go(low, middle)
    go(0, headers.size)
