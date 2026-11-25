// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.{ForgeError, ForgeKeyProblem}
import xyz.kd5ujc.scarab.crypto.opcert.OperationalCertificate
import xyz.kd5ujc.scarab.crypto.vrf.Draft03Prover

class KeyFilesSuite extends munit.FunSuite:
  private def envelope(typ: String, cbor: String) =
    s"""{"type": "$typ", "description": "", "cborHex": "$cbor"}"""
  private def hex(s: String) = Bytes.fromHex(s).toOption.get
  private def bstr(b: Bytes) =
    val n = b.size
    val head = if n < 24 then f"${0x40 + n}%02x" else if n < 256 then f"58$n%02x" else f"59${n}%04x"
    head + b.hex

  // The operational certificate `cardano-cli` wrote for pool 1 of a cardano-testnet sandbox (public).
  private val realOpCert =
    "828458204eddc30873000b596da9c7a6653bc8329548fe4b59b5e59f1c179bcde1de67f000005840dec25fe39031b55d4d4f7a9af824abfda1af877d5630ec96c9b928170a8d091663f83fdb42300230dfccc3c2c10eae20ce95a482e5b129912858bfecd32f66065820cddbd57340c9bb8e2e20e892420f55b4e7a0745c3fac3c46d30eb4312ba5340a"

  test("a cardano-cli operational certificate loads and its signature verifies") {
    val (oc, cold) =
      KeyFiles.opCert("opcert.cert", envelope(KeyFiles.OpCertType, realOpCert)).toOption.get
    assertEquals(oc.hotVkey.hex, "4eddc30873000b596da9c7a6653bc8329548fe4b59b5e59f1c179bcde1de67f0")
    assertEquals(oc.sequence, BigInt(0))
    assertEquals(oc.kesPeriod, BigInt(0))
    assertEquals(cold.hex, "cddbd57340c9bb8e2e20e892420f55b4e7a0745c3fac3c46d30eb4312ba5340a")
    assertEquals(
      OperationalCertificate.verify(cold, oc.hotVkey, oc.sequence, oc.kesPeriod, oc.sigma),
      Right(OperationalCertificate.Result.OperationalCertificateSignatureVerified)
    )
  }

  test("VRF and KES signing keys round-trip through the cardano-cli envelope layout") {
    val vrf = Draft03Prover.SigningKey.fromSeed(hex("01" * 32)).toOption.get
    val vrfText = envelope(KeyFiles.VrfSigningKeyType, bstr(vrf.toBytes))
    assertEquals(vrf.toBytes.size, 64)
    assert(bstr(vrf.toBytes).startsWith("5840"))
    assertEquals(
      KeyFiles.vrfSigningKey("vrf.skey", vrfText).toOption.get.publicKey,
      vrf.publicKey
    )
    val kes = Sum6KesSigning.generate(hex("02" * 32)).toOption.get
    assert(bstr(kes.toBytes).startsWith("590260"))
    val kesText = envelope(KeyFiles.KesSigningKeyType, bstr(kes.toBytes))
    assertEquals(
      KeyFiles.kesSigningKey("kes.skey", kesText).toOption.get.verificationKey,
      kes.verificationKey
    )
  }

  test("wrong types and bad JSON are typed KeyFile errors") {
    def isKeyFile(e: Either[ForgeError, ?]) = e.left.exists(_.isInstanceOf[ForgeError.KeyFile])
    val kes = Sum6KesSigning.generate(hex("02" * 32)).toOption.get
    assert(
      isKeyFile(
        KeyFiles.vrfSigningKey("f", envelope(KeyFiles.KesSigningKeyType, bstr(kes.toBytes)))
      )
    )
    assert(isKeyFile(KeyFiles.vrfSigningKey("f", "not json")))
    assert(
      isKeyFile(
        KeyFiles.vrfSigningKey("f", envelope(KeyFiles.VrfSigningKeyType, "5840" + "00" * 64))
      )
    )
    assert(isKeyFile(KeyFiles.opCert("f", envelope(KeyFiles.OpCertType, "8200"))))
  }

  test("malformed key envelopes retain shape without retaining payload bytes") {
    val payload = hex("ab" * 32)
    val result = KeyFiles.vrfSigningKey(
      "synthetic.skey",
      envelope(KeyFiles.VrfSigningKeyType, "81" + bstr(payload))
    )
    assertEquals(
      result,
      Left(
        ForgeError.KeyFile(
          "synthetic.skey",
          xyz.kd5ujc.scarab.ledger.ForgeKeyProblem.ExpectedByteString(
            xyz.kd5ujc.scarab.ledger.KeyShape.Array(1)
          )
        )
      )
    )
    assert(!result.toString.contains(payload.hex))
  }

  test("KES key-file errors preserve the crypto cause until presentation") {
    val cases = Vector(
      Bytes.empty -> Sum6KesSigning.Failure.InvalidKeyLength(0),
      hex("00" * 608) -> Sum6KesSigning.Failure.VerificationKeyMismatch(Sum6KesSigning.Depth)
    )
    cases.foreach { case (bytes, cause) =>
      val result =
        KeyFiles.kesSigningKey("kes.skey", envelope(KeyFiles.KesSigningKeyType, bstr(bytes)))
      val expected = ForgeError.KesKey("kes.skey", cause)
      assertEquals(result, Left(expected))
      assertEquals(expected.message, s"key file kes.skey: ${cause.message}")
    }
  }

  test("credentials whose certificate names another KES key are refused") {
    val (oc, cold) =
      KeyFiles.opCert("opcert.cert", envelope(KeyFiles.OpCertType, realOpCert)).toOption.get
    val credentials = PoolCredentials(
      Draft03Prover.SigningKey.fromSeed(hex("01" * 32)).toOption.get,
      Sum6KesSigning.generate(hex("02" * 32)).toOption.get,
      oc,
      cold
    )
    assertEquals(
      PoolCredentials.check(credentials),
      Left(ForgeError.KesKeyNotCertified(oc.hotVkey, credentials.kes.verificationKey))
    )
  }

  test(
    "credential checks retain malformed certificate causes and distinguish signature rejection"
  ) {
    val kes = Sum6KesSigning.generate(hex("02" * 32)).fold(e => fail(e.message), identity)
    val c = PoolCredentials(
      Draft03Prover.SigningKey.fromSeed(hex("01" * 32)).fold(e => fail(e.toString), identity),
      kes,
      xyz.kd5ujc.scarab.ledger.Header.OpCert(kes.verificationKey, 0, 0, Bytes.empty),
      hex("03" * 32)
    )
    assertEquals(
      PoolCredentials.check(c),
      Left(
        ForgeError.CertificateFailure(
          OperationalCertificate.Failure.Signature(
            xyz.kd5ujc.scarab.crypto.witness.WitnessInputError.MalformedSignatureLength(0)
          )
        )
      )
    )
    assertEquals(
      PoolCredentials.check(c.copy(opCert = c.opCert.copy(sequence = -1))),
      Left(
        ForgeError.CertificateFailure(
          OperationalCertificate.Failure.InvalidCounter(
            OperationalCertificate.UInt64.Error.OutOfRange(-1)
          )
        )
      )
    )
    assertEquals(
      PoolCredentials.check(c.copy(opCert = c.opCert.copy(sigma = hex("00" * 64)))),
      Left(ForgeError.CertificateSignatureRejected)
    )
  }

  // `--shelley-bls-key` (cardano-node b004fe0c `Cardano/Node/Protocol/Shelley.hs:324-339`): throwaway
  // scalars made here, in the envelope `cardano-cli dijkstra node key-gen-BLS` writes

  private def blsKey(n: Int) =
    xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.right(
      xyz.kd5ujc.scarab.crypto.bls.SecretKey
        .generate(xyz.kd5ujc.scarab.crypto.bls.BlsFixtures.seed(n))
    )
  private def blsEnvelope(n: Int) = envelope(KeyFiles.BlsSigningKeyType, bstr(blsKey(n).toBytes))
  private def scalars(text: String) =
    KeyFiles.blsSigningKeys("bls.skey", text).map(_.map(_.toBytes))

  test("a BLS key file holds one text envelope, or a JSON array of them, or an empty array") {
    assert(bstr(blsKey(1).toBytes).startsWith("5820"))
    assertEquals(scalars(blsEnvelope(1)), Right(Vector(blsKey(1).toBytes)))
    assertEquals(
      scalars(s"[${blsEnvelope(1)}, ${blsEnvelope(2)}]"),
      Right(Vector(blsKey(1).toBytes, blsKey(2).toBytes))
    )
    assertEquals(scalars("[]"), Right(Vector.empty))
  }

  test("a BLS key file of another envelope type, not a scalar, or not envelopes is refused") {
    assertEquals(
      scalars(envelope(KeyFiles.VrfSigningKeyType, bstr(blsKey(1).toBytes))),
      Left(
        ForgeError.KeyFile(
          "bls.skey",
          ForgeKeyProblem.EnvelopeType(KeyFiles.BlsSigningKeyType, KeyFiles.VrfSigningKeyType)
        )
      )
    )
    assert(
      scalars(envelope(KeyFiles.BlsSigningKeyType, bstr(hex("00" * 32)))).left.exists {
        case ForgeError.KeyFile(_, ForgeKeyProblem.Bls(_)) => true
        case _                                             => false
      }
    )
    // an array with an element that is no envelope is read as one envelope, which it is not
    assert(
      scalars(s"[${blsEnvelope(1)}, 5]").left.exists {
        case ForgeError.KeyFile(_, ForgeKeyProblem.Field("type", _)) => true
        case _                                                       => false
      }
    )
  }

  test("a VRF key read for credentials that are not taken is erased; one that is taken is kept") {
    def key() = Draft03Prover.SigningKey.fromSeed(hex("07" * 32)).toOption.get
    val alpha = hex("01")
    val failure: Either[ForgeError, Int] =
      Left(ForgeError.KeyFile("kes.skey", ForgeKeyProblem.OtherPermissions(0x1ff)))
    val refused = key()
    assertEquals(KeyFiles.erasedUnless(refused, failure)(_ => true), failure)
    assertEquals(refused.prove(alpha), Left(Draft03Prover.Failure.MissingSigningKey))
    assertEquals(refused.toBytes.take(32), hex("00" * 32), "the seed after a failure")
    val notKept = key()
    assertEquals(KeyFiles.erasedUnless(notKept, Right(2))(_ == 1), Right(2))
    assertEquals(notKept.output(alpha), Left(Draft03Prover.Failure.MissingSigningKey))
    val kept = key()
    assertEquals(KeyFiles.erasedUnless(kept, Right(1))(_ == 1), Right(1))
    assert(kept.prove(alpha).isRight, "the key the credentials keep was erased")
  }

  test("a VRF key read for credentials whose read throws is erased, and the throw passes on") {
    def key() = Draft03Prover.SigningKey.fromSeed(hex("07" * 32)).toOption.get
    val alpha = hex("01")
    val thrown = key()
    def failing: Either[ForgeError, Int] = throw new IllegalStateException("the read failed")
    intercept[IllegalStateException](KeyFiles.erasedUnless(thrown, failing)(_ => true))
    assertEquals(thrown.prove(alpha), Left(Draft03Prover.Failure.MissingSigningKey))
    val fatal = key()
    def overflowing: Either[ForgeError, Int] = throw new StackOverflowError()
    // munit's intercept does not catch a fatal error, so the suite catches it here
    val caught =
      try
        KeyFiles.erasedUnless(fatal, overflowing)(_ => true)
        None
      catch case e: StackOverflowError => Some(e)
    assert(caught.isDefined, "the fatal error did not pass through")
    assertEquals(fatal.prove(alpha), Left(Draft03Prover.Failure.MissingSigningKey))
  }
