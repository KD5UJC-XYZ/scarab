// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import xyz.kd5ujc.scarab.codec.{Bytes, HaskellSort}

import FetchGsv.PeerGsv

/** Praos fetch decisions from ouroboros-network 2fb69829, BlockFetch/Decision.hs
  * (prioritisePeerChains, fetchRequestDecisions and selectBlocksUpToLimits). The owner supplies
  * validated candidate fragments and the consensus chain preorder. Reservations are committed
  * atomically by the owner; this object performs no effects and knows no error capabilities.
  */
object BlockFetchPolicy:
  final case class Header(hash: Bytes, slot: BigInt, bytes: Long)
  final case class Fragment(anchor: Option[Bytes], headers: Vector[Header])
  final case class InFlight(requests: Int = 0, bytes: Long = 0, blocks: Set[Bytes] = Set.empty)
  enum Status:
    case Ready, Busy, Starting, Shutdown, Aberrant
  enum ProbabilityBand:
    case Low, Moderate, High
  enum Refusal:
    case NoBlocks, InFlightOtherPeer, InFlightThisPeer, AlreadyFetched
    case ChainNotPlausible, IntersectionTooDeep, PeerShutdown, PeerStarting, PeerSlow
    case RequestsInFlight(limit: Int)
    case BytesInFlight(bytes: Long, low: Long, high: Long)
    case PeerBusy(bytes: Long, low: Long, high: Long)
    case Concurrency(mode: FetchMode, limit: Int)
    case GenesisConcurrency(limit: Int)
    case InvalidConfiguration, InvalidCandidate
  final case class Candidate[P](
      peer: P,
      chainPreference: Int,
      chainHead: Bytes,
      fragments: Vector[Fragment],
      performance: PeerGsv = FetchGsv.default,
      inFlight: InFlight = InFlight(),
      status: Status = Status.Ready,
      tieBreak: Long = 0,
      chainHeadSlot: BigInt = 0,
      refusal: Option[Refusal] = None
  )
  final case class Config(
      bulkConcurrency: Int = 1,
      deadlineConcurrency: Int = 1,
      maxRequestsPerPeer: Int = 100
  ):
    def concurrency(mode: FetchMode): Int = mode match
      case FetchMode.BulkSync => bulkConcurrency
      case FetchMode.Deadline => deadlineConcurrency
  enum Decision[P]:
    case Request(peer: P, fragments: Vector[Fragment])
    case Declined(peer: P, reason: Refusal)

  def probabilityBand(probability: Double): ProbabilityBand =
    if probability > 0.98 then ProbabilityBand.High
    else if probability > 0.75 then ProbabilityBand.Moderate
    else ProbabilityBand.Low

  /** `comparePeerGSV` (ouroboros-network 2fb69829, BlockFetch/DeltaQ.hs:38–67; `comparePeerGSV'` at
    * 71–79 is the case without active peers). The 20% incumbent advantage applies only to
    * preferred-peer admission. Both factors are `DiffTime` products, which floor to picoseconds
    * (`Data.Fixed`), so the advantage is ⌊4g/5⌋ and the strict 5% band is ⌊max/20⌋. Within the band
    * the salted tie breaker decides. The relation is not transitive (each of three peers can be
    * within 5% of its neighbour but not of the third), so it is only applied through
    * [[HaskellSort.sortBy]], the reference's own `sortBy`; a `java.util` sort may throw on it.
    */
  private[selection] def compareGsv[P](active: Set[P])(a: Candidate[P], b: Candidate[P]): Int =
    def geographic(candidate: Candidate[P]): BigInt =
      val g = candidate.performance.geographic
      if active(candidate.peer) then FetchGsv.floorDivide(g * 4, 5) else g
    val ga = geographic(a)
    val gb = geographic(b)
    if (ga - gb).abs < FetchGsv.floorDivide(ga.max(gb), 20) then
      java.lang.Long.compare(a.tieBreak, b.tieBreak)
    else ga.compare(gb)

  private def totalBytes[P](candidate: Candidate[P]): Long =
    candidate.fragments
      .flatMap(_.headers)
      .foldLeft(BigInt(0))((n, h) => n + h.bytes)
      .min(BigInt(Long.MaxValue))
      .toLong

  /** `prioritisePeerChains` (Decision.hs:731–833): first the reference's `sortBy` with
    * `comparePeerGSV'` (783–784, 821–822), then stable sorts on total keys, for which any stable
    * sort gives the reference's order. Deadline candidates with equal bands/preference are
    * interleaved across chain heads, as upstream's group/transpose stage specifies.
    */
  def rank[P](mode: FetchMode, candidates: Vector[Candidate[P]]): Vector[Candidate[P]] =
    val (declined, tied) = HaskellSort
      .sortBy(candidates)(compareGsv(Set.empty[P]))
      .partition(_.refusal.isDefined)
    mode match
      case FetchMode.BulkSync =>
        declined ++ tied.sortWith { (a, b) =>
          if a.chainPreference != b.chainPreference then a.chainPreference < b.chainPreference
          else
            a.performance.duration(a.inFlight.bytes, totalBytes(a)) <
              b.performance.duration(b.inFlight.bytes, totalBytes(b))
        }
      case FetchMode.Deadline =>
        def band(c: Candidate[P]): Int =
          probabilityBand(c.performance.probability(c.inFlight.bytes, totalBytes(c))).ordinal
        val ordered = tied.sortWith { (a, b) =>
          if band(a) != band(b) then band(a) > band(b)
          else a.chainPreference > b.chainPreference
        }
        val groups = ordered.foldLeft(Vector.empty[Vector[Candidate[P]]]) { (acc, c) =>
          acc.lastOption match
            case Some(last)
                if band(last.head) == band(c) &&
                  last.head.chainPreference == c.chainPreference =>
              acc.init :+ (last :+ c)
            case _ => acc :+ Vector(c)
        }
        groups.flatMap { group =>
          val heads = group.groupBy(c => (c.chainHeadSlot, c.chainHead)).toVector.sortBy(_._1)
          val depth = heads.map(_._2.size).maxOption.getOrElse(0)
          (0 until depth).toVector.flatMap(i => heads.flatMap(_._2.lift(i)))
        } ++ declined

  /** Include active peers with no pending fragments so concurrency and preferred admission see all
    * sessions. Preferred peers may temporarily exceed the target while replacing slower peers; they
    * are the first of the reference's `sortBy` with `comparePeerGSV` (Decision.hs:1028–1035).
    */
  def decide[P](
      config: Config,
      mode: FetchMode,
      candidates: Vector[Candidate[P]]
  ): Vector[Decision[P]] =
    val active = candidates
      .filter(c => c.status != Status.Shutdown && c.inFlight.requests > 0)
      .map(_.peer)
      .toSet
    val preferred = HaskellSort
      .sortBy(candidates.filter(_.status != Status.Shutdown))(compareGsv(active))
      .take(config.concurrency(mode).max(0))
      .map(_.peer)
      .toSet
    val initiallyClaimed = candidates
      .filter(c => c.status == Status.Ready || c.status == Status.Busy)
      .flatMap(_.inFlight.blocks)
      .toSet
    val prepared = candidates.map { candidate =>
      if candidate.refusal.isDefined then candidate
      else
        val fragments =
          filterFragments(candidate.fragments, h => !candidate.inFlight.blocks(h.hash))
        candidate.copy(
          fragments = fragments,
          refusal =
            if fragments.isEmpty && candidate.fragments.nonEmpty then Some(Refusal.InFlightThisPeer)
            else None
        )
    }
    val (_, _, results) = rank(mode, prepared).foldLeft(
      (active.size, initiallyClaimed, Vector.empty[Decision[P]])
    ) { case ((concurrent, claimed, results), candidate) =>
      val available =
        if mode == FetchMode.BulkSync then
          filterFragments(candidate.fragments, h => !claimed(h.hash))
        else filterFragments(candidate.fragments, h => !candidate.inFlight.blocks(h.hash))
      val decision = select(
        config,
        mode,
        if preferred(candidate.peer) then 0 else concurrent,
        candidate,
        available
      )
      decision match
        case Decision.Request(_, fragments) =>
          val hashes = fragments.flatMap(_.headers.map(_.hash)).toSet
          (
            concurrent + (if candidate.inFlight.requests == 0 then 1 else 0),
            claimed ++ hashes,
            results :+ decision
          )
        case _ => (concurrent, claimed, results :+ decision)
    }
    results

  /** Splitting retains each hole's preceding header as the next fragment's anchor. */
  private[selection] def filterFragments(
      fragments: Vector[Fragment],
      keep: Header => Boolean
  ): Vector[Fragment] =
    fragments.flatMap { fragment =>
      val (completed, current, anchor) = fragment.headers.foldLeft(
        (Vector.empty[Fragment], Vector.empty[Header], fragment.anchor)
      ) { case ((done, run, start), header) =>
        if keep(header) then (done, run :+ header, start)
        else
          (
            if run.nonEmpty then done :+ Fragment(start, run) else done,
            Vector.empty,
            Some(header.hash)
          )
      }
      if current.nonEmpty then completed :+ Fragment(anchor, current) else completed
    }

  private[selection] def select[P](
      config: Config,
      mode: FetchMode,
      concurrent: Int,
      candidate: Candidate[P],
      fragments: Vector[Fragment]
  ): Decision[P] =
    val window = candidate.performance.window
    val flight = candidate.inFlight
    val refusal =
      if candidate.refusal.isDefined then candidate.refusal
      else if config.bulkConcurrency < 0 || config.deadlineConcurrency < 0 || config.maxRequestsPerPeer < 0
      then Some(Refusal.InvalidConfiguration)
      else if flight.requests < 0 || flight.bytes < 0 ||
        candidate.fragments.exists(_.headers.exists(_.bytes < 0))
      then Some(Refusal.InvalidCandidate)
      else if fragments.isEmpty then
        Some(
          if candidate.fragments.nonEmpty then Refusal.InFlightOtherPeer else Refusal.NoBlocks
        )
      else
        candidate.status match
          case Status.Shutdown => Some(Refusal.PeerShutdown)
          case Status.Starting => Some(Refusal.PeerStarting)
          case Status.Aberrant => Some(Refusal.PeerSlow)
          case _ if flight.requests >= config.maxRequestsPerPeer =>
            Some(Refusal.RequestsInFlight(config.maxRequestsPerPeer))
          case _ if flight.bytes >= window.highBytes =>
            Some(Refusal.BytesInFlight(flight.bytes, window.lowBytes, window.highBytes))
          case Status.Busy =>
            Some(Refusal.PeerBusy(flight.bytes, window.lowBytes, window.highBytes))
          case _
              if concurrent > config.concurrency(mode) ||
                (flight.requests == 0 && concurrent == config.concurrency(mode)) =>
            Some(Refusal.Concurrency(mode, config.concurrency(mode)))
          case _ => None
    refusal.fold[Decision[P]] {
      val (_, _, selected) =
        fragments.foldLeft((flight.requests, BigInt(flight.bytes), Vector.empty[Fragment])) {
          case ((requests, bytes, done), fragment) =>
            if requests >= config.maxRequestsPerPeer || bytes >= window.highBytes then
              (requests, bytes, done)
            else
              val (size, headers) = fragment.headers.foldLeft((bytes, Vector.empty[Header])) {
                case ((n, kept), h) =>
                  if n >= window.highBytes then (n, kept) else (n + h.bytes, kept :+ h)
              }
              if headers.isEmpty then (requests, size, done)
              else (requests + 1, size, done :+ fragment.copy(headers = headers))
        }
      Decision.Request(candidate.peer, selected)
    }(reason => Decision.Declined(candidate.peer, reason))
