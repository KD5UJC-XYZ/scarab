// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import java.lang.management.ManagementFactory
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.Header
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** Bounded review measurement of the production forest and idle decision paths; no ledger timing or
  * whole-node throughput claim. Run with tools/runMain, inside the JDK image.
  */
object SelectionReviewBenchmark extends IOApp.Simple:
  private val local =
    ChainSelection.Local(Point.Origin, SelectView(-1, 0, None, 0, None), Vector.empty)
  private def hash(n: Int): Bytes = Bytes(Vector.tabulate(4)(i => (n >>> (i * 8)).toByte))
  private def forest(
      size: Int,
      forks: Boolean
  ): Either[ProcessedForest.Cycle, ProcessedForest[Header]] =
    ProcessedForest.of((1 to size).map { n =>
      Header
        .UnsizedByron(
          Bytes.empty,
          hash(n),
          false,
          0,
          n,
          21600,
          n,
          Option.when(!forks && n > 1)(hash(n - 1))
        )
        .withSizeHint(FetchTesting.sizeHint(1))
    })

  private def measureForest(size: Int, forks: Boolean, admission: Boolean): IO[Unit] =
    for
      headers <- IO.fromEither(
        forest(size, forks).leftMap(cycle => new AssertionError(cycle.toString))
      )
      decision = () =>
        CandidateSelection
          .project(
            2160,
            local,
            AvailableBlocks.candidates(
              local,
              headers,
              _ => false,
              Option.when(admission && size > 0)(Set(hash(size)))
            ),
            None
          )
          .size
      result <- IO.delay {
        val cpu = ManagementFactory.getThreadMXBean
        (1 to 20).foreach(_ => decision())
        val startCpu = cpu.getCurrentThreadCpuTime
        val start = System.nanoTime()
        val selected = (1 to 100).map(_ => decision()).sum
        (System.nanoTime() - start, cpu.getCurrentThreadCpuTime - startCpu, selected)
      }
      selected = result._3
      _ <- IO.println(
        s"forest size=$size forks=$forks admission=$admission iterations=100 elapsedNs=${result._1} cpuNs=${result._2} selected=$selected"
      )
    yield ()

  /** The common case of a sync: the block after the tip of a selected window of 2160 blocks, all in
    * a linear forest of `size` blocks, the window a fresh one each time, as a commit publishes one.
    */
  private def measureTip(size: Int): IO[Unit] =
    for
      headers <- IO.fromEither(
        forest(size, false).leftMap(cycle => new AssertionError(cycle.toString))
      )
      chain = (1 until size).toVector.flatMap(n => headers.get(hash(n)))
      window = chain.takeRight(2160)
      anchor <- IO.fromOption(chain.dropRight(2160).lastOption)(new AssertionError("no anchor"))
      selected = ChainSelection.Local(
        anchor.point,
        SelectView.of(anchor),
        window.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
      )
      decision = () =>
        val fresh = selected.copy()
        CandidateSelection
          .project(
            2160,
            fresh,
            AvailableBlocks.candidates(fresh, headers, _ => false, Some(Set(hash(size)))),
            None
          )
          .size
      result <- IO.delay {
        val cpu = ManagementFactory.getThreadMXBean
        (1 to 20).foreach(_ => decision())
        val startCpu = cpu.getCurrentThreadCpuTime
        val start = System.nanoTime()
        val selected = (1 to 100).map(_ => decision()).sum
        (System.nanoTime() - start, cpu.getCurrentThreadCpuTime - startCpu, selected)
      }
      _ <- IO.println(
        s"tip size=$size window=2160 iterations=100 elapsedNs=${result._1} cpuNs=${result._2} selected=${result._3}"
      )
    yield ()

  private def measureIdle(peers: Int): IO[Unit] =
    for
      coordinator <- FetchCoordinator.create[IO]()
      _ <- (0 until peers).toVector.traverse(i => coordinator.session(i.toLong)).use { _ =>
        val cpu = ManagementFactory.getOperatingSystemMXBean
          .asInstanceOf[com.sun.management.OperatingSystemMXBean]
        for
          _ <- coordinator.refreshGenesis.replicateA_(1000)
          beforeCpu <- IO.delay(cpu.getProcessCpuTime)
          before <- IO.monotonic
          _ <- coordinator.refreshGenesis.replicateA_(10000)
          after <- IO.monotonic
          afterCpu <- IO.delay(cpu.getProcessCpuTime)
          _ <- IO.println(
            s"idle peers=$peers iterations=10000 elapsedNs=${(after - before).toNanos} processCpuNs=${afterCpu - beforeCpu}"
          )
        yield ()
      }
    yield ()

  def run: IO[Unit] =
    Vector(0, 128, 1024, 4096).traverse_(size =>
      Vector(false, true).traverse_(forks =>
        Vector(false, true).traverse_(admission => measureForest(size, forks, admission))
      )
    ) *> Vector(2500, 4096).traverse_(measureTip) *> Vector(0, 32).traverse_(measureIdle)
