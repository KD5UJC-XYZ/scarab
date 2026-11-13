// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.text

import xyz.kd5ujc.scarab.uplc.{Program, ReferenceCorpus}
import xyz.kd5ujc.scarab.uplc.ReferenceCorpus.{ConformanceCase, Expected}

/** The textual programs of the conformance corpus of plutus 1.70.0.0 against the reference's
  * parser, as its recorded expectations show it: a program whose expected output is
  * `parse/decode error` is refused (by the parser, or as an open term), every other program is read
  * and closed, every expected result is read and closed, and printing a program and reading it back
  * gives the same program.
  */
final class CorpusTextSuite extends munit.FunSuite:

  private lazy val cases: Vector[ConformanceCase] =
    ReferenceCorpus.conformance(ReferenceCorpus.Pinned).fold(p => fail(p.toString), identity)

  test("the 1.70.0.0 view has 1,009 cases: 723 results with budgets, 222 failures, 64 refusals") {
    val all = cases
    assertEquals(all.size, 1009)
    val counts = all.groupMapReduce {
      _.expected match
        case _: Expected.Evaluates      => "evaluates"
        case Expected.EvaluationFailure => "evaluation failure"
        case Expected.ParseError        => "parse/decode error"
    }(_ => 1)(_ + _)
    assertEquals(
      counts,
      Map("evaluates" -> 723, "evaluation failure" -> 222, "parse/decode error" -> 64)
    )
  }

  test("every program the reference reads is read and closed; every refused one is refused") {
    val verdicts = cases.map(c => c -> UplcParser.program(c.program))
    val wrong = verdicts.collect {
      case (c, Left(e)) if c.expected != Expected.ParseError => s"${c.name}: refused, $e"
      case (c, Right(_)) if c.expected == Expected.ParseError =>
        s"${c.name}: read, but the reference refuses it"
    }
    val refusals = verdicts.collect { case (c, Left(e)) => c.name -> e }
    val open = refusals.collect { case (n, ProgramError.Open(f)) => s"$n: free ${f.name}" }
    val unparsable = refusals.collect { case (n, ProgramError.Unparsable(e)) => s"$n: $e" }
    println(
      s"conformance programs: ${verdicts.size - refusals.size} read, ${unparsable.size} unparsable, " +
        s"${open.size} open\n" + (unparsable ++ open).mkString("  ", "\n  ", "")
    )
    assert(wrong.isEmpty, wrong.mkString("\n"))
  }

  test("every expected result is read and closed") {
    val wrong = cases
      .collect { case ConformanceCase(name, _, Expected.Evaluates(result, _)) =>
        UplcParser.program(result).left.map(e => s"$name: $e")
      }
      .collect { case Left(why) => why }
    assert(wrong.isEmpty, wrong.mkString("\n"))
  }

  private def roundTrip(text: String): Option[String] =
    UplcParser.program(text).toOption.flatMap { (p: Program) =>
      val printed = UplcPrinter.program(p)
      UplcParser.program(printed) match
        case Right(q) if q == p => None
        case other              => Some(s"printed as $printed, read back as $other")
    }

  test("printing a program and reading it back gives the same program, over the corpus") {
    val texts = cases.flatMap { c =>
      c.expected match
        case Expected.Evaluates(result, _) =>
          Vector(c.name -> c.program, s"${c.name} (result)" -> result)
        case _ => Vector(c.name -> c.program)
    }
    val wrong = texts.flatMap((name, text) => roundTrip(text).map(why => s"$name: $why"))
    assert(wrong.isEmpty, wrong.mkString("\n"))
  }
