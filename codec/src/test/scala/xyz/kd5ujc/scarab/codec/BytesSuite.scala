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
