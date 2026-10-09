// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import ReferenceJson.Json as J
import SyntheticRewardProjection.*
import java.nio.file.{Files, Path, StandardOpenOption}

/** One bounded offline synthetic matrix; source identity is external input bytes, never ledger IDs.
  */
private[lab] object SyntheticRewardDifferential:
  val InputSchema = "synthetic-reward-cases-v1"
  val ResultSchema = "synthetic-reward-result-v1"
  private def fields(j: J, names: String*): Map[String, J] = j match
    case J.Obj(fs) => require(fs.keySet == names.toSet, "unexpected/missing synthetic fields"); fs
    case _         => throw new IllegalArgumentException("synthetic object required")
  private def text(j: J): String = ReferenceJson.string(j)
  private def uint(j: J, max: BigInt): BigInt =
    val s = text(j); require(s.matches("0|[1-9][0-9]{0,19}"), "canonical decimal string required")
    val n = BigInt(s); require(n <= max, "synthetic integer bound"); n
  private def bool(j: J): Boolean = j match
    case J.Lit("true")  => true
    case J.Lit("false") => false
    case _              => throw new IllegalArgumentException("synthetic boolean required")
  private def array(j: J, max: Int): Vector[J] =
    val xs = ReferenceJson.array(j); require(xs.size <= max, "synthetic array bound"); xs
  private def label(j: J): String =
    val s = text(j); require(s.matches("[a-zA-Z][a-zA-Z0-9-]{0,47}"), "synthetic label shape"); s
  def decodeInput(raw: Bytes, expectedSha256: String): Vector[Case] =
    require(
      raw != null && raw.size <= 65536 && expectedSha256.matches("[0-9a-f]{64}") && sha256(
        raw
      ) == expectedSha256,
      "synthetic input byte identity/bound"
    )
    val parsed = ReferenceJson.parse(raw)
    require(encode(parsed) == raw, "canonical synthetic input bytes required")
    val root = fields(parsed, "schema", "profile", "cases")
    require(
      text(root("schema")) == InputSchema && text(
        root("profile")
      ) == "five-credentials-two-pools-v1",
      "synthetic profile/schema"
    )
    val cases = array(root("cases"), 32).map { j =>
      val f = fields(
        j,
        "id",
        "fees",
        "blocksA",
        "blocksB",
        "empty",
        "window",
        "omitScriptAtFreeze",
        "applyRegistration",
        "actions"
      )
      val acts = array(f("actions"), 32).map { j =>
        val a = fields(j, "label", "op", "slot"); val op = text(a("op"))
        require(Set("start", "pulse", "force", "rupdFresh").contains(op), "synthetic action")
        Action(label(a("label")), op, uint(a("slot"), 499))
      }
      require(
        acts.nonEmpty && acts.map(_.label).distinct.size == acts.size,
        "synthetic action labels"
      )
      val window = uint(f("window"), 100);
      require(window == 80 || window == 100, "declared synthetic/derived windows only")
      Case(
        label(f("id")),
        uint(f("fees"), 1000),
        uint(f("blocksA"), 3),
        uint(f("blocksB"), 3),
        bool(f("empty")),
        window,
        bool(f("omitScriptAtFreeze")),
        bool(f("applyRegistration")),
        acts
      )
    }
    require(
      cases.nonEmpty && cases.map(_.id).distinct.size == cases.size,
      "synthetic case identities"
    )
    cases
  def output(input: Bytes, inputHash: String): J =
    val cases = decodeInput(input, inputHash)
    obj(
      "schema" -> str(ResultSchema),
      "inputSha256" -> str(inputHash),
      "producer" -> str("scala"),
      "cases" -> arr(cases.map(project))
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
    if errors.isEmpty then verifyConservation(actual)
    errors
  private def verifyConservation(root: J): Unit =
    def field(j: J, k: String) = ReferenceJson.field(j, k)
    def integer(j: J): BigInt =
      val s = text(j);
      require(s.matches("-?(0|[1-9][0-9]{0,19})") && s != "-0", "monetary integer shape"); BigInt(s)
    def amountSum(j: J) = ReferenceJson.array(j).map(x => integer(field(x, "amount"))).sum
    ReferenceJson.array(field(root, "cases")).foreach { c =>
      ReferenceJson.array(field(c, "steps")).foreach { step =>
        val done = field(step, "complete")
        if done != J.Lit("null") then
          val total = amountSum(field(done, "rewards"))
          require(
            total + integer(field(done, "deltaT")) + integer(field(done, "deltaR")) + integer(
              field(done, "deltaF")
            ) == 0,
            "completed monetary conservation"
          )
      }
      val app = field(c, "application")
      if app != J.Lit("null") then
        val pots = field(app, "pots")
        val potSum = Vector("treasury", "reserves", "fees").map(k => integer(field(pots, k))).sum
        val fees = integer(field(field(field(c, "initial"), "allocation"), "fees"))
        require(
          potSum + amountSum(field(app, "balances")) == 1000 + fees,
          "application tracked conservation"
        )
        require(
          amountSum(field(app, "unregistered")) == integer(field(app, "totalUnregistered")),
          "unregistered total"
        )
        require(
          amountSum(field(app, "registered")) == amountSum(field(app, "credited")),
          "registered credit total"
        )
    }

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
object SyntheticRewardDifferentialMain:
  def main(args: Array[String]): Unit =
    require(
      args.length == 3 || args.length == 5,
      "input expected-sha256 scala-output [native|synthetic-expectation result-file]"
    )
    val input = SyntheticRewardDifferential.read(Path.of(args(0)), 65536)
    val hash = args(1)
    val result = SyntheticRewardProjection.encode(SyntheticRewardDifferential.output(input, hash))
    Files.write(
      Path.of(args(2)),
      result.toArray,
      StandardOpenOption.CREATE_NEW,
      StandardOpenOption.WRITE
    )
    if args.length == 5 then
      val actual = SyntheticRewardDifferential.read(Path.of(args(4)), 1048576)
      val errors = SyntheticRewardDifferential.compare(input, hash, actual, args(3))
      require(errors.isEmpty, "reward projection mismatch: " + errors.mkString(", "))
      println(
        s"${args(3)} projection agrees for explicitly synthetic input $hash; no general native parity/runtime claim"
      )
    else println(s"Scala synthetic projection written for $hash; no native comparison performed")
