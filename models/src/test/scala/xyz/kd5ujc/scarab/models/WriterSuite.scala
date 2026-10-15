// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.models.runtime.Writer

/** The Writer's second group (indefinite containers, floats, bignums, the ledger's length policy)
  * against the bytes cborg and the ledger produce, and the canonical group against being left
  * alone.
  */
class WriterSuite extends munit.FunSuite:
  private def written(body: Writer => Unit): String =
    val w = new Writer
    body(w)
    w.result.hex

  test("floats are written at the width asked for and never shrunk (cborg encodeFloat/Double)") {
    assertEquals(written(_.float32(1.0f)), "fa3f800000")
    assertEquals(written(_.float32(0.0f)), "fa00000000")
    assertEquals(written(_.float32(-0.0f)), "fa80000000")
    assertEquals(written(_.float32(100000.0f)), "fa47c35000")
    assertEquals(written(_.float64(1.1)), "fb3ff199999999999a")
    assertEquals(written(_.float64(1.0)), "fb3ff0000000000000")
    assertEquals(written(_.float16(1.0f)), "f93c00")
    assertEquals(written(_.float16(65504f)), "f97bff")
    assertEquals(written(_.float16(-4.0f)), "f9c400")
  }

  test("indefinite containers and the break") {
    assertEquals(
      written { w =>
        w.indefiniteArray(); w.uint(1L); w.uint(2L); w.break()
      },
      "9f0102ff"
    )
    assertEquals(
      written { w =>
        w.indefiniteMap(); w.uint(1L); w.uint(2L); w.break()
      },
      "bf0102ff"
    )
  }

  test("the ledger length policy: definite up to 23 items, indefinite above") {
    def array(n: Int) = written(w => w.variableArray(n)((0 until n).foreach(_ => w.uint(0L))))
    def map(n: Int) =
      written(w =>
        w.variableMap(n)((0 until n).foreach { _ =>
          w.uint(0L); w.uint(1L)
        })
      )
    assertEquals(array(0), "80")
    assertEquals(array(23), "97" + "00" * 23)
    assertEquals(array(24), "9f" + "00" * 24 + "ff")
    assertEquals(map(23), "b7" + "0001" * 23)
    assertEquals(map(24), "bf" + "0001" * 24 + "ff")
    Vector(array(24), map(24)).foreach(h =>
      assert(Cbor.decode(Bytes.fromHex(h).fold(fail(_), identity)).isRight)
    )
  }

  test("integers beyond 64 bits are bignums (cborg encodeInteger)") {
    val two64 = BigInt(1) << 64
    assertEquals(written(_.integer(0)), "00")
    assertEquals(written(_.integer(two64 - 1)), "1bffffffffffffffff")
    assertEquals(written(_.integer(-two64)), "3bffffffffffffffff")
    assertEquals(written(_.integer(two64)), "c249010000000000000000")
    assertEquals(written(_.integer(-two64 - 1)), "c349010000000000000000")
    assertEquals(written(_.integer(two64 * 256 + 5)), "c24a" + "01" + "00" * 8 + "05")
    val decoded = Cbor
      .decode(Bytes.fromHex("c249010000000000000000").fold(fail(_), identity))
      .map(_.value)
    assert(decoded.exists(_.isInstanceOf[Value.Tag]))
  }

  test("the canonical group is unchanged: definite heads, shortest forms") {
    assertEquals(
      written { w =>
        w.arrayHead(2); w.uint(24L); w.int(BigInt(-1))
      },
      "82181820"
    )
    assertEquals(written(_.mapHead(0)), "a0")
    assertEquals(written(_.tag(258)), "d90102")
    assertEquals(written(_.uint(BigInt(1) << 63)), "1b8000000000000000")
  }
