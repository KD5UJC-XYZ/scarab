// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.bls

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import cats.effect.kernel.Sync
import cats.mtl.Raise
import cats.syntax.all.*
import scala.util.Using
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
  /** The library jblst ships for this platform, pinned by its sha-256 ([[Blst.pinned]]): on
    * Linux/amd64 a portable build, which chooses the ADX/BMI2 multiplication at run time (ADR 0025;
    * CI checks it with `scripts/check_native_isa.py`).
    */
  case Portable

  /** Any other library found as the resource `blstJNI` loads (a system property pointing elsewhere,
    * or another jar first on the class path), by the sha-256 of its bytes.
    */
  case Other(sha256: String)

  /** No such resource: `blstJNI` falls back to `System.loadLibrary("blst")`. */
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
  case G1Uncompress, G2Uncompress, Pairing

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
      s"the BLS12-381 library in use is not the pinned jblst build ($build) and $cpu; " +
        "load the library jblst ships (docs/adr/0025-jblst.md)"
    case Unusable(answer, failure) =>
      s"the BLS12-381 library fails its known answer $answer: $failure"

/** The check that the JVM is going to use the libblst jblst ships (ADR 0025). jblst's `blstJNI`
  * looks up `<os>/<arch>/libblst.so` as a resource of its own package, copies it to a temporary
  * file and loads it.
  */
object Blst:
  /** sha-256 of each library in `tech.pegasys:jblst` 0.3.18 (blst v0.3.17), by resource name. */
  val pinned: Map[String, String] = Map(
    "Linux/amd64/libblst.so" -> "6a87d87cc982078dcf40f034f21959839d1fe20d45229d37de9c82c178f2a0ce",
    "Linux/aarch64/libblst.so" -> "b47e669bcd521a0cc3b5753beab0c9ddb5cc81988d5d555722de3149db75b52b",
    "Mac/x86_64/libblst.dylib" -> "17aa48bb47a6df714d68ebdfa622341866e3df5d5ba632c3abf526f92f7cbb98",
    "Mac/aarch64/libblst.dylib" -> "7fd78cc498f3f3998070f50d5645cff71b1af01be94699da393235a5cfebdbe4",
    "Windows/amd64/blst.dll" -> "3cbd35dbf7859829a38720444098b72cfe9d2d79c1f39c2e43fa26b94e1c99a5"
  )

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
        val sha = sha256Hex(Using.resource(in)(_.readAllBytes()))
        if pinned.get(name).contains(sha) then LibraryBuild.Portable else LibraryBuild.Other(sha)
  }

  /** The processor, from `/proc/cpuinfo` (Linux; elsewhere [[CpuSupport.Unknown]]). */
  def cpu[F[_]: Sync]: F[CpuSupport] = Sync[F].blocking {
    val cpuinfo: Path = Paths.get("/proc/cpuinfo")
    if Files.isReadable(cpuinfo) then
      CpuSupport.parse(new String(Files.readAllBytes(cpuinfo), StandardCharsets.UTF_8))
    else CpuSupport.Unknown
  }

  /** Whether the JVM may load the library. On Linux/amd64 it may when it is the pinned jblst build,
    * or when the processor is shown to have every feature an unpinned build may assume. Other
    * platforms are not affected: libblst is built there without such an assumption.
    */
  def admissible(report: BlstReport): Either[BlstProblem, BlstReport] = report match
    case BlstReport(Platform.LinuxAmd64, cpu, build)
        if build != LibraryBuild.Portable && !cpu.complete =>
      Left(BlstProblem.LibraryNeedsMissingCpuFeatures(cpu.missing, build))
    case ok => Right(ok)

  /** Known answers for G1/G2 decompression and pairing bilinearity. Computing them can cause SIGILL
    * on a library the processor cannot run, so this runs after [[admissible]]; it also shows that
    * blstJNI and the native library match.
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
