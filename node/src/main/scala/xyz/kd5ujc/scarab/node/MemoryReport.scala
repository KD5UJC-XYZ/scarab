// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import java.lang.management.ManagementFactory
import scala.jdk.CollectionConverters.*

/** A sample of where the process's memory is (roadmap K6): the JVM's own accounting (heap,
  * non-heap, the direct and mapped buffer pools), the kernel's view of the process
  * (`/proc/self/status`: resident anonymous memory, resident file pages) and of the container
  * (cgroup), and whatever the stores report about their native memory (RocksDB table readers,
  * memtables, block cache). `jcmd <pid> VM.native_memory` and `GC.class_histogram` are asked of the
  * running JVM from outside.
  */
object MemoryReport:
  private val MiB = 1024.0 * 1024.0

  private def mib(bytes: Long): Json =
    BigDecimal(bytes / MiB).setScale(1, BigDecimal.RoundingMode.HALF_UP).asJson

  /** `Vm*` and `Rss*` lines of `/proc/self/status` in bytes (the file has kB); empty elsewhere. */
  private[node] def procStatus(text: String): Map[String, Long] =
    text.linesIterator.flatMap { l =>
      l.trim.split("\\s+").toList match
        case key :: value :: "kB" :: Nil if key.startsWith("Vm") || key.startsWith("Rss") =>
          value.toLongOption.map(v => key.stripSuffix(":") -> v * 1024)
        case _ => None
    }.toMap

  private def read(path: String): Option[String] =
    Either
      .catchOnly[Exception](java.nio.file.Files.readString(java.nio.file.Path.of(path)))
      .toOption

  /** cgroup v2 `memory.stat` and `memory.current`: what the container is charged, page cache too.
    */
  private def cgroup: Map[String, Long] =
    val wanted = Set("anon", "file", "kernel", "slab", "shmem", "file_mapped")
    val stat = read("/sys/fs/cgroup/memory.stat").toList.flatMap(_.linesIterator).flatMap { l =>
      l.split(" ").toList match
        case k :: v :: Nil if wanted(k) => v.toLongOption.map(k -> _)
        case _                          => None
    }
    val current =
      read("/sys/fs/cgroup/memory.current").flatMap(_.trim.toLongOption).map("current" -> _)
    (stat ++ current).toMap

  def sample(stores: IO[Map[String, Long]]): IO[Json] =
    stores.flatMap { extra =>
      IO.blocking {
        val mem = ManagementFactory.getMemoryMXBean
        val heap = mem.getHeapMemoryUsage
        val non = mem.getNonHeapMemoryUsage
        val buffers = ManagementFactory
          .getPlatformMXBeans(classOf[java.lang.management.BufferPoolMXBean])
          .iterator()
          .asScala
          .map(p =>
            p.getName -> Json.obj("count" -> p.getCount.asJson, "usedMiB" -> mib(p.getMemoryUsed))
          )
        val pools = ManagementFactory.getMemoryPoolMXBeans
          .iterator()
          .asScala
          .filter(p => p.getName.contains("Metaspace") || p.getName.contains("Class"))
          .map(p => p.getName -> mib(p.getUsage.getUsed))
        val gcs = ManagementFactory.getGarbageCollectorMXBeans
          .iterator()
          .asScala
          .map(g =>
            g.getName -> Json.obj(
              "count" -> g.getCollectionCount.asJson,
              "ms" -> g.getCollectionTime.asJson
            )
          )
        def sizes(m: Map[String, Long]) = Json.obj(m.toSeq.sortBy(_._1).map((k, v) => k -> mib(v))*)
        Json.obj(
          "heapUsedMiB" -> mib(heap.getUsed),
          "heapCommittedMiB" -> mib(heap.getCommitted),
          "nonHeapUsedMiB" -> mib(non.getUsed),
          "nonHeapCommittedMiB" -> mib(non.getCommitted),
          "memoryPoolsMiB" -> Json.obj(pools.toSeq*),
          "bufferPools" -> Json.obj(buffers.toSeq*),
          "threads" -> ManagementFactory.getThreadMXBean.getThreadCount.asJson,
          "procMiB" -> sizes(procStatus(read("/proc/self/status").getOrElse(""))),
          "cgroupMiB" -> sizes(cgroup),
          "gc" -> Json.obj(gcs.toSeq*),
          "storesMiB" -> sizes(extra)
        )
      }
    }
