// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}

/** Native droppers are intentionally permissive about values, but distinguish CBOR token forms.
  * These cases follow cardano-ledger f649f975 Ssc.hs and Binary/Decoding/Drop.hs, independently of
  * the three proof comparisons. The maintained era corpus supplies actual reference-adopted bodies.
  */
class ByronTransportSuite extends FunSuite:
  private def hex(value: String): Bytes = Bytes.fromHex(value).fold(e => fail(e.message), identity)
  private def concat(values: Iterable[Bytes]): Bytes = values.foldLeft(Bytes.empty)(_ ++ _)
  private def array(values: Bytes*): Bytes = ByronCbor.of(0x80 + values.size) ++ concat(values)
  private def list(values: Bytes*): Bytes =
    ByronCbor.of(0x9f) ++ concat(values) ++ ByronCbor.of(0xff)
  private def map(values: (Bytes, Bytes)*): Bytes =
    ByronCbor.of(0xa0 + values.size) ++ concat(values.flatMap((k, v) => Vector(k, v)))
  private def tagged(values: Bytes*): Bytes = hex("d90102") ++ array(values*)
  private val bytes = hex("40")
  private val zero = hex("00")
  private val extra = array(map())
  private val certificate = array(bytes, zero, bytes, bytes)
  private val certificates = tagged(certificate)
  private val secret = array(bytes, bytes, bytes, list(bytes))
  private val commitment = array(map(bytes -> list(bytes)), secret)
  private val signed = array(bytes, commitment, bytes)
  private val payloads = Vector(
    array(zero, tagged(signed), certificates),
    array(hex("01"), map(bytes -> bytes), certificates),
    array(hex("02"), map(bytes -> map(bytes -> list(bytes))), certificates),
    array(hex("03"), certificates)
  )
  private def body(ssc: Bytes, txs: Bytes = list(), delegation: Bytes = list()): Bytes =
    array(txs, ssc, delegation, array(array(), list()))
  private def check(ssc: Bytes): Either[xyz.kd5ujc.scarab.ledger.LedgerDecodeError, Unit] =
    ByronTransport.check(body(ssc), extra, boundary = false)
  private def items(node: Node): Vector[Node] = node.value match
    case Value.Arr(values) => values
    case other             => fail(s"expected corpus array, got $other")

  test("all 50 reference-adopted Byron bodies and extras retain their original token forms") {
    val byron = Files
      .readString(Path.of("fixtures/eras/blocks.hex"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(hex)
      .map(raw => items(Cbor.decode(raw).fold(e => fail(e.message), identity)))
      .filter(_.head.value match
        case Value.UInt(tag) => tag == 0 || tag == 1
        case _               => false)
      .toVector
    assertEquals(byron.size, 50)
    byron.foreach { outer =>
      val block = items(outer(1))
      val boundary = outer(0).value == Value.UInt(0)
      assertEquals(ByronTransport.check(block(1).original, block(2).original, boundary), Right(()))
    }
  }

  payloads.zipWithIndex.foreach { (payload, tag) =>
    test(s"SSC tag $tag accepts its nonempty native nested shape") {
      assertEquals(check(payload), Right(()))
    }
  }

  test("native discarded tags, duplicate maps and nonminimal heads remain accepted") {
    val unusualSet = hex("c0") ++ array(certificate, certificate)
    val duplicateMap = map(bytes -> bytes, bytes -> bytes)
    assertEquals(check(array(hex("1801"), duplicateMap, unusualSet)), Right(()))
    assertEquals(check(hex("9802") ++ hex("1803") ++ unusualSet), Right(()))
    assertEquals(
      ByronTransport.check(list(hex("5800")), hex("9801b80218ff580018ff5800"), boundary = true),
      Right(())
    )
    val word64 = array(bytes, hex("1bffffffffffffffff"), bytes, bytes)
    assertEquals(check(array(hex("03"), tagged(word64))), Right(()))
    assertEquals(
      ByronTransport.check(body(payloads(3)), hex("9801b800"), boundary = false),
      Right(())
    )
  }

  test("native droppers accept empty encrypted-share and secret-commitment lists") {
    // Ssc.hs comments describe NonEmpty, but its dropList grammar does not enforce nonemptiness.
    val emptySecret = array(bytes, bytes, bytes, list())
    val emptyCommitment = array(map(bytes -> list()), emptySecret)
    assertEquals(
      check(array(zero, tagged(array(bytes, emptyCommitment, bytes)), tagged())),
      Right(())
    )
  }

  private val invalidSsc = Vector(
    "null" -> hex("f6"),
    "indefinite outer payload" -> list(hex("03"), tagged()),
    "unknown tag" -> array(hex("04"), tagged()),
    "out-of-range tag" -> array(hex("190100"), tagged()),
    "wrong certificates arity" -> array(hex("03"), tagged(), bytes),
    "missing set tag" -> array(hex("03"), array(certificate)),
    "indefinite tagged set" -> array(hex("03"), hex("d90102") ++ list(certificate)),
    "indefinite certificate tuple" -> array(hex("03"), tagged(list(bytes, zero, bytes, bytes))),
    "negative certificate epoch" -> array(hex("03"), tagged(array(bytes, hex("20"), bytes, bytes))),
    "indefinite certificate bytes" -> array(
      hex("03"),
      tagged(array(hex("5fff"), zero, bytes, bytes))
    ),
    "indefinite opening map" -> array(hex("01"), hex("bfff"), certificates),
    "nonbytes opening key" -> array(hex("01"), map(zero -> bytes), certificates),
    "nonbytes opening value" -> array(hex("01"), map(bytes -> zero), certificates),
    "nonmap inner shares" -> array(hex("02"), map(bytes -> bytes), certificates),
    "definite shares list" -> array(
      hex("02"),
      map(bytes -> map(bytes -> array(bytes))),
      certificates
    ),
    "nonbytes shared value" -> array(
      hex("02"),
      map(bytes -> map(bytes -> list(zero))),
      certificates
    ),
    "bad signed commitment arity" -> array(zero, tagged(array(bytes, commitment)), certificates),
    "bad commitment tuple" -> array(
      zero,
      tagged(array(bytes, array(bytes, secret), bytes)),
      certificates
    ),
    "definite encrypted shares list" -> array(
      zero,
      tagged(array(bytes, array(map(bytes -> array(bytes)), secret), bytes)),
      certificates
    ),
    "bad secret proof arity" -> array(
      zero,
      tagged(array(bytes, array(map(), array(bytes, bytes, bytes)), bytes)),
      certificates
    ),
    "definite secret commitments" -> array(
      zero,
      tagged(array(bytes, array(map(), array(bytes, bytes, bytes, array(bytes))), bytes)),
      certificates
    )
  )

  invalidSsc.foreach { (label, payload) =>
    test(s"SSC native structural rejection: $label") {
      assert(check(payload).isLeft)
    }
  }

  test("main payload lists and extra data preserve the native v1 token requirements") {
    Vector(
      (body(payloads(3), txs = array()), extra),
      (body(payloads(3), delegation = array()), extra),
      (list(list(), payloads(3), list(), array(array(), list())), extra),
      (body(payloads(3)), hex("f6")),
      (body(payloads(3)), list(map())),
      (body(payloads(3)), array(hex("bfff"))),
      (body(payloads(3)), array(map(zero -> bytes)))
    ).foreach { (raw, tail) => assert(ByronTransport.check(raw, tail, boundary = false).isLeft) }
  }

  test("EBB skips body proof only after decoding its indefinite byte list and Word8 attributes") {
    assertEquals(ByronTransport.check(list(bytes, bytes), extra, boundary = true), Right(()))
    Vector(
      (hex("f6"), extra),
      (array(bytes), extra),
      (list(zero), extra),
      (list(hex("5fff")), extra),
      (list(), hex("f6")),
      (list(), list(map())),
      (list(), array(hex("bfff"))),
      (list(), array(map(hex("190100") -> bytes))),
      (list(), array(map(zero -> zero))),
      (list(), array(map(zero -> hex("5fff"))))
    ).foreach { (raw, tail) => assert(ByronTransport.check(raw, tail, boundary = true).isLeft) }
  }

  test("wide native SSC collections remain stack safe") {
    val opening = ByronCbor.of(0xba, 0x00, 0x00, 0x27, 0x10) ++
      concat(Vector.fill(10000)(bytes ++ bytes))
    xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack {
      assertEquals(check(array(hex("01"), opening, tagged())), Right(()))
    }
  }
