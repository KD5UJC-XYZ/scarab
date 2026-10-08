// SPDX-License-Identifier: Apache-2.0
package lab.header

import java.nio.file.{Files, Path}
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value}

class PraosCertificateStateSuite extends munit.FunSuite:
  import PraosCertificateState.*
  private def get[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def arr(n: Node): Vector[Node] = n.value.asInstanceOf[Value.Arr].value
  private def b(n: Node): Bytes = n.value.asInstanceOf[Value.ByteString].value
  private def n(node: Node): BigInt = node.value.asInstanceOf[Value.UInt].value
  private val raw =
    Bytes.fromArray(Files.readAllBytes(Path.of("fixtures/opcert/evidence/preprod_70070331.cbor")))
  assertEquals(
    Bytes.fromArray(java.security.MessageDigest.getInstance("SHA-256").digest(raw.toArray)).hex,
    "a0bd5600bb96ff965fbaa72d9c3d2692e7aecc3c3948a4b816263069782ed902"
  )
  private val h = arr(get(Cbor.decode(raw))); private val body = arr(h.head)
  private val issuer = Blake2b.hash224.hash(b(body(3)))
  private val vrf = Blake2b.hash256.hash(b(body(4)))
  private val hash = Blake2b.hash256.hash(raw)
  private val counter = n(arr(body(8))(1))
  private val source = Bytes(Vector.fill(32)(1.toByte))
  private val anchor = Point(b(body(2)), n(body(1)) - 1, n(body(0)) - 1)
  private def context(v: Bytes = vrf, lifetime: Int = 62): Context = get(
    Context.checked(source, source, 0, 100000000, 129600, lifetime, Map(issuer -> v))
  )
  private val ctx = context()
  private def seeded(current: BigInt, c: Context = ctx): State =
    get(seed(c, anchor, Map(issuer -> current), source))

  test("source counter rule: initial zero fallback only for pool-distribution membership") {
    assertEquals(counterRule(None, true, 0), Right(BigInt(0)))
    assertEquals(counterRule(None, true, 1), Right(BigInt(1)))
    assertEquals(counterRule(None, true, 2), Left("CounterOverIncrementedOCERT"))
    assertEquals(counterRule(None, false, 0), Left("NoCounterForKeyHashOCERT"))
    assertEquals(counterRule(Some(BigInt(7)), false, 7), Right(BigInt(7)))
  }
  test("source counter rule: equal repeats allowed, stale and skipped increments rejected") {
    assertEquals(counterRule(Some(BigInt(7)), true, 6), Left("CounterTooSmallOCERT"))
    assertEquals(counterRule(Some(BigInt(7)), true, 7), Right(BigInt(7)))
    assertEquals(counterRule(Some(BigInt(7)), true, 8), Right(BigInt(8)))
    assertEquals(counterRule(Some(BigInt(7)), true, 9), Left("CounterOverIncrementedOCERT"))
    val repeated = Vector(BigInt(7), BigInt(7), BigInt(8), BigInt(8)).foldLeft(BigInt(7)) {
      (m, next) => get(counterRule(Some(m), true, next))
    }
    assertEquals(repeated, BigInt(8))
  }
  test("Word64 maxBound successor wraps; malformed counters do not enter state") {
    val max = (BigInt(1) << 64) - 1
    assertEquals(counterRule(Some(max - 1), true, max), Right(max))
    assertEquals(counterRule(Some(max), true, max), Left("CounterOverIncrementedOCERT"))
    assertEquals(counterRule(Some(max), true, 0), Left("CounterTooSmallOCERT"))
    assert(counterRule(Some(max + 1), true, max).isLeft)
    assert(seed(ctx, anchor, Map(issuer -> BigInt(-1)), source).isLeft)
  }
  test("authenticated equal/new counters apply and undo restores exact prior map") {
    for old <- Vector(counter - 1, counter) do
      val before = seeded(old)
      val applied = get(applyHeader(ctx, before, raw, hash))
      assertEquals(applied.after.counters(issuer), counter)
      assertEquals(applied.after.tip.hash, hash)
      assertEquals(get(undo(applied.after, applied)).id, before.id)
      assertEquals(get(undo(applied.after, applied)).counters(issuer), old)
      assert(!applied.consensusValidated && !applied.vrfEligibilityChecked)
      assert(applyHeader(ctx, applied.after, raw, hash).isLeft) // same block cannot extend itself
  }
  test("signed header with stale or skipped issue counter leaves seed unchanged") {
    for (old, expected) <- Vector(
        (counter + 1, "CounterTooSmallOCERT"),
        (counter - 2, "CounterOverIncrementedOCERT")
      )
    do
      val before = seeded(old)
      assertEquals(applyHeader(ctx, before, raw, hash), Left(expected))
      assertEquals(before.counters(issuer), old)
    val empty = get(seed(ctx, anchor, Map.empty, source))
    assertEquals(applyHeader(ctx, empty, raw, hash), Left("CounterOverIncrementedOCERT"))
  }
  test("undo cannot substitute a receipt from a different counter history") {
    val a = get(applyHeader(ctx, seeded(counter - 1), raw, hash))
    val b = get(applyHeader(ctx, seeded(counter), raw, hash))
    assertEquals(a.after.counters, b.after.counters)
    assertNotEquals(a.after.id, b.after.id)
    assert(undo(a.after, b).isLeft)
    assert(undo(a.before, a).isLeft)
    val forkSeed = get(undo(a.after, a))
    // A lower sibling certificate becomes admissible only after restoring the ancestor counter.
    assert(counterRule(a.after.counters.get(issuer), true, counter - 1).isLeft)
    assertEquals(counterRule(forkSeed.counters.get(issuer), true, counter - 1), Right(counter - 1))
    val replayed = get(applyHeader(ctx, forkSeed, raw, hash))
    assertEquals(replayed.after.id, a.after.id)
  }
  test("byte, anchor, registration and context mismatches cannot advance counters") {
    val before = seeded(counter)
    assert(applyHeader(ctx, before, raw, source).isLeft)
    val wrong = context(source)
    assert(applyHeader(wrong, before, raw, hash).isLeft)
    assert(applyHeader(wrong, seeded(counter, wrong), raw, hash).isLeft)
    val wrongAnchor = get(seed(ctx, anchor.copy(hash = source), before.counters, source))
    assert(applyHeader(ctx, wrongAnchor, raw, hash).isLeft)
    val changed = Bytes(raw.value.updated(raw.size - 1, (raw.value.last ^ 1).toByte))
    assert(applyHeader(ctx, before, changed, Blake2b.hash256.hash(changed)).isLeft)
    val alternate = Bytes(raw.value.take(1) ++ Vector(0x98.toByte, 10.toByte) ++ raw.value.drop(2))
    assert(
      applyHeader(ctx, before, alternate, Blake2b.hash256.hash(alternate)).left.toOption.get
        .contains("unsupported serialization")
    )
  }
  test("KES expiry and fixed registration window remain preconditions") {
    val expired = context(lifetime = 35)
    assert(
      applyHeader(expired, seeded(counter, expired), raw, hash).left.toOption.get
        .contains("lifetime expired")
    )
    val window =
      get(Context.checked(source, source, 0, anchor.slot, 129600, 62, Map(issuer -> vrf)))
    assert(
      applyHeader(window, seeded(counter, window), raw, hash).left.toOption.get
        .contains("registration window")
    )
  }
