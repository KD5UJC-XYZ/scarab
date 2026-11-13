// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import java.nio.charset.StandardCharsets
import munit.ScalaCheckSuite
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.{ECDomainParameters, ECPrivateKeyParameters}
import org.bouncycastle.crypto.signers.{BIP340Signer, ECDSASigner, HMacDSAKCalculator}
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.DefaultFun
import xyz.kd5ujc.scarab.uplc.DefaultFun.*
import xyz.kd5ujc.scarab.uplc.builtin.EvaluationFailure

import ExtendedTesting.*

/** The hashes (`PlutusCore/Crypto/Hash.hs`) and the signature builtins
  * (`PlutusCore/Crypto/Ed25519.hs`, `Secp256k1.hs`) of plutus 1.70.0.0: known answers, the order of
  * the argument checks, and which problems fail the builtin and which answer `False`.
  */
final class SignaturesSuite extends ScalaCheckSuite:
  private def hex(s: String): Bytes = hexBytes(s)
  private val abc = Bytes.wrap("abc".getBytes(StandardCharsets.US_ASCII))

  test("the hashes: known answers for \"abc\" and the empty string (Hash.hs:37-62)") {
    def h(f: DefaultFun, b: Bytes) = bytesOf(e(f, ExtendedTesting.bytes(b))).hex
    assertEquals(
      h(Sha2_256, abc),
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    )
    assertEquals(
      h(Sha3_256, abc),
      "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532"
    )
    assertEquals(
      h(Blake2b_256, abc),
      "bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319"
    )
    assertEquals(h(Blake2b_224, abc), "9bd237b02a29e43bdd6738afa5b53ff0eee178d6210b618e4511aec8")
    assertEquals(
      h(Keccak_256, abc),
      "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45"
    )
    assertEquals(
      h(Keccak_256, Bytes.empty),
      "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"
    )
    assertEquals(h(Ripemd_160, abc), "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc")
    assertEquals(
      h(Sha2_256, Bytes.empty),
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )
  }

  private def input(f: SignatureInputFailure) =
    EvaluationFailure.SignatureInput(f) -> f.lines

  // RFC 8032, section 7.1, test 1 (the empty message)
  private val edKey = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
  private val edSig =
    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

  test(
    "Ed25519: a verdict for a 32-byte key and a 64-byte signature, else a failure (Ed25519.hs:32-44)"
  ) {
    assert(boolOf(e(VerifyEd25519Signature, bytes(edKey), bytes(""), bytes(edSig))))
    assert(!boolOf(e(VerifyEd25519Signature, bytes(edKey), bytes("00"), bytes(edSig))))
    assert(!boolOf(e(VerifyEd25519Signature, bytes(edKey), bytes(""), bytes("00" * 64))))
    val badKey = SignatureInputFailure.InvalidVerificationKey(SignatureScheme.Ed25519)
    assertEquals(
      failureOf(e(VerifyEd25519Signature, bytes(edKey.drop(2)), bytes(""), bytes(edSig.drop(2)))),
      input(badKey)
    )
    assertEquals(badKey.lines, Vector("Ed25519 signature verification: Invalid verification key."))
    assertEquals(
      failureOf(e(VerifyEd25519Signature, bytes(edKey), bytes(""), bytes(edSig + "00"))),
      input(SignatureInputFailure.InvalidSignature(SignatureScheme.Ed25519))
    )
  }

  private val curve = SECNamedCurves.getByName("secp256k1")
  private val domain = ECDomainParameters(curve.getCurve, curve.getG, curve.getN, curve.getH)
  private val n = BigInt(curve.getN)
  private def be32(v: BigInt): String = f"$v%064x"
  private val p = BigInt(curve.getCurve.getField.getCharacteristic)

  /** The least x whose `x^3 + 7` is not a square modulo p: no point has it. */
  private val offCurve: String = be32(
    Iterator
      .from(1)
      .map(BigInt(_))
      .find(x => (x.pow(3) + 7).modPow((p - 1) / 2, p) != 1)
      .getOrElse(sys.error("no x off the curve"))
  )
  private val sk = BigInt("c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721", 16)
  private val pk = Bytes.wrap(curve.getG.multiply(sk.bigInteger).normalize().getEncoded(true)).hex
  private val msg = "d3444c2dad5f0229ca3e517eddddc8f61f53e804d8a0edb42c2d7bdb5dd05c00"

  /** A valid `(r, s)` with `s` in the low half (RFC 6979 nonces, Bouncy Castle's signer). */
  private def sign(message: String): (BigInt, BigInt) =
    val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
    signer.init(true, ECPrivateKeyParameters(sk.bigInteger, domain))
    signer.generateSignature(hex(message).toArray) match
      case Array(r0, s0) =>
        val s = BigInt(s0)
        (BigInt(r0), if s > n / 2 then n - s else s)
      case other => sys.error(s"signature of ${other.length} parts")

  private def ecdsa(key: String, message: String, signature: String) =
    e(VerifyEcdsaSecp256k1Signature, bytes(key), bytes(message), bytes(signature))
  private def ecdsaFailure(f: SignatureInputFailure) = input(f)
  private val badKey = SignatureInputFailure.InvalidVerificationKey(SignatureScheme.EcdsaSecp256k1)
  private val badSig = SignatureInputFailure.InvalidSignature(SignatureScheme.EcdsaSecp256k1)

  test("ECDSA: key, then signature, then message hash; then a verdict (Secp256k1.hs:53-65)") {
    val (r, s) = sign(msg)
    val sig = be32(r) + be32(s)
    assert(boolOf(ecdsa(pk, msg, sig)))
    assert(!boolOf(ecdsa(pk, "00" * 32, sig)))
    assertEquals(failureOf(ecdsa(pk.drop(2), "00", "00")), ecdsaFailure(badKey))
    assertEquals(failureOf(ecdsa(pk, "00", sig.drop(2))), ecdsaFailure(badSig))
    assertEquals(
      failureOf(ecdsa(pk, msg.drop(2), sig)),
      ecdsaFailure(SignatureInputFailure.InvalidMessageHash)
    )
    assertEquals(
      SignatureInputFailure.InvalidMessageHash.lines,
      Vector("ECDSA SECP256k1 signature verification: Invalid message hash.")
    )
  }

  test("ECDSA: the key is 33 bytes, prefix 02 or 03, x < p, on the curve") {
    val x = pk.drop(2)
    assertEquals(failureOf(ecdsa("04" + x, msg, "00" * 64))._1, ecdsaFailure(badKey)._1)
    assertEquals(failureOf(ecdsa("02" + "ff" * 32, msg, "00" * 64))._1, ecdsaFailure(badKey)._1)
    assertEquals(failureOf(ecdsa("02" + offCurve, msg, "00" * 64))._1, ecdsaFailure(badKey)._1)
    assertEquals(failureOf(ecdsa("03" + offCurve, msg, "00" * 64))._1, ecdsaFailure(badKey)._1)
    val uncompressed =
      Bytes.wrap(curve.getG.multiply(sk.bigInteger).normalize().getEncoded(false)).hex
    assertEquals(failureOf(ecdsa(uncompressed, msg, "00" * 64))._1, ecdsaFailure(badKey)._1)
  }

  test("ECDSA: r or s at least n fail; r = 0, s = 0 or a high s answer False") {
    val (r, s) = sign(msg)
    assertEquals(failureOf(ecdsa(pk, msg, be32(n) + be32(s)))._1, ecdsaFailure(badSig)._1)
    assertEquals(failureOf(ecdsa(pk, msg, be32(r) + be32(n)))._1, ecdsaFailure(badSig)._1)
    assert(!boolOf(ecdsa(pk, msg, "00" * 64)))
    assert(!boolOf(ecdsa(pk, msg, be32(0) + be32(s))))
    assert(!boolOf(ecdsa(pk, msg, be32(r) + be32(0))))
    assert(!boolOf(ecdsa(pk, msg, be32(r) + be32(n - s))))
  }

  property("ECDSA: Bouncy Castle's low-s signatures verify, and not on another message") {
    Prop.forAll(Gen.listOfN(32, Gen.choose(0, 255))) { m =>
      val message = Bytes.wrap(m.map(_.toByte).toArray).hex
      val (r, s) = sign(message)
      assert(boolOf(ecdsa(pk, message, be32(r) + be32(s))))
      val other = Bytes.wrap(m.map(b => (b ^ 1).toByte).toArray).hex
      assert(!boolOf(ecdsa(pk, other, be32(r) + be32(s))))
    }
  }

  private def schnorr(key: String, message: String, signature: String) =
    e(VerifySchnorrSecp256k1Signature, bytes(key), bytes(message), bytes(signature))
  private val schnorrKey =
    SignatureInputFailure.InvalidVerificationKey(SignatureScheme.SchnorrSecp256k1)
  private val schnorrSig = SignatureInputFailure.InvalidSignature(SignatureScheme.SchnorrSecp256k1)

  // BIP-340, test vector 0
  private val spk = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
  private val smsg = "00" * 32
  private val ssig =
    "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0"

  test("Schnorr: BIP-340 vector 0; well-formed wrong signatures are False (Secp256k1.hs:96-106)") {
    assert(boolOf(schnorr(spk, smsg, ssig)))
    assert(!boolOf(schnorr(spk, "01" + "00" * 31, ssig)))
    assert(!boolOf(schnorr(spk, smsg, "00" * 64)))
    assert(!boolOf(schnorr(spk, smsg, "ff" * 64)))
    assert(!boolOf(schnorr(spk, smsg, ssig.take(64) + be32(n))))
    assert(!boolOf(schnorr(spk, smsg, be32(BigInt(1) << 255) + ssig.drop(64))))
    assert(!boolOf(schnorr(spk, "", ssig)))
    assert(!boolOf(schnorr(spk, "07" * 1000, ssig)))
  }

  test("Schnorr: a key that does not lift, or a wrong length, fails; key before signature") {
    for key <- Vector("02" + spk, spk.drop(2), "ff" * 32, offCurve) do
      assertEquals(
        failureOf(schnorr(key, smsg, ssig.drop(2)))._1,
        EvaluationFailure.SignatureInput(schnorrKey),
        key
      )
    assertEquals(failureOf(schnorr(spk, smsg, ssig.drop(2))), input(schnorrSig))
    assertEquals(
      failureOf(schnorr(spk, smsg, ssig + "00"))._1,
      EvaluationFailure.SignatureInput(schnorrSig)
    )
  }

  property("Schnorr: Bouncy Castle's BIP-340 signatures verify, any message length") {
    val gen = for
      key <- Gen.choose(BigInt(1), n - 1)
      m <- Gen.listOf(Gen.choose(0, 255))
    yield (key, m)
    Prop.forAll(gen) { (key, m) =>
      val signer = BIP340Signer()
      val message = m.map(_.toByte).toArray
      signer.init(true, ECPrivateKeyParameters(key.bigInteger, BIP340Signer.getDomain))
      signer.update(message, 0, message.length)
      val signature = Bytes.wrap(signer.generateSignature())
      val xOnly =
        Bytes.wrap(curve.getG.multiply(key.bigInteger).normalize().getAffineXCoord.getEncoded)
      assert(boolOf(schnorr(xOnly.hex, Bytes.wrap(message).hex, signature.hex)))
      assert(!boolOf(schnorr(xOnly.hex, Bytes.wrap(message :+ 1.toByte).hex, signature.hex)))
    }
  }
