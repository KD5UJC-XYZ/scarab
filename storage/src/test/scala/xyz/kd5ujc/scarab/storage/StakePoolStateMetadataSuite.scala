// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.protocol.StateLayout
import xyz.kd5ujc.scarab.storage.haskell.state.{
  HaskellState,
  HaskellStateCodecs,
  LedgerFileCodecs,
  LeiosStateCodecs,
  NativeLeiosStateCodecs,
  NativeStateCodecs
}

/** A registered pool's metadata (`spsMetadata :: StrictMaybe PoolMetadata`) is read as the
  * reference reads it, `DecCBOR PoolMetadata` (cardano-ledger f649f975
  * `State/StakePool.hs:522-524`, 9df9f98c `:683-685`): a record of two items, a `Url` and a hash. A
  * one-item record (nightly fuzz, `leios-state` seed 20784481) is refused at the read, not kept and
  * then refused by the writer.
  */
class StakePoolStateMetadataSuite extends FunSuite:
  private def version(major: Long): ProtVer = ProtVer(Word64.fromUnsigned(major), Word64.zero)

  private def hex(s: String): Bytes =
    Bytes.fromHex(s.filterNot(_.isWhitespace)).fold(e => fail(e.toString), identity)

  private def text(s: String): String =
    val b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val head =
      if b.length < 24 then f"${0x60 + b.length}%02x"
      else if b.length < 256 then f"78${b.length}%02x"
      else fail("too long")
    head + b.map(x => f"${x & 0xff}%02x").mkString

  private val key28 = "01" * 28

  /** A `StakePoolState` with metadata `metadata` (`StrictMaybe`, a list of zero or one): ten items
    * in the 11.1 layout, eleven in the Leios layout with no voting key after the VRF key hash; its
    * empty sets tagged 258 (from version 9) or not (`set`).
    */
  private def pool(layout: StateLayout, metadata: String, set: String = "d9010280"): Bytes =
    val (head, key) = layout match
      case StateLayout.Node11_1 => ("8a", "")
      case StateLayout.Leios    => ("8b", "80")
    hex(
      s"$head 5820${"02" * 32} $key 00 00 d81e820102 8200581c$key28 $set 80 $metadata 00 $set"
    )

  private val families: Vector[(String, LedgerFileCodecs, Long)] = Vector(
    ("11.1 reference file, version 9", HaskellStateCodecs, 9),
    ("11.1 native, version 9", NativeStateCodecs, 9),
    ("Leios reference file, version 9", LeiosStateCodecs, 9),
    ("Leios reference file, version 12", LeiosStateCodecs, 12),
    ("Leios native, version 12", NativeLeiosStateCodecs, 12)
  )

  private def read(
      family: LedgerFileCodecs,
      major: Long,
      bytes: Bytes
  ): Either[DecodeError, StakePoolState] =
    given ProtVer = version(major)
    family.stakePoolState.decodeBytes(bytes, HaskellState.limits)

  private def refused(e: node.LedgerDecodeError): DecodeError.Kind = DecodeError.Kind.Domain(e)

  test("a one-item metadata record is refused at the read, in every layout and family") {
    families.foreach { (name, family, major) =>
      val bytes = pool(family.stateLayout, s"81 81 ${text("x")}")
      assertEquals(
        read(family, major, bytes).left.map(_.kind),
        Left(refused(node.LedgerDecodeError.ArrayLength(node.DecodeField.PoolMetadata, 2, 1))),
        name
      )
    }
  }

  test("a [url, hash] metadata record is read and written back as it came") {
    families.foreach { (name, family, major) =>
      given ProtVer = version(major)
      Vector("80", s"81 82 ${text("https://x")} 5820${"03" * 32}").foreach { metadata =>
        val bytes = pool(family.stateLayout, metadata)
        val state = read(family, major, bytes).fold(e => fail(s"$name: ${e.render}"), identity)
        assertEquals(family.stakePoolState.write(state), Right(bytes), s"$name $metadata")
      }
    }
  }

  test("the metadata's other refusals are the future parameters' own") {
    families.foreach { (name, family, major) =>
      val layout = family.stateLayout
      assertEquals(
        read(family, major, pool(layout, s"81 83 ${text("x")} 4100 00")).left.map(_.kind),
        Left(refused(node.LedgerDecodeError.ArrayLength(node.DecodeField.PoolMetadata, 2, 3))),
        name
      )
      assertEquals(
        read(family, major, pool(layout, s"81 82 ${text("u" * 129)} 4100")).left.map(_.kind),
        Left(
          refused(node.LedgerDecodeError.TextTooLong(node.DecodeField.PoolMetadataURL, 128, 129))
        ),
        name
      )
    }
    // a chunked URL from version 12 only in the Leios build (`decodeString`, 9df9f98c
    // `Decoding/Decoder.hs:1564-1598`; definite only at f649f975)
    val chunkedUrl = "81 82 7f6178ff 4100"
    assert(read(LeiosStateCodecs, 12, pool(StateLayout.Leios, chunkedUrl)).isRight)
    assertEquals(
      read(HaskellStateCodecs, 12, pool(StateLayout.Node11_1, chunkedUrl)).left.map(_.kind),
      Left(refused(node.LedgerDecodeError.IndefiniteLength(node.DecodeField.PoolMetadataURL)))
    )
  }

  test("below version 9 the metadata's URL is at most 64 bytes, in both layouts") {
    Vector(HaskellStateCodecs, NativeStateCodecs, LeiosStateCodecs).foreach { family =>
      val layout = family.stateLayout
      val ok = pool(layout, s"81 82 ${text("u" * 64)} 4100", set = "80")
      assert(read(family, 2, ok).isRight, family.toString)
      assertEquals(
        read(family, 2, pool(layout, s"81 82 ${text("u" * 65)} 4100", set = "80")).left.map(_.kind),
        Left(
          refused(node.LedgerDecodeError.TextTooLong(node.DecodeField.PoolMetadataURL, 64, 65))
        ),
        family.toString
      )
    }
  }
