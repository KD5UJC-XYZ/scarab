// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Fiber, Outcome, Ref, Resource}
import cats.effect.std.Mutex
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.PeerError

/** One queue consumer for a follower session. Reselection can leave its pending read alone; a new
  * intersection first stops that consumer, then drains the peer's retained wire producers
  * (consensus 82ecba329 ChainSync/Client.hs:916–938,1499–1511).
  */
private[node] final class SessionInput[F[_]: Async] private (
    peer: Peer[F],
    state: Ref[F, SessionInput.State[F]],
    mutex: Mutex[F]
):
  import SessionInput.*
  private val F = Async[F]

  /** There is one caller. A completed old event retains its epoch until the session's serial
    * boundary checks it; actual faults always land, including a fault completed before a pause.
    */
  def next(using Raise[F, PeerError]): F[Read] = F.tailRecM(()) { _ =>
    mutex.lock
      .surround {
        F.uncancelable { _ =>
          state.get.flatMap { before =>
            if before.paused then F.pure(Left(before.changed))
            else
              before.active match
                case Some(active) => F.pure(Right(active))
                case None =>
                  Fault.fiber(peer.next).start.flatMap { fiber =>
                    val active = Pending(before.epoch, before.sequence, fiber)
                    state
                      .set(before.copy(sequence = before.sequence + 1, active = Some(active)))
                      .as(Right(active))
                  }
          }
        }
      }
      .flatMap {
        case Left(changed) => changed.get.as(Left(()))
        case Right(active) =>
          active.fiber.join.flatMap {
            case Outcome.Succeeded(result) =>
              clear(active) *> result
                .flatMap(r => Fault.land(r).map(event => Right(Read(active.epoch, event))))
            case Outcome.Errored(error) => F.raiseError(error)
            case Outcome.Canceled() =>
              state.get.flatMap { current =>
                if current.epoch != active.epoch || current.paused then F.pure(Left(()))
                else F.canceled *> F.never[Either[Unit, Read]]
              }
          }
      }
  }

  def current(read: Read): F[Boolean] = state.get.map(s => !s.paused && s.epoch == read.epoch)

  /** Called under the session's serial boundary, never while waiting for a normal next event. A
    * failed intersection leaves reads paused until the owning session releases this resource.
    */
  def reintersect[A](body: F[A])(using Raise[F, PeerError]): F[A] = F.uncancelable { poll =>
    pause *> poll(body).flatTap(_ => resume)
  }

  private def clear(active: Pending[F]): F[Unit] = mutex.lock.surround {
    state.update { before =>
      if before.active.exists(_.sequence == active.sequence) then before.copy(active = None)
      else before
    }
  }

  private def pause(using Raise[F, PeerError]): F[Unit] =
    mutex.lock
      .surround {
        state.modify(before =>
          before.copy(epoch = before.epoch + 1, paused = true, active = None) -> before.active
        )
      }
      .flatMap(_.traverse_ { active =>
        // Cancellation/join is outside the input mutex: its consumer may be clearing a completed
        // result under that mutex. This cancels only peer.next, never a submitted wire producer.
        active.fiber.cancel *> active.fiber.join.flatMap {
          case Outcome.Succeeded(result) =>
            result.flatMap(_.fold(fault => Fault.land[F, Unit](Left(fault)), _ => F.unit))
          case Outcome.Errored(error) => F.raiseError(error)
          case Outcome.Canceled()     => F.unit
        }
      })

  private def resume: F[Unit] = Deferred[F, Unit].flatMap { fresh =>
    mutex.lock
      .surround {
        state.modify(before => before.copy(paused = false, changed = fresh) -> before.changed)
      }
      .flatMap(_.complete(()).void)
  }

private[node] object SessionInput:
  final case class Read private[SessionInput] (epoch: Long, event: SyncEvent)
  private final case class Pending[F[_]](
      epoch: Long,
      sequence: Long,
      fiber: Fiber[F, Throwable, Either[Fault, SyncEvent]]
  )
  private final case class State[F[_]](
      epoch: Long,
      sequence: Long,
      paused: Boolean,
      active: Option[Pending[F]],
      changed: Deferred[F, Unit]
  )

  def resource[F[_]: Async](peer: Peer[F]): Resource[F, SessionInput[F]] =
    Resource.make {
      for
        changed <- Deferred[F, Unit]
        state <- Ref.of[F, State[F]](State(0L, 0L, false, None, changed))
        mutex <- Mutex[F]
      yield new SessionInput(peer, state, mutex)
    } { input =>
      // The owner has already ended. Live reintersection lands a completed causal fault above;
      // teardown suppresses only a secondary outcome while joining its remaining reader.
      Fault.fiber(input.pause).void
    }
