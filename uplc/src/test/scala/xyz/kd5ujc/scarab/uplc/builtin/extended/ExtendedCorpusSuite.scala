// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.uplc.ReferenceCorpus
import xyz.kd5ujc.scarab.uplc.builtin.{BuiltinFailure, EvaluationFailure}
import xyz.kd5ujc.scarab.uplc.builtin.core.CoreVariant

/** The conversion corpus cases (`plutus-reference/conformance/builtin/semantics`, plutus 1.70.0.0,
  * results produced by the Haskell evaluator) of the builtins whose denotations are here, and the
  * BLS12-381 cases ported from cardano-crypto-class (`bls12_381-cardano-crypto-tests`). Each
  * program is a tree of builtin applications; [[BuiltinTree]] evaluates it under variant E, the
  * corpus's (`def = maxBound`, `Default/Builtins.hs:2546-2547`), and its result or failure must be
  * the expected one. Budgets are not compared here (the costing is another work package's).
  *
  * A case whose program needs the machine (a lambda, `delay`, ...) is not run; the counts of run
  * and not run cases are pinned so that a change in either is seen.
  */
final class ExtendedCorpusSuite extends munit.FunSuite:
  private val semantics = "conformance/builtin/semantics/"
  private val tree = BuiltinTree(CoreVariant.E)

  private val directories: Set[String] =
    ExtendedDenotations.functions.map(_.name) + "bls12_381-cardano-crypto-tests"

  private def read(file: Path): String =
    new String(Files.readAllBytes(file), StandardCharsets.UTF_8)

  /** The `.uplc` files below `semantics`, by directory (the builtin's name). */
  private val cases: Map[String, Vector[(String, Path, Path)]] =
    val view = ReferenceCorpus.view("1.70.0.0")
    view.toVector
      .collect {
        case (name, file) if name.startsWith(semantics) && name.endsWith(".uplc") =>
          (name.drop(semantics.length).takeWhile(_ != '/'), name, file)
      }
      .filter((dir, _, _) => directories.contains(dir))
      .flatMap((dir, name, file) => view.get(s"$name.expected").map(e => (dir, name, file, e)))
      .groupMap(_._1)((_, name, file, expected) => (name, file, expected))
      .view
      .mapValues(_.sortBy(_._1))
      .toMap

  /** How a case agrees: by its value, by a parse error, or by a failure of the given kind. */
  private enum Agreement:
    case Result, ParseError
    case Failure(kind: String)

  private enum Verdict:
    case Agree(how: Agreement)
    case Disagree(detail: String)
    case NotRun(reason: String)

  /** The kind of a failure, for the counts: the class of an unlifting failure, the case of an
    * evaluation failure.
    */
  private def kind(stop: TreeFailure): String = stop match
    case TreeFailure.Builtin(f, BuiltinFailure.Unlifting(u)) =>
      s"unlifting ${u.errorClass} ${f.name}"
    case TreeFailure.Builtin(_, BuiltinFailure.Evaluation(e)) =>
      e match
        case EvaluationFailure.Defect(f, d) => s"DEFECT $f $d"
        case other                          => other.productPrefix
    case other => other.toString

  private def verdict(program: Path, expectation: Path): Verdict =
    CorpusProgram.expected(read(expectation)) match
      case Left(e) => Verdict.Disagree(s"unreadable expectation: $e")
      case Right(expected) =>
        (CorpusProgram.program(read(program)), expected) match
          case (Left(_), Expected.ParseError) => Verdict.Agree(Agreement.ParseError)
          case (Left(e), _)                   => Verdict.Disagree(s"parse: $e, expected $expected")
          case (Right(term), _) =>
            (tree.eval(term), expected) match
              case (Left(TreeStop.NeedsMachine(form)), _) => Verdict.NotRun(form)
              case (Left(TreeStop.Failed(f)), Expected.EvaluationFailure) =>
                Verdict.Agree(Agreement.Failure(kind(f)))
              case (Right(TreeValue.Con(c)), Expected.Result(e)) if c == e =>
                Verdict.Agree(Agreement.Result)
              case (Right(v @ (TreeValue.Partial(_, _, _) | TreeValue.Opaque(_))), _) =>
                Verdict.NotRun(s"result $v is not a constant")
              case (got, _) => Verdict.Disagree(s"got $got, expected $expected")

  /** The builtins with no directory of their own: `mulMlResult` and `finalVerify`, whose cases are
    * those of `millerLoop` and the cardano-crypto-class pairing tests, and batch 7, which the
    * corpus of 1.70.0.0 has no case of.
    */
  private def exercisedElsewhere(dir: String): Boolean = Set(
    "bls12_381_mulMlResult",
    "bls12_381_finalVerify",
    "multiIndexArray",
    "policies",
    "assetCount",
    "keepPolicies",
    "dropPolicies"
  ).contains(dir)

  /** The cases that need the machine, by name; nothing else may be left out. */
  private val needsMachine: Set[String] = Set(
    "conformance/builtin/semantics/complementByteString/case-05/case-05.uplc"
  )

  private val outcomes: Map[String, Vector[(String, Verdict)]] =
    cases.view.mapValues(_.map((name, file, expected) => name -> verdict(file, expected))).toMap

  for dir <- directories.toVector.sorted do
    test(s"$dir: every corpus case agrees") {
      val results = outcomes.getOrElse(dir, Vector.empty)
      assert(results.nonEmpty || exercisedElsewhere(dir), s"no corpus case for $dir")
      val disagreements = results.collect { case (n, Verdict.Disagree(d)) => s"$n: $d" }
      assert(disagreements.isEmpty, disagreements.mkString("\n"))
      val notRun = results.collect { case (n, Verdict.NotRun(r)) => n -> r }
      assertEquals(
        notRun.map(_._1).toSet,
        needsMachine.filter(_.contains(s"/$dir/")),
        notRun.mkString("\n")
      )
    }

  test("the cases agree for the pinned reasons, and no failure is a defect") {
    val all = outcomes.values.flatten.toVector
    val counts = all
      .groupMapReduce {
        case (_, Verdict.Agree(Agreement.Failure(k))) => s"failure: $k"
        case (_, Verdict.Agree(how))                  => how.toString
        case (_, other)                               => other.productPrefix
      }(_ => 1)(_ + _)
    assert(!counts.keySet.exists(_.contains("DEFECT")), counts.toString)
    assertEquals(counts, ExtendedCorpusSuite.Counts)
  }

object ExtendedCorpusSuite:
  /** The cases below the builtins' directories at plutus 1.70.0.0, by how they agree. */
  val Counts: Map[String, Int] = Map(
    "Result" -> 476,
    "NotRun" -> 1,
    "failure: Bitwise" -> 42,
    "failure: ValueOperation" -> 35,
    "failure: Bls" -> 28,
    "failure: ExpMod" -> 22,
    "failure: SignatureInput" -> 20,
    "failure: ArrayIndexOutOfBounds" -> 2,
    // An `Int` (`readBit`, `shiftByteString` and `rotateByteString` in E) or a `Word8`
    // (`replicateByte`) out of its range: the unlifting's failure, not the denotation's.
    "failure: unlifting Operational readBit" -> 2,
    "failure: unlifting Operational shiftByteString" -> 2,
    "failure: unlifting Operational rotateByteString" -> 2,
    "failure: unlifting Operational replicateByte" -> 4
  )
