// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}

class ConformanceRegressionSuite extends FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)

  test("optional-before-required accepts disjoint present and absent members") {
    val absent =
      features.OptionalMiddle.decodeBytes(bytes("8201f5")).fold(e => fail(e.render), identity)
    assertEquals(absent.before, None)
    assertEquals(absent.after, BigInt(1))
    assertEquals(absent.payload, None)
    val present =
      features.OptionalMiddle
        .decodeBytes(bytes("844101024100f5"))
        .fold(e => fail(e.render), identity)
    assertEquals(present.before, Some(bytes("01")))
    assertEquals(present.after, BigInt(2))
    assertEquals(present.payload, Some(bytes("00")))
    assertEquals(features.OptionalMiddle.encodeUnchecked(present), bytes("844101024100f5"))
    Vector("8101", "82014100", "830102f4ff", "8201f6").foreach { hex =>
      assert(features.OptionalMiddle.decodeBytes(bytes(hex)).isLeft, hex)
    }
  }

  test(
    "spliced optional groups retain their actual size and consume the complete enclosing suffix"
  ) {
    Vector("820102", "8341010203").foreach { hex =>
      val input = bytes(hex)
      val value = features.GroupSuffix.decodeBytes(input).fold(e => fail(e.render), identity)
      assertEquals(features.GroupSuffix.encodeUnchecked(value), input)
      val expected = if hex.startsWith("82") then 2 else 3
      assertEquals(features.OptionalGroup.arity(value.optionalGroup) + 1, expected)
      assertEquals(value.optionalGroup.before.isDefined, expected == 3)
    }
    Vector("8101", "8401020304", "8201f5", "82410102").foreach(hex =>
      assert(features.GroupSuffix.decodeBytes(bytes(hex)).isLeft, hex)
    )
  }

  test("a matching optional member is never reconsidered after its required suffix fails") {
    import xyz.kd5ujc.scarab.models.{runtime as rt}
    // Original-schema counterpart: [g, tail: uint], g = (? before: uint, after: uint).
    def greedy(hex: String): rt.Decoded[(Option[BigInt], BigInt, BigInt)] =
      rt.Codec.any.decodeBytes(bytes(hex)).flatMap(node => rt.Arr.between(node, 2, 3)).flatMap {
        xs =>
          rt.Arr.optional(xs, 0, rt.Codec.uint, "before") { (before, position) =>
            for
              after <- rt.Arr.at(xs, position)(rt.Codec.uint.decode)
              tail <- rt.Arr.at(xs, position + 1)(rt.Codec.uint.decode)
              _ <- rt.Arr.end(xs, position + 2)
            yield (before, after, tail)
          }
      }
    assert(greedy("820102").isLeft, "greedily consumed optional prefix cannot become absent")
    assertEquals(greedy("83010203"), Right((Some(BigInt(1)), BigInt(2), BigInt(3))))
    assert(greedy("8401020304").isLeft, "a matching prefix does not permit unconsumed values")
  }

  test("named integer comparisons retain both bounds and exclude an interior point") {
    Vector("820300", "8218ff18ff", "820305").foreach { hex =>
      assert(features.ComparisonControls.decodeBytes(bytes(hex)).isRight, hex)
    }
    Vector("820200", "820304", "8219010000", "8203190100").foreach { hex =>
      assert(features.ComparisonControls.decodeBytes(bytes(hex)).isLeft, hex)
    }
  }

  test(
    "generic any retains each floating width, signed zero and NaN payload without changing wire policy"
  ) {
    Vector("81f93c00", "81fa80000000", "81fb3ff0000000000000", "81f97e01").foreach { hex =>
      val input = bytes(hex)
      val value = features.AnyPayload.decodeBytes(input).fold(e => fail(e.render), identity)
      assertEquals(features.AnyPayload.encodeUnchecked(value), input, hex)
      assert(Cbor.decode(input).isLeft, "hand-written wire CBOR still rejects floats by default")
    }
  }

  test("generic any retains undefined and unassigned simple values without coercing them to null") {
    Vector("81e0", "81f3", "81f7", "81f820", "81f8ff").foreach { hex =>
      val input = bytes(hex)
      val value = features.AnyPayload.decodeBytes(input).fold(e => fail(e.render), identity)
      assertEquals(features.AnyPayload.encodeUnchecked(value), input)
      assert(Cbor.decode(input).isLeft, "default wire policy still rejects generic simple values")
    }
    Vector("81f800", "81f817", "81f818", "81f81f", "81fc", "81fd", "81fe").foreach(hex =>
      assert(features.AnyPayload.decodeBytes(bytes(hex)).isLeft, hex)
    )
  }

  test(
    "non-preserved maps normalize key order while preserved maps retain order and integer widths"
  ) {
    val input = bytes("a266616374697665f5646e616d656141")
    val person = features.Person.decodeBytes(input).fold(e => fail(e.render), identity)
    val normalized = features.Person.encodeUnchecked(person)
    assertNotEquals(normalized, input)
    assertEquals(features.Person.decodeBytes(normalized), Right(person))
    assertEquals(
      features.Person.encodeUnchecked(
        features.Person.decodeBytes(normalized).fold(e => fail(e.render), identity)
      ),
      normalized
    )
    val preserved = features.PreservedPerson.decodeBytes(input).fold(e => fail(e.render), identity)
    assertEquals(features.PreservedPerson.encodeUnchecked(preserved), input)
    val wide = bytes("9f1801ff")
    val optional = features.PreservedOptional.decodeBytes(wide).fold(e => fail(e.render), identity)
    assertEquals(optional.before, None)
    assertEquals(optional.after, BigInt(1))
    assertEquals(features.PreservedOptional.encodeUnchecked(optional), wide)
  }

  test(
    "bounded_bytes checks original chunks rather than the joined total or a blanket bytes override"
  ) {
    val definite = bytes("5841" + "ab" * 65)
    val chunked = bytes("5f5840" + "ab" * 64 + "41abff")
    val oversizedChunk = bytes("5f5841" + "ab" * 65 + "ff")
    assert(alonzo.BoundedBytes.decodeBytes(definite).isLeft)
    assert(babbage.BoundedBytes.decodeBytes(oversizedChunk).isLeft)
    val value = conway.BoundedBytes.decodeBytes(chunked).fold(e => fail(e.render), identity)
    assertEquals(value.value.size, 65)
    assertEquals(conway.BoundedBytes.encodeUnchecked(value), chunked)
    Vector(
      alonzo.BoundedBytes.decodeBytes(chunked).isRight,
      babbage.BoundedBytes.decodeBytes(chunked).isRight
    ).foreach(result => assert(result))
  }
