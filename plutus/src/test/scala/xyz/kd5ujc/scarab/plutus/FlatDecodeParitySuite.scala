// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.nio.file.{Files, Path, Paths}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal
import scalus.serialization.flat.FlatDecodingError
import scalus.uplc.{DeBruijnedProgram, Program, ScarabProgramView}
import xyz.kd5ujc.scarab.codec.Bytes

/** [[FlatScan.decode]] against the flat decoder of Scalus 1.3.0, which production no longer uses,
  * over every script the fixtures hold: the upstream conformance programs (encoded by Scalus, and
  * the original-byte sidecars), the boundary, costing, upstream-boundary and transaction script
  * corpora, and the flat decoding probes. Where both decode, the programs are equal (after the
  * repair of Scalus's narrowed wide indices); where they disagree, the case is one of the pinned
  * differences below, each of which the reference decides ([[FlatDecodeOracleSuite]]).
  */
class FlatDecodeParitySuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  /** How the two decoders may differ, each a place where Scalus differs from the reference. */
  private enum Difference:
    /** Scalus throws on a `data` constant the reference reads: borer stops at 1000 levels and
      * refuses bytes after the item.
      */
    case ScalusRefusesData

    /** Scalus replaces what is not UTF-8 in a `string` constant; the reference refuses it. */
    case ScalusAcceptsInvalidUtf8

    /** Scalus wraps a version component beyond 32 bits; Scarab keeps it out of every version. */
    case ScalusNarrowsVersion

    /** Scalus's `decodeFlat` reads neither the filler after the program nor the end of the input;
      * the reference's strict decoder requires both.
      */
    case ScalusIgnoresFiller

    /** Scalus wraps a natural beyond 64 bits (a variable index or a `constr` tag); the reference
      * refuses it (`scope-natural-65` of the boundary corpus).
      */
    case ScalusWrapsWord64

    /** Scalus reads a `Word64` of any number of groups; the reference refuses an eleventh. */
    case ScalusAcceptsLongWord64

    /** Scalus reads a tag 2 or 3 big number behind a wide head; the reference reads one only behind
      * `0xc2` or `0xc3`.
      */
    case ScalusAcceptsWideBigNumberHead

  private val expected: Map[String, Difference] =
    Vector(FlatDecodeCorpus.BeyondBorer, FlatDecodeCorpus.NearSizeBound)
      .flatMap(levels =>
        Vector("", "-result", "-serialised").map(suffix => s"data-depth-$levels$suffix")
      )
      .appendedAll(
        Vector(
          s"data-depth-${FlatDecodeCorpus.BeyondBorer}-equal",
          "data-trailing-zero",
          "data-trailing-bytes",
          "data-trailing-truncated-item"
        )
      )
      .map(_ -> Difference.ScalusRefusesData)
      .toMap ++ Map(
      "string-not-utf8" -> Difference.ScalusAcceptsInvalidUtf8,
      "string-surrogate" -> Difference.ScalusAcceptsInvalidUtf8,
      "version-major-wide" -> Difference.ScalusNarrowsVersion,
      "version-minor-wide" -> Difference.ScalusNarrowsVersion,
      "var-10-groups-bit64" -> Difference.ScalusWrapsWord64,
      "var-11-groups-padded" -> Difference.ScalusAcceptsLongWord64,
      "data-tag2-wide-head" -> Difference.ScalusAcceptsWideBigNumberHead,
      "data-tag3-wide-head" -> Difference.ScalusAcceptsWideBigNumberHead
    ) ++ Vector(
      "filler-trailing-zero-byte",
      "filler-trailing-ff-byte",
      "filler-without-one",
      "filler-misaligned",
      "cbor-remainder-and-flat-trailing"
    ).map(_ -> Difference.ScalusIgnoresFiller)

  private enum Outcome:
    case Equal, BothRefuse
    case Differs(difference: Difference)
    case Unexplained(detail: String)

  private def compare(flat: Array[Byte]): Outcome =
    val scalus = Try(DeBruijnedProgram.fromFlatEncoded(flat))
    (FlatScan.decode(flat), scalus.toEither) match
      case (Right(ours), Right(theirs)) =>
        val repaired = ScarabProgramView.withTerm(
          theirs,
          WideIndices.repair(theirs.term, ours.scan.wideVariables)
        )
        if ours.program == repaired then Outcome.Equal
        else if ours.program.term == repaired.term then
          Outcome.Differs(Difference.ScalusNarrowsVersion)
        else Outcome.Unexplained("different programs")
      case (Left(_), Left(_)) => Outcome.BothRefuse
      // Scalus 1.4.0 refuses a malformed `value` constant while decoding, as the reference's
      // `buildValueWith` does; the walk builds it and records the fault for admission to refuse.
      case (Right(ours), Left(_: FlatDecodingError)) if ours.scan.malformedValue.isDefined =>
        Outcome.BothRefuse
      case (Right(_), Left(_)) =>
        Outcome.Differs(Difference.ScalusRefusesData)
      case (Left(FlatScan.Failure.InvalidUtf8), Right(_)) =>
        Outcome.Differs(Difference.ScalusAcceptsInvalidUtf8)
      case (
            Left(FlatScan.Failure.UnterminatedFiller | FlatScan.Failure.TooMuchSpace(_)),
            Right(_)
          ) =>
        Outcome.Differs(Difference.ScalusIgnoresFiller)
      case (Left(FlatScan.Failure.WordOverflow), Right(_)) =>
        Outcome.Differs(Difference.ScalusWrapsWord64)
      case (Left(FlatScan.Failure.WordTooLong), Right(_)) =>
        Outcome.Differs(Difference.ScalusAcceptsLongWord64)
      case (
            Left(FlatScan.Failure.MalformedData(PlutusData.DecodeError.BigNumberWideHead(_))),
            Right(_)
          ) =>
        Outcome.Differs(Difference.ScalusAcceptsWideBigNumberHead)
      case (Left(failure), Right(_)) => Outcome.Unexplained(s"only Scarab refuses: $failure")

  private def onBigStack[A](body: => A): A =
    val result = new java.util.concurrent.atomic.AtomicReference[Either[Throwable, A]]()
    val worker = new Thread(
      null,
      () =>
        result.set(
          try Right(body)
          catch case NonFatal(e) => Left(e)
        ),
      "flat-parity",
      1024L * 1024 * 1024
    )
    worker.start()
    worker.join()
    result.get().fold(throw _, identity)

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private val conformanceRoot: Path =
    Paths.get(getClass.getResource(s"/${PlutusConformanceSuite.Directory}").toURI)

  /** Flat bytes from the conformance programs Scalus parses and can encode (a BLS constant has no
    * flat encoding), and from the original-byte sidecars.
    */
  private lazy val conformance: Vector[(String, Array[Byte])] =
    val files = Files.walk(conformanceRoot).iterator().asScala.toVector
    val encoded = files.filter(_.toString.endsWith(".uplc")).flatMap { path =>
      Program
        .parseUplc(Files.readString(path))
        .toOption
        .flatMap(program => Try(program.flatEncoded).toOption)
        .map(conformanceRoot.relativize(path).toString -> _)
    }
    val sidecars = files.filter(_.toString.endsWith(".flat.cbor.hex")).flatMap { path =>
      PlutusScriptBytes
        .flatOf(hex(Files.readString(path).trim).toArray)
        .map(conformanceRoot.relativize(path).toString -> _)
    }
    encoded ++ sidecars

  private def jsonl(file: String): Vector[(String, Array[Byte])] =
    Files
      .readAllLines(Path.of(file))
      .asScala
      .toVector
      .zipWithIndex
      .flatMap { (line, n) =>
        val row = io.circe.parser.parse(line).fold(throw _, identity)
        row.hcursor
          .get[String]("script")
          .toOption
          .flatMap(s => PlutusScriptBytes.flatOf(hex(s).toArray))
          .map(s"$file:$n" -> _)
      }

  private def scripts(probes: Vector[(String, Bytes)]): Vector[(String, Array[Byte])] =
    probes.flatMap((id, script) => PlutusScriptBytes.flatOf(script.toArray).map(id -> _))

  private lazy val fixtures: Vector[(String, Array[Byte])] =
    Vector(
      "fixtures/reference/plutus-costing/scripts.jsonl",
      "fixtures/reference/plutus-boundaries/scripts.jsonl",
      "fixtures/reference/plutus-boundaries/transaction-scripts.jsonl",
      "fixtures/reference/plutus-upstream-boundaries/scripts.jsonl",
      "fixtures/reference/plutus-boundary-transactions/scripts.jsonl"
    ).flatMap(jsonl) ++ scripts(PlutusBoundaryCorpus.probes.map(p => p.id -> p.script))

  test("Scarab's decoder builds Scalus's program for every fixture script") {
    val cases = conformance ++ fixtures
    val outcomes = onBigStack(cases.map((id, flat) => id -> compare(flat)))
    val unexplained = outcomes.collect { case (id, Outcome.Unexplained(d)) => s"$id: $d" }
    assertEquals(unexplained, Vector.empty)
    val differing = outcomes.collect { case (id, Outcome.Differs(d)) => id -> d }
    assertEquals(
      differing.toMap,
      Vector(
        "fixtures/reference/plutus-boundaries/scripts.jsonl:21",
        "fixtures/reference/plutus-boundary-transactions/scripts.jsonl:8",
        "scope-natural-65"
      ).map(_ -> Difference.ScalusWrapsWord64).toMap
    )
    val equal = outcomes.count(_._2 == Outcome.Equal)
    // The corpus is large and nearly all of it decodes; a shrinking count is a lost fixture.
    // With Scalus 1.4.0 the 26 malformed `value` constants are refused by both (2900 equal).
    assert(equal >= 2900, s"only $equal programs compared")
  }

  test("the flat decoding probes differ from Scalus exactly where the reference decides") {
    val probes = scripts(FlatDecodeCorpus.probes.map(p => p.id -> p.script))
    val outcomes = onBigStack(probes.map((id, flat) => id -> compare(flat)))
    val differing = outcomes.collect { case (id, Outcome.Differs(d)) => id -> d }.toMap
    assertEquals(differing, expected)
    val unexplained = outcomes.collect { case (id, Outcome.Unexplained(d)) => s"$id: $d" }
    assertEquals(unexplained, Vector.empty)
  }
