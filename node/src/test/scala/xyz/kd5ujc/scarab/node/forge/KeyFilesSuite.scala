// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.ForgeError
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
