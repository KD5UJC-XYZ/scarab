// SPDX-License-Identifier: Apache-2.0
package lab.witness

import java.util.concurrent.{Callable, Executors, TimeUnit}
import lab.cbor.Bytes
import org.bouncycastle.math.ec.rfc8032.Ed25519

class StrictEd25519Suite extends munit.FunSuite:
  import VerificationResult.*
  import WitnessTestData.*

  test("published empty and one-byte message known answers use plain Ed25519") {
    Vector("sodium-valid-0", "sodium-valid-1").foreach { id =>
      val v = vector(id)
      assertEquals(
        StrictEd25519.verify(v.key, v.signature, v.message),
        Right(SignatureVerified),
        id
      )
      assertEquals(
        StrictEd25519.verifyEd25519(v.witness.publicKey, v.witness.signature, v.message),
        Right(SignatureVerified),
        id
      )
    }
  }

  test("local public-input message, R, S, and valid-but-wrong-key mutations fail") {
    val v = vector("sodium-valid-1")
    assertEquals(
      StrictEd25519.verify(v.key, v.signature, flip(v.message, 0)),
      Right(SignatureRejected)
    )
    assertEquals(StrictEd25519.verify(v.key, v.signature, Bytes.empty), Right(SignatureRejected))
    Vector(0, 31, 32, 63).foreach { index =>
      assertEquals(
        StrictEd25519.verify(v.key, flip(v.signature, index), v.message),
        Right(SignatureRejected)
      )
    }
    assertEquals(
      StrictEd25519.verify(vector("sodium-valid-0").key, v.signature, v.message),
      Right(SignatureRejected)
    )
  }

  test("raw verifier reports length errors separately from rejected signatures") {
    val v = vector("sodium-valid-0")
    Vector(31, 33).foreach { size =>
      assertEquals(
        StrictEd25519.verify(Bytes(Vector.fill(size)(0.toByte)), v.signature, v.message),
        Left(WitnessInputError.MalformedPublicKeyLength(size))
      )
    }
    Vector(63, 65).foreach { size =>
      assertEquals(
        StrictEd25519.verify(v.key, Bytes(Vector.fill(size)(0.toByte)), v.message),
        Left(WitnessInputError.MalformedSignatureLength(size))
      )
    }
    assertEquals(
      StrictEd25519
        .verify(Bytes(Vector.fill(32)(0.toByte)), Bytes(Vector.fill(64)(0.toByte)), Bytes.empty),
      Right(SignatureRejected)
    )
  }

  test("noncanonical, off-curve, and small-order A and R encodings are rejected") {
    val v = vector("sodium-valid-0")
    // The seven pinned sodium blacklist y encodings, plus a canonical off-curve y=2.
    val encodings = Vector(
      "order-4 zero" -> ("00" * 32),
      "identity" -> ("01" + "00" * 31),
      "order-8 first" -> "26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
      "order-8 second" -> "c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
      "order-2 p-1" -> ("ec" + "ff" * 30 + "7f"),
      "noncanonical p" -> ("ed" + "ff" * 30 + "7f"),
      "noncanonical p+1" -> ("ee" + "ff" * 30 + "7f"),
      "noncanonical maximum y" -> ("ff" * 31 + "7f"),
      "off-curve y=2" -> ("02" + "00" * 31)
    )
    encodings.foreach { case (label, encoding) =>
      val original = hex(encoding)
      val oppositeSign = Bytes(original.value.updated(31, (original.value(31) ^ 0x80).toByte))
      Vector(original, oppositeSign).foreach { point =>
        assert(PublicKey32.create(point).isRight, label)
        assertEquals(
          StrictEd25519.verify(point, v.signature, v.message),
          Right(SignatureRejected),
          label
        )
        val signature = Bytes(point.value ++ v.signature.value.drop(32))
        assert(Signature64.create(signature).isRight, label)
        assertEquals(
          StrictEd25519.verify(v.key, signature, v.message),
          Right(SignatureRejected),
          label
        )
      }
    }
  }

  test("S equal to L, greater than L, and published S+L malleations fail") {
    val v = vector("sodium-valid-0")
    val order = (BigInt(1) << 252) + BigInt("27742317777372353535851937790883648493")
    Vector(order, order + 1, (BigInt(1) << 256) - 1).foreach { s =>
      val scalar = Vector.tabulate(32)(i => ((s >> (8 * i)) & 255).toByte)
      val signature = Bytes(v.signature.value.take(32) ++ scalar)
      assertEquals(StrictEd25519.verify(v.key, signature, v.message), Right(SignatureRejected))
    }
    Vector("sodium-S-plus-L-0", "sodium-S-plus-L-1").foreach { id =>
      val malformedScalar = vector(id)
      assertEquals(
        StrictEd25519
          .verify(malformedScalar.key, malformedScalar.signature, malformedScalar.message),
        Right(SignatureRejected),
        id
      )
    }
  }

  test("BC cofactored cases 2/4/5 remain rejected by the strict equation") {
    Vector(2, 4, 5).foreach { index =>
      val v = vector(s"speccheck-$index")
      assert(
        Ed25519
          .verify(v.signature.toArray, 0, v.key.toArray, 0, v.message.toArray, 0, v.message.size),
        v.id
      )
      assertEquals(
        StrictEd25519.verify(v.key, v.signature, v.message),
        Right(SignatureRejected),
        v.id
      )
    }
  }

  test("mixed-order speccheck case 3 must not be excluded by a prime-subgroup restriction") {
    val v = vector("speccheck-3")
    assert(!Ed25519.validatePublicKeyFull(v.key.toArray, 0))
    assert(!Ed25519.validatePublicKeyFull(v.signature.value.take(32).toArray, 0))
    assert(
      Ed25519.verify(v.signature.toArray, 0, v.key.toArray, 0, v.message.toArray, 0, v.message.size)
    )
    assertEquals(StrictEd25519.verify(v.key, v.signature, v.message), Right(SignatureVerified))
  }

  test("parallel calls do not share digest or caller-owned mutable input state") {
    val v = vector("sodium-valid-1")
    val pool = Executors.newFixedThreadPool(4)
    try
      val results = Vector.tabulate(32) { index =>
        val message = if index % 2 == 0 then v.message else flip(v.message, 0)
        val expected = if index % 2 == 0 then SignatureVerified else SignatureRejected
        val result = pool.submit(
          new Callable[Either[VerificationError, VerificationResult]]:
            override def call(): Either[VerificationError, VerificationResult] =
              StrictEd25519.verifyEd25519(v.witness.publicKey, v.witness.signature, message)
        )
        result -> expected
      }
      results.foreach { case (result, expected) =>
        assertEquals(result.get(10, TimeUnit.SECONDS), Right(expected))
      }
    finally pool.shutdownNow()
  }

  test("unexpected implementation failures are not disguised as signature rejection") {
    val v = vector("sodium-valid-0")
    // Deliberate programming misuse, not an adversarial byte-input conformance case.
    val result = StrictEd25519.verifyEd25519(null, v.witness.signature, v.message)
    assertEquals(
      result,
      Left(VerificationError.ImplementationFailure("java.lang.NullPointerException"))
    )
  }
