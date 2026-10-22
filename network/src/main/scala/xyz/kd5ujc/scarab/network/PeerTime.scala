// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, Ref, Resource, Temporal}
import cats.effect.std.Mutex
import cats.syntax.all.*
import scala.concurrent.duration.*

/** A request clock excluding local consumer work. Concurrent callbacks pause the clock once, rather
  * than subtracting overlapping waits twice; queued requests use the same clock.
  */
private[network] final class PeerTime[F[_]: Temporal] private (
    state: Ref[F, PeerTime.State[F]],
    lock: Mutex[F]
):
  private val F = Temporal[F]
  private def read = lock.lock.surround((F.monotonic, state.get).tupled)
  private def elapsed(time: FiniteDuration, current: PeerTime.State[F]): FiniteDuration =
    time - current.excluded - current.paused.fold(Duration.Zero)(time - _)
  def now: F[FiniteDuration] = read.map((time, current) => elapsed(time, current))

  private def pause(enter: Boolean): F[Unit] = lock.lock.surround {
    (F.monotonic, Deferred[F, Unit]).flatMapN { (time, changed) =>
      state
        .modify { current =>
          val next =
            if enter then
              current.copy(depth = current.depth + 1, paused = current.paused.orElse(Some(time)))
            else if current.depth == 1 then
              current.copy(
                depth = 0,
                paused = None,
                excluded = current.excluded + current.paused.fold(Duration.Zero)(time - _)
              )
            else current.copy(depth = current.depth - 1)
          next.copy(changed = changed) -> current.changed
        }
        .flatMap(_.complete(()).void)
    }
  }
  def local[A](body: F[A]): F[A] = Resource.make(pause(true))(_ => pause(false)).use(_ => body)

  private def expired(began: FiniteDuration, budget: FiniteDuration): F[Unit] =
    F.tailRecM(()) { _ =>
      read.flatMap { (time, current) =>
        val remaining = budget - (elapsed(time, current) - began)
        if remaining <= Duration.Zero then F.pure(Right(()))
        else
          (if current.paused.nonEmpty then current.changed.get
           else F.race(current.changed.get, F.sleep(remaining)).void).as(Left(()))
      }
    }

  def within[A](limit: ProtocolDeadline, began: FiniteDuration)(wire: F[A])(timeout: F[A]): F[A] =
    limit match
      case ProtocolDeadline.Unbounded => wire
      case ProtocolDeadline.After(budget) =>
        now.flatMap { current =>
          if current - began >= budget then timeout
          else F.race(wire, expired(began, budget)).flatMap(_.fold(F.pure, _ => timeout))
        }

private[network] object PeerTime:
  private final case class State[F[_]](
      depth: Int,
      paused: Option[FiniteDuration],
      excluded: FiniteDuration,
      changed: Deferred[F, Unit]
  )
  def create[F[_]: Temporal]: F[PeerTime[F]] =
    Deferred[F, Unit].flatMap { changed =>
      (Ref.of[F, State[F]](State(0, None, Duration.Zero, changed)), Mutex[F])
        .mapN(new PeerTime(_, _))
    }
