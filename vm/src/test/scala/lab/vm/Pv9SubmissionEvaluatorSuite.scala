// SPDX-License-Identifier: Apache-2.0
package lab.vm

import lab.cbor.{Bytes, Cbor}
import lab.plutus.PlutusExecution as P
import java.nio.file.{Files, Path}
import scalus.uplc.builtin.Data
import scalus.uplc.builtin.Data.toCbor
import scalus.cardano.onchain.plutus.prelude.{List as PList}

class Pv9SubmissionEvaluatorSuite extends munit.FunSuite:
  private def read(path: String) = Bytes.fromArray(Files.readAllBytes(Path.of(path)))
  private def hex(text: String) = Bytes.fromHex(text).fold(fail(_), identity)
  private val script = read("vm/src/test/resources/plutus-pv9-reference/script.cbor")
  private val model = read("vm/src/main/resources/plutus-pv9/cost-model.json")
  private val context = read("vm/src/test/resources/plutus-pv9-reference/context.cbor")
  private val binding = Bytes(Vector.fill(32)(1.toByte))
  private def request(c: Bytes = context, s: Bytes = script, m: Bytes = model, b: Bytes = binding) =
    P.request(s, c, m, P.ProfileBudget, b).fold(e => fail(e.toString), identity)
  private val rows = ujson
    .read(Files.readString(Path.of("fixtures/plutus-pv9-translator/vectors.json")))("vectors")
    .arr
    .toVector

  rows.foreach { row =>
    test(s"${row("name").str}: typed fixed-budget PV9 result agrees with retained observation") {
      val ref = row("reference")
      val req = request(c = hex(ref("contextDataCborHex").str))
      val result = Pv9SubmissionEvaluator.evaluate(req)
      if ref("evaluation")("status").str == "v3-validation-success" then
        val success = result.fold(e => fail(e.toString), identity)
        assertEquals(
          success,
          P.Success(
            req.requestDigest,
            req.scriptSHA256,
            req.contextSHA256,
            req.modelSHA256,
            req.declared,
            P.Budget(
              BigInt(ref("evaluation")("memoryUnits").str),
              BigInt(ref("evaluation")("cpuSteps").str)
            )
          )
        )
      else
        assertEquals(ref("evaluation")("category").str, "cek-error-unclassified")
        assertEquals(result, Left(P.Failure.ScriptFailure("explicit-error")))
    }
  }

  test("script/model byte mutations reject before evaluation") {
    assert(
      Pv9SubmissionEvaluator
        .evaluate(request(s = Bytes(script.value.updated(0, 0.toByte))))
        .left
        .toOption
        .exists(_.isInstanceOf[P.Failure.Unsupported])
    )
    assert(
      Pv9SubmissionEvaluator
        .evaluate(request(m = Bytes(model.value.updated(0, 0.toByte))))
        .left
        .toOption
        .exists(_.isInstanceOf[P.Failure.Unsupported])
    )
    assert(P.request(script, context, model, P.Budget(99999, 30000000), binding).isLeft)
  }
  test("malformed, deep and noncanonical context bytes fail closed") {
    val definite =
      Cbor.encode(Cbor.decode(context).fold(fail(_), identity).value).fold(fail(_), identity)
    for raw <- Vector(
        Bytes(Vector(0xff.toByte)),
        Bytes(Vector.fill(34)(0x81.toByte) :+ 0.toByte),
        definite
      )
    do
      assert(
        Pv9SubmissionEvaluator
          .evaluate(request(c = raw))
          .left
          .toOption
          .exists(_.isInstanceOf[P.Failure.MalformedInput])
      )
    assertEquals(
      Pv9SubmissionEvaluator.evaluate(null),
      Left(P.Failure.MalformedInput("execution request required"))
    )
  }
  test("success binds the full caller digest and immutable input bytes") {
    val source = context.toArray
    val first = request(c = Bytes.fromArray(source))
    source(0) = 0
    val second = request(b = Bytes(Vector.fill(32)(2.toByte)))
    assertNotEquals(first.requestDigest, second.requestDigest)
    val a = Pv9SubmissionEvaluator.evaluate(first).fold(e => fail(e.toString), identity)
    val b = Pv9SubmissionEvaluator.evaluate(second).fold(e => fail(e.toString), identity)
    assertEquals(a.requestDigest, first.requestDigest)
    assertEquals(b.requestDigest, second.requestDigest)
    assertEquals(a.consumed, b.consumed)
    assertEquals(a.contextSHA256, b.contextSHA256)
  }

  test(
    "request byte limits reject null, empty, oversized and wrong-width members with typed errors"
  ) {
    val malformed = Left(P.Failure.MalformedInput("execution request byte limits"))
    for bad <- Vector(
        null,
        Bytes.empty,
        Bytes(Vector.fill(368)(0.toByte)),
        Bytes(Vector.fill(370)(0.toByte))
      )
    do assertEquals(P.request(bad, context, model, P.ProfileBudget, binding), malformed)
    for bad <- Vector(null, Bytes.empty, Bytes(Vector.fill(65537)(0.toByte))) do
      assertEquals(P.request(script, bad, model, P.ProfileBudget, binding), malformed)
    for bad <- Vector(null, Bytes.empty, Bytes(Vector.fill(16385)(0.toByte))) do
      assertEquals(P.request(script, context, bad, P.ProfileBudget, binding), malformed)
    for bad <- Vector(
        null,
        Bytes.empty,
        Bytes(Vector.fill(31)(0.toByte)),
        Bytes(Vector.fill(33)(0.toByte))
      )
    do assertEquals(P.request(script, context, model, P.ProfileBudget, bad), malformed)
    for bad <- Vector(
        null,
        P.Budget(-1, 30000000),
        P.Budget(100000, -1),
        P.Budget(BigInt(1) << 128, BigInt(1) << 128),
        P.Budget(100000, 29999999)
      )
    do
      assertEquals(
        P.request(script, context, model, bad, binding),
        Left(P.Failure.Unsupported("execution request requires the fixed profile budget"))
      )
  }

  test("same-length script/model mismatches return the exact unsupported failure") {
    val expected = Left(P.Failure.Unsupported("registered PV9 script/model/budget required"))
    assertEquals(
      Pv9SubmissionEvaluator.evaluate(request(s = Bytes(script.value.updated(0, 0.toByte)))),
      expected
    )
    assertEquals(
      Pv9SubmissionEvaluator.evaluate(request(m = Bytes(model.value.updated(0, 0.toByte)))),
      expected
    )
  }

  test("valid CBOR outside Data differs from valid Data rejected during script execution") {
    // Bool is legal core CBOR but not Plutus Data, so decodeData must reject before CEK.
    assertEquals(
      Pv9SubmissionEvaluator.evaluate(request(c = Bytes(Vector(0xf5.toByte)))),
      Left(P.Failure.MalformedInput("invalid context Data"))
    )
    // Canonical integer Data reaches the registered script, whose unConstrData builtin rejects it.
    // This is a VM-port negative input, never a context admitted by PlutusContext.derive.
    assertEquals(
      Pv9SubmissionEvaluator.evaluate(request(c = Bytes(Vector(0.toByte)))),
      Left(P.Failure.ScriptFailure("builtin-error"))
    )
    assert(
      Pv9SubmissionEvaluator
        .evaluate(request(c = Bytes(context.value :+ 0.toByte)))
        .left
        .toOption
        .exists(_.isInstanceOf[P.Failure.MalformedInput])
    )
  }

  test(
    "fixed-budget exhaustion is reachable through bounded adversarial Data without changing script or limits"
  ) {
    // Enlarge both equal datum occurrences with an extra list. This deliberately violates the
    // ledger context grammar: only the low-level VM port is exercised, not transaction admission.
    // No native/reference evaluation of this new negative vector is claimed.
    val original = Data.fromCbor(context.toArray)
    def fields(d: Data): List[Data] = d match
      case Data.Constr(_, xs) => xs.toScalaList
      case _                  => fail("fixture constructor expected")
    val datum = fields(fields(fields(original)(2))(1)).head
    val enlarged = datum match
      case Data.Constr(tag, xs) =>
        Data.Constr(
          tag,
          PList.from(
            xs.toScalaList :+
              Data.List(PList.from(List.fill(512)(Data.I(BigInt(0)))))
          )
        )
      case _ => fail("fixture datum expected")
    def replace(d: Data): Data =
      if d == datum then enlarged
      else
        d match
          case Data.Constr(tag, xs) => Data.Constr(tag, PList.from(xs.toScalaList.map(replace)))
          case Data.List(xs)        => Data.List(PList.from(xs.toScalaList.map(replace)))
          case Data.Map(xs) =>
            Data.Map(PList.from(xs.toScalaList.map((k, v) => replace(k) -> replace(v))))
          case other => other
    val adverse = Bytes.fromArray(replace(original).toCbor)
    assert(Cbor.decode(adverse, Cbor.Limits(65536, 32, 4096, 4096)).isRight)
    val req = request(c = adverse)
    assertEquals(req.declared, P.ProfileBudget)
    assertEquals(req.scriptPayload, script)
    assertEquals(req.modelText, model)
    assertEquals(Pv9SubmissionEvaluator.evaluate(req), Left(P.Failure.BudgetExhausted))
  }
