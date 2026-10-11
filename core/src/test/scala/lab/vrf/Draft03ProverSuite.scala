// SPDX-License-Identifier: Apache-2.0
package lab.vrf

import lab.cbor.Bytes
import org.scalacheck.Prop.forAll
import org.scalacheck.Gen

class Draft03ProverSuite extends munit.ScalaCheckSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).toOption.get

  private def key(seed: String) = Draft03Prover.SigningKey.fromSeed(hex(seed)).toOption.get

  // IETF draft-03 test vector 16 (RFC 8032 test 1 key, empty alpha); fixtures/vrf/vectors.tsv row v0.
  private val seed16 = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"
  private val pk16 = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
  private val proof16 =
    "b6b4699f87d56126c9117a7da55bd0085246f4c56dbc95d20172612e9d38e8d7ca65e573a126ed88d4e30a46f80a666854d675cf3ba81de0de043c3774f061560f55edc256a787afe701677c0f602900"
  private val beta16 =
    "5b49b554d05c0cd5a5325376b3387de59d924fd1e13ded44648ab33c21349a603f25b84ec5ed887995b33da5e3bfcb87cd2f64521c4c62cf825cffabbe5d31cc"

  test("draft-03 vector 16: public key, proof and output match the reference exactly") {
    val k = key(seed16)
    assertEquals(k.publicKey.hex, pk16)
    val proved = k.prove(Bytes(Vector.empty)).toOption.get
    assertEquals(proved.proof.hex, proof16)
    assertEquals(proved.output.hex, beta16)
  }

  test("draft-03 vector 17: one-byte alpha") {
    val k = key("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
    assertEquals(
      k.publicKey.hex,
      "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"
    )
    val proved = k.prove(hex("72")).toOption.get
    assertEquals(
      proved.proof.hex,
      "ae5b66bdf04b4c010bfe32b2fc126ead2107b697634f6f7337b9bff8785ee111200095ece87dde4dbe87343f6df3b107d91798c8a7eb1245d3bb9c5aafb093358c13e6ae1111a55717e895fd15f99f07"
    )
    assertEquals(
      proved.output.hex,
      "94f4487e1b2fec954309ef1289ecb2e15043a2461ecc7b2ae7d4470607ef82eb1cfa97d84991fe4a7bfdfd715606bc27e2967a6c557cfb5875879b671740b7d8"
    )
  }

  test("the 64-byte vrf.skey layout is seed || public key and is checked") {
    val k = key(seed16)
    assertEquals(k.toBytes.hex, seed16 + pk16)
    assert(Draft03Prover.SigningKey.fromBytes(k.toBytes).isRight)
    val wrong = Bytes.fromArray(k.toBytes.toArray.updated(40, 0.toByte))
    assert(Draft03Prover.SigningKey.fromBytes(wrong).isLeft)
    assert(Draft03Prover.SigningKey.fromBytes(hex("00")).isLeft)
  }

  property("every proof verifies with the strict verifier and certifies the same output") {
    forAll(Gen.listOfN(32, Gen.choose(0, 255)), Gen.listOfN(40, Gen.choose(0, 255))) { (s, a) =>
      val k = Draft03Prover.SigningKey
        .fromSeed(Bytes.fromArray(s.map(_.toByte).toArray))
        .toOption
        .get
      val alpha = Bytes.fromArray(a.map(_.toByte).toArray)
      val proved = k.prove(alpha).toOption.get
      StrictDraft03.verify(k.publicKey, proved.proof, alpha) match
        case StrictDraft03.Result.Verified(out) => out == proved.output
        case _                                  => false
    }
  }

  test("proving is deterministic and depends on alpha") {
    val k = key(seed16)
    val a = k.prove(hex("01")).toOption.get
    assertEquals(k.prove(hex("01")).toOption.get, a)
    assertNotEquals(k.prove(hex("02")).toOption.get.proof, a.proof)
  }

  test("the Praos certificate proved over slot and nonce verifies as a header would") {
    val k = key(seed16)
    val slot = PraosVrfCertificate.Slot.fromBigInt(1234).toOption.get
    val nonce = PraosVrfCertificate.Hash32.fromBytes(hex("11" * 32)).toOption.get
    val input = PraosVrfCertificate.Input.create(slot, nonce).toOption.get
    val alpha = PraosVrfCertificate.alpha(input).toOption.get
    val proved = k.prove(alpha).toOption.get
    assertEquals(
      PraosVrfCertificate.verify(input, k.publicKey, proved.proof, proved.output),
      PraosVrfCertificate.Result.VerifiedCertificate(proved.output)
    )
  }
