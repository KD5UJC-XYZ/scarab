// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import cats.Applicative
import cats.effect.{Async, Deferred, Ref, Resource}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** One shared CSJ registry; role changes and wakeups are committed together. Waiting is cancelable
  * and holds no transport or ChainSync locks. Each session's Resource removes it on every exit.
  * What a transition reports is traced once it is committed, as the reference traces the event its
  * STM transaction returns (consensus 82ecba329, Jumping.hs:312, 943-947).
  */
final class ChainSyncJumpingRuntime[F[_]: Async, P, J] private (
    state: Ref[F, (ChainSyncJumping.State[P, J], Deferred[F, Unit])],
    jumpSize: BigInt,
    changed: F[Unit],
    traces: ChainSyncJumpingRuntime.Traces[F, P]
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

  /** Commit `f`'s step, and give back what it reports. */
  private def commit(f: State[P, J] => Step[P, J, Event[P]]): F[Option[Event[P]]] =
    alter { s =>
      val result = f(s)
      (result.state, result.event)
    }

  /** Commit `f`'s step, then trace what it reports as `id`'s. */
  private def step(id: P)(f: State[P, J] => Step[P, J, Event[P]]): F[Unit] =
    commit(f).flatMap(_.traverse_(traces.csj(id, _)))

  def snapshot: F[State[P, J]] = state.get.map(_._1)

  /** `rotateDynamo` (consensus 82ecba329, Jumping.hs:908-947), traced on the Devoted BlockFetch
    * tracer.
    */
  def rotate(id: P): F[Unit] =
    alter { s =>
      val result = ChainSyncJumping.rotate(s, id)
      (result.state, result.event)
    }.flatMap(_.traverse_(traces.dbf))

  /** A session registered as `registerClient` registers it, and removed on every exit; its
    * registration is traced once the removal is installed, so the trace can neither fail nor block
    * the acquisition.
    */
  def session(id: P, anchor: Point, caughtUp: Boolean): Resource[F, Session] =
    Resource
      .make(commit(register(_, id, anchor, caughtUp)))(_ => step(id)(remove(_, id)))
      .evalTap(_.traverse_(traces.csj(id, _)))
      .as(new Session(id))

  final class Session private[ChainSyncJumpingRuntime] (val id: P):
    def snapshot: F[Option[Peer[P, J]]] =
      ChainSyncJumpingRuntime.this.snapshot.map(_.peers.find(_.id == id))

    /** `jgNextInstruction` (consensus 82ecba329, Jumping.hs:296-304): the first time it must wait
      * for an instruction it traces `BlockedOnJump`, once, before it blocks.
      */
    def next: F[Instruction[J]] =
      F.tailRecM(false) { blocked =>
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
              case Left(wait) =>
                traces.csj(id, Event.BlockedOnJump).unlessA(blocked) *> wait.get.as(Left(true))
              case Right(instruction) => F.pure(Right(instruction))
            }
        }
      }
    def update(info: JumpInfo[J]): F[Unit] = alter(s => (ChainSyncJumping.update(s, id, info), ()))
    def onRollForward(point: Point): F[Unit] =
      step(id)(ChainSyncJumping.onRollForward(_, id, point, jumpSize))
    def onRollback(point: Point): F[Unit] = step(id)(ChainSyncJumping.onRollback(_, id, point))
    def onAwaitReply: F[Unit] = step(id)(ChainSyncJumping.onAwaitReply(_, id))
    def processJump(
        info: JumpInfo[J],
        goodPoint: Boolean,
        accepted: Boolean
    ): F[Option[JumpInfo[J]]] =
      alter { s =>
        val result = ChainSyncJumping.processJump(s, id, info, goodPoint, accepted)
        (result.state, (result.graft, result.event))
      }.flatMap((graft, event) => event.traverse_(traces.csj(id, _)).as(graft))

object ChainSyncJumpingRuntime:
  /** Where the runtime traces: the reference's `csjTracer`, labelled with the peer whose handle the
    * transition was of, and its `dbfTracer` (consensus 82ecba329, ouroboros-consensus-diffusion
    * `Ouroboros/Consensus/Node/Tracers.hs:100-102`).
    */
  trait Traces[F[_], P]:
    def csj(peer: P, event: ChainSyncJumping.Event[P]): F[Unit]
    def dbf(event: ChainSyncJumping.RotatedDynamo[P]): F[Unit]

  object Traces:
    /** Traces nothing. */
    def none[F[_]: Applicative, P]: Traces[F, P] = new Traces[F, P]:
      def csj(peer: P, event: ChainSyncJumping.Event[P]) = Applicative[F].unit
      def dbf(event: ChainSyncJumping.RotatedDynamo[P]) = Applicative[F].unit

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
      changed: Option[F[Unit]] = None,
      traces: Option[Traces[F, P]] = None
  ): F[ChainSyncJumpingRuntime[F, P, J]] =
    Deferred[F, Unit].flatMap { signal =>
      Ref
        .of[F, (ChainSyncJumping.State[P, J], Deferred[F, Unit])](
          (ChainSyncJumping.State[P, J](), signal)
        )
        .map(
          new ChainSyncJumpingRuntime(
            _,
            jumpSize,
            changed.getOrElse(Async[F].unit),
            traces.getOrElse(Traces.none[F, P])
          )
        )
    }
