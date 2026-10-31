// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import cats.effect.kernel.Sync
import cats.mtl.Raise
import cats.syntax.all.*
import org.scijava.nativelib.NativeLibraryUtil
import org.scijava.nativelib.NativeLibraryUtil.Architecture
import scala.util.Using
import scalus.crypto.jni.{Blst as JniBlst, CryptoJni, Secp256k1 as JniSecp256k1, Sodium}

/** The platforms `org.scalus:scalus-crypto-jni:0.1.0` ships `libscalus_crypto` for, the resource of
  * each in that jar and the sha-256 of its bytes (ADR 0020). Only these bytes are admitted.
  *
  * @param architecture
  *   the platform as native-lib-loader 2.5.0 names it (`NativeLibraryUtil.getArchitecture`)
  * @param resource
  *   the class path resource native-lib-loader extracts on this platform
  * @param sha256
  *   the sha-256 of that resource in the pinned jar
  */
enum CryptoPlatform(val architecture: Architecture, val resource: String, val sha256: String):
  case Linux64
      extends CryptoPlatform(
        Architecture.LINUX_64,
        "natives/linux_64/libscalus_crypto.so",
        "ca17484cefcd8e6ecf5c706d1fa1144a874283516eb78e52a535daa02894934a"
      )
  case LinuxArm64
      extends CryptoPlatform(
        Architecture.LINUX_ARM64,
        "natives/linux_arm64/libscalus_crypto.so",
        "8473caf6b844af4a40e502ffb8d6e210c3ff43867558ef3aeff17359198f293d"
      )
  case Osx64
      extends CryptoPlatform(
        Architecture.OSX_64,
        "natives/osx_64/libscalus_crypto.dylib",
        "d6ed19b01a08a369ed28f46edb4b5ffa7c2c59e1f7c9f5b3539bd4650b8e8acf"
      )
  case OsxArm64
      extends CryptoPlatform(
        Architecture.OSX_ARM64,
        "natives/osx_arm64/libscalus_crypto.dylib",
        "6897d67fbd5fc8df05093fbe80c244401693afd3baaedc2e9c7977fed31b7ff1"
      )
  case Windows64
      extends CryptoPlatform(
        Architecture.WINDOWS_64,
        "natives/windows_64/scalus_crypto.dll",
        "42325071a91a1bb8072d161832f879338c47ec243b095c4164ec51173eb6bda7"
      )

  /** The platform directory of the jar (`linux_64`), for the log. */
  def directory: String = resource.split('/')(1)

object CryptoPlatform:
  def of(architecture: Architecture): Option[CryptoPlatform] =
    values.find(_.architecture == architecture)

/** The library `CryptoJni` is going to load, found as native-lib-loader will find it. */
enum CryptoLibrary:
  /** A file on `java.library.path`: `NativeLoader.loadLibrary` tries `System.loadLibrary` first, so
    * this file is loaded instead of any resource.
    */
  case OnLibraryPath(file: Path, sha256: String)

  /** A class path resource, extracted to `java.io.tmpdir` and loaded from there. */
  case Resource(name: String, sha256: String)

  /** The sha-256 of the library's bytes. */
  def digest: String = this match
    case OnLibraryPath(_, sha) => sha
    case Resource(_, sha)      => sha

/** What the start-up check found; the node logs it. */
final case class NativeCryptoReport(platform: CryptoPlatform, library: CryptoLibrary)

/** What libscalus_crypto has to compute right at start-up. Each signature scheme has a valid and a
  * forged case, so a library that answers `true` to everything fails too.
  */
enum CryptoKnownAnswer:
  case Ed25519Valid, Ed25519Forged
  case EcdsaValid, EcdsaForged
  case SchnorrValid, SchnorrForged
  case G1Compression, G2Compression
  case Pairing, PairingMismatch
  case G1HashToGroup, G2HashToGroup

/** Why the node refuses to start with the Plutus native crypto library it would use. Without the
  * library the Ed25519, secp256k1 and BLS12-381 builtins are unavailable, so every Plutus V1/V2
  * script that verifies a signature could not be evaluated.
  */
enum NativeCryptoProblem:
  /** scalus-crypto-jni has no library for this platform. */
  case UnsupportedPlatform(architecture: Architecture)

  /** Neither `java.library.path` nor the class path has the library. */
  case Missing(platform: CryptoPlatform)

  /** The library that would be loaded is not the pinned build for this platform. */
  case Unpinned(platform: CryptoPlatform, library: CryptoLibrary)

  /** `CryptoJni` did not load the library: the extraction to `java.io.tmpdir` or the `dlopen`
    * failed (a `noexec` temporary directory, a missing directory, a C library older than the
    * library needs). `cause` is the initialisation error when there was one; otherwise the loader
    * only printed to stderr.
    */
  case NotLoaded(platform: CryptoPlatform, cause: Option[Throwable])

  /** A known answer came out wrong. */
  case WrongAnswer(answer: CryptoKnownAnswer)

  /** A known answer threw. */
  case AnswerDefect(answer: CryptoKnownAnswer, cause: Throwable)

  def message: String = this match
    case UnsupportedPlatform(architecture) =>
      s"scalus-crypto-jni has no native library for $architecture"
    case Missing(platform) =>
      s"libscalus_crypto for ${platform.directory} is neither on java.library.path nor on the class path"
    case Unpinned(platform, CryptoLibrary.OnLibraryPath(file, sha)) =>
      s"libscalus_crypto on java.library.path ($file, sha-256 $sha) would be loaded instead of " +
        s"the pinned ${platform.directory} build"
    case Unpinned(platform, CryptoLibrary.Resource(name, sha)) =>
      s"the libscalus_crypto resource $name (sha-256 $sha) is not the pinned ${platform.directory} build"
    case NotLoaded(platform, cause) =>
      s"libscalus_crypto for ${platform.directory} did not load" + cause.fold(
        "; java.io.tmpdir must be writable and allow execution"
      )(e => s": ${e.getClass.getName}: ${Option(e.getMessage).getOrElse("")}")
    case WrongAnswer(answer) => s"libscalus_crypto fails its known answer $answer"
    case AnswerDefect(answer, cause) =>
      s"libscalus_crypto fails its known answer $answer: ${cause.getClass.getName}"

/** The native library of Scalus's Plutus builtins (libsodium, libsecp256k1 and blst in one
  * `libscalus_crypto`, loaded by `scalus.crypto.jni.CryptoJni`) and the check that the JVM is going
  * to use the pinned build of it (ADR 0020).
  *
  * `CryptoJni` loads the library once, when the class initialises, and reports a failure only on
  * stderr; a failure of native-lib-loader's own initialisation (an unusable `java.io.tmpdir`)
  * escapes as an `ExceptionInInitializerError` and later as `NoClassDefFoundError`. [[available]]
  * turns all of that into a value.
  */
object NativeCrypto:
  private val libraryName = "scalus_crypto"

  /** Whether `CryptoJni` loaded its library. Never throws: an initialisation error is `false`. */
  def available: Boolean = loaded.isRight

  private def loaded: Either[Option[Throwable], Unit] =
    // ExceptionInInitializerError and NoClassDefFoundError are LinkageErrors, which NonFatal does
    // not match
    Thrown
      .orAlso[LinkageError, Boolean](CryptoJni.isEnabled)
      .leftMap(Some(_))
      .flatMap(Either.cond(_, (), None))

  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** The platform as native-lib-loader sees it. Reading it does not initialise `CryptoJni`. */
  def platform[F[_]: Sync]: F[Either[NativeCryptoProblem, CryptoPlatform]] = Sync[F].delay {
    val architecture = NativeLibraryUtil.getArchitecture
    CryptoPlatform.of(architecture).toRight(NativeCryptoProblem.UnsupportedPlatform(architecture))
  }

  /** The library native-lib-loader 2.5.0 will load, in its order: a file named
    * `System.mapLibraryName("scalus_crypto")` in a directory of `java.library.path`
    * (`System.loadLibrary`), then the resource under `natives/`, the class path root and
    * `META-INF/lib`, each followed by the platform directory. Nothing is loaded.
    */
  def locate[F[_]: Sync]: F[Option[CryptoLibrary]] = Sync[F].blocking {
    val fileName = java.lang.System.mapLibraryName(libraryName)
    val onPath = Option(java.lang.System.getProperty("java.library.path")).toList
      .flatMap(_.split(java.io.File.pathSeparatorChar).toList)
      .filter(_.nonEmpty)
      .iterator
      .map(dir => Paths.get(dir).resolve(fileName))
      .find(Files.isRegularFile(_))
      .map(file => CryptoLibrary.OnLibraryPath(file, sha256Hex(Files.readAllBytes(file))))
    val loader = classOf[NativeLibraryUtil].getClassLoader
    def resource: Option[CryptoLibrary] =
      List("natives/", "", "META-INF/lib").iterator
        .map(prefix =>
          NativeLibraryUtil.getPlatformLibraryPath(prefix) +
            NativeLibraryUtil.getPlatformLibraryName(libraryName)
        )
        .flatMap(name =>
          Option(loader.getResourceAsStream(name)).map { in =>
            val bytes = Using.resource(in)(_.readAllBytes())
            CryptoLibrary.Resource(name, sha256Hex(bytes))
          }
        )
        .nextOption()
    onPath.orElse(resource)
  }

  /** The library found may be loaded when it is the pinned build of the platform. */
  def admissible(
      platform: CryptoPlatform,
      library: Option[CryptoLibrary]
  ): Either[NativeCryptoProblem, NativeCryptoReport] =
    library match
      case None => Left(NativeCryptoProblem.Missing(platform))
      case Some(found) if found.digest != platform.sha256 =>
        Left(NativeCryptoProblem.Unpinned(platform, found))
      case Some(found) => Right(NativeCryptoReport(platform, found))

  /** Loads the library (initialises `CryptoJni`) and computes the known answers: Ed25519 (RFC 8032,
    * test 1), ECDSA and Schnorr over secp256k1 (the vectors of `Secp256k1Suite`; BIP-340 vector 0),
    * the BLS12-381 generators' compression and pairing bilinearity, and `hashToGroup` with a
    * non-ASCII domain separation tag (the pinned Haskell answers of
    * `fixtures/reference/plutus-boundaries/hash-vectors.jsonl`).
    */
  def knownAnswers[F[_]: Sync](platform: CryptoPlatform): F[Option[NativeCryptoProblem]] =
    Sync[F].blocking {
      def attempt(answer: CryptoKnownAnswer)(check: => Boolean): Option[NativeCryptoProblem] =
        Thrown
          .orAlso[LinkageError, Boolean](check)
          .fold(
            e => Some(NativeCryptoProblem.AnswerDefect(answer, e)),
            Option.unless(_)(NativeCryptoProblem.WrongAnswer(answer))
          )
      import KnownAnswers.*
      import CryptoKnownAnswer.*
      loaded.left
        .map(NativeCryptoProblem.NotLoaded(platform, _))
        .swap
        .toOption
        .orElse(attempt(Ed25519Valid)(Sodium.ed25519VerifyDetached(edSig, Array.empty, edKey)))
        .orElse(
          attempt(Ed25519Forged)(!Sodium.ed25519VerifyDetached(edSig, Array(0.toByte), edKey))
        )
        .orElse(attempt(EcdsaValid)(JniSecp256k1.ecdsaVerify(ecdsaMsg, ecdsaSig, ecdsaKey)))
        .orElse(
          attempt(EcdsaForged)(!JniSecp256k1.ecdsaVerify(flipped(ecdsaMsg), ecdsaSig, ecdsaKey))
        )
        .orElse(
          attempt(SchnorrValid)(JniSecp256k1.schnorrVerify(schnorrSig, schnorrMsg, schnorrKey))
        )
        .orElse(
          attempt(SchnorrForged)(
            !JniSecp256k1.schnorrVerify(schnorrSig, flipped(schnorrMsg), schnorrKey)
          )
        )
        .orElse(
          attempt(G1Compression)(JniBlst.p1Compress(JniBlst.p1Uncompress(g1)) sameElements g1)
        )
        .orElse(
          attempt(G2Compression)(JniBlst.p2Compress(JniBlst.p2Uncompress(g2)) sameElements g2)
        )
        .orElse {
          // e(2 G1, G2) = e(G1, 2 G2), and both differ from e(G1, G2)
          lazy val p1 = JniBlst.p1Uncompress(g1)
          lazy val p2 = JniBlst.p2Uncompress(g2)
          lazy val left = JniBlst.millerLoop(JniBlst.p1AddOrDouble(p1, p1), p2)
          attempt(Pairing)(
            JniBlst.finalVerify(left, JniBlst.millerLoop(p1, JniBlst.p2AddOrDouble(p2, p2)))
          ).orElse(attempt(PairingMismatch)(!JniBlst.finalVerify(left, JniBlst.millerLoop(p1, p2))))
        }
        .orElse(
          attempt(G1HashToGroup)(
            JniBlst.p1Compress(JniBlst.p1HashTo(hashMessage, hashDst)) sameElements g1Hashed
          )
        )
        .orElse(
          attempt(G2HashToGroup)(
            JniBlst.p2Compress(JniBlst.p2HashTo(hashMessage, hashDst)) sameElements g2Hashed
          )
        )
    }

  /** The start-up check: the report on success, otherwise the problem raised. */
  def verify[F[_]: Sync](using Raise[F, NativeCryptoProblem]): F[NativeCryptoReport] =
    for
      platform <- platform[F].flatMap(_.fold(Raise[F, NativeCryptoProblem].raise, _.pure[F]))
      library <- locate[F]
      report <- admissible(platform, library).fold(Raise[F, NativeCryptoProblem].raise, _.pure[F])
      wrong <- knownAnswers[F](platform)
      _ <- wrong.traverse_(Raise[F, NativeCryptoProblem].raise)
    yield report

  private def flipped(bytes: Array[Byte]): Array[Byte] =
    bytes.updated(0, (bytes(0) ^ 1).toByte)

  private[plutus] object KnownAnswers:
    def hex(s: String): Array[Byte] =
      s.grouped(2).map(java.lang.Integer.parseInt(_, 16).toByte).toArray

    // RFC 8032 section 7.1, test 1: the empty message
    val edKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
    val edSig = hex(
      "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
    )
    // the low-s RFC 6979 signature of Secp256k1Suite's key and message
    val ecdsaKey = hex("032c8c31fc9f990c6b55e3865a184a4ce50e09481f2eaeb3e60ec1cea13a6ae645")
    val ecdsaMsg = hex("d3444c2dad5f0229ca3e517eddddc8f61f53e804d8a0edb42c2d7bdb5dd05c00")
    val ecdsaSig = hex(
      "8ad800aa3946be07783be773436ead22f22b73610b217c51ff5e8da49fff2c10608619f6544f56929c3777e59a4b8f139b3d74a7393387271c8f49f2959cefe7"
    )
    // BIP-340 test vector 0
    val schnorrKey = hex("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9")
    val schnorrMsg = Array.fill[Byte](32)(0)
    val schnorrSig = hex(
      "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0"
    )
    // the compressed generators (draft-irtf-cfrg-pairing-friendly-curves)
    val g1 = hex(
      "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"
    )
    val g2 = hex(
      "93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8"
    )
    // plutus-1.70.0.0/cardano-crypto-class outputs for message 0x3f and tag 0x90,
    // fixtures/reference/plutus-boundaries/hash-vectors.jsonl
    val hashMessage = Array(0x3f.toByte)
    val hashDst = Array(0x90.toByte)
    val g1Hashed = hex(
      "b35ffb9cff40045da92d89f5f0227351b62c8d37037e8469ad925ce5f9ae291fe205a106683e0fc6af59e72ad1caecb9"
    )
    val g2Hashed = hex(
      "ae595116abcb38c626713d756045cd7fb9e7a695b1d5fad4e023bbe12bd6ea3ab9d9e1fc0b06ecc7a6fc56cb1a5cfa1f1162e00ce1b462344cd6ac25dd6fa6bb515666bba7726eaf24cd4752691df3b56752f93d270e91635bd2e042cafa0ee5"
    )
