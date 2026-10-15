// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.{ECDomainParameters, ECPrivateKeyParameters}
import org.bouncycastle.crypto.signers.{ECDSASigner, HMacDSAKCalculator}
import scalus.uplc.builtin.{Builtins, ByteString}

/** The two secp256k1 builtins against the reference (`PlutusCore.Crypto.Secp256k1`): a malformed
  * argument fails the builtin, anything well-formed is a verdict. The ECDSA cases that Scalus 1.2.0
  * gets wrong are pinned both ways: the reproducer shows the Scalus behaviour (drop the workaround
  * when it fails), the rest runs [[Secp256k1.verifyEcdsa]]. Preprod transaction 97b31e35
  * (`PreprodSecp256k1RulesSuite`) passes an all-zero signature and expects `False`.
  */
class Secp256k1Suite extends FunSuite:
  private val n = Secp256k1.Order
  private val curve = SECNamedCurves.getByName("secp256k1")
  private val domain = ECDomainParameters(curve.getCurve, curve.getG, curve.getN, curve.getH)

  private def be32(v: BigInt): Array[Byte] =
    val b = v.toByteArray.dropWhile(_ == 0)
    require(b.length <= 32)
    Array.fill(32 - b.length)(0.toByte) ++ b

  private def hex(h: String): Array[Byte] = h.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private val sk =
    BigInt(1, hex("c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721"))
  private val pk: Array[Byte] = curve.getG.multiply(sk.bigInteger).getEncoded(true)
  private val msg: Array[Byte] = hex(
    "d3444c2dad5f0229ca3e517eddddc8f61f53e804d8a0edb42c2d7bdb5dd05c00"
  )

  /** A valid (r, s) with s in the low half, deterministic (RFC 6979). */
  private val (r, s) =
    val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
    signer.init(true, ECPrivateKeyParameters(sk.bigInteger, domain))
    val Array(r0, s0) = signer.generateSignature(msg)
    val s1 = if BigInt(s0) > (n >> 1) then n.bigInteger.subtract(s0) else s0
    (BigInt(r0), BigInt(s1))

  private def sig(r: BigInt, s: BigInt): Array[Byte] = be32(r) ++ be32(s)
  private def ecdsa(pk: Array[Byte] = pk, msg: Array[Byte] = msg, sig: Array[Byte] = sig(r, s)) =
    Secp256k1.verifyEcdsa(pk, msg, sig)

  private def fails(body: => Any, label: String)(using munit.Location): Unit =
    val _ = intercept[IllegalArgumentException](clue(label) match { case _ => body })

  test("a valid signature verifies, a signature on another message does not") {
    assert(ecdsa())
    assert(!ecdsa(msg = msg.updated(0, (msg(0) ^ 1).toByte)))
    assert(!ecdsa(sig = sig(r, s + 1)))
  }

  test("r = 0 or s = 0 parse and verify to False (the case of preprod 97b31e35)") {
    assert(!ecdsa(sig = Array.fill(64)(0.toByte)))
    assert(!ecdsa(sig = sig(0, s)))
    assert(!ecdsa(sig = sig(r, 0)))
  }

  test("r or s at least n do not parse: the builtin fails") {
    fails(ecdsa(sig = sig(n, s)), "r = n")
    fails(ecdsa(sig = sig(r, n)), "s = n")
    fails(ecdsa(sig = sig(n + 1, s)), "r = n + 1")
    fails(ecdsa(sig = Array.fill(64)(0xff.toByte)), "all ones")
    // n - 1 does parse
    assert(!ecdsa(sig = sig(n - 1, s)))
  }

  test(
    "a high s is False: libsecp256k1 verifies only normalised signatures and Plutus does not normalise"
  ) {
    assert(ecdsa(sig = sig(r, s)))
    assert(!ecdsa(sig = sig(r, n - s)))
  }

  test("key: 33 bytes, prefix 02 or 03, a point on the curve; anything else fails") {
    fails(ecdsa(pk = pk.updated(0, 0x04.toByte)), "prefix 04 with 33 bytes")
    fails(ecdsa(pk = pk.updated(0, 0x00.toByte)), "prefix 00")
    fails(ecdsa(pk = pk.updated(0, 0x05.toByte)), "prefix 05")
    fails(ecdsa(pk = Array.fill(33)(0.toByte)), "zero key")
    fails(ecdsa(pk = 0x02.toByte +: Array.fill(32)(0xff.toByte)), "x at least the field prime")
    val offCurve = Iterator
      .from(1)
      .map(x => 0x02.toByte +: be32(BigInt(x)))
      .find(b => scala.util.Try(curve.getCurve.decodePoint(b)).isFailure)
      .get
    fails(ecdsa(pk = offCurve), "x off the curve")
    // the other parity of a valid key is a different valid key: a verdict
    assert(!ecdsa(pk = pk.updated(0, (pk(0) ^ 1).toByte)))
  }

  test("lengths: key 33, message 32, signature 64; anything else fails") {
    fails(ecdsa(pk = pk.take(32)), "key 32")
    fails(ecdsa(pk = pk :+ 0.toByte), "key 34")
    fails(ecdsa(pk = curve.getG.multiply(sk.bigInteger).getEncoded(false)), "uncompressed key")
    fails(ecdsa(pk = Array.empty[Byte]), "empty key")
    fails(ecdsa(msg = msg.take(31)), "message 31")
    fails(ecdsa(msg = msg :+ 0.toByte), "message 33")
    fails(ecdsa(msg = Array.empty[Byte]), "empty message")
    fails(ecdsa(sig = sig(r, s).take(63)), "signature 63")
    fails(ecdsa(sig = sig(r, s) :+ 0.toByte), "signature 65")
    fails(ecdsa(sig = Array.empty[Byte]), "empty signature")
  }

  test("reproducer: Scalus 1.2.0 fails on r = 0 or s = 0 where the reference answers False") {
    def scalus(sig: Array[Byte]) =
      Builtins.verifyEcdsaSecp256k1Signature(
        ByteString.fromArray(pk),
        ByteString.fromArray(msg),
        ByteString.fromArray(sig)
      )
    assert(scalus(sig(r, s)))
    // when this stops failing, remove the override of verifyEcdsaSecp256k1Signature
    intercept[Exception](scalus(Array.fill(64)(0.toByte)))
    intercept[Exception](scalus(sig(0, s)))
    intercept[Exception](scalus(sig(r, 0)))
    assert(!scalus(sig(r, n - s)))
  }

  // ---- Schnorr (BIP-340): Scalus agrees with the reference ----

  private def schnorr(pk: Array[Byte], msg: Array[Byte], sig: Array[Byte]) =
    Builtins.verifySchnorrSecp256k1Signature(
      ByteString.fromArray(pk),
      ByteString.fromArray(msg),
      ByteString.fromArray(sig)
    )

  // BIP-340 test vector 0
  private val spk = hex("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9")
  private val smsg = Array.fill(32)(0.toByte)
  private val ssig = hex(
    "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA821525F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0"
  )

  test("Schnorr: a valid signature verifies; well-formed wrong ones are False, never a failure") {
    assert(schnorr(spk, smsg, ssig))
    assert(!schnorr(spk, smsg.updated(0, 1.toByte), ssig))
    assert(!schnorr(spk, smsg, Array.fill(64)(0.toByte)))
    assert(!schnorr(spk, smsg, Array.fill(64)(0xff.toByte)))
    assert(!schnorr(spk, smsg, ssig.take(32) ++ be32(n)))
    assert(!schnorr(spk, smsg, be32(BigInt(1) << 255) ++ ssig.drop(32)))
    assert(!schnorr(spk, Array.empty[Byte], ssig))
    assert(!schnorr(spk, Array.fill(1000)(7.toByte), ssig))
  }

  test("Schnorr: a key that is not an x coordinate of a point, or a wrong length, fails") {
    val offCurve = Iterator
      .from(1)
      .map(x => be32(BigInt(x)))
      .find(b => scala.util.Try(curve.getCurve.decodePoint(0x02.toByte +: b)).isFailure)
      .get
    intercept[Exception](schnorr(offCurve, smsg, ssig))
    intercept[Exception](schnorr(Array.fill(32)(0xff.toByte), smsg, ssig))
    intercept[Exception](schnorr(Array.fill(32)(0.toByte), smsg, ssig))
    intercept[Exception](schnorr(spk.take(31), smsg, ssig))
    intercept[Exception](schnorr(spk :+ 0.toByte, smsg, ssig))
    intercept[Exception](schnorr(spk, smsg, ssig.take(63)))
    intercept[Exception](schnorr(spk, smsg, ssig :+ 0.toByte))
  }
