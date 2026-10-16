// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.Monad
import cats.effect.{Ref, Sync}
import cats.mtl.Stateful
import cats.syntax.all.*
import java.lang.management.ManagementFactory
import scala.jdk.CollectionConverters.*
import scala.util.Try
import xyz.kd5ujc.scarab.consensus.{SchedulingStats, VolatileChain}
import xyz.kd5ujc.scarab.ledger.EpochGeometry
import xyz.kd5ujc.scarab.network.ConnectionManager
import xyz.kd5ujc.scarab.node.forge.ForgeStats
import xyz.kd5ujc.scarab.node.mempool.Mempool

/** What is measured when it is asked for, not when it happens: the state that already lives
  * somewhere (the chain, the mempool, the connection table, the forger's counters, the JVM). Each
  * collector reads its source and writes the metrics; a scrape runs them all, then renders. They
  * stay consistent with the events by construction: the same source answers `/tip`, `/connections`,
  * `/scheduling` and `/forging`.
  */
object Collectors:
  /** The collectors a scrape runs. The endpoint is up before the sources exist (the stores are
    * still being opened and replayed), so a collector is added when its source is: until then its
    * gauges are absent, which is not the same as zero. A collector that fails leaves its gauges as
    * they were and does not fail the scrape.
    */
  final class Registry[F[_]: Sync] private (ready: Ref[F, Vector[F[Unit]]]):
    def add(collector: F[Unit]): F[Unit] = ready.update(_ :+ collector.handleError(_ => ()))

    /** Run what is registered so far, then write the registry in the text format. */
    def scrape(using metrics: Metrics[F]): F[String] =
      ready.get.flatMap(collectors => metrics.scrape(collectors.toList))

  object Registry:
    def create[F[_]: Sync]: F[Registry[F]] =
      Ref.of[F, Vector[F[Unit]]](Vector.empty).map(new Registry(_))

  /** The tip of the chain, as the node sees it now. `geometryOf` is the epoch geometry a state
    * knows (the one of the genesis files, refined once the chain has entered Shelley, which is what
    * `NodeState.geometry` gives).
    */
  def chain[F[_]: Monad, S](geometryOf: S => EpochGeometry)(using
      state: Stateful[F, VolatileChain[S]],
      metrics: Metrics[F]
  ): F[Unit] =
    import Catalogue.*
    state.get.flatMap { volatile =>
      val geometry = geometryOf(volatile.state)
      val tip = volatile.tipRef
      val slot = tip.fold(BigInt(0))(_.slot)
      val epochNo = geometry.epochOf(slot)
      // the blocks of the volatile chain per slot, from its anchor (the immutable tip) to the tip
      val anchor =
        volatile.anchor.block.map(_.slot).orElse(volatile.blocks.headOption.map(_._1.slot))
      val chainDensity = (anchor, tip) match
        case (Some(from), Some(to)) if to.slot > from =>
          volatile.blocks.size.toDouble / (to.slot - from).toDouble
        case _ => 0.0
      metrics.set(blockNum, tip.fold(0.0)(_.blockNo.toDouble)) *>
        metrics.set(slotNum, slot.toDouble) *>
        metrics.set(epoch, epochNo.toDouble) *>
        metrics.set(slotInEpoch, (slot - geometry.firstSlot(epochNo)).toDouble) *>
        metrics.set(density, chainDensity) *>
        metrics.set(volatileBlocks, volatile.blocks.size.toDouble)
    }

  def mempool[F[_]: Monad](mempool: Mempool[F])(using metrics: Metrics[F]): F[Unit] =
    import Catalogue.*
    mempool.snapshot.flatMap(s =>
      metrics.set(txsInMempool, s.entries.size.toDouble) *>
        metrics.set(mempoolBytes, s.bytes.toDouble)
    )

  /** The connection table: the three counts the Haskell node's connection manager reports, and the
    * connections by state.
    */
  def connections[F[_]: Monad](manager: ConnectionManager[F])(using metrics: Metrics[F]): F[Unit] =
    import Catalogue.*
    manager.snapshot.flatMap { s =>
      val byState =
        s.connections.groupMapReduce((_, state) => ConnectionStateLabel.of(state))(_ => 1)(_ + _)
      metrics.set(inboundConns, s.counters.inbound.toDouble) *>
        metrics.set(outboundConns, s.counters.outbound.toDouble) *>
        metrics.set(duplexConns, s.counters.duplex.toDouble) *>
        ConnectionStateLabel.values.toList.traverse_(label =>
          metrics.set(connectionsByState, label, byState.getOrElse(label, 0).toDouble)
        )
    }

  /** The whole-run scheduling measurements (D8), as `/scheduling` serves them. */
  def scheduling[F[_]: Monad](stats: SchedulingStats[F])(using metrics: Metrics[F]): F[Unit] =
    import Catalogue.*
    stats.total.flatMap { r =>
      metrics.advance(timerSamples, r.samples.toDouble) *>
        metrics.set(timerLatenessMax, r.maxTimerLatenessMs / 1000.0) *>
        metrics.advance(timersLate, LatenessLabel.Over50Ms, r.timersLateOver50.toDouble) *>
        metrics.advance(timersLate, LatenessLabel.Over100Ms, r.timersLateOver100.toDouble) *>
        metrics.advance(cpuSteps, r.stepCount.toDouble) *>
        metrics.set(cpuLongestStep, r.longest.fold(0.0)(_._2.maxMs / 1000.0))
    }

  /** The forger's counters for the whole run (the same ones `/forging` serves). */
  def forging[F[_]: Monad](stats: ForgeStats[F])(using metrics: Metrics[F]): F[Unit] =
    import Catalogue.*
    stats.total.flatMap { t =>
      val skipped =
        t.skipped.toList.groupMapReduce((reason, _) => SkipLabel.parse(reason))(_._2)(_ + _)
      metrics.advance(forgeAboutToLead, t.slotsChecked.toDouble) *>
        metrics.advance(forgeNodeIsLeader, t.leaderSlots.toDouble) *>
        metrics.advance(forgeNodeNotLeader, (t.slotsChecked - t.leaderSlots).toDouble) *>
        metrics.advance(forgeForged, t.forged.toDouble) *>
        metrics.advance(forgeAdopted, t.adopted.toDouble) *>
        metrics.advance(forgeDidntAdopt, t.lost.toDouble) *>
        metrics.set(blocksForged, t.forged.toDouble) *>
        metrics.set(nodeIsLeader, t.leaderSlots.toDouble) *>
        metrics.set(nodeCannotForge, t.failed.toDouble) *>
        metrics.set(slotsMissed, skipped.getOrElse(SkipLabel.Busy, 0L).toDouble) *>
        SkipLabel.values.toList.traverse_(label =>
          metrics.advance(forgeSkipped, label, skipped.getOrElse(label, 0L).toDouble)
        )
    }

  /** The JVM and the process, after the Prometheus JVM client's names. */
  def jvm[F[_]: Sync](using metrics: Metrics[F]): F[Unit] =
    import Catalogue.*
    Sync[F].blocking(Jvm.sample).flatMap { s =>
      metrics.set(jvmMemoryUsed, AreaLabel.Heap, s.heap.getUsed.toDouble) *>
        metrics.set(jvmMemoryUsed, AreaLabel.NonHeap, s.nonHeap.getUsed.toDouble) *>
        metrics.set(jvmMemoryCommitted, AreaLabel.Heap, s.heap.getCommitted.toDouble) *>
        metrics.set(jvmMemoryCommitted, AreaLabel.NonHeap, s.nonHeap.getCommitted.toDouble) *>
        metrics.set(jvmMemoryMax, AreaLabel.Heap, s.heap.getMax.toDouble) *>
        metrics.set(jvmMemoryMax, AreaLabel.NonHeap, s.nonHeap.getMax.toDouble) *>
        s.collectors.traverse_((name, count, millis) =>
          metrics.advance(jvmGcCollections, name, count.toDouble) *>
            metrics.advance(jvmGcSeconds, name, millis / 1000.0)
        ) *>
        metrics.set(jvmThreads, s.threads.toDouble) *>
        metrics.set(jvmClasses, s.classes.toDouble) *>
        metrics.advance(processCpuSeconds, s.cpuNanos / 1e9) *>
        s.residentBytes.traverse_(metrics.set(processResident, _)) *>
        s.openFds.traverse_(metrics.set(processOpenFds, _)) *>
        metrics.set(processStart, s.startMillis / 1000.0) *>
        metrics.set(nodeStartTime, (s.startMillis / 1000).toDouble)
    }

/** One reading of the JVM's management beans and of `/proc`. */
private[metrics] object Jvm:
  final case class Sample(
      heap: java.lang.management.MemoryUsage,
      nonHeap: java.lang.management.MemoryUsage,
      /** Name, collections, milliseconds spent. */
      collectors: List[(String, Long, Long)],
      threads: Int,
      classes: Int,
      cpuNanos: Long,
      residentBytes: Option[Double],
      openFds: Option[Double],
      startMillis: Long
  )

  def sample: Sample =
    val memory = ManagementFactory.getMemoryMXBean
    val os = ManagementFactory.getOperatingSystemMXBean
    Sample(
      memory.getHeapMemoryUsage,
      memory.getNonHeapMemoryUsage,
      ManagementFactory.getGarbageCollectorMXBeans.asScala.toList
        .map(g => (g.getName, g.getCollectionCount.max(0L), g.getCollectionTime.max(0L))),
      ManagementFactory.getThreadMXBean.getThreadCount,
      ManagementFactory.getClassLoadingMXBean.getLoadedClassCount,
      os match
        case s: com.sun.management.OperatingSystemMXBean => s.getProcessCpuTime.max(0L)
        case _                                           => 0L
      ,
      residentBytes,
      os match
        case u: com.sun.management.UnixOperatingSystemMXBean =>
          Some(u.getOpenFileDescriptorCount.toDouble)
        case _ => None
      ,
      ManagementFactory.getRuntimeMXBean.getStartTime
    )

  /** `VmRSS` of `/proc/self/status`, in bytes; none where there is no such file. */
  private def residentBytes: Option[Double] =
    // a missing or unreadable file is "not available here", not a failure of the scrape
    Try(java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status"))).toOption
      .flatMap(
        _.asScala
          .collectFirst { case line if line.startsWith("VmRSS:") => line }
          .flatMap(_.split("\\s+").lift(1))
          .flatMap(_.toLongOption)
          .map(_ * 1024.0)
      )
