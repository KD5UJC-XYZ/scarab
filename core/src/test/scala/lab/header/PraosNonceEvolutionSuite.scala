// SPDX-License-Identifier: Apache-2.0
package lab.header

import lab.Blake2b
import lab.cbor.Bytes

class PraosNonceEvolutionSuite extends munit.FunSuite:
  import PraosNonceEvolution.*
  private def get[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def b(n: Int): Bytes = Bytes(Vector.fill(32)(n.toByte))
  private def h(n: Int): Nonce = Nonce.Hash(b(n))
  private val certificates = get(
    PraosCertificateState.Context
      .checked(b(9), b(8), 0, 2999, 129600, 60, Map(Bytes(Vector.fill(28)(1.toByte)) -> b(1)))
  )
  private val context = get(Context.checked(certificates, 500, 5, 1, 20))
  private val fields = Fields(h(1), h(2), h(3), None, h(4), h(5))
  private val output = Bytes(Vector.tabulate(64)(_.toByte))
  test("Conway window is rational ceil 4k/f and malformed/overflow contexts reject") {
    assertEquals(context.window, BigInt(400))
    assertEquals(get(Context.checked(certificates, 500, 1, 3, 10)).window, BigInt(14))
    assert(Context.checked(certificates, 0, 5, 1, 20).isLeft)
    assert(Context.checked(certificates, 500, 0, 1, 20).isLeft)
    assert(Context.checked(certificates, 500, 5, 0, 20).isLeft)
    assert(Context.checked(certificates, 500, 5, 21, 20).isLeft)
    assert(Context.checked(certificates, 500, (BigInt(1) << 64) - 1, 1, 20).isLeft)
  }
  test("nonce combination has neutral identity; VRF contribution is N-prefixed double hash") {
    assertEquals(combine(Nonce.Neutral, h(1)), h(1))
    assertEquals(combine(h(1), Nonce.Neutral), h(1))
    val expected =
      Blake2b.hash256.hash(Blake2b.hash256.hash(Bytes(Vector(0x4e.toByte) ++ output.value)))
    assertEquals(contribution(output), Nonce.Hash(expected))
    assertNotEquals(
      contribution(output),
      Nonce.Hash(Blake2b.hash256.hash(Bytes(Vector(0x4e.toByte) ++ output.value)))
    )
    assertNotEquals(combine(h(1), h(2)), combine(h(2), h(1)))
  }
  test("candidate updates strictly before boundary; evolving and LAB continue after freeze") {
    val before = update(context, 99, fields, h(6), output)
    val boundary = update(context, 100, fields, h(6), output)
    val after = update(context, 499, fields, h(6), output)
    assertEquals(before.candidate, before.evolving)
    assertEquals(boundary.candidate, fields.candidate)
    assertEquals(after.candidate, fields.candidate)
    assertNotEquals(after.evolving, fields.evolving)
    assertEquals(after.lab, h(6))
    assertEquals(after.epoch, fields.epoch)
  }
  test(
    "epoch tick uses OLD previous-block contribution then rotates LAB; no per-empty-epoch loop"
  ) {
    assertEquals(tick(context, 127, 499, fields), fields)
    val next = tick(context, 499, 500, fields)
    assertEquals(next.epoch, combine(fields.candidate, fields.lastEpochBlock))
    assertEquals(next.lastEpochBlock, fields.lab)
    assertEquals(next.previousEpoch, Some(fields.epoch))
    assertEquals(next.evolving, fields.evolving)
    assertEquals(next.candidate, fields.candidate)
    assertEquals(tick(context, 499, 1500, fields), next)
    val applied = update(context, 500, next, h(7), output)
    assertEquals(applied.lastEpochBlock, fields.lab)
    assertEquals(applied.lab, h(7))
    assertEquals(applied.epoch, next.epoch)
  }
  test("seed identity binds explicit unknown versus neutral and rejects malformed snapshots") {
    val cert = get(
      PraosCertificateState.seed(
        certificates,
        PraosCertificateState.Point(b(7), 127, 3),
        Map.empty,
        b(9)
      )
    )
    val unknown = get(seed(context, cert, fields, b(9)))
    val neutral = get(seed(context, cert, fields.copy(previousEpoch = Some(Nonce.Neutral)), b(9)))
    assertNotEquals(unknown.id, neutral.id)
    assert(seed(context, cert, fields.copy(epoch = Nonce.Hash(Bytes.empty)), b(9)).isLeft)
    assert(seed(context, cert, fields, Bytes.empty).isLeft)
    assert(applyHeader(context, unknown, null).isLeft)
    assert(undo(unknown, null).isLeft)
  }
