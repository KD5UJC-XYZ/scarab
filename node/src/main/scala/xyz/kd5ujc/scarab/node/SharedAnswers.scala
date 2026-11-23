// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.Mutex
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.Stream

/** Answers that are costly to build, built one at a time and shared: the HTTP query server's
  * `GET /state` (#1150). A request for a key whose answer is being built waits for that build and
  * gets its answer. The answer at the current point is kept until the point moves, so asking again
  * builds nothing; an answer at any other point goes to the requests that waited for it and is not
  * kept.
  */
trait SharedAnswers[F[_], P, K, A]:
  /** The answer for `key`: the kept one, the one being built, or the one `build` gives, run once no
    * other build runs. A build that fails fails every request that waited for it, and is not kept.
    * When the request that runs a build is cancelled, a request that waited for it asks again.
    */
  def answer(key: K)(build: => F[A]): F[A]

  /** The current point is now `point`: the answers at any other point are dropped. */
  def moved(point: P): F[Unit]

object SharedAnswers:
  /** How a build ended, for the requests that waited for it. */
  private[node] enum Ending[+A]:
    case Answered(answer: A)
    case Failed(cause: Throwable)

    /** The request that ran the build was cancelled: a request that waited for it asks again. */
    case Abandoned

  /** A key's answer: being built, with the end of the build to wait on, or built and kept. */
  private[node] enum Entry[+D, +A]:
    case Building(ending: D)
    case Built(answer: A)

  /** What a request does: take the kept answer, wait for the build under way, or build. */
  private[node] enum Step[+D, +A]:
    case Kept(answer: A)
    case Wait(ending: D)
    case Build

  /** The answers, and the point they are kept for. A key has one entry at most, so at most one
    * build of a key is under way; a built entry is at `current`, since [[moved]] drops the others
    * and [[settle]] keeps an answer only at `current`. `D` is what a waiting request waits on.
    */
  private[node] final case class Table[P, K, D, A](
      current: Option[P],
      entries: Map[K, Entry[D, A]]
  ):
    /** A request for `key`; if it is to build, the key's entry is `mine` until it is settled. */
    def ask(key: K, mine: D): (Table[P, K, D, A], Step[D, A]) =
      entries.get(key) match
        case Some(Entry.Built(answer))    => (this, Step.Kept(answer))
        case Some(Entry.Building(theirs)) => (this, Step.Wait(theirs))
        case None => (copy(entries = entries.updated(key, Entry.Building(mine))), Step.Build)

    /** The build of `key` ended with `ending`: its answer is kept if it is at the current point. */
    def settle(key: K, ending: Ending[A], at: K => P): Table[P, K, D, A] =
      ending match
        case Ending.Answered(answer) if current.contains(at(key)) =>
          copy(entries = entries.updated(key, Entry.Built(answer)))
        case _ => copy(entries = entries.removed(key))

    def moved(point: P, at: K => P): Table[P, K, D, A] =
      Table(
        Some(point),
        entries.filter {
          case (_, Entry.Building(_)) => true
          case (key, Entry.Built(_))  => at(key) == point
        }
      )

  private[node] object Table:
    def empty[P, K, D, A]: Table[P, K, D, A] = Table(None, Map.empty)

  /** Answers kept for no point until the first [[SharedAnswers.moved]]; `at` is the point of a
    * key's answer.
    */
  def of[F[_]: Async, P, K, A](at: K => P): F[SharedAnswers[F, P, K, A]] =
    (Ref.of[F, Table[P, K, Deferred[F, Ending[A]], A]](Table.empty), Mutex[F]).mapN {
      (table, lock) =>
        new SharedAnswers[F, P, K, A]:
          def answer(key: K)(build: => F[A]): F[A] =
            Deferred[F, Ending[A]]
              .flatMap { mine =>
                Async[F].uncancelable { poll =>
                  table.modify(_.ask(key, mine)).flatMap {
                    case Step.Kept(answer) => Async[F].pure(Ending.Answered(answer))
                    case Step.Wait(theirs) => poll(theirs.get)
                    case Step.Build =>
                      poll(lock.lock.surround(Async[F].defer(build)).attempt)
                        .onCancel(end(key, mine, Ending.Abandoned))
                        .flatMap { result =>
                          val ending = result.fold(Ending.Failed(_), Ending.Answered(_))
                          end(key, mine, ending).as(ending)
                        }
                  }
                }
              }
              .flatMap {
                case Ending.Answered(answer) => Async[F].pure(answer)
                case Ending.Failed(cause)    => Async[F].raiseError(cause)
                case Ending.Abandoned        => answer(key)(build)
              }

          def moved(point: P): F[Unit] = table.update(_.moved(point, at))

          private def end(key: K, mine: Deferred[F, Ending[A]], ending: Ending[A]): F[Unit] =
            table.update(_.settle(key, ending, at)) *> mine.complete(ending).void
    }

  /** Answers kept for the points of `points`, for the life of the resource. An exception in the
    * fiber that follows them goes to `defect`, which stops the node.
    */
  def following[F[_]: Async, P, K, A](
      points: Stream[F, P],
      at: K => P,
      defect: Throwable => F[Unit]
  ): Resource[F, SharedAnswers[F, P, K, A]] =
    Resource
      .eval(of[F, P, K, A](at))
      .flatTap(answers =>
        points.evalMap(answers.moved).compile.drain.handleErrorWith(defect).background
      )
