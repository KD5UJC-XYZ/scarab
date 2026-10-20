// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.{Genesis, KeyHash, StakePool}
import xyz.kd5ujc.scarab.models.runtime.Codec as ModelCodec
import xyz.kd5ujc.scarab.storage.haskell.state.Record

class CodecDerivationSuite extends FunSuite:
  private final case class Box[A](value: A, rest: Vector[A])
  private enum Choice[A]:
    case One(value: A)
    case Many(values: Vector[A])

  private given box[A](using Codec[A]): Codec[Box[A]] = Codec.auto
  private given choice[A](using Codec[A]): Codec[Choice[A]] = Codec.auto

  private def record[A](codec: ModelCodec[A]): ModelCodec[Box[A]] =
    Record.of[Box[A]]((codec, ModelCodec.vec(codec)))

  test("generic product and sum codecs need no runtime evidence for the field argument") {
    val value = Box(Vector(1, 2), Vector(Vector(3), Vector(4, 5)))
    assertEquals(Codec.decode[Box[Vector[Int]]](Codec.encode(value)), Right(value))
    val cases: Vector[Choice[Vector[String]]] =
      Vector(Choice.One(Vector("a", "b")), Choice.Many(Vector(Vector("c"), Vector("d", "e"))))
    cases.foreach(value =>
      assertEquals(Codec.decode[Choice[Vector[String]]](Codec.encode(value)), Right(value))
    )
  }

  test("field accessors preserve phantom roles without runtime class recovery") {
    import NodeCodecs.given
    val bytes = Bytes.fromArray(Array.fill(28)(7.toByte))
    val pool = KeyHash.fromBytes[StakePool](bytes).fold(e => fail(e.toString), identity)
    val genesis = KeyHash.fromBytes[Genesis](bytes).fold(e => fail(e.toString), identity)
    val pools = Box(pool, Vector(pool))
    val geneses = Box(genesis, Vector(genesis))
    assertEquals(Codec.decode[Box[KeyHash[StakePool]]](Codec.encode(pools)), Right(pools))
    assertEquals(Codec.decode[Box[KeyHash[Genesis]]](Codec.encode(geneses)), Right(geneses))
    assert(java.util.Arrays.equals(Codec.encode(pools), Codec.encode(geneses)))
  }

  test("generated accessors reject wrong generic arguments and phantom roles at compile time") {
    val generic = typeCheckErrors("""
      import xyz.kd5ujc.scarab.storage.ProductFields
      case class Holder[A](value: Vector[A])
      ProductFields.named[Holder[Int], Vector[String]]("value")
    """)
    val role = typeCheckErrors("""
      import xyz.kd5ujc.scarab.storage.ProductFields
      import xyz.kd5ujc.scarab.ledger.state.{KeyHash, StakePool, Genesis}
      case class Holder[A](value: KeyHash[A])
      ProductFields.named[Holder[StakePool], KeyHash[Genesis]]("value")
    """)
    assert(generic.nonEmpty)
    assert(role.nonEmpty)
  }

  test("Record projects generic fields directly and preserves nested error paths") {
    val codec = record(ModelCodec.uintBig(100))
    val value = Box(BigInt(7), Vector(BigInt(9)))
    assertEquals(codec.decodeBytes(codec.encodeUnchecked(value)), Right(value))
    val invalid = record(ModelCodec.text).encodeUnchecked(Box("bad", Vector("value")))
    val failure = codec.decodeBytes(invalid)
    assert(failure.isLeft)
    assertEquals(failure.left.toOption.map(_.path), Some(List("value")))
  }
