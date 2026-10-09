// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.Bytes
import ReferenceJson.Json as J
import EmptyGovernanceProjection.*
import SyntheticRewardProjection.{encode, sha256, str}
import java.nio.file.Path

class EmptyGovernanceDifferentialSuite extends munit.FunSuite:
  private def resource(): Bytes =
    val in = getClass.getResourceAsStream("/empty-governance/cases.json")
    require(in != null, "shared governance cases missing")
    try
      val b = in.readNBytes(65537)
      require(b.length <= 65536, "input resource bound")
      Bytes.fromArray(b)
    finally in.close()
  private val input = resource()
  private def field(j: J, keys: String*): J = keys.foldLeft(j)((a, k) => ReferenceJson.field(a, k))
  private def rows(j: J) = ReferenceJson.array(j)
  private def number(j: J) = BigInt(ReferenceJson.string(j))
  private def result(id: String): J = rows(field(output(input, InputPin), "cases"))
    .find(j => field(j, "id") == str(id))
    .get
  private def label(root: J, producer: String): Bytes =
    encode(J.Obj(root.asInstanceOf[J.Obj].fields.updated("producer", str(producer))))

  test("pinned finite matrix has twelve accepted and three explicit profile rejections") {
    assertEquals(sha256(input), InputPin)
    assertEquals(decodeInput(input, InputPin).size, 15)
    val cases = rows(field(output(input, InputPin), "cases"))
    assertEquals(cases.count(j => field(j, "status") == str("accepted")), 12)
    assertEquals(cases.count(j => field(j, "status") == str("profile-rejected")), 3)
    cases.filter(j => field(j, "status") == str("accepted")).foreach { c =>
      assertEquals(number(field(c, "after", "dormant")), number(field(c, "before", "dormant")) + 1)
      assertEquals(field(c, "before", "accounts"), field(c, "after", "accounts"))
      assertEquals(field(c, "before", "pots"), field(c, "after", "pots"))
      assertEquals(number(field(c, "diagnostic", "freshPulseSize")), BigInt(1))
      assertEquals(number(field(c, "diagnostic", "freshSeedTreasury")), BigInt(17))
      assertEquals(number(field(c, "after", "completed", "ratify", "enact", "treasury")), BigInt(0))
      assertEquals(
        field(c, "diagnostic", "newMarkPools"),
        field(c, "after", "completed", "snapshot", "pools")
      )
      val pots = field(c, "after", "pots")
      val potSum = Vector("treasury", "reserves", "fees", "deposits", "donations")
        .map(k => number(field(pots, k)))
        .sum
      val rewards = rows(field(c, "after", "accounts")).map(a => number(field(a, "rewards"))).sum
      assertEquals(potSum + rewards, BigInt(2200))
    }
  }

  test("normalized completion uses registry membership and retains distinct key/script accounts") {
    for id <- Vector("credential", "abstain", "no-confidence", "combined") do
      val distribution =
        rows(field(result(id), "after", "completed", "snapshot", "drepDistribution"))
      assertEquals(distribution.size, 1)
      assertEquals(number(field(distribution.head, "coin")), BigInt(200))
    for id <- Vector("baseline", "registry-only") do
      assertEquals(
        rows(field(result(id), "after", "completed", "snapshot", "drepDistribution")).size,
        0
      )
    val accounts = rows(field(result("credential"), "after", "accounts"))
    assert(ReferenceJson.string(field(accounts.head, "credential")).startsWith("script:"))
    assert(ReferenceJson.string(field(accounts(1), "credential")).startsWith("key:"))
    val credentialCase = decodeInput(input, InputPin).find(_.id == "credential").get
    val unregistered = project(credentialCase.copy(registered = false))
    assertEquals(
      rows(field(unregistered, "after", "completed", "snapshot", "drepDistribution")).size,
      0
    )
  }

  test("committee reconciliation ignores member expiry and parameters rotate independently") {
    val expired = result("expired")
    assertEquals(
      field(expired, "before", "committeeState"),
      field(expired, "after", "committeeState")
    )
    val orphan = result("orphan")
    assertEquals(rows(field(orphan, "before", "committeeState")).size, 2)
    assertEquals(rows(field(orphan, "after", "committeeState")).size, 1)
    assertEquals(
      field(result("resigned"), "before", "committeeState"),
      field(result("resigned"), "after", "committeeState")
    )
    assertEquals(field(result("previous-only"), "before", "parameters", "previousFee"), str("45"))
    assertEquals(field(result("previous-only"), "after", "parameters", "previousFee"), str("44"))
    assertEquals(
      field(result("potential-only"), "after", "parameters", "future"),
      str("PotentialNone")
    )
  }

  test(
    "strict canonical input rejects wrong pin, unknown fields, duplicates and numeric coercion"
  ) {
    intercept[IllegalArgumentException](decodeInput(input, "0" * 64))
    val root = ReferenceJson.parse(input).asInstanceOf[J.Obj]
    val extra = encode(J.Obj(root.fields.updated("extra", str("x"))))
    intercept[IllegalArgumentException](decodeInput(extra, sha256(extra)))
    val duplicateCases = rows(root.fields("cases"))
    val duplicate = encode(
      J.Obj(root.fields.updated("cases", J.Arr(duplicateCases.updated(1, duplicateCases.head))))
    )
    intercept[IllegalArgumentException](decodeInput(duplicate, sha256(duplicate)))
    val first = duplicateCases.head.asInstanceOf[J.Obj]
    val numeric = encode(
      J.Obj(
        root.fields.updated(
          "cases",
          J.Arr(duplicateCases.updated(0, J.Obj(first.fields.updated("dormant", J.Num("0")))))
        )
      )
    )
    intercept[IllegalArgumentException](decodeInput(numeric, sha256(numeric)))
    val spaced = Bytes(Vector(32.toByte) ++ input.value)
    intercept[IllegalArgumentException](decodeInput(spaced, sha256(spaced)))
  }

  test("comparison rejects envelope substitutions, nested extras and array reordering") {
    // Self-derived bytes exercise comparison mechanics only; they are not independent expectations.
    val expected = output(input, InputPin)
    val self = label(expected, "synthetic-expectation")
    assertEquals(compare(input, InputPin, self, "synthetic-expectation"), Vector.empty)
    intercept[IllegalArgumentException](compare(input, InputPin, self, "native"))
    val root = ReferenceJson.parse(self).asInstanceOf[J.Obj]
    val wrongPin = encode(J.Obj(root.fields.updated("inputSha256", str("0" * 64))))
    intercept[IllegalArgumentException](compare(input, InputPin, wrongPin, "synthetic-expectation"))
    val cases = rows(root.fields("cases"))
    val first = cases.head.asInstanceOf[J.Obj]
    val nested = encode(
      J.Obj(
        root.fields.updated(
          "cases",
          J.Arr(cases.updated(0, J.Obj(first.fields.updated("internalHash", str("unexpected")))))
        )
      )
    )
    assert(compare(input, InputPin, nested, "synthetic-expectation").nonEmpty)
    val reordered = encode(J.Obj(root.fields.updated("cases", J.Arr(cases.reverse))))
    assert(compare(input, InputPin, reordered, "synthetic-expectation").nonEmpty)
  }

  test("recorded native governance golden matches the finite normalized projection") {
    val in = getClass.getResourceAsStream("/empty-governance/native-result.json")
    require(in != null, "missing curated native governance golden")
    val native =
      try Bytes.fromArray(in.readNBytes(1048577))
      finally in.close()
    assert(native.value.size <= 1048576)
    assertEquals(sha256(native), "df3cda77eba7238b36631890d3fb2f17e979b3277b9db1dbd1e239f7587bed05")
    assertEquals(
      sha256(encode(output(input, InputPin))),
      "1b8c233ff9dc585f03740d37875fef5d3a0788568fab815acce47b1245fb4b3b"
    )
    assertEquals(compare(input, InputPin, native, "native"), Vector.empty)
    intercept[IllegalArgumentException](compare(input, InputPin, native, "synthetic-expectation"))
  }

  // Explicitly opt in only after a separately produced native artifact is available.
  sys.env.get("EMPTY_GOVERNANCE_NATIVE_RESULT").foreach { path =>
    test("externally supplied native result exactly matches the full normalized projection") {
      sys.env.get("EMPTY_GOVERNANCE_SCALA_OUTPUT").foreach { destination =>
        java.nio.file.Files.write(
          Path.of(destination),
          encode(output(input, InputPin)).toArray,
          java.nio.file.StandardOpenOption.CREATE_NEW,
          java.nio.file.StandardOpenOption.WRITE
        )
      }
      val raw = SyntheticRewardDifferential.read(Path.of(path), 1048576)
      assertEquals(compare(input, InputPin, raw, "native"), Vector.empty)
    }
  }
