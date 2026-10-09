// SPDX-License-Identifier: Apache-2.0
package lab
import lab.cbor.Bytes
import ReferenceJson.Json as J
import SyntheticRewardProjection.*
import SyntheticRewardDifferential.*

class SyntheticRewardDifferentialSuite extends munit.FunSuite:
  private def resource(name: String): Bytes =
    val in = getClass.getResourceAsStream("/synthetic-reward/" + name)
    require(in != null, "missing synthetic resource")
    try Bytes.fromArray(in.readAllBytes())
    finally in.close()
  private val input = resource("cases-proposed.json")
  private val expected = resource("expectations-proposed.json")
  private val pin = sha256(input)
  test("all finite case projections match explicitly synthetic expectations, not native output") {
    assertEquals(decodeInput(input, pin).size, 11)
    assertEquals(compare(input, pin, expected, "synthetic-expectation"), Vector.empty)
    intercept[IllegalArgumentException](compare(input, pin, expected, "native"))
  }
  test("recorded self-generated native golden matches the exact finite monetary projection") {
    val native = resource("native-result.json")
    assertEquals(pin, "14d229647bcf67312d5979b4632d137c23177ac3dcf20356963b6cf193e97fb9")
    assertEquals(
      sha256(native),
      "a1eb9842d864eb318723ae29792ba9aaf4d31410ae9f420ac51529c5507ddb74"
    )
    assertEquals(compare(input, pin, native, "native"), Vector.empty)
    intercept[IllegalArgumentException](compare(input, pin, native, "synthetic-expectation"))
  }
  test("bounded exact input schema and canonical bytes reject tampering and duplicates") {
    intercept[IllegalArgumentException](decodeInput(input, "0" * 64))
    val parsed = ReferenceJson.parse(input).asInstanceOf[J.Obj]
    val extra = encode(J.Obj(parsed.fields.updated("unexpected", str("x"))))
    intercept[IllegalArgumentException](decodeInput(extra, sha256(extra)))
    val spaced = Bytes.fromArray(
      (" " + new String(input.toArray, java.nio.charset.StandardCharsets.UTF_8))
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    intercept[IllegalArgumentException](decodeInput(spaced, sha256(spaced)))
    val huge = Bytes(Vector.fill(65537)(32.toByte))
    intercept[IllegalArgumentException](decodeInput(huge, sha256(huge)))
    val duplicate = Bytes.fromArray(
      "{\"schema\":\"a\",\"schema\":\"b\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    intercept[IllegalArgumentException](decodeInput(duplicate, sha256(duplicate)))
  }
  test("result binding, precision, phase/order/field changes produce bounded mismatch paths") {
    val root = ReferenceJson.parse(expected).asInstanceOf[J.Obj]
    val wrong = encode(J.Obj(root.fields.updated("inputSha256", str("0" * 64))))
    intercept[IllegalArgumentException](compare(input, pin, wrong, "synthetic-expectation"))
    val cases = root.fields("cases").asInstanceOf[J.Arr].values
    val first = cases.head.asInstanceOf[J.Obj]
    val steps = first.fields("steps").asInstanceOf[J.Arr].values
    val initial = steps.head.asInstanceOf[J.Obj]
    val remaining = initial.fields("remaining").asInstanceOf[J.Arr].values
    val mutated = J.Obj(initial.fields.updated("remaining", J.Arr(remaining.reverse)))
    val changedCase = J.Obj(first.fields.updated("steps", J.Arr(steps.updated(0, mutated))))
    val changed = encode(J.Obj(root.fields.updated("cases", J.Arr(cases.updated(0, changedCase)))))
    assert(compare(input, pin, changed, "synthetic-expectation").exists(_.contains("remaining")))
    val extras = encode(
      J.Obj(
        root.fields.updated(
          "cases",
          J.Arr(cases.updated(0, J.Obj(first.fields.updated("internalHash", str("x")))))
        )
      )
    )
    assert(compare(input, pin, extras, "synthetic-expectation").nonEmpty)
    val alteredText = new String(expected.toArray, java.nio.charset.StandardCharsets.UTF_8)
      .replace("\"deltaR\":\"802\"", "\"deltaR\":802")
    assert(
      compare(
        input,
        pin,
        Bytes.fromArray(alteredText.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        "synthetic-expectation"
      ).nonEmpty
    )
  }

  test("direct start cannot silently stand in for late native RUPD dispatch") {
    val c = decodeInput(input, pin).head
    intercept[IllegalArgumentException](
      project(c.copy(actions = Vector(Action("start201", "start", 201))))
    )
  }
