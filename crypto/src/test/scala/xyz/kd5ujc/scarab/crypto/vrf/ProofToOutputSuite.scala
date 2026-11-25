// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import scala.jdk.CollectionConverters.*
import scala.util.Using
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.witness.{Ed25519Model as M}
import ProofToOutput.Refusal

/** [[ProofToOutput]], the reference's output of a proof alone, against the pinned libsodium's
  * `crypto_vrf_ietfdraft03_proof_to_hash` on 105 edge proofs (`fixtures/vrf-proof-to-hash`), and
  * against `StrictDraft03.verify` on proved and changed proofs, with the outcome of each change
  * read from `verify.c:13-41` (the steps are in `ProofToOutput` 's Scaladoc).
  */
class ProofToOutputSuite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(200)

  private val vectors = Path.of("fixtures/vrf-cardano-base")
  private val root = Path.of("fixtures/vrf-proof-to-hash")

  private def hexOf(bytes: Array[Byte]): String = Bytes.fromArray(bytes).hex
  private def bytesOf(hex: String): Array[Byte] =
    Bytes.fromHex(hex).fold(e => fail(e.message), identity).toArray

  private def pinned(file: String, sha256: String): String =
    val bytes = Files.readAllBytes(root.resolve(file))
    assertEquals(hexOf(MessageDigest.getInstance("SHA-256").digest(bytes)), sha256, file)
    Files.readString(root.resolve(file), UTF_8)

  private def le(value: BigInt): Array[Byte] = M.le(value, 32)

  /** SHA-512 of 0x04, 0x03 and the encoding of [8]P: the output of a proof whose Gamma is P. */
  private def outputOf(gamma: M.Point): String =
    val digest = MessageDigest.getInstance("SHA-512")
    digest.update(Array[Byte](4, 3))
    digest.update((gamma * 8).encode)
    hexOf(digest.digest())

  /** The fourteen changes of the fixture, and what `verify.c:13-41` does with each. */
  private enum Change(val name: String):
    case Unchanged extends Change("unchanged")
    case ChallengeBit extends Change("c-bit-0")
    case SPlusL extends Change("s-plus-L")
    case SEqualsL extends Change("s-equals-L")
    case SLMinusOne extends Change("s-L-minus-1")
    case STwo252 extends Change("s-2^252")
    case SBit255 extends Change("s-bit-255")
    case Gamma(label: String, encoding: Array[Byte]) extends Change(label)

  private val gammas = Vector(
    Change.Gamma("gamma-identity", le(1)),
    Change.Gamma("gamma-identity-sign-bit", le(BigInt(1).setBit(255))),
    Change.Gamma("gamma-order-2", le(M.p - 1)),
    Change.Gamma("gamma-order-8", M.order8.encode),
    Change.Gamma("gamma-basepoint", M.basepoint.encode),
    Change.Gamma("gamma-noncanonical-identity", le(M.p + 1)),
    Change.Gamma("gamma-noncanonical-p-plus-3", le(M.p + 3)),
    Change.Gamma("gamma-off-curve-y-2", le(2))
  )

  private val changes: Vector[Change] =
    Vector(
      Change.Unchanged,
      Change.ChallengeBit,
      Change.SPlusL,
      Change.SEqualsL,
      Change.SLMinusOne,
      Change.STwo252,
      Change.SBit255
    ) ++ gammas

  private def applied(change: Change, pi: Array[Byte]): Array[Byte] =
    val s = M.fromLe(pi.slice(48, 80))
    def withS(v: BigInt) = pi.take(48) ++ le(v)
    change match
      case Change.Unchanged    => pi
      case Change.ChallengeBit => pi.updated(32, (pi(32) ^ 1).toByte)
      case Change.SPlusL       => withS(s + M.L)
      case Change.SEqualsL     => withS(M.L)
      case Change.SLMinusOne   => withS(M.L - 1)
      case Change.STwo252      => withS(BigInt(1) << 252)
      case Change.SBit255      => withS(s.setBit(255))
      case Change.Gamma(_, g)  => g ++ pi.drop(32)

  /** The outcome `verify.c:13-41` gives a changed proof whose unchanged output is `beta`. */
  private def expected(change: Change, beta: String): Either[Refusal, String] = change match
    // c is not read; s is read only for its check, and L - 1 and 2^252 are below L
    case Change.Unchanged | Change.ChallengeBit | Change.SLMinusOne | Change.STwo252 => Right(beta)
    // verify.c:24-27: the top four bits are set and s is not below L
    case Change.SPlusL | Change.SEqualsL | Change.SBit255 => Left(Refusal.NoncanonicalScalar)
    // verify.c:19-22: canonical and on the curve, of any order, x = 0 with either sign
    case Change.Gamma(_, g) =>
      if M.y(g) >= M.p then Left(Refusal.GammaEncoding)
      else M.decode(g, M.Reading.Lenient).map(outputOf).toRight(Refusal.GammaEncoding)

  test("the 105 native results: the fixture's proofs rebuilt, and ProofToOutput gives each") {
    val cases =
      pinned(
        "cases.tsv",
        "b0b60d8000bc7c7aca03873a9f4b18866b8a9a7fb6d32e972c9f7906761da805"
      ).linesIterator
        .map(_.split("\t", -1).toVector)
        .toVector
    val results =
      pinned(
        "sodium-results.tsv",
        "5332c9ec33aa801211a1b3dfddd91da9bb68f6d74819898d8771482bad5f3153"
      ).linesIterator
        .map(_.split("\t", -1).toVector)
        .map {
          case Vector(id, result) => id -> result; case other => fail(s"malformed row $other")
        }
        .toMap
    val files = Using
      .resource(Files.list(vectors))(_.iterator.asScala.map(_.getFileName.toString).toVector)
      .filter(_.startsWith("vrf_ver03_"))
      .sorted
    assertEquals(files.size, 7, "the vectors")
    val rebuilt = for
      file <- files
      lines = Files
        .readString(vectors.resolve(file), UTF_8)
        .linesIterator
        .map(_.split(":", 2).map(_.trim))
        .toVector
      pi = bytesOf(lines.find(_(0) == "pi").get(1))
      beta = lines.find(_(0) == "beta").get(1)
      change <- changes
    yield (s"$file/${change.name}", applied(change, pi), expected(change, beta))
    assertEquals(cases.map(_(0)), rebuilt.map(_._1), "the case ids, in order")
    assertEquals(results.keySet, rebuilt.map(_._1).toSet, "a native result for every case")
    cases.zip(rebuilt).foreach { (row, built) =>
      val (id, hex) = row match
        case Vector(i, h) => (i, h)
        case other        => fail(s"malformed case $other")
      val (_, pi, fromC) = built
      assertEquals(hex, hexOf(pi), s"$id: the proof rebuilt from its vector")
      val native = results(id) match
        case "Refused"                        => None
        case out if out.startsWith("Output:") => Some(out.drop("Output:".length))
        case other                            => fail(s"$id: unknown result $other")
      val got = ProofToOutput(Bytes.fromArray(pi))
      assertEquals(got.map(_.hex).toOption, native, s"$id: ProofToOutput against the native result")
      assertEquals(got.map(_.hex), fromC, s"$id: ProofToOutput against verify.c:13-41 read")
    }
  }

  private def bytes(n: Int): Gen[Array[Byte]] =
    Gen.listOfN(n, Gen.choose(0, 255)).map(_.map(_.toByte).toArray)

  /** A key's public key, a proof of alpha and the output StrictDraft03.verify gives it. */
  private val proved: Gen[(Bytes, Array[Byte], Bytes, String)] =
    for
      seed <- bytes(32)
      alpha <- Gen.choose(0, 40).flatMap(bytes)
    yield
      val key = Draft03Prover.SigningKey
        .fromSeed(Bytes.fromArray(seed))
        .fold(e => fail(e.toString), identity)
      val proof = key.prove(Bytes.fromArray(alpha)).fold(e => fail(e.toString), identity).proof
      StrictDraft03.verify(key.publicKey, proof, Bytes.fromArray(alpha)) match
        case StrictDraft03.Result.Verified(out) =>
          (key.publicKey, proof.toArray, Bytes.fromArray(alpha), out.hex)
        case other => fail(s"a proof does not verify: $other")

  property("for a proof StrictDraft03.verify accepts, ProofToOutput gives the verified output") {
    forAllNoShrink(proved) { (_, pi, _, out) =>
      assertEquals(ProofToOutput(Bytes.fromArray(pi)).map(_.hex), Right(out))
    }
  }

  // Changes of a verified proof, each with the outcome verify.c:13-41 gives it: c is not read, so a
  // changed c keeps the output of a proof that no longer verifies; s is refused at L and above and
  // otherwise not read; Gamma must be canonical and on the curve, of any order, and the output is
  // then that of the new Gamma; another length is refused before the C function.
  private enum Mutation:
    case Challenge(bit: Int)
    case S(value: BigInt)
    case Gamma(encoding: Array[Byte])
    case Length(size: Int)

  private val mutation: Gen[Mutation] =
    val torsion = M.torsion.map(_.encode)
    val signBitZero = Vector(M.identity, M.torsion(4)).map(t => le(t.affineY.setBit(255)))
    val nonCanonical =
      (0 to 18).toVector.flatMap(d => Vector(le(M.p + d), le((M.p + d).setBit(255))))
    Gen.oneOf(
      Gen.choose(0, 127).map(Mutation.Challenge(_)),
      Gen
        .oneOf(
          Gen.choose(BigInt(0), M.L - 1),
          Gen.oneOf(M.L, M.L + 1, BigInt(1) << 252, (BigInt(1) << 252) - 1, (BigInt(1) << 256) - 1),
          Gen.choose(M.L, (BigInt(1) << 256) - 1)
        )
        .map(Mutation.S(_)),
      Gen
        .oneOf(
          Gen.oneOf(torsion ++ signBitZero ++ nonCanonical),
          Gen
            .zip(Gen.choose(BigInt(1), M.L - 1), Gen.choose(0, 7))
            .map((a, i) => (M.basepoint * a + M.torsion(i)).encode),
          bytes(32)
        )
        .map(Mutation.Gamma(_)),
      Gen.choose(0, 160).suchThat(_ != 80).map(Mutation.Length(_))
    )

  property("for a changed proof, ProofToOutput refuses exactly where verify.c:13-41 refuses") {
    forAllNoShrink(proved, mutation) { case ((pk, pi, alpha, out), change) =>
      val (changed, expectedOutcome) = change match
        case Mutation.Challenge(bit) =>
          (pi.updated(32 + bit / 8, (pi(32 + bit / 8) ^ (1 << (bit % 8))).toByte), Right(out))
        case Mutation.S(v) =>
          val outcome = if v >= M.L then Left(Refusal.NoncanonicalScalar) else Right(out)
          (pi.take(48) ++ M.le(v, 32), outcome)
        case Mutation.Gamma(g) =>
          val outcome =
            if M.y(g) >= M.p then Left(Refusal.GammaEncoding)
            else M.decode(g, M.Reading.Lenient).map(outputOf).toRight(Refusal.GammaEncoding)
          (g ++ pi.drop(32), outcome)
        case Mutation.Length(n) =>
          (Array.tabulate(n)(i => pi(i % 80)), Left(Refusal.ProofLength(n)))
      val clue = change match
        case Mutation.Gamma(g) => s"Gamma ${hexOf(g)}"
        case other             => other.toString
      assertEquals(ProofToOutput(Bytes.fromArray(changed)).map(_.hex), expectedOutcome, clue)
      change match
        case Mutation.Challenge(_) =>
          assertEquals(
            StrictDraft03.verify(pk, Bytes.fromArray(changed), alpha),
            StrictDraft03.Result.Rejected(StrictDraft03.Rejection.ChallengeMismatch),
            "a changed challenge does not verify, and still has its output"
          )
        case _ => ()
    }
  }
