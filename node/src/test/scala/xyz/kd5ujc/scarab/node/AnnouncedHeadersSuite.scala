// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.ledger.ChainError
import xyz.kd5ujc.scarab.protocol.{LeiosField, LeiosNotify, LeiosWireError}

/** `MsgLeiosBlockAnnouncement` read as the build the Leios testnet runs reads it (#757): the header
  * is `Header blk`, read with the node-to-node decoder of its era (`dispatchDecoder`,
  * `HardFork/Combinator/Serialisation/SerialiseNodeToNode.hs:125-130`, ouroboros-consensus
  * `2416493b`), so a header its era's decoder refuses, or an era index the Cardano eras do not
  * have, fails the message. The node's reader is [[AnnouncedHeaders]].
  */
class AnnouncedHeadersSuite extends FunSuite:
  private given LeiosNotify.AnnouncementReader = AnnouncedHeaders(BigInt(21600))

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  /** An item as a byte string's CBOR. */
  private def bstr(b: Bytes): Bytes =
    val n = b.size
    val head =
      if n < 24 then f"${0x40 + n}%02x" else if n < 256 then f"58$n%02x" else f"59$n%04x"
    hex(head) ++ b

  private def envelope(era: Int, header: Bytes): Bytes =
    hex(f"82$era%02xd818") ++ bstr(header)

  private def announce(item: Bytes) =
    LeiosNotify.decodePrefix(LeiosNotify.State.Busy, hex("8204") ++ item)

  /** The announcing header of block 64,849 of the testnet, as its relay sent it on ChainSync. */
  private val announcing: Bytes =
    Files
      .readAllLines(Path.of("fixtures", "leios", "testnet", "headers-notable.tsv"))
      .asScala
      .map(_.split('\t'))
      .find(_(0) == "announcing")
      .map(p => hex(p(5)))
      .getOrElse(fail("fixture"))

  /** The 10-field Praos header of a Preview Conway block. */
  private val conwayHeader: Bytes =
    val block = hex(Files.readAllLines(Path.of("fixtures", "preview", "conway.hex")).get(0).trim)
    Cbor.decode(block, Cbor.Limits(2500000, 2048, 1000000, 2500000)).toOption.map(_.value) match
      case Some(Value.Arr(Vector(_, body))) =>
        body.value match
          case Value.Arr(parts) => parts.head.original
          case other            => fail(other.toString)
      case other => fail(other.toString)

  test("a real announcement of the testnet is read, its envelope kept as it came") {
    assertEquals(
      announce(announcing).map(_._1),
      Right(LeiosNotify.Message.BlockAnnouncement(announcing))
    )
  }

  test("a header of an earlier era is read by that era's decoder, as `dispatchDecoder` reads it") {
    val conway = envelope(6, conwayHeader)
    assertEquals(announce(conway).map(_._1), Right(LeiosNotify.Message.BlockAnnouncement(conway)))
  }

  test("a valid envelope holding no header of its era fails the message with the era's refusal") {
    // the header inside `82 07 d8 18 59 xx xx`
    val leiosHeader = announcing.drop(7)
    // not a header; a Praos header where Dijkstra's Leios header is read; the Leios header where
    // Conway's Praos header is read
    val refused =
      Vector(envelope(7, hex("00")), envelope(7, conwayHeader), envelope(6, leiosHeader))
    refused.foreach { item =>
      announce(item) match
        case Left(LeiosWireError.UndecodableAnnouncement(_: ChainError)) => ()
        case other => fail(s"${item.hex.take(40)}: $other")
    }
  }

  test("an era index the Cardano eras do not have is no envelope of an era") {
    // `decodeNS` refuses an index past the last era (`HardFork/Combinator/Serialisation/Common.hs`)
    Vector(envelope(8, hex("00")), envelope(9, announcing.drop(7))).foreach { item =>
      assertEquals(announce(item), Left(LeiosWireError.BadField(4, LeiosField.Header)), item.hex)
    }
  }
end AnnouncedHeadersSuite
