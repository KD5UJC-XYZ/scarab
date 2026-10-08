// SPDX-License-Identifier: Apache-2.0
package lab.witness

import lab.cbor.Bytes

class WitnessInputSuite extends munit.FunSuite:
  import WitnessInputError.*

  private def checkOwnership[A](
      size: Int,
      construct: Array[Byte] => Either[WitnessInputError, A],
      outputArray: A => Array[Byte]
  ): Unit =
    val source = Array.tabulate[Byte](size)(_.toByte)
    val original = source.toVector
    val value = construct(source).toOption.get
    val equal = construct(source.clone()).toOption.get
    val beforeHash = value.hashCode()
    assertEquals(value, equal)
    assertEquals(value.hashCode(), equal.hashCode())
    assertEquals(Set(value, equal).size, 1)
    source(0) = (source(0) ^ 1).toByte
    assertEquals(outputArray(value).toVector, original)
    val exposed = outputArray(value)
    exposed(1) = (exposed(1) ^ 1).toByte
    assertEquals(outputArray(value).toVector, original)
    assertEquals(value, equal)
    assertEquals(value.hashCode(), beforeHash)
    assertNotEquals(value, construct(source).toOption.get)

  test("fixed-width public values own source and exported arrays and have byte equality") {
    checkOwnership[PublicKey32](32, PublicKey32.fromArray, _.toArray)
    checkOwnership[Signature64](64, Signature64.fromArray, _.toArray)
    checkOwnership[BodyHash32](32, BodyHash32.fromArray, _.toArray)
  }

  test("Bytes constructors and array constructors have equal values without type confusion") {
    val bytes32 = Bytes.fromArray(Array.tabulate[Byte](32)(_.toByte))
    val bytes64 = Bytes.fromArray(Array.tabulate[Byte](64)(_.toByte))
    val key = PublicKey32.create(bytes32).toOption.get
    val signature = Signature64.create(bytes64).toOption.get
    val hash = BodyHash32.create(bytes32).toOption.get
    assertEquals(key, PublicKey32.fromArray(bytes32.toArray).toOption.get)
    assertEquals(signature, Signature64.fromArray(bytes64.toArray).toOption.get)
    assertEquals(hash, BodyHash32.fromArray(bytes32.toArray).toOption.get)
    assert(!key.equals(hash))
    assert(!hash.equals(key))
    assert(!signature.equals(bytes64))
    assert(!key.equals(null))
    assert(!signature.equals(null))
    assert(!hash.equals(null))
    key.bytes.toArray(0) = 99.toByte
    signature.bytes.toArray(0) = 99.toByte
    hash.bytes.toArray(0) = 99.toByte
    assertEquals(key.bytes, bytes32)
    assertEquals(signature.bytes, bytes64)
    assertEquals(hash.bytes, bytes32)
  }

  test("31/33-byte public keys and body hashes and 63/65-byte signatures are malformed") {
    Vector(0, 31, 33).foreach { size =>
      val input = Array.fill[Byte](size)(0)
      assertEquals(PublicKey32.fromArray(input), Left(MalformedPublicKeyLength(size)))
      assertEquals(PublicKey32.create(Bytes.fromArray(input)), Left(MalformedPublicKeyLength(size)))
      assertEquals(BodyHash32.fromArray(input), Left(MalformedBodyHashLength(size)))
      assertEquals(BodyHash32.create(Bytes.fromArray(input)), Left(MalformedBodyHashLength(size)))
    }
    Vector(0, 63, 65).foreach { size =>
      val input = Array.fill[Byte](size)(0)
      assertEquals(Signature64.fromArray(input), Left(MalformedSignatureLength(size)))
      assertEquals(Signature64.create(Bytes.fromArray(input)), Left(MalformedSignatureLength(size)))
    }
  }

  test("correct width is a structural check and does not promise a valid point or signature") {
    assert(PublicKey32.fromArray(new Array[Byte](32)).isRight)
    assert(Signature64.fromArray(new Array[Byte](64)).isRight)
    assert(BodyHash32.fromArray(new Array[Byte](32)).isRight)
  }
