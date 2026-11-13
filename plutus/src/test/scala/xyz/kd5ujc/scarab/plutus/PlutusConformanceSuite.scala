// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.{Json, parser}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits
import munit.FunSuite
import scalus.uplc.{Constant, DeBruijnedProgram, Program, ScarabProgramView, Term}
import scalus.uplc.eval.{CountingBudgetSpender, Log, RestrictingBudgetSpender}
import scalus.uplc.DeBruijn

/** The original evaluation cases of a plutus release, under the upstream executable's term profile:
  * its semantics variant, saturating counting and its caser. Terms execute directly so BLS
  * constants retain their meaning and cost rather than being rewritten as Flat `uncompress` calls.
  * The machine is the one [[ScalusEvaluator]] builds for a request ([[ScalusEvaluator.corrected]],
  * [[ScalusEvaluator.CorrectedVm.cek]]). No case is excluded: successful terms and recorded budgets
  * must equal the unchanged upstream expectations.
  */
abstract class PlutusConformanceCorpus(release: PlutusConformanceSuite.Release) extends FunSuite:
  import PlutusConformanceSuite.*

  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  protected val language: PlutusLanguage = release.language
  protected val protocol: Int = release.protocol
  private val costModel = release.costModel

  private val reference = release.view

  /** The machine [[ScalusEvaluator]] builds for the request ([[ScalusEvaluator.corrected]]). */
  protected val machine: ScalusEvaluator.CorrectedVm =
    assertEquals(SemanticsVariant.of(language, protocol), release.variant)
    ScalusEvaluator
      .corrected(language, protocol, costModel, caseOnData = false)
      .fold(failure => fail(failure.message), identity)

  private val Unlimited = ExUnits(BigInt(1) << 60, BigInt(1) << 60)

  private def budgetOf(text: String): Option[ExUnits] =
    val n = raw"cpu:\s*(\d+)\s*\|\s*mem:\s*(\d+)".r.unanchored
    text match
      case n(cpu, mem)                                 => Some(ExUnits(BigInt(mem), BigInt(cpu)))
      case "parse/decode error" | "evaluation failure" => None
      case _                                           => fail(s"budget file: $text")

  protected def cases: Vector[Case] =
    reference
      .under(Conformance)
      .collect { case (name, _) if name.endsWith(".uplc") => name.stripSuffix(".uplc") }
      .map { stem =>
        def file(suffix: String) = s"$Conformance/$stem$suffix"
        Case(
          stem.split('/').dropRight(1).mkString("/"),
          reference.read(file(".uplc")),
          reference.read(file(".uplc.expected")).trim,
          budgetOf(reference.read(file(".budget.expected"))),
          Option.when(reference.contains(file(".flat.cbor.hex"))) {
            Bytes
              .fromHex(reference.read(file(".flat.cbor.hex")).trim)
              .fold(e => fail(e.toString), identity)
          }
        )
      }
      .sortBy(_.id)

  /** Runs one case through the evaluator; `None` when it passes, else why not. */
  protected def check(c: Case): Option[String] = c.originalFlat match
    case Some(script) =>
      // Scalus's text parser normalises malformed Value maps. These public corpus literals
      // must reach the decoder as their original ordered Flat entries, before that parser.
      assertEquals(c.expected, "parse/decode error", c.id)
      Option.when(ScalusEvaluator.pureDecoder.validate(language, protocol, script).isRight)(
        "original malformed Flat constant decoded"
      )
    case None => checkParsed(c)

  protected def checkParsed(c: Case, evaluate: Program => Outcome = run): Option[String] =
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

  protected enum Outcome:
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
    val machineCosts = release.json("cekMachineCostsE.json").hcursor
    def cost(key: String, unit: String) =
      machineCosts.downField(key).downField(unit).as[Long].fold(throw _, identity)
    // every step kind costs the same in variant E; the wrapper uses apply, lam and const
    val kinds = Vector("cekApplyCost", "cekLamCost", "cekConstCost")
    assertEquals(kinds.map(cost(_, "exBudgetCPU")).distinct.size, 1)
    assertEquals(kinds.map(cost(_, "exBudgetMemory")).distinct.size, 1)
    ExUnits(cost(kinds.head, "exBudgetMemory"), cost(kinds.head, "exBudgetCPU"))

  private def minus(a: ExUnits, steps: Int): ExUnits =
    ExUnits(a.mem - step.mem * steps, a.steps - step.steps * steps)

  protected def runLedger(p: Program): Outcome =
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
    * The Data flag is the release executable's caser ([[PlutusConformanceSuite.Release]]).
    */
  protected def run(p: Program): Outcome =
    scala.util.Try(p.deBruijnedProgram.term) match
      case scala.util.Failure(error) => Outcome.Unavailable(error.toString)
      case scala.util.Success(term) if !closed(List(term -> 0)) =>
        Outcome.Undecodable("free variable in upstream term")
      case scala.util.Success(term) => evaluate(p.version, term)

  /** The closed de Bruijn `term` of a program of `version` on the machine, as [[run]] runs it. */
  private def evaluate(version: (Int, Int, Int), term: Term): Outcome =
    val spender = CountingBudgetSpender()
    val log = Log()
    def spent = ExUnits(spender.getSpentBudget.memory, spender.getSpentBudget.steps)
    val cek = machine.cek(spender, log, debug = false, release.caseOnData(version))
    scala.util
      .Try(DeBruijn.fromDeBruijnTerm(cek.evaluateTerm(term)))
      .fold(
        error =>
          if ScalusEvaluator.referenceFailure(error) then Outcome.Failed(spent, error.getMessage)
          else Outcome.Unavailable(error.toString),
        result => Outcome.Succeeded(spent, result)
      )

  /** Every case with a `.flat` file, and the ids of the cases without one. */
  protected def flatCases: (Vector[FlatCase], Vector[String]) =
    def bytes(name: String) =
      Bytes.fromArray(Files.readAllBytes(reference.path(s"$Conformance/$name")))
    val (flat, textOnly) = reference
      .under(Conformance)
      .collect {
        case (name, _) if name.endsWith(".uplc") || name.endsWith(".flat") =>
          name.stripSuffix(".uplc").stripSuffix(".flat")
      }
      .distinct
      .sorted
      .partitionMap { stem =>
        val id = stem.split('/').dropRight(1).mkString("/")
        if reference.contains(s"$Conformance/$stem.flat") then
          Left(
            FlatCase(
              id,
              bytes(s"$stem.flat"),
              bytes(s"$stem.flat.expected"),
              reference.read(s"$Conformance/$stem.budget.expected").trim
            )
          )
        else Right(id)
      }
    (flat, textOnly)

  /** The program the upstream runner reads from Flat bytes (`getInputProg Flat` and
    * `decodeFlatProg`, `PlutusConformance/Common.hs:383-398,562-575`, plutus 1.70.0.0 `507cab8c`,
    * the same at 1.71.0.0): `unflat` as an `UnrestrictedProgram`, whose only check is that `constr`
    * and `case` need version 1.1.0 (`UntypedPlutusCore/Core/Instance/Flat.hs:167-185,288-305`),
    * then `unDeBruijnProgram` over the whole term (`Common.hs:550-554`). The Scalus backend's
    * decoder is [[FlatScan.decode]]; it builds a malformed `value` constant normalised and records
    * it, where the reference's decoder refuses it (`PlutusCore/Value.hs` 637-694), so a recorded
    * one is a refusal here. [[closed]] is the closedness `unDeBruijnProgram` checks, `constr`
    * fields and `case` included, and an index too wide for Scalus is open (`FlatScan`).
    */
  protected def decodeFlat(bytes: Bytes): Either[FlatRefusal, DeBruijnedProgram] =
    if bytes.isEmpty then Left(FlatRefusal.Empty)
    else
      FlatScan
        .decode(bytes.toArray)
        .left
        .map(FlatRefusal.Undecodable(_))
        .flatMap(d => d.scan.malformedValue.map(FlatRefusal.MalformedValue(_)).toLeft(d.program))
        .filterOrElse(p => closed(List(p.term -> 0)), FlatRefusal.FreeVariable)

  /** A `.flat.expected` file as `decodeFlatExpected` reads it (`Common.hs:347-354`): a failure's
    * text, compared byte for byte first, or the Flat-encoded result program.
    */
  private def flatGolden(bytes: Bytes): Either[FlatGolden, DeBruijnedProgram] =
    Vector(ParseError, EvaluationFailure)
      .find(text => bytes == Bytes.fromArray(text.getBytes(StandardCharsets.UTF_8)))
      .map(FlatGolden.Failure(_))
      .toLeft(())
      .flatMap(_ => decodeFlat(bytes).left.map(FlatGolden.Unreadable(_)))

  /** Runs a case from its `.flat` file as the upstream runner does with `--format flat`: a refused
    * program is `parse/decode error`, a failed evaluation `evaluation failure` (`getTestedValue`,
    * `Common.hs:403-417`); the result must equal the decoded `.flat.expected` program, version and
    * de Bruijn term (`compareAlphaEq`, `Common.hs:423-473`), and the budget `.budget.expected`
    * (`testForBudget`, `Common.hs:257-267`). The mismatches, none when it passes.
    */
  protected def checkFlat(c: FlatCase): List[String] =
    val tested: Either[(String, String), (DeBruijnedProgram, ExUnits, Term)] =
      decodeFlat(c.program) match
        case Left(refusal) => Left(ParseError -> refusal.toString)
        case Right(p) =>
          evaluate(p.version, p.term) match
            case Outcome.Unavailable(reason)    => Left("implementation unavailable" -> reason)
            case Outcome.Undecodable(reason)    => Left(ParseError -> reason)
            case Outcome.Failed(_, reason)      => Left(EvaluationFailure -> reason)
            case Outcome.Succeeded(spent, term) => Right((p, spent, term))
    val result = (flatGolden(c.expected), tested) match
      case (Left(FlatGolden.Failure(text)), Left((got, why))) =>
        Option.when(got != text)(s"expected $text, got $got: $why")
      case (Left(FlatGolden.Failure(text)), Right((_, spent, term))) =>
        Some(s"expected $text, evaluated to ${term.show} (${spent.show})")
      case (Left(FlatGolden.Unreadable(refusal)), _) =>
        Some(s"the expected result does not decode: $refusal")
      case (Right(_), Left((got, why))) => Some(s"expected a result, got $got: $why")
      case (Right(expected), Right((p, _, term))) =>
        Option.when(expected.version != p.version || !sameTerm(expected.term, term))(
          s"result: expected ${expected.version} ${expected.term.show}, got ${p.version} ${term.show}"
        )
    val budget = tested match
      case Left((got, _)) => Option.when(c.budget != got)(s"budget: expected ${c.budget}, got $got")
      case Right((_, spent, _)) =>
        budgetOf(c.budget) match
          case Some(b) => Option.when(spent != b)(s"budget: expected ${b.show}, got ${spent.show}")
          case None    => Some(s"budget: expected ${c.budget}, evaluated (${spent.show})")
    result.toList ++ budget.toList

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
      s"plutus conformance ${release.tag}, variant ${release.variant}, ${all.size} cases in " +
        s"${"%.1f".format(seconds)} s\n" +
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

  test("upstream Plutus conformance, Flat cases as the runner reads them with --format flat") {
    val started = System.nanoTime
    val (flat, textOnly) = flatCases
    val results = flat.map(c => c -> checkFlat(c))
    val seconds = (System.nanoTime - started) / 1e9
    val failures = results.collect { case (c, why) if why.nonEmpty => c.id -> why.mkString("; ") }
    println(
      s"plutus conformance ${release.tag} on the .flat files, ${flat.size} cases in " +
        s"${"%.1f".format(seconds)} s: ${flat.size - failures.size} passed, ${failures.size} " +
        s"failed; ${textOnly.size} cases have no Flat form"
    )
    assert(
      failures.isEmpty,
      s"${failures.size} failures:\n" + failures.map((id, why) => s"$id: $why").mkString("\n")
    )
    assertEquals(
      textOnly.filterNot(id => NoFlatFiles.exists(d => id == d || id.startsWith(s"$d/"))),
      Vector.empty,
      "a case outside the text parser's directories has no Flat form"
    )
    assertEquals(flat.size, 906, "every original case with a Flat form must execute")
    assertEquals(textOnly.size, 103)
  }

  test("a variable no lam binds anywhere in a Flat program is a refusal, as upstream decodes it") {
    def v(index: Int) = Term.Var(scalus.uplc.NamedDeBruijn(s"i$index", index))
    def lam(body: Term) = Term.LamAbs("x", body)
    def constr(fields: Term*) = Term.Constr(scalus.cardano.ledger.Word64(0L), fields.toList)
    def decoded(term: Term) =
      decodeFlat(Bytes.fromArray(ScarabProgramView.program((1, 1, 0), term).flatEncoded))
    // the ledger's scope check, `FlatScan.openVariable`, skips `constr` and `case`
    assertEquals(decoded(lam(constr(v(1), v(2)))).left.toOption, Some(FlatRefusal.FreeVariable))
    assertEquals(
      decoded(lam(Term.Case(constr(), List(lam(v(3)))))).left.toOption,
      Some(FlatRefusal.FreeVariable)
    )
    assertEquals(decoded(lam(v(0))).left.toOption, Some(FlatRefusal.FreeVariable))
    assert(decoded(lam(lam(constr(v(1), v(2))))).isRight)
    assertEquals(decodeFlat(Bytes.empty).left.toOption, Some(FlatRefusal.Empty))
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

/** All original Plutus 1.70.0.0 evaluation cases, under the upstream executable's term profile:
  * variant E, saturating counting and `caseBuiltin` including Data. Two settings follow the
  * upstream executable (`CekEvaluator.hs` lines 46-61 at plutus 507cab8c) and differ from the
  * ledger's: the budget is the counting one, and `case` on `Data` is allowed (`caseBuiltin`, where
  * the ledger's `caseBuiltinNoData` applies from protocol 11 up to Dijkstra, `Common/Eval.hs` lines
  * 142-146).
  *
  * Original malformed-Value Flat sidecars retain decoder coverage where the dependency's text
  * parser normalises their order. Ledger admission and restricting budgets are separate contracts,
  * tested by [[PlutusReachabilitySuite]] and the focused profile regressions below.
  */
class PlutusConformanceSuite extends PlutusConformanceCorpus(PlutusConformanceSuite.Release.V170):
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
  /** The conformance cases' directory in a [[PlutusReference.View]]. */
  val Conformance = "conformance"

  /** A plutus release's reference data and the profile of its conformance executable.
    *
    * @param caseOnData
    *   the executable's caser for a program of this version: `caseBuiltin` at 1.70.0.0
    *   (`CekEvaluator.hs:46-61`, 507cab8c), `defaultCaserBuiltinFor newestPV` at 1.71.0.0
    *   (`CekEvaluator.hs:47-61`, 261766ad), which is the ledger's rule at protocol 12
    */
  final case class Release(
      reference: PlutusRelease,
      language: PlutusLanguage,
      protocol: Int,
      variant: SemanticsVariant,
      caseOnData: ((Int, Int, Int)) => Boolean
  ):
    def tag: String = reference.tag

    def view: PlutusReference.View = PlutusReference.view(reference)

    /** A file of the release's `cost-model` or `ledger-api` data, by its name. */
    def resource(name: String): String =
      view.read(
        Vector(s"cost-model/$name", s"ledger-api/$name").find(view.contains).getOrElse(name)
      )

    def json(name: String): Json = parser.parse(resource(name)).fold(throw _, identity)

    /** The variant E on-chain parameters of PlutusV3, valued from the plutus JSON files. */
    lazy val costModel: Vector[Long] = costModelOf(this)

  object Release:
    val V170: Release = Release(
      PlutusRelease.Plutus1_70,
      PlutusLanguage.V3,
      11,
      SemanticsVariant.E,
      _ => true
    )

    /** The executable runs at `newestPV`, 12, in the default variant, `maxBound`: G. The cases run
      * as PlutusV4, which the ledger evaluates in PlutusV3's evaluation context.
      */
    val V171: Release = Release(
      PlutusRelease.Plutus1_71,
      PlutusLanguage.V4,
      12,
      SemanticsVariant.G,
      PlutusAvailability.caseOnData(12, _)
    )

  /** The reference data of the 1.70.0.0 release, whose cases [[PlutusConformanceSuite]] runs. */
  def reference: PlutusReference.View = Release.V170.view

  def resource(name: String): String = Release.V170.resource(name)

  def json(name: String): Json = Release.V170.json(name)

  lazy val costModel: Vector[Long] = Release.V170.costModel

  private def costModelOf(release: Release): Vector[Long] =
    val builtins = release.json("builtinCostModelE.json")
    val machine = release.json("cekMachineCostsE.json")
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
    release
      .resource(s"V${PlutusLanguage.V3.id + 1}-param-names.txt")
      .linesIterator
      .filter(_.nonEmpty)
      .map(valueOf)
      .takeWhile(_.isDefined)
      .map(_.get)
      .toVector

  /** The directories whose cases have no Flat form (`dirsWithNoFlatFiles`,
    * `PlutusConformance/Common.hs:149-153`, `507cab8c`, the same at 1.71.0.0): the text parser's
    * cases. Run on the `.flat` files, the runner skips a case below them that has none, and fails
    * on one anywhere else (`Common.hs:212-219`).
    */
  val NoFlatFiles: Vector[String] = Vector("builtin/parser", "term/parser")

  private[plutus] val ParseError = "parse/decode error"
  private[plutus] val EvaluationFailure = "evaluation failure"

  /** A case in its Flat form: the bytes of its `.flat` and `.flat.expected` files and the text of
    * its `.budget.expected` file, which the two forms share.
    */
  final case class FlatCase(id: String, program: Bytes, expected: Bytes, budget: String)

  /** Why the upstream runner reads no program from Flat bytes; it shows each as
    * `parse/decode error`.
    */
  enum FlatRefusal:
    case Empty
    case Undecodable(failure: FlatScan.Failure)
    case MalformedValue(fault: FlatScan.ValueFault)
    case FreeVariable

  /** What a `.flat.expected` file holds when it holds no result program. */
  enum FlatGolden:
    case Failure(text: String)
    case Unreadable(refusal: FlatRefusal)

  final case class Case(
      id: String,
      program: String,
      expected: String,
      budget: Option[ExUnits],
      originalFlat: Option[Bytes]
  )

  /** `builtin/semantics`, `builtin/parser`, `term`, ... */
  private[plutus] def category(id: String): String =
    id.split('/').take(if id.startsWith("builtin/") then 2 else 1).mkString("/")

  extension (e: ExUnits) private[plutus] def show: String = s"cpu ${e.steps}, mem ${e.mem}"
