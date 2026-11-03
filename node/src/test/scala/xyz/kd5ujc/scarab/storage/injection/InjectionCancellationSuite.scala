// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.injection

import cats.effect.{Deferred, IO, Ref}
import cats.effect.kernel.Outcome
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

/** Native fs-api bracket cancellation controls; the close-failure case is a regression, not a
  * waiver.
  */
class InjectionCancellationSuite extends FailsOnUncaught:
  private enum FsError extends DomainError:
    case Close
  private enum BodyError extends DomainError:
    case Refusal
  private type Error = InjectionFiles.Failure[FsError, BodyError]

  Vector(false, true).foreach { closeFails =>
    test(s"native cancellation with closeFails=$closeFails preserves the final observed outcome") {
      (for
        entered <- Deferred[IO, Unit]
        events <- Ref.of[IO, Vector[String]](Vector.empty)
        files = new InjectionFiles(new InjectionFiles.Operations[IO, FsError, Unit]:
          def open(segments: Vector[String])(using Raise[IO, FsError]): IO[Unit] =
            events.update(_ :+ "open")
          def readSome(handle: Unit, requested: Int)(using Raise[IO, FsError]): IO[Bytes] =
            IO.pure(Bytes.empty)
          def close(handle: Unit)(using errors: Raise[IO, FsError]): IO[Unit] =
            events.update(_ :+ "close") *> (if closeFails then errors.raise(FsError.Close)
                                            else IO.unit))
        task = Handle
          .allow[Error] {
            files
              .use[BodyError, Unit](Vector("input")) { _ =>
                events.update(_ :+ "body") *> entered.complete(()) *> IO.never[Unit]
              }
              .map(_.asRight[Error])
          }
          .rescue(error => IO.pure(Left(error)))
        observed <- task.start.bracket { worker =>
          entered.get *> worker.cancel *> worker.join.flatMap {
            case Outcome.Canceled()     => IO.pure("cancelled")
            case Outcome.Errored(cause) => IO.pure(s"defect:$cause")
            case Outcome.Succeeded(value) =>
              value.map {
                case Left(InjectionFiles.Failure.File(FsError.Close)) => "close-failure"
                case other                                            => s"completed:$other"
              }
          }
        }(_.cancel)
        history <- events.get
        reported <- IO(takeUncaught().map(e => s"${e.getClass.getName}: $e"))
      yield
        println(
          s"cancel-control closeFails=$closeFails outcome=$observed events=$history reported=$reported"
        )
        assertEquals(history, Vector("open", "body", "close"))
        assertEquals(observed, if closeFails then "close-failure" else "cancelled")
        assertEquals(reported, Nil)
      ).timeout(5.seconds)
    }
  }
