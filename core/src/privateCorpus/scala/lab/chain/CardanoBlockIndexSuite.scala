// SPDX-License-Identifier: Apache-2.0
package lab.chain

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}

class CardanoBlockIndexSuite extends munit.FunSuite:
  private case class Expected(
      file: String,
      era: String,
      height: Long,
      slot: Long,
      hash: String,
      parent: String,
      sha: String,
      headerSize: Int
  )
  // Literal expectations imported from independently retained research manifests,
  // never generated from this Scala indexer. Trust/provenance is documented separately.
  private val expected = Vector(
    Expected(
      "shelley-0.cbor",
      "shelley",
      4490511L,
      4492800L,
      "aa83acbf5904c0edfe4d79b3689d3d00fcfc553cf360fd2229b98d464c28e9de",
      "f8084c61b6a238acec985b59310b6ecec49c0ab8352249afd7268da5cff2a457",
      "e9907a0a9a0084cd677df4215c9e659e247258007ffe793d0334a68276640503",
      1002
    ),
    Expected(
      "shelley-1.cbor",
      "shelley",
      4490512L,
      4492840L,
      "4aff331a7b3022c0e3f94694f9a5f5739a78727db344a7b0331d1a0376e923e9",
      "aa83acbf5904c0edfe4d79b3689d3d00fcfc553cf360fd2229b98d464c28e9de",
      "19ea1710ab6ec481ee77c6c8726c51d817f3d0918ad68ca24aa5cc714c2d5c1b",
      1002
    ),
    Expected(
      "shelley-2.cbor",
      "shelley",
      4490513L,
      4492860L,
      "3b5acf05f4f58290acee1654c9e1ec3a787aa9c27810c296103afd206702fb1b",
      "4aff331a7b3022c0e3f94694f9a5f5739a78727db344a7b0331d1a0376e923e9",
      "3e3ad8eec98837f08f8b5b95926dc1e4f7ac413e355815444009a666d0cef327",
      1002
    ),
    Expected(
      "shelley-3.cbor",
      "shelley",
      4490514L,
      4492880L,
      "23fd3b638e8f286978681567d52597b73f7567e18719cef2cbd66bba31303d98",
      "3b5acf05f4f58290acee1654c9e1ec3a787aa9c27810c296103afd206702fb1b",
      "a4ac1e40c42d9719fb5b4691bfd2569b47bb1e8224b751c9efa678d98a93942e",
      1002
    ),
    Expected(
      "allegra-0.cbor",
      "allegra",
      5086524L,
      16588800L,
      "078d102d0247463f91eef69fc77f3fbbf120f3118e68cd5e6a493c15446dbf8c",
      "4e9bbbb67e3ae262133d94c3da5bffce7b1127fc436e7433b87668dba34c354a",
      "c619d440bd4643ed29f9a8e5fc057b746e32fb1811a8a7296843b467e9034223",
      1005
    ),
    Expected(
      "allegra-1.cbor",
      "allegra",
      5086525L,
      16588845L,
      "d320eb16082133a24b11d00f687a55740a3efddf6de2a104bb826d6113f1bfcd",
      "078d102d0247463f91eef69fc77f3fbbf120f3118e68cd5e6a493c15446dbf8c",
      "7c2f0daa4388947bfe75c6f03431ef762ddc43a019340eebf8d5b08110456c56",
      1005
    ),
    Expected(
      "allegra-2.cbor",
      "allegra",
      5086526L,
      16588862L,
      "2563b8f81e5881452c25533ce0d8976a1f845041d3a2cbb3a0f98fe8eee6dd20",
      "d320eb16082133a24b11d00f687a55740a3efddf6de2a104bb826d6113f1bfcd",
      "7062fff426cc8b8c381adb906ceea6ab160e5c52fe2664d5c31d9ecec509810d",
      1003
    ),
    Expected(
      "allegra-3.cbor",
      "allegra",
      5086527L,
      16588889L,
      "f77e011ef1ad383dced90e32e18c28c0fdc6e363066ab5adf191044fdbcf668d",
      "2563b8f81e5881452c25533ce0d8976a1f845041d3a2cbb3a0f98fe8eee6dd20",
      "eb298c19c25e95e92a3ee78fb942f4016a61d4dffba581f21e5e1b3065247962",
      1005
    )
  )
  private def fixture(name: String): Bytes =
    val stream = getClass.getResourceAsStream(s"/historical-index/$name")
    require(stream != null, s"missing fixture $name")
    try Bytes.fromArray(stream.readAllBytes())
    finally stream.close()
  private def raw: Bytes = fixture("shelley-0.cbor")
  private def index(b: Bytes) = CardanoBlockIndex.inspect(b, 1048576)
  private def splice(b: Bytes, start: Int, count: Int, replacement: Bytes): Bytes =
    Bytes(b.value.take(start) ++ replacement.value ++ b.value.drop(start + count))
  private def hex(s: String): Bytes = Bytes.fromHex(s).toOption.get

  test(
    "all eight original fixtures match independent hash, parent, slot, height and SHA expectations"
  ) {
    expected.foreach { e =>
      val b = fixture(e.file)
      val i = index(b).fold(fail(_), identity)
      assertEquals(i.era, e.era)
      assertEquals(i.blockNo, BigInt(e.height))
      assertEquals(i.slot, BigInt(e.slot))
      assertEquals(i.headerHash.hex, e.hash)
      assertEquals(i.parentHash.hex, e.parent)
      assertEquals(i.rawSha256.hex, e.sha)
      assertEquals(i.headerLength, e.headerSize)
      assertEquals(i.headerOffset, 3)
      assertEquals(i.rawBytes, b)
      assertEquals(
        i.headerBytes,
        Bytes(b.value.slice(i.headerOffset, i.headerOffset + i.headerLength))
      )
    }
    expected.groupBy(_.era).values.foreach { segment =>
      segment.sliding(2).foreach { pair =>
        assertEquals(
          index(fixture(pair(1).file)).toOption.get.parentHash,
          index(fixture(pair(0).file)).toOption.get.headerHash
        )
      }
    }
  }

  test("every truncation, trailing item and unsupported era is rejected") {
    (0 until raw.size).foreach(n => assert(index(Bytes(raw.value.take(n))).isLeft, s"prefix $n"))
    assert(index(Bytes(raw.value :+ 0.toByte)).isLeft)
    Vector(0, 1, 5, 6, 7, 8).foreach(n =>
      assert(index(splice(raw, 1, 1, Bytes(Vector(n.toByte)))).isLeft)
    )
    assert(index(hex("820280")).isLeft)
    assert(index(hex("820383808080")).isLeft)
  }

  test(
    "wrong header arities, parent type/length, unsigned fields, and extra block fields are rejected"
  ) {
    Vector(
      (0, "83"),
      (2, "85"),
      (3, "83"),
      (4, "90"),
      (5, "3a"),
      (10, "3a"),
      (15, "5821"),
      (15, "7820")
    ).foreach { case (offset, replacement) =>
      assert(index(splice(raw, offset, if offset == 15 then 2 else 1, hex(replacement))).isLeft)
    }
    // A real fifth field rather than merely a truncated declared arity.
    assert(index(Bytes(splice(raw, 2, 1, hex("85")).value :+ 0.toByte)).isLeft)
    // Tag-2 bignum encoding cannot stand in for uint64 even if mathematically in range.
    assert(index(splice(raw, 5, 5, hex("c249010000000000000000"))).isLeft)
  }

  test("non-shortest integer and container encodings preserve original header identity") {
    val ordinary = index(raw).toOption.get
    // Original block number uses 0x1a + four bytes. Use the legal non-shortest uint64 form.
    val altered = splice(raw, 5, 5, hex("1b000000000044850f"))
    val changed = index(altered).fold(fail(_), identity)
    assertEquals(changed.blockNo, ordinary.blockNo)
    assertEquals(changed.slot, ordinary.slot)
    assertNotEquals(changed.headerHash, ordinary.headerHash)
    assertEquals(changed.headerHash, Blake2b.hash256.hash(changed.headerBytes))
    assertEquals(changed.rawBytes, altered)
    val outerWide = splice(raw, 0, 1, hex("9802"))
    val outer = index(outerWide).toOption.get
    assertEquals(outer.headerOffset, 4)
    assertEquals(outer.headerHash, ordinary.headerHash)
    assertNotEquals(outer.rawSha256, ordinary.rawSha256)
    val indefiniteHeader = splice(
      raw,
      3,
      ordinary.headerLength,
      Bytes(Vector(0x9f.toByte) ++ ordinary.headerBytes.value.drop(1) :+ 0xff.toByte)
    )
    val indefinite = index(indefiniteHeader).toOption.get
    assertEquals(indefinite.blockNo, ordinary.blockNo)
    assertNotEquals(indefinite.headerHash, ordinary.headerHash)
  }

  test("full uint64 range is preserved without signed Long truncation") {
    val changed = index(splice(raw, 10, 5, hex("1bffffffffffffffff"))).toOption.get
    assertEquals(changed.slot, (BigInt(1) << 64) - 1)
  }

  test("NtC tag24 byte wrapper and NtN-style header wrapper are not disk blocks") {
    val wrapped = Cbor.encode(Value.Tag(24, Node(Value.ByteString(raw), Bytes.empty))).toOption.get
    assert(index(wrapped).isLeft)
    val header = index(raw).toOption.get.headerBytes
    assert(index(Bytes(hex("8202").value ++ header.value)).isLeft)
  }

  test("byte, depth, item and declared length limits reject before unbounded allocation") {
    assert(CardanoBlockIndex.inspect(raw, raw.size - 1).isLeft)
    assert(CardanoBlockIndex.inspect(raw, -1).isLeft)
    assert(CardanoBlockIndex.inspect(raw, Int.MaxValue).isLeft)
    assert(CardanoBlockIndex.inspect(raw, CardanoBlockIndex.Limits(maxDepth = 1)).isLeft)
    assert(CardanoBlockIndex.inspect(raw, CardanoBlockIndex.Limits(maxItems = 4)).isLeft)
    assert(CardanoBlockIndex.inspect(raw, CardanoBlockIndex.Limits(maxStringBytes = 31)).isLeft)
    Vector("9bffffffffffffffff", "5bffffffffffffffff", "bf00ff", "1c", "8202ff").foreach { s =>
      assert(index(hex(s)).isLeft)
    }
  }

  test("caller array mutations cannot alter checked output bytes") {
    val array = raw.toArray
    val input = Bytes.fromArray(array)
    val checked = index(input).toOption.get
    array(3) = 0
    val returnedArray = checked.headerBytes.toArray
    returnedArray(0) = 0
    assertEquals(checked.rawBytes, raw)
    assertEquals(checked.headerHash.hex, expected.head.hash)
  }
