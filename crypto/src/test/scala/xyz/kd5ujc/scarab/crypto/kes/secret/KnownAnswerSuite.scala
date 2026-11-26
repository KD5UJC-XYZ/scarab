// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.kes.secret

import io.circe.Decoder
import io.circe.parser.decode
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.secret.Scratch

/** Known answers for the two primitives of the KES secret path, beside the differential suites.
  *
  * `Leaf` against the Ed25519 test vectors of RFC 8032 section 7.1 (TEST 1, 2, 3, 1024 and
  * SHA(abc)): public key and signature of each. TEST 1024's 1023-byte message, public key and
  * signature are case 83 of the vendored, pinned Wycheproof file (fixtures/wycheproof-ed25519,
  * "draft-josefsson-eddsa-ed25519-02: Test 1024"); its seed is the RFC's.
  *
  * `Blake2b256` against BLAKE2b with a 32-byte digest: of the empty input and of "abc", the values
  * published for BLAKE2b-256, and of inputs of 1, 127, 128, 129, 255, 256, 257 and 1000 bytes, the
  * byte at `i` being `(7 i + 3) mod 256`, whose digests were computed for this suite with CPython
  * 3.14's `hashlib.blake2b(digest_size = 32)`, the BLAKE2 reference implementation. The
  * cardano-base Sum KES vectors the tree holds are the generation vectors of fixtures/kes-gen,
  * which `Sum6KesSigningSuite` runs through this code.
  */
class KnownAnswerSuite extends munit.FunSuite:
  private def hex(s: String): Array[Byte] = s.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
  private def show(a: Array[Byte]): String = Bytes.fromArray(a).hex

  private final case class Rfc8032(
      name: String,
      seed: String,
      publicHex: String,
      message: String,
      signature: String
  )

  private val sha512OfAbc: String =
    show(MessageDigest.getInstance("SHA-512").digest("abc".getBytes(UTF_8)))

  private final case class Test(tcId: Int, msg: String, sig: String) derives Decoder
  private final case class Pk(pk: String) derives Decoder
  private final case class Group(publicKey: Pk, tests: List[Test]) derives Decoder
  private final case class Vectors(testGroups: List[Group]) derives Decoder

  /** Case 83 of the pinned Wycheproof file: the public key, message and signature of RFC 8032's
    * TEST 1024.
    */
  private val test1024: (String, String, String) =
    val file = Path.of("fixtures/wycheproof-ed25519/ed25519_test.json")
    val bytes = Files.readAllBytes(file)
    assertEquals(
      show(MessageDigest.getInstance("SHA-256").digest(bytes)),
      "ea80ffebac085e21cb9d3e903e7fa2b448d96702da5b31f061d0b83b430b74b3",
      "the Wycheproof file is not the pinned one"
    )
    val vectors = decode[Vectors](new String(bytes, UTF_8)).fold(e => fail(e.toString), identity)
    vectors.testGroups
      .flatMap(g => g.tests.filter(_.tcId == 83).map(t => (g.publicKey.pk, t.msg, t.sig)))
      .headOption
      .getOrElse(fail("no case 83"))

  private val rfc8032 = List(
    Rfc8032(
      "TEST 1",
      "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
      "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
      "",
      "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
    ),
    Rfc8032(
      "TEST 2",
      "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
      "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
      "72",
      "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"
    ),
    Rfc8032(
      "TEST 3",
      "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
      "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
      "af82",
      "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"
    ),
    Rfc8032(
      "TEST 1024",
      "f5e5767cf153319517630f226876b86c8160cc583bc013744c6bf255f5cc0ee5",
      test1024._1,
      test1024._2,
      test1024._3
    ),
    Rfc8032(
      "TEST SHA(abc)",
      "833fe62409237b9d62ec77587520911e9a759cec1d19755b7da901b96dca3d42",
      "ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf",
      sha512OfAbc,
      "dc2a4459e7369633a52b1bf277839a00201009a3efbf3ecb69bea2186c26b58909351fc9ac90b3ecfdfbc7c66431e0303dca179c138ac17ad9bef1177331a704"
    )
  )

  test("a leaf's public key and signature are those of the RFC 8032 test vectors") {
    assertEquals(hex(test1024._2).length, 1023, "TEST 1024's message")
    rfc8032.foreach { v =>
      val derived = new Array[Byte](32)
      val signed = new Array[Byte](64)
      Scratch.within { s =>
        Leaf.publicKey(s)(hex(v.seed), 0, derived, 0)
        Leaf.sign(s)(hex(v.seed), 0, hex(v.message), signed, 0)
      }
      assertEquals(show(derived), v.publicHex, s"${v.name}: public key")
      assertEquals(show(signed), v.signature, s"${v.name}: signature")
    }
  }

  private def input(n: Int): Array[Byte] = Array.tabulate(n)(i => ((7 * i + 3) % 256).toByte)

  private val blake2b256 = List(
    "" -> "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
    "abc" -> "bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319"
  ).map((text, digest) => (text.getBytes(UTF_8), digest)) ++ List(
    1 -> "e88bd757ad5b9bedf372d8d3f0cf6c962a469db61a265f6418e1ffed86da29ec",
    127 -> "c9ae3859964b35f04c54b36d33cf299d7290ee621005d28e51598a943560aaaa",
    128 -> "f0501d06597880592bc49234eef100ec1ff349058d0e9d9b753504e24af86dd6",
    129 -> "a34a4e1e03c541dfbf3099c4b6c143c022ced65c28bd7e8a10e0a098461aecf0",
    255 -> "f2d64a40e9412a3414161ff6250075225418fd7c271c1123e162e1bca0de9f93",
    256 -> "d93ebb9c802f5630ab22516fd82b6c21bc8bd551d531349b715f046ed11ed871",
    257 -> "4ce481b24d387422d2bc2baa03d1afd55a1327939ff537c71eb9b38709268649",
    1000 -> "d62b6c768ce1afc8367e0498ab2f8e3f7c178c35b1429f14c4604b545d200f52"
  ).map((n, digest) => (input(n), digest))

  test("Blake2b256 gives the known BLAKE2b-256 digests, across and between blocks") {
    blake2b256.foreach { (message, digest) =>
      val out = new Array[Byte](32)
      Scratch.within(s => Blake2b256.hash(s)(message, 0, message.length, out, 0))
      assertEquals(show(out), digest, s"${message.length} bytes")
    }
  }
