// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import io.circe.Json
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.oracle.{GeneratedCorpus, ScriptWriter, Verdict}
import xyz.kd5ujc.scarab.uplc.text.UplcParser

/** Development tool: the wall-clock time of the conversions between `Data` and lists, against the
  * length of the list, through the ledger's entry point (`LedgerEval.evaluateSerialised`, as
  * `Verdict.evaluate` calls it), with the default cost model of the language and protocol.
  *
  * `tools/runMain xyz.kd5ujc.scarab.uplc.DataConversionTimingMain [table [rounds]|retention [id]|requests FILE]`
  *
  *   - `table`: 4,000 applications (200 rounds of 20) of each of `unMapData`, `unListData`,
  *     `unConstrData`, `listData`, `mapData` and `constrData` in V3 at protocol 10, and of `case`
  *     on a `Constr` in V3 at protocol 12, each on an argument of one element and on one of 8,000
  *     map entries or 16,000 list items. The two lengths spend the same budget. For each: the
  *     verdict, the budget, and the median time of `rounds` runs (default 5) after one warm-up run.
  *   - `retention`: two scripts that keep the results of many `unMapData` calls on an 8,000-entry
  *     map alive: V3 at protocol 11, 700 rounds that each build a `constr` of 50 results, within
  *     the budget; and V3 at protocol 10, an endless loop that keeps each result in a closure until
  *     the budget runs out. Run once each (or only the one `id` names), in a JVM of the node's heap
  *     (`-Xmx768m`), so that a heap the results do not fit is seen (as a thrown error); one JVM per
  *     script keeps a full heap from one out of the other's run.
  *   - `requests FILE`: every script above as a request of the generated-program oracle
  *     (`scripts/oracle/plutus/generated-171`), one JSON object per line, to compare its verdicts
  *     and budgets with the ones printed here.
  *
  * The default budget is a transaction's: 10,000,000,000 CPU and 14,000,000 memory units.
  */
object DataConversionTimingMain:
  private final case class Shape(
      id: String,
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      program: String,
      arguments: Vector[PlutusData]
  ):
    lazy val script: Bytes =
      UplcParser.program(program).fold(e => sys.error(s"$id: $e"), ScriptWriter.script)
    def model: Vector[Long] =
      GeneratedCorpus.Plutus171
        .profileOf(language.id + 1, protocol.value)
        .fold(sys.error(s"$id: no profile"))(GeneratedCorpus.model(GeneratedCorpus.Plutus171, _))

  private val budget = ExBudget.of(10000000000L, 14000000L)
  private val unit = "(con unit ())"
  private def zero = PlutusData.I(0)
  private def list(n: Int) = PlutusData.List(Vector.fill(n)(zero))
  private def map(n: Int) = PlutusData.Map(Vector.fill(n)(zero -> zero))
  private def constr(n: Int) = PlutusData.Constr(0, Vector.fill(n)(zero))

  // `k` applications of `call` in a body that a fixed point runs `n` times: 4,000 by default.
  private def loop(call: String, k: Int = 20, n: Int = 200): String =
    val body = (0 until k).foldLeft("[[f f] [[(builtin subtractInteger) n] (con integer 1)]]")(
      (inner, i) => s"[(lam u$i $inner) $call]"
    )
    s"[(lam f [[f f] (con integer $n)]) (lam f (lam n (force [[[(force (builtin ifThenElse)) " +
      s"[[(builtin equalsInteger) n] (con integer 0)]] (delay $unit)] (delay $body)])))]"

  // The loop over `d`, the list `pre` makes of the argument `d0` once.
  private def over(pre: String, call: String): String = s"(lam d0 [(lam d ${loop(call)}) $pre])"

  private def v3(version: String, protocol: ProtocolMajor)(
      name: String,
      term: String,
      small: PlutusData,
      large: PlutusData,
      size: Int
  ): Vector[Shape] =
    Vector(1 -> small, size -> large).map((n, d) =>
      Shape(s"$name/$n", PlutusLanguage.V3, protocol, s"(program $version $term)", Vector(d))
    )

  private val table: Vector[Shape] =
    val p10 = v3("1.1.0", ProtocolMajor.Plomin)
    val p12 = v3("1.2.0", ProtocolMajor.Dijkstra)
    Vector(
      p10("unMapData", s"(lam d ${loop("[(builtin unMapData) d]")})", map(1), map(8000), 8000),
      p10(
        "unListData",
        s"(lam d ${loop("[(builtin unListData) d]")})",
        list(1),
        list(16000),
        16000
      ),
      p10(
        "unConstrData",
        s"(lam d ${loop("[(builtin unConstrData) d]")})",
        constr(1),
        constr(16000),
        16000
      ),
      p10(
        "listData",
        over("[(builtin unListData) d0]", "[(builtin listData) d]"),
        list(1),
        list(16000),
        16000
      ),
      p10(
        "mapData",
        over("[(builtin unMapData) d0]", "[(builtin mapData) d]"),
        map(1),
        map(8000),
        8000
      ),
      p10(
        "constrData",
        over("[(builtin unListData) d0]", "[[(builtin constrData) (con integer 0)] d]"),
        list(1),
        list(16000),
        16000
      ),
      p12(
        "case-on-Constr",
        s"(lam d ${loop(s"(case d (lam z $unit))")})",
        constr(1),
        constr(16000),
        16000
      )
    ).flatten

  private def mapLiteral(n: Int): String =
    Vector.fill(n)("(I 0, I 0)").mkString("(con data (Map [", ", ", "]))")

  private val retention: Vector[Shape] =
    val calls = Vector.fill(50)("[(builtin unMapData) d]").mkString(" ")
    val rounds =
      s"[[[(lam f [f f]) (lam self (lam n (lam acc (force [[[(force (builtin ifThenElse)) " +
        s"[[(builtin equalsInteger) n] (con integer 0)]] (delay $unit)] (delay [[[self self] " +
        s"[[(builtin subtractInteger) n] (con integer 1)]] (constr 0 $calls acc)])]))))] " +
        s"(con integer 700)] $unit]"
    val half = "(lam x (lam acc [[x x] [(lam r (lam z [acc r])) [(builtin unMapData) d]]]))"
    Vector(
      Shape(
        "retained-constr-700x50",
        PlutusLanguage.V3,
        ProtocolMajor.VanRossem,
        s"(program 1.1.0 [(lam d $rounds) ${mapLiteral(8000)}])",
        Vector.empty
      ),
      Shape(
        "retained-closures",
        PlutusLanguage.V3,
        ProtocolMajor.Plomin,
        s"(program 1.0.0 [(lam d [[$half $half] (lam q q)]) ${mapLiteral(8000)}])",
        Vector.empty
      )
    )

  private def verdict(shape: Shape): String =
    Verdict.evaluate(
      Verdict.context(shape.language, shape.protocol, shape.model),
      shape.script,
      shape.arguments,
      budget
    ) match
      case Left(unavailable) => s"no verdict: $unavailable"
      case Right(v @ Verdict.Ran(_, spent, _)) =>
        f"${v.stage}%-10s ${v.constructor.getOrElse("")}%-16s cpu ${spent.cpu.toLong}%,d mem ${spent.memory.toLong}%,d"
      case Right(v) => s"${v.stage} ${v.constructor.getOrElse("")}"

  // The timed region reads the system clock (EFF-6's exception for a measurement tool), and keeps
  // what the JVM throws, a heap the results do not fit among it, as the run's outcome.
  @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.SystemTimeAndUnsafeRun"))
  private def timed(shape: Shape): (String, Long) =
    val start = System.nanoTime
    val outcome =
      try verdict(shape)
      catch
        // a constant, since the heap may still be full when it is caught
        case _: OutOfMemoryError => "thrown: java.lang.OutOfMemoryError"
        case e: Throwable        => s"thrown: ${e.getClass.getName}: ${e.getMessage}"
    (outcome, (System.nanoTime - start) / 1000000)

  private def request(shape: Shape): Json = Json.obj(
    "id" -> Json.fromString(shape.id),
    "language" -> Json.fromInt(shape.language.id + 1),
    "protocol" -> Json.fromInt(shape.protocol.value),
    "script" -> Json.fromString(shape.script.hex),
    "costModel" -> Json.fromValues(shape.model.map(Json.fromLong)),
    "arguments" -> Json.fromValues(
      shape.arguments.map(d => Json.fromString(ScriptWriter.argument(d).hex))
    ),
    "cpu" -> Json.fromLong(budget.cpu.toLong),
    "memory" -> Json.fromLong(budget.memory.toLong),
    "verbose" -> Json.True
  )

  def main(args: Array[String]): Unit = args.toList match
    case "requests" :: file :: Nil =>
      val lines = (table ++ retention).map(request(_).noSpaces)
      Files.writeString(Path.of(file), lines.mkString("", "\n", "\n"))
      println(s"wrote ${lines.size} requests to $file")
    case "retention" :: only =>
      retention.filter(s => only.forall(_ == s.id)).foreach { shape =>
        val (outcome, ms) = timed(shape)
        println(f"${shape.id}%-24s $ms%,8d ms  $outcome")
      }
    case "table" :: rest =>
      val rounds = rest.headOption.flatMap(_.toIntOption).getOrElse(5)
      table.foreach { shape =>
        val (first, _) = timed(shape)
        val times = (1 to rounds).map(_ => timed(shape)._2).sorted
        println(f"${shape.id}%-22s median ${times(times.size / 2)}%,7d ms  $first")
      }
    case other => sys.error(s"usage: [table [rounds] | retention [id] | requests FILE], not $other")
