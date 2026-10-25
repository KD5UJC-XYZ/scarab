// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scalus.uplc.Program
import xyz.kd5ujc.scarab.codec.Bytes

/** Phase-2 probes of `constrData` at the sign and Word64 boundaries of its index, each followed by
  * the builtins that read the result: `serialiseData` (compared with the reference's bytes from
  * `fixtures/reference/plutus-serialise-data/indices.tsv`), `unConstrData` (the index, and the data
  * rebuilt from both halves), `equalsData` and `chooseData`. Every probe ends in `()` when the
  * property holds and in `error` otherwise; `equals-next` compares with the next index and so
  * expects `error`. Nothing here computes an expected value: the reference verdicts and budgets are
  * in `constr-index-verdicts.jsonl`.
  */
object ConstrIndexCorpus:
  final case class Index(value: BigInt, empty: String, oneField: String)
  final case class Probe(id: String, script: Bytes, index: Index)

  val indices: Vector[Index] = Files
    .readAllLines(Path.of("fixtures/reference/plutus-serialise-data/indices.tsv"))
    .asScala
    .toVector
    .filterNot(_.startsWith("#"))
    .map { line =>
      line.split("\t").toVector match
        case Vector(index, empty, oneField) => Index(BigInt(index), empty, oneField)
        case other                          => sys.error(s"malformed constructor index row: $other")
    }

  private def constr(i: BigInt, fields: String) =
    s"[ (builtin constrData) (con integer $i) (con (list data) [$fields]) ]"

  private def holds(condition: String) =
    s"(force [ (force (builtin ifThenElse)) $condition (delay (con unit ())) (delay (error)) ])"

  private val fst = "(force (force (builtin fstPair)))"
  private val snd = "(force (force (builtin sndPair)))"

  private def bodies(index: Index): Vector[(String, String)] =
    val i = index.value
    val empty = constr(i, "")
    val one = constr(i, "I 1")
    Vector(
      "serialise-empty" -> holds(
        s"[ (builtin equalsByteString) [ (builtin serialiseData) $empty ] (con bytestring #${index.empty}) ]"
      ),
      "serialise-field" -> holds(
        s"[ (builtin equalsByteString) [ (builtin serialiseData) $one ] (con bytestring #${index.oneField}) ]"
      ),
      "unconstr-index" -> holds(
        s"[ (builtin equalsInteger) [ $fst [ (builtin unConstrData) $one ] ] (con integer $i) ]"
      ),
      "unconstr-rebuild" -> holds(
        s"[ (lam p [ (builtin equalsData) [ (builtin constrData) [ $fst p ] [ $snd p ] ] $one ]) [ (builtin unConstrData) $one ] ]"
      ),
      "equals-same" -> holds(s"[ (builtin equalsData) $one $one ]"),
      "equals-next" -> holds(s"[ (builtin equalsData) $empty ${constr(i + 1, "")} ]"),
      "choose" -> ("(force [ (force (builtin chooseData)) " + empty +
        " (delay (con unit ())) (delay (error)) (delay (error)) (delay (error)) (delay (error)) ])")
    )

  private def compiled(body: String): Bytes =
    Bytes.fromArray(
      Program.parseUplc(s"(program 1.0.0 $body)").fold(e => sys.error(e.toString), _.cborEncoded)
    )

  val probes: Vector[Probe] = for
    index <- indices
    (name, body) <- bodies(index)
  yield Probe(s"constr-index-${index.value}-$name", compiled(body), index)

  val languages: Vector[PlutusLanguage] = Vector(PlutusLanguage.V2, PlutusLanguage.V3)
  val protocols: Vector[Int] = Vector(9, 10, 11)

  /** Writes the requests for the reference costing oracle (`scripts/oracle/plutus/costing`). */
  def main(args: Array[String]): Unit =
    val out = Path.of(args.headOption.getOrElse("/oracle/constr-index-requests.jsonl"))
    val rows = for
      probe <- probes
      language <- languages
      protocol <- protocols
    yield Json.obj(
      "id" -> Json.fromString(probe.id),
      "language" -> Json.fromInt(language.id + 1),
      "protocol" -> Json.fromInt(protocol),
      "script" -> Json.fromString(probe.script.hex),
      "costModel" -> Json
        .arr(PlutusBoundaryCorpus.costModel(language, protocol).map(Json.fromLong)*),
      "cpu" -> Json.fromBigInt(PlutusBoundaryCorpus.budget.steps),
      "memory" -> Json.fromBigInt(PlutusBoundaryCorpus.budget.mem)
    )
    Files.writeString(out, rows.map(_.noSpaces).mkString("\n") + "\n")
