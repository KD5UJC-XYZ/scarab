// SPDX-License-Identifier: Apache-2.0
package lab.vm

import scalus.uplc.builtin.ByteString

class Blake2bPlatformSuite extends munit.FunSuite:
  // Literal inputs/digests from the two vendored Plutus 1.63 BLAKE2b fixtures.
  private val emptyDigest =
    "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8"
  private val input25 = ByteString.fromHex("2e7ea84da4bc4d7cfb463e3f2c8647057afff3fbececa1d200")
  private val digest25 =
    "91c60f99b33303c02b39ed93b713e3915a180c3747f3b31e05727618ee401624"

  test("raw official digests have exactly 32 bytes for empty and 200-bit input") {
    assertEquals(input25.size, 25)
    for (input, expected) <- Seq(ByteString.empty -> emptyDigest, input25 -> digest25) do
      val before = input.bytes.clone()
      val actual = Blake2bPlatform.blake2b_256(input)
      assertEquals(actual.size, 32)
      assertEquals(actual.toHex, expected)
      assertEquals(input.bytes.toSeq, before.toSeq)
  }
  test("repeated and interleaved calls retain independent digest state and output") {
    val retained = Blake2bPlatform.blake2b_256(input25)
    for _ <- 1 to 8 do
      assertEquals(Blake2bPlatform.blake2b_256(ByteString.empty).toHex, emptyDigest)
      val next = Blake2bPlatform.blake2b_256(input25)
      assertEquals(next.toHex, digest25)
      assert(!(retained.bytes eq next.bytes))
      assertEquals(retained.toHex, digest25)
  }
  test("every other platform capability throws typed unsupported, including filesystem defaults") {
    val p = Blake2bPlatform
    val b = ByteString.empty
    val rejected: Seq[() => Any] = Seq(
      () => p.sha2_256(b),
      () => p.sha2_512(b),
      () => p.sha3_256(b),
      () => p.blake2b_224(b),
      () => p.keccak_256(b),
      () => p.ripemd_160(b),
      () => p.verifyEd25519Signature(b, b, b),
      () => p.signEd25519(b, b),
      () => p.verifyEcdsaSecp256k1Signature(b, b, b),
      () => p.verifySchnorrSecp256k1Signature(b, b, b),
      () => p.bls12_381_G1_equal(null, null),
      () => p.bls12_381_G1_add(null, null),
      () => p.bls12_381_G1_scalarMul(1, null),
      () => p.bls12_381_G1_neg(null),
      () => p.bls12_381_G1_compress(null),
      () => p.bls12_381_G1_uncompress(b),
      () => p.bls12_381_G1_hashToGroup(b, b),
      () => p.bls12_381_G2_equal(null, null),
      () => p.bls12_381_G2_add(null, null),
      () => p.bls12_381_G2_scalarMul(1, null),
      () => p.bls12_381_G2_neg(null),
      () => p.bls12_381_G2_compress(null),
      () => p.bls12_381_G2_uncompress(b),
      () => p.bls12_381_G2_hashToGroup(b, b),
      () => p.bls12_381_millerLoop(null, null),
      () => p.bls12_381_mulMlResult(null, null),
      () => p.bls12_381_finalVerify(null, null),
      () => p.bls12_381_G1_multiScalarMul(Seq.empty, Seq.empty),
      () => p.bls12_381_G2_multiScalarMul(Seq.empty, Seq.empty),
      () => p.modPow(2, 3, 5),
      () => p.readFile("unused"),
      () => p.writeFile("unused", Array.emptyByteArray),
      () => p.appendFile("unused", Array.emptyByteArray),
      () => p.createDirectories("unused"),
      () => p.fileExists("unused")
    )
    rejected.foreach(call => intercept[UnsupportedBackend](call()))
  }
