// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.{ACursor, Decoder, Json}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** `writeBits` at the input length limit of the variants that bound byte strings, against the
  * executed reference (plutus 1.70.0.0, `PlutusCore/Default/Builtins.hs` lines 2199-2214, and
  * `maximumInputLength` of `PlutusCore/Bitwise.hs` lines 81-82): for every request of
  * `fixtures/reference/plutus-writebits-limit`, the script Scarab builds is the one the reference
  * ran, and Scarab's stage and spent budget, on failure too, are the reference's.
  */
class WriteBitsLimitReferenceSuite extends FunSuite:
  import FlatProgram.*

  private val directory = Path.of("fixtures/reference/plutus-writebits-limit")

  private def rows(name: String): Vector[Json] =
    Files
      .readAllLines(directory.resolve(name))
      .asScala
      .toVector
      .map(line => io.circe.parser.parse(line).fold(error => fail(error.message), identity))

  private val requests = rows("requests.jsonl")
  private val verdicts = rows("verdicts.jsonl")

  private def at[A: Decoder](row: Json, path: String*): A =
    path.init
      .foldLeft[ACursor](row.hcursor)(_.downField(_))
      .get[A](path.last)
      .fold(error => fail(s"${path.mkString(".")}: ${error.message}"), identity)

  /** `[(lam _ ()) [(builtin writeBits) #00…00 [] False]]` with `n` zero bytes: the result is
    * discarded, so a V3 script returns unit.
    */
  private def term(n: Int): Tm =
    val input = Const(Vector(1), _.byteString(Array.fill(n)(0.toByte)))
    val noIndices = Const(Vector(7, 5, 0), _.bit(0))
    val clear = Const(Vector(4), _.bit(0))
    Ap(Lam(unit), Ap(Ap(Ap(Builtin(80), input), noIndices), clear))

  private def script(language: PlutusLanguage, n: Int) =
    val version = if language.evaluatedAs == PlutusLanguage.V3 then (1, 1, 0) else (1, 0, 0)
    FlatProgram.script(version, term(n))

  private final case class Probe(language: PlutusLanguage, protocol: Int, length: Int):
    def id: String = s"writebits-v${language.id + 1}-pv$protocol-$length"

  // variant C, then variants E, D and D
  private val probes =
    for
      (language, protocol) <- Vector(
        PlutusLanguage.V3 -> 10,
        PlutusLanguage.V3 -> 11,
        PlutusLanguage.V2 -> 11,
        PlutusLanguage.V1 -> 11
      )
      length <- Vector(4096, 4097)
    yield Probe(language, protocol, length)

  // the oracle's default budget, as the requests name none
  private val budget = ExUnits(BigInt(1) << 60, BigInt(1) << 60)

  private def evaluate(language: PlutusLanguage, protocol: Int, n: Int, model: Vector[Long]) =
    ScalusEvaluator.pure.evaluate(
      EvalRequest(language, protocol, script(language, n), Vector.empty, model, budget)
    )

  test("the requests and the verdicts are the eight probes, in order") {
    assertEquals(requests.map(at[String](_, "id")), probes.map(_.id))
    assertEquals(verdicts.map(at[String](_, "id")), probes.map(_.id))
  }

  for (probe, index) <- probes.zipWithIndex do
    test(s"${probe.id}: the reference's script, stage and spent budget") {
      val request = requests(index)
      val verdict = verdicts(index)
      assertEquals(at[Int](request, "language"), probe.language.id + 1)
      assertEquals(at[Int](request, "protocol"), probe.protocol)
      assertEquals(at[String](request, "script"), script(probe.language, probe.length).hex)
      val model = at[Vector[Long]](request, "costModel")
      assertEquals(model, PlutusBoundaryCorpus.costModel(probe.language, probe.protocol))
      assert(at[Boolean](verdict, "reference", "admitted"), "the reference admitted the script")
      val ours = evaluate(probe.language, probe.protocol, probe.length, model) match
        case EvalResult.Success(spent, _)                        => ("success", spent)
        case EvalResult.Failure(EvalFailure.Script(_), spent, _) => ("evaluation", spent)
        case other => fail(s"${probe.id}: unexpected outcome $other")
      val reference = (
        at[String](verdict, "reference", "stage"),
        ExUnits(
          BigInt(at[Long](verdict, "reference", "spent", "memory")),
          BigInt(at[Long](verdict, "reference", "spent", "cpu"))
        )
      )
      assertEquals(ours, reference)
    }

  // The oracle runs quiet, so its verdicts hold no logs; these lines are read from the source
  // (`Builtins.hs` lines 2207-2213 at 1.70.0.0).
  test("from the source: the refusal logs the reference's two lines") {
    val model = PlutusBoundaryCorpus.costModel(PlutusLanguage.V3, 11)
    evaluate(PlutusLanguage.V3, 11, 4097, model) match
      case EvalResult.Failure(EvalFailure.Script(_), _, logs) =>
        assertEquals(
          logs,
          Vector("writeBits: input too long (maximum is 4096 bytes)", "Length: 4097")
        )
      case other => fail(s"expected a script failure, got $other")
  }

  // The fixture has no verdicts of plutus 1.71.0.0; at `261766ad` `writeBits` branches on
  // `ensurableByteString` (`Builtins.hs` lines 2217-2232), which holds for D to G (lines
  // 2813-2820).
  test("from the source: variants F and G (protocol 12) keep the limit") {
    PlutusLanguage.values.foreach { language =>
      val model = PlutusBoundaryCorpus.costModel(language, 12)
      evaluate(language, 12, 4096, model) match
        case EvalResult.Success(_, _) => ()
        case other                    => fail(s"$language/12, 4096 bytes: got $other")
      evaluate(language, 12, 4097, model) match
        case EvalResult.Failure(EvalFailure.Script(_), _, _) => ()
        case other => fail(s"$language/12, 4097 bytes: got $other")
    }
  }
