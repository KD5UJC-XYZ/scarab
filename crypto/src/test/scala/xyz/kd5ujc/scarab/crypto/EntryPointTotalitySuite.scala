// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto

import com.weavechain.curve25519.{CompressedEdwardsY, Scalar}
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import scala.util.{Failure, Random, Success, Try}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.{Sum6Kes, Sum6KesSigning}
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosVrfCertificate, StrictDraft03}
import xyz.kd5ujc.scarab.crypto.witness.{ByronEd25519, StrictEd25519}

/** Every entry point of `crypto` that reaches its Java sources (the vendored curve arithmetic and
  * the verification and VRF kernels written against it) returns a value for every input: a verdict,
  * a refusal or a typed defect, never an exception. The inputs are bytes of every length (none, the
  * lengths either side of each fixed size: 31 to 33, 63 to 65, 79 to 81, 447 to 449 and 607 to 609,
  * and longer ones) and of any content (zeros, ones, random), integers past every range the entry
  * points check, and inputs one bit away from accepted ones, which pass the length and decoding
  * checks and reach the arithmetic behind them. Which value comes back is the business of the
  * conformance suites, not of this one.
  */
class EntryPointTotalitySuite extends munit.ScalaCheckSuite:

  /** `body` gives a value, not null: an exception it throws is the failure. */
  private def returns[A](what: => String)(body: => A): Unit =
    Try(body) match
      case Success(value) => assert(value != null, s"$what returned null")
      case Failure(e)     => fail(s"$what threw", e)

  private val boundaries =
    List(0, 1, 16, 31, 32, 33, 63, 64, 65, 79, 80, 81, 447, 448, 449, 607, 608, 609)

  private val length: Gen[Int] =
    Gen.frequency(3 -> Gen.oneOf(boundaries), 1 -> Gen.choose(0, 2048))

  private def filled(n: Int): Gen[Array[Byte]] =
    Gen.oneOf(
      Gen.const(Array.fill[Byte](n)(0)),
      Gen.const(Array.fill[Byte](n)(-1)),
      Gen.long.map { seed =>
        val bytes = new Array[Byte](n)
        new Random(seed).nextBytes(bytes)
        bytes
      }
    )

  private val anyArray: Gen[Array[Byte]] = length.flatMap(filled)
  private val anyBytes: Gen[Bytes] = anyArray.map(Bytes.fromArray)

  /** Bytes of any length half the time, and the other half exactly `n` of them, of any content. */
  private def around(n: Int): Gen[Bytes] = Gen.oneOf(anyBytes, filled(n).map(Bytes.fromArray))

  private val anyInt: Gen[Int] =
    Gen.frequency(
      1 -> Gen.oneOf(Int.MinValue, -1, 0, 1, 31, 32, 62, 63, 64, Int.MaxValue),
      1 -> Gen.choose(0, 63),
      1 -> Gen.choose(Int.MinValue, Int.MaxValue)
    )

  private val anyBigInt: Gen[BigInt] =
    Gen.frequency(
      1 -> Gen.oneOf(
        BigInt(-1),
        BigInt(0),
        BigInt(63),
        BigInt(64),
        BigInt(Int.MaxValue) + 1,
        (BigInt(1) << 64) - 1,
        BigInt(1) << 64,
        -(BigInt(1) << 100)
      ),
      1 -> Gen.choose(0, 63).map(BigInt(_)),
      1 -> Gen.long.map(BigInt(_))
    )

  /** `bytes` with one bit flipped, drawn anywhere in them. */
  private def flipped(bytes: Bytes): Gen[Bytes] =
    for
      at <- Gen.choose(0, bytes.size - 1)
      bit <- Gen.choose(0, 7)
    yield
      val copy = bytes.toArray
      copy(at) = (copy(at) ^ (1 << bit)).toByte
      Bytes.fromArray(copy)

  private def seed(fill: Int): Array[Byte] = Array.tabulate[Byte](32)(i => (fill + 7 * i).toByte)

  // an Ed25519 key and a signature it made, each verified by the reference's rules
  private val edSecret = seed(1)
  private val edKey: Bytes =
    val pk = new Array[Byte](32)
    Ed25519.generatePublicKey(edSecret, 0, pk, 0)
    Bytes.fromArray(pk)
  private def edSigned(message: Array[Byte]): Bytes =
    val signature = new Array[Byte](64)
    Ed25519.sign(edSecret, 0, message, 0, message.length, signature, 0)
    Bytes.fromArray(signature)
  private val edMessage = "a message".getBytes("US-ASCII")
  private val edSignature = edSigned(edMessage)

  // a VRF key, and a proof and output it made
  private val vrfKey: Draft03Prover.SigningKey =
    Draft03Prover.SigningKey.fromSeed(Bytes.fromArray(seed(2))).fold(e => fail(s"$e"), identity)
  private val vrfAlpha = Bytes.fromArray("alpha".getBytes("US-ASCII"))
  private val vrfProved: Draft03Prover.Proved =
    vrfKey.prove(vrfAlpha).fold(e => fail(s"$e"), identity)

  // a KES key at period 0, its verification key and a signature it made
  private val kesKey: Sum6KesSigning.SigningKey =
    Sum6KesSigning.generate(Bytes.fromArray(seed(3))).fold(e => fail(s"$e"), identity)
  private val kesMessage = Bytes.fromArray("a header body".getBytes("US-ASCII"))
  private val kesSignature: Bytes = kesKey.sign(kesMessage).fold(e => fail(s"$e"), identity)

  property(
    "Ed25519 verification, strict and Byron, returns a value for any key, signature and message"
  ) {
    forAllNoShrink(around(32), around(64), anyBytes) { (key, signature, message) =>
      val what = s"key ${key.size}, signature ${signature.size}, message ${message.size} bytes"
      returns(s"StrictEd25519.verify of $what")(StrictEd25519.verify(key, signature, message))
      returns(s"ByronEd25519.verify of $what")(ByronEd25519.verify(key, signature, message))
    }
  }

  property("Ed25519 verification returns a value one bit from an accepted key or signature") {
    forAllNoShrink(flipped(edKey), flipped(edSignature), anyBytes) { (key, signature, message) =>
      returns("StrictEd25519.verify, key flipped")(StrictEd25519.verify(key, edSignature, message))
      returns("StrictEd25519.verify, signature flipped")(
        StrictEd25519.verify(edKey, signature, message)
      )
      returns("ByronEd25519.verify, key flipped")(ByronEd25519.verify(key, edSignature, message))
      returns("ByronEd25519.verify, signature flipped")(
        ByronEd25519.verify(edKey, signature, message)
      )
    }
  }

  property("KES verification returns a value for any root, period, message and signature") {
    forAllNoShrink(around(32), anyInt, anyBytes, around(448)) {
      (root, period, message, signature) =>
        returns(
          s"Sum6Kes.verify of a ${root.size}-byte root at $period, ${signature.size}-byte signature"
        )(
          Sum6Kes.verify(root, period, message, signature)
        )
    }
  }

  property("KES verification returns a value one bit from an accepted signature") {
    forAllNoShrink(flipped(kesSignature), anyInt, anyBytes) { (signature, period, message) =>
      val root = kesKey.verificationKey
      returns(s"Sum6Kes.verify at $period")(Sum6Kes.verify(root, period, message, signature))
      returns(s"Sum6Kes.verify of the signed message at $period")(
        Sum6Kes.verify(root, period, kesMessage, signature)
      )
    }
  }

  property("operational certificate verification returns a value for any input") {
    forAllNoShrink(around(32), around(32), anyBigInt, anyBigInt, around(64)) {
      (coldKey, hotKey, counter, startPeriod, signature) =>
        returns(s"OperationalCertificate.verify, ${coldKey.size}-byte cold key")(
          OperationalCertificate.verify(coldKey, hotKey, counter, startPeriod, signature)
        )
    }
  }

  property("operational certificate verification returns a value one bit from a signed one") {
    forAllNoShrink(filled(32), Gen.choose(0L, Long.MaxValue), Gen.choose(0L, Long.MaxValue)) {
      (hot, counter, start) =>
        def be8(n: Long) = Array.tabulate[Byte](8)(i => (n >> (8 * (7 - i))).toByte)
        val signed = edSigned(hot ++ be8(counter) ++ be8(start))
        forAllNoShrink(flipped(signed)) { signature =>
          returns("OperationalCertificate.verify")(
            OperationalCertificate
              .verify(edKey, Bytes.fromArray(hot), BigInt(counter), BigInt(start), signature)
          )
        }
    }
  }

  property("VRF verification returns a value for any key, proof, alpha and claimed output") {
    forAllNoShrink(around(32), around(80), anyBytes, around(64)) { (key, proof, alpha, claimed) =>
      val what = s"key ${key.size}, proof ${proof.size}, alpha ${alpha.size} bytes"
      returns(s"StrictDraft03.verify of $what")(StrictDraft03.verify(key, proof, alpha))
      returns(s"PraosVrfCertificate.verifyAlpha of $what")(
        PraosVrfCertificate.verifyAlpha(alpha, key, proof, claimed)
      )
    }
  }

  property("VRF verification returns a value one bit from an accepted key or proof") {
    forAllNoShrink(flipped(vrfKey.publicKey), flipped(vrfProved.proof), anyBytes) {
      (key, proof, alpha) =>
        val output = vrfProved.output
        returns("StrictDraft03.verify, key flipped")(
          StrictDraft03.verify(key, vrfProved.proof, vrfAlpha)
        )
        returns("StrictDraft03.verify, proof flipped")(
          StrictDraft03.verify(vrfKey.publicKey, proof, vrfAlpha)
        )
        returns("StrictDraft03.verify, any alpha")(
          StrictDraft03.verify(vrfKey.publicKey, proof, alpha)
        )
        returns("PraosVrfCertificate.verifyAlpha, proof flipped")(
          PraosVrfCertificate.verifyAlpha(vrfAlpha, vrfKey.publicKey, proof, output)
        )
    }
  }

  property("the Praos VRF input and certificate return a value for any slot, nonce and proof") {
    forAllNoShrink(anyBigInt, Gen.option(around(32)), around(80), around(64)) {
      (slot, nonce, proof, claimed) =>
        val built =
          for
            s <- PraosVrfCertificate.Slot.fromBigInt(slot)
            n <- nonce.fold[Either[PraosVrfCertificate.Failure, PraosVrfCertificate.EpochNonce]](
              Right(PraosVrfCertificate.NeutralNonce)
            )(PraosVrfCertificate.Hash32.fromBytes)
            input <- PraosVrfCertificate.Input.create(s, n)
          yield input
        returns(s"the Praos VRF input of slot $slot")(built)
        built.foreach { input =>
          returns("PraosVrfCertificate.alpha")(PraosVrfCertificate.alpha(input))
          returns("PraosVrfCertificate.verify")(
            PraosVrfCertificate.verify(input, vrfKey.publicKey, proof, claimed)
          )
        }
    }
  }

  property("a VRF signing key is read from any bytes, and proves over any alpha, as a value") {
    forAllNoShrink(around(32), around(64), anyArray, flipped(vrfKey.toBytes), anyBytes) {
      (seed, bytes, payload, near, alpha) =>
        returns(s"Draft03Prover.SigningKey.fromSeed of ${seed.size} bytes")(
          Draft03Prover.SigningKey.fromSeed(seed)
        )
        returns(s"Draft03Prover.SigningKey.fromBytes of ${bytes.size} bytes")(
          Draft03Prover.SigningKey.fromBytes(bytes)
        )
        returns("Draft03Prover.SigningKey.fromBytes, one bit from a key")(
          Draft03Prover.SigningKey.fromBytes(near)
        )
        returns(s"Draft03Prover.SigningKey.fromPayload of ${payload.length} bytes")(
          Draft03Prover.SigningKey.fromPayload(payload.clone())
        )
        returns(s"prove over ${alpha.size} bytes")(vrfKey.prove(alpha))
        returns(s"output over ${alpha.size} bytes")(vrfKey.output(alpha))
    }
  }

  property("a KES signing key is made and read from any bytes as a value") {
    forAllNoShrink(around(32), around(608), anyArray, anyInt) { (seed, bytes, payload, period) =>
      returns(s"Sum6KesSigning.generate of ${seed.size} bytes")(Sum6KesSigning.generate(seed))
      returns(s"Sum6KesSigning.fromBytes of ${bytes.size} bytes at $period")(
        Sum6KesSigning.fromBytes(bytes, period)
      )
      returns(s"Sum6KesSigning.fromEvolved of ${bytes.size} bytes")(
        Sum6KesSigning.fromEvolved(bytes)
      )
      returns(s"Sum6KesSigning.fromEvolvedPayload of ${payload.length} bytes")(
        Sum6KesSigning.fromEvolvedPayload(payload.clone())
      )
    }
  }

  property("a KES signing key one bit from a valid one is read as a value") {
    forAllNoShrink(flipped(kesKey.toBytes), anyInt) { (near, period) =>
      returns(s"Sum6KesSigning.fromBytes at $period")(Sum6KesSigning.fromBytes(near, period))
      returns("Sum6KesSigning.fromEvolved")(Sum6KesSigning.fromEvolved(near))
      returns("Sum6KesSigning.fromEvolvedPayload")(Sum6KesSigning.fromEvolvedPayload(near.toArray))
    }
  }

  property("a KES signing key signs any message and evolves to any target as a value") {
    forAllNoShrink(anyBytes, anyBigInt) { (message, target) =>
      val key = kesKey.copy
      returns(s"sign of ${message.size} bytes")(key.sign(message))
      returns(s"evolveTo $target")(key.evolveTo(target))
      returns(s"evolve after evolveTo $target")(key.evolve())
      returns(s"sign at period ${key.period}")(key.sign(message))
      key.forget()
      returns("sign once forgotten")(key.sign(message))
      returns("evolve once forgotten")(key.evolve())
    }
  }

  property("the vendored decoders return a value for any bytes") {
    forAllNoShrink(anyArray, filled(32)) { (scalar, point) =>
      returns(s"Scalar.fromCanonicalBytes of ${scalar.length} bytes")(
        Scalar.fromCanonicalBytes(scalar.clone())
      )
      returns("CompressedEdwardsY.decompress")(new CompressedEdwardsY(point.clone()).decompress())
    }
  }

  test("inputs past the size limits the entry points check return a value") {
    // one input each, just over StrictDraft03's limit on alpha (1 MiB) and Sum6Kes's on the message
    val alpha = Bytes.fromArray(new Array[Byte](StrictDraft03.MaxAlphaBytes + 1))
    val message = Bytes.fromArray(new Array[Byte](Sum6Kes.MaxMessageBytes + 1))
    returns("StrictDraft03.verify over 1 MiB")(
      StrictDraft03.verify(vrfKey.publicKey, vrfProved.proof, alpha)
    )
    returns("PraosVrfCertificate.verifyAlpha over 1 MiB")(
      PraosVrfCertificate.verifyAlpha(alpha, vrfKey.publicKey, vrfProved.proof, vrfProved.output)
    )
    returns("prove over 1 MiB")(vrfKey.prove(alpha))
    returns("Sum6Kes.verify over 64 KiB")(
      Sum6Kes.verify(kesKey.verificationKey, 0, message, kesSignature)
    )
    returns("sign over 64 KiB")(kesKey.copy.sign(message))
    returns("StrictEd25519.verify over 1 MiB")(StrictEd25519.verify(edKey, edSignature, alpha))
    returns("ByronEd25519.verify over 1 MiB")(ByronEd25519.verify(edKey, edSignature, alpha))
  }
