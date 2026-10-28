// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.{Json, parser}
import java.nio.file.{Files, Path, Paths}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scalus.cardano.ledger.{Language, MajorProtocolVersion}
import scalus.uplc.{BuiltinSemanticsVariant, Constant, DeBruijnedProgram, Program, Term}
import scalus.uplc.eval.{CountingBudgetSpender, Log, RestrictingBudgetSpender}
import scalus.uplc.DeBruijn

/** All original Plutus 1.70.0.0 evaluation cases, under the upstream executable's term profile:
  * variant E, saturating counting and `caseBuiltin` including Data. Terms execute directly so BLS
  * constants retain their meaning and cost rather than being rewritten as Flat `uncompress` calls.
  * The machine is the one [[ScalusEvaluator]] builds for a request ([[ScalusEvaluator.corrected]],
  * [[ScalusEvaluator.CorrectedVm.cek]]): same builtin runtimes, cost model, `case` on built-in
  * values and tracing flag. Two settings follow the upstream executable (`CekEvaluator.hs` lines
  * 46-61 at plutus 507cab8c) and differ from the ledger's: the budget is the counting one, and
  * `case` on `Data` is allowed (`caseBuiltin`, where the ledger's `caseBuiltinNoData` applies from
  * protocol 11 up to Dijkstra, `Common/Eval.hs` lines 142-146).
  *
  * Original malformed-Value Flat sidecars retain decoder coverage where the dependency's text
  * parser normalises their order. Ledger admission and restricting budgets are separate contracts,
  * tested by [[PlutusReachabilitySuite]] and the focused profile regressions below. No case is
  * excluded: successful terms and recorded budgets must equal the unchanged upstream expectations.
  */
class PlutusConformanceSuite extends FunSuite:
  import PlutusConformanceSuite.*

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private val language = PlutusLanguage.V3
  private val protocol = 11

  private val root: Path =
    Paths.get(getClass.getResource(s"/$Directory").toURI)

  /** The machine [[ScalusEvaluator]] builds for the request ([[ScalusEvaluator.corrected]]). */
  private val machine: ScalusEvaluator.CorrectedVm =
    val variant = BuiltinSemanticsVariant.fromProtocolAndPlutusVersion(
      MajorProtocolVersion(protocol),
      Language.fromId(language.id)
    )
    assertEquals(variant, BuiltinSemanticsVariant.E)
    ScalusEvaluator
      .corrected(language, protocol, costModel)
      .fold(failure => fail(failure.message), identity)

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
          budgetOf(read(dir.resolve(s"$name.budget.expected"))),
          Option.when(Files.exists(dir.resolve(s"$name.flat.cbor.hex"))) {
            Bytes
              .fromHex(read(dir.resolve(s"$name.flat.cbor.hex")).trim)
              .fold(e => fail(e.toString), identity)
          }
        )
      }
      .toVector
      .sortBy(_.id)

  /** Runs one case through the evaluator; `None` when it passes, else why not. */
  private def check(c: Case): Option[String] = c.originalFlat match
    case Some(script) =>
      // Scalus's text parser normalises malformed Value maps. These public corpus literals
      // must reach the decoder as their original ordered Flat entries, before that parser.
      assertEquals(c.expected, "parse/decode error", c.id)
      Option.when(ScalusEvaluator.pureDecoder.validate(language, protocol, script).isRight)(
        "original malformed Flat constant decoded"
      )
    case None => checkParsed(c)

  private def checkParsed(c: Case, evaluate: Program => Outcome = run): Option[String] =
    val parsed = Program.parseUplc(c.program)
    c.expected match
      case "parse/decode error" =>
        parsed match
          case Left(_) => None
          case Right(p) =>
            evaluate(p) match
              case Outcome.Undecodable(_) => None
              case other                  => Some(s"expected a parse/decode error, got $other")
      case _ =>
        parsed match
          case Left(e) => Some(s"our parser rejects it: $e")
          case Right(p) =>
            val outcome = evaluate(p)
            val budgetOk = (spent: ExUnits) =>
              c.budget match
                case Some(b) =>
                  Option.when(spent != b)(s"budget: expected ${b.show}, got ${spent.show}")
                case None =>
                  // the suite records no budget for a failing evaluation or an undecodable program
                  Option.when(c.expected != "evaluation failure")("the suite records no budget")
            (c.expected, outcome) match
              case (_, Outcome.Unavailable(reason)) => Some(s"implementation unavailable: $reason")
              case (_, Outcome.Undecodable(r))      => Some(s"decode failure: $r")
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
    case Unavailable(reason: String)
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

  private def runLedger(p: Program): Outcome =
    // A free variable cannot be flat-encoded: the reference reports it as a decode error too.
    scala.util.Try((p.cborEncoded, wrapped(p).cborEncoded)) match
      case scala.util.Failure(e) => Outcome.Undecodable(s"cannot encode: ${e.getMessage}")
      case scala.util.Success((plain, wrap)) => runEncoded(plain, wrap)

  private def runEncoded(plain: Array[Byte], wrap: Array[Byte]): Outcome =
    val request =
      EvalRequest(language, protocol, Bytes.fromArray(wrap), Vector.empty, costModel, Unlimited)
    ScalusEvaluator.pure.evaluate(request) match
      case EvalResult.Failure(EvalFailure.Unsupported(reason), _, _) =>
        Outcome.Unavailable(reason.message)
      case EvalResult.Failure(EvalFailure.Decode(r), _, _) => Outcome.Undecodable(r.message)
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

  /** Plutus 507cab8c, PlutusConformance/CekEvaluator.hs:54-61 checks all free variables first. */
  @scala.annotation.tailrec
  private def closed(todo: List[(Term, Int)]): Boolean = todo match
    case Nil => true
    case (term, depth) :: rest =>
      term match
        case Term.Var(name, _)       => name.index > 0 && name.index <= depth && closed(rest)
        case Term.LamAbs(_, body, _) => closed((body -> (depth + 1)) :: rest)
        case Term.Apply(fun, arg, _) => closed((fun -> depth) :: (arg -> depth) :: rest)
        case Term.Force(body, _)     => closed((body -> depth) :: rest)
        case Term.Delay(body, _)     => closed((body -> depth) :: rest)
        case Term.Constr(_, fields, _) =>
          closed(fields.foldLeft(rest)((xs, t) => (t -> depth) :: xs))
        case Term.Case(scrutinee, branches, _) =>
          closed((scrutinee -> depth) :: branches.foldLeft(rest)((xs, t) => (t -> depth) :: xs))
        case _: Term.Const | _: Term.Builtin | _: Term.Error => closed(rest)

  /** The upstream executable evaluates terms directly, with no ledger result-unit restriction.
    * CountingBudgetSpender saturates additions as Plutus SatInt does (Scalus 1.3.0 Cek.scala:809).
    * The explicit Data flag mirrors caseBuiltin, rather than the ledger's caseBuiltinNoData at
    * PV11.
    */
  private def run(p: Program): Outcome =
    scala.util.Try(p.deBruijnedProgram.term) match
      case scala.util.Failure(error) => Outcome.Unavailable(error.toString)
      case scala.util.Success(term) if !closed(List(term -> 0)) =>
        Outcome.Undecodable("free variable in upstream term")
      case scala.util.Success(term) =>
        val spender = CountingBudgetSpender()
        val log = Log()
        def spent = ExUnits(spender.getSpentBudget.memory, spender.getSpentBudget.steps)
        val cek = machine.cek(spender, log, debug = false, caseOnData = true)
        scala.util
          .Try(DeBruijn.fromDeBruijnTerm(cek.evaluateTerm(term)))
          .fold(
            error =>
              if ScalusEvaluator.referenceFailure(error) then
                Outcome.Failed(spent, error.getMessage)
              else Outcome.Unavailable(error.toString),
            result => Outcome.Succeeded(spent, result)
          )

  test("upstream Plutus conformance, evaluation cases") {
    val started = System.nanoTime
    val all = cases
    val results = all.map(c => c -> check(c))
    val seconds = (System.nanoTime - started) / 1e9
    val categories = results.groupBy { case (c, _) => category(c.id) }.toVector.sortBy(_._1)
    val summary = categories.map { case (cat, rs) =>
      val failed = rs.count(_._2.isDefined)
      s"  $cat: ${rs.size - failed} passed, $failed failed of ${rs.size}"
    }
    val failures = results.collect { case (c, Some(why)) => c.id -> why }
    println(
      s"plutus conformance 1.70.0.0, variant E, ${all.size} cases in ${"%.1f".format(seconds)} s\n" +
        summary.mkString("\n") +
        s"\n  total: ${all.size - failures.size} passed, ${failures.size} failed"
    )
    assert(
      failures.isEmpty,
      s"${failures.size} failures:\n" +
        failures.map((id, why) => s"$id: $why").mkString("\n")
    )
    assertEquals(all.size, 1009, "the complete original evaluation corpus must execute")
  }

  test("upstream scope checking includes delayed and unselected subterms") {
    val texts = Vector(
      "(program 1.0.0 x)",
      "(program 1.0.0 (delay x))",
      "(program 1.1.0 (constr 0 x))",
      "(program 1.1.0 (case (constr 0) (con unit ()) x))"
    )
    texts.foreach { text =>
      val program = Program.parseUplc(text).fold(e => fail(e), identity)
      assert(run(program).isInstanceOf[Outcome.Undecodable], text)
    }
    val bound = Term.LamAbs("x", Term.Var(scalus.uplc.NamedDeBruijn("x", 1)))
    val deep = (1 to 20000).foldLeft(bound: Term)((term, _) => Term.Delay(term))
    assert(closed(List(deep -> 0)), "closedness walks deep terms without recursive JVM frames")
  }

  test("direct BLS constants, saturating counts and Data cases repair the original harness") {
    val all = cases
    val bls = all.filter(c => c.id.startsWith("builtin/parser/bls12-381/") && c.budget.nonEmpty)
    val saturated = all.filter(c =>
      c.id.startsWith("builtin/semantics/dropList/") &&
        c.budget.exists(_.steps == BigInt(Long.MaxValue))
    )
    val data = all.filter(c => c.id.startsWith("term/constant-case/data/") && c.budget.nonEmpty)
    assertEquals(bls.size, 6, "original parsed BLS constants")
    assertEquals(saturated.size, 5, "original saturating dropList cases")
    assertEquals(data.size, 2, "original Data caseBuiltin cases")
    (bls ++ saturated ++ data).foreach { c =>
      assert(
        checkParsed(c, runLedger).nonEmpty,
        s"${c.id}: the original ledger harness must diverge"
      )
      assertEquals(check(c), None, s"${c.id}: exact original result and budget")
    }
  }

  test("case on Data is the one setting of the machine that the upstream profile changes") {
    val data = cases.filter(c => c.id.startsWith("term/constant-case/data/") && c.budget.nonEmpty)
    assertEquals(data.size, 2, "original Data caseBuiltin cases")
    data.foreach { c =>
      val term = Program
        .parseUplc(c.program)
        .fold(e => fail(e), _.deBruijnedProgram.term)
      def evaluate(caseOnData: Boolean) =
        scala.util.Try(
          machine.cek(CountingBudgetSpender(), Log(), debug = false, caseOnData).evaluateTerm(term)
        )
      assert(evaluate(caseOnData = false).isFailure, s"${c.id}: the ledger machine refuses it")
      assert(evaluate(caseOnData = true).isSuccess, s"${c.id}: the upstream profile runs it")
    }
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
      budget: Option[ExUnits],
      originalFlat: Option[Bytes]
  )

  /** `builtin/semantics`, `builtin/parser`, `term`, ... */
  private def category(id: String): String =
    id.split('/').take(if id.startsWith("builtin/") then 2 else 1).mkString("/")

  extension (e: ExUnits) private def show: String = s"cpu ${e.steps}, mem ${e.mem}"
