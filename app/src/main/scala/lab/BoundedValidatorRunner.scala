// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Resource}
import cats.syntax.all.*
import cats.effect.syntax.all.*
import lab.cbor.{Bytes, Cbor, Value}
import lab.network.ChainSync
import lab.ledger.ClusterTransition as Ledger
import scala.concurrent.duration.*

/** Online, bounded, in-memory scoped validation. Never promotes downloaded progress to authority.
  */
object BoundedValidatorRunner:
  import BoundedChainFollower.{Event, Original, Peer}

  /** Explicit availability classification supplied by a trusted Peer adapter. Ambiguous transport
    * exceptions (including IllegalStateException) are terminal, never inferred retryable.
    */
  final class Unavailable(message: String) extends RuntimeException(message)

  final case class Policy(
      target: Int = 4,
      maxEvents: Int = 64,
      reconnects: Int = 2,
      maxBytes: Long = 32L * 1024 * 1024,
      duration: FiniteDuration = 60.seconds
  ):
    def valid: Boolean =
      BoundedChainFollower.Policy(target, maxEvents, reconnects, maxBytes, duration).valid

  enum Stop:
    case TargetReached, EventBudget, ByteBudget, TimeBudget, AlreadyRun
    case Unsupported(stage: String, feature: String)
    case Rejected(stage: String, reason: String)
    case OutsideRetainedWindow
    case PeerFailure(reason: String)
    case TransportExhausted(reason: String)
    case CleanupFailed(reason: String)
    case Internal(reason: String)

  /** returnedBytes counts returned envelopes and complete returned blocks, NOT total wire traffic.
    * Failed partial fetches are not visible through Peer. Reorgs/retries never refund these
    * counters.
    */
  final case class Outcome(
      snapshot: CoherentSequence.Snapshot,
      reason: Stop,
      events: Int,
      returnedBytes: Long,
      reconnects: Int
  )
  private final case class Halt(reason: Stop) extends RuntimeException
  private final case class Retry(reason: String) extends RuntimeException
  private def describe(e: Throwable): String = e.getClass.getName + ": " +
    Option(e.getMessage).getOrElse("").take(512)

  private[lab] def sequenceFailure(f: CoherentSequence.Failure): Stop = f match
    case CoherentSequence.Failure.Unsupported(stage, feature)  => Stop.Unsupported(stage, feature)
    case CoherentSequence.Failure.Rejected("internal", reason) => Stop.Internal(reason)
    case CoherentSequence.Failure.Rejected(stage, reason)
        if Set("rollback", "certificate-undo", "nonce-undo").contains(stage) =>
      Stop.Internal(stage + ": " + reason)
    case CoherentSequence.Failure.Rejected(stage, reason) => Stop.Rejected(stage, reason)
    case CoherentSequence.Failure.LedgerRejected(Ledger.Failure.InternalFailure(kind)) =>
      Stop.Internal("ledger: " + kind)
    case CoherentSequence.Failure.LedgerRejected(reason) => Stop.Rejected("ledger", reason.toString)
    case CoherentSequence.Failure.OutsideRetainedWindow  => Stop.OutsideRetainedWindow
    case other                                           => Stop.Internal(other.toString)

  final class Runner[F[_]: Async] private[BoundedValidatorRunner] (
      context: SequenceInput.Context,
      runtime: CoherentSequence.Runtime[F],
      peer: Resource[F, Peer[F]],
      policy: Policy,
      started: Ref[F, Boolean],
      events: Ref[F, Int],
      bytes: Ref[F, Long],
      retries: Ref[F, Int],
      cleanup: Ref[F, Option[Stop]],
      between: String => F[Unit]
  ):
    private val F = Async[F]
    def snapshot: F[CoherentSequence.Snapshot] = runtime.snapshot
    private def stop[A](reason: Stop): F[A] = F.raiseError(Halt(reason))
    private def checked[A](result: CoherentSequence.Result[A]): F[A] =
      result.fold(f => stop(sequenceFailure(f)), F.pure)
    private def wire[A](action: F[A]): F[A] = action.handleErrorWith {
      case e: Unavailable => F.raiseError(Retry(describe(e)))
      case e              => stop(Stop.PeerFailure(describe(e)))
    }
    private def charge(n: Int, bound: Int): F[Unit] =
      bytes.updateAndGet(_ + n.toLong).flatMap { total =>
        if total > policy.maxBytes then stop(Stop.ByteBudget)
        else if n <= 0 || n > bound then stop(Stop.Rejected("input", "object size bound"))
        else F.unit
      }
    private def rollback(point: ChainSync.Point): F[Unit] =
      snapshot.flatMap(s => runtime.rollbackTo(s.fence, point)).flatMap(checked).void *>
        between("after-rollback")
    private def header(envelope: Bytes): Either[Stop, ReferenceCaptureCommand.Header] =
      Cbor
        .decode(envelope, Cbor.Limits(65535, 24, 8192, 65535))
        .leftMap(Stop.Rejected("header", _))
        .flatMap { root =>
          val unsupportedEra = root.value match
            case Value.Arr(parts) if parts.size == 2 =>
              (parts(0).value, parts(1).value) match
                case (Value.UInt(era), Value.Tag(tag, body)) if era != 6 && tag == 24 =>
                  body.value match
                    case Value.ByteString(raw) if raw.size <= 4096 => Some(era)
                    case _                                         => None
                case _ => None
            case _ => None
          unsupportedEra match
            case Some(era) => Left(Stop.Unsupported("era", s"NtN era $era; Conway index 6 only"))
            case None =>
              ReferenceCaptureCommand.header(envelope).leftMap(Stop.Rejected("header", _))
        }
    private def forward(p: Peer[F], envelope: Bytes): F[Unit] =
      for
        _ <- charge(envelope.size, 65535)
        h <- F.delay(header(envelope)).flatMap(_.fold(stop, F.pure))
        _ <-
          if h.major != 11 || h.minor != 2 then stop(Stop.Unsupported("header", "11.2 only"))
          else F.unit
        _ <-
          if h.slot / context.nonces.context.epochLength != context.epoch then
            stop(Stop.Unsupported("epoch", "same supplied epoch only"))
          else F.unit
        current <- snapshot
        extendsTip = current.state.acquisition.tip match
          case ChainSync.Point.Block(slot, hash) => h.parent == hash && h.slot > slot.value
          case _                                 => false
        _ <-
          if extendsTip then F.unit
          else stop(Stop.Rejected("header", "announcement does not extend scoped tip"))
        point = ChainSync.Point.Block(ChainSync.UInt64.from(h.slot).toOption.get, h.hash)
        raw <- wire(p.fetch(point))
        _ <- charge(raw.size, 1048576)
        block <- F
          .delay(SequenceInput.block(Original(envelope, raw)))
          .flatMap(
            _.fold(
              {
                case SequenceInput.Failure.Unsupported(feature) =>
                  stop(Stop.Unsupported("input", feature))
                case SequenceInput.Failure.Rejected(stage, detail) =>
                  stop(Stop.Rejected(stage, detail))
              },
              F.pure
            )
          )
        _ <- F.cede *> between("before-prepare")
        candidate <- runtime.prepare(block).flatMap(checked)
        _ <- F.cede *> between("before-publish")
        _ <- runtime.publish(candidate).flatMap(checked)
        _ <- between("after-publish")
      yield ()
    private def loop(p: Peer[F]): F[Stop] = snapshot.flatMap { s =>
      if s.state.acquisition.size >= policy.target then F.pure(Stop.TargetReached)
      else
        events.modify(n => if n < policy.maxEvents then (n + 1, true) else (n, false)).flatMap {
          case false => F.pure(Stop.EventBudget)
          case true =>
            wire(p.next).flatMap {
              case Event.Await             => loop(p)
              case Event.Backward(point)   => rollback(point) *> loop(p)
              case Event.Forward(envelope) => forward(p, envelope) *> loop(p)
            }
        }
    }
    private def session: F[Stop] = F.uncancelable { poll =>
      // allocated remains cancelable; Resource itself releases any partially acquired resources.
      // Acquisition can include failed partial-resource cleanup. Without a typed release channel,
      // even Unavailable here is terminal; only established Peer operations may request a retry.
      poll(peer.allocated.handleErrorWith(e => stop(Stop.PeerFailure(describe(e))))).flatMap {
        (p, release) =>
          val work = for
            s <- snapshot
            offered = s.state.acquisition.candidates
            selected <- wire(p.intersect(offered))
            _ <-
              if offered.contains(selected) then rollback(selected)
              else stop(Stop.OutsideRetainedWindow)
            result <- loop(p)
          yield result
          poll(work).guarantee(release.handleErrorWith { e =>
            val reason = Stop.CleanupFailed(describe(e))
            cleanup.set(Some(reason)) *> stop(reason)
          })
      }
    }
    private def attempt: F[Stop] = session.handleErrorWith { error =>
      cleanup.get.flatMap {
        case Some(reason) => F.pure(reason)
        case None =>
          error match
            case Retry(reason) =>
              retries
                .modify { n =>
                  if n < policy.reconnects then (n + 1, true) else (n, false)
                }
                .flatMap(ok =>
                  if ok then F.cede *> attempt else F.pure(Stop.TransportExhausted(reason))
                )
            case Halt(reason) => F.pure(reason)
            case other        => F.pure(Stop.Internal(describe(other)))
      }
    }
    private def outcome(reason: Stop): F[Outcome] =
      (snapshot, cleanup.get, events.get, bytes.get, retries.get).mapN { (s, failure, e, b, r) =>
        Outcome(s, failure.getOrElse(reason), e, b, r)
      }

    /** One owner and one run for this lifetime. Cancellation preserves readable committed state.
      * AlreadyRun is an advisory rejection; if the first run is active its counters can move. Only
      * the coordinator snapshot is atomic, not the separate telemetry reads.
      */
    def run: F[Outcome] = started.getAndSet(true).flatMap {
      case true  => outcome(Stop.AlreadyRun)
      case false => F.timeoutTo(attempt, policy.duration, F.pure(Stop.TimeBudget)).flatMap(outcome)
    }

  def resource[F[_]: Async](
      context: SequenceInput.Context,
      peer: Resource[F, Peer[F]],
      policy: Policy = Policy()
  ): Resource[F, Runner[F]] = resourceObserved(context, peer, policy, _ => Async[F].unit)

  /** Deterministic test barriers get labels only, never runtime mutation authority. */
  private[lab] def resourceObserved[F[_]: Async](
      context: SequenceInput.Context,
      peer: Resource[F, Peer[F]],
      policy: Policy,
      between: String => F[Unit]
  ): Resource[F, Runner[F]] = Resource.eval {
    val F = Async[F]
    for
      _ <- F.raiseUnless(policy.valid)(new IllegalArgumentException("invalid runner policy"))
      runtime <- CoherentSequence
        .create[F](context)
        .flatMap(r => F.fromEither(r.leftMap(f => Halt(sequenceFailure(f)))))
      started <- Ref.of[F, Boolean](false)
      events <- Ref.of[F, Int](0)
      bytes <- Ref.of[F, Long](0L)
      retries <- Ref.of[F, Int](0)
      cleanup <- Ref.of[F, Option[Stop]](None)
    yield new Runner(
      context,
      runtime,
      peer,
      policy,
      started,
      events,
      bytes,
      retries,
      cleanup,
      between
    )
  }
