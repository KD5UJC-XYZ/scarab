// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Deferred, Ref}
import cats.data.NonEmptyVector
import cats.syntax.all.*

/** The application result handles read by PeerStateActions (ouroboros-network 2fb69829,
  * PeerSelection/PeerStateActions.hs:254–420). Each activation replaces its hot handles; retiring
  * them preserves their previous result for the exit policy. A completed old run cannot overwrite a
  * later activation.
  */
final class ProtocolResults[F[_]: Async, A] private (
    state: Ref[F, ProtocolResults.State[F, A]]
):
  import ProtocolResults.*
  private val F = Async[F]

  /** One immutable read and the notification that changes that read. */
  def snapshot: F[Snapshot[F, A]] =
    state.get.map(s => Snapshot(s.entries, s.controls, s.changed.get))

  /** Wake readers after a peer-status or control transition, using the same wait registration. */
  def notifyChange: F[Unit] = update(identity)

  private def update(f: State[F, A] => State[F, A]): F[Unit] =
    F.uncancelable { _ =>
      Deferred[F, Unit].flatMap { changed =>
        state.modify(s => (f(s).copy(changed = changed), s.changed)).flatMap(_.complete(())).void
      }
    }

  private def install(
      protocols: Vector[(Int, Temperature)],
      controls: Map[Temperature, Control],
      replace: Option[Temperature] = None
  ): F[BigInt] =
    F.uncancelable { _ =>
      Deferred[F, Unit].flatMap { changed =>
        state
          .modify { s =>
            val generation = s.generation + 1
            val retained = s.entries.filterNot((_, entry) => replace.contains(entry.temperature))
            val entries = protocols.foldLeft(retained) { case (all, (protocol, temperature)) =>
              all.updated(protocol, Entry(temperature, generation, Result.Running))
            }
            (
              s.copy(
                entries = entries,
                controls = s.controls ++ controls,
                generation = generation,
                changed = changed
              ),
              (generation, s.changed)
            )
          }
          .flatMap((generation, previous) => previous.complete(()).as(generation))
      }
    }

  private def run(protocol: Int, generation: BigInt): Run[F, A] = new Run[F, A]:
    def complete(result: Either[NetworkError, A]): F[Unit] = update(s =>
      s.entries.get(protocol) match
        case Some(entry) if entry.generation == generation && entry.result == Result.Running =>
          s.copy(entries =
            s.entries.updated(
              protocol,
              entry.copy(result = result.fold(Result.Errored(_), Result.Returned(_)))
            )
          )
        case _ => s
    )

  /** Install a protocol handle before its application begins. */
  def start(protocol: Int, temperature: Temperature): F[Run[F, A]] =
    install(Vector(protocol -> temperature), Map(temperature -> Control.Continue))
      .map(run(protocol, _))

  /** Replace the whole hot bundle as one write, as `startProtocols` does (1164–1174). */
  def startHot(transactions: Boolean): F[Hot[F, A]] =
    val ids = Vector(Protocols.ChainSync, Protocols.BlockFetch) ++ Option.when(transactions)(
      Protocols.TxSubmission2
    )
    install(
      ids.map(_ -> Temperature.Hot),
      Map.empty,
      Some(Temperature.Hot)
    )
      .map(generation =>
        Hot(
          run(Protocols.ChainSync, generation),
          run(Protocols.BlockFetch, generation),
          Option.when(transactions)(run(Protocols.TxSubmission2, generation))
        )
      )

  def activateHot: F[Unit] = update(s =>
    s.copy(controls =
      s.controls ++ Map(Temperature.Hot -> Control.Continue, Temperature.Warm -> Control.Quiesce)
    )
  )

  def control(temperature: Temperature): F[Control] = state.get.map(_.controls(temperature))

  def awaitTermination(temperature: Temperature): F[Unit] = F.tailRecM(()) { _ =>
    snapshot.flatMap { current =>
      if current.controls(temperature) == Control.Terminate then F.pure(Right(()))
      else current.changed.as(Left(()))
    }
  }

  def terminateHot: F[Unit] = update(s =>
    s.copy(controls =
      s.controls ++ Map(Temperature.Hot -> Control.Terminate, Temperature.Warm -> Control.Continue)
    )
  )

  def terminateAll: F[Unit] =
    update(s => s.copy(controls = Temperature.values.map(_ -> Control.Terminate).toMap))

  /** Retain the bearer failure before releasing protocol resources. Already returned values and
    * inactive handles keep their results; the mux API currently exposes one terminal cause.
    */
  def abortRunning(error: NetworkError): F[Unit] = update(s =>
    s.copy(entries = s.entries.map { (id, entry) =>
      id -> (if entry.result == Result.Running then entry.copy(result = Result.Errored(error))
             else entry)
    })
  )

  /** `awaitAllResults`: running protocols block; protocols never started contribute no result. */
  def awaitAll(temperatures: Set[Temperature]): F[LastToFinish[A]] =
    F.tailRecM(()) { _ =>
      snapshot.flatMap { s =>
        val entries = s.entries.toVector.filter((_, e) => temperatures(e.temperature)).sortBy(_._1)
        if entries.exists(_._2.result == Result.Running) then s.changed.as(Left(()))
        else
          val values = entries.flatMap { (id, entry) =>
            entry.result match
              case Result.Returned(value)             => Vector(id -> Right(value))
              case Result.Errored(error)              => Vector(id -> Left(error))
              case Result.NotRunning(result)          => Vector(id -> result)
              case Result.NotStarted | Result.Running => Vector.empty
          }
          val errors = values.collect { case (id, Left(error)) => MiniProtocolFailure(id, error) }
          val result = NonEmptyVector.fromVector(errors) match
            case Some(failures) => LastToFinish.SomeErrored(failures)
            case None =>
              LastToFinish.AllSucceeded(values.collect { case (id, Right(value)) =>
                id -> value
              }.toMap)
          F.pure(Right(result))
      }
    }

  /** After a successful hot deactivation, retain outcomes without triggering first-to-finish. */
  def retireHot: F[Unit] = update(s =>
    s.copy(entries = s.entries.map { (id, entry) =>
      val result = entry.result match
        case Result.Returned(value) if entry.temperature == Temperature.Hot =>
          Result.NotRunning(Right(value))
        case Result.Errored(error) if entry.temperature == Temperature.Hot =>
          Result.NotRunning(Left(error))
        case other => other
      id -> entry.copy(result = result)
    })
  )

object ProtocolResults:
  /** Bundle order is the left-biased STM order of `peerMonitoringLoop`. */
  enum Temperature:
    case Established, Warm, Hot

  enum Control:
    case Continue, Quiesce, Terminate

  enum Result[+A]:
    case NotStarted
    case Running
    case Returned(value: A)
    case Errored(error: NetworkError)
    case NotRunning(value: Either[NetworkError, A])

  final case class Entry[+A](temperature: Temperature, generation: BigInt, result: Result[A])

  final case class MiniProtocolFailure(protocol: Int, error: NetworkError)

  enum LastToFinish[+A]:
    case AllSucceeded(values: Map[Int, A])
    case SomeErrored(failures: NonEmptyVector[MiniProtocolFailure])

  trait Run[F[_], A]:
    def complete(result: Either[NetworkError, A]): F[Unit]

  final case class Hot[F[_], A](
      chainSync: Run[F, A],
      blockFetch: Run[F, A],
      transactions: Option[Run[F, A]]
  )

  final case class Snapshot[F[_], A](
      entries: Map[Int, Entry[A]],
      controls: Map[Temperature, Control],
      changed: F[Unit]
  ):
    /** Match the placement of `retry` in awaitFirstResult (366–379): choose the first readable
      * handle in protocol-number order, then retry the entire temperature if that handle is
      * NotRunning or NotStarted. In particular it must not skip an inactive lower-number handle to
      * observe a higher-number completion in that temperature.
      */
    def first: Option[(Int, Temperature, Either[NetworkError, A])] =
      Temperature.values.toVector.flatMap { temperature =>
        entries.toVector
          .filter(_._2.temperature == temperature)
          .sortBy(_._1)
          .find(_._2.result != Result.Running)
          .flatMap {
            case (id, Entry(_, _, Result.Returned(value))) => Some((id, temperature, Right(value)))
            case (id, Entry(_, _, Result.Errored(error)))  => Some((id, temperature, Left(error)))
            case _                                         => None
          }
      }.headOption

  private final case class State[F[_], A](
      entries: Map[Int, Entry[A]],
      controls: Map[Temperature, Control],
      generation: BigInt,
      changed: Deferred[F, Unit]
  )

  def create[F[_]: Async, A]: F[ProtocolResults[F, A]] =
    Deferred[F, Unit].flatMap(changed =>
      Ref
        .of[F, State[F, A]](
          State(
            Vector(Protocols.ChainSync, Protocols.BlockFetch)
              .map(_ -> Entry[A](Temperature.Hot, 0, Result.NotStarted))
              .toMap,
            Map(
              Temperature.Hot -> Control.Terminate,
              Temperature.Warm -> Control.Continue,
              Temperature.Established -> Control.Continue
            ),
            BigInt(0),
            changed
          )
        )
        .map(new ProtocolResults(_))
    )
