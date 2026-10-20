// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.blst

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import cats.effect.kernel.Sync
import cats.mtl.Raise
import cats.syntax.all.*
import scala.util.control.NonFatal
import supranational.blst.{blstJNI, P1, P2, PT}

/** CPU features of x86-64 that the upstream libblst assumes without asking. */
enum CpuFeature:
  case Adx, Bmi2

  /** The name in the `flags` line of `/proc/cpuinfo`. */
  def flag: String = this match
    case Adx  => "adx"
    case Bmi2 => "bmi2"

/** What the processor offers. [[Unknown]] is a platform without a readable `/proc/cpuinfo`. */
enum CpuSupport:
  case Known(features: Set[CpuFeature])
  case Unknown

  /** The features of [[CpuFeature]] that the processor is known to lack. */
  def missing: Set[CpuFeature] = this match
    case Known(features) => CpuFeature.values.toSet -- features
    case Unknown         => Set.empty

  /** The processor is known to have every feature the upstream library needs. */
  def complete: Boolean = this match
    case Known(features) => features.size == CpuFeature.values.length
    case Unknown         => false

object CpuSupport:
  /** What `/proc/cpuinfo` says: the features that every processor in the file has (a hybrid machine
    * can differ between cores, and a thread may run on any of them).
    */
  def parse(cpuinfo: String): CpuSupport =
    val flagLines = cpuinfo.linesIterator.collect {
      case line if line.startsWith("flags") && line.contains(':') =>
        line.dropWhile(_ != ':').drop(1).trim.split("\\s+").toSet
    }.toList
    flagLines match
      case Nil => Unknown
      case _ =>
        val common = flagLines.reduce(_ intersect _)
        Known(CpuFeature.values.toSet.filter(f => common.contains(f.flag)))

/** Which libblst the JVM is going to load. */
enum LibraryBuild:
  /** The library of this repository: blst built with `-D__BLST_PORTABLE__`, which chooses the
    * ADX/BMI2 multiplication at run time.
    */
  case Portable

  /** Any other library found as the resource blst-java loads (the one inside blst-java, unless a
    * system property points elsewhere), by the sha-256 of its bytes.
    */
  case Other(sha256: String)

  /** No such resource: blst-java falls back to `System.loadLibrary("blst")`. */
  case OnLibraryPath

/** Where this JVM runs, as far as the choice of the native library goes. */
enum Platform:
  case LinuxAmd64
  case Other

object Platform:
  def current: Platform =
    val os = java.lang.System.getProperty("os.name", "")
    val arch = java.lang.System.getProperty("os.arch", "")
    if os.startsWith("Linux") && (arch == "amd64" || arch == "x86_64") then LinuxAmd64 else Other

/** What the start-up check found out; the node logs it. */
final case class BlstReport(platform: Platform, cpu: CpuSupport, build: LibraryBuild)

/** What blst has to compute right (a known answer) at start-up. */
enum KnownAnswer:
  case G1Uncompress, G2Uncompress, Pairing, G1RawDst, G2RawDst

/** Why the node refuses to start with the BLS12-381 library it would use. */
enum BlstProblem:
  /** The processor lacks features the loaded library uses unconditionally (or the processor cannot
    * be read and the library is not the portable one): the JVM would die with SIGILL.
    */
  case LibraryNeedsMissingCpuFeatures(missing: Set[CpuFeature], build: LibraryBuild)

  /** The library loads but computes a wrong answer, or does not load. */
  case Unusable(answer: KnownAnswer, failure: String)

  def message: String = this match
    case LibraryNeedsMissingCpuFeatures(missing, build) =>
      val cpu =
        if missing.isEmpty then "the processor's features cannot be read"
        else s"the processor lacks ${missing.map(_.flag).toList.sorted.mkString(", ")}"
      s"the BLS12-381 library in use is not the portable build ($build) and $cpu; " +
        "put blst-native before blst-java on the class path (docs/adr/0012-portable-blst.md)"
    case Unusable(answer, failure) =>
      s"the BLS12-381 library fails its known answer $answer: $failure"

/** The portable libblst of this repository and the check that the JVM is going to use it (ADR
  * 0012). blst-java (`blstJNI`) looks up `<os>/<arch>/libblst.so` as a resource of its own package,
  * copies it to a temporary file and loads it; `blst-native` ships the resource of Linux/amd64 and,
  * being a project before the libraries on the class path, is found first.
  */
object Blst:
  /** sha-256 of `supranational/blst/Linux/amd64/libblst.so` in this repository. */
  val portableSha256: String = "5e5176a1056c0fcf5b253315fefb997466187f79977a0f65e1ee78e74c569e21"

  /** The name `blstJNI` gives the library resource (`blstJNI.java`, SWIG output of blst
    * `bindings/java`): `<os.name up to the first blank>/<os.arch>/libblst.so`, replaceable through
    * `supranational.blst.jniResource`.
    */
  def resourceName(properties: String => Option[String]): String =
    properties("supranational.blst.jniResource").getOrElse {
      val os = properties("os.name").getOrElse("").replaceFirst(" .*", "")
      val arch = properties("os.arch").getOrElse("")
      s"$os/$arch/${java.lang.System.mapLibraryName("blst")}"
    }

  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  /** The build of the library `blstJNI` is going to load. Looking up the resource does not
    * initialise `blstJNI`, so nothing is loaded yet.
    */
  def build[F[_]: Sync]: F[LibraryBuild] = Sync[F].blocking {
    val name = resourceName(k => Option(java.lang.System.getProperty(k)))
    Option(classOf[blstJNI].getResourceAsStream(name)) match
      case None => LibraryBuild.OnLibraryPath
      case Some(in) =>
        val bytes =
          try in.readAllBytes()
          finally in.close()
        val sha = sha256Hex(bytes)
        if sha == portableSha256 then LibraryBuild.Portable else LibraryBuild.Other(sha)
  }

  /** The processor, from `/proc/cpuinfo` (Linux; elsewhere [[CpuSupport.Unknown]]). */
  def cpu[F[_]: Sync]: F[CpuSupport] = Sync[F].blocking {
    val cpuinfo: Path = Paths.get("/proc/cpuinfo")
    if Files.isReadable(cpuinfo) then
      CpuSupport.parse(new String(Files.readAllBytes(cpuinfo), StandardCharsets.UTF_8))
    else CpuSupport.Unknown
  }

  /** Whether the JVM may load the library. On Linux/amd64 it may when it is the portable build, or
    * when the processor is shown to have every feature the upstream build assumes. Other platforms
    * are not affected: libblst is built there without such an assumption.
    */
  def admissible(report: BlstReport): Either[BlstProblem, BlstReport] = report match
    case BlstReport(Platform.LinuxAmd64, cpu, build)
        if build != LibraryBuild.Portable && !cpu.complete =>
      Left(BlstProblem.LibraryNeedsMissingCpuFeatures(cpu.missing, build))
    case ok => Right(ok)

  /** Known answers for G1/G2 decompression, pairing bilinearity and the raw-byte DST adapter.
    * Computing them can cause SIGILL on a library the processor cannot run, so this runs after
    * [[admissible]]; it also shows that blstJNI and the native library match.
    */
  def knownAnswers[F[_]: Sync]: F[Option[(KnownAnswer, String)]] = Sync[F].blocking {
    def attempt(answer: KnownAnswer)(check: => Boolean): Option[(KnownAnswer, String)] =
      try if check then None else Some(answer -> "wrong result")
      catch
        // an UnsatisfiedLinkError (a native method the library lacks) is a LinkageError, which
        // NonFatal does not match
        case NonFatal(e)     => Some(answer -> e.getClass.getSimpleName)
        case e: LinkageError => Some(answer -> e.getClass.getSimpleName)
    val g1 = hex(KnownAnswers.g1)
    val g2 = hex(KnownAnswers.g2)
    attempt(KnownAnswer.G1Uncompress)(new P1(g1).compress().sameElements(g1))
      .orElse(attempt(KnownAnswer.G2Uncompress)(new P2(g2).compress().sameElements(g2)))
      .orElse(
        Option
          .when(Platform.current == Platform.LinuxAmd64)(
            attempt(KnownAnswer.G1RawDst)(
              RawDst
                .hashG1(Array(0x3f.toByte), Array(0x90.toByte))
                .sameElements(hex(KnownAnswers.g1RawDst))
            )
              .orElse(
                attempt(KnownAnswer.G2RawDst)(
                  RawDst
                    .hashG2(Array(0x3f.toByte), Array(0x90.toByte))
                    .sameElements(hex(KnownAnswers.g2RawDst))
                )
              )
          )
          .flatten
      )
      .orElse(attempt(KnownAnswer.Pairing) {
        // e(2 G1, G2) = e(G1, 2 G2)
        val a = new PT(new P2(g2).to_affine(), new P1(g1).dbl().to_affine())
        val b = new PT(new P2(g2).dbl().to_affine(), new P1(g1).to_affine())
        PT.finalverify(a, b)
      })
  }

  private def hex(s: String): Array[Byte] =
    s.grouped(2).map(java.lang.Integer.parseInt(_, 16).toByte).toArray

  /** The start-up check: the report on success, otherwise the problem raised. */
  def verify[F[_]: Sync](using Raise[F, BlstProblem]): F[BlstReport] =
    for
      cpuSupport <- cpu[F]
      libraryBuild <- build[F]
      report <- admissible(BlstReport(Platform.current, cpuSupport, libraryBuild)).fold(
        Raise[F, BlstProblem].raise,
        _.pure[F]
      )
      wrong <- knownAnswers[F]
      _ <- wrong.traverse_((answer, failure) =>
        Raise[F, BlstProblem].raise(BlstProblem.Unusable(answer, failure))
      )
    yield report

  /** The compressed generators, from the BLS12-381 standard
    * (draft-irtf-cfrg-pairing-friendly-curves).
    */
  private object KnownAnswers:
    val g1 =
      "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"
    val g2 =
      "93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8"
    // Actual pinned plutus-1.70.0.0/cardano-crypto-class outputs, hash-vectors.jsonl.
    val g1RawDst =
      "b35ffb9cff40045da92d89f5f0227351b62c8d37037e8469ad925ce5f9ae291fe205a106683e0fc6af59e72ad1caecb9"
    val g2RawDst =
      "ae595116abcb38c626713d756045cd7fb9e7a695b1d5fad4e023bbe12bd6ea3ab9d9e1fc0b06ecc7a6fc56cb1a5cfa1f1162e00ce1b462344cd6ac25dd6fa6bb515666bba7726eaf24cd4752691df3b56752f93d270e91635bd2e042cafa0ee5"
