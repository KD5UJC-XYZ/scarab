// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.assertions

import cats.Applicative
import cats.effect.{Async, Ref, Resource, Sync}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.{Encoder, JsonObject}
import io.circe.syntax.*
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.network.Fibers

/** Antithesis assertions made from inside the node (#1032): the component that knows a fact asserts
  * it, from its typed event, when it happens, so that the platform sees a rare branch at once and
  * can steer its exploration toward it. A property is a case of the closed catalogue
  * ([[Property]]); its details are any value with an `Encoder.AsObject`, shown in the platform's
  * report, and taken by name: they are built and encoded only for an evaluation that is written, so
  * never without `--assertions-file`. An assertion never blocks, writes or raises: what it costs
  * the caller is a look at what was recorded before.
  */
trait Assertions[F[_]]:
  /** `property` is evaluated here with `condition`, which is true at least once in a run. */
  def sometimes[D: Encoder.AsObject](
      property: Property.Sometimes,
      condition: Boolean,
      details: => D
  ): F[Unit]

  /** `property` is evaluated here with `condition`, which is true every time. */
  def always[D: Encoder.AsObject](
      property: Property.Always,
      condition: Boolean,
      details: => D
  ): F[Unit]

  /** `property`, a branch the node never reaches, is reached here. */
  def unreachable[D: Encoder.AsObject](property: Property.Unreachable, details: => D): F[Unit]

object Assertions:
  def apply[F[_]](using assertions: Assertions[F]): Assertions[F] = assertions

  /** No assertions, the node without `--assertions-file`: nothing is recorded or written. */
  def noop[F[_]: Applicative]: Assertions[F] = new Assertions[F]:
    def sometimes[D: Encoder.AsObject](
        property: Property.Sometimes,
        condition: Boolean,
        details: => D
    ): F[Unit] = Applicative[F].unit
    def always[D: Encoder.AsObject](
        property: Property.Always,
        condition: Boolean,
        details: => D
    ): F[Unit] = Applicative[F].unit
    def unreachable[D: Encoder.AsObject](property: Property.Unreachable, details: => D): F[Unit] =
      Applicative[F].unit

  /** The assertions recorded in the fallback SDK's lines ([[SdkLine]]), each line handed to `sink`
    * in one call ([[appending]] writes them to a file).
    *
    *   - A fiber of the resource writes the declarations of the whole catalogue first, then each
    *     evaluation in the order it was made.
    *   - An assertion records the first true and the first false evaluation of its property (in one
    *     `Ref.modify`), encodes a new one's details and offers it to an unbounded queue, in one
    *     uncancelable step, and returns: the queue holds at most two evaluations per property in
    *     the node's life, and the caller never waits for the writer. The SDK asks for no more: a
    *     property's result depends only on whether it was ever true and ever false. Details whose
    *     encoder throws are written as the exception's class ([[unencoded]]), so the evaluation is
    *     written and the caller does not raise.
    *   - The writer runs in an error scope of its own and ends with a value (ADR 0017, [[writer]]).
    *     A sink that fails ends it with [[AssertionsFailure]], which goes to `failed` once; the
    *     node goes on, and what is offered after that is not written.
    *   - At release the queue is drained before the sink closes, so an evaluation made just before
    *     the node stops (the fault that stops it) is written.
    */
  def recording[F[_]: Async](
      sink: Resource[F, String => F[Unit]],
      failed: AssertionsFailure => F[Unit]
  ): Resource[F, Assertions[F]] =
    for
      seen <- Resource.eval(Ref.of[F, Set[(Property, Boolean)]](Set.empty))
      queue <- Resource.eval(Queue.unbounded[F, Option[Evaluation]])
      _ <- Resource.make(writer(sink, queue, failed).start)(running =>
        queue.offer(None) *> running.join.void
      )
    yield new Assertions[F]:
      def sometimes[D: Encoder.AsObject](
          property: Property.Sometimes,
          condition: Boolean,
          details: => D
      ): F[Unit] = evaluated(property, condition, details)
      def always[D: Encoder.AsObject](
          property: Property.Always,
          condition: Boolean,
          details: => D
      ): F[Unit] = evaluated(property, condition, details)
      def unreachable[D: Encoder.AsObject](property: Property.Unreachable, details: => D): F[Unit] =
        evaluated(property, false, details)

      private def evaluated[D: Encoder.AsObject](
          property: Property,
          condition: Boolean,
          details: => D
      ): F[Unit] =
        Async[F].uncancelable(_ =>
          seen
            .modify(recorded =>
              if recorded((property, condition)) then (recorded, false)
              else (recorded + ((property, condition)), true)
            )
            .flatMap(fresh =>
              Async[F].whenA(fresh)(
                Async[F]
                  .delay(details.asJsonObject)
                  .attempt
                  .flatMap(encoded =>
                    queue.offer(
                      Some(Evaluation(property, condition, encoded.fold(unencoded, identity)))
                    )
                  )
              )
            )
        )

  /** The details of an evaluation whose encoder threw: the exception's class. */
  private[assertions] def unencoded(cause: Throwable): JsonObject =
    JsonObject("unencoded" -> cause.getClass.getName.asJson)

  /** The writer fiber: its body in a scope of its own ([[written]]), then its failure reported to
    * `failed`, once. The report is contained as well: a `failed` that raises (a log that cannot be
    * written) leaves the value as it was and is not reported again, so the fiber ends with a value
    * whatever happens (ADR 0017).
    */
  private[assertions] def writer[F[_]: Async](
      sink: Resource[F, String => F[Unit]],
      queue: Queue[F, Option[Evaluation]],
      failed: AssertionsFailure => F[Unit]
  ): F[Either[AssertionsFailure, Unit]] =
    written(sink, queue).flatTap(_.fold(failed(_).attempt.void, _ => Async[F].unit))

  /** The writer's body, in its own scope: the declarations, then the evaluations until the release
    * offers `None`.
    */
  private def written[F[_]: Async](
      sink: Resource[F, String => F[Unit]],
      queue: Queue[F, Option[Evaluation]]
  ): F[Either[AssertionsFailure, Unit]] =
    Fibers.scoped[F, AssertionsFailure, Unit](AssertionsFailure.Unwritable(_))(
      sink.use { write =>
        def drained: F[Unit] = queue.take.flatMap(_.traverse_(e => write(e.line) *> drained))
        Property.catalogue.traverse_(p =>
          write(SdkLine.declaration(p.kind, p.message, p.location))
        ) *> drained
      }
    )

  /** `file`, appended to (made, with its directories, when it is not there), as the log file is:
    * each line and its `\n` in one write. A node started again appends its declarations again after
    * what the earlier run wrote. The file is closed at release.
    */
  def appending[F[_]: Sync](file: Path): Resource[F, String => F[Unit]] =
    Resource
      .fromAutoCloseable(Sync[F].blocking {
        Option(file.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
        new FileOutputStream(file.toFile, true)
      })
      .map(stream => line => Sync[F].blocking(stream.write((line + "\n").getBytes(UTF_8))))

  /** An evaluation as the writer receives it, its details already encoded. */
  private[assertions] final case class Evaluation(
      property: Property,
      condition: Boolean,
      details: JsonObject
  ):
    def line: String =
      SdkLine.evaluation(property.kind, property.message, property.location, condition, details)

/** Why the node stopped writing its assertions. The node goes on without them, and says so once. */
enum AssertionsFailure extends DomainError:
  /** The file could not be opened or written: an exception of the file system, kept. */
  case Unwritable(cause: Throwable)

  def message: String = this match
    case Unwritable(cause) =>
      s"the file could not be opened or written: ${Option(cause.getMessage).getOrElse(cause.getClass.getName)}"
