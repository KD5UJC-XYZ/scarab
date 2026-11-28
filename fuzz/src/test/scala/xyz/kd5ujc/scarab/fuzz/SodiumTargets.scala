// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*
import scala.util.Random
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosVrfCertificate, StrictDraft03}
import xyz.kd5ujc.scarab.crypto.witness.{StrictEd25519, VerificationResult}

/** Differential targets: Scarab's Ed25519 and VRF answers against those of the libsodium the
  * reference node links ([[Sodium]]), on the same inputs, live. They run only where [[Sodium]]'s
  * library is named (the fuzz gate builds it); elsewhere `FuzzSuite` skips them.
  *
  *   - `sodium-ed25519`: `StrictEd25519.verify` against `crypto_sign_ed25519_verify_detached`, the
  *     verifier of cardano-base's `Ed25519DSIGN` (transaction witnesses from Shelley on,
  *     operational certificates, KES leaves). `ByronEd25519` has no target here: the reference
  *     verifies Byron signatures with ed25519-donna through cardano-crypto, not with libsodium.
  *   - `sodium-vrf-verify`: `StrictDraft03.verify` against `crypto_vrf_ietfdraft03_verify` (the
  *     verdict and the output), and `PraosVrfCertificate.verifyAlpha` against the reference's
  *     verify-then-compare of a claimed output. The output of each proof that verifies is also
  *     checked against `crypto_vrf_ietfdraft03_proof_to_hash`, which the native verify computes it
  *     with: a check of the binding, not further evidence. One known difference is held as a fixed
  *     case ([[alphaBound]]).
  *   - `sodium-vrf-prove`: for the same seed and alpha, `Draft03Prover` 's public key, proof and
  *     output against `crypto_vrf_ietfdraft03_keypair_from_seed`, `_prove` and `_proof_to_hash`
  *     (proofs are deterministic), and for the Praos input the alpha of `PraosVrfCertificate.alpha`
  *     against Blake2b-256 of `crypto_generichash_blake2b`, with the certificate verified.
  *
  * A fuzz input is read as the choices of one case ([[Choices]]): a mutation, two arguments and a
  * mode, then the bytes the mode takes. The modes are bytes chosen by the fuzzer, a valid signature
  * or proof made by the oracle from a seed, one valid under a mixed-order key (the key plus a point
  * of small order, the challenge ground until the equation leaves nothing of it) and, for the VRF,
  * one whose Gamma, or whose key and Gamma, have a component of small order ([[madeProof]]). The
  * point's order is chosen first, so that orders 2, 4 and 8 are as likely. The mutations put the
  * edge cases where the two might differ: every small-order point in both signs, every
  * non-canonical y, points with a small-order component added, sign bits flipped, scalars at and
  * above L (S + kL, L, L - 1, 2^252, the top bits set) and bit flips. The seeds are these cases and
  * the committed corpora of `fixtures/witness`, `fixtures/wycheproof-ed25519` and `fixtures/vrf`.
  *
  * [[run]] checks every seed once, then the random cases, and requires its tallies to hold each of
  * the target's `required` kinds: a case both sides accept and, for the verifiers, one both refuse
  * and one both accept with a component of each order 2, 4 and 8 in the key and, for the VRF, in
  * Gamma. The torsion tallies come from the construction (which point was added and was not changed
  * by a mutation), not from a predicate of the oracle. Each run prints its tallies, and
  * `scripts/check_differential_fuzz.py` holds the fuzz workflows' floors on them.
  */
object SodiumTargets:
  private val P: BigInt = (BigInt(1) << 255) - 19
  private val L: BigInt = Sodium.L

  /** The part of a case a point of small order was added to. */
  enum Part:
    case Key, Gamma

  /** What a tally counts: cases both sides accepted, cases both refused, and the accepted cases
    * whose key or Gamma was built with a component of a given order.
    */
  enum Kind:
    case Accepted, Refused
    case Torsion(part: Part, order: Int)

  final case class Tally(counts: Map[Kind, Long]):
    def apply(kind: Kind): Long = counts.getOrElse(kind, 0L)
    def -(earlier: Tally): Tally =
      Tally((counts.keySet ++ earlier.counts.keySet).map(k => k -> (this(k) - earlier(k))).toMap)
    def +(other: Tally): Tally =
      Tally((counts.keySet ++ other.counts.keySet).map(k => k -> (this(k) + other(k))).toMap)

  private val counters = new ConcurrentHashMap[(String, Kind), LongAdder]()
  private def count(name: String, kind: Kind): Unit =
    counters.computeIfAbsent((name, kind), _ => new LongAdder).increment()

  /** The cases of `name` so far. */
  def tally(name: String): Tally =
    Tally(counters.asScala.collect {
      case ((n, kind), adder) if n == name => kind -> adder.sum()
    }.toMap)

  private def agreed(name: String, torsion: Seq[(Part, Option[Int])]): Unit =
    count(name, Kind.Accepted)
    torsion.foreach((part, order) => order.foreach(o => count(name, Kind.Torsion(part, o))))

  /** The fuzz input as the choices of one case: each read takes the next bytes, zeros past the end.
    */
  private final class Choices(bytes: Array[Byte]):
    private var at = 0
    def byte(): Int =
      val b = if at < bytes.length then bytes(at) & 0xff else 0
      at += 1
      b
    def take(n: Int): Array[Byte] = Array.fill(n)(byte().toByte)
    def rest(): Array[Byte] =
      val tail = if at < bytes.length then bytes.drop(at) else Array.emptyByteArray
      at = math.max(at, bytes.length)
      tail

  private def hex(text: String): Array[Byte] =
    Bytes.fromHex(text).fold(e => throw new IllegalArgumentException(e.toString), _.toArray)
  private def show(bytes: Array[Byte]): String = Bytes.fromArray(bytes).hex

  private def encoding(y: BigInt, negative: Boolean): Array[Byte] =
    val e = Scalars.encode(y)
    if negative then e(31) = (e(31) | 0x80).toByte
    e
  private def negated(e: Array[Byte]): Array[Byte] = e.updated(31, (e(31) ^ 0x80).toByte)

  // the two y of the points of order 8, as libsodium 1.0.18's small-order blacklist lists them
  // (IntersectMBO/libsodium dbb48cce
  // `src/libsodium/crypto_core/ed25519/ref10/ed25519_ref10.c:1226-1235`)
  private val order8a = hex("26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05")
  private val order8b = hex("c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a")
  private val basePoint = hex("5866666666666666666666666666666666666666666666666666666666666666")

  /** The orders of the points of small order but the identity, and the points of each: what a
    * mixed-order key or Gamma adds.
    */
  val orders: Vector[Int] = Vector(2, 4, 8)
  private val torsion: Map[Int, Vector[Array[Byte]]] = Map(
    2 -> Vector(encoding(P - 1, false)),
    4 -> Vector(encoding(0, false), encoding(0, true)),
    8 -> Vector(order8a, negated(order8a), order8b, negated(order8b))
  )

  /** The point of small order `small` chooses, and its order: the order first, so that each is as
    * likely, then the point of that order.
    */
  private def smallPoint(small: Int): (Array[Byte], Int) =
    val order = orders(small % orders.size)
    val points = torsion(order)
    (points((small / orders.size) % points.size), order)

  /** Encodings at the edges: every small-order point in both signs, every non-canonical y (p to p +
    * 18) in both signs, the base point and its negation, y = 2 and all ones.
    */
  private val edges: Vector[Array[Byte]] =
    Vector(BigInt(1), P - 1, BigInt(0)).flatMap(y =>
      Vector(encoding(y, false), encoding(y, true))
    ) ++
      Vector(order8a, negated(order8a), order8b, negated(order8b)) ++
      (0 until 19).flatMap(k => Vector(encoding(P + k, false), encoding(P + k, true))) ++
      Vector(basePoint, negated(basePoint), encoding(2, false), Array.fill(32)(0xff.toByte))

  /** Scalars at and above L, and at the bit boundaries the checks read. */
  private val scalars: Vector[BigInt] = Vector(
    BigInt(0),
    BigInt(1),
    L - 1,
    L,
    L + 1,
    2 * L - 1,
    2 * L,
    BigInt(1) << 252,
    (BigInt(1) << 253) - 1,
    BigInt(1) << 253,
    (BigInt(1) << 255) - 1,
    BigInt(1) << 255,
    (BigInt(1) << 256) - 1
  )

  private def flip(bytes: Array[Byte], bit: Int): Array[Byte] =
    if bytes.isEmpty then Array(bit.toByte)
    else
      val at = (bit >> 3) % bytes.length
      bytes.updated(at, (bytes(at) ^ (1 << (bit & 7))).toByte)

  private def add(oracle: Sodium.Oracle, point: Array[Byte], small: Int): Array[Byte] =
    oracle.ed25519Add(point, smallPoint(small)._1).getOrElse(point)

  /** A scalar at `from` of `bytes` replaced. */
  private def scalar(bytes: Array[Byte], from: Int)(f: BigInt => BigInt): Array[Byte] =
    bytes.take(from) ++ Scalars.encode(f(Scalars.decode(bytes.slice(from, from + 32)))) ++
      bytes.drop(from + 32)

  private def sha512(pieces: Array[Byte]*): Array[Byte] =
    val digest = MessageDigest.getInstance("SHA-512")
    pieces.foreach(p => digest.update(p))
    digest.digest()

  private def violation(name: String, what: String): Nothing =
    throw new Violation(s"$name: $what")

  // --- Ed25519 -----------------------------------------------------------------------------------

  /** A case: its key, signature and message, and the order of the small-order point its key was
    * built with, if any and if no mutation changed the key since.
    */
  private final case class Signed(
      key: Array[Byte],
      signature: Array[Byte],
      message: Array[Byte],
      keyTorsion: Option[Int] = None
  ):
    override def toString: String =
      s"key ${show(key)}, signature ${show(signature)}, message ${show(message)}"

  /** A signature valid under the mixed-order key A + T: signed with that key's bytes, and the
    * message extended until the challenge h, modulo L, is a multiple of the order of T, so that
    * [h]T vanishes from the cofactorless equation.
    */
  private def mixedSignature(
      oracle: Sodium.Oracle,
      seed: Array[Byte],
      small: Int,
      message: Array[Byte]
  ): Signed =
    val (key, secret) = oracle.ed25519Keypair(seed)
    val (point, order) = smallPoint(small)
    val mixed = oracle.ed25519Add(key, point).getOrElse(key)
    val signing = secret.take(32) ++ mixed
    val attempts = (0 until 64).iterator.map { i =>
      val m = message :+ i.toByte
      Signed(mixed, oracle.ed25519Sign(signing, m), m, Some(order))
    }
    val h = (s: Signed) => Scalars.decode(sha512(s.signature.take(32), s.key, s.message)).mod(L)
    attempts
      .find(s => h(s).mod(order) == 0)
      .getOrElse(Signed(mixed, Array.fill(64)(0), message, Some(order)))

  private def signed(oracle: Sodium.Oracle, c: Choices, small: Int): Signed = c.byte() % 3 match
    case 0 => Signed(c.take(32), c.take(64), c.rest())
    case 1 =>
      val (key, secret) = oracle.ed25519Keypair(c.take(32))
      val message = c.rest()
      Signed(key, oracle.ed25519Sign(secret, message), message)
    case _ => mixedSignature(oracle, c.take(32), small, c.rest())

  private val signatureMutations = 14

  /** The case `kind` makes of `s`; a changed key no longer counts as built with its point. */
  private def mutateSigned(oracle: Sodium.Oracle, s: Signed, kind: Int, a: Int, b: Int): Signed =
    val m = mutatedSigned(oracle, s, kind, a, b)
    if m.key.sameElements(s.key) then m else m.copy(keyTorsion = None)

  private def mutatedSigned(oracle: Sodium.Oracle, s: Signed, kind: Int, a: Int, b: Int): Signed =
    val r = s.signature.take(32)
    val withR = (r2: Array[Byte]) => s.copy(signature = r2 ++ s.signature.drop(32))
    kind % signatureMutations match
      case 0  => s
      case 1  => s.copy(signature = flip(s.signature, a * 2 + (b & 1)))
      case 2  => s.copy(key = flip(s.key, a))
      case 3  => s.copy(message = flip(s.message, a + 256 * b))
      case 4  => s.copy(signature = scalar(s.signature, 32)(_ + L * (1 + a % 14)))
      case 5  => s.copy(signature = scalar(s.signature, 32)(_ => scalars(a % scalars.size)))
      case 6  => s.copy(signature = scalar(s.signature, 32)(_ + (BigInt(1 + a % 7) << 253)))
      case 7  => withR(edges(a % edges.size))
      case 8  => s.copy(key = edges(a % edges.size))
      case 9  => s.copy(key = add(oracle, s.key, a))
      case 10 => withR(add(oracle, r, a))
      case 11 => s.copy(key = negated(s.key))
      case 12 => withR(negated(r))
      case _ =>
        val once = mutateSigned(oracle, s, 1 + a % (signatureMutations - 2), b, a)
        mutateSigned(oracle, once, 1 + b % (signatureMutations - 2), a, b)

  private def ed25519(name: String): Input => Unit = in =>
    Probe.run(name, in, perByte = 4000) {
      val oracle = Sodium.loaded
      val c = new Choices(in.bytes)
      val kind = c.byte()
      val a = c.byte()
      val b = c.byte()
      val s = mutateSigned(oracle, signed(oracle, c, b), kind, a, b)
      val reference = oracle.ed25519Verify(s.key, s.signature, s.message)
      val scarab =
        StrictEd25519.verify(
          Bytes.fromArray(s.key),
          Bytes.fromArray(s.signature),
          Bytes.fromArray(s.message)
        )
      scarab match
        case Right(verdict) =>
          val verified = verdict == VerificationResult.SignatureVerified
          if verified != reference then
            violation(
              name,
              s"Scarab ${if verified then "accepts" else "refuses"}, libsodium " +
                s"${if reference then "accepts" else "refuses"}: $s"
            )
          if verified then agreed(name, Seq(Part.Key -> s.keyTorsion))
          else count(name, Kind.Refused)
        case Left(error) =>
          violation(name, s"Scarab gave no verdict ($error), libsodium ${reference}: $s")
    }

  // --- VRF verification --------------------------------------------------------------------------

  /** A case: its key, proof and alpha, and the orders of the small-order points its key and Gamma
    * were built with, if any and if no mutation changed them since.
    */
  private final case class Proved(
      key: Array[Byte],
      proof: Array[Byte],
      alpha: Array[Byte],
      keyTorsion: Option[Int] = None,
      gammaTorsion: Option[Int] = None
  ):
    override def toString: String = s"key ${show(key)}, proof ${show(proof)}, alpha ${show(alpha)}"

  /** A proof valid under the mixed-order key Y + T: proved with that key's bytes, and alpha
    * extended until the challenge c is congruent to L modulo the order of T. U is computed as [L -
    * c]Y + [s]B, the negation of c taken modulo L (IntersectMBO/libsodium dbb48cce
    * `src/libsodium/crypto_vrf/ietfdraft03/verify.c:86-88`, and the verification equation of
    * Scarab's VRF math), so its torsion part is [L - c]T, which vanishes then and not when c is a
    * multiple of the order.
    */
  private def mixedProof(
      oracle: Sodium.Oracle,
      seed: Array[Byte],
      small: Int,
      alpha: Array[Byte]
  ): Proved =
    val (key, secret) = oracle.vrfKeypair(seed)
    val (point, order) = smallPoint(small)
    val mixed = oracle.ed25519Add(key, point).getOrElse(key)
    val proving = secret.take(32) ++ mixed
    (0 until 64).iterator
      .flatMap { i =>
        val m = alpha :+ i.toByte
        oracle.vrfProve(proving, m).map(Proved(mixed, _, m, keyTorsion = Some(order)))
      }
      .find(p => cancels(p.proof, order))
      .getOrElse(Proved(mixed, Array.fill(80)(0), alpha, keyTorsion = Some(order)))

  /** Whether [L - c]T vanishes for T of `order`, c being the proof's challenge. */
  private def cancels(proof: Array[Byte], order: Int): Boolean =
    (L - Scalars.decode(proof.slice(32, 48))).mod(order) == 0

  /** The secret scalar x of a VRF seed, modulo L: SHA-512 of the seed, its first half clamped, as
    * `crypto_vrf_seed_keypair` and `crypto_vrf_ietfdraft03_prove` compute it
    * (IntersectMBO/libsodium dbb48cce `src/libsodium/crypto_vrf/crypto_vrf.c:59-67`,
    * `ietfdraft03/prove.c:25-28`).
    */
  private def secretScalar(seed: Array[Byte]): BigInt =
    val az = sha512(seed).take(32)
    az(0) = (az(0) & 248).toByte
    az(31) = ((az(31) & 127) | 64).toByte
    Scalars.decode(az).mod(L)

  /** H, the hash to curve of `key` and `alpha`: the Elligator 2 map of the first 32 bytes of
    * SHA-512(0x04 || 0x01 || key || alpha), bit 255 cleared (`ietfdraft03/verify.c:75-83`).
    */
  private def hashToCurve(
      oracle: Sodium.Oracle,
      key: Array[Byte],
      alpha: Array[Byte]
  ): Array[Byte] =
    val r = sha512(Array[Byte](4, 1), key, alpha).take(32)
    r(31) = (r(31) & 0x7f).toByte
    oracle.ed25519FromUniform(r)

  /** A proof made here, with the oracle's group arithmetic, whose key is Y + T1 and whose Gamma is
    * [x]H + T2, either point of small order left out. The verifier computes U = [L - c](Y + T1) +
    * [s]B and V = [L - c]Gamma + [s]H; with s = k + cx, they are [k]B + [L - c]T1 and [k]H + [L -
    * c]T2. The challenge is c = SHA-512(0x04 || 0x02 || H || Gamma || [k]B || [k]H), its first 16
    * bytes, so the proof verifies exactly when [L - c]T1 and [L - c]T2 vanish, that is when c is
    * congruent to L modulo the larger order: the nonce k is drawn again until it is
    * (`ietfdraft03/verify.c:86-104`). The output is that of [8]Gamma, the same as without T2.
    */
  private def madeProof(
      oracle: Sodium.Oracle,
      seed: Array[Byte],
      alpha: Array[Byte],
      keyPoint: Option[(Array[Byte], Int)],
      gammaPoint: Option[(Array[Byte], Int)]
  ): Proved =
    val (plain, _) = oracle.vrfKeypair(seed)
    val key = keyPoint.flatMap((t, _) => oracle.ed25519Add(plain, t)).getOrElse(plain)
    val x = secretScalar(seed)
    val h = hashToCurve(oracle, key, alpha)
    val gamma = oracle
      .ed25519Multiply(Scalars.encode(x), h)
      .flatMap(g => gammaPoint.fold(Option(g))((t, _) => oracle.ed25519Add(g, t)))
      .getOrElse(Array.fill[Byte](32)(0))
    val order = (keyPoint.toSeq ++ gammaPoint.toSeq).map(_._2).maxOption.getOrElse(1)
    val made = (0 until 64).iterator.flatMap { i =>
      val k = Scalars.decode(sha512(seed, alpha, Array(i.toByte))).mod(L)
      for
        kb <- oracle.ed25519MultiplyBase(Scalars.encode(k))
        kh <- oracle.ed25519Multiply(Scalars.encode(k), h)
        c = sha512(Array[Byte](4, 2), h, gamma, kb, kh).take(16)
        s = (k + Scalars.decode(c) * x).mod(L)
      yield gamma ++ c ++ Scalars.encode(s)
    }
    Proved(
      key,
      made.find(cancels(_, order)).getOrElse(Array.fill(80)(0)),
      alpha,
      keyPoint.map(_._2),
      gammaPoint.map(_._2)
    )

  // Alphas are kept below `StrictDraft03.MaxAlphaBytes`, which Scarab refuses as an envelope before
  // the verifier; a header's alpha has 32 bytes.
  private def alphaOf(c: Choices): Array[Byte] = c.rest().take(1 << 16)

  private def proved(oracle: Sodium.Oracle, c: Choices, small: Int): Proved = c.byte() % 5 match
    case 0 => Proved(c.take(32), c.take(80), alphaOf(c))
    case 1 =>
      val (key, secret) = oracle.vrfKeypair(c.take(32))
      val alpha = alphaOf(c)
      Proved(key, oracle.vrfProve(secret, alpha).getOrElse(Array.fill(80)(0)), alpha)
    case 2 => mixedProof(oracle, c.take(32), small, alphaOf(c))
    case 3 => madeProof(oracle, c.take(32), alphaOf(c), None, Some(smallPoint(small)))
    case _ =>
      val other = c.byte()
      madeProof(oracle, c.take(32), alphaOf(c), Some(smallPoint(small)), Some(smallPoint(other)))

  private val proofMutations = 15

  /** The case `kind` makes of `p`; a changed key or Gamma no longer counts as built with its point.
    */
  private def mutateProved(oracle: Sodium.Oracle, p: Proved, kind: Int, a: Int, b: Int): Proved =
    val m = mutatedProved(oracle, p, kind, a, b)
    m.copy(
      keyTorsion = if m.key.sameElements(p.key) then m.keyTorsion else None,
      gammaTorsion =
        if m.proof.take(32).sameElements(p.proof.take(32)) then m.gammaTorsion else None
    )

  private def mutatedProved(oracle: Sodium.Oracle, p: Proved, kind: Int, a: Int, b: Int): Proved =
    val gamma = p.proof.take(32)
    val withGamma = (g: Array[Byte]) => p.copy(proof = g ++ p.proof.drop(32))
    kind % proofMutations match
      case 0  => p
      case 1  => p.copy(proof = flip(p.proof, a))
      case 2  => p.copy(proof = flip(p.proof, 256 + a % 128))
      case 3  => p.copy(proof = flip(p.proof, 384 + a))
      case 4  => p.copy(key = flip(p.key, a))
      case 5  => p.copy(alpha = flip(p.alpha, a + 256 * b).take(1 << 16))
      case 6  => p.copy(proof = scalar(p.proof, 48)(_ + L * (1 + a % 14)))
      case 7  => p.copy(proof = scalar(p.proof, 48)(_ => scalars(a % scalars.size)))
      case 8  => p.copy(proof = scalar(p.proof, 48)(_ + (BigInt(1 + a % 15) << 252)))
      case 9  => withGamma(edges(a % edges.size))
      case 10 => p.copy(key = edges(a % edges.size))
      case 11 => withGamma(add(oracle, gamma, a))
      case 12 => p.copy(key = add(oracle, p.key, a))
      case 13 => if (a & 1) == 0 then withGamma(negated(gamma)) else p.copy(key = negated(p.key))
      case _ =>
        val once = mutateProved(oracle, p, 1 + a % (proofMutations - 2), b, a)
        mutateProved(oracle, once, 1 + b % (proofMutations - 2), a, b)

  private def vrfVerify(name: String): Input => Unit = in =>
    Probe.run(name, in, perByte = 4000) {
      val oracle = Sodium.loaded
      val c = new Choices(in.bytes)
      val kind = c.byte()
      val a = c.byte()
      val b = c.byte()
      val p = mutateProved(oracle, proved(oracle, c, b), kind, a, b)
      val reference = oracle.vrfVerify(p.key, p.proof, p.alpha)
      val key = Bytes.fromArray(p.key)
      val proof = Bytes.fromArray(p.proof)
      val alpha = Bytes.fromArray(p.alpha)
      (StrictDraft03.verify(key, proof, alpha), reference) match
        case (StrictDraft03.Result.Verified(output), Some(expected)) =>
          if !output.toArray.sameElements(expected) then
            violation(
              name,
              s"the outputs differ: Scarab ${output.hex}, libsodium ${show(expected)}: $p"
            )
          if !oracle.vrfProofToHash(p.proof).exists(_.sameElements(expected)) then
            violation(name, s"libsodium's proof to hash is not the verified output: $p")
          agreed(name, Seq(Part.Key -> p.keyTorsion, Part.Gamma -> p.gammaTorsion))
        case (StrictDraft03.Result.Rejected(_), None) => count(name, Kind.Refused)
        case (scarab, theirs) =>
          violation(
            name,
            s"Scarab $scarab, libsodium ${theirs.fold("refuses")(o => s"verifies to ${show(o)}")}: $p"
          )
      // the certificate: the reference verifies, then compares the claimed output with the proof's
      val claimed = reference.map(o => if (b & 3) == 0 then flip(o, a) else o).getOrElse(c.take(64))
      val certificate = PraosVrfCertificate.verifyAlpha(alpha, key, proof, Bytes.fromArray(claimed))
      val expected = reference match
        case None                                         => "ProofRejected"
        case Some(output) if output.sameElements(claimed) => "VerifiedCertificate"
        case Some(_)                                      => "OutputMismatch"
      val agrees = (certificate, reference) match
        case (PraosVrfCertificate.Result.ProofRejected(_), None) => true
        case (PraosVrfCertificate.Result.VerifiedCertificate(output), Some(o)) =>
          o.sameElements(claimed) && output.toArray.sameElements(o)
        case (PraosVrfCertificate.Result.OutputMismatch, Some(o)) => !o.sameElements(claimed)
        case _                                                    => false
      if !agrees then
        violation(
          name,
          s"the certificate is $certificate, the reference's $expected: $p, claimed ${show(claimed)}"
        )
    }

  // --- VRF proving -------------------------------------------------------------------------------

  private def vrfProve(name: String): Input => Unit = in =>
    Probe.run(name, in, perByte = 4000) {
      val oracle = Sodium.loaded
      val c = new Choices(in.bytes)
      val praos = c.byte() % 2 == 0
      val seed = c.take(32)
      val slot = c.take(8)
      val nonce = if c.byte() % 2 == 0 then None else Some(c.take(32))
      val input = if praos then Some(praosInput(slot, nonce)) else None
      // the reference's Praos alpha is Blake2b-256 of the slot, big-endian, and the nonce's bytes
      val alpha = input match
        case None => c.rest()
        case Some(i) =>
          val ours = PraosVrfCertificate.alpha(i).map(_.toArray)
          val theirs = oracle.blake2b256(slot ++ nonce.getOrElse(Array.emptyByteArray))
          if !ours.exists(_.sameElements(theirs)) then
            violation(
              name,
              s"the Praos alpha of slot ${show(slot)} and nonce ${nonce.map(show)} is " +
                s"${ours.map(show)}, libsodium's Blake2b-256 ${show(theirs)}"
            )
          theirs
      val (publicKey, secret) = oracle.vrfKeypair(seed)
      val key = Draft03Prover.SigningKey
        .fromSeed(Bytes.fromArray(seed))
        .fold(e => violation(name, s"no key from seed ${show(seed)}: $e"), identity)
      val what = s"seed ${show(seed)}, alpha ${show(alpha)}"
      if !key.publicKey.toArray.sameElements(publicKey) then
        violation(
          name,
          s"the public keys differ: Scarab ${key.publicKey.hex}, libsodium ${show(publicKey)}: $what"
        )
      val proof = oracle
        .vrfProve(secret, alpha)
        .getOrElse(violation(name, s"libsodium proves nothing: $what"))
      val output =
        oracle.vrfProofToHash(proof).getOrElse(violation(name, s"libsodium has no output: $what"))
      key.prove(Bytes.fromArray(alpha)) match
        case Right(ours) =>
          if !ours.proof.toArray.sameElements(proof) then
            violation(
              name,
              s"the proofs differ: Scarab ${ours.proof.hex}, libsodium ${show(proof)}: $what"
            )
          if !ours.output.toArray.sameElements(output) then
            violation(
              name,
              s"the outputs differ: Scarab ${ours.output.hex}, libsodium ${show(output)}: $what"
            )
        case Left(e) =>
          violation(name, s"Scarab proves nothing ($e), libsodium ${show(proof)}: $what")
      if !key.output(Bytes.fromArray(alpha)).exists(_.toArray.sameElements(output)) then
        violation(name, s"Scarab's output of alpha alone is not the proof's: $what")
      if !oracle.vrfVerify(publicKey, proof, alpha).exists(_.sameElements(output)) then
        violation(name, s"libsodium does not verify its own proof to its output: $what")
      input.foreach { i =>
        PraosVrfCertificate.verify(
          i,
          Bytes.fromArray(publicKey),
          Bytes.fromArray(proof),
          Bytes.fromArray(output)
        ) match
          case PraosVrfCertificate.Result.VerifiedCertificate(_) => ()
          case other => violation(name, s"the Praos certificate is $other: $what")
      }
      key.forget()
      count(name, Kind.Accepted)
    }

  private def praosInput(slot: Array[Byte], nonce: Option[Array[Byte]]): PraosVrfCertificate.Input =
    val built =
      for
        s <- PraosVrfCertificate.Slot.fromBigInt(BigInt(1, slot))
        n <- nonce.fold[Either[PraosVrfCertificate.Failure, PraosVrfCertificate.EpochNonce]](
          Right(PraosVrfCertificate.NeutralNonce)
        )(h => PraosVrfCertificate.Hash32.fromBytes(Bytes.fromArray(h)))
        i <- PraosVrfCertificate.Input.create(s, n)
      yield i
    built.fold(e => throw new Violation(s"no Praos input of slot ${show(slot)}: $e"), identity)

  // --- seeds -------------------------------------------------------------------------------------

  /** Every mode with every mutation and a few arguments, each over four pseudo-random seeds and
    * messages, and the unmutated cases over twelve, whose torsion arguments then choose every point
    * of small order.
    */
  private def generated(modes: Int, mutations: Int): Vector[Array[Byte]] =
    val r = new Random(1418)
    (for
      mode <- 0 until modes
      mutation <- 0 until mutations
      argument <- Vector(0, 1, 37, 255)
      fill <- 0 until (if mutation == 0 then 12 else 4)
    yield Array(mutation, argument, (argument * 7 + mutation + fill) & 0xff, mode).map(_.toByte) ++
      Array.fill(97 + r.nextInt(48))(r.nextInt(256).toByte)).toVector

  /** The rows of a corpus table (`id`, key, signature or proof, message) that `keep` names and
    * whose key and signature have the sizes the functions take, as raw cases; rows of other sizes
    * and the `NULL` envelopes are the decoders' cases, not the verifiers'.
    */
  private def table(
      path: String,
      sizes: (Int, Int),
      keep: String => Boolean = _ => true
  ): Vector[Array[Byte]] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .map(_.split("\t", -1).toVector)
      .collect { case id +: fields if keep(id) => fields.map(Bytes.fromHex(_).map(_.toArray)) }
      .collect {
        case Vector(Right(k), Right(s), Right(m)) if k.length == sizes._1 && s.length == sizes._2 =>
          Array[Byte](0, 0, 0, 0) ++ k ++ s ++ m
      }

  private def wycheproof: Vector[Array[Byte]] =
    val json = io.circe.parser
      .parse(Files.readString(Path.of("fixtures/wycheproof-ed25519/ed25519_test.json")))
      .fold(throw _, identity)
    json.hcursor
      .downField("testGroups")
      .values
      .toVector
      .flatten
      .flatMap { group =>
        val key = group.hcursor.downField("publicKey").get[String]("pk").fold(throw _, identity)
        group.hcursor.downField("tests").values.toVector.flatten.flatMap { t =>
          val sig = t.hcursor.get[String]("sig").fold(throw _, identity)
          val msg = t.hcursor.get[String]("msg").fold(throw _, identity)
          Option.when(key.length == 64 && sig.length == 128)(
            Array[Byte](0, 0, 0, 0) ++ hex(key) ++ hex(sig) ++ hex(msg)
          )
        }
      }

  private val ed25519Seeds: () => Vector[Array[Byte]] = () =>
    generated(3, signatureMutations) ++ table(
      "fixtures/witness/vectors.tsv",
      32 -> 64
    ) ++ wycheproof

  // the further rows of `fixtures/vrf` but its re-runs of `vectors.tsv` and its unstructured bytes:
  // the mixed-order, torsion, negative-zero and scalar-boundary mutations
  private val vrfSeeds: () => Vector[Array[Byte]] = () =>
    generated(5, proofMutations) ++ table("fixtures/vrf/vectors.tsv", 32 -> 80) ++
      table(
        "fixtures/vrf/additional.tsv",
        32 -> 80,
        id => id.startsWith("t-") || id.contains("scalar")
      )

  private val proveSeeds: () => Vector[Array[Byte]] = () =>
    val r = new Random(1418)
    Vector.tabulate(64)(i =>
      Array(i.toByte) ++ Array.fill(41 + r.nextInt(48))(r.nextInt(256).toByte)
    )

  // --- the known difference ----------------------------------------------------------------------

  /** `StrictDraft03.MaxAlphaBytes` as it stands since the prototype's first publication: 1 MiB. */
  private val RecordedAlphaBound: Int = 1048576

  /** The one difference between the VRF verifier and the reference that is known and kept, held
    * here as a fixed case so that a change on either side is seen: `StrictDraft03.verify` refuses
    * an alpha over `StrictDraft03.MaxAlphaBytes` (1 MiB) as `AlphaTooLarge`, before the verifier,
    * and the reference verifies a proof over any alpha (cardano-base 060819b5
    * `cardano-crypto-praos/src/Cardano/Crypto/VRF/Praos.hs:502-512` hands its length to
    * `crypto_vrf_ietfdraft03_verify` unchecked). The limit came with the research prototype's first
    * publication (commit 58172e52d, "alpha exceeds 1 MiB research limit"), and became the typed
    * case in #188; it is kept by decision (decisions register, #1418). No caller in the node
    * reaches it: both verify alphas the node builds itself, 32 bytes each
    * (`PraosVrfCertificate.alpha`, `TPraos.seed`), as the reference's `mkInputVRF` and `mkSeed` are
    * 32-byte Blake2b-256 hashes. At the limit both verify the proof to the same output; one byte
    * over it, the oracle verifies it and Scarab refuses exactly so. The bound is written out
    * ([[RecordedAlphaBound]]), not read from `StrictDraft03`, so that moving it fails this case.
    */
  private def alphaBound(): Unit =
    val name = "sodium-vrf-verify alpha bound"
    val oracle = Sodium.loaded
    val (key, secret) = oracle.vrfKeypair(Array.fill(32)(0x2a.toByte))
    Vector(RecordedAlphaBound, RecordedAlphaBound + 1).foreach { size =>
      val alpha = Array.fill(size)(0x61.toByte)
      val proof = oracle
        .vrfProve(secret, alpha)
        .getOrElse(violation(name, s"libsodium proves nothing over $size bytes"))
      val reference = oracle
        .vrfVerify(key, proof, alpha)
        .getOrElse(violation(name, s"libsodium refuses its proof over an alpha of $size bytes"))
      val scarab =
        StrictDraft03.verify(Bytes.fromArray(key), Bytes.fromArray(proof), Bytes.fromArray(alpha))
      val expected =
        if size <= RecordedAlphaBound then StrictDraft03.Result.Verified(Bytes.fromArray(reference))
        else StrictDraft03.Result.MalformedEnvelope(StrictDraft03.EnvelopeError.AlphaTooLarge(size))
      if scarab != expected then
        violation(name, s"over an alpha of $size bytes Scarab gives $scarab, not $expected")
    }

  // --- the targets -------------------------------------------------------------------------------

  /** A differential target: the share of the run's cases it takes (a case costs it up to two
    * milliseconds, a decoder's microseconds, and the fuzz gate has one hour for all of them), the
    * kinds its run must count at least once, and the fixed cases it checks first.
    */
  final case class Differential(
      target: Target,
      share: Int,
      required: Vector[Kind],
      fixed: () => Unit = () => ()
  )

  private val verifierKinds: Vector[Kind] = Vector(Kind.Accepted, Kind.Refused)
  private def torsionKinds(part: Part): Vector[Kind] = orders.map(Kind.Torsion(part, _))

  val all: Vector[Differential] = Vector(
    Differential(
      Target("sodium-ed25519", ed25519Seeds, ed25519("sodium-ed25519")),
      2,
      verifierKinds ++ torsionKinds(Part.Key)
    ),
    Differential(
      Target("sodium-vrf-verify", vrfSeeds, vrfVerify("sodium-vrf-verify")),
      8,
      verifierKinds ++ torsionKinds(Part.Key) ++ torsionKinds(Part.Gamma),
      alphaBound
    ),
    Differential(
      Target("sodium-vrf-prove", proveSeeds, vrfProve("sodium-vrf-prove")),
      20,
      Vector(Kind.Accepted)
    )
  )

  private def label(kind: Kind): String = kind match
    case Kind.Accepted             => "accepted"
    case Kind.Refused              => "refused"
    case Kind.Torsion(part, order) => s"${part.toString.toLowerCase}$order"

  private def json(t: Tally, kinds: Vector[Kind]): io.circe.Json =
    io.circe.Json.obj(kinds.map(k => label(k) -> io.circe.Json.fromLong(t(k)))*)

  /** One run of `d`: its fixed cases, every seed once, then its share of the run's cases at random.
    * It prints a line for the reader and the `differential` line
    * `scripts/check_differential_fuzz.py` reads, and returns the required kinds it did not count:
    * none if the run is complete.
    */
  def run(d: Differential): Vector[Kind] =
    val name = d.target.name
    d.fixed()
    val start = tally(name)
    val corpus = d.target.corpus()
    corpus.foreach(seed => d.target.check(new Input(seed)))
    val afterSeeds = tally(name)
    Runner.run(name, Mutate.generator(corpus.toIndexedSeq), d.share)(d.target.check)
    val seeds = afterSeeds - start
    val random = tally(name) - afterSeeds
    val cases = math.max(1, FuzzConfig.cases / d.share) * FuzzConfig.seeds
    val torsion = d.required.collect { case k: Kind.Torsion => s"${label(k)} ${random(k)}" }
    val withTorsion =
      if torsion.isEmpty then "" else torsion.mkString(" (accepted with torsion: ", ", ", ")")
    val sides = s"${random(Kind.Accepted)} accepted and ${random(Kind.Refused)} refused by both"
    println(s"  $name: $cases cases, $sides$withTorsion; ${corpus.size} seeds first")
    val line = io.circe.Json.obj(
      "target" -> io.circe.Json.fromString(name),
      "cases" -> io.circe.Json.fromInt(cases),
      "seeds" -> json(seeds, d.required),
      "run" -> json(random, d.required)
    )
    println(s"  differential ${line.noSpaces}")
    d.required.filter(k => (seeds + random)(k) == 0)
