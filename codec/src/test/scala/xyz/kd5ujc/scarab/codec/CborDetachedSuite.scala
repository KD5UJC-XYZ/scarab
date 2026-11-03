// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** A decode with [[Cbor.Detached]] records (#687): an empty indefinite list read as the head of the
  * items after its break, as the reference reads a `RecD` record so written, and the items that
  * enclose it framed and spanning the bytes as the reference reads them.
  */
class CborDetachedSuite extends munit.FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  private def detached(records: (Int, Int)*): Cbor.Detached =
    Cbor.Detached(records.map(_._1).toVector, records.map(_._2).toVector)

  test("a detached record holds the items after its break, and its parent counts it as one") {
    // [1, 9f ff 2 3]: a list of two, the second the record of 2 and 3
    val raw = bytes("82019fff0203")
    assertEquals(Cbor.decode(raw), Left(Cbor.Error.TrailingBytes))
    val root =
      Cbor.decode(raw, Cbor.Limits(), detached(2 -> 2)).fold(e => fail(e.message), identity)
    val Value.Arr(Vector(one, record)) = root.value: @unchecked
    assertEquals(one.value, Value.UInt(1))
    assert(record.isDetached)
    assert(record.isIndefinite)
    assert(!one.isDetached && !root.isDetached)
    assertEquals(record.original, bytes("9fff0203"))
    assertEquals(
      record.value.toString,
      Value.Arr(Vector(Node(Value.UInt(2), Bytes.empty), Node(Value.UInt(3), Bytes.empty))).toString
    )
    assertEquals(
      record.items.map(_.map(_.original).toVector),
      Some(Vector(bytes("02"), bytes("03")))
    )
    assertEquals(root.original, raw)
    assert(!root.decodeHasEmptyIndefiniteList)
  }

  test("records nest, and an indefinite parent ends at its own break after them") {
    // 9f [9f ff [9f ff 1 2] 3] ff: an indefinite list holding a record whose first field is a record
    val raw = bytes("9f9fff9fff010203ff")
    val root =
      Cbor.decode(raw, Cbor.Limits(), detached(1 -> 2, 3 -> 2)).fold(e => fail(e.message), identity)
    val Value.Arr(Vector(outer)) = root.value: @unchecked
    val Value.Arr(Vector(inner, three)) = outer.value: @unchecked
    assertEquals(outer.original, bytes("9fff9fff010203"))
    assertEquals(inner.original, bytes("9fff0102"))
    assertEquals(three.value, Value.UInt(3))
    assert(outer.isDetached && inner.isDetached)
  }

  test("an empty indefinite list not given as a record is the empty list, and the decode says so") {
    val root = Cbor.decode(bytes("829fff01")).fold(e => fail(e.message), identity)
    val Value.Arr(Vector(empty, _)) = root.value: @unchecked
    assert(!empty.isDetached)
    assertEquals(empty.value, Value.Arr(Vector.empty))
    assert(root.decodeHasEmptyIndefiniteList)
    assert(!Cbor.decode(bytes("829f01ff01")).exists(_.decodeHasEmptyIndefiniteList))
    assert(!Node(Value.Arr(Vector.empty), bytes("9fff")).isDetached)
  }

  test("a record that is not an empty indefinite list where it is given is refused") {
    // not a list
    assertEquals(
      Cbor.decode(bytes("820102"), Cbor.Limits(), detached(1 -> 1)),
      Left(Cbor.Error.DetachedRecord(1))
    )
    // a non-empty indefinite list
    assertEquals(
      Cbor.decode(bytes("819f01ff"), Cbor.Limits(), detached(1 -> 1)),
      Left(Cbor.Error.DetachedRecord(1))
    )
    // inside a byte string, never reached as an item
    assertEquals(
      Cbor.decode(bytes("42" + "9fff"), Cbor.Limits(), detached(1 -> 1)),
      Left(Cbor.Error.DetachedRecord(1))
    )
    // its fields run past the end of the input
    assertEquals(
      Cbor.decode(bytes("9fff01"), Cbor.Limits(), detached(0 -> 2)),
      Left(Cbor.Error.UnexpectedEnd)
    )
  }

  test("records that are not one count of one or more fields an offset, in order, are refused") {
    val raw = bytes("82019fff0203")
    for records <- Vector(
        Cbor.Detached(Vector(2), Vector.empty),
        Cbor.Detached(Vector(2), Vector(0)),
        Cbor.Detached(Vector(2), Vector(-1)),
        Cbor.Detached(Vector(2, 2), Vector(2, 2))
      )
    do
      assert(
        Cbor
          .decode(raw, Cbor.Limits(), records)
          .left
          .exists(_.isInstanceOf[Cbor.Error.DetachedRecord])
      )
  }

  test("the decode without records is unchanged") {
    val raw = bytes("a2019f01ff029fff")
    assertEquals(
      Cbor.decode(raw, Cbor.Limits(), Cbor.Detached.none).map(_.original),
      Cbor.decode(raw).map(_.original)
    )
  }
