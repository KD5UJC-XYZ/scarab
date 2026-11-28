// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Availability, DefaultFun, DefaultUni, PlutusLanguage, ProtocolMajor}

/** The decoder's shortcuts (#1529), each giving what the way around it gives: a constant of a type
  * that is not a list, an array or a pair is read by its type's own decoder, without the plan of
  * its type; a type of one tag without the list of its tags; and the ledger's check of a builtin by
  * its ordinal, not in the set of the builtins available. The value, the type, the position after
  * them and every refusal are the same, on any input.
  */
final class FlatShortcutsSuite extends ScalaCheckSuite:
  private val plain = Vector(
    DefaultUni.Integer,
    DefaultUni.ByteString,
    DefaultUni.String,
    DefaultUni.Unit,
    DefaultUni.Bool,
    DefaultUni.Data,
    DefaultUni.Bls12_381_G1_Element,
    DefaultUni.Bls12_381_G2_Element,
    DefaultUni.Bls12_381_MlResult,
    DefaultUni.Value
  )

  // Every type of the universe once, the match exhaustive, so that a new type is placed here.
  private def plainOrNot(uni: DefaultUni): Boolean = uni match
    case DefaultUni.Integer | DefaultUni.ByteString | DefaultUni.String | DefaultUni.Unit |
        DefaultUni.Bool | DefaultUni.Data | DefaultUni.Bls12_381_G1_Element |
        DefaultUni.Bls12_381_G2_Element | DefaultUni.Bls12_381_MlResult | DefaultUni.Value =>
      true
    case DefaultUni.List(_) | DefaultUni.Array(_) | DefaultUni.Pair(_, _) => false

  test("the plain types are those the match places as plain") {
    assert(plain.forall(plainOrNot))
    assertEquals(plain.distinct.size, 10)
  }

  // Inputs that read as values of each type and inputs that do not: arbitrary bytes, and bytes of
  // small values (short natural numbers, empty byte strings, a byte string holding a CBOR integer).
  private val inputs: Gen[Array[Byte]] = Gen.oneOf(
    Gen
      .choose(0, 64)
      .flatMap(Gen.containerOfN[Array, Byte](_, Gen.choose(Byte.MinValue, Byte.MaxValue))),
    Gen.containerOfN[Array, Byte](4, Gen.oneOf[Byte](0, 1, 2, 0x7f, 0x41, 0x81.toByte, 0xff.toByte))
  )

  property("a plain type's value is its plan's, wherever it starts") {
    Prop.forAll(inputs, Gen.choose(0L, 15L), Gen.oneOf(plain)) { (raw, at, uni) =>
      val input = Input(Bytes.wrap(raw))
      assertEquals(ConstantDecoder.value(input, at, uni), ConstantDecoder.planned(input, at, uni))
    }
  }

  // Types as their tags write them, a continuation bit before each tag: one tag of each of the
  // sixteen values, one followed by more tags, and arbitrary bytes.
  private val headers: Gen[Array[Byte]] = Gen.oneOf(
    Gen.zip(Gen.choose(0, 15), Gen.choose(0, 255)).map { (tag, rest) =>
      // 1 tttt 0 then the low bits of `rest`, and 1 tttt 1 tttt 0 when `rest` is odd
      val bits =
        if rest % 2 == 0 then (0x80 | (tag << 3)) << 8 | rest
        else (0x80 | (tag << 3) | 0x04 | (tag >> 2)) << 8 | ((tag & 3) << 6) | (rest >> 2)
      Array((bits >> 8).toByte, bits.toByte, rest.toByte)
    },
    inputs
  )

  property("a type of one tag is the type its tags parse to, wherever it starts") {
    Prop.forAll(headers, Gen.choose(0L, 7L)) { (raw, at) =>
      val input = Input(Bytes.wrap(raw))
      TypeHeader
        .single(input, at)
        .foreach(got => assertEquals(TypeHeader.parsed(input, at), Right(got)))
      assertEquals(TypeHeader.read(input, at), TypeHeader.parsed(input, at))
    }
  }

  test("every tag of a type of kind * on its own is read without the parse") {
    val star = Vector(0, 1, 2, 3, 4, 8, 9, 10, 11, 13)
    (0 to 15).foreach { tag =>
      val input = Input(Bytes.wrap(Array((0x80 | (tag << 3)).toByte)))
      assertEquals(TypeHeader.single(input, 0).isDefined, star.contains(tag), s"tag $tag")
      assertEquals(TypeHeader.read(input, 0), TypeHeader.parsed(input, 0), s"tag $tag")
    }
  }

  test("the ledger's check of a builtin admits the builtins available, at every protocol") {
    for
      language <- PlutusLanguage.values.toVector
      protocol <- (0 to 13).map(ProtocolMajor(_))
      fun <- DefaultFun.values.toVector
    do
      val available = Availability.builtins(language, protocol).contains(fun)
      val checked = Checks.ledger(language, protocol).builtin(fun)
      assertEquals(
        checked,
        Either
          .cond(available, (), Availability.Refusal.BuiltinNotAvailable(fun, language, protocol)),
        s"$fun, $language, $protocol"
      )
  }
