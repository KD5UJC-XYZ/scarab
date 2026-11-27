// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import cats.syntax.all.*
import java.lang.foreign.{
  Arena,
  FunctionDescriptor,
  Linker,
  MemorySegment,
  SymbolLookup,
  ValueLayout
}
import java.lang.invoke.MethodHandle
import java.nio.file.{Files, Path}
import scala.jdk.OptionConverters.*
import scala.util.Using
import xyz.kd5ujc.scarab.codec.Bytes

/** The libsodium the reference node links, as the oracle of the differential targets
  * ([[SodiumTargets]]): IntersectMBO/libsodium at dbb48cce5429cb6585c9034f002568964f1ce567, built
  * by `scripts/sodium_oracle.py` from its source archive and named by the environment variable
  * [[Variable]]. It is called through the Foreign Function and Memory API, with no glue of our own,
  * and only from the fuzz module's tests: the node, `crypto` and every other suite never load it.
  *
  * Without the variable the targets are skipped. With it, a library that is missing, does not load,
  * lacks a function, reports another version or fails the self-test is a failure: a run that was
  * told to compare must compare. The self-test is the RFC 8032 test-1 signature, which must verify
  * and must be refused with L added to S (a build with `ED25519_COMPAT` accepts that), and the
  * draft-03 example 10 (`fixtures/vrf-cardano-base/vrf_ver03_standard_10`), whose key, proof and
  * output the library must give.
  */
object Sodium:
  val Variable: String = "SCARAB_SODIUM_ORACLE"
  val Version: String = "1.0.18"

  enum Unavailable:
    case NotConfigured
    case Missing(path: String)
    case Unloadable(path: String, cause: Throwable)
    case MissingFunction(name: String)
    case WrongVersion(actual: String)
    case InitFailed(status: Int)
    case SelfTestFailed(check: String)

  /** Why the targets are skipped when [[Variable]] is not set. */
  val notConfigured: String =
    s"$Variable is not set: the libsodium oracle of the differential targets is absent, so they " +
      "are skipped (scripts/sodium_oracle.py builds it; docs/running/fuzzing.md)"

  lazy val oracle: Either[Unavailable, Oracle] =
    sys.env.get(Variable).filter(_.nonEmpty) match
      case None       => Left(Unavailable.NotConfigured)
      case Some(path) => load(Path.of(path))

  /** The oracle, for a target that runs only once [[oracle]] is known to be present. */
  def loaded: Oracle =
    oracle.fold(why => throw new Violation(s"the libsodium oracle is unavailable: $why"), identity)

  private val A = ValueLayout.ADDRESS
  private val I = ValueLayout.JAVA_INT
  // `unsigned long long` and `size_t`, both 64 bits in the x86-64 and AArch64 Linux ABIs
  private val J = ValueLayout.JAVA_LONG

  private val functions: Vector[(String, FunctionDescriptor)] = Vector(
    "sodium_init" -> FunctionDescriptor.of(I),
    "sodium_version_string" -> FunctionDescriptor.of(A),
    "crypto_sign_ed25519_verify_detached" -> FunctionDescriptor.of(I, A, A, J, A),
    "crypto_sign_ed25519_seed_keypair" -> FunctionDescriptor.of(I, A, A, A),
    "crypto_sign_ed25519_detached" -> FunctionDescriptor.of(I, A, A, A, J, A),
    "crypto_core_ed25519_add" -> FunctionDescriptor.of(I, A, A, A),
    "crypto_core_ed25519_is_valid_point" -> FunctionDescriptor.of(I, A),
    "crypto_vrf_ietfdraft03_keypair_from_seed" -> FunctionDescriptor.of(I, A, A, A),
    "crypto_vrf_ietfdraft03_prove" -> FunctionDescriptor.of(I, A, A, A, J),
    "crypto_vrf_ietfdraft03_verify" -> FunctionDescriptor.of(I, A, A, A, A, J),
    "crypto_vrf_ietfdraft03_proof_to_hash" -> FunctionDescriptor.of(I, A, A),
    "crypto_generichash_blake2b" -> FunctionDescriptor.of(I, A, J, A, J, A, J)
  )

  private def load(path: Path): Either[Unavailable, Oracle] =
    for
      _ <- Either.cond(Files.isRegularFile(path), (), Unavailable.Missing(path.toString))
      lookup <- Either
        .catchNonFatal(SymbolLookup.libraryLookup(path, Arena.global()))
        .leftMap(Unavailable.Unloadable(path.toString, _))
      handles <- functions.traverse[[R] =>> Either[Unavailable, R], (String, MethodHandle)] {
        (name, descriptor) =>
          lookup
            .find(name)
            .toScala
            .map(symbol => name -> Linker.nativeLinker().downcallHandle(symbol, descriptor))
            .toRight(Unavailable.MissingFunction(name))
      }
      oracle = new Oracle(handles.toMap)
      _ <- oracle.init
      _ <- Either.cond(oracle.version == Version, (), Unavailable.WrongVersion(oracle.version))
      _ <- oracle.selfTest
    yield oracle

  private def hex(text: String): Array[Byte] =
    Bytes.fromHex(text).fold(e => throw new IllegalArgumentException(e.toString), _.toArray)

  /** The group order L. */
  val L: BigInt = (BigInt(1) << 252) + BigInt("27742317777372353535851937790883648493")

  /** The functions of the library the targets call. Each takes and returns arrays of the sizes the
    * C function reads and writes, checked before the call, so that no call reads past an argument.
    */
  final class Oracle private[Sodium] (handles: Map[String, MethodHandle]):
    private def call(name: String, args: AnyRef*): AnyRef = handles(name).invokeWithArguments(args*)
    private def status(name: String, args: AnyRef*): Int = call(name, args*) match
      case i: java.lang.Integer => i.intValue
      case other                => throw new IllegalStateException(s"$name returned $other")

    private def sized(what: String, bytes: Array[Byte], size: Int): Unit =
      if bytes.length != size then
        throw new IllegalArgumentException(s"$what is ${bytes.length} bytes, not $size")

    private def in(arena: Arena, bytes: Array[Byte]): MemorySegment =
      val segment = arena.allocate(math.max(1, bytes.length).toLong)
      MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0L, bytes.length)
      segment
    private def out(arena: Arena, size: Int): MemorySegment = arena.allocate(size.toLong)
    private def read(segment: MemorySegment, size: Int): Array[Byte] =
      segment.asSlice(0L, size.toLong).toArray(ValueLayout.JAVA_BYTE)
    private def length(bytes: Array[Byte]): java.lang.Long =
      java.lang.Long.valueOf(bytes.length.toLong)
    private def confined[A](body: Arena => A): A = Using.resource(Arena.ofConfined())(body)

    private[Sodium] def init: Either[Unavailable, Unit] =
      val s = status("sodium_init")
      Either.cond(s == 0 || s == 1, (), Unavailable.InitFailed(s))

    def version: String =
      call("sodium_version_string") match
        case segment: MemorySegment => segment.reinterpret(64L).getString(0L)
        case other                  => throw new IllegalStateException(s"version string $other")

    /** `crypto_sign_ed25519_verify_detached`: whether the signature verifies. */
    def ed25519Verify(
        publicKey: Array[Byte],
        signature: Array[Byte],
        message: Array[Byte]
    ): Boolean =
      sized("public key", publicKey, 32)
      sized("signature", signature, 64)
      confined { arena =>
        status(
          "crypto_sign_ed25519_verify_detached",
          in(arena, signature),
          in(arena, message),
          length(message),
          in(arena, publicKey)
        ) == 0
      }

    /** `crypto_sign_ed25519_seed_keypair`: the public key and the 64-byte `seed || public key`. */
    def ed25519Keypair(seed: Array[Byte]): (Array[Byte], Array[Byte]) =
      sized("seed", seed, 32)
      confined { arena =>
        val pk = out(arena, 32)
        val sk = out(arena, 64)
        val s = status("crypto_sign_ed25519_seed_keypair", pk, sk, in(arena, seed))
        if s != 0 then throw new IllegalStateException(s"seed keypair returned $s")
        (read(pk, 32), read(sk, 64))
      }

    /** `crypto_sign_ed25519_detached` with the 64-byte `seed || public key`, whose second half is
      * used as given: the challenge is computed over it.
      */
    def ed25519Sign(secretKey: Array[Byte], message: Array[Byte]): Array[Byte] =
      sized("secret key", secretKey, 64)
      confined { arena =>
        val sig = out(arena, 64)
        val s = status(
          "crypto_sign_ed25519_detached",
          sig,
          MemorySegment.NULL,
          in(arena, message),
          length(message),
          in(arena, secretKey)
        )
        if s != 0 then throw new IllegalStateException(s"sign returned $s")
        read(sig, 64)
      }

    /** `crypto_core_ed25519_add`: the sum of two points on the curve, of any order. */
    def ed25519Add(p: Array[Byte], q: Array[Byte]): Option[Array[Byte]] =
      sized("point", p, 32)
      sized("point", q, 32)
      confined { arena =>
        val r = out(arena, 32)
        Option.when(status("crypto_core_ed25519_add", r, in(arena, p), in(arena, q)) == 0)(
          read(r, 32)
        )
      }

    /** `crypto_core_ed25519_is_valid_point`: whether the encoding is canonical and a point of the
      * prime-order subgroup but the identity. A key either side accepts is canonical, on the curve
      * and not of small order, so for such a key false means mixed order.
      */
    def ed25519PrimeOrder(point: Array[Byte]): Boolean =
      sized("point", point, 32)
      confined(arena => status("crypto_core_ed25519_is_valid_point", in(arena, point)) == 1)

    /** `crypto_vrf_ietfdraft03_keypair_from_seed`: the public key and `seed || public key`. */
    def vrfKeypair(seed: Array[Byte]): (Array[Byte], Array[Byte]) =
      sized("seed", seed, 32)
      confined { arena =>
        val pk = out(arena, 32)
        val sk = out(arena, 64)
        val s = status("crypto_vrf_ietfdraft03_keypair_from_seed", pk, sk, in(arena, seed))
        if s != 0 then throw new IllegalStateException(s"VRF keypair returned $s")
        (read(pk, 32), read(sk, 64))
      }

    /** `crypto_vrf_ietfdraft03_prove` with `seed || public key`, the public key used as given. */
    def vrfProve(secretKey: Array[Byte], alpha: Array[Byte]): Option[Array[Byte]] =
      sized("secret key", secretKey, 64)
      confined { arena =>
        val proof = out(arena, 80)
        val s = status(
          "crypto_vrf_ietfdraft03_prove",
          proof,
          in(arena, secretKey),
          in(arena, alpha),
          length(alpha)
        )
        Option.when(s == 0)(read(proof, 80))
      }

    /** `crypto_vrf_ietfdraft03_verify`: the output of a proof that verifies. */
    def vrfVerify(
        publicKey: Array[Byte],
        proof: Array[Byte],
        alpha: Array[Byte]
    ): Option[Array[Byte]] =
      sized("public key", publicKey, 32)
      sized("proof", proof, 80)
      confined { arena =>
        val output = out(arena, 64)
        val s = status(
          "crypto_vrf_ietfdraft03_verify",
          output,
          in(arena, publicKey),
          in(arena, proof),
          in(arena, alpha),
          length(alpha)
        )
        Option.when(s == 0)(read(output, 64))
      }

    /** `crypto_vrf_ietfdraft03_proof_to_hash`: the output of a proof, not verified. */
    def vrfProofToHash(proof: Array[Byte]): Option[Array[Byte]] =
      sized("proof", proof, 80)
      confined { arena =>
        val output = out(arena, 64)
        Option.when(status("crypto_vrf_ietfdraft03_proof_to_hash", output, in(arena, proof)) == 0)(
          read(output, 64)
        )
      }

    /** `crypto_generichash_blake2b` without a key: Blake2b-256 of `input`. */
    def blake2b256(input: Array[Byte]): Array[Byte] =
      confined { arena =>
        val digest = out(arena, 32)
        val s = status(
          "crypto_generichash_blake2b",
          digest,
          java.lang.Long.valueOf(32L),
          in(arena, input),
          length(input),
          MemorySegment.NULL,
          java.lang.Long.valueOf(0L)
        )
        if s != 0 then throw new IllegalStateException(s"blake2b returned $s")
        read(digest, 32)
      }

    private[Sodium] def selfTest: Either[Unavailable, Unit] =
      val rfcKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
      val rfcSignature = hex(
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
      )
      val plusL = rfcSignature.take(32) ++ Scalars.encode(Scalars.decode(rfcSignature.drop(32)) + L)
      val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
      val pi = hex(
        "b6b4699f87d56126c9117a7da55bd0085246f4c56dbc95d20172612e9d38e8d7ca65e573a126ed88d4e30a46f80a666854d675cf3ba81de0de043c3774f061560f55edc256a787afe701677c0f602900"
      )
      val beta = hex(
        "5b49b554d05c0cd5a5325376b3387de59d924fd1e13ded44648ab33c21349a603f25b84ec5ed887995b33da5e3bfcb87cd2f64521c4c62cf825cffabbe5d31cc"
      )
      val (vrfKey, vrfSecret) = vrfKeypair(seed)
      val checks = Vector(
        "the RFC 8032 test-1 signature verifies" -> ed25519Verify(
          rfcKey,
          rfcSignature,
          Array.emptyByteArray
        ),
        "the RFC 8032 test-1 signature with S + L is refused" ->
          !ed25519Verify(rfcKey, plusL, Array.emptyByteArray),
        "the draft-03 example 10 seed gives its key" -> vrfKey.sameElements(rfcKey),
        "the draft-03 example 10 proof is proved" ->
          vrfProve(vrfSecret, Array.emptyByteArray).exists(_.sameElements(pi)),
        "the draft-03 example 10 proof verifies to its output" ->
          vrfVerify(rfcKey, pi, Array.emptyByteArray).exists(_.sameElements(beta))
      )
      checks.collectFirst { case (what, false) => Unavailable.SelfTestFailed(what) }.toLeft(())

/** Scalars as 32 little-endian bytes, for the cases the targets build at and above L. */
object Scalars:
  def decode(bytes: Array[Byte]): BigInt = BigInt(1, bytes.reverse)

  /** `value` modulo 2^256, little-endian in 32 bytes. */
  def encode(value: BigInt): Array[Byte] =
    val reduced = value.mod(BigInt(1) << 256)
    val big = reduced.toByteArray.reverse.take(32)
    big ++ Array.fill(32 - big.length)(0.toByte)
