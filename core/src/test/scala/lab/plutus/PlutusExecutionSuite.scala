// SPDX-License-Identifier: Apache-2.0
package lab.plutus

import lab.cbor.Bytes
import PlutusExecution.*

class PlutusExecutionSuite extends munit.FunSuite:
  private val script = Bytes(Vector.fill(369)(1.toByte))
  private val context = Bytes(Vector(2.toByte))
  private val model = Bytes(Vector(3.toByte))
  private val binding = Bytes(Vector.fill(32)(4.toByte))
  private def get(c: Bytes = context, m: Bytes = model, b: Bytes = binding): Request =
    request(script, c, m, ProfileBudget, b).fold(e => fail(e.toString), identity)

  test("state binding and each original execution payload affect request identity") {
    val initial = get()
    assertNotEquals(initial.requestDigest, get(b = Bytes(Vector.fill(32)(5.toByte))).requestDigest)
    assertNotEquals(initial.requestDigest, get(c = Bytes(Vector(6.toByte))).requestDigest)
    assertNotEquals(initial.requestDigest, get(m = Bytes(Vector(7.toByte))).requestDigest)
    val changed = request(
      Bytes(Vector.fill(369)(8.toByte)),
      context,
      model,
      ProfileBudget,
      binding
    ).toOption.get
    assertNotEquals(initial.requestDigest, changed.requestDigest)
    assertEquals(initial.requestDigest, get().requestDigest)
  }
  test("length framing distinguishes adjacent payload repartitioning") {
    val first = get(c = Bytes(Vector(1.toByte)), m = Bytes(Vector(2.toByte, 3.toByte)))
    val second = get(c = Bytes(Vector(1.toByte, 2.toByte)), m = Bytes(Vector(3.toByte)))
    assertNotEquals(first.requestDigest, second.requestDigest)
  }
  test("limits and nulls fail before hashing without conferring script validity") {
    assert(request(null, context, model, ProfileBudget, binding).isLeft)
    assert(request(Bytes(null), context, model, ProfileBudget, binding).isLeft)
    assert(request(script, Bytes.empty, model, ProfileBudget, binding).isLeft)
    assert(
      request(script, Bytes(Vector.fill(65537)(0.toByte)), model, ProfileBudget, binding).isLeft
    )
    assert(
      request(script, context, Bytes(Vector.fill(16385)(0.toByte)), ProfileBudget, binding).isLeft
    )
    assert(request(script, context, model, ProfileBudget, Bytes.empty).isLeft)
    assert(request(script, context, model, Budget(30000000, 100000), binding).isLeft)
    assert(request(script, context, model, null, binding).isLeft)
    assert(request(script, context, model, ProfileBudget, binding).isRight)
  }
