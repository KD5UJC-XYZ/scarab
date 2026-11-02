// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Real blocks for comparing and timing [[Cbor]]'s decoder (issue 650), the same corpus as the
  * protocol reader's (`WireCorpus` in the protocol tests):
  *
  *   - the 648 blocks of the Haskell node's Preview immutable chunks
  *     (`fixtures/haskell-immutable`), split with [[Cbor.decodePrefix]];
  *   - the 70 first preprod blocks (`fixtures/preprod/blocks.hex`, `[era, block]`);
  *   - the mainnet block fixtures (the `.hex` files of `fixtures/mainnet`).
  */
object CborCorpus:
  /** Limits a block is decoded with: the default ones, wide enough for any block. */
  val Blocks: Cbor.Limits = Cbor.Limits(maxInputBytes = 2500000, maxItems = 2500000)

  private def root: Path =
    // the tests of a module run in its directory under sbt, at the root when forked by hand
    Iterator(Path.of("."), Path.of(".."))
      .find(p => Files.isDirectory(p.resolve("fixtures")))
      .getOrElse(Path.of("."))
      .resolve("fixtures")

  private def hexLines(path: Path): Vector[Bytes] =
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(line => Bytes.fromHex(line).fold(e => sys.error(s"$path: $e"), identity))

  private def files(dir: Path, suffix: String): Vector[Path] =
    Using
      .resource(Files.list(dir))(_.iterator.asScala.filter(_.toString.endsWith(suffix)).toVector)
      .sorted

  /** The items of `chunk`, one after another. */
  private def items(chunk: Bytes): Vector[Bytes] =
    Iterator
      .unfold(0) { offset =>
        Option.when(offset < chunk.size) {
          Cbor.decodePrefix(chunk.drop(offset), Blocks) match
            case Right(node) => (node.original, offset + node.original.size)
            case Left(error) => sys.error(s"chunk at $offset: $error")
        }
      }
      .toVector

  lazy val preview: Vector[Bytes] =
    files(root.resolve("haskell-immutable/preview/immutable"), ".chunk")
      .flatMap(p => items(Bytes.wrap(Files.readAllBytes(p))))

  lazy val preprod: Vector[Bytes] = hexLines(root.resolve("preprod/blocks.hex"))

  lazy val mainnet: Vector[Bytes] = files(root.resolve("mainnet"), ".hex").flatMap(hexLines)

  lazy val all: Vector[Bytes] = preview ++ preprod ++ mainnet
