// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** One shared CSJ registry; role changes and wakeups are committed together. Waiting is cancelable
  * and holds no transport or ChainSync locks. Each session's Resource removes it on every exit.
  */
final class ChainSyncJumpingRuntime[F[_]: Async, P, J] private (
    state: Ref[F, (ChainSyncJumping.State[P, J], Deferred[F, Unit])],
    jumpSize: BigInt,
    changed: F[Unit]
):
  import ChainSyncJumping.*
  private val F = Async[F]

  private def alter[A](f: State[P, J] => (State[P, J], A)): F[A] =
    F.uncancelable { _ =>
      Deferred[F, Unit].flatMap { signal =>
        state
          .modify { (before, previous) =>
            val (after, value) = f(before)
            if after == before then ((before, previous), (value, Option.empty[Deferred[F, Unit]]))
            else ((after, signal), (value, Some(previous)))
          }
          .flatMap((value, previous) =>
            previous.traverse_(p => p.complete(()).void *> changed).as(value)
          )
      }
    }

  def snapshot: F[State[P, J]] = state.get.map(_._1)
  def rotate(id: P): F[Unit] = alter(s => (ChainSyncJumping.rotate(s, id), ()))
  def session(id: P, anchor: Point, caughtUp: Boolean): Resource[F, Session] =
    Resource
      .make(alter(s => (register(s, id, anchor, caughtUp), ())))(_ =>
        alter(s => (remove(s, id), ()))
      )
      .as(new Session(id))

  final class Session private[ChainSyncJumpingRuntime] (val id: P):
    def snapshot: F[Option[Peer[P, J]]] =
      ChainSyncJumpingRuntime.this.snapshot.map(_.peers.find(_.id == id))
    def next: F[Instruction[J]] =
      F.tailRecM(()) { _ =>
        Deferred[F, Unit].flatMap { signal =>
          ChainSyncJumpingRuntime
            .publish[F, Either[Deferred[F, Unit], Instruction[J]]](
              state.modify { (before, previous) =>
                val result = ChainSyncJumping.next(before, id)
                result.instruction match
                  case Instruction.Wait => ((before, previous), (Left(previous), None))
                  case instruction if result.state == before =>
                    ((before, previous), (Right(instruction), None))
                  case instruction => ((result.state, signal), (Right(instruction), Some(previous)))
              },
              changed
            )
            .flatMap {
              case Left(wait)         => wait.get.as(Left(()))
              case Right(instruction) => F.pure(Right(instruction))
            }
        }
      }
    def update(info: JumpInfo[J]): F[Unit] = alter(s => (ChainSyncJumping.update(s, id, info), ()))
    def onRollForward(point: Point): F[Unit] =
      alter(s => (ChainSyncJumping.onRollForward(s, id, point, jumpSize), ()))
    def onRollback(point: Point): F[Unit] =
      alter(s => (ChainSyncJumping.onRollback(s, id, point), ()))
    def onAwaitReply: F[Unit] = alter(s => (ChainSyncJumping.onAwaitReply(s, id), ()))
    def processJump(
        info: JumpInfo[J],
        goodPoint: Boolean,
        accepted: Boolean
    ): F[Option[JumpInfo[J]]] =
      alter { s =>
        val result = ChainSyncJumping.processJump(s, id, info, goodPoint, accepted)
        (result.state, result.graft)
      }

object ChainSyncJumpingRuntime:
  /** Installing a replacement wakeup and completing the old one are one cancellation boundary. The
    * returned wait is consumed outside this boundary, so parked jumpers remain cancelable.
    */
  private[selection] def publish[F[_]: Async, A](
      change: F[(A, Option[Deferred[F, Unit]])],
      changed: F[Unit]
  ): F[A] =
    Async[F].uncancelable { _ =>
      change.flatMap((value, previous) =>
        previous.traverse_(p => p.complete(()).void *> changed).as(value)
      )
    }
  def create[F[_]: Async, P, J](
      jumpSize: BigInt = 4320,
      changed: Option[F[Unit]] = None
  ): F[ChainSyncJumpingRuntime[F, P, J]] =
    Deferred[F, Unit].flatMap { signal =>
      Ref
        .of[F, (ChainSyncJumping.State[P, J], Deferred[F, Unit])](
          (ChainSyncJumping.State[P, J](), signal)
        )
        .map(new ChainSyncJumpingRuntime(_, jumpSize, changed.getOrElse(Async[F].unit)))
    }
