// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.security.{KeyPairGenerator, Signature}
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.submission.SignedTransaction

class AdaAdmissionPoolSuite extends munit.FunSuite:
  private val R = ClusterTransition
  private def n(v: V) = Node(v, Bytes.empty)
  private def u(i: BigInt) = V.UInt(i)
  private def arr(v: V*) = V.Arr(v.toVector.map(n))
  private def dict(v: (V, V)*) = V.Map(v.toVector.map((k, x) => n(k) -> n(x)))
  private def raw(v: V) = Cbor.encode(v).toOption.get
  private def bs(b: Bytes) = V.ByteString(b)
  private def hex(s: String) = Bytes.fromHex(s).toOption.get
  private def cat(xs: Bytes*) = Bytes(xs.toVector.flatMap(_.value))
  private def array(xs: Vector[Bytes]) = cat(hex("9f"), Bytes(xs.flatMap(_.value)), hex("ff"))
  private val pairs = Vector.fill(3)(KeyPairGenerator.getInstance("Ed25519").generateKeyPair())
  private val publics = pairs.map(k => Bytes.fromArray(k.getPublic.getEncoded.takeRight(32)))
  private val hashes = publics.map(Blake2b.hash224.hash)
  private val digest = Bytes(Vector.fill(32)(1.toByte))
  private val input = arr(bs(Bytes(Vector.fill(32)(2.toByte))), u(0))
  private val otherInput = arr(bs(Bytes(Vector.fill(32)(3.toByte))), u(1))
  private val native = raw(
    arr(u(1), arr(arr(u(0), bs(hashes(0))), arr(u(4), u(20)), arr(u(5), u(30))))
  )
  private def scriptHash = NativeScript.decode(native).toOption.get.hash
  private def keyAddress(k: Int) = Bytes(Vector(0x60.toByte) ++ hashes(k).value)
  private val scriptAddress = Bytes(Vector(0x70.toByte) ++ scriptHash.value)
  private def output(address: Bytes, coin: BigInt) = arr(bs(address), u(coin))
  private def env(
      a: BigInt = 44,
      b: BigInt = 155381,
      max: BigInt = 16384,
      cost: BigInt = 4310,
      magic: Long = 1082026,
      epoch: BigInt = 0
  ) =
    R.environment(digest, digest, magic, epoch, 9, 0, a, b, max, cost).toOption.get
  private def initial(
      environment: R.Environment = env(),
      fees: BigInt = 700000,
      nativeInput: Boolean = false,
      nonminimal: Boolean = false
  ): R.State =
    val before = raw(
      dict(
        input -> output(if nativeInput then scriptAddress else keyAddress(0), 4000000),
        otherInput -> output(keyAddress(1), 9000000)
      )
    )
    val encoded =
      if nonminimal then hex(before.hex.replace("1a003d0900", "1b00000000003d0900")) else before
    R.checkpoint(environment, encoded, fees, 20, digest).toOption.get
  private def body(
      refs: Vector[V] = Vector(input),
      coin: BigInt = 3800000,
      fee: BigInt = 200000,
      lower: BigInt = 20,
      upper: BigInt = 30,
      extra: Vector[(V, V)] = Vector.empty
  ): Bytes =
    raw(
      dict(
        (Vector(
          u(0) -> arr(refs*),
          u(1) -> arr(output(keyAddress(0), coin)),
          u(2) -> u(fee),
          u(8) -> u(lower),
          u(3) -> u(upper)
        ) ++ extra)*
      )
    )
  private def tx(
      b: Bytes = body(),
      scripts: Vector[Bytes] = Vector.empty,
      signers: Vector[Int] = Vector(0)
  ): Bytes =
    val witnesses = signers.map { k =>
      val signer = Signature.getInstance("Ed25519")
      signer.initSign(pairs(k).getPrivate)
      signer.update(Blake2b.hash256.hash(b).toArray)
      raw(arr(bs(publics(k)), bs(Bytes.fromArray(signer.sign()))))
    }
    val fields =
      (if witnesses.nonEmpty then Vector(hex("00"), array(witnesses)) else Vector.empty) ++
        (if scripts.nonEmpty then Vector(hex("01"), array(scripts)) else Vector.empty)
    cat(hex("84"), b, cat(hex("bf"), Bytes(fields.flatMap(_.value)), hex("ff")), hex("f5f6"))

  private case class Pin(owner: Int, generation: Int, view: Int = 0)
  private val pin = Pin(1, 0)
  private def candidate(raw: Bytes = tx(), p: Pin = pin, state: R.State = initial()) =
    AdaAdmission.prepare(p, state, raw).toOption.get

  test("original identity preserves noncanonical spans and rejects oversize before parsing") {
    val b = body()
    val alternateBody = hex(b.hex.replace("1a0039fbc0", "1b000000000039fbc0"))
    val raw = tx(alternateBody)
    val identity = SignedTransaction.checked(raw).toOption.get
    assertEquals(identity.original, raw)
    assertEquals(identity.originalBody, alternateBody)
    assertEquals(identity.transactionId, Blake2b.hash256.hash(alternateBody))
    assertEquals(identity.byteSize, raw.size)
    assertEquals(
      SignedTransaction.checked(Bytes(Vector.fill(65537)(0.toByte))).left.toOption,
      Some(SignedTransaction.Error.InputLimit)
    )
    assert(SignedTransaction.checked(cat(raw, hex("00"))).isLeft)
  }
  test("scoped admission validates signatures fees and shape without changing confirmed state") {
    val state = initial()
    val originalMap = state.outputMap
    val good = AdaAdmission.prepare(pin, state, tx()).toOption.get
    assertEquals(good.transaction.original, tx())
    assert(!good.fullLedgerValidated)
    assertEquals(good.pin, pin)
    assert(good.fee.supplied >= good.fee.minimum)
    assertEquals(state.outputMap, originalMap)
    assertEquals(state.revision, BigInt(0))
    val badSig = tx()
    val corrupted =
      Bytes(badSig.value.updated(badSig.size - 4, (badSig.value(badSig.size - 4) ^ 1).toByte))
    assert(AdaAdmission.prepare(pin, state, corrupted).isLeft)
    val lowFee = AdaAdmission.prepare(pin, state, tx(body(coin = 3999999, fee = 1)))
    lowFee.left.toOption.get match
      case AdaAdmission.Failure.Ledger(
            R.Failure.Rejected(NativeSpending.Error.FeeTooSmall(supplied, minimum))
          ) =>
        assertEquals(supplied, BigInt(1))
        assert(minimum > supplied)
      case other => fail(other.toString)
    assert(AdaAdmission.prepare(pin, state, hex("80")).isLeft)
  }
  test("ADA whitelist rejects scripts and extended body fields before broad validator") {
    assert(
      AdaAdmission
        .prepare(pin, initial(nativeInput = true), tx(scripts = Vector(native)))
        .left
        .toOption
        .get
        .isInstanceOf[AdaAdmission.Failure.Unsupported]
    )
    assert(
      AdaAdmission
        .prepare(pin, initial(nativeInput = true), tx())
        .left
        .toOption
        .get
        .isInstanceOf[AdaAdmission.Failure.Unsupported]
    )
    Vector(4, 5, 9, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22).foreach { field =>
      assert(
        AdaAdmission
          .prepare(pin, initial(), tx(body(extra = Vector(u(field) -> u(0)))))
          .left
          .toOption
          .get
          .isInstanceOf[AdaAdmission.Failure.Unsupported]
      )
    }
    val falseValidity = Bytes(tx().value.updated(tx().size - 2, 0xf4.toByte))
    assert(
      AdaAdmission
        .prepare(pin, initial(), falseValidity)
        .left
        .toOption
        .get
        .isInstanceOf[AdaAdmission.Failure.Unsupported]
    )
  }
  test("duplicates are idempotent; alternate witness envelope cannot replace pinned bytes") {
    val c = candidate()
    val (s, first) = AdaPool.admit(AdaPool.empty(pin), c, 0)
    assert(first.isInstanceOf[AdaPool.Outcome.Accepted[?]])
    val leaseSnapshot = s.eligible(0)
    val (same, again) = AdaPool.admit(s, candidate(c.transaction.original), 1)
    assertEquals(same.size, 1)
    assert(again.isInstanceOf[AdaPool.Outcome.AlreadyPresent[?]])
    val variant = candidate(tx(signers = Vector(0, 1)))
    assertEquals(variant.transaction.transactionId, c.transaction.transactionId)
    assertEquals(
      AdaPool.admit(s, variant, 2)._2,
      AdaPool.Outcome.Rejected(AdaPool.Rejection.EnvelopeConflict)
    )
    assertEquals(leaseSnapshot.head.original, c.transaction.original)
    assertEquals(s.eligible(2).head.original, c.transaction.original)
  }
  test("common owner gate gives one winner for concurrently prepared competing spends") {
    val candidates = Vector(candidate(), candidate(tx(body(coin = 3700000, fee = 300000))))
    val gate = new Object
    var state = AdaPool.empty(pin)
    val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    val start = new java.util.concurrent.CountDownLatch(1)
    try
      val futures = candidates.map(c =>
        executor.submit(new java.util.concurrent.Callable[AdaPool.Outcome[Pin]] {
          def call(): AdaPool.Outcome[Pin] =
            start.await()
            gate.synchronized {
              val (next, result) = AdaPool.admit(state, c, 0)
              state = next
              result
            }
        })
      )
      start.countDown()
      val results = futures.map(_.get(10, java.util.concurrent.TimeUnit.SECONDS))
      assertEquals(results.count(_.isInstanceOf[AdaPool.Outcome.Accepted[?]]), 1)
      assertEquals(results.count(_.isInstanceOf[AdaPool.Outcome.Rejected[?]]), 1)
      assertEquals(state.size, 1)
      assertEquals(state.reserved.size, 1)
    finally executor.shutdownNow()
  }
  test("state change during validation returns Retry with no entry or reservation") {
    val prepared = candidate()
    val (moved, work) = AdaPool.move(AdaPool.empty(pin), pin.copy(generation = 1), Set.empty, 0)
    val ready = AdaPool.finish(moved, AdaPool.revalidate(work, initial()), 0)._1
    val (s, result) = AdaPool.admit(ready, prepared, 0)
    assertEquals(result, AdaPool.Outcome.Retry(ready.pin))
    assertEquals(s.size, 0)
    assertEquals(s.reserved, Set.empty[TxIn])
  }
  test(
    "rollback invalidates relay and old rebuild; survivors regain reservations in new generation"
  ) {
    val original = candidate()
    val s = AdaPool.admit(AdaPool.empty(pin), original, 0)._1
    val (moved, oldWork) = AdaPool.move(s, pin.copy(generation = 1, view = 1), Set.empty, 1)
    assertEquals(moved.eligible(1), Vector.empty)
    assertEquals(moved.reserved, Set.empty[TxIn])
    assertEquals(AdaPool.admit(moved, candidate(p = moved.pin), 1)._2, AdaPool.Outcome.Unavailable)
    val (rollback, freshWork) = AdaPool.move(moved, pin.copy(generation = 2), Set.empty, 2)
    assert(!AdaPool.finish(rollback, AdaPool.revalidate(oldWork, initial()), 2)._2)
    val (ready, installed) = AdaPool.finish(rollback, AdaPool.revalidate(freshWork, initial()), 2)
    assert(installed)
    assertEquals(ready.reserved, original.spent)
    assertEquals(ready.eligible(2).head.original, original.transaction.original)
    assert(!AdaPool.finish(ready, AdaPool.revalidate(freshWork, initial()), 2)._2)
  }
  test(
    "capacity expiry removal inclusion and shutdown release reservations without changing originals"
  ) {
    val c = candidate()
    val tiny = AdaPool.empty(pin, AdaPool.Limits(maxBytes = 1))
    assertEquals(AdaPool.admit(tiny, c, 0)._2, AdaPool.Outcome.Rejected(AdaPool.Rejection.Capacity))
    val s = AdaPool.admit(AdaPool.empty(pin), c, 0)._1
    val expired = AdaPool.expire(s, 60000000000L)
    assertEquals(expired.size, 0)
    assertEquals(
      expired.status(c.transaction.transactionId, 60000000000L),
      Some(AdaPool.Status.Dropped(AdaPool.Drop.Expired))
    )
    assertEquals(expired.status(c.transaction.transactionId, 120000000000L), None)
    assertEquals(
      AdaPool.remove(s, c.transaction.transactionId, AdaPool.Drop.Removed, 1).reserved,
      Set.empty[TxIn]
    )
    val (included, _) =
      AdaPool.move(s, pin.copy(generation = 1), Set(c.transaction.transactionId), 1)
    assertEquals(included.size, 0)
    assertEquals(
      included.status(c.transaction.transactionId, 1),
      Some(AdaPool.Status.Included(included.pin))
    )
    val closed = AdaPool.shutdown(s, 1)
    assertEquals(closed.reserved, Set.empty[TxIn])
    assertEquals(AdaPool.admit(closed, c, 2)._2, AdaPool.Outcome.Unavailable)
    intercept[IllegalArgumentException](AdaPool.Limits(maxTransactions = 65))
  }
  test("removal during rebuild is not resurrected; expired and invalid survivors drop") {
    val c = candidate()
    val s = AdaPool.admit(AdaPool.empty(pin), c, 0)._1
    val (moved, work) = AdaPool.move(s, pin.copy(generation = 1), Set.empty, 1)
    val result = AdaPool.revalidate(work, initial())
    val removed = AdaPool.remove(moved, c.transaction.transactionId, AdaPool.Drop.Removed, 2)
    assertEquals(AdaPool.finish(removed, result, 3)._1.size, 0)
    assertEquals(AdaPool.finish(moved, result, 60000000000L)._1.size, 0)
    val badView = initial(environment = env(a = 9999))
    val invalid = AdaPool.finish(moved, AdaPool.revalidate(work, badView), 2)._1
    assertEquals(invalid.size, 0)
    assert(
      invalid.status(c.transaction.transactionId, 2).get.isInstanceOf[AdaPool.Status.Dropped[?]]
    )
  }

  test("complete-pin equality fences owner and view changes independently of generation") {
    val c = candidate()
    Vector(pin.copy(owner = 2), pin.copy(view = 1)).foreach { changed =>
      val (state, result) = AdaPool.admit(AdaPool.empty(changed), c, 0)
      assertEquals(result, AdaPool.Outcome.Retry(changed))
      assertEquals(state.reserved, Set.empty[TxIn])
    }
  }
  test("status history evicts oldest summaries; pending parent is never resolved from pool") {
    var state = AdaPool.empty(pin, AdaPool.Limits(maxHistory = 2))
    val candidates = (0 until 3).map(i => candidate(tx(body(coin = 3800000 - i, fee = 200000 + i))))
    candidates.foreach { c =>
      state = AdaPool.admit(state, c, 0)._1
      state = AdaPool.remove(state, c.transaction.transactionId, AdaPool.Drop.Removed, 1)
    }
    assertEquals(state.status(candidates.head.transaction.transactionId, 1), None)
    assert(state.status(candidates.last.transaction.transactionId, 1).nonEmpty)
    val parent = candidate()
    val child =
      tx(body(refs = Vector(arr(bs(parent.transaction.transactionId), u(0))), coin = 3600000))
    AdaAdmission.prepare(pin, initial(), child).left.toOption.get match
      case AdaAdmission.Failure.Ledger(
            R.Failure.Rejected(NativeSpending.Error.UnresolvedInputs(_))
          ) =>
        ()
      case other => fail(other.toString)
  }
