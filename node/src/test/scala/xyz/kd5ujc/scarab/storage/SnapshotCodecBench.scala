// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import munit.Assertions
import xyz.kd5ujc.scarab.node.NodeSetup

/** Full native snapshot codec benchmark; I/O, verification, hashing and structural comparisons are
  * outside timing. A legacy-derived input uses the maintained authenticated V5 conversion.
  */
object SnapshotCodecBench extends Assertions:
  private val bean = ManagementFactory.getThreadMXBean match
    case b: com.sun.management.ThreadMXBean => b
    case _                                  => fail("allocation counter unavailable")
  private def checked[E, A](result: Either[E, A]): A =
    result.fold(e => fail(s"fixture refused: $e"), identity)
  private def sha(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString
  private def measure[A](label: String)(body: => A)(verify: A => Unit): A =
    var result = body
    verify(result)
    (0 until 10).foreach { _ =>
      result = body; verify(result)
    }
    val rows = (0 until 9).map { sample =>
      val allocation = bean.getCurrentThreadAllocatedBytes
      val start = System.nanoTime()
      result = body
      val elapsed = System.nanoTime() - start
      val bytes = bean.getCurrentThreadAllocatedBytes - allocation
      verify(result)
      println(s"SAMPLE $label sample=$sample ns=$elapsed allocated=$bytes")
      (elapsed, bytes)
    }
    val ns = rows.map(_._1).sorted
    val bytes = rows.map(_._2).sorted
    println(
      s"METRIC $label median_ns=${ns(4)} min_ns=${ns.head} max_ns=${ns.last} allocated=${bytes(4)}"
    )
    result

  def main(args: Array[String]): Unit =
    assert(args.length == 3, "expected label, snapshot path, config directory")
    val label = args(0)
    val raw = Files.readAllBytes(Path.of(args(1)))
    val content = checked(
      FileLedgerSnapshots.verified(raw).flatMap(xyz.kd5ujc.scarab.node.NativeSnapshots.current)
    )
    val env = checked(NodeSetup.fromDirectory(Path.of(args(2)))).mirrorEnv()
    println(s"INPUT $label bytes=${raw.length} sha256=${sha(raw)}")
    val initial = checked(SnapshotV7.decode(env, content))
    println(s"INPUT_STATE $label ${sha(initial.toString.getBytes("UTF-8"))}")
    val expected = checked(SnapshotV7.encoded(initial))
    val encoded = measure(s"$label.encode")(SnapshotV7.encoded(initial)) { result =>
      assert(java.util.Arrays.equals(checked(result), expected))
    }
    println(s"DIGEST $label.encode bytes=${expected.length} sha256=${sha(checked(encoded))}")
    val decoded = measure(s"$label.decode")(SnapshotV7.decode(env, content)) { result =>
      assertEquals(checked(result), initial)
    }
    println(s"DIGEST $label.decode ${sha(checked(decoded).toString.getBytes("UTF-8"))}")
    val truncated = SnapshotV7.decode(env, content.dropRight(1))
    assert(truncated.isLeft)
    println(s"DIGEST $label.truncated ${sha(truncated.toString.getBytes("UTF-8"))}")
