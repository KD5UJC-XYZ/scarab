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
import xyz.kd5ujc.scarab.uplc.ledger.{LedgerScriptEvaluation, Unavailability}
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

    /** The evaluator gives no verdict: a defect, never a recorded stage. */
    case NoVerdict(reason: Unavailability)

    def name: String = this match
      case Success(_)   => "success"
      case Decode       => "decode"
      case Setup        => "setup"
      case Scope        => "scope"
      case Budget       => "budget"
      case Evaluation   => "evaluation"
      case NoVerdict(_) => "no verdict"

  /** The evaluator the full replays run through, from the scripts' bytes: the ledger's
    * `deserialiseScript` and `evaluateScriptRestricting` ([[LedgerScriptEvaluation]]).
    */
  val instance: Option[ScriptEvaluation] = Some(LedgerScriptEvaluation)

/** The whole-script costing oracles of plutus 1.70.0.0 (`fixtures/reference/plutus-costing`: 55,740
  * observations of 1,858 scripts under 30 parameter lists, and 111,552 budget boundaries;
  * `fixtures/reference/genesis-cost-models/evaluation.jsonl`: 5,661 observations under lists of
  * every length and of extreme values). Every list makes a context with its profile's variant. The
  * scripts that are upstream conformance programs (797 of 1,858, each wrapped in the oracle's
  * strict lambda returning unit, `[(lam x (con unit ())) P]`) are replayed from their text: the
  * cost of every single application of a builtin to constants, and the machine with the core
  * builtins on every program whose builtins have denotations, counting and at the budget
  * boundaries. Every row of both oracles is also replayed from the scripts' bytes through the
  * ledger's evaluation ([[ScriptEvaluation.instance]]): the admission, the stage and, on success,
  * the exact budget, then each success within its own budget and one unit less of each component.
  */
final class CostingOracleSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

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

  /** The full replays from the scripts' bytes, every row (about 40 seconds and 10 seconds). */
  private def fullReplay: ScriptEvaluation =
    ScriptEvaluation.instance.getOrElse(
      fail("incomplete: no evaluator from the scripts' bytes (ScriptEvaluation.instance)")
    )

  test("replay: the 55,740 observations and 111,552 budget boundaries, every row") {
    val (observations, boundaries, disagreements) =
      CostingOracleReplay.costing(fullReplay, _ => true)
    println(
      s"costing oracle, from the scripts' bytes: $observations observations and $boundaries " +
        s"budget boundaries checked, ${disagreements.size} disagreements"
    )
    assertEquals(disagreements.take(40), Vector.empty[String])
    assertEquals(observations, 55740)
    assertEquals(boundaries, 111552)
  }

  test("replay: the 5,661 genesis cost-model observations, lists of every length, every row") {
    val (observations, disagreements) = CostingOracleReplay.genesis(fullReplay, _ => true)
    println(
      s"genesis cost-model lists, from the scripts' bytes: $observations observations checked, " +
        s"${disagreements.size} disagreements"
    )
    assertEquals(disagreements.take(40), Vector.empty[String])
    assertEquals(observations, 5661)
  }

/** The whole-script replays of the costing oracle and the genesis cost-model observations through a
  * [[ScriptEvaluation]], over the rows `select` keeps: the admission, the stage and, on success,
  * the exact budget; then, for a success, the budget itself (success), less one CPU unit (budget)
  * and less one memory unit (budget). Each returns the rows checked and every disagreement.
  */
object CostingOracleReplay:
  private val costing = Path.of("fixtures/reference/plutus-costing")
  private val genesisDir = Path.of("fixtures/reference/genesis-cost-models")
  private val unlimited = ExBudget.of(1L << 60, 1L << 60)

  private def rows(path: Path): Vector[Json] =
    Files
      .readString(path)
      .linesIterator
      .map(parser.parse(_).fold(e => throw new IllegalStateException(e.message), identity))
      .toVector

  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor
      .get[A](key)
      .fold(e => throw new IllegalStateException(s"$key: ${e.message} in $row"), identity)

  private def languageOf(number: Int): PlutusLanguage =
    PlutusLanguage.values
      .find(_.id + 1 == number)
      .getOrElse(throw new IllegalStateException(s"language $number"))

  private def bytes(hex: String): Bytes =
    Bytes.fromHex(hex).fold(e => throw new IllegalStateException(e.toString), identity)

  private def context(language: PlutusLanguage, values: Vector[Long]): EvaluationContext =
    EvaluationContext
      .build(language, values)
      ._2
      .fold(e => throw new IllegalStateException(e.toString), identity)

  /** The costing oracle's rows `select` keeps (by their id, `script:model`): the number of
    * observations and of budget boundaries checked, and the disagreements.
    */
  def costing(evaluator: ScriptEvaluation, select: String => Boolean): (Int, Int, Vector[String]) =
    val scripts = rows(costing.resolve("scripts.jsonl"))
    val models = rows(costing.resolve("models.jsonl"))
    val verdicts = rows(costing.resolve("verdicts.jsonl"))
    val results = models.zipWithIndex.flatMap { (model, j) =>
      val language = languageOf(field[Int](model, "language"))
      val protocol = ProtocolMajor(field[Int](model, "protocol"))
      val profile =
        s"V${field[Int](model, "language")}/${protocol.value} x${field[Int](model, "scale")}"
      lazy val built = context(language, field[Vector[Long]](model, "costModel"))
      scripts.zipWithIndex.flatMap { (script, i) =>
        val expected = verdicts(i * models.size + j)
        val rowId = field[String](expected, "id")
        Option.when(select(rowId)) {
          val id = s"${field[String](script, "id")} $profile"
          val code = bytes(field[String](script, "script"))
          val stage = field[String](expected, "stage")
          val admitted = evaluator.admits(language, protocol, code)
          val actual = evaluator.evaluate(language, protocol, code, built, unlimited)
          val units = Option.when(stage == "success")(
            ExBudget.of(field[Long](expected, "cpu"), field[Long](expected, "memory"))
          )
          val row = Vector(
            Option.when(rowId != s"$i:$j")(s"$id: verdict ${rowId}, expected $i:$j"),
            Option.when(admitted != field[Boolean](expected, "admitted"))(
              s"$id: admitted $admitted, oracle ${field[Boolean](expected, "admitted")}"
            ),
            Option.when(
              actual.name != stage || units.exists(u => actual != ScriptEvaluation.Stage.Success(u))
            )(s"$id: $actual, oracle $stage ${units.fold("")(_.toString)}")
          ).flatten
          val boundaries = units.toVector.flatMap { u =>
            val lessCpu = u.copy(cpu = SatInt(u.cpu.toLong - 1))
            val lessMemory = u.copy(memory = SatInt(u.memory.toLong - 1))
            Vector(u -> "success", lessCpu -> "budget", lessMemory -> "budget").map {
              (limit, want) =>
                val got = evaluator.evaluate(language, protocol, code, built, limit)
                Option.when(got.name != want)(s"$id within $limit: $got, oracle $want")
            }
          }
          (row, boundaries)
        }
      }
    }
    (
      results.size,
      results.map(_._2.size).sum,
      results.flatMap((row, boundaries) => row ++ boundaries.flatten)
    )

  /** The genesis observations `select` keeps (by their id, `script|list`): the number checked and
    * the disagreements.
    */
  def genesis(evaluator: ScriptEvaluation, select: String => Boolean): (Int, Vector[String]) =
    val observations = rows(genesisDir.resolve("evaluation.jsonl"))
    val code = rows(costing.resolve("scripts.jsonl"))
      .map(row => field[String](row, "id") -> field[String](row, "script"))
      .toMap
    val lists = rows(genesisDir.resolve("evaluation-models.jsonl"))
      .map(model => field[String](model, "id") -> model)
      .toMap
    val results = observations.flatMap { row =>
      val id = field[String](row, "id")
      Option.when(select(id)) {
        val (scriptId, modelId) = id.split('|') match
          case Array(s, m) => (s, m)
          case _           => throw new IllegalStateException(s"id $id")
        val model = lists.getOrElse(modelId, throw new IllegalStateException(s"no list $modelId"))
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
        val script =
          bytes(code.getOrElse(scriptId, throw new IllegalStateException(s"no script $scriptId")))
        val admitted = evaluator.admits(language, protocol, script)
        val actual =
          evaluator.evaluate(language, protocol, script, context(language, values), unlimited)
        val stage = field[String](row, "stage")
        val units = Option.when(stage == "success")(
          ExBudget.of(field[Long](row, "cpu"), field[Long](row, "memory"))
        )
        Vector(
          Option.when(admitted != field[Boolean](row, "admitted"))(
            s"$id: admitted $admitted, oracle ${field[Boolean](row, "admitted")}"
          ),
          Option.when(
            actual.name != stage || units.exists(u => actual != ScriptEvaluation.Stage.Success(u))
          )(s"$id: $actual, oracle $stage ${units.fold("")(_.toString)}")
        ).flatten
      }
    }
    (results.size, results.flatten)
