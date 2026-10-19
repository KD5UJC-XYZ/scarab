// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import io.circe.Json
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.Projection

/** The K1 projection (`Projection.node`, the JSON the state digest is made from) of every state of
  * the era-walking capture, as the SHA-256 of its text: `fixtures/projection-golden/eras.tsv`, one
  * line per state, `index TAB era TAB block number TAB sha256`. First recorded (J2.7) from the flat
  * state of the old rules; re-recorded at J2.9, when those rules were removed, from the mirror of
  * the era-indexed rules, which `ProjectionSuite` requires. The two recordings differ in two things
  * and nothing else (shown by laying the old recording's two differences over the new text, which
  * gave back every one of the old digests of the 681 Shelley-based states): the old flat state
  * carried the parameter fields of the Alonzo genesis that an era's `PParams` does not have in the
  * reference (in the eras before Alonzo, and `utxoCostPerWord` from Babbage), and it held a reward
  * update from the first block of the first Shelley epoch, where the reference makes it at `4k/f`
  * slots into the epoch. The digest on Preview did not change at J2.8.
  *
  * #135 records genuine pending reward work as rewardUpdate null in 19 rows; all other fields
  * restore the old digests when the already captured pending update is completed in the private
  * proof. The fixture README retains exact scope, hashes and provenance.
  *
  * The Byron states are in the file for the record: the projection then was of a placeholder ledger
  * state, which a chain in Byron does not have, and is now the consensus part alone.
  */
object ProjectionGolden:
  val file: Path = Path.of("fixtures/projection-golden/eras.tsv")

  def sha256(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  /** The projection of a sample, read from its mirror. */
  def ofMirror(s: ErasStates.Sample): Json =
    Projection
      .node(s.anchor.point, s.mirror, s.mirror.utxo)
      .fold(e => sys.error(e.toString), identity)

  /** The line of the file for a sample. */
  def line(i: Int, s: ErasStates.Sample): String =
    s"$i\t${s.era}\t${s.block.header.blockNo}\t${sha256(ofMirror(s).noSpaces)}"

  /** Writes the file from the mirror. Not a refresh: a change of the file is a change of the
    * digest, which needs its reasons.
    */
  def main(args: Array[String]): Unit =
    Files.createDirectories(file.getParent)
    val lines = ErasStates.samples.zipWithIndex.map((s, i) => line(i, s)).map(l => s"$l\n")
    Files.writeString(file, lines.mkString)
    println(s"wrote ${lines.size} lines to $file")
