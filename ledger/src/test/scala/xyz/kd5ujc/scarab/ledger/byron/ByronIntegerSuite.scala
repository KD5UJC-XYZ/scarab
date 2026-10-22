// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}

class ByronIntegerSuite extends FunSuite:
  private def hex(value: String): Bytes = Bytes.fromHex(value).fold(e => fail(e.message), identity)
  private def node(value: String) = Cbor.decode(hex(value)).fold(e => fail(e.message), identity)

  // Actual cborg0.2.10 ConsumeInteger outcomes, the decoder used by pinned Byron decodeInteger.
  private val cases = Vector(
    ("positive-definite", "c2420100", Some(BigInt(256))),
    ("negative-definite", "c3420100", Some(BigInt(-257))),
    ("positive-indefinite", "c25f41014100ff", None),
    ("negative-indefinite", "c35f41014100ff", None),
    ("positive-longtag", "d802420100", None),
    ("negative-longtag", "d803420100", None),
    ("noncanonical-length", "c258020100", Some(BigInt(256))),
    ("empty", "c240", Some(BigInt(0))),
    ("negative-empty", "c340", Some(BigInt(-1))),
    ("leading-zero", "c2420001", Some(BigInt(1)))
  )

  cases.foreach { (label, encoded, expected) =>
    test(s"shared Byron integer decoder matches native $label") {
      val decoded = ByronInteger.decode(node(encoded), "native integer")
      expected match
        case Some(value) => assertEquals(decoded, Right(value))
        case None        => assert(decoded.isLeft)
    }

    test(s"parameter model and transport agree on native natural $label") {
      val fields = Vector.tabulate(14)(i => if i == 1 then "81" + encoded else "80").mkString
      val input = node("8e" + fields)
      val accepted = expected.exists(_ >= 0)
      assertEquals(ByronTransport.parameters(input).isRight, accepted)
      val actual = ByronParamsUpdate.decode(input)
      expected.filter(_ >= 0) match
        case Some(value) => assertEquals(actual.map(_.slotDurationMs), Right(Some(value)))
        case None        => assert(actual.isLeft)
    }
  }
