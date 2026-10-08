// SPDX-License-Identifier: Apache-2.0
package lab.vm

import scalus.uplc.builtin.*
import scalus.uplc.builtin.bls12_381.*
private[vm] final class UnsupportedBackend(operation: String) extends RuntimeException(operation)

private[vm] object UnsupportedPlatform extends PlatformSpecific:
  def sha2_256(bs: ByteString): ByteString = throw new UnsupportedBackend(
    "UnsupportedPlatform:sha2_256"
  )
  def sha2_512(bs: ByteString): ByteString = throw new UnsupportedBackend(
    "UnsupportedPlatform:sha2_512"
  )
  def sha3_256(bs: ByteString): ByteString = throw new UnsupportedBackend(
    "UnsupportedPlatform:sha3_256"
  )
  def blake2b_224(bs: ByteString): ByteString = throw new UnsupportedBackend(
    "UnsupportedPlatform:blake2b_224"
  )
  def blake2b_256(bs: ByteString): ByteString = throw new UnsupportedBackend(
    "UnsupportedPlatform:blake2b_256"
  )
  def verifyEd25519Signature(pk: ByteString, msg: ByteString, sig: ByteString): Boolean =
    throw new UnsupportedBackend("UnsupportedPlatform:verifyEd25519Signature")
  def signEd25519(privateKey: ByteString, msg: ByteString): ByteString =
    throw new UnsupportedBackend("UnsupportedPlatform:signEd25519")
  def verifyEcdsaSecp256k1Signature(pk: ByteString, msg: ByteString, sig: ByteString): Boolean =
    throw new UnsupportedBackend("UnsupportedPlatform:verifyEcdsaSecp256k1Signature")
  def verifySchnorrSecp256k1Signature(pk: ByteString, msg: ByteString, sig: ByteString): Boolean =
    throw new UnsupportedBackend("UnsupportedPlatform:verifySchnorrSecp256k1Signature")
  def bls12_381_G1_equal(p1: G1Element, p2: G1Element): Boolean = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_G1_equal"
  )
  def bls12_381_G1_add(p1: G1Element, p2: G1Element): G1Element = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_G1_add"
  )
  def bls12_381_G1_scalarMul(s: BigInt, p: G1Element): G1Element = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_G1_scalarMul"
  )
  def bls12_381_G1_neg(
      p: G1Element
  ): G1Element = throw new UnsupportedBackend("UnsupportedPlatform:bls12_381_G1_neg")
  def bls12_381_G1_compress(p: G1Element): ByteString = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_G1_compress"
  )
  def bls12_381_G1_uncompress(bs: ByteString): G1Element = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_G1_uncompress"
  )
  def bls12_381_G1_hashToGroup(bs: ByteString, dst: ByteString): G1Element =
    throw new UnsupportedBackend("UnsupportedPlatform:bls12_381_G1_hashToGroup")
  def bls12_381_G2_equal(p1: G2Element, p2: G2Element): Boolean = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_G2_equal"
  )
  def bls12_381_G2_add(p1: G2Element, p2: G2Element): G2Element = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_G2_add"
  )
  def bls12_381_G2_scalarMul(s: BigInt, p: G2Element): G2Element = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_G2_scalarMul"
  )
  def bls12_381_G2_neg(
      p: G2Element
  ): G2Element = throw new UnsupportedBackend("UnsupportedPlatform:bls12_381_G2_neg")
  def bls12_381_G2_compress(p: G2Element): ByteString = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_G2_compress"
  )
  def bls12_381_G2_uncompress(bs: ByteString): G2Element = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_G2_uncompress"
  )
  def bls12_381_G2_hashToGroup(bs: ByteString, dst: ByteString): G2Element =
    throw new UnsupportedBackend("UnsupportedPlatform:bls12_381_G2_hashToGroup")
  def bls12_381_millerLoop(
      p1: G1Element,
      p2: G2Element
  ): MLResult = throw new UnsupportedBackend("UnsupportedPlatform:bls12_381_millerLoop")
  def bls12_381_mulMlResult(r1: MLResult, r2: MLResult): MLResult = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_mulMlResult"
  )
  def bls12_381_finalVerify(p1: MLResult, p2: MLResult): Boolean = throw new UnsupportedBackend(
    "UnsupportedPlatform:bls12_381_finalVerify"
  )
  def bls12_381_G1_multiScalarMul(
      scalars: Seq[BigInt],
      points: Seq[G1Element]
  ): G1Element = throw new UnsupportedBackend("UnsupportedPlatform:bls12_381_G1_multiScalarMul")
  def bls12_381_G2_multiScalarMul(
      scalars: Seq[BigInt],
      points: Seq[G2Element]
  ): G2Element = throw new UnsupportedBackend("UnsupportedPlatform:bls12_381_G2_multiScalarMul")
  def keccak_256(bs: ByteString): ByteString = throw new UnsupportedBackend(
    "UnsupportedPlatform:keccak_256"
  )
  def ripemd_160(byteString: ByteString): ByteString = throw new UnsupportedBackend(
    "UnsupportedPlatform:ripemd_160"
  )
  def modPow(base: BigInt, exp: BigInt, modulus: BigInt): BigInt = throw new UnsupportedBackend(
    "modPow"
  )
  def readFile(path: String): Array[Byte] = throw new UnsupportedBackend(
    "UnsupportedPlatform:readFile"
  )
  def writeFile(path: String, bytes: Array[Byte]): Unit = throw new UnsupportedBackend(
    "UnsupportedPlatform:writeFile"
  )
  def appendFile(path: String, bytes: Array[Byte]): Unit = throw new UnsupportedBackend(
    "UnsupportedPlatform:appendFile"
  )
