// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import ReferenceJson.Json as J
import SyntheticRewardProjection.*
import SyntheticBoundaryDifferential.*

class SyntheticBoundaryDifferentialSuite extends munit.FunSuite:
  private val input =
    val in = getClass.getResourceAsStream("/synthetic-boundary/cases.json")
    require(in != null)
    try Bytes.fromArray(in.readAllBytes())
    finally in.close()
  private lazy val result = output(input, InputHash).asInstanceOf[J.Obj]
  private def f(j: J, k: String): J = ReferenceJson.field(j, k)
  private def n(j: J): BigInt = BigInt(ReferenceJson.string(j))
  private def xs(j: J): Vector[J] = ReferenceJson.array(j)
  private def cases = xs(f(result, "cases"))
  private def named(id: String) = cases.find(c => f(c, "id") == str(id)).get
  private def reward(c: J, lane: String) = f(f(c, lane), "reward")
  private def synthetic(j: J) = encode(
    J.Obj(j.asInstanceOf[J.Obj].fields.updated("producer", str("synthetic-expectation")))
  )

  test("exact seven-case input is pinned; relabelled Scala checks comparator mechanics only") {
    assertEquals(sha256(input), InputHash)
    assertEquals(decodeInput(input, InputHash).size, 7)
    assertEquals(f(result, "producer"), str("scala"))
    assertEquals(
      compare(input, InputHash, synthetic(result), "synthetic-expectation"),
      Vector.empty
    )
    intercept[IllegalArgumentException](compare(input, InputHash, synthetic(result), "native"))
  }
  test("recorded native boundary golden matches unchanged finite Scala projections") {
    val in = getClass.getResourceAsStream("/synthetic-boundary/native-result.json")
    require(in != null, "missing curated native golden")
    val native =
      try Bytes.fromArray(in.readAllBytes())
      finally in.close()
    assertEquals(sha256(native), "0398f64618fe0d16377d8ff8bc9cd65991e6333ca03407c119b6b64e3dea5ed5")
    assertEquals(
      sha256(encode(result)),
      "c8bf5b2548142558e4bcef4027dbf9d0066cd556218f6c85a452332b5bb563e5"
    )
    assertEquals(compare(input, InputHash, native, "native"), Vector.empty)
    intercept[IllegalArgumentException](compare(input, InputHash, native, "synthetic-expectation"))
  }
  test("actual successor slots retain timing edges and pre-tick RUPD provenance") {
    val expected = Map(
      "early" -> "Absent",
      "start-edge" -> "Absent",
      "start" -> "Pulsing",
      "force-edge" -> "Pulsing",
      "late" -> "Complete",
      "old-pulsing" -> "Absent",
      "old-absent" -> "Absent"
    )
    cases.foreach { c =>
      val id = ReferenceJson.string(f(c, "id"))
      val tick = reward(c, "tick")
      assertEquals(f(tick, "phase"), str(expected(id)))
      // Labels identify lanes, so compare all remaining reward fields.
      def visible(j: J) = j.asInstanceOf[J.Obj].fields - "label"
      assertEquals(visible(tick), visible(f(c, "freshPreTickRupd")))
      if Set("start", "force-edge", "late").contains(id) then
        assert(visible(tick) != visible(f(c, "postBoundaryDiagnostic")))
    }
  }
  test(
    "nonzero application conserves pots and balances; SNAP, fees, counts and old-mark leadership rotate"
  ) {
    cases.foreach { c =>
      val before = f(c, "before"); val after = f(c, "boundary")
      val oldSnapshots = f(before, "snapshots"); val newSnapshots = f(after, "snapshots")
      assertEquals(f(newSnapshots, "set"), f(oldSnapshots, "mark"))
      assertEquals(f(newSnapshots, "go"), f(oldSnapshots, "set"))
      assertEquals(f(after, "leadership"), f(f(oldSnapshots, "mark"), "distribution"))
      assertEquals(f(after, "previousCounts"), f(before, "currentCounts"))
      assertEquals(f(after, "currentCounts"), arr(Vector.empty))
      assertEquals(f(newSnapshots, "fees"), f(f(after, "pots"), "fees"))
      Vector(before, after, f(c, "tick")).foreach { state =>
        val pots = f(state, "pots").asInstanceOf[J.Obj].fields.values.map(n).sum
        val balances = xs(f(state, "accounts")).map(a => n(f(a, "balance"))).sum
        assertEquals(pots + balances, BigInt(2200))
      }
      if f(c, "id") != str("old-absent") then
        assert(n(f(f(after, "pots"), "fees")) < n(f(f(before, "pots"), "fees")))
    }
    assertEquals(f(named("old-pulsing"), "boundary"), f(named("early"), "boundary"))
    assertEquals(
      f(f(named("old-absent"), "boundary"), "accounts"),
      f(f(named("old-absent"), "before"), "accounts")
    )
  }
  test(
    "comparator rejects identity, extra fields, ordering, numeric representation and reward mutations"
  ) {
    intercept[IllegalArgumentException](decodeInput(input, "0" * 64))
    intercept[IllegalArgumentException](
      decodeInput(Bytes(Vector.fill(65537)(32.toByte)), InputHash)
    )
    intercept[IllegalArgumentException](compare(input, InputHash, synthetic(result), "scala"))
    val wrong = J.Obj(result.fields.updated("inputSha256", str("0" * 64)))
    intercept[IllegalArgumentException](
      compare(input, InputHash, synthetic(wrong), "synthetic-expectation")
    )
    val first = cases.head.asInstanceOf[J.Obj]
    val mutations = Vector(
      J.Obj(first.fields.updated("extra", str("unexpected"))),
      J.Obj(first.fields.updated("slot", J.Lit("500"))),
      J.Obj(first.fields.updated("boundary", f(first, "before")))
    )
    mutations.foreach { changed =>
      val raw = synthetic(J.Obj(result.fields.updated("cases", arr(cases.updated(0, changed)))))
      val diffs = compare(input, InputHash, raw, "synthetic-expectation")
      assert(diffs.nonEmpty && diffs.size <= 16)
    }
    assert(
      compare(
        input,
        InputHash,
        synthetic(J.Obj(result.fields.updated("cases", arr(cases.reverse)))),
        "synthetic-expectation"
      ).nonEmpty
    )
  }
