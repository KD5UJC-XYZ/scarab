// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import java.nio.file.{Files, Path}
import java.security.MessageDigest

class BlockFetchGoldenSuite extends munit.FunSuite:
  private def right[A](value: Either[String, A]): A = value.fold(fail(_), identity)
  private def digest(bytes: Bytes): String =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(bytes.toArray)).hex

  test("source-derived message around upstream Conway golden preserves raw bytes") {
    val directory = Vector(
      Path.of("fixtures/network/block-fetch"),
      Path.of("../fixtures/network/block-fetch")
    ).find(Files.isDirectory(_)).getOrElse(fail("missing BlockFetch fixtures"))
    val message = right(
      Bytes.fromHex(Files.readString(directory.resolve("conway-block-source-derived.hex")).trim)
    )
    assertEquals(message.size, 7809)
    assertEquals(
      digest(message),
      "ccf88f406ce7085c3425e8cb63f94c59697c386d8920650b8f8f9b042f4b0d43"
    )
    val codec = CardanoBlockFetch.payloadCodec()
    val decoded =
      right(BlockFetch.decode(BlockFetch.State.Streaming, BlockFetch.Role.Server, message, codec))
    decoded match
      case BlockFetch.Message.Block(block) =>
        assertEquals(block.bytes.size, 7802)
        assertEquals(
          digest(block.bytes),
          "0b7c8bdb99cf28f5e73769733abb4d4630013c5cf9368ed3176717841f95d8f3"
        )
        assertEquals(block.bytes.value.take(2), Vector(0x82.toByte, 0x07.toByte))
        assertEquals(
          right(
            BlockFetch.encode(BlockFetch.State.Streaming, BlockFetch.Role.Server, decoded, codec)
          ),
          message
        )
        assertEquals(block.bytes, Bytes(message.value.drop(7)))
      case other => fail(s"unexpected message $other")
  }
