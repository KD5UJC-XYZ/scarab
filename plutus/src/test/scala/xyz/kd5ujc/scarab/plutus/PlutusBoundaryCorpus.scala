// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.file.{Files, Path}
import scalus.uplc.{Constant, DefaultFun, Program, Term}
import xyz.kd5ujc.scarab.codec.Bytes

import FlatProgram.*

/** Original-byte boundary probes for the pinned ledger API, independent of Scalus's text parser for
  * constants it normalises. The malformed value entries preserve the upstream conformance literals,
  * including their order and duplicates.
  */
object PlutusBoundaryCorpus:
  final case class Probe(id: String, script: Bytes, crypto: Boolean = false)
  val budget: ExUnits = ExUnits(BigInt(14000000), BigInt(10000000000L))
  private val v100 = (1, 0, 0)
  private val v110 = (1, 1, 0)
  private def hex(s: String): Array[Byte] =
    Bytes.fromHex(s).fold(e => sys.error(e.toString), _.toArray)
  private def tokens(xs: (String, Int)*): Vector[(Array[Byte], BigInt)] =
    xs.toVector.map((k, n) => hex(k) -> BigInt(n))
  private def values(xs: (String, Vector[(Array[Byte], BigInt)])*): Tm =
    value(xs.toVector.map((k, ts) => hex(k) -> ts))
  private def raw(id: String, term: Tm, version: (Int, Int, Int) = v100): Probe =
    Probe(id, script(version, term))
  private def text(id: String, program: String, crypto: Boolean = false): Probe =
    Probe(
      id,
      Bytes.fromArray(Program.parseUplc(program).fold(e => sys.error(e.toString), _.cborEncoded)),
      crypto
    )

  val malformedValues: Vector[Probe] = Vector(
    raw(
      "currencyIDs-unordered",
      values(
        "88" -> tokens("aa" -> 123, "bb" -> 456),
        "9999" -> tokens("aa" -> 123),
        "123456" -> tokens("bb" -> 456)
      )
    ),
    raw(
      "duplicate-currencyIDs",
      values(
        "" -> tokens("00" -> 123, "01" -> 456),
        "1234" -> tokens("00" -> 123, "01" -> 456),
        "1234" -> tokens("02" -> 789, "03" -> 12)
      )
    ),
    raw("duplicate-tokenIDs", values("" -> tokens("" -> 123, "" -> 456))),
    raw("empty-tokens", values("" -> tokens())),
    raw(
      "tokenIDs-unordered",
      values(
        "123456" -> tokens("bb" -> 456),
        "88" -> tokens("88" -> 2, "9999" -> 5, "123456" -> 100),
        "9999" -> tokens("aa" -> 123)
      )
    ),
    raw("zero-asset", values("" -> tokens("" -> 0, "aa" -> 1)))
  )

  private def ignore(term: Tm): Tm = Ap(Lam(unit), term)
  private val big = BigInt(1) << 127
  private val wide = BigInt(4294967298L)
  val probes: Vector[Probe] = malformedValues ++ Vector(
    raw("value-valid", values("61" -> tokens("61" -> 1, "62" -> -2))),
    raw("value-currency-key-33", value(Vector(Array.fill[Byte](33)(1) -> tokens("61" -> 1)))),
    raw(
      "value-token-key-33",
      value(Vector(hex("61") -> Vector(Array.fill[Byte](33)(1) -> BigInt(1))))
    ),
    raw("value-quantity-max", value(Vector(hex("61") -> Vector(hex("61") -> (big - 1))))),
    raw("value-quantity-min", value(Vector(hex("61") -> Vector(hex("61") -> -big)))),
    raw("value-quantity-over", value(Vector(hex("61") -> Vector(hex("61") -> big)))),
    raw(
      "value-list-malformed",
      Const(
        Vector(7, 5, 13),
        b => {
          b.bit(1)
          values("61" -> tokens("61" -> 0)) match
            case Const(_, write) => write(b)
            case _               => ()
          b.bit(0)
        }
      )
    ),
    raw("header-31", ignore(nestedEmptyList(15)), v110),
    raw("header-33", ignore(nestedEmptyList(16)), v110),
    raw("constr-1024", ignore(Constr(0, Vector.fill(1024)(unit))), v110),
    raw("constr-1025", ignore(Constr(0, Vector.fill(1025)(unit))), v110),
    raw("scope-free-delay", ignore(Delay(Var(5))), v110),
    raw("scope-bound-delay", ignore(Delay(Lam(Var(1)))), v110),
    raw("scope-zero", ignore(Delay(Lam(Var(0)))), v110),
    raw("scope-wide", Ap(Lam(ignore(Delay(Var(wide)))), unit), v110),
    raw("scope-natural-65", ignore(Delay(Var(BigInt(1) << 64))), v110),
    raw("scope-unused-case-branch", Case(integer(0), Vector(unit, Var(9))), v110),
    raw("scope-unused-constr-field", ignore(Delay(Constr(0, Vector(Var(9))))), v110),
    raw("scope-unused-wide-constr-field", ignore(Delay(Constr(0, Vector(Var(wide))))), v110),
    raw("scope-unused-wide-case-branch", Case(integer(0), Vector(unit, Var(wide))), v110),
    raw("scope-evaluated-wide-constr-field", ignore(Constr(0, Vector(Var(wide)))), v110),
    text("case-integer", "(program 1.1.0 (case (con integer 0) (con unit ())))"),
    text("case-data", "(program 1.1.0 (case (con data (Constr 0 [])) (lam fields (con unit ()))))")
  ) ++ Vector("G1", "G2").flatMap { group =>
    Vector("zero", "on-curve-bit3-clear", "on-curve-bit3-set").map { name =>
      val file =
        s"/${PlutusConformanceSuite.Directory}/builtin/parser/bls12-381/$group/$name/$name.uplc"
      val literal = resource(file)
      val encoded = "0x([0-9a-fA-F]+)".r
        .findFirstMatchIn(literal)
        .fold(sys.error(s"missing BLS literal $file"))(_.group(1))
      raw(
        s"bls-$group-$name",
        Const(Vector(if group == "G1" then 9 else 10), _.byteString(hex(encoded)))
      )
    }
  } ++ Vector(
    "builtin/semantics/bls12_381_G1_hashToGroup/hash-dst-len-255",
    "builtin/semantics/bls12_381_G2_hashToGroup/hash-dst-len-255",
    "builtin/semantics/bls12_381-cardano-crypto-tests/signature/large-dst"
  ).map { id =>
    val name = id.split('/').last
    val base = s"/${PlutusConformanceSuite.Directory}/$id/$name"
    val actual =
      Program.parseUplc(resource(base + ".uplc")).fold(e => sys.error(e.toString), identity)
    val expected = Program
      .parseUplc(resource(base + ".uplc.expected"))
      .fold(e => sys.error(e.toString), identity)
    val equal = expected.term match
      case Term.Const(Constant.Bool(true), _) => actual.term
      case _ =>
        Term.Apply(
          Term.Apply(Term.Builtin(DefaultFun.EqualsByteString), actual.term),
          expected.term
        )
    val asserted = Term.Force(
      Term.Apply(
        Term.Apply(
          Term.Apply(Term.Force(Term.Builtin(DefaultFun.IfThenElse)), equal),
          Term.Delay(Term.Const(Constant.Unit))
        ),
        Term.Delay(Term.Error())
      )
    )
    Probe(id, Bytes.fromArray(Program(actual.version, asserted).cborEncoded), true)
  } ++ hashVectors.flatMap { vector =>
    val dst = vector.hcursor.get[String]("dst").fold(throw _, identity)
    Vector("G1", "G2").map { group =>
      val expected = vector.hcursor.get[String](group.toLowerCase).fold(throw _, identity)
      val equal =
        s"[[(builtin equalsByteString) [(builtin bls12_381_${group}_compress) [[(builtin bls12_381_${group}_hashToGroup) (con bytestring #3f)] (con bytestring #$dst)]]] (con bytestring #$expected)]"
      text(
        s"hash-$group-${if dst.isEmpty then "empty" else if dst.length > 8 then "255" else dst}",
        s"(program 1.0.0 (force [[[(force (builtin ifThenElse)) $equal] (delay (con unit ()))] (delay (error))]))",
        true
      )
    }
  } ++ Vector("dropList-09", "dropList-10", "dropList-14", "dropList-15", "dropList-16").map {
    name =>
      val file = s"/${PlutusConformanceSuite.Directory}/builtin/semantics/dropList/$name/$name.uplc"
      val in = getClass.getResourceAsStream(file)
      val program =
        try String(in.readAllBytes(), "UTF-8")
        finally in.close()
      text(name, program)
  }

  private lazy val hashVectors: Vector[Json] =
    import scala.jdk.CollectionConverters.*
    Files
      .readAllLines(Path.of("fixtures/reference/plutus-boundaries/hash-vectors.jsonl"))
      .asScala
      .toVector
      .map(line => io.circe.parser.parse(line).fold(throw _, identity))

  private def resource(name: String): String =
    val in = getClass.getResourceAsStream(name)
    try String(in.readAllBytes(), "UTF-8")
    finally in.close()

  def costModel(language: PlutusLanguage, protocol: Int): Vector[Long] =
    val variant =
      if language == PlutusLanguage.V3 then (if protocol < 11 then "C" else "E")
      else if protocol < 11 then "B"
      else "D"
    val builtins = PlutusConformanceSuite.json(s"builtinCostModel$variant.json")
    val machine = PlutusConformanceSuite.json(s"cekMachineCosts$variant.json")
    PlutusConformanceSuite
      .resource(s"V${language.id + 1}-param-names.txt")
      .linesIterator
      .filter(_.nonEmpty)
      .map { name =>
        val path = name.split('\'').toVector
        val head = s"${path.head.head.toLower}${path.head.tail}"
        path.tail
          .foldLeft((if head.startsWith("cek") then machine else builtins).hcursor.downField(head))(
            (cursor, field) => cursor.downField(field)
          )
          .as[Long]
          .fold(throw _, identity)
      }
      .toVector

  def main(args: Array[String]): Unit =
    val out = Path.of(args.headOption.getOrElse("/oracle/requests.jsonl"))
    val evaluator = ScalusEvaluator.pure
    val decoder = ScalusEvaluator.pureDecoder
    val rows = for
      probe <- probes
      language <- PlutusLanguage.values.toVector
      protocol <- Vector(9, 10, 11)
    yield
      val model = costModel(language, protocol)
      val admitted = decoder.validate(language, protocol, probe.script).isRight
      val outcome = evaluator.evaluate(
        EvalRequest(language, protocol, probe.script, Vector.empty, model, budget)
      )
      val stage = outcome match
        case EvalResult.Success(_, _) => "success"
        case EvalResult.Failure(EvalFailure.Decode(_), _, _) =>
          if admitted then "setup" else "decode"
        case EvalResult.Failure(EvalFailure.OpenTerm(_), _, _)    => "scope"
        case EvalResult.Failure(EvalFailure.Unsupported(_), _, _) => "unsupported"
        case EvalResult.Failure(EvalFailure.OutOfBudget, _, _)    => "budget"
        case _                                                    => "evaluation"
      Json.obj(
        "id" -> Json.fromString(probe.id),
        "language" -> Json.fromInt(language.id + 1),
        "protocol" -> Json.fromInt(protocol),
        "script" -> Json.fromString(probe.script.hex),
        "crypto" -> Json.fromBoolean(probe.crypto),
        "costModel" -> Json.arr(model.map(Json.fromLong)*),
        "scalus" -> Json.obj(
          "admitted" -> Json.fromBoolean(admitted),
          "stage" -> Json.fromString(stage)
        )
      )
    Files.writeString(out, rows.map(_.noSpaces).mkString("\n") + "\n")
