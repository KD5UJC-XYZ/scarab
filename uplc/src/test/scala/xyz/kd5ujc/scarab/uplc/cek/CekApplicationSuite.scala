// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultFun,
  DefaultUni,
  PlutusData,
  SemanticsVariant,
  Signature,
  Term
}
import xyz.kd5ujc.scarab.uplc.cost.{CekConformance, CostStream, DefaultCostModels, ExBudget}
import xyz.kd5ujc.scarab.uplc.flat.FlatGen
import xyz.kd5ujc.scarab.uplc.cek.Terms.*

/** The machine against [[PresentCekMachine]], the machine before a saturated builtin application
  * read its arguments as the machine holds them (#1411): over generated builtin applications and
  * terms, with every builtin and the default cost model of several variants, the stub builtins and
  * a stream cost, counting and restricting, each report must agree in its result, its logs, the
  * budget it spent and its failure. The applications include arguments that do not unlift (not a
  * constant, a constant of another type, a constant outside a variant's bound), denotations that
  * fail, and limits that run out at any point, inside a builtin's cost stream too.
  */
final class CekApplicationSuite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(1000)

  private val variants: Vector[SemanticsVariant] =
    Vector(SemanticsVariant.B, SemanticsVariant.C, SemanticsVariant.E, SemanticsVariant.G)

  private lazy val chain: Map[SemanticsVariant, MachineParameters] =
    variants
      .map(v => v -> CekConformance.machine(DefaultCostModels.costs(v), CaserBuiltin.WithData))
      .toMap

  /** The stub builtins, whose components give only their vectors of arguments, with `trace` costing
    * a stream of three elements.
    */
  private val stubs: MachineParameters =
    StubBuiltins
      .parameters(StubBuiltins.distinctCosts)
      .copy(builtins =
        StubBuiltins.withCost(
          DefaultFun.Trace,
          () => CostStream.of(ExBudget.of(1000, 10), ExBudget.of(5000, 10), ExBudget.of(700, 300))
        )
      )

  private val coreStubs: MachineParameters =
    MachineParameters(
      StubBuiltins.distinctCosts,
      StubBuiltins.core(SemanticsVariant.E),
      CaserBuiltin.WithoutData
    )

  /** A term that computes a constant of `uni`; the BLS types come from their builtins. */
  private def constantTerm(uni: DefaultUni): Gen[Term] = uni match
    case DefaultUni.Bls12_381_G1_Element => g1
    case DefaultUni.Bls12_381_G2_Element => g2
    case DefaultUni.Bls12_381_MlResult =>
      Gen.zip(g1, g2).map((a, b) => app(builtin(DefaultFun.Bls12_381_millerLoop), a, b))
    // a list of points is a constant no term makes; the empty one is the constant there is
    case DefaultUni.List(e) if bls(e) => Gen.const(con(Constant.List.empty(e)))
    case other                        => FlatGen.constantOf(other).map(con)

  private def bls(uni: DefaultUni): Boolean = uni match
    case DefaultUni.Bls12_381_G1_Element | DefaultUni.Bls12_381_G2_Element |
        DefaultUni.Bls12_381_MlResult =>
      true
    case _ => false

  private val dst: Gen[Term] = FlatGen.constantOf(DefaultUni.ByteString).map(con)
  private val g1: Gen[Term] =
    Gen.zip(dst, dst).map((m, d) => app(builtin(DefaultFun.Bls12_381_G1_hashToGroup), m, d))
  private val g2: Gen[Term] =
    Gen.zip(dst, dst).map((m, d) => app(builtin(DefaultFun.Bls12_381_G2_hashToGroup), m, d))

  /** Constants of the shapes the polymorphic builtins take apart. */
  private val shaped: Gen[Term] =
    Gen
      .oneOf(
        DefaultUni.List(DefaultUni.Integer),
        DefaultUni.List(DefaultUni.Data),
        DefaultUni.List(DefaultUni.Pair(DefaultUni.Data, DefaultUni.Data)),
        DefaultUni.Pair(DefaultUni.Integer, DefaultUni.ByteString),
        DefaultUni.Pair(DefaultUni.Data, DefaultUni.Data),
        DefaultUni.Array(DefaultUni.Integer),
        DefaultUni.Integer,
        DefaultUni.Data
      )
      .flatMap(constantTerm)

  private val notAConstant: Gen[Term] =
    Gen.oneOf(lam(v(1)), delay(int(1)), builtin(DefaultFun.AddInteger), constr(0, int(1)))

  /** An argument for `parameter`: mostly one that unlifts, else one that does not. */
  private def argument(parameter: Signature.Parameter, depth: Int): Gen[Term] = parameter match
    case Signature.Parameter.Opaque(_) =>
      Gen.frequency(
        4 -> FlatGen.constants.map(con),
        2 -> notAConstant,
        (if depth > 0 then 2 else 0) -> application(depth - 1)
      )
    case Signature.Parameter.Constant(t) =>
      val fitting = t.ground.fold(shaped)(constantTerm)
      Gen.frequency(
        12 -> fitting,
        (if depth > 0 then 2 else 0) -> application(depth - 1),
        1 -> FlatGen.constants.map(con),
        1 -> notAConstant
      )

  /** A builtin forced and applied: mostly as its signature says, sometimes with a `force` too many
    * or too few, or an argument short.
    */
  private def application(depth: Int): Gen[Term] =
    for
      function <- Gen.oneOf(DefaultFun.values.toSeq)
      signature = function.signature
      forces <- Gen.frequency(
        10 -> Gen.const(signature.forces),
        1 -> Gen.const(signature.forces + 1),
        1 -> Gen.const((signature.forces - 1).max(0))
      )
      arguments <- Gen.sequence[List[Term], Term](
        signature.parameters.map(argument(_, depth))
      )
      count <- Gen.frequency(
        10 -> Gen.const(arguments.size),
        1 -> Gen.choose(0, arguments.size)
      )
    yield app(nest(forces, builtin(function))(force), arguments.take(count)*)

  private val programs: Gen[Term] =
    Gen.oneOf(
      Gen.choose(0, 40).map(n => countdown(n)),
      Gen.choose(0, 25).map(n => constrSum(n)),
      Gen.choose(0, 120).map(n => forceDelay(n, int(n)))
    )

  /** Data whose measure is a stream, compared or serialised. */
  private val streamed: Gen[Term] =
    for
      d <- FlatGen.constantOf(DefaultUni.Data).map(con)
      e <- FlatGen.constantOf(DefaultUni.Data).map(con)
      list <- FlatGen.constantOf(DefaultUni.List(DefaultUni.Integer)).map(con)
      term <- Gen.oneOf(
        app(builtin(DefaultFun.EqualsData), d, e),
        app(builtin(DefaultFun.SerialiseData), d),
        app(force(builtin(DefaultFun.ListToArray)), list),
        app(builtin(DefaultFun.EqualsData), d, d)
      )
    yield term

  /** The budget mode: counting, or a limit of a fraction of what the present machine spends
    * counting, so that it may run out at any step or cost element.
    */
  private def mode(parameters: MachineParameters, term: Term): Gen[BudgetMode] =
    val spent = PresentCekMachine.run(parameters, BudgetMode.Counting, term).budget.spent
    def part(units: Long): Gen[Long] =
      Gen.frequency(
        3 -> Gen.choose(0L, units.max(0L).min(Long.MaxValue - 1L) + 1L),
        1 -> Gen.const(units),
        1 -> Gen.const(units.min(Long.MaxValue - 1L) + 1L),
        1 -> Gen.const(Long.MaxValue / 4)
      )
    Gen.frequency(
      1 -> Gen.const(BudgetMode.Counting),
      4 -> Gen
        .zip(part(spent.cpu.toLong), part(spent.memory.toLong))
        .map((cpu, memory) => BudgetMode.Restricting(ExBudget.of(cpu, memory)))
    )

  private def resultOf(report: CekReport): Either[CekError, Term] = report.result match
    case CekResult.Failure(failure) => Left(failure.error)
    case success: CekResult.Success => Right(success.term)

  private def causeOf(report: CekReport): Option[Term] = report.result match
    case CekResult.Failure(failure) => failure.cause.map(_.term)
    case _: CekResult.Success       => None

  /** The two machines on `term`, expectation by expectation. */
  private def agree(parameters: MachineParameters, mode: BudgetMode, term: Term): Prop =
    val now = CekMachine.run(parameters, mode, term)
    val before = PresentCekMachine.run(parameters, mode, term)
    (Prop(resultOf(now) == resultOf(before)) :|
      s"result: ${resultOf(now)} where the present machine gives ${resultOf(before)}") &&
    (Prop(now.logs == before.logs) :|
      s"logs: ${now.logs} where the present machine gives ${before.logs}") &&
    (Prop(now.budget == before.budget) :|
      s"budget: ${now.budget} where the present machine gives ${before.budget}") &&
    (Prop(causeOf(now) == causeOf(before)) :|
      s"failure's cause: ${causeOf(now)} where the present machine gives ${causeOf(before)}")

  private def checked(parameters: Gen[MachineParameters], terms: Gen[Term]): Prop =
    val cases =
      for
        p <- parameters
        term <- terms
        m <- mode(p, term)
      yield (p, term, m)
    forAll(cases) { case (p, term, m) => agree(p, m, term) :| s"term: $term, mode: $m" }

  private val anyChain: Gen[MachineParameters] = Gen.oneOf(variants).map(chain)

  property("a builtin application, with every builtin and the variants' cost models") {
    checked(anyChain, application(2))
  }

  property("a builtin's cost stream, spent element by element, with the cost models") {
    checked(anyChain, streamed)
  }

  property("the stub builtins: components that give only vectors, and a cost stream") {
    checked(
      Gen.oneOf(stubs, coreStubs),
      Gen.oneOf(
        application(1),
        Gen.oneOf(DefaultFun.Trace, DefaultFun.DivideInteger).flatMap {
          case DefaultFun.Trace =>
            FlatGen
              .constantOf(DefaultUni.String)
              .map(s => app(force(builtin(DefaultFun.Trace)), con(s), int(1)))
          case f =>
            Gen
              .zip(FlatGen.constantOf(DefaultUni.Integer), Gen.choose(-1, 1))
              .map((a, b) => app(builtin(f), con(a), int(b)))
        }
      )
    )
  }

  property("generated terms") {
    checked(anyChain, FlatGen.terms(4))
  }

  property("programs that loop, past the slippage") {
    checked(Gen.oneOf(anyChain, Gen.const(stubs)), programs)
  }

  test("the generators reach every class of failure") {
    val parameters = chain(SemanticsVariant.E)
    def errorOf(term: Term): CekError =
      CekMachine.run(parameters, BudgetMode.Counting, term).result match
        case CekResult.Failure(failure) => failure.error
        case other                      => fail(s"expected a failure, got $other")
    assert(
      errorOf(app(builtin(DefaultFun.AddInteger), lam(v(1)), int(1))) match
        case CekError.Builtin(_, _) => true
        case _                      => false
    )
    val bytes = con(Constant.ByteString(xyz.kd5ujc.scarab.codec.Bytes.wrap(Array[Byte](1))))
    val outside = app(builtin(DefaultFun.IndexByteString), bytes, int(BigInt(1) << 70))
    assertEquals(
      errorOf(outside).errorClass,
      xyz.kd5ujc.scarab.uplc.builtin.ErrorClass.Operational,
      "an index outside `Int` is an operational unlifting failure"
    )
    val deep = con(Constant.Data(PlutusData.List(Vector.fill(50)(PlutusData.I(1)))))
    val limit = ExBudget.of(
      CekMachine
        .run(parameters, BudgetMode.Counting, app(builtin(DefaultFun.EqualsData), deep, deep))
        .budget
        .spent
        .cpu
        .toLong - 1,
      Long.MaxValue / 4
    )
    val run = CekMachine.run(
      parameters,
      BudgetMode.Restricting(limit),
      app(builtin(DefaultFun.EqualsData), deep, deep)
    )
    assert(
      resultOf(run) match
        case Left(CekError.OutOfBudget(_)) => true
        case _                             => false,
      s"a limit one short of an equalsData of a streamed measure runs out, got ${resultOf(run)}"
    )
  }
