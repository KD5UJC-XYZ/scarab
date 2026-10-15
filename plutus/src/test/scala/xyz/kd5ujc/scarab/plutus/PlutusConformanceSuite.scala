// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.{Json, parser}
import java.nio.file.{Files, Path, Paths}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scalus.cardano.ledger.{CostModels, Language, MajorProtocolVersion}
import scalus.uplc.{BuiltinSemanticsVariant, Constant, DeBruijnedProgram, Program, Term}
import scalus.uplc.builtin.platform
import scalus.uplc.eval.{Log, MachineParams, PlutusVM, RestrictingBudgetSpender}

/** The upstream Plutus conformance suite (`plutus-conformance/test-cases/uplc/evaluation` of plutus
  * 1.70.0.0, vendored under `src/test/resources/plutus-conformance-1.70.0.0`, see the README there)
  * run through [[ScalusEvaluator]].
  *
  * Each case is a textual UPLC program, the expected result (a program, `evaluation failure` or
  * `parse/decode error`) and the expected budget. The reference runs them with the default cost
  * model for testing, which is semantics variant E (`defaultCostModelParamsForTesting`), the
  * latest: Plutus V3 at protocol 11 in this node. Here a case passes when
  *   - the text parses (else the suite must expect a parse error),
  *   - [[ScalusEvaluator]] evaluates the flat-encoded program through the same path as a ledger
  *     script, with the variant E cost model as on-chain parameters, with the verdict the suite
  *     expects (a decode failure counts as the suite's `parse/decode error`),
  *   - the budget spent equals `.budget.expected` (also for a failing evaluation: the suite records
  *     the budget spent up to the failure), and
  *   - the result term is alpha-equivalent to the expected one. [[ScriptEvaluator]] reports no
  *     term, so the term is taken from a second run of the same machine, built from the same
  *     parameters; both runs must spend the same budget.
  *
  * Cases that fail for a known reason are listed with their classification in
  * `plutus-conformance-1.70.0.0-known.txt`; the suite fails when the set of failing cases differs
  * from that list in either direction, so a fixed deviation has to be removed from it.
  */
class PlutusConformanceSuite extends FunSuite:
  import PlutusConformanceSuite.*

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val language = PlutusLanguage.V3
  private val protocol = 11

  private val root: Path =
    Paths.get(getClass.getResource(s"/$Directory").toURI)

  /** The machine [[ScalusEvaluator]] builds for the request, to read the result term from. */
  private val machine: PlutusVM =
    val scalusLanguage = Language.fromId(language.id)
    val major = MajorProtocolVersion(protocol)
    val variant = BuiltinSemanticsVariant.fromProtocolAndPlutusVersion(major, scalusLanguage)
    assertEquals(variant, BuiltinSemanticsVariant.E)
    val params = MachineParams.fromCostModels(
      CostModels(Map(language.id -> costModel.toIndexedSeq)),
      scalusLanguage,
      major
    )
    PlutusVM(scalusLanguage, params, variant, platform, major)

  private val Unlimited = ExUnits(BigInt(1) << 60, BigInt(1) << 60)

  private def read(p: Path): String = Files.readString(p)

  private def budgetOf(text: String): Option[ExUnits] =
    val n = raw"cpu:\s*(\d+)\s*\|\s*mem:\s*(\d+)".r.unanchored
    text match
      case n(cpu, mem)                                 => Some(ExUnits(BigInt(mem), BigInt(cpu)))
      case "parse/decode error" | "evaluation failure" => None
      case _                                           => fail(s"budget file: $text")

  private def cases: Vector[Case] =
    Files
      .walk(root)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.endsWith(".uplc"))
      .map { p =>
        val dir = p.getParent
        val name = p.getFileName.toString.stripSuffix(".uplc")
        Case(
          root.relativize(dir).toString,
          read(p),
          read(dir.resolve(s"$name.uplc.expected")).trim,
          budgetOf(read(dir.resolve(s"$name.budget.expected")))
        )
      }
      .toVector
      .sortBy(_.id)

  /** Runs one case through the evaluator; `None` when it passes, else why not. */
  private def check(c: Case): Option[String] =
    val parsed = Program.parseUplc(c.program)
    c.expected match
      case "parse/decode error" =>
        parsed match
          case Left(_) => None
          case Right(p) =>
            run(p) match
              case Outcome.Undecodable(_) => None
              case other                  => Some(s"expected a parse/decode error, got $other")
      case _ =>
        parsed match
          case Left(e) => Some(s"our parser rejects it: $e")
          case Right(p) =>
            val outcome = run(p)
            val budgetOk = (spent: ExUnits) =>
              c.budget match
                case Some(b) =>
                  Option.when(spent != b)(s"budget: expected ${b.show}, got ${spent.show}")
                case None =>
                  // the suite records no budget for a failing evaluation or an undecodable program
                  Option.when(c.expected != "evaluation failure")("the suite records no budget")
            (c.expected, outcome) match
              case (_, Outcome.Undecodable(r))                      => Some(s"decode failure: $r")
              case ("evaluation failure", Outcome.Failed(spent, _)) => budgetOk(spent)
              case ("evaluation failure", Outcome.Succeeded(spent, _)) =>
                Some(s"expected an evaluation failure, evaluated (${spent.show})")
              case (_, Outcome.Failed(spent, r)) => Some(s"evaluation failure: $r (${spent.show})")
              case (text, Outcome.Succeeded(spent, term)) =>
                val expected = Program.parseUplc(text)
                expected match
                  case Left(e) => Some(s"cannot parse the expected result: $e")
                  case Right(e) =>
                    val same = sameTerm(e.deBruijnedProgram.term, term)
                    Option
                      .when(!same)(s"result: expected $text, got ${term.show}")
                      .orElse(budgetOk(spent))

  /** Equality of de Bruijn terms up to the names of binders and annotations. */
  private def sameTerm(a: Term, b: Term): Boolean = (a, b) match
    case (Term.Var(x, _), Term.Var(y, _))             => x.index == y.index
    case (Term.LamAbs(_, x, _), Term.LamAbs(_, y, _)) => sameTerm(x, y)
    case (Term.Apply(f, x, _), Term.Apply(g, y, _))   => sameTerm(f, g) && sameTerm(x, y)
    case (Term.Force(x, _), Term.Force(y, _))         => sameTerm(x, y)
    case (Term.Delay(x, _), Term.Delay(y, _))         => sameTerm(x, y)
    case (Term.Const(c, _), Term.Const(d, _))         => c == d
    case (Term.Builtin(f, _), Term.Builtin(g, _))     => f == g
    case (Term.Error(_), Term.Error(_))               => true
    case (Term.Constr(t, xs, _), Term.Constr(u, ys, _)) =>
      t == u && xs.size == ys.size && xs.zip(ys).forall(sameTerm.tupled)
    case (Term.Case(x, cs, _), Term.Case(y, ds, _)) =>
      sameTerm(x, y) && cs.size == ds.size && cs.zip(ds).forall(sameTerm.tupled)
    case _ => false

  private enum Outcome:
    case Undecodable(reason: String)
    case Failed(spent: ExUnits, reason: String)
    case Succeeded(spent: ExUnits, term: scalus.uplc.Term)

  /** A Plutus V3 script must return unit (a ledger rule, enforced by Scalus), which the suite's
    * programs do not. So the program runs as the argument of `(lam r (con unit ()))`: the same
    * evaluator and costing, and the overhead of the wrapper is subtracted from the budget. The
    * wrapper takes one machine step each for the application, the lambda and the constant, or only
    * the first two when the program fails.
    */
  private def wrapped(p: Program): Program =
    Program(
      p.version,
      Term.Apply(Term.LamAbs("$conformance", Term.Const(Constant.Unit)), p.term)
    )

  private val step: ExUnits =
    val machineCosts = json("cekMachineCostsE.json").hcursor
    def cost(key: String, unit: String) =
      machineCosts.downField(key).downField(unit).as[Long].fold(throw _, identity)
    // every step kind costs the same in variant E; the wrapper uses apply, lam and const
    val kinds = Vector("cekApplyCost", "cekLamCost", "cekConstCost")
    assertEquals(kinds.map(cost(_, "exBudgetCPU")).distinct.size, 1)
    assertEquals(kinds.map(cost(_, "exBudgetMemory")).distinct.size, 1)
    ExUnits(cost(kinds.head, "exBudgetMemory"), cost(kinds.head, "exBudgetCPU"))

  private def minus(a: ExUnits, steps: Int): ExUnits =
    ExUnits(a.mem - step.mem * steps, a.steps - step.steps * steps)

  private def run(p: Program): Outcome =
    // A free variable cannot be flat-encoded: the reference reports it as a decode error too.
    scala.util.Try((p.cborEncoded, wrapped(p).cborEncoded)) match
      case scala.util.Failure(e) => Outcome.Undecodable(s"cannot encode: ${e.getMessage}")
      case scala.util.Success((plain, wrap)) => runEncoded(plain, wrap)

  private def runEncoded(plain: Array[Byte], wrap: Array[Byte]): Outcome =
    val request =
      EvalRequest(language, protocol, Bytes.fromArray(wrap), Vector.empty, costModel, Unlimited)
    ScalusEvaluator.pure.evaluate(request) match
      case EvalResult.Failure(EvalFailure.Decode(r), _, _) => Outcome.Undecodable(r)
      case EvalResult.Failure(r, spent, _)                 =>
        // the failure happened inside the program, after the wrapper's application and lambda
        Outcome.Failed(minus(spent, 2), r.message)
      case EvalResult.Success(spent, _) =>
        // the result term from the same machine on the program itself
        val spender = RestrictingBudgetSpender(
          scalus.cardano.ledger.ExUnits(Unlimited.mem.toLong, Unlimited.steps.toLong)
        )
        val term = machine.evaluateDeBruijnedTerm(
          DeBruijnedProgram.fromCbor(plain).term,
          spender,
          Log(),
          false
        )
        val direct = ExUnits(spender.getSpentBudget.memory, spender.getSpentBudget.steps)
        // the evaluator run is the plain run plus the wrapper's three steps (the startup cost once)
        assertEquals(minus(spent, 3), direct, "the term run and the evaluator run spend the same")
        Outcome.Succeeded(minus(spent, 3), term)

  private def known: Map[String, String] =
    val in = getClass.getResourceAsStream(s"/$Directory-known.txt")
    if in == null then Map.empty
    else
      try
        String(in.readAllBytes(), "UTF-8").linesIterator
          .filter(l => l.nonEmpty && !l.startsWith("#"))
          .map { l =>
            val Array(path, rest*) = l.split('\t'): @unchecked
            path -> rest.mkString("\t")
          }
          .toMap
      finally in.close()

  test("upstream Plutus conformance, evaluation cases") {
    val started = System.nanoTime
    val all = cases
    val results = all.map(c => c -> check(c))
    val seconds = (System.nanoTime - started) / 1e9
    val categories = results.groupBy { case (c, _) => category(c.id) }.toVector.sortBy(_._1)
    val knownMap = known
    val summary = categories.map { case (cat, rs) =>
      val failed = rs.count(_._2.isDefined)
      s"  $cat: ${rs.size - failed} passed, $failed failed of ${rs.size}"
    }
    val failures = results.collect { case (c, Some(why)) => c.id -> why }
    println(
      s"plutus conformance 1.70.0.0, variant E, ${all.size} cases in ${"%.1f".format(seconds)} s\n" +
        summary.mkString("\n") +
        s"\n  total: ${all.size - failures.size} passed, ${failures.size} failed " +
        s"(${failures.count(f => knownMap.contains(f._1))} known)"
    )
    val unexpected = failures.filterNot(f => knownMap.contains(f._1))
    val fixed = knownMap.keySet -- failures.map(_._1).toSet
    assert(
      unexpected.isEmpty,
      s"${unexpected.size} unexpected failures:\n" +
        unexpected.map((id, why) => s"$id: $why").mkString("\n")
    )
    assert(fixed.isEmpty, s"known failures that now pass (remove them): ${fixed.toVector.sorted}")
  }

object PlutusConformanceSuite:
  val Directory = "plutus-conformance-1.70.0.0"

  def resource(name: String): String =
    val in = PlutusConformanceSuite.getClass.getResourceAsStream(s"/plutus-1.70.0.0/$name")
    assert(in != null, name)
    try String(in.readAllBytes(), "UTF-8")
    finally in.close()

  def json(name: String): Json = parser.parse(resource(name)).fold(throw _, identity)

  /** The variant E on-chain parameters of the language, valued from the plutus JSON files. */
  lazy val costModel: Vector[Long] =
    val builtins = json("builtinCostModelE.json")
    val machine = json("cekMachineCostsE.json")
    def valueOf(name: String): Option[Long] =
      val path = name.split('\'').toVector
      val head = s"${path.head.head.toLower}${path.head.tail}"
      path.tail
        .foldLeft(
          Option(
            (if head.startsWith("cek") then machine else builtins).hcursor.downField(head).focus
          )
        )((j, k) => j.flatten.map(_.hcursor.downField(k).focus))
        .flatten
        .flatMap(_.asNumber)
        .flatMap(_.toLong)
    resource(s"V${PlutusLanguage.V3.id + 1}-param-names.txt").linesIterator
      .filter(_.nonEmpty)
      .map(valueOf)
      .takeWhile(_.isDefined)
      .map(_.get)
      .toVector

  private final case class Case(
      id: String,
      program: String,
      expected: String,
      budget: Option[ExUnits]
  )

  /** `builtin/semantics`, `builtin/parser`, `term`, ... */
  private def category(id: String): String =
    id.split('/').take(if id.startsWith("builtin/") then 2 else 1).mkString("/")

  extension (e: ExUnits) private def show: String = s"cpu ${e.steps}, mem ${e.mem}"
