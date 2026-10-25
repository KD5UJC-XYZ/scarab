// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Concurrent, Deferred}
import cats.effect.Ref
import cats.effect.syntax.all.*
import cats.effect.std.Queue
import cats.syntax.all.*
import scala.collection.immutable.Queue as Fifo
import xyz.kd5ujc.scarab.codec.Bytes

/** Egress.hs:149–213 at ouroboros-network 2fb69829: one SDU turn per active channel. Each lane
  * (mini-protocol and direction) has a soft byte budget like the reference's
  * `egressSoftBufferLimit` (network-mux `Network/Mux.hs`, `muxChannel`): `send` appends while fewer
  * than `limit` bytes are buffered on the lane, so one message may overshoot, and later senders
  * wait. Bytes are released as each SDU is written.
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

  /** A sender waiting for its lane to drain below the limit. The granting writer queues `command`
    * itself, in grant order, and then completes `admitted`.
    */
  private final case class Waiter[F[_]](
      command: Command[F],
      size: Long,
      admitted: Deferred[F, Unit]
  )

  private final case class Budget[F[_]](held: Long, waiters: Fifo[Waiter[F]])

  final class Outbox[F[_]] private[MuxEgress] (
      inbox: Queue[F, Command[F]],
      budgets: Ref[F, Map[Lane, Budget[F]]],
      limit: Long,
      payloadLimit: Int
  )(using F: Concurrent[F]):
    private def empty: Budget[F] = Budget(0L, Fifo.empty)

    /** Admit `bytes` on the lane now, or park a waiter. Admission and queueing are one atomic step
      * against release, so a message is never queued without its bytes counted. A cancelled waiter
      * owns nothing: it is removed while parked, and once granted its message is already queued
      * (cancellation after submission leaves work queued) and the writer releases its bytes.
      */
    def send(protocol: Int, role: Mux.Role, bytes: Bytes): F[Unit] =
      val lane = Lane(protocol, role)
      val command: Command[F] = Command.Send(lane, bytes)
      F.uncancelable { poll =>
        Deferred[F, Unit].flatMap { admitted =>
          val waiter = Waiter[F](command, bytes.size.toLong, admitted)
          budgets
            .modify { all =>
              val budget = all.getOrElse(lane, empty)
              if budget.waiters.isEmpty && budget.held < limit then
                (all.updated(lane, budget.copy(held = budget.held + waiter.size)), true)
              else (all.updated(lane, budget.copy(waiters = budget.waiters.enqueue(waiter))), false)
            }
            .flatMap {
              case true => inbox.offer(command)
              case false =>
                poll(admitted.get).onCancel(
                  budgets.update(all =>
                    all.get(lane).fold(all) { budget =>
                      all.updated(
                        lane,
                        budget.copy(waiters = budget.waiters.filterNot(_.admitted eq admitted))
                      )
                    }
                  )
                )
            }
        }
      }

    /** The bytes of one written SDU leave the lane's buffer; waiters that now fit are admitted in
      * arrival order.
      */
    private def release(lane: Lane, written: Int): F[Unit] =
      F.uncancelable { _ =>
        budgets
          .modify { all =>
            val budget = all.getOrElse(lane, empty)
            @scala.annotation.tailrec
            def grant(
                held: Long,
                waiters: Fifo[Waiter[F]],
                granted: Vector[Waiter[F]]
            ): (Long, Fifo[Waiter[F]], Vector[Waiter[F]]) =
              waiters.dequeueOption match
                case Some((head, rest)) if held < limit =>
                  grant(held + head.size, rest, granted :+ head)
                case _ => (held, waiters, granted)
            val (held, waiters, granted) =
              grant(budget.held - written, budget.waiters, Vector.empty)
            val updated =
              if held == 0L && waiters.isEmpty then all - lane
              else all.updated(lane, Budget(held, waiters))
            (updated, granted)
          }
          .flatMap(_.traverse_(w => inbox.offer(w.command) *> w.admitted.complete(()).void))
      }

    // A flush is not byte-counted: it carries no payload and must reach the writer even while every
    // lane is full.
    def flush: F[Unit] = Deferred[F, Unit].flatMap { ack =>
      inbox.offer(Command.Flush(ack)) *> ack.get
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
                case Right(_)     => release(part.lane, part.bytes.size).as(Left(next))
              }
            case None =>
              ready.fence.traverse_(_.complete(())) *>
                F.pure(Left(Schedule[F](Vector.empty, None)))
        }
      }

  /** @param limit
    *   The soft byte budget of each lane, positive.
    */
  def create[F[_]: Concurrent](limit: Long, payloadLimit: Int): F[Outbox[F]] =
    if limit <= 0 then
      Concurrent[F].raiseError(
        new IllegalArgumentException(s"egress buffer limit must be positive: $limit")
      )
    else
      (Queue.unbounded[F, Command[F]], Ref.of[F, Map[Lane, Budget[F]]](Map.empty))
        .mapN(new Outbox(_, _, limit, payloadLimit))
