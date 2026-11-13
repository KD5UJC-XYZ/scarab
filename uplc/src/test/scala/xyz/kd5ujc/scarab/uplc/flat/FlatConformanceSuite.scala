// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.flat

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{DeBruijn, Program, ProgramVersion, ReferenceCorpus, Term, Word64}
import xyz.kd5ujc.scarab.uplc.ReferenceCorpus.{ConformanceCase, Expected, FlatCase, FlatExpected}
import xyz.kd5ujc.scarab.uplc.cek.Terms.*
import xyz.kd5ujc.scarab.uplc.cost.CekConformance
import xyz.kd5ujc.scarab.uplc.text.UplcParser

/** The conformance cases of plutus 1.70.0.0 run on their `.flat` files, as the reference's runner
  * runs them with `--format flat` ([[FlatConformance]]), through this module's Flat decoder and the
  * evaluator of [[xyz.kd5ujc.scarab.uplc.text.ConformanceSuite]] in the runner's profile
  * ([[CekConformance]]). No case is excluded: every result equals the decoded `.flat.expected`
  * program and every budget `.budget.expected`.
  *
  * The 103 cases with no Flat form are the text parser's (`dirsWithNoFlatFiles`,
  * `PlutusConformance/Common.hs:149-153`, `507cab8c`), which the runner skips in this format and
  * the text gate runs.
  */
final class FlatConformanceSuite extends munit.FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private lazy val corpus =
    ReferenceCorpus.flatConformance(ReferenceCorpus.Pinned).fold(p => fail(p.toString), identity)

  test(
    "the 906 cases with a Flat form, through the Flat decoder and the machine: results, budgets"
  ) {
    assertEquals(corpus.cases.size, 906)
    assertEquals(corpus.textOnly.size, 103)
    val report = FlatConformance.run(corpus.cases, CekConformance.backend.evaluate)
    println(
      s"plutus 1.70.0.0 conformance on the .flat files: ${report.conforming} of " +
        s"${corpus.cases.size} cases conform"
    )
    assert(report.mismatches.isEmpty, report.render(CekConformance.backend.show))
  }

  /** `conformance-consistency` (`consistency/Spec.hs:48-154`, `507cab8c`): each `.flat` program
    * decodes to the program its `.uplc` file parses to, both or neither, and each `.flat.expected`
    * result to the `.uplc.expected` one, or both record the same failure. Here the text is read by
    * [[UplcParser]], which closes a program as it reads it, so the case of a free variable that
    * upstream sets aside (`Spec.hs:35-41`, `term/var`) is compared too.
    */
  private def inconsistency(text: ConformanceCase, flat: FlatCase): Option[String] =
    val input = (UplcParser.program(text.program), FlatConformance.program(flat.program)) match
      case (Left(_), Left(_))              => None
      case (Right(parsed), Right(decoded)) => Option.when(parsed != decoded)("the programs differ")
      case (Left(e), Right(_))             => Some(s"only the Flat program reads (the text: $e)")
      case (Right(_), Left(r))             => Some(s"only the text program reads (the Flat: $r)")
    val result = (text.expected, flat.expected) match
      case (Expected.ParseError, FlatExpected.ParseError)               => None
      case (Expected.EvaluationFailure, FlatExpected.EvaluationFailure) => None
      case (Expected.Evaluates(t, b), FlatExpected.Evaluates(f, c)) =>
        (UplcParser.program(t), FlatConformance.program(f)) match
          case (Right(parsed), Right(decoded)) =>
            Option
              .when(parsed != decoded)("the results differ")
              .orElse(Option.when(b != c)("the budgets differ"))
          case (p, d) => Some(s"a result does not read: $p, $d")
      case (t, f) => Some(s"the expectations differ: $t, $f")
    Option(input.toList ++ result.toList).filter(_.nonEmpty).map(_.mkString("; "))

  test("each Flat program and result is the one its text form reads as") {
    val texts = ReferenceCorpus
      .conformance(ReferenceCorpus.Pinned)
      .fold(p => fail(p.toString), _.map(c => c.name -> c).toMap)
    val found = corpus.cases.flatMap { flat =>
      texts
        .get(flat.name)
        .fold(Some(s"${flat.name}: no text form"))(
          inconsistency(_, flat).map(s"${flat.name}: " + _)
        )
    }
    assertEquals(found, Vector.empty)
  }

  test("a variable no lam binds is refused wherever it is, constr fields and case included") {
    def refusal(term: Term) =
      FlatConformance.program(FlatWriter.flat(Program(ProgramVersion.V1_1_0, term))).left.toOption
    def free(index: Long) = Some(FlatRefusal.FreeVariable(DeBruijn(Word64.fromBits(index))))
    assertEquals(refusal(lam(v(2))), free(2))
    // the ledger's scope check (`Scope.check`) passes these three: it skips `constr` and `case`
    assertEquals(refusal(lam(constr(0, v(1), v(2)))), free(2))
    assertEquals(refusal(lam(caseOf(v(2), unit))), free(2))
    assertEquals(refusal(lam(caseOf(constr(0), v(1), lam(v(3))))), free(3))
    assertEquals(refusal(lam(v(0))), free(0), "index 0 names no binder")
    assertEquals(refusal(lam(lam(constr(0, v(1), v(2))))), None)
    assertEquals(FlatConformance.program(Bytes.empty), Left(FlatRefusal.Empty))
  }
