// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.core

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, PlutusData}
import xyz.kd5ujc.scarab.uplc.builtin.{BuiltinFailure, Outcome}

import CoreTesting.*
import DefaultFun.*

/** The reference's verdicts on the `Data` probes of `fixtures/reference`, rebuilt as builtin
  * applications from the corpora of the `plutus` test tree:
  *
  *   - `plutus-serialise-data/verdicts.jsonl` (`SerialiseDataCorpus`): `sha2_256 (serialiseData
  *     (constrData i []))` against the digest of the reference's bytes and of the chunked bytes, V2
  *     and V3 at protocols 9 to 11. `sha2_256` is not a core builtin; the JDK's SHA-256 stands in.
  *   - `plutus-serialise-data/constr-index-verdicts.jsonl` (`ConstrIndexCorpus`): `constrData` at
  *     the sign and Word64 boundaries of its index, then `serialiseData`, `unConstrData`,
  *     `equalsData` and `chooseData`.
  *   - `plutus-equals-data/verdicts.jsonl` (`EqualsDataCorpus`): `equalsData` on maps whose entries
  *     are reordered, repeated or nested, V1 to V3 at protocols 9 to 11.
  *
  * A probe ends in `()` exactly when its condition holds, so the verdict is compared with whether
  * every application succeeds and the condition is true. Budgets are not compared.
  */
final class DataReferenceSuite extends munit.FunSuite:
  private type R = Either[BuiltinFailure, Constant]

  /** `function` applied to the results of `arguments`, failing at the first that failed. */
  private def call(v: CoreVariant, function: DefaultFun, arguments: R*): R =
    arguments
      .foldLeft[Either[BuiltinFailure, Vector[Probe]]](Right(Vector.empty))((done, a) =>
        done.flatMap(ps => a.map(c => ps :+ con(c)))
      )
      .flatMap(ps => result(CoreTesting(v, function, ps*)))

  private def holds(condition: R): Boolean = condition == Right(Constant.Bool(true))
  private def c(constant: Constant): R = Right(constant)
  private def i(n: BigInt): R = c(Constant.Integer(n))

  private def rows(path: String): Vector[Vector[String]] =
    Files
      .readAllLines(Path.of(path))
      .asScala
      .toVector
      .filterNot(_.startsWith("#"))
      .map(_.split("\t").toVector)

  test("serialiseData of wide constructor indices, hashed, in every verdict") {
    val vectors = rows("fixtures/reference/plutus-serialise-data/vectors.tsv").map {
      case Vector(bits, sign, index, bytes, sha, chunkedBytes, chunkedSha) =>
        (s"$bits$sign", BigInt(index), bytes, sha, chunkedBytes, chunkedSha)
      case other => fail(s"malformed row $other")
    }
    val probes: Map[String, (BigInt, String)] = vectors.flatMap {
      (name, index, bytes, sha, chunkedBytes, chunkedSha) =>
        val reference = s"constr-index-$name-reference-digest" -> (index, sha)
        if bytes == chunkedBytes then Vector(reference)
        else Vector(reference, s"constr-index-$name-chunked-digest" -> (index, chunkedSha))
    }.toMap
    val verdicts = ReferenceVerdicts.read("fixtures/reference/plutus-serialise-data/verdicts.jsonl")
    assertEquals(verdicts.map(_.id).toSet, probes.keySet, "the ids of the verdicts")
    assertEquals(verdicts.size, probes.size * 6, "rows")
    def sha256(b: Bytes) = Bytes.wrap(MessageDigest.getInstance("SHA-256").digest(b.toArray)).hex
    for verdict <- verdicts do
      val (index, digest) = probes(verdict.id)
      val v = verdict.variant
      val serialised = call(v, SerialiseData, call(v, ConstrData, i(index), c(dataList())))
      val ok = serialised match
        case Right(Constant.ByteString(b)) => sha256(b) == digest
        case _                             => false
      assertEquals(
        ok,
        verdict.succeeded,
        s"${verdict.id} in V${verdict.language} at ${verdict.protocol}"
      )
  }

  test("constrData at the sign and Word64 boundaries of its index, and the builtins that read it") {
    val indices = rows("fixtures/reference/plutus-serialise-data/indices.tsv").map {
      case Vector(index, empty, oneField) => (BigInt(index), empty, oneField)
      case other                          => fail(s"malformed row $other")
    }
    def bodies(
        v: CoreVariant,
        index: BigInt,
        empty: String,
        oneField: String
    ): Vector[(String, Boolean)] =
      def constr(n: BigInt, fields: PlutusData*): R =
        call(v, ConstrData, i(n), c(dataList(fields*)))
      val none = constr(index)
      val one = constr(index, PlutusData.I(1))
      def bytesOf(hex: String) = c(Constant.ByteString(hexBytes(hex)))
      val pair = call(v, UnConstrData, one)
      val choose = none.flatMap { d =>
        val branches = Vector("constr", "map", "list", "i", "b").map(Probe.Lambda(_))
        CoreTesting(v, ChooseData, (con(d) +: branches)*) match
          case Outcome.Success(Probe.Lambda("constr"), _) => Right(true)
          case other                                      => Left(other)
      }
      Vector(
        "serialise-empty" -> holds(
          call(v, EqualsByteString, call(v, SerialiseData, none), bytesOf(empty))
        ),
        "serialise-field" -> holds(
          call(v, EqualsByteString, call(v, SerialiseData, one), bytesOf(oneField))
        ),
        "unconstr-index" -> holds(call(v, EqualsInteger, call(v, FstPair, pair), i(index))),
        "unconstr-rebuild" -> holds(
          call(
            v,
            EqualsData,
            call(v, ConstrData, call(v, FstPair, pair), call(v, SndPair, pair)),
            one
          )
        ),
        "equals-same" -> holds(call(v, EqualsData, one, one)),
        "equals-next" -> holds(call(v, EqualsData, none, constr(index + 1))),
        "choose" -> choose.isRight
      )
    val verdicts =
      ReferenceVerdicts.read("fixtures/reference/plutus-serialise-data/constr-index-verdicts.jsonl")
    val expectedIds = for
      (index, _, _) <- indices
      name <- Vector(
        "serialise-empty",
        "serialise-field",
        "unconstr-index",
        "unconstr-rebuild",
        "equals-same",
        "equals-next",
        "choose"
      )
    yield s"constr-index-$index-$name"
    assertEquals(verdicts.map(_.id).toSet, expectedIds.toSet, "the ids of the verdicts")
    assertEquals(verdicts.size, expectedIds.size * 6, "rows")
    for
      verdict <- verdicts
      (index, empty, oneField) <- indices
      (name, ok) <- bodies(verdict.variant, index, empty, oneField)
      if verdict.id == s"constr-index-$index-$name"
    do
      assertEquals(
        ok,
        verdict.succeeded,
        s"${verdict.id} in V${verdict.language} at ${verdict.protocol}"
      )
  }

  test("equalsData on reordered, repeated and nested map entries, in every verdict") {
    import PlutusData.{Constr, I, List as DList, Map as DMap}
    def map(entries: (PlutusData, PlutusData)*): PlutusData = DMap(entries.toVector)
    def list(items: PlutusData*): PlutusData = DList(items.toVector)
    def constr(tag: Int, fields: PlutusData*): PlutusData = Constr(tag, fields.toVector)
    def n(k: Int): PlutusData = I(k)
    val ab = map(n(1) -> n(1), n(2) -> n(2))
    val ba = map(n(2) -> n(2), n(1) -> n(1))
    val abDuplicate = map(n(1) -> n(1), n(2) -> n(2), n(1) -> n(1))
    val abReps = map(n(1) -> n(1), n(1) -> n(2))
    val baReps = map(n(1) -> n(2), n(1) -> n(1))
    val depth = 900
    def nest(bottom: PlutusData)(wrap: PlutusData => PlutusData) =
      (1 to depth).foldLeft(bottom)((inner, _) => wrap(inner))
    // `EqualsDataCorpus.probes`
    val probes: Map[String, (PlutusData, PlutusData)] = Map(
      "equal-maps" -> (ab, ab),
      "equal-maps-rebuilt" -> (ab, map(n(1) -> n(1), n(2) -> n(2))),
      "empty-maps" -> (map(), map()),
      "reordered" -> (ab, ba),
      "duplicate-pair" -> (ab, abDuplicate),
      "duplicate-pair-single" -> (map(n(1) -> n(1), n(1) -> n(1)), map(n(1) -> n(1))),
      "duplicate-key-reordered" -> (abReps, baReps),
      "duplicate-key-equal" -> (abReps, abReps),
      "duplicate-key-dropped" -> (abReps, map(n(1) -> n(1))),
      "different-values" -> (ab, map(n(1) -> n(1), n(2) -> n(3))),
      "map-and-empty-list" -> (map(), list()),
      "list-of-maps-equal" -> (list(ab, ba), list(ab, ba)),
      "list-of-maps-reordered-inside" -> (list(ab), list(ba)),
      "list-of-maps-swapped" -> (list(ab, ba), list(ba, ab)),
      "constr-with-map-equal" -> (constr(0, ab), constr(0, ab)),
      "constr-with-map-reordered-inside" -> (constr(0, ab), constr(0, ba)),
      "constr-index-differs" -> (constr(0, ab), constr(1, ab)),
      "map-of-maps-equal" -> (map(n(0) -> ab, n(1) -> ba), map(n(0) -> ab, n(1) -> ba)),
      "map-of-maps-inner-reordered" -> (map(n(0) -> ab), map(n(0) -> ba)),
      "map-of-maps-outer-reordered" -> (map(n(0) -> ab, n(1) -> ba), map(n(1) -> ba, n(0) -> ab)),
      "map-key-is-map-reordered" -> (map(ab -> n(0)), map(ba -> n(0))),
      "map-in-constr-in-list-in-map" -> (
        map(n(7) -> list(constr(2, ab))),
        map(n(7) -> list(constr(2, ba)))
      ),
      "deep-lists-equal" -> (nest(ab)(x => list(x)), nest(ab)(x => list(x))),
      "deep-lists-bottom-reordered" -> (nest(ab)(x => list(x)), nest(ba)(x => list(x))),
      "deep-maps-equal" -> (nest(ab)(x => map(n(0) -> x)), nest(ab)(x => map(n(0) -> x))),
      "deep-maps-bottom-reordered" -> (
        nest(ab)(x => map(n(0) -> x)),
        nest(ba)(x => map(n(0) -> x))
      ),
      "deep-constrs-bottom-duplicate" -> (
        nest(ab)(x => constr(0, x)),
        nest(abDuplicate)(x => constr(0, x))
      )
    )
    val verdicts = ReferenceVerdicts.read("fixtures/reference/plutus-equals-data/verdicts.jsonl")
    assertEquals(probes.size, 27, "probes")
    assertEquals(verdicts.map(_.id).toSet, probes.keySet, "the ids of the verdicts")
    assertEquals(verdicts.size, 27 * 9, "rows")
    for verdict <- verdicts do
      val (left, right) = probes(verdict.id)
      val ok =
        holds(call(verdict.variant, EqualsData, c(Constant.Data(left)), c(Constant.Data(right))))
      assertEquals(
        ok,
        verdict.succeeded,
        s"${verdict.id} in V${verdict.language} at ${verdict.protocol}"
      )
  }
