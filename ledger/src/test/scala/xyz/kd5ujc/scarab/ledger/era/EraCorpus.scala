// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Block

/** The recorded blocks the era suites read. Each line of a fixture is a block's hex (after any
  * label); a Byron block (`[0, …]` or `[1, …]`) is not one the era types read and is left out by
  * its tag, and every other line must decode as a Shelley-based block, so a regression that loses
  * one fails at its file and line rather than shrinking the corpus (CNF-2).
  */
object EraCorpus:
  def shelleyBlocks(files: Vector[String])(using loc: munit.Location): Vector[Block.Shelley] =
    files.flatMap { f =>
      Files
        .readString(Path.of(f))
        .linesIterator
        .zipWithIndex
        .map((l, i) => (s"$f:${i + 1}", l.trim.split("\\s+").last))
        .filter((_, hex) => hex.nonEmpty && !hex.startsWith("8200") && !hex.startsWith("8201"))
        .map { (at, hex) =>
          val raw = Bytes.fromHex(hex).fold(e => munit.Assertions.fail(s"$at: $e"), identity)
          Block.decodeShelley(raw) match
            case Right(b: Block.Shelley) => b
            case other => munit.Assertions.fail(s"$at: not a Shelley-based block: $other")
        }
    }
