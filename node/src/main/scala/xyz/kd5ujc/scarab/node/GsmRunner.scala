// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Clock, Temporal}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*

/** The effect loop of the Genesis State Machine ([[Gsm]]): it reads the conditions the machine
  * depends on at a fixed interval, steps the machine, keeps the persistent mark (the
  * `CaughtUpMarker` file of the reference, `GSM.hs` 520-550), tells what changed, and publishes the
  * judgement of the ledger the governor follows.
  */
object GsmRunner:

  /** The persistent mark: present when the node left off caught up. */
  trait Marker[F[_]]:
    def present: F[Boolean]
    def set(on: Boolean): F[Unit]

  object Marker:
    /** The mark as a file `CaughtUpMarker` in `dir`, as the reference keeps it. */
    def file[F[_]: cats.effect.Sync](dir: Path): Marker[F] = new Marker[F]:
      private val path = dir.resolve("CaughtUpMarker")
      def present = cats.effect.Sync[F].blocking(Files.exists(path))
      def set(on: Boolean) = cats.effect.Sync[F].blocking {
        if on then
          Files.createDirectories(dir)
          if !Files.exists(path) then Files.createFile(path)
        else Files.deleteIfExists(path)
        ()
      }

    /** No file: a node without a database does not remember being caught up. */
    def none[F[_]: cats.Applicative]: Marker[F] = new Marker[F]:
      def present = false.pure[F]
      def set(on: Boolean) = ().pure[F]

  /** What the loop reads and does.
    *
    * @param reading
    *   The conditions now (peers, selection, the Honest Availability Assumption).
    * @param publish
    *   The judgement changed (also once at the start).
    * @param initial
    *   The state the machine starts in.
    * @param emit
    *   A transition of the machine, for the log.
    * @param poll
    *   How often the conditions are read.
    */
  final case class Env[F[_]](
      reading: F[Gsm.Reading],
      marker: Marker[F],
      publish: LedgerStateJudgement => F[Unit],
      initial: Gsm.Phase => F[Unit],
      emit: (Gsm.Phase, Gsm.Event) => F[Unit],
      config: Gsm.Config = Gsm.Config(),
      poll: FiniteDuration = 1.second,
      seed: Long
  )

  def run[F[_]: Temporal](env: Env[F]): F[Nothing] =
    val F = Temporal[F]
    def now: F[Time] = Clock[F].monotonic
    for
      first <- env.reading
      present <- env.marker.present
      at <- now
      initial = Gsm.initial(present, first.untilTooOld, at, env.seed, env.config)
      // a mark that is no longer true (the tip is old) is removed, as the reference does
      _ <- env.marker.set(false).whenA(present && initial.phase != Gsm.Phase.CaughtUp)
      _ <- env.initial(initial.phase)
      _ <- env.publish(Gsm.judgement(initial.phase))
      done <- F.tailRecM[Gsm.State, Nothing](initial) { state =>
        F.sleep(env.poll) *> env.reading.flatMap { reading =>
          now.flatMap { at =>
            val result = Gsm.step(state, reading, at, env.config)
            for
              _ <- result.mark.traverse_(env.marker.set)
              _ <- result.events.traverse_(env.emit(result.state.phase, _))
              _ <- env
                .publish(Gsm.judgement(result.state.phase))
                .whenA(Gsm.judgement(result.state.phase) != Gsm.judgement(state.phase))
            yield Left(result.state)
          }
        }
      }
    yield done
