// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes

/** Real blocks for comparing and timing the CBOR walk of [[ProtocolWire.Reader]] (issue 657): the
  * BlockFetch payloads a node reads most of its bytes as.
  *
  *   - the 648 blocks of the Haskell node's Preview immutable chunks
  *     (`fixtures/haskell-immutable`), split with the current reader;
  *   - the 70 first preprod blocks (`fixtures/preprod/blocks.hex`, `[era, block]`);
  *   - the mainnet block fixtures (the `.hex` files of `fixtures/mainnet`).
  */
object WireCorpus:
  /** The limits of a BlockFetch `MsgBlock` while streaming (`BlockFetch.Limits.wire`). */
  val Streaming: ProtocolWire.Limits = ProtocolWire.Limits(2500000, 2499991, 64, 24, 8192)

  private def hexLines(path: Path): Vector[Bytes] =
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(line => Bytes.fromHex(line).fold(e => sys.error(s"$path: $e"), identity))

  /** The items of `chunk`, one after another, as the current reader splits them. */
  private def items(chunk: Bytes): Vector[Bytes] =
    Iterator
      .unfold(0) { offset =>
        Option.when(offset < chunk.size) {
          ProtocolWire.attempt {
            val reader = new ProtocolWire.Reader(chunk.drop(offset), Streaming)
            reader.item()
          } match
            case Right(item) => (item, offset + item.size)
            case Left(error) => sys.error(s"chunk at $offset: $error")
        }
      }
      .toVector

  lazy val preview: Vector[Bytes] =
    Files
      .list(Path.of("fixtures/haskell-immutable/preview/immutable"))
      .iterator
      .asScala
      .filter(_.toString.endsWith(".chunk"))
      .toVector
      .sorted
      .flatMap(p => items(Bytes.wrap(Files.readAllBytes(p))))

  lazy val preprod: Vector[Bytes] = hexLines(Path.of("fixtures/preprod/blocks.hex"))

  lazy val mainnet: Vector[Bytes] =
    Files
      .list(Path.of("fixtures/mainnet"))
      .iterator
      .asScala
      .filter(_.toString.endsWith(".hex"))
      .toVector
      .sorted
      .flatMap(hexLines)

  lazy val all: Vector[Bytes] = preview ++ preprod ++ mainnet
