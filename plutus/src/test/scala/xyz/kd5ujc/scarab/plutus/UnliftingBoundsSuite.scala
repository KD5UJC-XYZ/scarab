// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scalus.uplc.{BuiltinSemanticsVariant, DefaultFun, Program}
import xyz.kd5ujc.scarab.codec.Bytes

/** The `ensurable` variants' unlifting bounds (issue 498, [[UnliftingBounds]]): from protocol 11 an
  * integer argument outside `±2^262143` or a byte string argument longer than 65,536 bytes of the
  * builtins that take `CInteger` or `CByteString` fails the script, as in plutus 1.70.0.0; before
  * protocol 11 (variants A to C) it does not.
  */
class UnliftingBoundsSuite extends FunSuite:
  // the corpus parses 142 scripts with constants at the bounds (78,914-digit integers, 65 KB byte
  // strings) and evaluates 426 requests
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")
  private val evaluator = ScalusEvaluator.pure
  private val budget = ExUnits(BigInt(1) << 40, BigInt(1) << 60)

  // the value is discarded (`(lam r (con unit ()))`), so a V3 script returns unit; version 1.0.0
  // is accepted by every language at every protocol here
  private def script(body: String): Bytes =
    Bytes.fromArray(
      Program
        .parseUplc(s"(program 1.0.0 [ (lam r (con unit ())) $body ])")
        .fold(e => fail(e.toString), _.cborEncoded)
    )

  private def run(language: PlutusLanguage, protocol: Int, body: String): EvalResult =
    evaluator.evaluate(
      EvalRequest(
        language,
        protocol,
        script(body),
        Vector.empty,
        PlutusBoundaryCorpus.costModel(language, protocol),
        budget
      )
    )

  private def succeeds(language: PlutusLanguage, protocol: Int, body: String): Unit =
    run(language, protocol, body) match
      case EvalResult.Success(_, _) => ()
      case other                    => fail(s"$language/$protocol: expected success, got $other")

  private def fails(language: PlutusLanguage, protocol: Int, body: String): Unit =
    run(language, protocol, body) match
      case EvalResult.Failure(EvalFailure.Script(_), _, _) => ()
      case other => fail(s"$language/$protocol: expected a script failure, got $other")

  private def bytes(n: Int): String = "#" + "ab" * n
  private def integer(n: BigInt): String = s"(con integer $n)"
  private def hash(arg: String): String = s"[ (builtin sha2_256) (con bytestring $arg) ]"

  // the reproducer of issue 498: the appended value is 80,000 bytes, the result is not bounded but
  // the argument of sha2_256 is
  private val appendThenHash =
    s"[ (builtin sha2_256) [ [ (builtin appendByteString) (con bytestring ${bytes(40000)}) ] " +
      s"(con bytestring ${bytes(40000)}) ] ]"

  test("the reproducer: refused from protocol 11 in V3 (variant E), evaluated before") {
    fails(PlutusLanguage.V3, 11, appendThenHash)
    succeeds(PlutusLanguage.V3, 10, appendThenHash)
  }

  test("a byte string argument of 65,536 bytes is taken, of 65,537 refused, in variants D and E") {
    for language <- Vector(PlutusLanguage.V2, PlutusLanguage.V3) do
      succeeds(language, 11, hash(bytes(65536)))
      fails(language, 11, hash(bytes(65537)))
      succeeds(language, 10, hash(bytes(65537)))
  }

  test("an integer argument at the bounds is taken, past them refused, in variants D and E") {
    val max = (BigInt(1) << 262143) - 1
    val min = -(BigInt(1) << 262143)
    def add(n: BigInt) = s"[ [ (builtin addInteger) ${integer(n)} ] ${integer(0)} ]"
    for language <- Vector(PlutusLanguage.V2, PlutusLanguage.V3) do
      succeeds(language, 11, add(max))
      succeeds(language, 11, add(min))
      fails(language, 11, add(max + 1))
      fails(language, 11, add(min - 1))
      succeeds(language, 10, add(max + 1))
  }

  test("a result is not bounded: appending to past the bound and taking the length succeeds") {
    val length =
      s"[ (builtin lengthOfByteString) [ [ (builtin appendByteString) (con bytestring ${bytes(40000)}) ] " +
        s"(con bytestring ${bytes(40000)}) ] ]"
    succeeds(PlutusLanguage.V3, 11, length)
  }

  test("consByteString bounds its integer only in variant D") {
    val cons =
      s"[ [ (builtin consByteString) ${integer(BigInt(1) << 262143)} ] (con bytestring #00) ]"
    fails(PlutusLanguage.V2, 11, cons)
    assertEquals(
      UnliftingBounds.of(DefaultFun.ConsByteString, BuiltinSemanticsVariant.E).map(_._1),
      Vector(1)
    )
  }

  test("35 builtins have a bounded argument, as the ensurable branches of Builtins.hs type them") {
    val bounded =
      DefaultFun.values.filter(f => UnliftingBounds.of(f, BuiltinSemanticsVariant.D).nonEmpty)
    assertEquals(bounded.size, 35)
    assert(!bounded.contains(DefaultFun.EqualsInteger))
  }

  // the reference's verdict on every probe of UnliftingBoundsCorpus (plutus 1.70.0.0, the costing
  // oracle of `scripts/oracle/plutus/costing`): the stage and, on success, the exact budget
  private val rows = Files
    .readAllLines(Path.of("fixtures/reference/plutus-unlifting-bounds/verdicts.jsonl"))
    .asScala
    .toVector
    .map(line => io.circe.parser.parse(line).fold(throw _, identity))

  private def field[A: io.circe.Decoder](row: Json, name: String): A =
    row.hcursor.get[A](name).fold(error => fail(error.message), identity)

  test("the recorded verdicts cover every probe in every setting") {
    val expected = (for
      probe <- UnliftingBoundsCorpus.probes
      (language, protocol) <- UnliftingBoundsCorpus.settings
    yield (probe.id, language.id + 1, protocol)).toSet
    val actual = rows
      .map(row =>
        (field[String](row, "id"), field[Int](row, "language"), field[Int](row, "protocol"))
      )
      .toSet
    assertEquals(rows.size, expected.size)
    assertEquals(actual, expected)
  }

  test("every probe: the verdict and, on success, the budget equal the reference's") {
    val probes = UnliftingBoundsCorpus.probes.map(p => p.id -> p).toMap
    val differences = rows.flatMap { row =>
      val probe = probes(field[String](row, "id"))
      val language = PlutusLanguage.values
        .find(_.id + 1 == field[Int](row, "language"))
        .getOrElse(fail("language"))
      val protocol = field[Int](row, "protocol")
      val model = PlutusBoundaryCorpus.costModel(language, protocol)
      val modelBytes =
        Json.arr(model.map(Json.fromLong)*).noSpaces.getBytes(StandardCharsets.UTF_8)
      assertEquals(NativeCrypto.sha256Hex(probe.script.toArray), field[String](row, "scriptSha256"))
      assertEquals(NativeCrypto.sha256Hex(modelBytes), field[String](row, "costModelSha256"))
      val ours = evaluator.evaluate(
        EvalRequest(
          language,
          protocol,
          probe.script,
          Vector.empty,
          model,
          UnliftingBoundsCorpus.budget
        )
      ) match
        case EvalResult.Success(used, _)                     => ("success", Some(used))
        case EvalResult.Failure(EvalFailure.Script(_), _, _) => ("evaluation", None)
        case other                                           => (s"unexpected $other", None)
      val stage = field[String](row, "stage")
      val theirs = (
        stage,
        Option.when(stage == "success")(
          ExUnits(BigInt(field[Long](row, "memory")), BigInt(field[Long](row, "cpu")))
        )
      )
      Option.when(ours != theirs)(s"${probe.id} V${language.id + 1}/$protocol: $ours != $theirs")
    }
    assertEquals(differences, Vector.empty)
  }
