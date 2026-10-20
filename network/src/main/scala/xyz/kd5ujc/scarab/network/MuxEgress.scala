// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Concurrent, Deferred}
import cats.effect.std.{Queue, Semaphore}
import cats.syntax.all.*
import scala.collection.immutable.Queue as Fifo
import xyz.kd5ujc.scarab.codec.Bytes

/** Egress.hs:149–213 at ouroboros-network 2fb69829: one SDU turn per active channel. Admission
  * credits cover queued, staged and currently written messages, not just the inbox.
  */
private[network] object MuxEgress:
  final case class Lane(protocol: Int, role: Mux.Role)

  private enum Command[F[_]]:
    case Send(lane: Lane, bytes: Bytes)
    case Flush(ack: Deferred[F, Unit])

  private final case class Active(lane: Lane, head: Bytes, tail: Fifo[Bytes])
  private final case class Part(lane: Lane, bytes: Bytes, last: Boolean)
  private final case class Schedule[F[_]](
      active: Vector[Active],
      fence: Option[Deferred[F, Unit]]
  ):
    def append(lane: Lane, bytes: Bytes): Schedule[F] =
      val index = active.indexWhere(_.lane == lane)
      if index < 0 then copy(active = active :+ Active(lane, bytes, Fifo.empty))
      else
        val current = active(index)
        copy(active = active.updated(index, current.copy(tail = current.tail.enqueue(bytes))))

    def next(limit: Int): Option[(Part, Schedule[F])] = active.headOption.map { current =>
      val remaining = current.head.drop(limit)
      val next =
        if remaining.nonEmpty then Some(current.copy(head = remaining))
        else current.tail.dequeueOption.map { (head, tail) => Active(current.lane, head, tail) }
      Part(current.lane, current.head.take(limit), remaining.isEmpty) ->
        copy(active = active.drop(1) ++ next.toVector)
    }

  final class Outbox[F[_]] private[MuxEgress] (
      inbox: Queue[F, Command[F]],
      credits: Semaphore[F],
      payloadLimit: Int
  )(using F: Concurrent[F]):
    // Once a credit is acquired, ownership transfers atomically to the writer. A cancelled
    // caller waiting for admission owns no credit; cancellation after submission leaves work queued.
    private def submit(command: Command[F]): F[Unit] = F.uncancelable { poll =>
      poll(credits.acquire) *> inbox.offer(command).onError { case _ => credits.release }
    }

    def send(protocol: Int, role: Mux.Role, bytes: Bytes): F[Unit] =
      submit(Command.Send(Lane(protocol, role), bytes))

    def flush: F[Unit] = Deferred[F, Unit].flatMap { ack =>
      submit(Command.Flush(ack)) *> ack.get
    }

    // Stop importing at a global fence. Later commands cannot extend its predecessor set;
    // round-robin service drains that finite prefix before acknowledging it.
    private def fill(initial: Schedule[F]): F[Schedule[F]] = F.tailRecM(initial) { state =>
      def accept(command: Command[F]): Either[Schedule[F], Schedule[F]] = command match
        case Command.Send(lane, bytes) => Left(state.append(lane, bytes))
        case Command.Flush(ack)        => Right(state.copy(fence = Some(ack)))
      if state.fence.nonEmpty then F.pure(Right(state))
      else
        inbox.tryTake.flatMap {
          case Some(command)                 => F.pure(accept(command))
          case None if state.active.nonEmpty => F.pure(Right(state))
          case None                          => inbox.take.map(accept)
        }
    }

    def run(write: (Lane, Bytes) => F[Either[NetworkError, Unit]]): F[NetworkError] =
      F.tailRecM(Schedule[F](Vector.empty, None)) { state =>
        fill(state).flatMap { ready =>
          ready.next(payloadLimit) match
            case Some((part, next)) =>
              write(part.lane, part.bytes).flatMap {
                case Left(reason) => F.pure(Right(reason))
                case Right(_)     => credits.release.whenA(part.last).as(Left(next))
              }
            case None =>
              ready.fence.traverse_(_.complete(())) *> credits.release *>
                F.pure(Left(Schedule[F](Vector.empty, None)))
        }
      }

  def create[F[_]: Concurrent](queuedMessages: Int, payloadLimit: Int): F[Outbox[F]] =
    // The previous envelope was one writer-held message plus the configured queue slots.
    // Allocate queue credits first (retaining rejection of negative capacities), then the writer's
    // one credit. The inbox stays logically bounded even while the writer stages other lanes.
    (Queue.unbounded[F, Command[F]], Semaphore[F](queuedMessages.toLong).flatTap(_.release))
      .mapN(new Outbox(_, _, payloadLimit))
