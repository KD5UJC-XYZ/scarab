// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** Every `Bytes` is a window inside a non-null array: no constructor builds one that fails later.
  */
class BytesSuite extends munit.FunSuite:
  private val four = Array[Byte](1, 2, 3, 4)
  private def of(xs: Int*): Bytes = Bytes.fromArray(xs.map(_.toByte).toArray)

  test("a null array or vector fails at construction, by every constructor") {
    val none: Array[Byte] = null
    intercept[NullPointerException](Bytes.fromArray(none))
    intercept[NullPointerException](Bytes.wrap(none))
    intercept[NullPointerException](Bytes.wrap(none, 0, 32))
    intercept[NullPointerException](Bytes.wrap(none, 0, 0))
    intercept[NullPointerException](Bytes(null: Vector[Byte]))
  }

  test("a window past the array is cut to it, as a slice is") {
    assertEquals(Bytes.wrap(four, 1, 2), of(2, 3))
    assertEquals(Bytes.wrap(four, 2, 100), of(3, 4))
    assertEquals(Bytes.wrap(four, 1, Int.MaxValue), of(2, 3, 4))
    assertEquals(Bytes.wrap(four, -1, 3), of(1, 2))
    assertEquals(Bytes.wrap(four, 10, 5), Bytes.empty)
    assertEquals(Bytes.wrap(four, 1, -3), Bytes.empty)
    assertEquals(Bytes.wrap(four, Int.MinValue, Int.MaxValue), Bytes.empty)
    assertEquals(Bytes.wrap(four, 0, 4).slice(-5, 50), of(1, 2, 3, 4))
  }

  test("a byte or a number is read only from inside the bytes") {
    val window = Bytes.wrap(Array[Byte](9, 1, 2, -1, 4, 9), 1, 4)
    assertEquals((window.lift(0), window.lift(3)), (Some(1.toByte), Some(4.toByte)))
    assertEquals((window.lift(-1), window.lift(4)), (None, None))
    assertEquals((window.headOption, Bytes.empty.headOption), (Some(1.toByte), None))
    assertEquals(window.bigEndian(0, 4), Some(0x0102ff04L))
    assertEquals(window.littleEndian(0, 4), Some(0x04ff0201L))
    assertEquals(window.bigEndian(1, 2), Some(0x02ffL))
    assertEquals(window.bigEndian(4, 0), Some(0L))
    assertEquals(
      List(window.bigEndian(1, 4), window.bigEndian(-1, 2), window.bigEndian(0, 9)),
      List(None, None, None)
    )
    assertEquals(window.littleEndian(3, 2), None)
    val high = Bytes.fromArray(Array.fill[Byte](8)(-1))
    assertEquals((high.bigEndian(0, 8), high.littleEndian(0, 8)), (Some(-1L), Some(-1L)))
    assertEquals(window.foldLeft(List.empty[Byte])((xs, b) => b :: xs), List[Byte](4, -1, 2, 1))
  }
