// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultFun,
  DefaultUni,
  PlutusData,
  PlutusLanguage,
  SemanticsVariant,
  Text,
  Value
}
import xyz.kd5ujc.scarab.uplc.builtin.{Argument, MachineValue}

/** The costs of builtins with each variant's default parameters: from the budgets the Haskell
  * evaluator wrote for the upstream conformance programs (variant E), and worked by hand from the
  * reference's formulas for the other variants and for parameters at the bounds of `Int64`.
  */
final class CostingSuite extends munit.FunSuite:
  import DefaultFun.*

  private given MachineValue[Constant] with
    def constantOf(value: Constant): Option[Constant] = Some(value)
    def ofConstant(constant: Constant): Constant = constant

  private def int(n: BigInt): Constant = Constant.Integer(n)
  private def hex(h: String): Constant =
    Constant.ByteString(Bytes.fromHex(h).fold(e => fail(e.toString), identity))
  private def zeros(size: Int): Constant = Constant.ByteString(Bytes.wrap(new Array[Byte](size)))
  private def str(s: String): Constant = Constant.String(Text.fromString(s))
  private def data(d: PlutusData): Constant = Constant.Data(d)
  private def value(outer: Seq[(String, Seq[(String, Int)])]): Constant =
    def bytes(h: String) = Bytes.fromHex(h).fold(e => fail(e.toString), identity)
    Constant.Value(
      Value
        .decoded(outer.map((c, ts) => bytes(c) -> ts.map((t, q) => bytes(t) -> BigInt(q))))
        .fold(e => fail(e.toString), identity)
    )
  private def integers(values: Int*): Constant =
    Constant.List
      .of(DefaultUni.Integer, values.toVector.map(int(_)))
      .fold(e => fail(e.toString), identity)

  private def stream(costs: VariantCosts, function: DefaultFun, arguments: Constant*): CostStream =
    costs.costing(function)(arguments.toVector.map(Argument.Con[Constant](_)))

  private def budgets(
      costs: VariantCosts,
      function: DefaultFun,
      arguments: Constant*
  ): Vector[ExBudget] =
    stream(costs, function, arguments*).prefix(Int.MaxValue)

  private def cost(costs: VariantCosts, function: DefaultFun, arguments: Constant*): ExBudget =
    stream(costs, function, arguments*).total

  private lazy val defaults: Map[SemanticsVariant, VariantCosts] =
    DefaultCostModels.Variants.map(v => v -> DefaultCostModels.costs(v)).toMap

  /** The steps of a conformance program before its builtin is applied. */
  private final case class Steps(apply: Int, force: Int, builtin: Int, constant: Int)

  /** The budget the Haskell evaluator wrote for an upstream conformance program at variant E's
    * defaults (`plutus-conformance`, `mkCekEvaluator`, `def` = variant E), less the machine's
    * start-up and steps: what the builtin application cost.
    */
  private def conformance(test: String, steps: Steps): ExBudget =
    val Array(builtin, name) = test.split('/'): @unchecked
    val text =
      DefaultCostModels.read(s"conformance/builtin/semantics/$builtin/$name/$name.budget.expected")
    def field(key: String): Long =
      s"$key:\\s*(\\d+)".r
        .findFirstMatchIn(text)
        .fold(fail(s"$test: no $key in $text"))(_.group(1).toLong)
    val machine = defaults(SemanticsVariant.E).machineCosts
    val spent =
      machine.startup + machine.step(StepKind.Apply).times(steps.apply) +
        machine.step(StepKind.Force).times(steps.force) + machine
          .step(StepKind.Builtin)
          .times(steps.builtin) +
        machine.step(StepKind.Constant).times(steps.constant)
    ExBudget.of(field("cpu") - spent.cpu.toLong, field("mem") - spent.memory.toLong)

  private val two = Steps(apply = 2, force = 0, builtin = 1, constant = 2)
  private val three = Steps(apply = 3, force = 0, builtin = 1, constant = 3)

  test("variant E against the Haskell evaluator's budgets of the upstream conformance programs") {
    val e = defaults(SemanticsVariant.E)
    val cases = Vector(
      // max_size: 100788 + 420 * max(1, 1) = 101208; memory 1 + 1 * 1 = 2
      ("addInteger/addInteger-01", two, cost(e, AddInteger, int(1), int(1))),
      // above_and_below_diagonal, quadratic at (1, 1): 123203 + 1716 + 7305 + 57 + 960 - 900
      ("divideInteger/divideInteger-01", two, cost(e, DivideInteger, int(1), int(2))),
      (
        "equalsData/equalsData-01",
        two, {
          val d = data(PlutusData.Constr(1, Vector(PlutusData.I(0))))
          cost(e, EqualsData, d, d)
        }
      ),
      // linear_in_x on the empty string (1 word): 158519 + 8942; the amount is an Int (1)
      ("shiftByteString/case-01", two, cost(e, ShiftByteString, zeros(0), int(3))),
      // linear_in_z on ValueMaxDepth: (integerLog2 2 + 1) + (integerLog2 1 + 1) = 3; 219951 + 9444 * 3
      (
        "lookupCoin/absent",
        three,
        cost(
          e,
          LookupCoin,
          hex("aa"),
          hex("bb"),
          value(Vector("aa" -> Vector("aa" -> 100), "bb" -> Vector("aa" -> 1)))
        )
      ),
      // linear_in_x on IntegerCostedLiterally 7: 116711 + 1957 * 7 = 130410; memory 4
      (
        "dropList/dropList-03",
        Steps(2, 1, 1, 2),
        cost(e, DropList, int(7), integers(11, 22, 33, 44, 55, 66, 77, 88, 99))
      ),
      // exp_mod_cost at (1, 1, 1): 607153 + 231697 + 53144 = 891994
      ("expModInteger/expMod-01", three, cost(e, ExpModInteger, int(500), int(0), int(500))),
      // linear_on_diagonal on TextCostedByByteLength: 3 quot 4 = 0, 7 quot 4 = 1: off, 39184
      ("equalsString/equalsString-01", two, cost(e, EqualsString, str("Ola"), str(" mundo!"))),
      // 0 = 0: 1000 + 60594 * 0
      ("equalsString/equalsString-02", two, cost(e, EqualsString, str("Ola"), str("Ola"))),
      // linear_in_y: 72010 + 178 * 2 (12 bytes); memory added_sizes 1 + 2 = 3
      (
        "consByteString/consByteString-03",
        two,
        cost(e, ConsByteString, int(84), hex("686543616B654973414C6965"))
      ),
      // multiplied_sizes: 90434 + 519 * (2 * 1); memory added_sizes 2 + 1
      (
        "multiplyInteger/multiplyInteger-02",
        two,
        cost(e, MultiplyInteger, int(BigInt("793479793478939166266268485555555")), int(0))
      ),
      // const_above_diagonal on total sizes 3 >= 2: 618401 + 1998 * 3 + 28258 * 2 = 680911
      (
        "valueContains/multi-sufficient",
        two,
        cost(
          e,
          ValueContains,
          value(Vector("aa" -> Vector("aa" -> 10, "bb" -> 2800), "ffff" -> Vector("8888" -> 100))),
          value(Vector("aa" -> Vector("aa" -> 10), "ffff" -> Vector("8888" -> 20)))
        )
      ),
      // linear_on_diagonal, 1 word each: 28755 + 75 * 1 = 28830
      (
        "equalsByteString/equalsByteString",
        two,
        cost(e, EqualsByteString, hex("00ffaa"), hex("00ffaa"))
      ),
      // linear_in_z on 13 bytes (2 words): 20467 + 1 * 2; memory 4 + 0 * 2. The Ints are 1 each.
      (
        "sliceByteString/sliceByteString-01",
        three,
        cost(e, SliceByteString, int(3), int(5), hex("54686543616B654973414C6965"))
      ),
      // linear_in_y on a list of 1: 281145 + 18848 * 1; memory linear_in_x on #ff: 0 + 1 * 1
      (
        "writeBits/case-11",
        three,
        cost(e, WriteBits, hex("ff"), integers(0), Constant.Bool(false))
      ),
      // 5 nodes: quadratic_in_x 1000 + 95933 * 5 + 5^2 = 480690; memory 1 + 11 * 5 = 56
      (
        "unValueData/max-key-len",
        Steps(1, 0, 1, 1),
        cost(
          e,
          UnValueData,
          data(
            PlutusData.Map(
              Vector(
                PlutusData.B(Bytes.wrap(new Array[Byte](32))) ->
                  PlutusData.Map(
                    Vector(PlutusData.B(Bytes.wrap(new Array[Byte](32))) -> PlutusData.I(1))
                  )
              )
            )
          )
        )
      ),
      // linear_in_u on ValueMaxDepth 1 + 1: 356924 + 18413 * 2 = 393750; memory 45 + 21 * 2 = 87
      (
        "insertCoin/long-key-zero-1",
        Steps(4, 0, 1, 4),
        cost(e, InsertCoin, hex("aa" * 33), hex(""), int(0), value(Vector("" -> Vector("" -> 1))))
      )
    )
    cases.foreach((test, steps, actual) => assertEquals(actual, conformance(test, steps), test))
  }

  test("variant E's equalsData spends the min_size stream element by element") {
    val d = data(PlutusData.Constr(1, Vector(PlutusData.I(0))))
    // Both measures [4, 5]; their min is [4, 5]; scaleLinearly 898148 27279 gives
    // [898148, 4 * 27279, 5 * 27279]; memory is the constant 1, then zeros.
    assertEquals(
      budgets(defaults(SemanticsVariant.E), EqualsData, d, d),
      Vector(ExBudget.of(898148, 1), ExBudget.of(109116, 0), ExBudget.of(136395, 0))
    )
  }

  test("variant A: text by characters in pieces, the stream interleaved by added_sizes") {
    // appendString "hello" (5) and 150 characters ([100, 50]): added gives [5, 100, 50].
    // CPU 1000 + 24177 x: [1000, 120885, 2417700, 1208850]; memory 4 + x: [4, 5, 100, 50].
    assertEquals(
      budgets(defaults(SemanticsVariant.A), AppendString, str("hello"), str("a" * 150)),
      Vector(
        ExBudget.of(1000, 4),
        ExBudget.of(120885, 5),
        ExBudget.of(2417700, 100),
        ExBudget.of(1208850, 50)
      )
    )
    // max_size: 205665 + 812 * max(2, 1) = 207289; memory 1 + 2 = 3
    assertEquals(
      cost(defaults(SemanticsVariant.A), AddInteger, int(BigInt(1) << 64), int(5)),
      ExBudget.of(207289, 3)
    )
    // added_sizes: 69522 + 11687 * (2 + 1) = 104583; memory 0 + 3
    assertEquals(
      cost(defaults(SemanticsVariant.A), MultiplyInteger, int(BigInt(1) << 64), int(5)),
      ExBudget.of(104583, 3)
    )
  }

  test("consByteString's first argument: an Integer in A, B and D, a Word8 (1) in C and E") {
    val bytes = hex("686543616B654973414C6965")
    // A: linear_in_y 221973 + 511 * 2 (12 bytes); memory added_sizes 0 + (2 + 2) for 2^64's 2 words.
    assertEquals(
      cost(defaults(SemanticsVariant.A), ConsByteString, int(BigInt(1) << 64), bytes),
      ExBudget.of(222995, 4)
    )
    // D the same measure: memory 0 + (2 + 2).
    assertEquals(
      cost(defaults(SemanticsVariant.D), ConsByteString, int(BigInt(1) << 64), bytes).memory,
      SatInt(4)
    )
    // E measures a Word8 as 1 (the unlifting refuses 2^64 there before the costing sees it): 1 + 2.
    assertEquals(
      cost(defaults(SemanticsVariant.E), ConsByteString, int(BigInt(1) << 64), bytes).memory,
      SatInt(3)
    )
  }

  test("variant B: equalsString by characters, on the diagonal") {
    // 2 = 2 characters: 1000 + 60594 * 2 = 122188 (in D and E, 2 quot 4 = 0 bytes: 1000)
    assertEquals(
      cost(defaults(SemanticsVariant.B), EqualsString, str("ab"), str("cd")),
      ExBudget.of(122188, 1)
    )
    assertEquals(
      cost(defaults(SemanticsVariant.D), EqualsString, str("ab"), str("cd")),
      ExBudget.of(1000, 1)
    )
    // min_size: 44749 + 541 * min(2, 1) = 45290
    assertEquals(
      cost(defaults(SemanticsVariant.B), LessThanInteger, int(BigInt(1) << 64), int(5)),
      ExBudget.of(45290, 1)
    )
  }

  test("variant C: quadratic_in_z, literal_in_y_or_linear_in_z, quadratic_in_x and linear_in_y") {
    val c = defaults(SemanticsVariant.C)
    // integerToByteString False 0 (2^64): 1293828 + 28716 * 2 + 63 * 4 = 1351512; width 0 words, so
    // memory linear in z: 0 + 1 * 2 = 2.
    assertEquals(
      cost(c, IntegerToByteString, Constant.Bool(false), int(0), int(BigInt(1) << 64)),
      ExBudget.of(1351512, 2)
    )
    // Width 20 bytes: ((20 - 1) div 8) + 1 = 3 words, the memory as it is.
    assertEquals(
      cost(c, IntegerToByteString, Constant.Bool(false), int(20), int(BigInt(1) << 64)),
      ExBudget.of(1351512, 3)
    )
    // unValueData on 5 nodes [1, 1, 1, 1, 1]: quadratic 1000 + 95933 x + x^2 by running totals
    // 0..4: [1000, 95934, 95936, 95938, 95940, 95942]; memory 1 + 11 x: [1, 11, 11, 11, 11, 11].
    val node = PlutusData.Map(
      Vector(
        PlutusData.B(Bytes.empty) -> PlutusData.Map(
          Vector(PlutusData.B(Bytes.empty) -> PlutusData.I(5))
        )
      )
    )
    assertEquals(
      budgets(c, UnValueData, data(node)),
      Vector(
        ExBudget.of(1000, 1),
        ExBudget.of(95934, 11),
        ExBudget.of(95936, 11),
        ExBudget.of(95938, 11),
        ExBudget.of(95940, 11),
        ExBudget.of(95942, 11)
      )
    )
    // 1000 + 95933 * 5 + 5^2 = 480690
    assertEquals(cost(c, UnValueData, data(node)), ExBudget.of(480690, 56))
    // writeBits on 16 bytes (2 words) and 250 indices ([100, 100, 50]): CPU linear_in_y
    // 281145 + 18848 y: [281145, 1884800, 1884800, 942400]; memory linear_in_x 0 + 2.
    assertEquals(
      budgets(c, WriteBits, zeros(16), integers(Vector.fill(250)(0)*), Constant.Bool(true)),
      Vector(
        ExBudget.of(281145, 2),
        ExBudget.of(1884800, 0),
        ExBudget.of(1884800, 0),
        ExBudget.of(942400, 0)
      )
    )
    // modInteger (2 words, 1 word): not below the diagonal, so the quadratic:
    // max 85848 (123203 + 1716 * 2 + 7305 + 57 * 4 + 549 * 2 - 900) = 134366; memory linear_in_y 1.
    assertEquals(cost(c, ModInteger, int(BigInt(1) << 64), int(5)), ExBudget.of(134366, 1))
    // (1 word, 2 words) is below the diagonal: the constant 85848; memory 0 + 2.
    assertEquals(cost(c, ModInteger, int(5), int(BigInt(1) << 64)), ExBudget.of(85848, 2))
  }

  test("variant D: TextCostedByByteLength, above_and_below_diagonal, linear_in_y2") {
    val d = defaults(SemanticsVariant.D)
    // 5 quot 4 = 1 and 150 quot 4 = 37: one cost each, 1000 + 59957 * 38 = 2279366; memory 4 + 38.
    assertEquals(
      budgets(d, AppendString, str("hello"), str("a" * 150)),
      Vector(ExBudget.of(2279366, 42))
    )
    // divideInteger (3 words, 1 word): multiplied_sizes at (max, min) = (3, 1): 228465 + 122 * 3;
    // memory subtracted_sizes max 1 (3 - 1) = 2.
    assertEquals(cost(d, DivideInteger, int(BigInt(1) << 128), int(5)), ExBudget.of(228831, 2))
    // quotientInteger (1 word, 3 words) is const_above_diagonal: 85848; memory max 1 (1 - 3) = 1.
    assertEquals(cost(d, QuotientInteger, int(5), int(BigInt(1) << 128)), ExBudget.of(85848, 1))
    // modInteger's memory is linear_in_y2: 0 + 1 * 1 on the divisor.
    assertEquals(cost(d, ModInteger, int(BigInt(1) << 128), int(5)).memory, SatInt(1))
    // insertCoin on a Value of 2 currencies and at most 3 tokens: depth 2 + 2 = 4;
    // 356924 + 18413 * 4 = 430576, memory 45 + 21 * 4 = 129.
    val v =
      value(Vector("aa" -> Vector("01" -> 1), "bb" -> Vector("01" -> 1, "02" -> 2, "03" -> 3)))
    assertEquals(cost(d, InsertCoin, hex("cc"), hex("01"), int(5), v), ExBudget.of(430576, 129))
  }

  /** The costs of `variant` from its default list with some parameters replaced by name. */
  private def withParameters(variant: SemanticsVariant, changes: (String, Long)*): VariantCosts =
    val language = DefaultCostModels.languageOf(variant)
    val names = EvaluationContext.parameterNames(language)
    val values = DefaultCostModels.list(language, variant)
    val changed = changes.toMap
    val list = names.zip(values).map((name, v) => changed.getOrElse(name, v))
    assertEquals(changed.keySet -- names, Set.empty[String], "names")
    val (_, parameters) = EvaluationContext.tagWithParamNames(names, list)
    EvaluationContext.costsOf(variant, parameters).fold(e => fail(e.toString), identity)

  private def everyParameter(variant: SemanticsVariant, value: Long): VariantCosts =
    val language = DefaultCostModels.languageOf(variant)
    val names = EvaluationContext.parameterNames(language)
    val (_, parameters) = EvaluationContext.tagWithParamNames(names, Vector.fill(names.size)(value))
    EvaluationContext.costsOf(variant, parameters).fold(e => fail(e.toString), identity)

  private val Max = Long.MaxValue
  private val Min = Long.MinValue

  test("every parameter at Int64's minimum: costs saturate at minBound and never wrap") {
    // The reference evaluates under such a list (issue 485): no cost wraps to a positive value.
    val low = everyParameter(SemanticsVariant.E, Min)
    assertEquals(low.machineCosts.startup, ExBudget.of(Min, Min))
    StepKind.values.foreach(kind =>
      assertEquals(low.machineCosts.step(kind), ExBudget.of(Min, Min), kind.toString)
    )
    // max_size: minBound + minBound * 1 = minBound + minBound = minBound
    assertEquals(cost(low, AddInteger, int(1), int(1)), ExBudget.of(Min, Min))
    // min_size over [4, 5]: [minBound, minBound * 4, minBound * 5] = minBound each; memory a constant.
    val d = data(PlutusData.Constr(1, Vector(PlutusData.I(0))))
    assertEquals(
      budgets(low, EqualsData, d, d),
      Vector(ExBudget.of(Min, Min), ExBudget.of(Min, 0), ExBudget.of(Min, 0))
    )
    // The quadratic is at least its minimum, minBound; subtracted_sizes max minBound (1 - 1) = 0,
    // then minBound + minBound * 0 = minBound.
    assertEquals(cost(low, DivideInteger, int(1), int(1)), ExBudget.of(Min, Min))
    // Every builtin costs, in every variant (A, B, D through PlutusV1's names).
    DefaultCostModels.Variants.foreach { variant =>
      val costs = everyParameter(variant, Min)
      assert(
        costs
          .costing(AddInteger)(Vector(Argument.Con(int(1)), Argument.Con(int(1))))
          .head
          .cpu <= SatInt(0)
      )
    }
  }

  test("every parameter at Int64's maximum: costs saturate at maxBound") {
    val high = everyParameter(SemanticsVariant.D, Max)
    assertEquals(cost(high, AddInteger, int(1), int(1)), ExBudget.of(Max, Max))
    // above_and_below_diagonal: the multiplied model at (1, 1), maxBound + maxBound * 1; memory
    // max maxBound 0 = maxBound, then maxBound + maxBound * maxBound.
    assertEquals(cost(high, DivideInteger, int(1), int(1)), ExBudget.of(Max, Max))
    assertEquals(high.machineCosts.step(StepKind.Apply), ExBudget.of(Max, Max))
  }

  test("mixed signs at the bounds follow the reference's order of saturating operations") {
    val mixed = withParameters(
      SemanticsVariant.E,
      "addInteger-cpu-arguments-intercept" -> Max,
      "addInteger-cpu-arguments-slope" -> Min
    )
    // maxBound + minBound * 2: the product saturates at minBound first, then maxBound + minBound = -1.
    assertEquals(cost(mixed, AddInteger, int(BigInt(1) << 64), int(1)).cpu, SatInt(-1))
    // exp_mod_cost with a negative odd cost: -7 + (-7 `div` 2) = -7 + (-4) = -11 when a is wider
    // than the modulus (2 words over 1): Haskell's div floors.
    val expMod = withParameters(
      SemanticsVariant.E,
      "expModInteger-cpu-arguments-coefficient00" -> -7L,
      "expModInteger-cpu-arguments-coefficient11" -> 0L,
      "expModInteger-cpu-arguments-coefficient12" -> 0L
    )
    assertEquals(cost(expMod, ExpModInteger, int(BigInt(1) << 64), int(3), int(5)).cpu, SatInt(-11))
    assertEquals(cost(expMod, ExpModInteger, int(5), int(3), int(BigInt(1) << 64)).cpu, SatInt(-7))
    // A saturated cost0 stays saturated: maxBound + maxBound `dividedBy` 2 = maxBound + maxBound.
    val saturated =
      withParameters(SemanticsVariant.E, "expModInteger-cpu-arguments-coefficient00" -> Max)
    assertEquals(
      cost(saturated, ExpModInteger, int(BigInt(1) << 64), int(3), int(5)).cpu,
      SatInt(Max)
    )
    // The two-variable quadratic is at least its minimum: c00 = -10^6, others 0, minimum 85848.
    val clamped = withParameters(
      SemanticsVariant.E,
      "divideInteger-cpu-arguments-model-arguments-c00" -> -1000000L,
      "divideInteger-cpu-arguments-model-arguments-c10" -> 0L,
      "divideInteger-cpu-arguments-model-arguments-c01" -> 0L,
      "divideInteger-cpu-arguments-model-arguments-c20" -> 0L,
      "divideInteger-cpu-arguments-model-arguments-c11" -> 0L,
      "divideInteger-cpu-arguments-model-arguments-c02" -> 0L
    )
    assertEquals(cost(clamped, DivideInteger, int(1), int(1)).cpu, SatInt(85848))
  }

  test(
    "a shared Data graph of exponential unfolded size is costed lazily: its first budgets at once"
  ) {
    // d(n + 1) = Constr 0 [d n, d n]: 2^61 - 1 nodes unfolded at n = 60; every Constr is 4.
    val graph = data(
      (1 to 60).foldLeft(PlutusData.I(0): PlutusData)((d, _) => PlutusData.Constr(0, Vector(d, d)))
    )
    val e = defaults(SemanticsVariant.E)
    // equalsData, min_size: [898148, 4 * 27279, 4 * 27279, ...]; memory the constant 1.
    assertEquals(
      stream(e, EqualsData, graph, graph).prefix(4),
      Vector(
        ExBudget.of(898148, 1),
        ExBudget.of(109116, 0),
        ExBudget.of(109116, 0),
        ExBudget.of(109116, 0)
      )
    )
    // serialiseData, linear_in_x both: [955506, 4 * 213312, ...] and [0, 4 * 2, ...].
    assertEquals(
      stream(e, SerialiseData, graph).prefix(3),
      Vector(ExBudget.of(955506, 0), ExBudget.of(853248, 8), ExBudget.of(853248, 8))
    )
    // A constant cost reads no measure at all.
    assertEquals(
      stream(e, UnConstrData, graph),
      CostStream.single(cost(e, UnConstrData, data(PlutusData.I(0))))
    )
  }

  test("V4 is costed as V3: the same names and variants") {
    assertEquals(
      EvaluationContext.parameterNames(PlutusLanguage.V4),
      EvaluationContext.parameterNames(PlutusLanguage.V3)
    )
  }
