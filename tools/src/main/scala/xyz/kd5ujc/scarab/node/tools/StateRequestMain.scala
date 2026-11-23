// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import java.lang.management.{ManagementFactory, MemoryType}
import java.nio.file.Path
import org.http4s.{Method, Request, Response}
import org.http4s.implicits.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.node.{ChainView, NativeSnapshots, NodeSetup, QueryServer, SharedAnswers}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{ChainSync, ReferenceBuild}

/** `GET /state` on a native snapshot of the node's state, through the HTTP query server's routes as
  * the node serves them (#1150): what an answer costs to build and to keep. Run by hand in the JDK
  * container, with the heap settings of the node it measures:
  *
  * {{{
  * java -Xms8g -Xmx24g -XX:+UseG1GC -cp <tools classpath> xyz.kd5ujc.scarab.node.tools.StateRequestMain <snapshot> <genesis dir> kept|shared|unshared [requests]
  * }}}
  *
  * The snapshot is any version the node reads, brought to the current one in memory. It prints one
  * JSON line per step, a heap being the bytes in use after a full collection, the state reachable:
  *   - every mode: `loaded`, the heap;
  *   - `kept`: `built`, the first request at the tip, which builds the answer (its time and the
  *     body's bytes); `kept`, the heap while the answer is kept; `again`, a second request at the
  *     same tip; `dropped`, the heap once the tip has moved;
  *   - `shared`: `requests` requests at once at the tip, with its answer not built yet: their time,
  *     the builds run, and the sum of the heap pools' peaks since the requests began, an upper
  *     bound of the peak (the pools peak at different times; `-Xlog:gc` gives the heap at each
  *     collection);
  *   - `unshared`: as `shared`, each request with answers of its own, so that each builds, as every
  *     request built before #1150.
  */
object StateRequestMain extends IOApp:
  private type Answers = SharedAnswers[IO, ChainSync.Point, QueryServer.StateKey, Response[IO]]

  private val heap: IO[Long] =
    IO.blocking(System.gc()) *> IO.delay(
      ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed
    )

  private val heapPools = IO.delay(
    ManagementFactory.getMemoryPoolMXBeans.asScala.toVector.filter(_.getType == MemoryType.HEAP)
  )

  private val resetPeak: IO[Unit] =
    heapPools.flatMap(_.traverse_(p => IO.delay(p.resetPeakUsage())))

  private val peak: IO[Long] =
    heapPools.flatMap(_.traverse(p => IO.delay(p.getPeakUsage.getUsed)).map(_.sum))

  private def line(step: String, fields: (String, Json)*): IO[Unit] =
    IO.println(Json.obj(("stateRequest" -> step.asJson) +: fields*).noSpaces)

  /** Answers that count the builds they run. */
  private def counting(inner: Answers, builds: Ref[IO, Int]): Answers = new Answers:
    def answer(key: QueryServer.StateKey)(build: => IO[Response[IO]]) =
      inner.answer(key)(builds.update(_ + 1) *> build)
    def moved(point: ChainSync.Point) = inner.moved(point)

  def run(args: List[String]): IO[ExitCode] = args match
    case snapshot :: genesis :: mode :: rest =>
      NodeSetup.fromDirectory(ReferenceBuild.Default)(Path.of(genesis)) match
        case Left(error) => IO.println(error.message).as(ExitCode(1))
        case Right(setup) =>
          NativeSnapshots.read[IO](Path.of(snapshot), setup.mirrorEnv()).flatMap {
            case Left(error) => IO.println(error.message).as(ExitCode(1))
            case Right(read) =>
              val here = read.anchor.fold(ChainSync.Point.Origin)(_.point)
              val requests = rest.headOption.flatMap(_.toIntOption).getOrElse(4)
              measure(mode, here, read.state, requests)
          }
    case _ =>
      IO.println("arguments: <snapshot> <genesis dir> kept|shared|unshared [requests]")
        .as(ExitCode(2))

  private def measure(
      mode: String,
      here: ChainSync.Point,
      state: MirrorState,
      requests: Int
  ): IO[ExitCode] =
    val view = new ChainView[IO, MirrorState]:
      def tip = IO.pure(here -> state)
      def at(point: ChainSync.Point) = IO.pure(Option.when(point == here)(state))
    def ask(answers: Answers): IO[Long] =
      QueryServer
        .routes(view, answers)(_ => IO.unit)
        .orNotFound
        .run(Request[IO](Method.GET, uri"/state"))
        .flatMap(_.body.compile.count)
    def fresh(builds: Ref[IO, Int]): IO[Answers] =
      SharedAnswers
        .of[IO, ChainSync.Point, QueryServer.StateKey, Response[IO]](_.point)
        .flatTap(_.moved(here))
        .map(counting(_, builds))
    for
      loaded <- heap
      _ <- line(
        "loaded",
        "point" -> xyz.kd5ujc.scarab.node.Projection.point(here),
        "heap" -> loaded.asJson
      )
      builds <- Ref.of[IO, Int](0)
      code <- mode match
        case "kept" =>
          for
            answers <- fresh(builds)
            (first, bytes) <- ask(answers).timed
            _ <- line("built", "millis" -> first.toMillis.asJson, "bodyBytes" -> bytes.asJson)
            kept <- heap
            _ <- line("kept", "heap" -> kept.asJson, "overLoaded" -> (kept - loaded).asJson)
            (second, _) <- ask(answers).timed
            _ <- line("again", "millis" -> second.toMillis.asJson)
            _ <- answers.moved(ChainSync.Point.Origin)
            dropped <- heap
            // the state is read once more, so that it stays reachable through the collection above
            entries <- view.tip.map(_._2.utxo.count)
            _ <- line(
              "dropped",
              "heap" -> dropped.asJson,
              "overLoaded" -> (dropped - loaded).asJson,
              "utxoEntries" -> entries.asJson
            )
            total <- builds.get
            _ <- line("builds", "count" -> total.asJson)
          yield ExitCode.Success
        case "shared" | "unshared" =>
          for
            shared <- fresh(builds)
            _ <- resetPeak
            (took, _) <- (1 to requests).toVector
              .parTraverse(_ =>
                if mode == "shared" then ask(shared) else fresh(builds).flatMap(ask)
              )
              .timed
            highest <- peak
            total <- builds.get
            _ <- line(
              mode,
              "requests" -> requests.asJson,
              "millis" -> took.toMillis.asJson,
              "builds" -> total.asJson,
              "poolPeaksSum" -> highest.asJson,
              "poolPeaksSumOverLoaded" -> (highest - loaded).asJson
            )
          yield ExitCode.Success
        case other => line("unknown mode", "mode" -> other.asJson).as(ExitCode(2))
    yield code
