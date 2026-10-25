// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Concurrent, Deferred, Fiber, Ref, Resource}
import cats.effect.implicits.*
import cats.effect.std.Supervisor
import cats.syntax.all.*
import scala.collection.immutable.TreeMap

/** A [[Supervisor]] that keeps its children in the order they were supervised and, when it is
  * released, cancels them youngest first, one at a time, each awaited before the next.
  *
  * The supervisor of cats-effect keys its children by `Unique.Token` and cancels them through
  * `values.parUnorderedTraverse`, so the order in which in-flight work ends depends on identity
  * hash codes: two runs of the same schedule end a session with different work completed. The
  * reference has no such freedom. Each peer of the consensus test simulator has a registry of its
  * own (ouroboros-consensus `82ecba329`, `ouroboros-consensus-diffusion/test/consensus-test/Test/
  * Consensus/PeerSimulator/Run.hs` 443 to 474), and a registry kills its threads youngest first,
  * one at a time (resource-registry 0.3.0.0, `ResourceRegistry.hs` 551 to 553, 650 and 735).
  *
  * The contract of `supervise` is that of `Supervisor(await = false)`: the effect runs in a fiber
  * of its own, which outlives the call and is cancelled when the supervisor is released; its error
  * or outcome stays in the returned fiber and is not raised in the caller. A child that ends leaves
  * the registry. Supervising after the release fails with the `IllegalStateException` that the
  * library's supervisor raises (`supervisor already shutdown`), and starts no work.
  */
object OrderedSupervisor:
  private enum State[F[_]]:
    case Open(next: Long, children: TreeMap[Long, Fiber[F, Throwable, ?]])
    case Closed()

  def apply[F[_]](using F: Concurrent[F]): Resource[F, Supervisor[F]] =
    Resource
      .make(Ref.of[F, State[F]](State.Open(0L, TreeMap.empty)))(release[F])
      .map(state => supervisor[F](state))

  private def supervisor[F[_]](state: Ref[F, State[F]])(using F: Concurrent[F]): Supervisor[F] =
    new Supervisor[F]:
      def supervise[A](fa: F[A]): F[Fiber[F, Throwable, A]] =
        F.uncancelable { _ =>
          Deferred[F, Unit].flatMap { registered =>
            state
              .modify {
                case State.Open(next, children) => (State.Open(next + 1, children), Some(next))
                case closed @ State.Closed()    => (closed, None)
              }
              .flatMap {
                case None     => F.raiseError(shutdown)
                case Some(id) =>
                  // The child waits until it is registered, so that it can only leave the registry
                  // after it has entered it.
                  val child = (registered.get *> fa).guarantee(forget(state, id))
                  F.start(child).flatMap { fiber =>
                    state
                      .modify {
                        case State.Open(next, children) =>
                          (State.Open(next, children.updated(id, fiber)), true)
                        case closed @ State.Closed() => (closed, false)
                      }
                      .flatMap {
                        case true  => registered.complete(()).as(fiber)
                        case false => fiber.cancel *> F.raiseError(shutdown)
                      }
                  }
              }
          }
        }

  private def shutdown: Throwable = new IllegalStateException("supervisor already shutdown")

  private def forget[F[_]](state: Ref[F, State[F]], id: Long): F[Unit] =
    state.update {
      case State.Open(next, children) => State.Open(next, children - id)
      case closed @ State.Closed()    => closed
    }

  private def release[F[_]](state: Ref[F, State[F]])(using F: Concurrent[F]): F[Unit] =
    state
      .getAndSet(State.Closed())
      .flatMap {
        case State.Open(_, children) => children.values.toList.reverse.traverse_(_.cancel)
        case State.Closed()          => F.unit
      }
