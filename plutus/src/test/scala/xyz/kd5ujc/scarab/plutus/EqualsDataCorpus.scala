// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.file.{Files, Path}
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.{Constant, DefaultFun, NamedDeBruijn, Program, Term}
import scalus.uplc.builtin.Data
import xyz.kd5ujc.scarab.codec.Bytes

/** Phase-2 probes of `equalsData` on maps whose entries are reordered, repeated or nested. Every
  * probe ends in `()` when `equalsData left right` is `True` and in `error` otherwise; nothing here
  * computes an expected value, the reference verdicts and budgets are in
  * `fixtures/reference/plutus-equals-data/verdicts.jsonl`.
  */
object EqualsDataCorpus:
  final case class Probe(id: String, left: Data, right: Data):
    def script: Bytes = EqualsDataCorpus.compiled(left, right)

  private def i(n: Int): Data = Data.I(BigInt(n))
  private def map(entries: (Data, Data)*): Data = Data.Map(PList.from(entries.toList))
  private def list(items: Data*): Data = Data.List(PList.from(items.toList))
  private def constr(tag: Int, fields: Data*): Data =
    Data.Constr(BigInt(tag), PList.from(fields.toList))

  private val ab = map(i(1) -> i(1), i(2) -> i(2))
  private val ba = map(i(2) -> i(2), i(1) -> i(1))
  private val abDuplicate = map(i(1) -> i(1), i(2) -> i(2), i(1) -> i(1))
  private val abReps = map(i(1) -> i(1), i(1) -> i(2))
  private val baReps = map(i(1) -> i(2), i(1) -> i(1))

  /** The depth of the nested probes: Scalus encodes the script's constants with a CBOR writer that
    * stops at 1000 levels, so deeper data is passed as arguments in the unit tests instead.
    */
  val Depth = 900

  private def nest(levels: Int, bottom: Data)(wrap: Data => Data): Data =
    (1 to levels).foldLeft(bottom)((inner, _) => wrap(inner))

  val probes: Vector[Probe] = Vector(
    Probe("equal-maps", ab, ab),
    Probe("equal-maps-rebuilt", ab, map(i(1) -> i(1), i(2) -> i(2))),
    Probe("empty-maps", map(), map()),
    Probe("reordered", ab, ba),
    Probe("duplicate-pair", ab, abDuplicate),
    Probe("duplicate-pair-single", map(i(1) -> i(1), i(1) -> i(1)), map(i(1) -> i(1))),
    Probe("duplicate-key-reordered", abReps, baReps),
    Probe("duplicate-key-equal", abReps, abReps),
    Probe("duplicate-key-dropped", abReps, map(i(1) -> i(1))),
    Probe("different-values", ab, map(i(1) -> i(1), i(2) -> i(3))),
    Probe("map-and-empty-list", map(), list()),
    Probe("list-of-maps-equal", list(ab, ba), list(ab, ba)),
    Probe("list-of-maps-reordered-inside", list(ab), list(ba)),
    Probe("list-of-maps-swapped", list(ab, ba), list(ba, ab)),
    Probe("constr-with-map-equal", constr(0, ab), constr(0, ab)),
    Probe("constr-with-map-reordered-inside", constr(0, ab), constr(0, ba)),
    Probe("constr-index-differs", constr(0, ab), constr(1, ab)),
    Probe("map-of-maps-equal", map(i(0) -> ab, i(1) -> ba), map(i(0) -> ab, i(1) -> ba)),
    Probe("map-of-maps-inner-reordered", map(i(0) -> ab), map(i(0) -> ba)),
    Probe("map-of-maps-outer-reordered", map(i(0) -> ab, i(1) -> ba), map(i(1) -> ba, i(0) -> ab)),
    Probe("map-key-is-map-reordered", map(ab -> i(0)), map(ba -> i(0))),
    Probe(
      "map-in-constr-in-list-in-map",
      map(i(7) -> list(constr(2, ab))),
      map(i(7) -> list(constr(2, ba)))
    ),
    Probe("deep-lists-equal", nest(Depth, ab)(x => list(x)), nest(Depth, ab)(x => list(x))),
    Probe(
      "deep-lists-bottom-reordered",
      nest(Depth, ab)(x => list(x)),
      nest(Depth, ba)(x => list(x))
    ),
    Probe(
      "deep-maps-equal",
      nest(Depth, ab)(x => map(i(0) -> x)),
      nest(Depth, ab)(x => map(i(0) -> x))
    ),
    Probe(
      "deep-maps-bottom-reordered",
      nest(Depth, ab)(x => map(i(0) -> x)),
      nest(Depth, ba)(x => map(i(0) -> x))
    ),
    Probe(
      "deep-constrs-bottom-duplicate",
      nest(Depth, ab)(x => constr(0, x)),
      nest(Depth, abDuplicate)(x => constr(0, x))
    )
  )

  val languages: Vector[PlutusLanguage] =
    Vector(PlutusLanguage.V1, PlutusLanguage.V2, PlutusLanguage.V3)
  val protocols: Vector[Int] = Vector(9, 10, 11)

  private def data(d: Data): Term = Term.Const(Constant.Data(d))

  /** `force (ifThenElse (equalsData left right) (delay ()) (delay error))`. */
  def compiled(left: Data, right: Data): Bytes =
    val equal = Term.Apply(Term.Apply(Term.Builtin(DefaultFun.EqualsData), data(left)), data(right))
    Bytes.fromArray(Program((1, 0, 0), check(equal)).cborEncoded)

  private def check(condition: Term): Term =
    Term.Force(
      Term.Apply(
        Term.Apply(
          Term.Apply(Term.Force(Term.Builtin(DefaultFun.IfThenElse)), condition),
          Term.Delay(Term.Const(Constant.Unit))
        ),
        Term.Delay(Term.Error())
      )
    )

  /** `\\a b -> force (ifThenElse (equalsData a b) (delay ()) (delay error))`: the data is applied
    * as arguments, which the machine takes without encoding it, so it can be as deep as a datum.
    */
  val withArguments: Bytes =
    val equal = Term.Apply(
      Term.Apply(Term.Builtin(DefaultFun.EqualsData), Term.Var(NamedDeBruijn("a", 2))),
      Term.Var(NamedDeBruijn("b", 1))
    )
    Bytes.fromArray(
      Program((1, 0, 0), Term.LamAbs("a", Term.LamAbs("b", check(equal)))).cborEncoded
    )

  /** Writes the requests for the reference costing oracle (`scripts/oracle/plutus/costing`). */
  def main(args: Array[String]): Unit =
    val out = Path.of(args.headOption.getOrElse("/oracle/equals-data-requests.jsonl"))
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
