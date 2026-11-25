// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import scala.deriving.Mirror
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll
import org.scalacheck.rng.Seed
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultFun,
  DefaultUni,
  SemanticsVariant,
  Signature,
  Term,
  Text
}
import xyz.kd5ujc.scarab.uplc.builtin.{
  BuiltinFailure,
  Builtins,
  Denotations,
  ErrorClass,
  EvaluationFailure
}
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreUnlifting, PresentCoreDenotations}
import xyz.kd5ujc.scarab.uplc.cost.*
import xyz.kd5ujc.scarab.uplc.flat.FlatGen
import xyz.kd5ujc.scarab.uplc.cek.Terms.*

/** The machine against [[PresentCekMachine]], the machine before a saturated builtin application
  * read its arguments as the machine holds them (#1411), which runs with the core denotations and
  * the costing as they were then ([[PresentBuiltins]]), so that the two share no code #1411
  * changed: over generated builtin applications and terms, with every builtin and the default cost
  * model of several variants, cost models of every shape, the stub builtins and a stream cost,
  * counting and restricting, each report must agree in its result, its logs, the budget it spent
  * and its failure. The applications include arguments that do not unlift (not a constant, a
  * constant of another type, a constant outside a variant's bound), constants of another shape for
  * a parameter whose type has variables, denotations that fail, and limits that run out at any
  * point, inside a builtin's cost stream too.
  */
final class CekApplicationSuite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(1000)

  private val variants: Vector[SemanticsVariant] =
    Vector(SemanticsVariant.B, SemanticsVariant.C, SemanticsVariant.E, SemanticsVariant.G)

  /** The parameters of the machine and those of the machine before #1411. */
  private final case class Machines(now: MachineParameters, before: MachineParameters)

  private lazy val costs: Map[SemanticsVariant, VariantCosts] =
    variants.map(v => v -> DefaultCostModels.costs(v)).toMap

  private lazy val chain: Map[SemanticsVariant, Machines] =
    variants.map { v =>
      v -> Machines(
        CekConformance.machine(costs(v), CaserBuiltin.WithData),
        PresentBuiltins.machine(costs(v), CaserBuiltin.WithData)
      )
    }.toMap

  /** The machines of variant `v` with `model`'s costing. */
  private def costedBy(v: SemanticsVariant, model: BuiltinCostModel[SatInt]): Machines =
    val meanings = CekConformance.denotations(v)
    val now = PresentBuiltins.denotations(v)
    val denotations: Denotations = function => meanings.getOrElse(function, now(function))
    Machines(
      MachineParameters(
        costs(v).machineCosts,
        Builtins(v, CoreUnlifting.of(v), denotations, BuiltinCosting.of(v, model)),
        CaserBuiltin.WithData
      ),
      MachineParameters(costs(v).machineCosts, PresentBuiltins.of(v, model), CaserBuiltin.WithData)
    )

  /** The stub builtins, whose components give only their vectors of arguments, with `trace` costing
    * a stream of three elements.
    */
  private val stubParameters: MachineParameters =
    StubBuiltins
      .parameters(StubBuiltins.distinctCosts)
      .copy(builtins =
        StubBuiltins.withCost(
          DefaultFun.Trace,
          () => CostStream.of(ExBudget.of(1000, 10), ExBudget.of(5000, 10), ExBudget.of(700, 300))
        )
      )

  private val stubs: Machines = Machines(stubParameters, stubParameters)

  /** The core builtins of E with the stub costing, and before #1411 the core denotations then. */
  private val coreStubs: Machines =
    val e = SemanticsVariant.E
    val before = PresentCoreDenotations.of(e)
    Machines(
      MachineParameters(StubBuiltins.distinctCosts, StubBuiltins.core(e), CaserBuiltin.WithoutData),
      MachineParameters(
        StubBuiltins.distinctCosts,
        Builtins(
          e,
          CoreUnlifting.of(e),
          f => before.getOrElse(f, StubBuiltins.denotations(f)),
          StubBuiltins.costing
        ),
        CaserBuiltin.WithoutData
      )
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
  private def mode(machines: Machines, term: Term): Gen[BudgetMode] =
    val spent = PresentCekMachine.run(machines.before, BudgetMode.Counting, term).budget.spent
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
  private def agree(machines: Machines, mode: BudgetMode, term: Term): Prop =
    val now = CekMachine.run(machines.now, mode, term)
    val before = PresentCekMachine.run(machines.before, mode, term)
    (Prop(resultOf(now) == resultOf(before)) :|
      s"result: ${resultOf(now)} where the present machine gives ${resultOf(before)}") &&
    (Prop(now.logs == before.logs) :|
      s"logs: ${now.logs} where the present machine gives ${before.logs}") &&
    (Prop(now.budget == before.budget) :|
      s"budget: ${now.budget} where the present machine gives ${before.budget}") &&
    (Prop(causeOf(now) == causeOf(before)) :|
      s"failure's cause: ${causeOf(now)} where the present machine gives ${causeOf(before)}")

  private def checked(parameters: Gen[Machines], terms: Gen[Term]): Prop =
    val cases =
      for
        p <- parameters
        term <- terms
        m <- mode(p, term)
      yield (p, term, m)
    forAll(cases) { case (p, term, m) => agree(p, m, term) :| s"term: $term, mode: $m" }

  private val anyChain: Gen[Machines] = Gen.oneOf(variants).map(chain)

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

  /** The outcome classes the properties are to reach. */
  private enum Reached:
    case Value, Structural, Operational, Evaluation, OutOfBudget, Machine

  private def reached(report: CekReport): Reached = report.result match
    case _: CekResult.Success => Reached.Value
    case CekResult.Failure(failure) =>
      failure.error match
        case CekError.Builtin(_, BuiltinFailure.Unlifting(f)) =>
          f.errorClass match
            case ErrorClass.Structural  => Reached.Structural
            case ErrorClass.Operational => Reached.Operational
        case CekError.Builtin(_, BuiltinFailure.Evaluation(_)) => Reached.Evaluation
        case CekError.OutOfBudget(_)                           => Reached.OutOfBudget
        case _                                                 => Reached.Machine

  test("the generated applications reach every class of outcome the properties compare") {
    val cases =
      for
        p <- anyChain
        term <- application(2)
        m <- mode(p, term)
      yield CekMachine.run(p.now, m, term)
    val seen = Iterator
      .iterate(Seed(1411L))(_.next)
      .take(2000)
      .map(seed => reached(cases.pureApply(Gen.Parameters.default, seed)))
      .toSet
    assertEquals(seen, Reached.values.toSet)
  }

  test("a limit that runs out inside a cost stream stops at the element that overspends") {
    // `trace` costs 1000, 5000 and 700 CPU in the stub costing; the steps before it are counted,
    // not yet spent, so the budget meets the stream with only the start-up's 50 spent: a limit of
    // 3050 overspends at the second element and never spends the third.
    val term = app(force(builtin(DefaultFun.Trace)), con(StubBuiltins.string("x")), int(1))
    val mode = BudgetMode.Restricting(ExBudget.of(3050, 1000))
    val now = CekMachine.run(stubParameters, mode, term)
    assertEquals(
      resultOf(now),
      Left(CekError.OutOfBudget(ExBudget.of(3050 - 50 - 1000 - 5000, 1000 - 50 - 10 - 10))),
      "out of budget at the second element"
    )
    assertEquals(now.budget, PresentCekMachine.run(stubParameters, mode, term).budget, "as before")
  }

  private val coefficient: Gen[SatInt] =
    Gen.frequency(4 -> Gen.choose(0L, 2000L), 1 -> Gen.choose(0L, 3L)).map(SatInt(_))
  private val linear: Gen[Linear[SatInt]] = Gen.zip(coefficient, coefficient).map(Linear(_, _))
  private val linear2: Gen[Linear2[SatInt]] =
    Gen.zip(coefficient, coefficient, coefficient).map(Linear2(_, _, _))
  private val quadratic: Gen[Quadratic[SatInt]] =
    Gen.zip(coefficient, coefficient, coefficient).map(Quadratic(_, _, _))
  private val quadratic2: Gen[Quadratic2[SatInt]] =
    Gen
      .listOfN(7, coefficient)
      .map(c => Quadratic2(c(0), c(1), c(2), c(3), c(4), c(5), c(6)))
  private val subtracted: Gen[Subtracted[SatInt]] =
    Gen.zip(coefficient, coefficient, coefficient).map(Subtracted(_, _, _))

  /** Every shape of one argument. */
  private val oneShape: Gen[OneArgument[SatInt]] =
    Gen.oneOf(
      coefficient.map(OneArgument.ConstantCost(_)),
      linear.map(OneArgument.LinearInX(_)),
      quadratic.map(OneArgument.QuadraticInX(_))
    )

  /** Every shape of two arguments, the nested ones `depth` deep. */
  private def twoShape(depth: Int): Gen[TwoArguments[SatInt]] =
    import TwoArguments.*
    val flat = Gen.oneOf(
      coefficient.map(ConstantCost(_)),
      linear.map(LinearInX(_)),
      linear.map(LinearInY(_)),
      subtracted.map(LinearInY2(_)),
      linear2.map(LinearInXAndY(_)),
      linear.map(AddedSizes(_)),
      subtracted.map(SubtractedSizes(_)),
      linear.map(MultipliedSizes(_)),
      linear.map(MinSize(_)),
      linear.map(MaxSize(_)),
      Gen
        .zip(coefficient, coefficient, coefficient)
        .map((c, i, s) => LinearOnDiagonal(ConstantOrLinear(c, i, s))),
      quadratic.map(QuadraticInY(_)),
      quadratic2.map(QuadraticInXAndY(_)),
      Gen
        .listOfN(4, coefficient)
        .map(c => WithInteractionInXAndY(Interaction(c(0), c(1), c(2), c(3))))
    )
    if depth <= 0 then flat
    else
      Gen.frequency(
        6 -> flat,
        1 -> Gen.zip(coefficient, oneShape).map(ConstOffDiagonal(_, _)),
        1 -> Gen.zip(coefficient, twoShape(depth - 1)).map(ConstAboveDiagonal(_, _)),
        1 -> Gen.zip(coefficient, twoShape(depth - 1)).map(ConstBelowDiagonal(_, _)),
        1 -> Gen.zip(coefficient, twoShape(depth - 1)).map(AboveAndBelowDiagonal(_, _))
      )

  /** Every shape of three arguments. */
  private val threeShape: Gen[ThreeArguments[SatInt]] =
    import ThreeArguments.*
    Gen.oneOf(
      coefficient.map(ConstantCost(_)),
      linear.map(LinearInX(_)),
      linear.map(LinearInY(_)),
      linear.map(LinearInZ(_)),
      quadratic.map(QuadraticInZ(_)),
      linear.map(LiteralInYOrLinearInZ(_)),
      linear.map(LinearInMaxYZ(_)),
      linear2.map(LinearInYAndZ(_)),
      quadratic2.map(QuadraticInYAndZ(_)),
      Gen.zip(coefficient, coefficient, coefficient).map((a, b, c) => ExpModCost(ExpMod(a, b, c)))
    )

  private val fourShape: Gen[FourArguments[SatInt]] =
    Gen.oneOf(
      coefficient.map(FourArguments.ConstantCost(_)),
      linear.map(FourArguments.LinearInU(_))
    )

  /** A CPU and a memory shape of the arity of `existing`, a costing function of the model. */
  private def shapeLike(existing: Any): Gen[Any] = existing match
    case CostingFun(_: OneArgument[?], _) => Gen.zip(oneShape, oneShape).map(CostingFun(_, _))
    case CostingFun(_: TwoArguments[?], _) =>
      Gen.zip(twoShape(1), twoShape(1)).map(CostingFun(_, _))
    case CostingFun(_: ThreeArguments[?], _) =>
      Gen.zip(threeShape, threeShape).map(CostingFun(_, _))
    case CostingFun(_: FourArguments[?], _) => Gen.zip(fourShape, fourShape).map(CostingFun(_, _))
    case other                              => Gen.const(other)

  /** `model` with `function` costed by a generated shape of each kind, CPU and memory. */
  private def reshaped(
      model: BuiltinCostModel[SatInt],
      function: DefaultFun
  ): Gen[BuiltinCostModel[SatInt]] =
    val position = model.productElementNames.indexOf(function.name)
    assert(position >= 0, s"no field of the cost model for ${function.name}")
    shapeLike(model.productElement(position)).map { shape =>
      val fields = model.productIterator.toArray
      fields(position) = shape
      summon[Mirror.ProductOf[BuiltinCostModel[SatInt]]].fromProduct(Tuple.fromArray(fields))
    }

  /** Constants whose measures are streams: texts and lists past 100, `Data` with children. */
  private val streamy: Gen[Term] =
    Gen.oneOf(
      Gen
        .choose(90, 230)
        .flatMap(n => Gen.listOfN(n, Gen.alphaNumChar))
        .map(cs => con(Constant.String(Text.fromString(cs.mkString)))),
      Gen
        .choose(90, 230)
        .map(n =>
          con(
            Constant.List
              .of(DefaultUni.Integer, Vector.fill(n)(Constant.Integer(1)))
              .fold(m => throw IllegalStateException(m.toString), identity)
          )
        ),
      FlatGen.constantOf(DefaultUni.Data).map(con)
    )

  /** An argument for `parameter` that may have a measure of many elements, of the parameter's shape
    * or, where its type has variables or it is opaque, of any other.
    */
  private def measuredArgument(parameter: Signature.Parameter): Gen[Term] = parameter match
    case Signature.Parameter.Opaque(_) =>
      Gen.frequency(3 -> streamy, 2 -> FlatGen.constants.map(con), 1 -> notAConstant)
    case Signature.Parameter.Constant(t) =>
      t.ground match
        case Some(uni) => Gen.frequency(4 -> constantTerm(uni), 1 -> streamy)
        case None      => Gen.frequency(3 -> streamy, 2 -> shaped, 1 -> FlatGen.constants.map(con))

  /** The builtins with a parameter whose type has variables, or an opaque one. */
  private val polymorphic: Vector[DefaultFun] =
    DefaultFun.values.toVector.filter(_.signature.parameters.exists {
      case Signature.Parameter.Opaque(_)   => true
      case Signature.Parameter.Constant(t) => t.ground.isEmpty
    })

  property("cost models of every shape, on every argument, with constants of any shape") {
    val cases =
      for
        v <- Gen.oneOf(variants)
        function <- Gen.frequency(
          3 -> Gen.oneOf(polymorphic),
          1 -> Gen.oneOf(DefaultFun.values.toSeq)
        )
        model <- reshaped(costs(v).model.builtins, function)
        arguments <- Gen.sequence[List[Term], Term](
          function.signature.parameters.map(measuredArgument)
        )
        term = app(nest(function.signature.forces, builtin(function))(force), arguments*)
        machines = costedBy(v, model)
        m <- mode(machines, term)
      yield (machines, term, m, function, model)
    forAll(cases) { case (machines, term, m, function, model) =>
      val position = model.productElementNames.indexOf(function.name)
      agree(machines, m, term) :|
        s"term: $term, mode: $m, cost: ${model.productElement(position)}"
    }
  }

  test("the builtins that do not commute take their arguments in order") {
    def bytes(values: Int*): Term =
      con(Constant.ByteString(Bytes.wrap(values.map(_.toByte).toArray)))
    def integers(values: Int*): Term =
      con(
        Constant.List
          .of(DefaultUni.Integer, values.toVector.map(n => Constant.Integer(n)))
          .fold(m => throw IllegalStateException(m.toString), identity)
      )
    def apply(function: DefaultFun, arguments: Term*): Term =
      app(nest(function.signature.forces, builtin(function))(force), arguments*)
    import DefaultFun.*
    val expected: Vector[(Term, Term)] = Vector(
      apply(SubtractInteger, int(7), int(2)) -> int(5),
      apply(DivideInteger, int(7), int(2)) -> int(3),
      apply(DivideInteger, int(-7), int(2)) -> int(-4),
      apply(DivideInteger, int(0), int(7)) -> int(0),
      apply(QuotientInteger, int(-7), int(2)) -> int(-3),
      apply(RemainderInteger, int(-7), int(2)) -> int(-1),
      apply(ModInteger, int(-7), int(2)) -> int(1),
      apply(ModInteger, int(7), int(-2)) -> int(-1),
      apply(LessThanInteger, int(1), int(2)) -> bool(true),
      apply(LessThanInteger, int(2), int(1)) -> bool(false),
      apply(LessThanEqualsInteger, int(2), int(1)) -> bool(false),
      apply(AppendByteString, bytes(1), bytes(2, 3)) -> bytes(1, 2, 3),
      apply(ConsByteString, int(1), bytes(2, 3)) -> bytes(1, 2, 3),
      apply(SliceByteString, int(1), int(2), bytes(0, 1, 2, 3)) -> bytes(1, 2),
      apply(IndexByteString, bytes(10, 11), int(1)) -> int(11),
      apply(LessThanByteString, bytes(1), bytes(2)) -> bool(true),
      apply(MkCons, int(1), integers(2, 3)) -> integers(1, 2, 3),
      apply(IfThenElse, bool(true), int(1), int(2)) -> int(1),
      apply(IfThenElse, bool(false), int(1), int(2)) -> int(2),
      apply(ChooseList, integers(), int(1), int(2)) -> int(1),
      apply(ChooseList, integers(5), int(1), int(2)) -> int(2)
    )
    for
      v <- variants
      (term, value) <- expected
    do
      assertEquals(
        resultOf(CekMachine.run(chain(v).now, BudgetMode.Counting, term)),
        Right(value),
        s"$v: $term"
      )
    val byZero = apply(DivideInteger, int(7), int(0))
    assertEquals(
      resultOf(CekMachine.run(chain(SemanticsVariant.E).now, BudgetMode.Counting, byZero)),
      Left(
        CekError.Builtin(DivideInteger, BuiltinFailure.Evaluation(EvaluationFailure.DivisionByZero))
      ),
      "divideInteger 7 0 divides by the second"
    )
  }
