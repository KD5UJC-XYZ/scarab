// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import cats.effect.IO
import cats.mtl.Handle
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.CatsEffectSuite
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.{
  ECDomainParameters,
  ECPrivateKeyParameters,
  Ed25519PublicKeyParameters
}
import org.bouncycastle.crypto.signers.{ECDSASigner, Ed25519Signer, HMacDSAKCalculator}
import org.scijava.nativelib.NativeLibraryUtil
import scala.jdk.CollectionConverters.*

/** The start-up check of libscalus_crypto (ADR 0020): the pins are the bytes the jar ships, the
  * library this JVM loads passes it, the admission rule refuses anything else, and the known
  * answers are independently derived.
  */
class NativeCryptoSuite extends CatsEffectSuite:
  import NativeCrypto.KnownAnswers.*

  private val linuxAmd64 =
    NativeLibraryUtil.getArchitecture == NativeLibraryUtil.Architecture.LINUX_64

  private def resourceBytes(name: String): Option[Array[Byte]] =
    Option(classOf[NativeLibraryUtil].getClassLoader.getResourceAsStream(name)).map { in =>
      try in.readAllBytes()
      finally in.close()
    }

  test("every pin is the sha-256 of the resource the pinned scalus-crypto-jni jar ships") {
    CryptoPlatform.values.foreach { platform =>
      val bytes = resourceBytes(platform.resource).getOrElse(fail(s"no ${platform.resource}"))
      assertEquals(NativeCrypto.sha256Hex(bytes), platform.sha256, platform.toString)
    }
  }

  test("on Linux/amd64 the loader's resource name is the pinned one") {
    assume(linuxAmd64, "Linux/amd64 only")
    assertEquals(CryptoPlatform.of(NativeLibraryUtil.getArchitecture), Some(CryptoPlatform.Linux64))
    assertEquals(
      NativeLibraryUtil.getPlatformLibraryPath("natives/") +
        NativeLibraryUtil.getPlatformLibraryName("scalus_crypto"),
      CryptoPlatform.Linux64.resource
    )
  }

  test("the library this JVM loads passes the start-up check") {
    assume(linuxAmd64, "Linux/amd64 only")
    Handle
      .allow[NativeCryptoProblem](NativeCrypto.verify[IO].map(Right(_)))
      .rescue(problem => IO.pure(Left(problem)))
      .map(result =>
        assertEquals(
          result,
          Right(
            NativeCryptoReport(
              CryptoPlatform.Linux64,
              CryptoLibrary.Resource(CryptoPlatform.Linux64.resource, CryptoPlatform.Linux64.sha256)
            )
          )
        )
      ) *> IO(assert(NativeCrypto.available))
  }

  test("admission: a missing or unpinned library is refused, wherever it comes from") {
    val platform = CryptoPlatform.Linux64
    val pinned = CryptoLibrary.Resource(platform.resource, platform.sha256)
    val other = CryptoLibrary.Resource(platform.resource, "00" * 32)
    val onPath = CryptoLibrary.OnLibraryPath(Path.of("/usr/lib/libscalus_crypto.so"), "11" * 32)
    val copied =
      CryptoLibrary.OnLibraryPath(Path.of("/usr/lib/libscalus_crypto.so"), platform.sha256)
    assertEquals(
      NativeCrypto.admissible(platform, None),
      Left(NativeCryptoProblem.Missing(platform))
    )
    assertEquals(
      NativeCrypto.admissible(platform, Some(other)),
      Left(NativeCryptoProblem.Unpinned(platform, other))
    )
    assertEquals(
      NativeCrypto.admissible(platform, Some(onPath)),
      Left(NativeCryptoProblem.Unpinned(platform, onPath))
    )
    assertEquals(
      NativeCrypto.admissible(CryptoPlatform.LinuxArm64, Some(pinned)),
      Left(NativeCryptoProblem.Unpinned(CryptoPlatform.LinuxArm64, pinned))
    )
    assertEquals(
      NativeCrypto.admissible(platform, Some(pinned)),
      Right(NativeCryptoReport(platform, pinned))
    )
    assertEquals(
      NativeCrypto.admissible(platform, Some(copied)),
      Right(NativeCryptoReport(platform, copied))
    )
  }

  test("the Ed25519 known answer verifies under an independent implementation") {
    val verifier = Ed25519Signer()
    verifier.init(false, Ed25519PublicKeyParameters(edKey, 0))
    assert(verifier.verifySignature(edSig))
  }

  test("the ECDSA known answer is the low-s RFC 6979 signature of Secp256k1Suite's key") {
    val curve = SECNamedCurves.getByName("secp256k1")
    val domain = ECDomainParameters(curve.getCurve, curve.getG, curve.getN, curve.getH)
    val sk = BigInt(1, hex("c9afa9d845ba75166b5c215767b1d6934e50c3db36e89b127b8a622b120f6721"))
    val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
    signer.init(true, ECPrivateKeyParameters(sk.bigInteger, domain))
    val Array(r, s0) = signer.generateSignature(ecdsaMsg)
    val n = BigInt(curve.getN)
    val s = if BigInt(s0) > n / 2 then n - BigInt(s0) else BigInt(s0)
    def be32(v: BigInt) = v.toByteArray.dropWhile(_ == 0).reverse.padTo(32, 0.toByte).reverse
    assertEquals((be32(BigInt(r)) ++ be32(s)).toVector, ecdsaSig.toVector)
    assertEquals(curve.getG.multiply(sk.bigInteger).getEncoded(true).toVector, ecdsaKey.toVector)
  }

  test("the hashToGroup known answers are the pinned Haskell outputs for message 3f, tag 90") {
    val vectors = Files
      .readAllLines(Path.of("fixtures/reference/plutus-boundaries/hash-vectors.jsonl"))
      .asScala
      .flatMap(parse(_).toOption)
    val row = vectors
      .find(v =>
        v.hcursor
          .get[String]("dst")
          .contains("90") && v.hcursor.get[String]("message").contains("3f")
      )
      .getOrElse(fail("no vector for message 3f, tag 90"))
    assertEquals(row.hcursor.get[String]("g1").map(hex(_).toVector), Right(g1Hashed.toVector))
    assertEquals(row.hcursor.get[String]("g2").map(hex(_).toVector), Right(g2Hashed.toVector))
  }
