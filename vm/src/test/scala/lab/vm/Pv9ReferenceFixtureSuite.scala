// SPDX-License-Identifier: Apache-2.0
package lab.vm

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scalus.uplc.{Constant, Term}
import scalus.uplc.builtin.Data.toCbor

class Pv9ReferenceFixtureSuite extends munit.FunSuite:
  private def resource(path: String): Array[Byte] =
    val in = getClass.getResourceAsStream(path)
    require(in != null, s"missing resource: $path")
    try in.readAllBytes()
    finally in.close()
  private val model = resource("/plutus-pv9/cost-model.json")
  private val source = resource("/plutus-pv9/spend.uplc")
  private val script = resource("/plutus-pv9-reference/script.cbor")
  private val context = resource("/plutus-pv9-reference/context.cbor")
  private val tree = resource("/plutus-pv9-reference/context-tree.json")
  private val expectedBytes = resource("/plutus-pv9-reference/reference-result.json")
  private def digest(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
  private def fixture =
    Pv9ReferenceFixture.admit(model, source, script, context).fold(fail(_), identity)

  test("one pinned reference context preserves every ordered Data field and exact CBOR") {
    assertEquals(context.length, 424)
    assertEquals(digest(context), Pv9ReferenceFixture.contextSha256)
    assertEquals(digest(tree), "ac3afb8c8248be6f7bd4e60c9bb3bbcb2702a2925269c0ef6ddcd16d4ce65694")
    val admitted = fixture
    assertEquals(admitted.orderedContextTree, ujson.read(new String(tree, UTF_8)))
    assert(admitted.contextCbor.sameElements(context))
    assertEquals(digest(admitted.contextCbor), Pv9ReferenceFixture.contextSha256)
  }

  test("the exact imported context agrees with reference Unit and CPU/memory budget") {
    assertEquals(
      digest(expectedBytes),
      "8f50edfabd884d5c4b168adb0fd3bf532e3b47e49d45ef7034298b55c806b7b4"
    )
    val expected = ujson.read(new String(expectedBytes, UTF_8))
    val budget = Budget(
      expected("evaluation")("cpuSteps").str.toLong,
      expected("evaluation")("memoryUnits").str.toLong
    )
    assertEquals(budget, Pv9ReferenceFixture.referenceBudget)
    val admitted = fixture
    val first = admitted.evaluate()
    first match
      case Outcome.Success(Term.Const(Constant.Unit, _), used) => assertEquals(used, budget)
      case other => fail(s"expected reference Unit and budget, got $other")
    assertEquals(admitted.evaluate(), first)
    assertEquals(admitted.evaluate(budget), first)
    assert(
      admitted.evaluate(budget.copy(cpu = budget.cpu - 1)).isInstanceOf[Outcome.BudgetExhausted]
    )
    assert(
      admitted
        .evaluate(budget.copy(memory = budget.memory - 1))
        .isInstanceOf[Outcome.BudgetExhausted]
    )
    assert(admitted.contextCbor.sameElements(context))
    // The reference records failures as CEK errors; do not relabel that evidence.
    assertEquals(expected("countingEvaluationPerformed").bool, false)
    val probes = expected("machineLimitExperiments").arr
    assertEquals(
      probes.map(_("name").str).toSeq,
      Seq("exact-consumed", "cpu-one-below", "memory-one-below")
    )
    probes.foreach { p =>
      assert(p("contextUnchanged").bool)
      assert(!p("ledgerTransactionReconstructed").bool)
    }
    assertEquals(probes.head("result"), expected("evaluation"))
    assertEquals(probes(1)("cpuLimit").str.toLong, budget.cpu - 1)
    assertEquals(probes(1)("memoryLimit").str.toLong, budget.memory)
    assertEquals(probes(2)("cpuLimit").str.toLong, budget.cpu)
    assertEquals(probes(2)("memoryLimit").str.toLong, budget.memory - 1)
    probes.drop(1).foreach { p =>
      assertEquals(p("result")("status").str, "evaluation-error")
      assertEquals(p("result")("category").str, "cek-error-unclassified")
      assertEquals(p("result")("consumedBudget"), ujson.Null)
    }
  }

  test("the old marker context is not the reference context, regardless of its budget") {
    val synthetic = SyntheticSpend.context(SyntheticSpend()).toOption.get.toCbor
    assert(!synthetic.sameElements(context))
    assertNotEquals(digest(synthetic), Pv9ReferenceFixture.contextSha256)
    assert(Pv9ReferenceFixture.admit(model, source, script, synthetic).isLeft)
  }

  test("every context and serialized-script byte mutation is rejected before parsing") {
    for index <- context.indices do
      val mutated = context.clone()
      mutated(index) = (mutated(index) ^ 1).toByte
      assertEquals(
        Pv9ReferenceFixture.admit(model, source, script, mutated).left.toOption,
        Some("unregistered fixture member bytes")
      )
    for index <- script.indices do
      val mutated = script.clone()
      mutated(index) = (mutated(index) ^ 1).toByte
      assertEquals(
        Pv9ReferenceFixture.admit(model, source, mutated, context).left.toOption,
        Some("unregistered fixture member bytes")
      )
    assertEquals(
      Pv9ReferenceFixture.admit(model, source, script, Array.fill(424)(0xff.toByte)).left.toOption,
      Some("unregistered fixture member bytes")
    )
  }

  test("mismatched members, lengths and model/source mutations cannot enter evaluation") {
    val members = Vector(model, source, script, context)
    for
      member <- members.indices;
      index <- Seq(0, members(member).length / 2, members(member).length - 1)
    do
      val changed = members.map(_.clone())
      changed(member)(index) = (changed(member)(index) ^ 1).toByte
      assertEquals(
        Pv9ReferenceFixture.admit(changed(0), changed(1), changed(2), changed(3)).left.toOption,
        Some("unregistered fixture member bytes")
      )
    assert(Pv9ReferenceFixture.admit(source, model, script, context).isLeft)
    assert(Pv9ReferenceFixture.admit(model, source, context, script).isLeft)
    assert(Pv9ReferenceFixture.admit(model ++ Array(32.toByte), source, script, context).isLeft)
    assert(Pv9ReferenceFixture.admit(model, source, script, context ++ Array(0.toByte)).isLeft)
    assert(Pv9ReferenceFixture.admit(model, source, script.dropRight(1), context).isLeft)
    assert(Pv9ReferenceFixture.admit(Array.fill(16385)(0.toByte), source, script, context).isLeft)
    assert(Pv9ReferenceFixture.admit(model, Array.fill(16385)(0.toByte), script, context).isLeft)
  }

  test("admission takes ownership of bytes and returned representations are isolated") {
    val copies = Vector(model, source, script, context).map(_.clone())
    val admitted =
      Pv9ReferenceFixture.admit(copies(0), copies(1), copies(2), copies(3)).toOption.get
    copies.foreach(bytes => java.util.Arrays.fill(bytes, 0.toByte))
    val returned = admitted.contextCbor
    java.util.Arrays.fill(returned, 0.toByte)
    val returnedTree = admitted.orderedContextTree
    returnedTree("constructor") = ujson.Str("999")
    assert(admitted.contextCbor.sameElements(context))
    assertEquals(admitted.orderedContextTree, ujson.read(new String(tree, UTF_8)))
    assert(admitted.evaluate().isInstanceOf[Outcome.Success])
  }

  test("fixture budgets stay inside the existing research ceiling") {
    val admitted = fixture
    Seq(
      Budget(-1, 1),
      Budget(1, -1),
      Budget(30000001, 1),
      Budget(1, 100001),
      Budget(Long.MaxValue, 1)
    ).foreach { limit =>
      assert(admitted.evaluate(limit).isInstanceOf[Outcome.InvalidInput])
    }
  }
