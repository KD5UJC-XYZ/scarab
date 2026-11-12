// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultFun,
  DefaultUni,
  PlutusLanguage,
  Program,
  ProtocolMajor,
  ReferenceCorpus,
  Schemes,
  SemanticsVariant,
  Term,
  TermF
}
import xyz.kd5ujc.scarab.uplc.builtin.{Argument, MachineValue}
import xyz.kd5ujc.scarab.uplc.cek.{BudgetMode, CaserBuiltin, CekError, CekMachine, CekResult}
import xyz.kd5ujc.scarab.uplc.text.UplcParser

/** What the whole-script oracles need of an evaluator: whether the ledger admits a script
  * (`deserialiseScript`) and the stage, and on success the budget, of evaluating it within a limit
  * (`evaluateScriptRestricting`).
  */
trait ScriptEvaluation:
  def admits(language: PlutusLanguage, protocol: ProtocolMajor, script: Bytes): Boolean
  def evaluate(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      script: Bytes,
      context: EvaluationContext,
      limit: ExBudget
  ): ScriptEvaluation.Stage

object ScriptEvaluation:
  /** The stages the oracles record (`BuiltinCostingOracleSuite`, `CostModelLengthSuite`). */
  enum Stage:
    case Success(spent: ExBudget)
    case Decode, Setup, Scope, Budget, Evaluation

    def name: String = this match
      case Success(_) => "success"
      case Decode     => "decode"
      case Setup      => "setup"
      case Scope      => "scope"
      case Budget     => "budget"
      case Evaluation => "evaluation"

  /** The evaluator the full replays run through, from the scripts' bytes: it needs the Flat decoder
    * and the ledger's admission (work package 2 of issue 43) and the denotations of every builtin
    * (work package 6 for the cryptographic, bitwise, `Value` and array ones). Until it is defined
    * the full replays are reported as skipped, not passed; the upstream programs are replayed in
    * part from their text meanwhile.
    */
  val instance: Option[ScriptEvaluation] = None

/** The whole-script costing oracles of plutus 1.70.0.0 (`fixtures/reference/plutus-costing`: 55,740
  * observations of 1,858 scripts under 30 parameter lists, and 111,552 budget boundaries;
  * `fixtures/reference/genesis-cost-models/evaluation.jsonl`: 5,661 observations under lists of
  * every length and of extreme values). Every list makes a context with its profile's variant. The
  * scripts that are upstream conformance programs (797 of 1,858, each wrapped in the oracle's
  * strict lambda returning unit, `[(lam x (con unit ())) P]`) are replayed from their text: the
  * cost of every single application of a builtin to constants, and the machine with the core
  * builtins on every program whose builtins have denotations, counting and at the budget
  * boundaries. The full replay from the scripts' bytes stays an explicit incomplete gate until
  * [[ScriptEvaluation.instance]] is defined.
  */
final class CostingOracleSuite extends munit.FunSuite:
  private val costing = Path.of("fixtures/reference/plutus-costing")
  private val genesis = Path.of("fixtures/reference/genesis-cost-models")

  private def rows(path: Path): Vector[Json] =
    Files.readString(path).linesIterator.map(parser.parse(_).fold(throw _, identity)).toVector

  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(s"$key: ${e.message}"), identity)

  private lazy val scripts = rows(costing.resolve("scripts.jsonl"))
  private lazy val models = rows(costing.resolve("models.jsonl"))
  private lazy val verdicts = rows(costing.resolve("verdicts.jsonl"))

  private def languageOf(number: Int): PlutusLanguage =
    PlutusLanguage.values.find(_.id + 1 == number).getOrElse(fail(s"language $number"))

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  test("the costing oracle's files are the ones its manifest binds, and complete") {
    val manifest =
      parser.parse(Files.readString(costing.resolve("manifest.json"))).fold(throw _, identity)
    field[Map[String, String]](manifest, "sha256").foreach((name, hash) =>
      assertEquals(sha256(costing.resolve(name)), hash, name)
    )
    assertEquals(
      field[String](manifest, "plutusCommit"),
      "507cab8cf6192e5143f12be5b2d3005a2928dc6d"
    )
    assertEquals(scripts.size, field[Int](manifest, "scripts"))
    assertEquals(verdicts.size, field[Int](manifest, "observations"))
    assertEquals(verdicts.size, scripts.size * models.size)
    assertEquals(
      verdicts.count(field[String](_, "stage") == "success"),
      field[Int](manifest, "successes")
    )
  }

  test("every parameter list of the costing oracle makes a context with its profile's variant") {
    models.foreach { model =>
      val language = languageOf(field[Int](model, "language"))
      val protocol = ProtocolMajor(field[Int](model, "protocol"))
      val scale = field[Int](model, "scale")
      val values = field[Vector[Long]](model, "costModel")
      val variant = SemanticsVariant.of(language, protocol)
      assertEquals(
        values,
        DefaultCostModels.list(language, variant).map(_ * scale),
        s"$language $protocol x$scale"
      )
      val (warnings, context) = EvaluationContext.build(language, values)
      assertEquals(warnings, Vector.empty, s"$language $protocol x$scale")
      val costs = context
        .fold(e => fail(e.toString), identity)
        .at(protocol)
        .getOrElse(fail(s"no costs at $protocol"))
      assertEquals(costs.variant, variant)
      assertEquals(
        (costs.model.machine.leaves ++ costs.model.builtins.leaves)
          .map((n, v) => n -> v.toLong)
          .toMap,
        DefaultCostModels.parameters(variant).map((n, v) => n -> v * scale),
        s"$language $protocol x$scale"
      )
    }
  }

  private given MachineValue[Constant] with
    def constantOf(value: Constant): Option[Constant] = Some(value)
    def ofConstant(constant: Constant): Constant = constant

  /** The builtin, its forces and its constant arguments, if `term` is nothing else. */
  private def application(term: Term): Option[(DefaultFun, Int, Vector[Constant])] =
    @tailrec def go(
        t: Term,
        forces: Int,
        arguments: List[Constant]
    ): Option[(DefaultFun, Int, Vector[Constant])] =
      t.unfix match
        case TermF.Builtin(f)   => Some((f, forces, arguments.toVector))
        case TermF.Force(inner) => go(inner, forces + 1, arguments)
        case TermF.Apply(function, argument) =>
          argument.unfix match
            case TermF.Constant(c) => go(function, forces, c :: arguments)
            case _                 => None
        case _ => None
    go(term, 0, Nil).filter((f, forces, arguments) =>
      arguments.size == f.arity && forces == f.forces
    )

  /** Whether a type holds a BLS12-381 value: such constants have no Flat encoding, and the oracle's
    * bytes apply `uncompress` instead (its README), so their steps differ from the text's.
    */
  private def bls(t: DefaultUni): Boolean = t match
    case DefaultUni.Bls12_381_G1_Element | DefaultUni.Bls12_381_G2_Element |
        DefaultUni.Bls12_381_MlResult =>
      true
    case DefaultUni.List(e)    => bls(e)
    case DefaultUni.Array(e)   => bls(e)
    case DefaultUni.Pair(a, b) => bls(a) || bls(b)
    case _                     => false

  private def constants(term: Term): Vector[Constant] =
    Schemes.cata[TermF, Vector[Constant]](term) {
      case TermF.Constant(c) => Vector(c)
      case layer             => cats.Foldable[TermF].foldLeft(layer, Vector.empty[Constant])(_ ++ _)
    }

  /** The upstream programs of the oracle by script index, read from the 1.70.0.0 view's text. */
  private lazy val upstream: Vector[(Int, Program)] =
    val programs = ReferenceCorpus
      .conformance(ReferenceCorpus.Pinned)
      .fold(p => fail(p.toString), identity)
      .map(c => c.name -> c.program)
      .toMap
    scripts.zipWithIndex.flatMap { (row, i) =>
      val id = field[String](row, "id")
      Option
        .when(id.startsWith("upstream/"))(
          "builtin/semantics/" + id.stripPrefix("upstream/").split('/').dropRight(1).mkString("/")
        )
        .map(name =>
          programs
            .get(name)
            .fold(fail(s"no conformance case $name for $id"))(text =>
              UplcParser.program(text).fold(e => fail(s"$name: $e"), p => i -> p)
            )
        )
    }

  private enum Check:
    case Costed, Evaluated

  private def outOfBudget(result: CekResult): Boolean = result match
    case CekResult.Failure(failure) =>
      failure.error match
        case CekError.OutOfBudget(_) => true
        case _                       => false
    case _ => false

  private def stageOf(result: CekResult): String = result match
    case _: CekResult.Success => "success"
    case r if outOfBudget(r)  => "budget"
    case _                    => "evaluation"

  test(
    "partial replay: the upstream programs of the costing oracle from their text, every profile"
  ) {
    assertEquals(upstream.size, 797)
    val unit = Term.lam(Term.constant(Constant.Unit))
    val limit = ExBudget.of(1L << 60, 1L << 60)
    val checks = models.zipWithIndex.flatMap { (model, j) =>
      val language = languageOf(field[Int](model, "language"))
      val protocol = ProtocolMajor(field[Int](model, "protocol"))
      val profile =
        s"V${field[Int](model, "language")} protocol ${protocol.value} x${field[Int](model, "scale")}"
      val costs = EvaluationContext
        .build(language, field[Vector[Long]](model, "costModel"))
        ._2
        .toOption
        .flatMap(_.at(protocol))
        .getOrElse(fail(s"no costs for $profile"))
      val meanings = CekConformance.denotations(costs.variant)
      val caser =
        if protocol.value < 11 then CaserBuiltin.Unavailable(protocol) else CaserBuiltin.WithoutData
      val machine = CekConformance.machine(costs, caser)
      val steps = costs.machineCosts
      upstream.flatMap { (i, program) =>
        val verdict = verdicts(i * models.size + j)
        val stage = field[String](verdict, "stage")
        val name = s"$profile ${field[String](scripts(i), "id")}"
        val hasBls = constants(program.term).exists(c => bls(c.typeOf))
        lazy val expected = ExBudget.of(field[Long](verdict, "cpu"), field[Long](verdict, "memory"))
        // The cost alone: the start-up, the program's steps and the wrapper's (an apply, a lambda
        // and a constant), and the builtin's cost, added with saturation.
        val costed = Option
          .when(stage == "success" && !hasBls)(application(program.term))
          .flatten
          .map { (function, forces, arguments) =>
            val n = arguments.size.toLong
            val total = steps.startup + steps.step(StepKind.Apply).times(n + 1) +
              steps.step(StepKind.Force).times(forces.toLong) + steps.step(StepKind.Builtin) +
              steps.step(StepKind.Constant).times(n + 1) + steps.step(StepKind.LamAbs) +
              costs.costing(function)(arguments.map(Argument.Con[Constant](_))).total
            Check.Costed -> Option.when(total != expected)(
              s"$name: costed $total, oracle $expected"
            )
          }
        // The machine with the core builtins where they suffice, as the oracle ran it: within
        // 2^60, at the exact budget and one unit below in each dimension.
        val available = !hasBls && stage != "decode" &&
          CekConformance.builtins(program).forall(meanings.contains)
        val wrapped = Term.app(unit, program.term)
        def run(mode: BudgetMode) = CekMachine.run(machine, mode, wrapped)
        val evaluated = Option.when(available) {
          val disagreement =
            if stage == "success" then
              val counting = run(BudgetMode.Counting)
              val exact = run(BudgetMode.Restricting(expected)).result
              val lessCpu = expected.copy(cpu = SatInt(expected.cpu.toLong - 1))
              val lessMemory = expected.copy(memory = SatInt(expected.memory.toLong - 1))
              val cpuShort = run(BudgetMode.Restricting(lessCpu)).result
              val memoryShort = run(BudgetMode.Restricting(lessMemory)).result
              Option.when(
                counting.budget.spent != expected || stageOf(counting.result) != "success" ||
                  stageOf(exact) != "success" || !outOfBudget(cpuShort) || !outOfBudget(memoryShort)
              )(
                s"$name: spent ${counting.budget.spent} (${stageOf(counting.result)}), oracle " +
                  s"$expected; exact ${stageOf(exact)}, CPU - 1 ${stageOf(cpuShort)}, " +
                  s"memory - 1 ${stageOf(memoryShort)}"
              )
            else
              val actual = stageOf(run(BudgetMode.Restricting(limit)).result)
              Option.when(actual != stage)(s"$name: $actual, oracle $stage")
          Check.Evaluated -> disagreement
        }
        costed.toVector ++ evaluated.toVector
      }
    }
    val disagreements = checks.flatMap(_._2)
    val costed = checks.count(_._1 == Check.Costed)
    val evaluated = checks.count(_._1 == Check.Evaluated)
    println(
      s"costing oracle, upstream programs from their text over ${models.size} profiles: $costed " +
        s"costs and $evaluated machine runs checked, ${disagreements.size} disagreements"
    )
    assertEquals(disagreements.take(40), Vector.empty[String])
  }

  test("partial replay: the genesis lists of every length and extreme values, upstream programs") {
    val observations = rows(genesis.resolve("evaluation.jsonl"))
    val lists =
      rows(genesis.resolve("evaluation-models.jsonl")).map(m => field[String](m, "id") -> m).toMap
    val programs = upstream.map((i, p) => field[String](scripts(i), "id") -> p).toMap
    val unit = Term.lam(Term.constant(Constant.Unit))
    val limit = ExBudget.of(1L << 60, 1L << 60)
    val results = observations.flatMap { row =>
      val id = field[String](row, "id")
      val (scriptId, listId) = id.split('|') match
        case Array(s, l) => (s, l)
        case _           => fail(s"id $id")
      val model = lists.getOrElse(listId, fail(s"no list $listId"))
      val language = languageOf(field[Int](model, "language"))
      val protocol = ProtocolMajor(field[Int](model, "protocol"))
      val stage = field[String](row, "stage")
      programs
        .get(scriptId)
        .filter(p => stage != "decode" && !constants(p.term).exists(c => bls(c.typeOf)))
        .flatMap { program =>
          // The list's values, one value repeated, or the profile's defaults cut or extended with
          // sevens (the rule of CostModelLengthSuite).
          val values =
            (field[Option[Vector[Long]]](model, "values"), field[Option[Long]](model, "fill")) match
              case (Some(values), _)  => values
              case (None, Some(fill)) => Vector.fill(field[Int](model, "length"))(fill)
              case (None, None) =>
                (DefaultCostModels.list(language, SemanticsVariant.of(language, protocol)) ++
                  Vector.fill(60)(7L)).take(field[Int](model, "length"))
          val costs = EvaluationContext
            .build(language, values)
            ._2
            .toOption
            .flatMap(_.at(protocol))
            .getOrElse(fail(s"no costs for $listId"))
          Option.when(
            CekConformance
              .builtins(program)
              .forall(CekConformance.denotations(costs.variant).contains)
          ) {
            val caser =
              if protocol.value < 11 then CaserBuiltin.Unavailable(protocol)
              else CaserBuiltin.WithoutData
            val report = CekMachine.run(
              CekConformance.machine(costs, caser),
              BudgetMode.Restricting(limit),
              Term.app(unit, program.term)
            )
            val actual = stageOf(report.result)
            val spent = report.budget.spent
            val expected = Option.when(stage == "success")(
              ExBudget.of(field[Long](row, "cpu"), field[Long](row, "memory"))
            )
            Option.when(actual != stage || expected.exists(_ != spent))(
              s"$id: $actual ${Option.when(actual == "success")(spent).fold("")(_.toString)}, " +
                s"oracle $stage ${expected.fold("")(_.toString)}"
            )
          }
        }
    }
    val disagreements = results.flatten
    println(
      s"genesis cost-model lists, upstream programs from their text: ${results.size} of " +
        s"${observations.size} observations run through the machine " +
        s"(${observations
            .count(r => field[String](r, "stage") == "success" && field[Long](r, "cpu") < 0)} " +
        s"successes with a negative CPU in the fixture), ${disagreements.size} disagreements"
    )
    assertEquals(disagreements.take(40), Vector.empty[String])
  }

  test(
    "replay: the 55,740 observations and 111,552 budget boundaries (incomplete until the decoder and every builtin exist)"
  ) {
    val evaluation = ScriptEvaluation.instance
    assume(
      evaluation.isDefined,
      "incomplete: no Flat decoder and admission (issue 43, WP2) and no denotations of WP6's builtins yet"
    )
    evaluation.foreach { evaluator =>
      val unlimited = ExBudget.of(1L << 60, 1L << 60)
      models.zipWithIndex.foreach { (model, j) =>
        val language = languageOf(field[Int](model, "language"))
        val protocol = ProtocolMajor(field[Int](model, "protocol"))
        val context = EvaluationContext
          .build(language, field[Vector[Long]](model, "costModel"))
          ._2
          .fold(e => fail(e.toString), identity)
        scripts.zipWithIndex.foreach { (script, i) =>
          val id = field[String](script, "id")
          val expected = verdicts(i * models.size + j)
          assertEquals(field[String](expected, "id"), s"$i:$j")
          val code =
            Bytes.fromHex(field[String](script, "script")).fold(e => fail(e.toString), identity)
          assertEquals(
            evaluator.admits(language, protocol, code),
            field[Boolean](expected, "admitted"),
            id
          )
          val actual = evaluator.evaluate(language, protocol, code, context, unlimited)
          assertEquals(actual.name, field[String](expected, "stage"), id)
          if field[String](expected, "stage") == "success" then
            val units = ExBudget.of(field[Long](expected, "cpu"), field[Long](expected, "memory"))
            assertEquals(actual, ScriptEvaluation.Stage.Success(units), id)
            assertEquals(
              evaluator.evaluate(language, protocol, code, context, units).name,
              "success",
              s"$id exact"
            )
            val lessCpu = units.copy(cpu = SatInt(units.cpu.toLong - 1))
            assertEquals(
              evaluator.evaluate(language, protocol, code, context, lessCpu).name,
              "budget",
              s"$id CPU - 1"
            )
            val lessMemory = units.copy(memory = SatInt(units.memory.toLong - 1))
            assertEquals(
              evaluator.evaluate(language, protocol, code, context, lessMemory).name,
              "budget",
              s"$id memory - 1"
            )
        }
      }
    }
  }

  test(
    "replay: the genesis cost-model observations, lists of every length (incomplete until the decoder and every builtin exist)"
  ) {
    val observations = rows(genesis.resolve("evaluation.jsonl"))
    assertEquals(observations.size, 5661)
    val evaluation = ScriptEvaluation.instance
    assume(
      evaluation.isDefined,
      "incomplete: no Flat decoder and admission (issue 43, WP2) and no denotations of WP6's builtins yet"
    )
    evaluation.foreach { evaluator =>
      val unlimited = ExBudget.of(1L << 60, 1L << 60)
      val code = scripts.map(row => field[String](row, "id") -> field[String](row, "script")).toMap
      val lists = rows(genesis.resolve("evaluation-models.jsonl"))
        .map(model => field[String](model, "id") -> model)
        .toMap
      observations.foreach { row =>
        val id = field[String](row, "id")
        val (scriptId, modelId) = id.split('|') match
          case Array(s, m) => (s, m)
          case _           => fail(s"id $id")
        val model = lists.getOrElse(modelId, fail(s"no list $modelId"))
        val language = languageOf(field[Int](model, "language"))
        val protocol = ProtocolMajor(field[Int](model, "protocol"))
        // The list's values, one value repeated, or the profile's defaults cut or extended with
        // sevens (the rule of CostModelLengthSuite).
        val values =
          (field[Option[Vector[Long]]](model, "values"), field[Option[Long]](model, "fill")) match
            case (Some(values), _)  => values
            case (None, Some(fill)) => Vector.fill(field[Int](model, "length"))(fill)
            case (None, None) =>
              (DefaultCostModels.list(language, SemanticsVariant.of(language, protocol)) ++ Vector
                .fill(60)(7L))
                .take(field[Int](model, "length"))
        val context =
          EvaluationContext.build(language, values)._2.fold(e => fail(e.toString), identity)
        val script = Bytes
          .fromHex(code.getOrElse(scriptId, fail(s"no script $scriptId")))
          .fold(e => fail(e.toString), identity)
        assertEquals(
          evaluator.admits(language, protocol, script),
          field[Boolean](row, "admitted"),
          id
        )
        val actual = evaluator.evaluate(language, protocol, script, context, unlimited)
        assertEquals(actual.name, field[String](row, "stage"), id)
        if field[String](row, "stage") == "success" then
          assertEquals(
            actual,
            ScriptEvaluation.Stage.Success(
              ExBudget.of(field[Long](row, "cpu"), field[Long](row, "memory"))
            ),
            id
          )
      }
    }
  }
