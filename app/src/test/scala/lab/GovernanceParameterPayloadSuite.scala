// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{FeeSize, MinimumOutput}

/** Hand-built exact source-shape vectors, not captured native encodings or an admission oracle. */
class GovernanceParameterPayloadSuite extends munit.FunSuite:
  private val P = GovernanceParameterPayload
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def sha(b: Bytes) = ClusterHeaderObservation.sha256(b)
  private def n(v: V) = Node(v, Bytes.empty)
  private def a(vs: V*) = V.Arr(vs.toVector.map(n))
  private def ratio(a: BigInt, b: BigInt): V = V.Tag(30, n(this.a(V.UInt(a), V.UInt(b))))
  private def full(rho: V = ratio(3, 1000), tau: V = ratio(1, 5)): Vector[V] =
    Vector(
      V.UInt(44),
      V.UInt(155381),
      V.UInt(90112),
      V.UInt(16384),
      V.UInt(1100),
      V.UInt(0),
      V.UInt(0),
      V.UInt(18),
      V.UInt(500),
      ratio(3, 10),
      rho,
      tau,
      a(V.UInt(9), V.UInt(0)),
      V.UInt(170000000),
      V.UInt(4310),
      V.Map(Vector.empty),
      a(ratio(1, 10), ratio(1, 100)),
      a(V.UInt(1000), V.UInt(2000)),
      a(V.UInt(3000), V.UInt(4000)),
      V.UInt(5000),
      V.UInt(150),
      V.UInt(3),
      a(Vector.fill(5)(ratio(1, 2))*),
      a(Vector.fill(10)(ratio(1, 2))*),
      V.UInt(0),
      V.UInt(10),
      V.UInt(6),
      V.UInt(0),
      V.UInt(0),
      V.UInt(20),
      ratio(15, 1)
    )
  private def pp(xs: Vector[V] = full()): Bytes = get(Cbor.encode(V.Arr(xs.map(n))))

  private def decode(xs: Vector[V] = full()) =
    val raw = pp(xs)
    P.decode(raw, sha(raw))
  private val fees = get(FeeSize.Parameters.create("Conway", 9, 44, 155381, 16384))
  private val minimum = get(MinimumOutput.Parameters.checked("Conway", 9, 0, 4310))

  test("native positional extraction preserves full canonical original and all 31 spans") {
    val value = get(decode())
    assertEquals(value.original, pp())
    assertEquals(value.payload.original, pp())
    assertEquals(value.sha256, sha(pp()))
    assertEquals(value.fieldOriginals.size, 31)
    value.fieldOriginals
      .zip(full())
      .foreach((raw, expected) => assertEquals(raw, get(Cbor.encode(expected))))
    assertEquals(value.feePerByte, BigInt(44))
    assertEquals(value.feeFixed, BigInt(155381))
    assertEquals(value.maxTxSize, BigInt(16384))
    assertEquals(value.coinsPerUTxOByte, BigInt(4310))
    assert(P.checkProjections(value, fees, minimum, value.rewards).isRight)
    assert(!value.nativeSeedAdmitted && !value.fullParameterValidity)
  }

  test("canonical complete bytes and matching hash reject each inconsistent ledger projection") {
    val expected = get(decode()).rewards
    Vector((0, 45), (1, 155382), (3, 16385), (14, 4311)).foreach { (index, replacement) =>
      val changed = get(decode(full().updated(index, V.UInt(replacement))))
      assertEquals(changed.sha256, sha(changed.original))
      assert(P.checkProjections(changed, fees, minimum, expected).isLeft)
    }
  }

  test("canonical complete bytes and matching hash reject each inconsistent reward projection") {
    val expected = get(decode()).rewards
    Vector((9, ratio(1, 2)), (10, ratio(1, 100)), (11, ratio(1, 4)), (8, V.UInt(501))).foreach {
      (index, replacement) =>
        val changed = get(decode(full().updated(index, replacement)))
        assertEquals(changed.sha256, sha(changed.original))
        assert(P.checkProjections(changed, fees, minimum, expected).isLeft)
    }
  }

  test("explicit previous and current roles retain differences outside the consumed projection") {
    val previous = get(decode())
    val current = get(decode(full().updated(5, V.UInt(123))))
    val selected = get(
      P.bindRoles(
        previous,
        current,
        previous.sha256,
        current.sha256,
        previous.rewards,
        current.rewards,
        fees,
        minimum
      )
    )
    assert(selected.previous eq previous)
    assert(selected.current eq current)
    assertNotEquals(previous.sha256, current.sha256)
    assert(
      P.bindRoles(
        current,
        previous,
        previous.sha256,
        current.sha256,
        previous.rewards,
        current.rewards,
        fees,
        minimum
      ).isLeft
    )
    val same = get(
      P.bindRoles(
        previous,
        previous,
        previous.sha256,
        previous.sha256,
        previous.rewards,
        previous.rewards,
        fees,
        minimum
      )
    )
    assertNotEquals(selected.id, same.id)
  }

  test("wrong version count source pin duplicate map keys and noncanonical encoding reject") {
    assert(decode(full().updated(12, a(V.UInt(10), V.UInt(0)))).isLeft)
    assert(decode(full().updated(12, a(V.UInt(9), V.UInt(1)))).isLeft)
    assert(decode(full().dropRight(1)).isLeft)
    assert(P.decode(pp(), Bytes(Vector.fill(32)(0.toByte))).isLeft)
    val duplicate = V.Map(Vector(n(V.UInt(0)) -> n(a()), n(V.UInt(0)) -> n(a())))
    assert(decode(full().updated(15, duplicate)).isLeft)
    // First scalar is 44 as 0x18 0x2c; widen it after the two-byte array length.
    val noncanonical =
      Bytes(pp().value.take(2) ++ Vector(0x19.toByte, 0.toByte, 44.toByte) ++ pp().value.drop(4))
    assert(P.decode(noncanonical, sha(noncanonical)).isLeft)
  }

  test("unsigned and rational bounds and unsupported cost-model variants reject") {
    Vector((3, BigInt(1) << 32), (8, BigInt(65536))).foreach { (i, bad) =>
      assert(decode(full().updated(i, V.UInt(bad))).isLeft)
    }
    val aboveWord64 = V.Tag(2, n(V.ByteString(Bytes(Vector(1.toByte) ++ Vector.fill(8)(0.toByte)))))
    Vector(0, 1, 14).foreach(i => assert(decode(full().updated(i, aboveWord64)).isLeft))
    Vector(ratio(1, 0), ratio(2, 4), ratio(2, 1)).foreach { bad =>
      assert(decode(full().updated(10, bad)).isLeft)
    }
    assert(decode(full().updated(15, V.Map(Vector(n(V.UInt(0)) -> n(a(V.UInt(0))))))).isLeft)
    assert(decode(full().updated(14, V.NInt(-1))).isLeft)
    assert(P.decode(Bytes.empty, sha(Bytes.empty)).isLeft)
    val tooLarge = Bytes(Vector.fill(65537)(0.toByte))
    assert(P.decode(tooLarge, sha(tooLarge)).isLeft)
  }
