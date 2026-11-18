// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  PlutusData,
  Program,
  ProgramVersion,
  ProtocolMajor,
  SmallStack,
  Term
}
import xyz.kd5ujc.scarab.uplc.ledger.LedgerEval
import xyz.kd5ujc.scarab.uplc.oracle.GeneratedReplay
import xyz.kd5ujc.scarab.uplc.oracle.GeneratedReplay.{field, hex}

/** Which programs the evaluator keeps ([[ScarabEvaluator.Programs]]) and what they weigh
  * ([[ProgramWeight]]). The heap bound itself is [[ProgramCacheMemorySuite]]'s.
  */
class ProgramCacheSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private def request(script: Bytes): EvalRequest =
    EvalRequest(
      PlutusLanguage.V1,
      11,
      script,
      Vector.fill(3)(PlutusData.unit),
      PlutusBoundaryCorpus.costModel(PlutusLanguage.V1, 11),
      PlutusBoundaryCorpus.budget
    )

  private def verdict(result: EvalResult): Boolean = result match
    case EvalResult.Failure(EvalFailure.Unsupported(_), _, _) => false
    case _                                                    => true

  private def decoded(script: Bytes): Either[LedgerEval.NotAdmitted, Program] =
    LedgerEval.deserialiseScript(PlutusLanguage.V1, ProtocolMajor(11), script)

  /** What the cache weighs `script`'s entry at. */
  private def weighed(script: Bytes): Weight =
    decoded(script).fold(
      e => fail(s"$e"),
      ProgramWeight.entry(script, _, ScarabEvaluator.Programs.weight)
    )

  test("well-formedness keeps no program; an evaluation keeps its program, which is then reused") {
    val interpreter = ScarabEvaluator.Interpreter()
    val script = AmplifyingScripts.lams(1_000, 0)
    assertEquals(interpreter.validate(PlutusLanguage.V1, 11, script), Right(()))
    assertEquals(interpreter.held, (0, Weight.zero))
    val malformed = Bytes.fromArray(Array[Byte](0x41, 0x01))
    assert(interpreter.validate(PlutusLanguage.V1, 11, malformed).isLeft)
    assertEquals(interpreter.held, (0, Weight.zero))
    val result = interpreter.evaluate(request(script))
    assert(verdict(result), s"$result")
    val kept = interpreter.held
    assertEquals(kept, (1, weighed(script)))
    assertEquals(interpreter.validate(PlutusLanguage.V1, 11, script), Right(()))
    assertEquals(interpreter.held, kept)
  }

  test("a refused script is not kept, and is refused alike each time") {
    val interpreter = ScarabEvaluator.Interpreter()
    val malformed = Bytes.fromArray(Array[Byte](0x41, 0x01))
    val result = interpreter.evaluate(request(malformed))
    assert(
      result match
        case EvalResult.Failure(EvalFailure.Decode(_), _, _) => true
        case _                                               => false
      ,
      s"$result"
    )
    assertEquals(interpreter.held, (0, Weight.zero))
    assertEquals(interpreter.evaluate(request(malformed)), result)
    assertEquals(
      interpreter.validate(PlutusLanguage.V1, 11, malformed),
      ScarabEvaluator.pureDecoder.validate(PlutusLanguage.V1, 11, malformed)
    )
  }

  test("a script read from a larger buffer is kept with its own bytes alone") {
    val interpreter = ScarabEvaluator.Interpreter()
    val script = AmplifyingScripts.lams(1_000, 0)
    val buffer = Array.fill[Byte](4096)(0) ++ script.toArray ++ Array.fill[Byte](4096)(0)
    val window = Bytes.wrap(buffer, 4096, script.size)
    assertEquals(window, script)
    val _ = interpreter.evaluate(request(window))
    assertEquals(interpreter.held, (1, weighed(script)))
    // Writing to the buffer breaks the contract of `Bytes.wrap`, and shows that the key kept is a
    // copy: a key that shared the buffer would no longer match the script, and miss.
    buffer(4096 + 10) = (buffer(4096 + 10) ^ 0xff).toByte
    assertEquals(
      interpreter.evaluate(request(script)),
      ScarabEvaluator.pure.evaluate(request(script)),
      "the kept key does not change with the buffer it was read from"
    )
    assertEquals(interpreter.held._1, 1)
  }

  test("a lookup from a larger buffer leaves the kept key in place") {
    val interpreter = ScarabEvaluator.Interpreter()
    val script = AmplifyingScripts.lams(1_000, 0)
    val _ = interpreter.evaluate(request(script))
    val buffer = Array.fill[Byte](4096)(0) ++ script.toArray ++ Array.fill[Byte](4096)(0)
    val window = Bytes.wrap(buffer, 4096, script.size)
    assertEquals(interpreter.validate(PlutusLanguage.V1, 11, window), Right(()))
    assertEquals(interpreter.scripts, Vector(script))
    assert(
      interpreter.scripts.forall(kept => kept.compact eq kept),
      "a kept key owns exactly its bytes, and holds no buffer a lookup was read from"
    )
    assertEquals(interpreter.held, (1, weighed(script)))
  }

  private val leios = Path.of("fixtures/reference/leios-testnet-chain-scripts")

  /** The recorded scripts with the cost model of their request. */
  private lazy val recorded: Vector[(PlutusLanguage, Int, Bytes, Vector[Long])] =
    val models = GeneratedReplay.rows(leios.resolve("models.jsonl"))
    GeneratedReplay
      .rows(leios.resolve("requests.jsonl"))
      .map(r =>
        (
          PlutusLanguage.fromOrdinal(field[Int](r, "language") - 1),
          field[Int](r, "protocol"),
          hex(field[String](r, "script")),
          field[Vector[Long]](models(field[Int](r, "model")), "costModel")
        )
      )

  test("a recorded script's well-formedness is the same decoded afresh and reused from the cache") {
    val interpreter = ScarabEvaluator.Interpreter()
    val cold = recorded.map((language, protocol, script, _) =>
      ScarabEvaluator.pureDecoder.validate(language, protocol, script)
    )
    val evaluated = recorded.map((language, protocol, script, model) =>
      interpreter.evaluate(
        EvalRequest(language, protocol, script, Vector.empty, model, PlutusBoundaryCorpus.budget)
      )
    )
    assertEquals(evaluated.filterNot(verdict), Vector.empty)
    val (kept, _) = interpreter.held
    assertEquals(kept, recorded.map(r => (r._1, r._2, r._3)).distinct.size, "every one is kept")
    val warm =
      recorded.map((language, protocol, script, _) =>
        interpreter.validate(language, protocol, script)
      )
    assertEquals(warm, cold)
    assertEquals(cold.count(_.isRight), recorded.size, "every recorded script is well formed")
  }

  // The heap each shape's program holds, measured with `ProgramCacheHeapMain programs` (module
  // `tools`): 40 programs of each, after full collections, in a 768 MiB heap (`docs/performance.md`
  // section 59).
  private val measured = Vector(
    ("pair tree", AmplifyingScripts.pairTree, PlutusLanguage.V1, 11, 9_079L * 1024),
    ("lam chain", AmplifyingScripts.lams, PlutusLanguage.V1, 11, 1_249L * 1024),
    ("empty lists", AmplifyingScripts.emptyLists, PlutusLanguage.V1, 11, 3_289L * 1024),
    ("deep type", AmplifyingScripts.deepType, PlutusLanguage.V1, 10, 199L * 1024),
    ("constr chain", AmplifyingScripts.constrs, PlutusLanguage.V3, 11, 784L * 1024),
    ("data chain", AmplifyingScripts.dataChain, PlutusLanguage.V1, 11, 870L * 1024),
    ("data list", AmplifyingScripts.dataList, PlutusLanguage.V1, 11, 81L * 1024),
    ("data pairs", AmplifyingScripts.dataPairs, PlutusLanguage.V1, 11, 262L * 1024),
    ("empty values", AmplifyingScripts.emptyValues, PlutusLanguage.V3, 12, 6_285L * 1024)
  )

  test("each large program weighs at least the heap it was measured to hold") {
    measured.foreach { (name, make, language, protocol, heap) =>
      val script = make(16_000, 0)
      val weight = LedgerEval
        .deserialiseScript(language, ProtocolMajor(protocol), script)
        .fold(e => fail(s"$name: $e"), p => ProgramWeight.of(p))
      assert(weight.toLong >= heap, s"$name weighs ${weight.toLong}, below its $heap bytes of heap")
      assert(weight.toLong <= 4 * heap, s"$name weighs ${weight.toLong}, four times its $heap")
    }
  }

  test("a program's version is weighed with its components, however large") {
    val large = BigInt(1) << 80_000
    val version = ProgramVersion.of(large, 0, 0).getOrElse(fail("a version of naturals"))
    val weight = ProgramWeight.of(Program(version, Term.error))
    assert(weight.toLong >= 80_000L / 8, s"${weight.toLong}")
  }

  test("weighing stops once the weight is beyond the limit") {
    val program = decoded(AmplifyingScripts.pairTree(16_000, 0)).fold(e => fail(s"$e"), identity)
    val whole = ProgramWeight.of(program)
    val limit = Weight.mebibytes(1)
    val stopped = ProgramWeight.of(program, limit)
    assert(whole.toLong > 8L * 1024 * 1024, s"${whole.toLong}")
    assert(stopped.exceeds(limit), s"${stopped.toLong}")
    assert(stopped.toLong < 2L * 1024 * 1024, s"${stopped.toLong}: the walk went on")
  }

  test("terms, constants and data as deep as a script makes them are weighed on a small stack") {
    val depth = 200_000
    SmallStack.run {
      val lams = (1 to depth).foldLeft(Term.constant(Constant.Unit))((t, _) => Term.lam(t))
      val types = (1 to depth).foldLeft(Constant.Unit: Constant) { (c, _) =>
        Constant.List.of(c.typeOf, Vector(c)).getOrElse(fail("nesting"))
      }
      val data =
        (1 to depth).foldLeft(PlutusData.I(0): PlutusData)((d, _) => PlutusData.List(Vector(d)))
      val programs = Vector(
        Program(ProgramVersion.V1_0_0, lams),
        Program(ProgramVersion.V1_0_0, Term.constant(types)),
        Program(ProgramVersion.V1_0_0, Term.constant(Constant.Data(data)))
      )
      programs.foreach(p => assert(ProgramWeight.of(p).toLong > depth.toLong * 64))
    }
  }

  test("a weight saturates instead of overflowing") {
    assertEquals(Weight.unbounded + Weight.bytes(1), Weight.unbounded)
    assertEquals((Weight.unbounded * 2).toLong, Long.MaxValue)
    assertEquals(Weight.bytes(3) - Weight.bytes(5), Weight.zero)
    assertEquals(Weight.bytes(-1), Weight.zero)
  }
