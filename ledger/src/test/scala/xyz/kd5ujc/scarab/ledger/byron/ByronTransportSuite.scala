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
  private def body(
      ssc: Bytes,
      txs: Bytes = list(),
      delegation: Bytes = list(),
      updates: Bytes = array(array(), list())
  ): Bytes =
    array(txs, ssc, delegation, updates)
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

  private def blob(size: Int): Bytes = ByronCbor.bytes(Bytes.fromArray(Array.fill[Byte](size)(0)))
  private def known(value: Bytes): Bytes = hex("d818") ++ ByronCbor.bytes(value)
  private def txt(value: String): Bytes =
    Cbor.encode(Value.Text(value)).fold(e => fail(e.message), identity)
  private def integer(value: BigInt): Bytes =
    if value >= 0 && value < (BigInt(1) << 64) then ByronCbor.uint(value)
    else if value < 0 && value >= -(BigInt(1) << 64) then
      val raw = ByronCbor.uint(-value - 1)
      ByronCbor.of((raw(0) & 255) + 32) ++ raw.drop(1)
    else
      val magnitude = if value >= 0 then value else -value - 1
      val raw = magnitude.toByteArray.dropWhile(_ == 0)
      ByronCbor.of(if value >= 0 then 0xc2 else 0xc3) ++ ByronCbor.bytes(Bytes.fromArray(raw))
  private def addr(attrs: Bytes = map(), kind: Bytes = zero): Bytes =
    val payload = array(blob(28), attrs, kind)
    array(known(payload), ByronCbor.uint(BigInt(ByronCbor.crc32(payload))))
  private def txInput(index: Bytes = zero): Bytes = array(zero, known(array(blob(32), index)))
  private def txOutput(address: Bytes = addr(), amount: Bytes = zero): Bytes =
    array(address, amount)
  private def tx(
      inputs: Bytes = list(txInput()),
      outputs: Bytes = list(txOutput()),
      attrs: Bytes = map()
  ): Bytes =
    array(inputs, outputs, attrs)
  private def txWitness(tag: Bytes = zero, keySize: Int = 64): Bytes =
    array(tag, known(array(blob(keySize), blob(64))))
  private def txAux(transaction: Bytes = tx(), witnesses: Bytes = array(txWitness())): Bytes =
    array(transaction, witnesses)
  private val deleg = array(zero, blob(64), blob(64), blob(64))
  private def checkTx(value: Bytes): Either[xyz.kd5ujc.scarab.ledger.LedgerDecodeError, Unit] =
    ByronTransport.check(body(payloads(3), txs = list(value)), extra, boundary = false)
  private def params(index: Int = -1, value: Bytes = zero): Bytes =
    array(Vector.tabulate(14)(i => if i == index then array(value) else array())*)
  private def proposal(
      parameters: Bytes = params(),
      metadata: Bytes = map(),
      attrs: Bytes = map()
  ): Bytes =
    array(
      array(zero, zero, zero),
      parameters,
      array(txt(""), zero),
      metadata,
      attrs,
      blob(64),
      blob(64)
    )
  private val vote = array(blob(64), blob(32), hex("f4"), blob(64))
  private def checkUpdate(value: Bytes): Either[xyz.kd5ujc.scarab.ledger.LedgerDecodeError, Unit] =
    ByronTransport.check(body(payloads(3), updates = value), extra, boundary = false)
  private def withParams(value: Bytes): Bytes = array(array(proposal(value)), list(vote))
  private def fee(a: BigInt, b: BigInt): Bytes = array(zero, known(array(integer(a), integer(b))))

  test(
    "ordinary native transaction, redemption witness, delegation, proposal and false vote decode"
  ) {
    assertEquals(checkTx(txAux()), Right(()))
    assertEquals(checkTx(txAux(witnesses = array(txWitness(hex("02"), 32)))), Right(()))
    assertEquals(
      ByronTransport.check(body(payloads(3), delegation = list(deleg)), extra, false),
      Right(())
    )
    assertEquals(checkUpdate(array(array(proposal()), list(vote))), Right(()))
    // Ordinary noncanonical length heads remain legal where native DecCBOR does not ask Canonical.
    assertEquals(checkTx(hex("9802") ++ tx() ++ array(txWitness())), Right(()))
    assertEquals(checkTx(txAux(tx(attrs = map(hex("1800") -> hex("5800"))))), Right(()))
  }

  private val invalidTransactions = Vector(
    "indefinite TxAux" -> list(tx(), array(txWitness())),
    "indefinite transaction" -> txAux(list(list(txInput()), list(txOutput()), map())),
    "definite inputs" -> txAux(tx(inputs = array(txInput()))),
    "empty inputs" -> txAux(tx(inputs = list())),
    "definite outputs" -> txAux(tx(outputs = array(txOutput()))),
    "empty outputs" -> txAux(tx(outputs = list())),
    "indefinite input tuple" -> txAux(tx(inputs = list(list(zero, known(array(blob(32), zero)))))),
    "unknown input tag" -> txAux(tx(inputs = list(array(hex("01"), known(array(blob(32), zero)))))),
    "indefinite input reference" -> txAux(
      tx(inputs = list(array(zero, known(list(blob(32), zero)))))
    ),
    "input index Word16 overflow" -> txAux(tx(inputs = list(txInput(hex("1a00010000"))))),
    "missing nested input tag" -> txAux(
      tx(inputs = list(array(zero, ByronCbor.bytes(array(blob(32), zero)))))
    ),
    "nested input trailing item" -> txAux(
      tx(inputs = list(array(zero, known(array(blob(32), zero) ++ zero))))
    ),
    "indefinite output tuple" -> txAux(tx(outputs = list(list(addr(), zero)))),
    "lovelace above supply" -> txAux(
      tx(outputs = list(txOutput(amount = integer(ByronLedger.MaxLovelace + 1))))
    ),
    "indefinite witness vector" -> txAux(witnesses = list(txWitness())),
    "indefinite witness tuple" -> txAux(witnesses =
      array(list(zero, known(array(blob(64), blob(64)))))
    ),
    "unknown witness tag" -> txAux(witnesses = array(txWitness(hex("01")))),
    "short witness key" -> txAux(witnesses = array(txWitness(keySize = 63))),
    "indefinite witness value" -> txAux(witnesses =
      array(array(zero, known(list(blob(64), blob(64)))))
    ),
    "duplicate transaction attributes" -> txAux(tx(attrs = map(zero -> bytes, zero -> bytes))),
    "unordered transaction attributes" -> txAux(tx(attrs = map(hex("02") -> bytes, zero -> bytes))),
    "indefinite transaction attributes" -> txAux(tx(attrs = hex("bfff"))),
    "script address type" -> txAux(tx(outputs = list(txOutput(addr(kind = hex("01")))))),
    "noncanonical address type" -> txAux(tx(outputs = list(txOutput(addr(kind = hex("1800")))))),
    "duplicate address attributes" -> txAux(
      tx(outputs = list(txOutput(addr(map(zero -> bytes, zero -> bytes)))))
    ),
    "unordered address attributes" -> txAux(
      tx(outputs = list(txOutput(addr(map(hex("03") -> bytes, zero -> bytes)))))
    ),
    "noncanonical network magic" -> txAux(
      tx(outputs = list(txOutput(addr(map(hex("02") -> ByronCbor.bytes(hex("1800")))))))
    ),
    "noncanonical derivation bytes" -> txAux(
      tx(outputs = list(txOutput(addr(map(hex("01") -> ByronCbor.bytes(hex("5800")))))))
    )
  )
  invalidTransactions.foreach { (label, value) =>
    test(s"ordinary native transaction rejection: $label") { assert(checkTx(value).isLeft) }
  }

  test("ordinary attributes preserve unknown data without applying ledger admission limits") {
    val unknown = map(hex("03") -> blob(512))
    assertEquals(
      checkTx(txAux(tx(attrs = unknown, outputs = list(txOutput(addr(unknown)))))),
      Right(())
    )
    assertEquals(
      checkTx(
        txAux(
          tx(outputs =
            list(
              txOutput(
                addr(map(hex("01") -> ByronCbor.bytes(bytes), hex("02") -> ByronCbor.bytes(zero)))
              )
            )
          )
        )
      ),
      Right(())
    )
  }

  test("delegation decoding enforces tuple, epoch and cryptographic byte lengths") {
    Vector(
      list(zero, blob(64), blob(64), blob(64)),
      array(integer(-1), blob(64), blob(64), blob(64)),
      array(integer(BigInt(1) << 64), blob(64), blob(64), blob(64)),
      array(zero, blob(63), blob(64), blob(64))
    )
      .foreach { value =>
        assert(
          ByronTransport.check(body(payloads(3), delegation = list(value)), extra, false).isLeft
        )
      }
  }

  test(
    "native Natural parameters, signed Nano coefficients and decoder rounding remain permissive"
  ) {
    (1 to 5).foreach(i =>
      assertEquals(checkUpdate(withParams(params(i, integer(BigInt(1) << 80)))), Right(()))
    )
    Vector(
      fee(-1, -1),
      fee(0, -(BigInt(1) << 80)),
      fee((BigInt(1) << 64) * 1000000000, 0),
      fee(ByronLedger.MaxLovelace * 1000000000 + 500000000, 0)
    ).foreach { value =>
      assertEquals(checkUpdate(withParams(params(12, value))), Right(()))
    }
    assert(checkUpdate(withParams(params(12, fee(-1000000000, 0)))).isLeft)
    assert(
      checkUpdate(
        withParams(params(12, fee(ByronLedger.MaxLovelace * 1000000000 + 500000001, 0)))
      ).isLeft
    )
  }

  test("ordinary update map ordering uses decoded Unicode codepoints, not UTF-16 ordering") {
    val installer = array(bytes, blob(32), bytes, bytes)
    val sorted = map(txt("\ue000") -> installer, txt("\ud800\udc00") -> installer)
    assertEquals(checkUpdate(array(array(proposal(metadata = sorted)), list(vote))), Right(()))
    Vector(
      map(txt("z") -> installer, txt("a") -> installer),
      map(txt("a") -> installer, txt("a") -> installer)
    )
      .foreach(value =>
        assert(checkUpdate(array(array(proposal(metadata = value)), list())).isLeft)
      )
  }

  private val invalidUpdates = Vector(
    "indefinite payload" -> list(array(), list()),
    "definite votes" -> array(array(), array(vote)),
    "indefinite Maybe" -> array(list(), list()),
    "multiple proposals" -> array(array(proposal(), proposal()), list()),
    "indefinite proposal" -> array(
      array(
        list(
          array(zero, zero, zero),
          params(),
          array(txt(""), zero),
          map(),
          map(),
          blob(64),
          blob(64)
        )
      ),
      list()
    ),
    "nonempty proposal attributes" -> array(array(proposal(attrs = map(zero -> bytes))), list()),
    "indefinite vote" -> array(array(), list(list(blob(64), blob(32), hex("f4"), blob(64)))),
    "nonboolean vote" -> array(array(), list(array(blob(64), blob(32), zero, blob(64)))),
    "short voter" -> array(array(), list(array(blob(63), blob(32), hex("f4"), blob(64)))),
    "negative Natural" -> withParams(params(1, integer(-1))),
    "script version overflow" -> withParams(params(0, integer(65536))),
    "portion above denominator" -> withParams(params(6, integer(BigInt(10).pow(15) + 1))),
    "negative slot" -> withParams(params(10, integer(-1))),
    "epoch overflow" -> withParams(params(13, integer(BigInt(1) << 64))),
    "unknown fee policy" -> withParams(params(12, array(hex("01"), known(array(zero, zero))))),
    "indefinite fee coefficients" -> withParams(params(12, array(zero, known(list(zero, zero)))))
  )
  invalidUpdates.foreach { (label, value) =>
    test(s"ordinary native update rejection: $label") { assert(checkUpdate(value).isLeft) }
  }
