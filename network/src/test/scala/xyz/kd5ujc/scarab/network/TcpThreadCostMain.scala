// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, IOApp, Ref, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import java.lang.management.ManagementFactory
import java.nio.channels.SocketChannel
import java.nio.file.{Files, Path}
import javax.management.ObjectName
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.network.Listeners.failing

/** What the blocking socket design costs at the hard limit of accepted connections (issue 854): the
  * threads, the resident memory and the committed thread stacks of a JVM whose listener holds
  * `connections` accepted connections (512, the reference's `acceptedConnectionsHardLimit`), each
  * with the node's multiplexer reading it, as `InboundGovernor.serve` runs one. A connection's
  * reader waits in a blocking read on the blocking pool, so each live connection holds one thread.
  *
  * The clients are plain sockets in the same JVM that never read, so they hold no thread; each
  * sample is taken once the thread count has stopped changing for a second. With `hold` as its
  * second argument, the control, each handler holds its connection without reading it, so the
  * difference between the two runs is what the readers cost.
  *
  * Not part of `check`: run it with `java -XX:NativeMemoryTracking=summary -XX:+AlwaysPreTouch -cp
  * <network test classpath> xyz.kd5ujc.scarab.network.TcpThreadCostMain [connections] [read|hold]`.
  * It prints a `HOST` line, a `SAMPLE` line before the connections and one with them, and a `DELTA`
  * line; the figures are recorded in `docs/performance/peers-and-network.md`.
  */
object TcpThreadCostMain extends IOApp:
  private final case class Sample(threads: Int, rssKb: Long, stacksKb: Option[Long]):
    def line(label: String): String =
      s"SAMPLE at=$label threads=$threads rss_kb=$rssKb thread_stacks_committed_kb=" +
        stacksKb.fold("n/a")(_.toString)

  private def rssKb: IO[Long] =
    IO.blocking(
      Files
        .readAllLines(Path.of("/proc/self/status"))
        .asScala
        .collectFirst { case l if l.startsWith("VmRSS:") => l.split("\\s+")(1).toLong }
        .getOrElse(-1L)
    )

  /** The committed size of the `Thread` category of native memory tracking, when it is on. */
  private def stacksKb: IO[Option[Long]] =
    IO.blocking {
      val summary = ManagementFactory.getPlatformMBeanServer
        .invoke(
          new ObjectName("com.sun.management:type=DiagnosticCommand"),
          "vmNativeMemory",
          Array[AnyRef](Array("summary")),
          Array("[Ljava.lang.String;")
        )
        .toString
      "-\\s+Thread \\(reserved=\\d+KB, committed=(\\d+)KB\\)".r
        .findFirstMatchIn(summary)
        .map(_.group(1).toLong)
    }.handleError(_ => None)

  private def threads: IO[Int] = IO(ManagementFactory.getThreadMXBean.getThreadCount)

  /** The thread count once it has not changed for a second (at most 30 s). */
  private def settled: IO[Int] =
    def loop(last: Int, stable: Int): IO[Int] =
      if stable >= 10 then IO.pure(last)
      else
        IO.sleep(100.millis) *> threads.flatMap(n => loop(n, if n == last then stable + 1 else 0))
    threads.flatMap(loop(_, 0)).timeout(30.seconds)

  private def sample: IO[Sample] =
    settled.flatMap(n => (rssKb, stacksKb).mapN(Sample(n, _, _)))

  private def clients(at: java.net.InetSocketAddress, n: Int): Resource[IO, List[SocketChannel]] =
    List
      .fill(n)(Resource.make(IO.blocking(SocketChannel.open(at)))(c => IO.blocking(c.close())))
      .sequence

  def run(args: List[String]): IO[cats.effect.ExitCode] =
    val connections = args.headOption.flatMap(_.toIntOption).getOrElse(512)
    val reading = !args.drop(1).headOption.contains("hold")
    Ref.of[IO, Int](0).flatMap { serving =>
      val served = BearerServer
        .listen[IO, NetworkError](Listeners.Loopback, maxConnections = None)((_, _) => IO.unit) {
          (bearer, _) =>
            val held =
              if reading then Mux.resource[IO](bearer, Set.empty).use(_.closed).void
              else IO.never[Unit]
            serving.update(_ + 1) *> held.guarantee(serving.update(_ - 1))
        }
      for
        _ <- IO.println(
          s"HOST processors=${Runtime.getRuntime.availableProcessors} " +
            s"java=${System.getProperty("java.runtime.version")} connections=$connections " +
            s"handlers=${if reading then "read" else "hold"}"
        )
        _ <- Handle
          .allow[NetworkError](served.use { listening =>
            for
              before <- sample
              _ <- IO.println(before.line("listening"))
              during <- clients(listening.address.toInetSocketAddress, connections).use { _ =>
                Wait.until(serving.get, 30.seconds)(_ == connections) *> sample
              }
              _ <- IO.println(during.line(s"$connections-connections"))
              _ <- IO.println(
                s"DELTA threads=${during.threads - before.threads} " +
                  s"rss_kb=${during.rssKb - before.rssKb} thread_stacks_committed_kb=" +
                  (during.stacksKb, before.stacksKb).mapN(_ - _).fold("n/a")(_.toString) +
                  s" per_connection_rss_kb=${(during.rssKb - before.rssKb) / connections}"
              )
            yield ()
          })
          .rescue(e => IO.println(s"FAILED $e"))
      yield cats.effect.ExitCode.Success
    }
