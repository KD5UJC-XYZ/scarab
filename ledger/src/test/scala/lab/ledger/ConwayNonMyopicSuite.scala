// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import ConwayStake as S
import ConwayEpochBoundary as B
import ConwayRewardStart as R
import munit.FunSuite

class ConwayNonMyopicSuite extends FunSuite:
  private val N = ConwayNonMyopic
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def key(n: Int) = Bytes(Vector.fill(28)(n.toByte))
  private def weights(xs: Vector[Float]) = get(
    N.likelihood(xs.map(java.lang.Float.floatToRawIntBits))
  )
  private def constant(x: Float) = weights(Vector.fill(100)(x))

  test("empty new domain drops history and replaces pot even with zero monetary rewards") {
    val old = get(N.state(Map(key(1) -> constant(7f)), 99))
    val done = get(N.completeSupplied(old, old.id, 0, Set.empty, Map.empty))
    assertEquals(done.after.likelihoods.size, 0)
    assertEquals(done.after.rewardPot, BigInt(0))
    assert(!done.nativeParityValidated && !done.likelihoodGenerationImplemented && !done.published)
    val nonzero = get(N.completeSupplied(old, old.id, 123, Set.empty, Map.empty))
    assertEquals(nonzero.after.rewardPot, BigInt(123))
  }
  test("new-only pools are normalized, existing decayed, old-only dropped") {
    val old = get(N.state(Map(key(1) -> constant(1f), key(3) -> constant(9f)), 1000))
    val now = weights(Vector.tabulate(100)(i => i.toFloat - 50f))
    val done = get(
      N.completeSupplied(old, old.id, 37, Set(key(1), key(2)), Map(key(2) -> now, key(1) -> now))
    )
    assertEquals(done.after.orderedPools, Vector(key(1), key(2)))
    assertEquals(
      done.after.likelihoods(key(2)).rawBits,
      Vector.tabulate(100)(i => java.lang.Float.floatToRawIntBits(i.toFloat))
    )
    assertEquals(done.after.rewardPot, BigInt(37))
    assertEquals(old.rewardPot, BigInt(1000))
    assertEquals(old.likelihoods.size, 2)
  }
  test("binary32 decay and normalize use separately rounded arithmetic") {
    val history = weights(Vector.tabulate(100)(_.toFloat))
    val old = get(N.state(Map(key(1) -> history), 0))
    val done = get(N.completeSupplied(old, old.id, 0, Set(key(1)), Map(key(1) -> constant(0f))))
    assertEquals(done.after.likelihoods(key(1)).rawBits(1), 0x3f666666)
    assertEquals(done.after.likelihoods(key(1)).rawBits(2), 0x3fe66666)
  }
  test("storage bits preserve signed zero; native normalized equality treats zeros equal") {
    val plus = constant(0f)
    val minus = get(N.likelihood(Vector.fill(100)(0x80000000)))
    assert(plus.rawBits != minus.rawBits)
    assert(get(N.equivalentModuloOffset(plus, minus)))
    assert(get(N.equivalentModuloOffset(constant(9f), constant(-3f))))
    val old = get(N.state(Map.empty, 0))
    val done = get(N.completeSupplied(old, old.id, 0, Set(key(1)), Map(key(1) -> minus)))
    assertEquals(done.after.likelihoods(key(1)).rawBits, Vector.fill(100)(0))
  }
  test("application replaces rather than merges; absence preserves object") {
    val frozen = get(N.state(Map.empty, 0))
    val current = get(N.state(Map(key(2) -> constant(2f)), 22))
    val done = get(N.completeSupplied(frozen, frozen.id, 5, Set.empty, Map.empty))
    assert(get(N.applyAtBoundary(current, current.id, None)) eq current)
    assert(get(N.applyAtBoundary(current, current.id, Some(done))) eq done.after)
    assertEquals(current.rewardPot, BigInt(22))
  }
  test("exact lengths, finite domain, map coverage, coins and identity fail closed") {
    assert(N.likelihood(Vector.fill(99)(0)).isLeft)
    assert(N.likelihood(Vector.fill(100)(0x7fc00000)).isLeft)
    assert(N.likelihood(Vector.fill(100)(0x7f800000)).isLeft)
    assert(N.state(Map(Bytes.empty -> constant(0f)), 0).isLeft)
    assert(N.state(Map.empty, -1).isLeft)
    assert(N.state(Map.empty, BigInt(1) << 64).isLeft)
    val old = get(N.state(Map.empty, 0))
    assert(N.completeSupplied(old, key(1), 0, Set.empty, Map.empty).isLeft)
    assert(N.completeSupplied(old, old.id, 0, Set(key(1)), Map.empty).isLeft)
    assert(N.completeSupplied(old, old.id, 0, Set.empty, Map(key(1) -> constant(0f))).isLeft)
    assert(N.generateForFrozen(null, null).isLeft)
    assert(N.completeFrozen(old, old.id, null, null, null, Map.empty).isLeft)
  }
  test("overflow is unsupported, never silently clamped or converted") {
    val x = weights(Vector(-Float.MaxValue, Float.MaxValue) ++ Vector.fill(98)(0f))
    assert(N.normalize(x).isLeft)
    val old = get(N.state(Map(key(1) -> constant(Float.MaxValue)), 0))
    assert(
      N.completeSupplied(old, old.id, 0, Set(key(1)), Map(key(1) -> constant(Float.MaxValue)))
        .isLeft
    )
  }

  private class FrozenFixture(withPool: Boolean = false, fees: BigInt = 37):
    private def bytes(i: Int) = Bytes(Vector.fill(32)(i.toByte))
    private def encoded(format: String, values: Vector[BigInt]) = get(
      Cbor.encode(
        V.Arr(
          Vector(Node(V.Text(format), Bytes.empty)) ++ values.map(x => Node(V.UInt(x), Bytes.empty))
        )
      )
    )
    val bo = B.owner(); val so = S.owner()
    val credential = S.Credential(false, key(3))
    val pools =
      if withPool then
        Map(
          key(1) -> S
            .Pool(bytes(5), 0, 0, S.Ratio(0, 1), credential, Set(credential.hash), Set.empty, 0)
        )
      else Map.empty[Bytes, S.Pool]
    val context = get(S.context(bytes(7), 500, Map.empty, pools))
    val go = get(S.fromActive(context, Map.empty))
    val env = get(
      ClusterTransition.environment(bytes(7), bytes(7), 1082026, 0, 9, 0, 44, 155381, 16384, 4310)
    )
    val ledger = get(
      ClusterTransition.checkpoint(env, get(Cbor.encode(V.Map(Vector.empty))), 50, 110, bytes(7))
    )
    val state = get(S.seed(so, context, ledger, bytes(7), Map.empty, S.Snapshots(go, go, go, fees)))
    val start = get(
      B.context(bo, so, bytes(8), state, B.Pots(0, 1000, 0, 2000), Map.empty, Map.empty)
    )
    val params = get(
      R.decodeParameters(encoded(R.ParameterFormat, Vector[BigInt](9, 0, 0, 1, 0, 1)))
    )
    val globals = get(R.decodeGlobals(encoded(R.GlobalFormat, Vector[BigInt](500, 1, 20, 2000))))
    val frozen = get(B.freezeForAllocation(bo, start, 110, 100, params, globals))
    val allocation = get(R.calculate(frozen, frozen.id))

  test(
    "checked frozen empty-go uses allocated pot and validates frozen and allocation identities"
  ) {
    val f = new FrozenFixture()
    val other = new FrozenFixture(fees = 38)
    val history = get(N.state(Map(key(2) -> constant(7f)), 99))
    val supplied = get(N.generateForFrozen(f.frozen, f.frozen.id))
    assertEquals(supplied.size, 0)
    val done =
      get(N.completeFrozen(history, history.id, f.frozen, f.frozen.id, f.allocation, supplied))
    assertEquals(done.after.likelihoods.size, 0)
    assertEquals(done.after.rewardPot, BigInt(37))
    assert(N.generateForFrozen(f.frozen, key(9)).isLeft)
    assert(N.completeFrozen(history, history.id, f.frozen, key(9), f.allocation, supplied).isLeft)
    assert(
      N.completeFrozen(history, history.id, f.frozen, f.frozen.id, other.allocation, supplied)
        .isLeft
    )
  }
  test("empty active stake does not erase go pools or permit guessed likelihood generation") {
    val f = new FrozenFixture(withPool = true)
    assertEquals(f.frozen.go.pools.keySet, Set(key(1)))
    assert(N.generateForFrozen(f.frozen, f.frozen.id).isLeft)
    val history = get(N.state(Map.empty, 0))
    assert(
      N.completeFrozen(history, history.id, f.frozen, f.frozen.id, f.allocation, Map.empty).isLeft
    )
    assert(
      N.completeFrozen(
        history,
        history.id,
        f.frozen,
        f.frozen.id,
        f.allocation,
        Map(key(2) -> constant(0f))
      ).isLeft
    )
    val done = get(
      N.completeFrozen(
        history,
        history.id,
        f.frozen,
        f.frozen.id,
        f.allocation,
        Map(key(1) -> constant(0f))
      )
    )
    assertEquals(done.after.orderedPools, Vector(key(1)))
  }
