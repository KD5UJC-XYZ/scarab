// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.{Functor, Monad}
import cats.effect.{Clock, Temporal}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import cats.effect.syntax.all.*
import java.time.Instant
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, GenesisInternalProblem, HeaderError, PeerError}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.network.genesis.LimitOnPatience
import xyz.kd5ujc.scarab.consensus.HeaderChain

/** The node's clock over the era history: the wall time, and the history that places a slot on it.
  * The history is read at each use because a chain that starts in Byron learns where Byron ends
  * only when its ledger has seen the transition ([[EpochGeometry.resolved]]). Tests supply a fake.
  */
trait SlotClock[F[_]]:
  def now: F[Instant]
  def geometry: F[EpochGeometry]

object SlotClock:
  /** The system's wall clock (`Clock.realTime`, so `TestControl` drives it). */
  def system[F[_]: Clock: Functor](history: F[EpochGeometry]): SlotClock[F] = new SlotClock[F]:
    def now = Clock[F].realTime.map(d => Instant.EPOCH.plusNanos(d.toNanos))
    def geometry = history

/** What the node's clock says about a header's slot at the moment the header arrived
  * (`HeaderInFutureCheck`, ouroboros-consensus 4.2.1.0
  * `MiniProtocol/ChainSync/Client/InFutureCheck.hs` 134-176).
  */
enum HeaderEligibility:
  /** The slot has begun: the header may be considered at once. */
  case Eligible

  /** The slot begins within the tolerable skew: the header is valid but is held back until `onset`.
    */
  case WaitUntil(onset: Instant)

  /** The slot begins beyond the tolerable skew: the header is invalid and the peer is disconnected.
    */
  case FarFuture(error: HeaderError.FarFuture)

object HeaderEligibility:
  /** `defaultClockSkew` of the reference (`InFutureCheck.hs` 203-207): 2 seconds. */
  val DefaultSkew: FiniteDuration = 2.seconds

  private val NanosPerMilli = BigInt(1000000)
  private val NanosPerSecond = BigInt(1000000000)

  def forecast(
      slot: BigInt,
      onsetPicos: BigInt,
      arrival: Instant,
      skew: FiniteDuration
  ): HeaderEligibility =
    val arrived =
      BigInt(arrival.getEpochSecond) * BigInt(10).pow(12) + BigInt(arrival.getNano) * 1000
    val ahead = onsetPicos - arrived
    if ahead <= 0 then Eligible
    else if ahead > BigInt(skew.toNanos) * 1000 then
      FarFuture(HeaderError.FarFuture(slot, (ahead + 999999999) / 1000000000, skew.toMillis))
    else
      val nanos = onsetPicos / 1000 + (if onsetPicos % 1000 > 0 then 1 else 0)
      WaitUntil(Instant.ofEpochSecond((nanos / 1000000000).toLong, (nanos % 1000000000).toLong))

  /** Judge `slot` for a header that arrived at `arrival`. Exact integer arithmetic over the slot
    * number a peer chose; a header is far in the future only when its slot begins strictly more
    * than `skew` after its arrival (`tooEarly = skew < negate ageUponArrival`, line 156), so a slot
    * that begins exactly `skew` ahead waits.
    *
    * While the Byron span is still `derived` (the ledger has not seen the transition to Shelley) a
    * Shelley slot cannot be placed: reading every slot as a Byron slot puts later slots too far
    * ahead. The slot is then placed with the shorter of the two slot lengths, which can only be
    * earlier than its true onset, so a header is never judged far in the future wrongly; the cost
    * is that such a header is not judged at all until the ledger has caught up (the reference waits
    * for the ledger instead, `PastHorizon`, `Client.hs` 1768).
    */
  def judge(
      geometry: EpochGeometry,
      slot: BigInt,
      arrival: Instant,
      skew: FiniteDuration
  ): HeaderEligibility =
    val placed =
      if geometry.byron.derived then
        geometry.copy(byron =
          geometry.byron
            .copy(slotLengthMillis = geometry.byron.slotLengthMillis.min(geometry.slotLengthMillis))
        )
      else geometry
    val onset =
      (BigInt(placed.systemStart.toEpochMilli) + placed.slotOffsetMillis(slot)) * NanosPerMilli
    val arrived = BigInt(arrival.getEpochSecond) * NanosPerSecond + arrival.getNano
    val ahead = onset - arrived
    if ahead <= 0 then Eligible
    else if ahead > BigInt(skew.toNanos) then
      FarFuture(
        HeaderError.FarFuture(
          slot,
          (ahead + NanosPerMilli - 1) / NanosPerMilli,
          skew.toMillis
        )
      )
    else
      WaitUntil(
        Instant.ofEpochSecond((onset / NanosPerSecond).toLong, (onset % NanosPerSecond).toLong)
      )

object HeaderClock:
  /** Hold the headers of a live ChainSync session to the node's clock (AUD-01, #153).
    *
    * Sample each header's arrival before its history query, as the reference does in
    * `InFutureCheck.hs` 144–176. Forecasting, validation or synthetic delay for an earlier header
    * cannot make a later header appear to arrive too early. A header beyond the skew raises
    * [[PeerError.InvalidHeader]] with [[HeaderError.FarFuture]]; otherwise delay once to its onset
    * before continuing to the next header and eventually offering the batch to `inner`.
    *
    * It is a wrapper, so it applies whether or not header-first validation is on, and sessions made
    * without it (snapshot restore, `--db` replay, tools) never meet it.
    */
  def clocked[F[_]: Temporal](
      clock: SlotClock[F],
      inner: HeaderCheck[F],
      skew: FiniteDuration = HeaderEligibility.DefaultSkew,
      patience: Option[LimitOnPatience[F]] = None
  ): HeaderCheck[F] = new HeaderCheck[F]:
    override def withPatience(value: LimitOnPatience[F]): HeaderCheck[F] =
      clocked(clock, inner.withPatience(value), skew, Some(value))

    override def withInvariant(
        invariant: GenesisInternalProblem => F[Unit]
    ): HeaderCheck[F] =
      clocked(clock, inner.withInvariant(invariant), skew, patience)

    override def observed(observer: HeaderCheck.Event => F[Unit]): HeaderCheck[F] =
      clocked(clock, inner.observed(observer), skew, patience)

    def start(intersection: ChainSync.Point): F[HeaderGate[F]] =
      inner.start(intersection).map(gate => new Gated[F](clock, gate, skew, patience))

    override def capture(using cats.Applicative[F]): F[Option[HeaderCheck.Captured[F]]] =
      inner.capture.map(
        _.map(captured =>
          captured.copy(start =
            point => captured.start(point).map(gate => new Gated[F](clock, gate, skew, patience))
          )
        )
      )

  /** [[clocked]] for a check that grafts: its gates are held to the clock, and the gates it tells
    * `recording` of are those of `inner`, which the clock only wraps.
    */
  def grafting[F[_]: Temporal](
      clock: SlotClock[F],
      inner: HeaderCheck.Grafting[F],
      skew: FiniteDuration = HeaderEligibility.DefaultSkew,
      patience: Option[LimitOnPatience[F]] = None
  ): HeaderCheck.Grafting[F] = new HeaderCheck.Grafting[F]:
    private val plain = clocked(clock, inner, skew, patience)

    def start(intersection: ChainSync.Point): F[HeaderGate[F]] = plain.start(intersection)

    override def capture(using cats.Applicative[F]): F[Option[HeaderCheck.Captured[F]]] =
      plain.capture

    override def withPatience(value: LimitOnPatience[F]): HeaderCheck[F] =
      plain.withPatience(value)

    override def withInvariant(invariant: GenesisInternalProblem => F[Unit]): HeaderCheck[F] =
      plain.withInvariant(invariant)

    override def observed(observer: HeaderCheck.Event => F[Unit]): HeaderCheck[F] =
      plain.observed(observer)

    def recording(started: HeaderGate.Grafting[F] => F[Unit]): HeaderCheck[F] =
      clocked(clock, inner.recording(started), skew, patience)

  private final class Gated[F[_]: Temporal](
      clock: SlotClock[F],
      inner: HeaderGate[F],
      skew: FiniteDuration,
      patience: Option[LimitOnPatience[F]]
  ) extends HeaderGate[F]:
    def counts = inner.counts

    override def canonicalTiming: Boolean = inner.canonicalTiming

    override def prepareHeader(
        header: xyz.kd5ujc.scarab.ledger.Header
    )(using cats.Applicative[F], Raise[F, PeerError]): F[HeaderGate.Acceptance] =
      inner.prepareHeader(header)

    override def forecastTime(header: xyz.kd5ujc.scarab.ledger.Header)(using
        cats.Applicative[F]
    ): F[Option[HeaderGate.TimeForecast]] = inner.forecastTime(header)

    override def snapshot(using cats.Applicative[F]): F[Option[HeaderChain[NodeHeaderState]]] =
      inner.snapshot

    override def intersectionSelection(using
        cats.Applicative[F]
    ): F[Option[xyz.kd5ujc.scarab.consensus.ChainSelection.Local]] = inner.intersectionSelection

    def accept(batch: Vector[SyncEvent])(using Raise[F, PeerError]): F[Unit] =
      eligible(batch).flatMap {
        case HeaderGate.Acceptance.Accepted => inner.accept(batch)
        case HeaderGate.Acceptance.Restart(point) =>
          PeerError.Diverged(PeerError.Divergence.DetachedCandidate(point)).raise[F, Unit]
        case HeaderGate.Acceptance.End(point) =>
          PeerError.RolledBackPastIntersection(point).raise[F, Unit]
      }

    override def acceptOrRestart(batch: Vector[SyncEvent])(using
        Raise[F, PeerError],
        cats.Functor[F]
    ): F[HeaderGate.Acceptance] = eligible(batch).flatMap {
      case HeaderGate.Acceptance.Accepted => inner.acceptOrRestart(batch)
      case restart                        => Temporal[F].pure(restart)
    }

    private def eligible(batch: Vector[SyncEvent])(using
        Raise[F, PeerError]
    ): F[HeaderGate.Acceptance] =
      val headers = batch.collect { case SyncEvent.RollForward(h, _) => h }
      val checkTime = Temporal[F].tailRecM(0) { index =>
        headers
          .lift(index)
          .fold(
            Temporal[F].pure[Either[Int, HeaderGate.Acceptance]](
              Right(HeaderGate.Acceptance.Accepted)
            )
          ) { header =>
            // Native recordHeaderArrival runs for this header before its history query.
            // A previous header's forecast, validation or synthetic delay must not age this sample.
            clock.now.flatMap { arrival =>
              inner.forecastTime(header).flatMap {
                case Some(HeaderGate.TimeForecast.Restart(point)) =>
                  Temporal[F].pure(Right(HeaderGate.Acceptance.Restart(point)))
                case Some(HeaderGate.TimeForecast.Refused(problem)) =>
                  PeerError.Genesis(problem).raise[F, Either[Int, HeaderGate.Acceptance]]
                case Some(HeaderGate.TimeForecast.Invalid(error)) =>
                  PeerError
                    .InvalidHeader(header.point, error)
                    .raise[F, Either[Int, HeaderGate.Acceptance]]
                case Some(HeaderGate.TimeForecast.Known(onset)) =>
                  val check = HeaderEligibility.forecast(header.slot, onset, arrival, skew) match
                    case HeaderEligibility.Eligible => Temporal[F].unit
                    case HeaderEligibility.FarFuture(error) =>
                      PeerError.InvalidHeader(header.point, error).raise[F, Unit]
                    case HeaderEligibility.WaitUntil(until) => HeaderClock.delayOnce(clock, until)
                  check *> inner.prepareHeader(header).map {
                    case HeaderGate.Acceptance.Accepted => Left(index + 1)
                    case restart                        => Right(restart)
                  }
                case None if index == 0 =>
                  legacyEligible(batch, arrival).as(Right(HeaderGate.Acceptance.Accepted))
                case None =>
                  PeerError
                    .Protocol(PeerError.ProtocolProblem.HeaderValidationDidNotSettle)
                    .raise[F, Either[Int, HeaderGate.Acceptance]]
              }
            }
          }
      }
      if inner.canonicalTiming then
        patience.fold(checkTime)(p =>
          (p.setPaused(LimitOnPatience.PauseReason.HeaderTime, true) *> checkTime)
            .guarantee(p.setPaused(LimitOnPatience.PauseReason.HeaderTime, false))
        )
      else checkTime

    private def legacyEligible(batch: Vector[SyncEvent], firstArrival: Instant)(using
        Raise[F, PeerError]
    ): F[Unit] =
      batch.collect { case SyncEvent.RollForward(header, _) => header }.zipWithIndex.traverse_ {
        (header, index) =>
          val arrived = if index == 0 then Temporal[F].pure(firstArrival) else clock.now
          (arrived, clock.geometry).tupled.flatMap { (arrival, geometry) =>
            val known = header match
              case _: xyz.kd5ujc.scarab.ledger.Header.Byron =>
                geometry.copy(byron = geometry.byron.copy(derived = false))
              case _ => geometry
            HeaderEligibility.judge(known, header.slot, arrival, skew) match
              case HeaderEligibility.Eligible => Temporal[F].unit
              case HeaderEligibility.FarFuture(error) =>
                PeerError.InvalidHeader(header.point, error).raise[F, Unit]
              case HeaderEligibility.WaitUntil(onset) =>
                val waiting = delayOnce(clock, onset)
                patience.fold(waiting)(p =>
                  (p.setPaused(LimitOnPatience.PauseReason.HeaderTime, true) *> waiting)
                    .guarantee(p.setPaused(LimitOnPatience.PauseReason.HeaderTime, false))
                )
          }
      }

  /** Native `handleHeaderArrival` samples wall time once, then delays once (InFutureCheck.hs
    * 160–176); a wall-clock step during that delay does not schedule another wait.
    */
  private[node] def delayOnce[F[_]: Temporal](clock: SlotClock[F], onset: Instant): F[Unit] =
    clock.now.flatMap { now =>
      val remaining = java.time.Duration.between(now, onset)
      if remaining.isNegative || remaining.isZero then Temporal[F].unit
      else Temporal[F].sleep(remaining.toNanos.nanos)
    }

  /** Sleep (on the monotonic clock) until the wall clock reaches `onset`. The wall clock is read
    * again after each sleep, so a clock that stepped back is waited out and one that stepped
    * forward is noticed at the next wake-up. Stack-safe: `tailRecM` over the unit state.
    */
  private[node] def until[F[_]: Temporal](clock: SlotClock[F], onset: Instant): F[Unit] =
    Monad[F].tailRecM(()) { _ =>
      clock.now.flatMap { now =>
        val remaining = java.time.Duration.between(now, onset)
        if remaining.isNegative || remaining.isZero then ().asRight[Unit].pure[F]
        else Temporal[F].sleep(remaining.toNanos.nanos).as(().asLeft[Unit])
      }
    }
