// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Async, Ref, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.fetcher.{BlockCursor, BlockSource, Digests, Point, SourceEvent, SourceIdentity}
import lab.network.*
import scala.concurrent.duration.*

/** Bounded Conway byte acquisition, never a consensus/ledger tip. */
object BoundedChainFollower:
  final class Invalid(message: String) extends RuntimeException(message)
  final class PublicationFailed(cause: Throwable)
      extends RuntimeException("acquisition checkpoint publication failed; reopen required", cause)
  final case class Policy(
      target: Int = 4,
      maxEvents: Int = 64,
      reconnects: Int = 2,
      maxBytes: Long = 32L * 1024 * 1024,
      duration: FiniteDuration = 60.seconds
  ):
    def valid: Boolean = target >= 1 && target <= 8 && maxEvents >= target && maxEvents <= 256 &&
      reconnects >= 0 && reconnects <= 4 && maxBytes > 0 && maxBytes <= 64L * 1024 * 1024 &&
      duration > Duration.Zero && duration <= 120.seconds

  final case class Original(envelope: Bytes, block: Bytes)
  private final case class Entry(original: Original, header: ReferenceCaptureCommand.Header):
    def point: ChainSync.Point =
      ChainSync.Point.Block(ChainSync.UInt64.from(header.slot).toOption.get, header.hash)

  /** Construct only by rechecking the original bytes and complete parent/number chain. */
  final class Checkpoint private[BoundedChainFollower] (
      val anchor: ChainSync.Point,
      private[BoundedChainFollower] val entries: Vector[Entry]
  ):
    def originals: Vector[Original] = entries.map(_.original)
    def tip: ChainSync.Point = entries.lastOption.fold(anchor)(_.point)
    def size: Int = entries.size
    def candidates: Vector[ChainSync.Point] = entries.reverse.map(_.point) :+ anchor
    private[BoundedChainFollower] def rollback(point: ChainSync.Point): Either[String, Checkpoint] =
      if point == anchor then Right(new Checkpoint(anchor, Vector.empty))
      else
        val index = entries.indexWhere(_.point == point)
        Either.cond(
          index >= 0,
          new Checkpoint(anchor, entries.take(index + 1)),
          "rollback outside retained acquisition window"
        )

    /** Immutable branch adapter for existing Fetch.run/NioSegmentStore. Its digest binds all bytes.
      * A different fork has a different identity and requires a separate store.
      */
    def source[F[_]: Async]: BlockSource[F] =
      val predecessor = Point.parse(pointText(anchor)).toOption.get
      val recipe = originals
        .map(o =>
          Digests.sha256(o.envelope.value.toArray) + ":" + Digests.sha256(o.block.value.toArray)
        )
        .mkString("bounded-conway-acquisition-v1\n" + pointText(anchor) + "\n", "\n", "\n")
      val bound = SourceIdentity
        .checked(Digests.text(recipe), "bounded-conway-acquisition-v1", predecessor)
        .toOption
        .get
      new BlockSource[F]:
        def identity = bound
        def inputBytes = Async[F].pure(originals.map(_.block.size.toLong).sum)
        def open = Resource.eval(Ref.of[F, Int](0)).map { position =>
          new BlockCursor[F]:
            def next = position.modify { index =>
              if index < entries.size then
                (index + 1, SourceEvent.Raw(entries(index).original.block))
              else (index, SourceEvent.End)
            }
        }

  private def pointText(point: ChainSync.Point): String = point match
    case ChainSync.Point.Block(slot, hash) => s"${slot.value}:${hash.hex}"
    case _                                 => "origin"

  def checked(anchor: ChainSync.Point, originals: Vector[Original]): Either[String, Checkpoint] =
    val concrete = anchor match
      case ChainSync.Point.Block(_, hash) => hash.size == 32
      case _                              => false
    if !concrete || originals.size > 8 then
      Left("concrete anchor and at most eight originals required")
    else
      originals.foldLeft[Either[String, Checkpoint]](Right(new Checkpoint(anchor, Vector.empty))) {
        (state, original) => state.flatMap(append(_, original))
      }

  private def append(state: Checkpoint, original: Original): Either[String, Checkpoint] =
    for
      _ <- Either.cond(
        original.envelope.size <= 65535 && original.block.size <= 1048576 && state.size < 8,
        (),
        "acquisition object limit"
      )
      h <- ReferenceCaptureCommand.header(original.envelope)
      _ <- ReferenceCaptureCommand.compare(h, original.block, state.tip)
      _ <- Either.cond(
        state.entries.lastOption.forall(e => h.blockNo == e.header.blockNo + 1),
        (),
        "block number discontinuity"
      )
    yield new Checkpoint(state.anchor, state.entries :+ Entry(original, h))

  enum Event:
    case Forward(envelope: Bytes)
    case Backward(point: ChainSync.Point)
    case Await

  /** Each resource owns one fresh ChainSync connection. fetch owns an exact single-block batch. */
  trait Peer[F[_]]:
    def intersect(candidates: Vector[ChainSync.Point]): F[ChainSync.Point]
    def next: F[Event]
    def fetch(point: ChainSync.Point): F[Bytes]

  final case class Outcome(checkpoint: Checkpoint, reason: String, events: Int, admittedBytes: Long)

  final class Follower[F[_]: Async] private[BoundedChainFollower] (
      state: Ref[F, Checkpoint],
      gate: Semaphore[F],
      peer: Resource[F, Peer[F]],
      policy: Policy,
      publish: Checkpoint => F[Unit]
  ):
    private val F = Async[F]
    def checkpoint: F[Checkpoint] = state.get
    private def check[A](value: Either[String, A]): F[A] =
      F.fromEither(value.leftMap(new Invalid(_)))
    private def commit(next: Checkpoint): F[Unit] =
      F.uncancelable(_ =>
        publish(next).handleErrorWith(error => F.raiseError(new PublicationFailed(error))) *>
          state.set(next)
      )

    /** Serialized, cancelable invocation. Checked progress remains readable after cancellation. */
    def run: F[Outcome] = gate.permit.use { _ =>
      for
        events <- Ref.of[F, Int](0)
        bytes <- Ref.of[F, Long](0)
        result <- {
          def outcome(reason: String) =
            (state.get, events.get, bytes.get).mapN(Outcome(_, reason, _, _))
          def charge(n: Int) = bytes
            .modify { used =>
              if n.toLong > policy.maxBytes - used then (used, false) else (used + n, true)
            }
            .flatMap(ok => F.raiseUnless(ok)(new Invalid("input budget")))
          def session: F[Unit] = peer.use { p =>
            def loop: F[Unit] = state.get.flatMap { current =>
              if current.size >= policy.target then F.unit
              else
                events
                  .modify(n => if n < policy.maxEvents then (n + 1, true) else (n, false))
                  .flatMap { allowed =>
                    F.raiseUnless(allowed)(new Invalid("event budget")) *> p.next.flatMap {
                      case Event.Await => loop
                      case Event.Backward(point) =>
                        check(current.rollback(point)).flatMap(commit) *> loop
                      case Event.Forward(envelope) =>
                        charge(envelope.size) *> check(ReferenceCaptureCommand.header(envelope))
                          .flatMap { h =>
                            val point = ChainSync.Point.Block(
                              ChainSync.UInt64.from(h.slot).toOption.get,
                              h.hash
                            )
                            // Reject stale/duplicate/forked announcements before requesting bytes.
                            val extendsTip = current.tip match
                              case ChainSync.Point.Block(slot, hash) =>
                                h.parent == hash && h.slot > slot.value
                              case _ => false
                            F.raiseUnless(extendsTip)(
                              new Invalid("announcement does not extend acquisition tip")
                            ) *>
                              p.fetch(point)
                                .flatMap(raw =>
                                  charge(raw.size) *> check(
                                    append(current, Original(envelope, raw))
                                  )
                                )
                                .flatMap(commit) *> loop
                          }
                    }
                  }
            }
            state.get.flatMap { current =>
              p.intersect(current.candidates)
                .flatMap(point => check(current.rollback(point)))
                .flatMap(commit) *> loop
            }
          }
          def attempt(left: Int): F[Outcome] =
            session.as("targetReached").flatMap(outcome).handleErrorWith {
              case e: PublicationFailed => F.raiseError(e)
              case e: Invalid           => outcome(e.getMessage)
              case _ if left > 0        => attempt(left - 1)
              case _                    => outcome("peerFailure")
            }
          F.timeoutTo(attempt(policy.reconnects), policy.duration, outcome("timeBudget"))
        }
      yield result
    }

  def resource[F[_]: Async](
      initial: Checkpoint,
      peer: Resource[F, Peer[F]],
      policy: Policy = Policy()
  ): Resource[F, Follower[F]] =
    withPublication(initial, peer, policy, _ => Async[F].unit)

  /** A fresh owner reopens/revalidates the store before offering intersection candidates. */
  def persistedResource[F[_]: Async](
      store: AcquisitionCheckpointStore[F],
      peer: Resource[F, Peer[F]],
      policy: Policy = Policy()
  ): Resource[F, Follower[F]] =
    Resource.eval(store.snapshot).flatMap { saved =>
      Resource.eval(Ref.of[F, AcquisitionCheckpoint.Revision](saved.revision)).flatMap { revision =>
        withPublication(
          saved.checkpoint,
          peer,
          policy,
          next =>
            revision.get.flatMap(store.save(_, next)).flatMap(saved => revision.set(saved.revision))
        )
      }
    }

  private def withPublication[F[_]: Async](
      initial: Checkpoint,
      peer: Resource[F, Peer[F]],
      policy: Policy,
      publish: Checkpoint => F[Unit]
  ): Resource[F, Follower[F]] =
    Resource.eval(
      Async[F].raiseUnless(policy.valid && initial.size <= policy.target)(
        new Invalid("invalid follower policy/checkpoint")
      ) *>
        (Ref.of[F, Checkpoint](initial), Semaphore[F](1))
          .mapN(new Follower(_, _, peer, policy, publish))
    )

  /** Transport injection supports scripted peers and owned AsyncTcpTransport resources alike. Idle
    * bounds local processing between replies and the next request; it does not change handshake,
    * can-await, must-reply or block-fetch deadlines.
    */
  def sessions[F[_]: Async](
      connection: Resource[F, ByteTransport[F]],
      magic: Long,
      idle: FiniteDuration = 5.seconds
  ): Resource[F, Peer[F]] =
    val F = Async[F]
    def invalid[A](message: String): F[A] = F.raiseError(new Invalid(message))
    val deadlines = SessionDeadlines[F](5.seconds, idle, 5.seconds, F.pure(Some(120.seconds)))
    Resource.eval(
      F.raiseUnless(idle > Duration.Zero && idle <= 120.seconds)(
        new Invalid("session idle deadline must be positive and at most 120 seconds")
      )
    ) *> connection
      .flatMap(t =>
        ConnectionSession.resource[F, ChainSyncFixtures.OpaqueNtNHeaderFixture](
          t,
          ConnectionSession.NtN14,
          ChainSync.Role.Client,
          deadlines,
          ConnectionSession.Config(maxSduPayload = 65535, maxChunkBytes = 65543)
        )
      )
      .evalTap(_.negotiate(Handshake.defaultNodeToNode(magic)).flatMap {
        case Handshake.Result.Negotiated(14, data) if data == Handshake.Data(magic) => F.unit
        case _ => invalid("unexpected negotiation")
      })
      .flatMap { session =>
        Resource.eval(Ref.of[F, Boolean](false)).map { awaiting =>
          new Peer[F]:
            def intersect(candidates: Vector[ChainSync.Point]) =
              session.send(ChainSync.Message.FindIntersect(candidates)) *> session.receive.flatMap {
                case ChainSync.Message.IntersectFound(point, _) if candidates.contains(point) =>
                  F.pure(point)
                case _ => invalid("intersection unavailable or unoffered")
              }
            def next = awaiting.get.flatMap(waiting =>
              if waiting then F.unit else session.send(ChainSync.Message.RequestNext)
            ) *>
              session.receive.flatMap {
                case ChainSync.Message.AwaitReply => awaiting.set(true).as(Event.Await)
                case ChainSync.Message.RollForward(h, _) =>
                  awaiting.set(false).as(Event.Forward(h.bytes))
                case ChainSync.Message.RollBackward(point, _) =>
                  awaiting.set(false).as(Event.Backward(point))
                case _ => invalid("unexpected ChainSync response")
              }
            def fetch(point: ChainSync.Point) =
              F.fromEither(CardanoBlockFetch.SpecificPoint.from(point).leftMap(new Invalid(_)))
                .flatMap { specific =>
                  connection
                    .flatMap(t =>
                      BlockFetchSession.resource[F](
                        t,
                        BlockFetchSession.Config(
                          handshake = 5.seconds,
                          times = BlockFetch.TimeLimits(
                            busy = 5.seconds,
                            streaming = 5.seconds,
                            wholeRequest = 15.seconds
                          )
                        )
                      )
                    )
                    .use { fetch =>
                      fetch.bounded {
                        for
                          _ <- fetch.negotiate(Handshake.Data(magic))
                          _ <- fetch.request(CardanoBlockFetch.InclusiveRange.single(specific))
                          start <- fetch.receive
                          _ <- F.raiseUnless(start == BlockFetch.Message.StartBatch)(
                            new Invalid("expected StartBatch")
                          )
                          message <- fetch.receive
                          raw <- message match
                            case BlockFetch.Message.Block(block) => F.pure(block.bytes)
                            case _ => invalid[Bytes]("expected one block")
                          end <- fetch.receive
                          _ <- F.raiseUnless(end == BlockFetch.Message.BatchDone)(
                            new Invalid("incomplete or excess batch")
                          )
                          _ <- fetch.finish
                        yield raw
                      }
                    }
                }
        }
      }
