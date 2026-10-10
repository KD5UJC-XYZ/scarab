// SPDX-License-Identifier: Apache-2.0
package lab.vm

import lab.cbor.{Bytes, Cbor}
import lab.plutus.PlutusExecution as P
import java.nio.file.{Files, Path}

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
