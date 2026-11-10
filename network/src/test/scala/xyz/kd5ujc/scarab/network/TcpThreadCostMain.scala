// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, IOApp, Ref, Resource}
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
  * sample is taken once every handler holds its connection and, with readers, once every reader is
  * blocked in its read (from the JVM's thread dump). With `hold` as its second argument, the
  * control, each handler holds its connection without reading it, so the difference between the two
  * runs is what the readers cost.
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

  /** The threads blocked in a socket channel's read: the connections' readers. */
  private def readers: IO[Int] =
    IO(
      ManagementFactory.getThreadMXBean
        .dumpAllThreads(false, false)
        .count(
          _.getStackTrace.exists(f =>
            f.getClassName == "sun.nio.ch.SocketChannelImpl" && f.getMethodName == "read"
          )
        )
    )

  private def sample: IO[Sample] =
    (threads, rssKb, stacksKb).mapN(Sample(_, _, _))

  private def clients(at: java.net.InetSocketAddress, n: Int): Resource[IO, List[SocketChannel]] =
    List
      .fill(n)(Resource.make(IO.blocking(SocketChannel.open(at)))(c => IO.blocking(c.close())))
      .sequence

  def run(args: List[String]): IO[cats.effect.ExitCode] =
    val connections = args.headOption.flatMap(_.toIntOption).getOrElse(512)
    val reading = !args.drop(1).headOption.contains("hold")
    (Ref.of[IO, Int](0), Deferred[IO, Unit]).tupled.flatMap { (serving, all) =>
      // each handler counts itself once its connection is held; the last one opens the latch
      val counted =
        serving.updateAndGet(_ + 1).flatMap(n => all.complete(()).whenA(n == connections))
      val served = BearerServer
        .listen[IO, NetworkError](Listeners.Loopback, maxConnections = None)((_, _) => IO.unit) {
          (bearer, _) =>
            if reading then
              Mux.resource[IO](bearer, Set.empty).use(mux => counted *> mux.closed).void
            else counted *> IO.never[Unit]
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
                // with readers, the sample waits until every one of them is blocked in its read
                all.get.timeout(60.seconds) *>
                  readers.iterateUntil(_ >= connections).whenA(reading).timeout(60.seconds) *>
                  sample
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
