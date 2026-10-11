// SPDX-License-Identifier: Apache-2.0
package lab.node.plutus

import io.circe.{Json, parser}
import lab.cbor.Bytes
import lab.node.ledger.ExUnits
import munit.FunSuite
import scalus.uplc.{Program, Term}

/** Differential test of the Scalus machine's costing of `modInteger`, `remainderInteger` (and
  * `addInteger`, `divideInteger`, `quotientInteger` as controls) against the Plutus cost model.
  *
  * The oracle is `plutus-core/cost-model/data/builtinCostModel{A..E}.json` of plutus 1.70.0.0 (the
  * costing function of every builtin per semantics variant) and the evaluators of the shapes in
  * `PlutusCore.Evaluation.Machine.CostingFun.Core` (`runTwoArgumentModel`), written here from the
  * Haskell, not from Scalus. The on-chain parameter list is built from the JSON in the order of
  * `PlutusLedgerApi.V{1,2,3}.ParamName`, so a mismatch in Scalus's parameter order or in the shape
  * it gives a builtin shows up. The subject runs `[[builtin f] (con integer a)] (con integer b)`
  * through [[ScalusEvaluator]] and compares the spent units with the oracle's: the machine step
  * costs cancel against an `addInteger` script of the same shape.
  *
  * Semantics variants (`PlutusLedgerApi.MachineParameters.machineParametersFor`): A is V1/V2 below
  * protocol 9, B is V1/V2 at protocol 9 and 10, C is V3 below protocol 11, D is V1/V2 from 11 and E
  * is V3 from 11.
  */
class BuiltinCostingSuite extends FunSuite:
  private def resource(name: String): String =
    val in = getClass.getResourceAsStream(s"/plutus-1.70.0.0/$name")
    assert(in != null, name)
    try String(in.readAllBytes(), "UTF-8")
    finally in.close()

  private def json(name: String): Json = parser.parse(resource(name)).fold(throw _, identity)

  private def names(language: PlutusLanguage): Vector[String] =
    resource(s"V${language.id + 1}-param-names.txt").linesIterator.filter(_.nonEmpty).toVector

  private final case class Variant(
      letter: String,
      language: PlutusLanguage,
      protocol: Int
  )

  private val variants = Vector(
    Variant("A", PlutusLanguage.V1, 5),
    Variant("A", PlutusLanguage.V2, 7),
    Variant("B", PlutusLanguage.V1, 9),
    Variant("B", PlutusLanguage.V2, 10),
    Variant("C", PlutusLanguage.V3, 9),
    Variant("C", PlutusLanguage.V3, 10),
    Variant("D", PlutusLanguage.V1, 11),
    Variant("D", PlutusLanguage.V2, 11),
    Variant("E", PlutusLanguage.V3, 11)
  )

  /** The leaf of `path` below `root`, if it is a number. */
  private def leaf(root: Json, path: Seq[String]): Option[Long] =
    path
      .foldLeft(Option(root))((j, k) => j.flatMap(_.hcursor.downField(k).focus))
      .flatMap(_.asNumber)
      .flatMap(_.toLong)

  /** The on-chain parameters of `variant`: every name of the language's `ParamName` up to the first
    * the variant's JSON does not define, valued from the JSON.
    */
  private def parameters(v: Variant): Vector[Long] =
    val builtins = json(s"builtinCostModel${v.letter}.json")
    val machine = json(s"cekMachineCosts${v.letter}.json")
    def valueOf(name: String): Option[Long] =
      val path = name.split('\'').toVector
      val head = s"${path.head.head.toLower}${path.head.tail}"
      leaf(if head.startsWith("cek") then machine else builtins, head +: path.tail)
    names(v.language).iterator.map(valueOf).takeWhile(_.isDefined).map(_.get).toVector

  // --- the oracle: plutus's costing functions over sizes in 64-bit words ----------------------

  private def num(j: Json, key: String): Long =
    j.hcursor.downField(key).focus.flatMap(_.asNumber).flatMap(_.toLong).getOrElse(sys.error(key))

  /** `runTwoArgumentModel` for the shapes the integer builtins use. */
  private def cost(f: Json, x: Long, y: Long): Long =
    val arguments = f.hcursor.downField("arguments").focus.get
    f.hcursor.downField("type").as[String].toOption.get match
      case "constant_cost"    => arguments.asNumber.flatMap(_.toLong).get
      case "max_size"         => num(arguments, "intercept") + num(arguments, "slope") * (x max y)
      case "added_sizes"      => num(arguments, "intercept") + num(arguments, "slope") * (x + y)
      case "linear_in_y"      => num(arguments, "intercept") + num(arguments, "slope") * y
      case "linear_in_y2"     => num(arguments, "intercept") + num(arguments, "slope") * y
      case "multiplied_sizes" => num(arguments, "intercept") + num(arguments, "slope") * (x * y)
      case "subtracted_sizes" =>
        num(arguments, "intercept") + num(arguments, "slope") * (num(
          arguments,
          "minimum"
        ) max (x - y))
      case "const_above_diagonal" =>
        if x < y then num(arguments, "constant")
        else cost(arguments.hcursor.downField("model").focus.get, x, y)
      case "above_and_below_diagonal" =>
        cost(arguments.hcursor.downField("model").focus.get, x max y, x min y)
      case "quadratic_in_x_and_y" =>
        val c = (k: String) => num(arguments, k)
        (c("c00") + c("c10") * x + c("c01") * y + c("c20") * x * x + c("c11") * x * y +
          c("c02") * y * y) max c("minimum")
      case other => sys.error(s"shape $other")

  /** The oracle's cost of the builtin on integers of `x` and `y` words: (cpu, memory). */
  private def oracle(v: Variant, builtin: String, x: Long, y: Long): (Long, Long) =
    val f = json(s"builtinCostModel${v.letter}.json").hcursor.downField(builtin)
    (cost(f.downField("cpu").focus.get, x, y), cost(f.downField("memory").focus.get, x, y))

  // --- the subject: Scalus evaluating the script with the on-chain parameters ---------------

  /** An integer of exactly `words` 64-bit words, as `memoryUsageInteger` counts them. */
  private def integerOf(words: Int): BigInt = BigInt(1) << (64 * (words - 1))

  private def spent(v: Variant, builtin: String, a: BigInt, b: BigInt): ExUnits =
    // PlutusV3 scripts must return unit, so the integer is dropped: the same for every builtin.
    val text =
      s"(lam ctx [ (lam r (con unit ())) [ [ (builtin $builtin) (con integer $a) ] (con integer $b) ] ])"
    // Version 1.0.0 is valid for every language and protocol version (no constr or case).
    val code = Bytes.fromArray(
      Program.parseUplc(s"(program 1.0.0 $text)").fold(sys.error, identity).cborEncoded
    )
    val result = ScalusEvaluator.pure.evaluate(
      EvalRequest(
        v.language,
        v.protocol,
        code,
        Vector(PlutusData.unit),
        parameters(v),
        ExUnits(BigInt(1) << 60, BigInt(1) << 60)
      )
    )
    assert(result.isSuccess, s"${v.letter} $builtin $a $b: $result")
    result.spent

  /** Word sizes that cross every branch: below, on and above the diagonal, small and large. */
  private val sizes = Vector(1, 2, 3, 4, 7, 16, 40, 120)

  private val builtins = Vector(
    "modInteger",
    "remainderInteger",
    "divideInteger",
    "quotientInteger"
  )

  variants.foreach { v =>
    test(
      s"variant ${v.letter} (${v.language} @ protocol ${v.protocol}): ${builtins.mkString(", ")}"
    ) {
      assert(parameters(v).size > 100, "the parameter list was built")
      val failures = for
        builtin <- builtins
        x <- sizes
        y <- sizes
        // a divisor of y words, a dividend of x words; the base add script has the same shape
        a = integerOf(x)
        b = integerOf(y)
        base = spent(v, "addInteger", a, b)
        got = spent(v, builtin, a, b)
        (cpuF, memF) = oracle(v, builtin, x, y)
        (cpuA, memA) = oracle(v, "addInteger", x, y)
        expected = ExUnits(memF - memA, cpuF - cpuA)
        actual = ExUnits(got.mem - base.mem, got.steps - base.steps)
        if expected != actual
      yield s"$builtin x=$x y=$y words: expected $expected, Scalus $actual"
      assert(failures.isEmpty, failures.take(8).mkString("\n"))
    }
  }

  // --- verifyEd25519Signature: the argument the cost depends on ------------------------------

  /** Costs of a three-argument builtin on arguments of x, y and z words (the shapes it uses). */
  private def cost3(f: Json, x: Long, y: Long, z: Long): Long =
    val arguments = f.hcursor.downField("arguments").focus.get
    f.hcursor.downField("type").as[String].toOption.get match
      case "constant_cost" => arguments.asNumber.flatMap(_.toLong).get
      case "linear_in_x"   => num(arguments, "intercept") + num(arguments, "slope") * x
      case "linear_in_y"   => num(arguments, "intercept") + num(arguments, "slope") * y
      case "linear_in_z"   => num(arguments, "intercept") + num(arguments, "slope") * z
      case other           => sys.error(s"shape $other")

  /** `[ verifyEd25519Signature key message signature ]` with a message of `bytes` bytes. The
    * signature is not valid, which makes the builtin return false, still after costing.
    */
  private def spentVerify(v: Variant, messageBytes: Int): ExUnits =
    def bytes(n: Int, b: Int) = "#" + (b.toHexString.reverse.padTo(2, '0').reverse * n)
    val text =
      s"(lam ctx [ (lam r (con unit ())) [ (builtin verifyEd25519Signature) " +
        s"(con bytestring ${bytes(32, 1)}) (con bytestring ${bytes(messageBytes, 2)}) " +
        s"(con bytestring ${bytes(64, 3)}) ] ])"
    val code = Bytes.fromArray(
      Program.parseUplc(s"(program 1.0.0 $text)").fold(sys.error, identity).cborEncoded
    )
    val result = ScalusEvaluator.pure.evaluate(
      EvalRequest(
        v.language,
        v.protocol,
        code,
        Vector(PlutusData.unit),
        parameters(v),
        ExUnits(BigInt(1) << 60, BigInt(1) << 60)
      )
    )
    assert(result.isSuccess, s"${v.letter} verifyEd25519Signature $messageBytes: $result")
    result.spent

  // The costing function is `linear_in_z` (the signature, always 8 words) under semantics variant A
  // and `linear_in_y` (the message) from B on: with the message varying, the cost is constant in A.
  // Scalus 1.2.0 prices every variant by the message; `ScalusEvaluator` corrects variant A.
  variants.foreach { v =>
    test(s"verifyEd25519Signature, variant ${v.letter} (${v.language} @ protocol ${v.protocol})") {
      val model =
        json(s"builtinCostModel${v.letter}.json").hcursor.downField("verifyEd25519Signature")
      def words(bytes: Int) = (bytes - 1) / 8 + 1
      def expected(messageBytes: Int) =
        cost3(model.downField("cpu").focus.get, 4, words(messageBytes), 8)
      val base = spentVerify(v, 8)
      Vector(8, 64, 88, 200, 1000).foreach { n =>
        val got = spentVerify(v, n)
        assertEquals(
          got.steps - base.steps,
          BigInt(expected(n) - expected(8)),
          s"message of $n bytes"
        )
        assertEquals(got.mem, base.mem)
      }
    }
  }
