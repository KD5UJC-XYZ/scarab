// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes

import FlatProgram.*

/** V3 transaction scripts retain the original Flat indices and add an ignored context lambda. Check
  * the selected ASTs against the committed original bytes before producing any probes.
  */
object BoundaryTransactionScripts:
  private val version = (1, 1, 0)
  private val wide = BigInt(4294967298L)
  private def ignore(term: Tm): Tm = Ap(Lam(unit), term)
  enum Stage(val wire: String):
    case Decode extends Stage("decode")
    case Scope extends Stage("scope")
    case Evaluation extends Stage("evaluation")
    case Success extends Stage("success")
  final case class Probe(
      id: String,
      term: Tm,
      indices: Vector[BigInt] = Vector.empty,
      expected: Option[Stage] = None
  )

  val selected: Vector[Probe] = Vector(
    Probe("header-31", ignore(nestedEmptyList(15))),
    Probe("header-33", ignore(nestedEmptyList(16))),
    Probe("constr-1024", ignore(Constr(0, Vector.fill(1024)(unit)))),
    Probe("constr-1025", ignore(Constr(0, Vector.fill(1025)(unit)))),
    Probe("scope-free-delay", ignore(Delay(Var(5))), Vector(5)),
    Probe("scope-bound-delay", ignore(Delay(Lam(Var(1)))), Vector(1)),
    Probe("scope-zero", ignore(Delay(Lam(Var(0)))), Vector(0)),
    Probe("scope-wide", Ap(Lam(ignore(Delay(Var(wide)))), unit), Vector(wide)),
    Probe("scope-natural-65", ignore(Delay(Var(BigInt(1) << 64))), Vector(BigInt(1) << 64)),
    Probe("scope-unused-case-branch", Case(integer(0), Vector(unit, Var(9))), Vector(9)),
    Probe("scope-unused-constr-field", ignore(Delay(Constr(0, Vector(Var(9))))), Vector(9)),
    Probe(
      "scope-unused-wide-constr-field",
      ignore(Delay(Constr(0, Vector(Var(wide))))),
      Vector(wide)
    ),
    Probe("scope-unused-wide-case-branch", Case(integer(0), Vector(unit, Var(wide))), Vector(wide)),
    Probe("scope-evaluated-wide-constr-field", ignore(Constr(0, Vector(Var(wide)))), Vector(wide))
  )

  val constructorCases: Vector[Probe] = Vector(
    Probe(
      "scope-constructor-case-skip-wide",
      Case(Constr(0, Vector.empty), Vector(unit, Var(wide))),
      Vector(wide),
      Some(Stage.Success)
    ),
    Probe(
      "scope-constructor-case-evaluate-wide",
      Case(Constr(0, Vector.empty), Vector(Var(wide))),
      Vector(wide),
      Some(Stage.Evaluation)
    )
  )

  /** With the context binder and this extra binder, narrowed index 2 would be bound. The ignored
    * value would then produce unit, whereas the original wide index must fail when evaluated.
    */
  val narrowingControls: Vector[Probe] = Vector(
    Probe(
      "scope-depth-two-wide-constr-field",
      Ap(Lam(ignore(Constr(0, Vector(Var(wide))))), unit),
      Vector(wide),
      Some(Stage.Evaluation)
    ),
    Probe(
      "scope-depth-two-wide-case-branch",
      Ap(Lam(ignore(Case(Constr(0, Vector.empty), Vector(Var(wide))))), unit),
      Vector(wide),
      Some(Stage.Evaluation)
    )
  )

  private val directory = Path.of("fixtures/reference/plutus-boundaries")
  private def rows(file: String): Vector[Json] =
    Files.readAllLines(directory.resolve(file)).asScala.toVector.map { line =>
      parse(line).fold(e => sys.error(e.message), identity)
    }
  private def field[A: io.circe.Decoder](json: Json, name: String): A =
    json.hcursor.get[A](name).fold(e => sys.error(e.message), identity)
  private def hex(value: String): Bytes = Bytes.fromHex(value).fold(sys.error, identity)

  def checked: Vector[Probe] =
    val originals = rows("scripts.jsonl")
      .map(j => field[String](j, "id") -> hex(field[String](j, "script")))
      .toMap
    selected.foreach { probe =>
      require(
        originals.get(probe.id).contains(script(version, probe.term)),
        s"original bytes differ: ${probe.id}"
      )
    }
    selected ++ constructorCases ++ narrowingControls

  private def stage(probe: Probe, protocol: Int, verdicts: Vector[Json]): Stage =
    probe.expected.getOrElse {
      val wire = verdicts
        .find(j =>
          field[String](j, "id") == probe.id && field[Int](j, "language") == 3 &&
            field[Int](j, "protocol") == protocol
        )
        .fold(sys.error(s"missing verdict: ${probe.id}/$protocol"))(
          field[String](_, "stage")
        )
      Stage.values.find(_.wire == wire).getOrElse(sys.error(s"unexpected stage: $wire"))
    }

  def main(args: Array[String]): Unit =
    require(
      args.length == 2,
      "expected transaction-scripts.jsonl and oracle-requests.jsonl output paths"
    )
    val probes = checked
    val verdicts = rows("verdicts.jsonl")
    val transactions = probes.map { probe =>
      Json.obj(
        "id" -> Json.fromString(probe.id),
        "script" -> Json.fromString(script(version, Lam(probe.term)).hex),
        "sourceScript" -> Json.fromString(script(version, probe.term).hex),
        "sourceIndices" -> Json.arr(probe.indices.map(i => Json.fromString(i.toString))*),
        "stages" -> Json.obj(
          Vector(10, 11).map(p => p.toString -> Json.fromString(stage(probe, p, verdicts).wire))*
        )
      )
    }
    // The applied unit is ignored by the wrapper just as the ledger's context is. Unlike an
    // unapplied lambda, this executes deferred constr/case failures in the direct API oracle.
    val requests = for
      probe <- probes
      protocol <- Vector(10, 11)
    yield Json.obj(
      "id" -> Json.fromString(s"${probe.id}-applied-context"),
      "language" -> Json.fromInt(3),
      "protocol" -> Json.fromInt(protocol),
      "script" -> Json.fromString(script(version, Ap(Lam(probe.term), unit)).hex),
      "costModel" -> Json
        .arr(PlutusBoundaryCorpus.costModel(PlutusLanguage.V3, protocol).map(Json.fromLong)*)
    )
    Files.writeString(Path.of(args(0)), transactions.map(_.noSpaces).mkString("\n") + "\n")
    Files.writeString(Path.of(args(1)), requests.map(_.noSpaces).mkString("\n") + "\n")
