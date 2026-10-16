// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.node.Projection

/** The K1 projection (`Projection.node`, the JSON the state digest is made from) of every state of
  * the era-walking capture, as the SHA-256 of its text: `fixtures/projection-golden/eras.tsv`, one
  * line per state, `index TAB era TAB block number TAB sha256`. Recorded from the projection
  * as it was before it was written over the read paths of the mirror (J2.7); `ProjectionSuite`
  * requires the same text from the flat state, whose digest on Preview must not change.
  */
object ProjectionGolden:
  val file: Path = Path.of("fixtures/projection-golden/eras.tsv")

  def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  def lines: Vector[String] =
    ErasStates.samples.zipWithIndex.map { (s, i) =>
      val text = Projection.node(s.anchor.point, s.state).noSpaces
      s"$i\t${s.era}\t${s.block.header.blockNo}\t${sha256(text)}"
    }

  def main(args: Array[String]): Unit =
    Files.createDirectories(file.getParent)
    Files.writeString(file, lines.mkString("", "\n", "\n"))
    println(s"wrote ${lines.size} lines to $file")
