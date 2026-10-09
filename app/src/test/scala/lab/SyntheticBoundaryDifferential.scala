// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import ReferenceJson.Json as J
import SyntheticRewardProjection.*
import java.nio.file.{Files, Path, StandardOpenOption}

/** Exact finite synthetic contract. Native provenance is external to producer labels. */
private[lab] object SyntheticBoundaryDifferential:
  val InputSchema = "synthetic-boundary-cases-v1"
  val ResultSchema = "synthetic-boundary-result-v1"
  val InputHash = "b3dc3877984319078273937888395c51354dd293442005c9830c10ff3d36fc06"
  final case class Case(id: String, slot: BigInt, oldRewardPhase: String)
  private val declared = Vector(
    Case("early", 500, "Complete"),
    Case("start-edge", 600, "Complete"),
    Case("start", 601, "Complete"),
    Case("force-edge", 700, "Complete"),
    Case("late", 701, "Complete"),
    Case("old-pulsing", 500, "Pulsing"),
    Case("old-absent", 500, "Absent")
  )
  private def fields(j: J, names: String*): Map[String, J] = j match
    case J.Obj(fs) => require(fs.keySet == names.toSet, "unexpected/missing synthetic fields"); fs
    case _         => throw new IllegalArgumentException("synthetic object required")
  private def text(j: J): String = ReferenceJson.string(j)
  def decodeInput(raw: Bytes, expectedSha256: String): Vector[Case] =
    require(
      raw != null && raw.size <= 65536 && expectedSha256 == InputHash && sha256(raw) == InputHash,
      "exact bounded declared synthetic input required"
    )
    val parsed = ReferenceJson.parse(raw)
    require(encode(parsed) == raw, "canonical synthetic input bytes required")
    val root = fields(parsed, "schema", "profile", "cases")
    require(
      text(root("schema")) == InputSchema && text(
        root("profile")
      ) == "reward-balances-only-2200-v1",
      "input schema/profile"
    )
    val cases = ReferenceJson.array(root("cases")).map { value =>
      val f = fields(value, "id", "slot", "oldRewardPhase")
      val slot = text(f("slot"))
      require(slot.matches("0|[1-9][0-9]{0,3}"), "canonical bounded slot")
      Case(text(f("id")), BigInt(slot), text(f("oldRewardPhase")))
    }
    require(cases == declared, "exact declared case order and geometry")
    cases
  def output(input: Bytes, inputHash: String): J =
    obj(
      "schema" -> str(ResultSchema),
      "inputSha256" -> str(inputHash),
      "producer" -> str("scala"),
      "cases" -> arr(decodeInput(input, inputHash).map { c =>
        SyntheticBoundaryProjection.project(c.id, c.slot, c.oldRewardPhase)
      })
    )

  /** Exact bounded projection equality also rejects every undeclared nested field and array order.
    */
  def compare(
      input: Bytes,
      inputHash: String,
      result: Bytes,
      requiredProducer: String
  ): Vector[String] =
    require(
      Set("native", "synthetic-expectation").contains(requiredProducer),
      "explicit comparison provenance required"
    )
    require(result != null && result.size <= 1048576, "synthetic result byte bound")
    val actual = ReferenceJson.parse(result)
    val root = fields(actual, "schema", "inputSha256", "producer", "cases")
    require(text(root("producer")) == requiredProducer, "comparison producer mismatch")
    require(
      text(root("schema")) == ResultSchema && text(root("inputSha256")) == inputHash,
      "result input/schema binding mismatch"
    )
    val expected =
      output(input, inputHash).asInstanceOf[J.Obj].fields.updated("producer", str(requiredProducer))
    val diffs = Vector.newBuilder[String]
    var count = 0
    def diff(a: J, b: J, path: String): Unit = if a != b && count < 16 then
      (a, b) match
        case (J.Obj(x), J.Obj(y)) if x.keySet == y.keySet =>
          x.keys.toVector.sorted.foreach(k => diff(x(k), y(k), path + "." + k))
        case (J.Arr(x), J.Arr(y)) if x.size == y.size =>
          x.indices.foreach(i => diff(x(i), y(i), s"$path[$i]"))
        case _ => diffs += path; count += 1
    diff(J.Obj(expected), actual, "result")
    val errors = diffs.result()
    errors
  def read(path: Path, limit: Long): Bytes =
    require(
      Files.isRegularFile(path) && Files.size(path) <= limit,
      "bounded synthetic regular file required"
    )
    val in = Files.newInputStream(path)
    val raw =
      try in.readNBytes((limit + 1).toInt)
      finally in.close()
    require(raw.length <= limit, "synthetic file grew past bound")
    Bytes.fromArray(raw)

/** Explicit test-classpath command; reads synthetic files only, never executes native tools. */
object SyntheticBoundaryDifferentialMain:
  def main(args: Array[String]): Unit =
    require(
      args.length == 3 || args.length == 5,
      "input expected-sha256 scala-output [native|synthetic-expectation result-file]"
    )
    val input = SyntheticBoundaryDifferential.read(Path.of(args(0)), 65536)
    val hash = args(1)
    val result = SyntheticRewardProjection.encode(SyntheticBoundaryDifferential.output(input, hash))
    Files.write(
      Path.of(args(2)),
      result.toArray,
      StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE
    )
    if args.length == 5 then
      val actual = SyntheticBoundaryDifferential.read(Path.of(args(4)), 1048576)
      val errors = SyntheticBoundaryDifferential.compare(input, hash, actual, args(3))
      require(errors.isEmpty, "boundary projection mismatch: " + errors.mkString(", "))
      println(
        s"${args(3)} projection agrees for explicitly synthetic input $hash; no general native parity/runtime claim"
      )
    else println(s"Scala synthetic projection written for $hash; no native comparison performed")
