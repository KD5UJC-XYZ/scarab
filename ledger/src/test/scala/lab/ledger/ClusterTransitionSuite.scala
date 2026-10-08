// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.security.{KeyPairGenerator, Signature}
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import scala.compiletime.testing.typeCheckErrors

class ClusterTransitionSuite extends munit.FunSuite:
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
    R.checkpoint(environment, encoded, fees, 10, digest).toOption.get
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
  private def nextRef(applied: R.Applied): V = arr(bs(applied.candidate.transactionId), u(0))
  private def rejected[A](value: R.Checked[A], expected: NativeSpending.Error): Unit =
    assertEquals(value.left.toOption, Some(R.Failure.Rejected(expected)))

  test("candidate derives original output IDs UTxO and fees without any observed post-state") {
    val before = initial()
    val original = tx()
    val prepared = R.prepare(before, original, 20).toOption.get
    assertEquals(prepared.fees, BigInt(900000))
    assertEquals(prepared.transactionId, Blake2b.hash256.hash(body()))
    assertEquals(prepared.originalTransaction, original)
    assertEquals(before.fees, BigInt(700000))
    val applied = R.commit(before, prepared).toOption.get
    assertEquals(applied.state.revision, BigInt(1))
    assertEquals(applied.state.slot, BigInt(20))
    assertEquals(applied.state.size, 2)
    assert(applied.credentialBound && !applied.fullLedgerValidated)
    assert(R.compareReference(applied, prepared.outputMap, prepared.fees).isRight)
    assert(
      !NativeSpending
        .snapshot(prepared.outputMap)
        .toOption
        .get
        .contains(TxIn.create(Bytes(Vector.fill(32)(2.toByte)), 0).toOption.get)
    )
  }
  test(
    "native spend followed by ordinary key spend advances the same state including same-slot transactions"
  ) {
    val before = initial(nativeInput = true)
    val first = R.applyTransaction(before, tx(scripts = Vector(native)), 20).toOption.get
    assert(first.candidate.nativeAdmission.get.credentialBound)
    val second =
      R.applyTransaction(first.state, tx(body(Vector(nextRef(first)), 3600000)), 20).toOption.get
    assert(second.candidate.nativeAdmission.isEmpty)
    assertEquals(second.state.fees, before.fees + 400000)
    assertEquals(second.state.revision, BigInt(2))
  }
  test("undo restores exact output spans fee pot and slot but advances revision") {
    val before = initial(nonminimal = true)
    val applied = R.applyTransaction(before, tx(), 20).toOption.get
    val restored = R.undo(applied.state, applied.state.revision, applied.undo).toOption.get
    assertEquals(restored.outputMap, before.outputMap)
    assertEquals(restored.id, before.id)
    assertEquals(restored.fees, before.fees)
    assertEquals(restored.slot, before.slot)
    assertEquals(restored.revision, BigInt(2))
    assert(R.applyTransaction(restored, tx(), 20).isRight)
  }
  test("undo stack restores earlier head after reverting dependent transaction") {
    val before = initial()
    val first = R.applyTransaction(before, tx(), 20).toOption.get
    val second =
      R.applyTransaction(first.state, tx(body(Vector(nextRef(first)), 3600000)), 21).toOption.get
    assert(R.undo(second.state, second.state.revision, first.undo).isLeft)
    val one = R.undo(second.state, second.state.revision, second.undo).toOption.get
    val zero = R.undo(one, one.revision, first.undo).toOption.get
    assertEquals(zero.outputMap, before.outputMap)
    assertEquals(zero.fees, before.fees)
    assertEquals(zero.revision, BigInt(4))
  }
  test("prepared candidates and undo receipts are fenced after rollback and reapplication") {
    val before = initial()
    val prepared = R.prepare(before, tx(), 20).toOption.get
    val first = R.commit(before, prepared).toOption.get
    assert(R.commit(first.state, prepared).left.toOption.get.isInstanceOf[R.Failure.StaleState])
    val restored = R.undo(first.state, 1, first.undo).toOption.get
    assert(R.commit(restored, prepared).left.toOption.get.isInstanceOf[R.Failure.StaleState])
    val reapplied = R.applyTransaction(restored, tx(), 20).toOption.get
    assert(
      R.undo(reapplied.state, reapplied.state.revision, first.undo)
        .left
        .toOption
        .get
        .isInstanceOf[R.Failure.StaleState]
    )
    assert(
      R.undo(reapplied.state, 1, reapplied.undo)
        .left
        .toOption
        .get
        .isInstanceOf[R.Failure.StaleState]
    )
  }
  test("wrong branch changed parameters and checkpoint attribution cannot consume candidates") {
    val before = initial()
    val prepared = R.prepare(before, tx(), 20).toOption.get
    val branch = R.applyTransaction(before, tx(body(coin = 3700000, fee = 300000)), 20).toOption.get
    assert(R.commit(branch.state, prepared).isLeft)
    val standard = R.commit(before, prepared).toOption.get
    assert(R.undo(branch.state, branch.state.revision, standard.undo).isLeft)
    Vector(
      env(cost = 4311),
      env(a = 45),
      env(b = 155382),
      env(max = 16385),
      env(magic = 999),
      env(epoch = 1)
    ).foreach { e =>
      assertNotEquals(initial(e).environment.id, before.environment.id)
      assert(R.commit(initial(e), prepared).isLeft)
    }
    val other = R
      .checkpoint(
        before.environment,
        before.outputMap,
        before.fees,
        before.slot,
        Bytes(Vector.fill(32)(9.toByte))
      )
      .toOption
      .get
    assert(R.commit(other, prepared).isLeft)
  }
  test("duplicate replay unresolved and duplicate inputs cannot produce state") {
    val before = initial()
    val applied = R.applyTransaction(before, tx(), 20).toOption.get
    assert(R.prepare(applied.state, tx(), 20).left.toOption.get.isInstanceOf[R.Failure.Rejected])
    assert(
      R.prepare(before, tx(body(Vector(input, input))), 20)
        .left
        .toOption
        .get
        .isInstanceOf[R.Failure.Malformed]
    )
    assertEquals(before.revision, BigInt(0))
    assert(R.prepare(before, tx(), 20).isRight)
  }
  test("actual slot interval inclusion and monotonicity cannot be replaced by current time") {
    val before = initial()
    rejected(R.prepare(before, tx(), 19), NativeSpending.Error.OutsideValidityInterval)
    rejected(R.prepare(before, tx(), 30), NativeSpending.Error.OutsideValidityInterval)
    assert(R.prepare(before, tx(), 9).left.toOption.get.isInstanceOf[R.Failure.Unsupported])
    assert(R.prepare(before, tx(), R.MaxRevision + 1).isLeft)
  }
  test("native partial credential success is insufficient without balance minimum and fee checks") {
    val before = initial(nativeInput = true)
    val imbalance = tx(body(coin = 3799999), Vector(native))
    assert(NativeSpending.check(imbalance, before.outputMap).isRight)
    assert(R.prepare(before, imbalance, 20).left.toOption.get.isInstanceOf[R.Failure.Rejected])
    rejected(
      R.prepare(before, tx(body(coin = 1, fee = 3999999), Vector(native)), 20),
      NativeSpending.Error.MinimumOutputFailed
    )
    assert(
      R.prepare(initial(env(a = 100000), nativeInput = true), tx(scripts = Vector(native)), 20)
        .left
        .toOption
        .get
        .isInstanceOf[R.Failure.Rejected]
    )
  }
  test("credential errors and unsupported script/body features remain scoped typed failures") {
    val before = initial(nativeInput = true)
    rejected(R.prepare(before, tx(), 20), NativeSpending.Error.MissingScripts(Set(scriptHash)))
    rejected(
      R.prepare(before, tx(scripts = Vector(native), signers = Vector(1)), 20),
      NativeSpending.Error.FailedScripts(Set(scriptHash))
    )
    rejected(
      R.prepare(initial(), tx(signers = Vector(1)), 20),
      NativeSpending.Error.MissingKeys(Set(hashes(0)))
    )
    rejected(
      R.prepare(initial(), tx(scripts = Vector(native)), 20),
      NativeSpending.Error.ExtraneousScripts(Set(scriptHash))
    )
    assert(
      R.prepare(before, tx(body(extra = Vector(u(9) -> dict())), Vector(native)), 20)
        .left
        .toOption
        .get
        .isInstanceOf[R.Failure.Unsupported]
    )
  }
  test(
    "nonminimal original output spans survive creation and original body bytes control identity"
  ) {
    val b = body()
    val wider = hex(b.hex.replace("1a0039fbc0", "1b000000000039fbc0"))
    val first = R.applyTransaction(initial(), tx(wider), 20).toOption.get
    val expected =
      hex(raw(output(keyAddress(0), 3800000)).hex.replace("1a0039fbc0", "1b000000000039fbc0"))
    val created =
      NativeSpending.snapshot(first.state.outputMap).toOption.get(first.candidate.created.head)
    assertEquals(created.original, expected)
    assertEquals(first.candidate.transactionId, Blake2b.hash256.hash(wider))
    val staleSignature = hex(tx().hex.replace(b.hex, wider.hex))
    rejected(R.prepare(initial(), staleSignature, 20), NativeSpending.Error.InvalidSignature)
  }
  test("whole witness map counts towards memo fee and size despite duplicate script semantics") {
    val single = tx(scripts = Vector(native))
    val nodes = Cbor.decode(single).toOption.get.value.asInstanceOf[V.Arr].value
    val size = BigInt(2 + nodes(0).original.size + nodes(1).original.size)
    val before = initial(env(a = 1, b = 200000 - size, max = size), nativeInput = true)
    assert(R.prepare(before, single, 20).isRight)
    assert(R.prepare(before, tx(scripts = Vector(native, native)), 20).isLeft)
  }
  test("reference comparison is optional and cannot alter independently derived candidate") {
    val applied = R.applyTransaction(initial(), tx(), 20).toOption.get
    val rawMap = applied.state.outputMap
    assert(R.compareReference(applied, rawMap, applied.state.fees).isRight)
    assert(R.compareReference(applied, rawMap, applied.state.fees + 1).isLeft)
    assert(R.compareReference(applied, raw(dict()), applied.state.fees).isLeft)
    val untouchedMutation = hex(rawMap.hex.replace("1a00895440", "1b0000000000895440"))
    assert(R.compareReference(applied, untouchedMutation, applied.state.fees).isLeft)
    val createdMutation = hex(rawMap.hex.replace("1a0039fbc0", "1b000000000039fbc0"))
    assert(R.compareReference(applied, createdMutation, applied.state.fees).isRight)
    assertEquals(applied.state.outputMap, rawMap)
  }
  test("state and fee bounds fail before exposing a new state") {
    val environment = env()
    val many = (0 until R.MaxEntries)
      .map(i => arr(bs(digest), u(i)) -> output(keyAddress(0), 4000000))
      .toVector
    val full = R.checkpoint(environment, raw(dict(many*)), 0, 10, digest).toOption.get
    val firstRef = arr(bs(digest), u(0))
    val twoOutputs = raw(
      dict(
        u(0) -> arr(firstRef),
        u(1) -> arr(output(keyAddress(0), 1900000), output(keyAddress(0), 1900000)),
        u(2) -> u(200000),
        u(8) -> u(20),
        u(3) -> u(30)
      )
    )
    assert(
      R.prepare(full, tx(twoOutputs), 20).left.toOption.get.isInstanceOf[R.Failure.ResourceLimit]
    )
    assertEquals(full.size, R.MaxEntries)
    val overflow = initial(fees = R.MaxFees)
    assert(R.prepare(overflow, tx(), 20).left.toOption.get.isInstanceOf[R.Failure.ResourceLimit])
    assertEquals(overflow.fees, R.MaxFees)
    assert(
      R.checkpoint(
        environment,
        raw(dict((many :+ (arr(bs(digest), u(4096)) -> output(keyAddress(0), 1)))*)),
        0,
        10,
        digest
      ).isLeft
    )
    assert(
      R.checkpoint(environment, Bytes(Vector.fill(R.MaxStateBytes + 1)(0.toByte)), 0, 10, digest)
        .isLeft
    )
  }
  test("checkpoint closure rejects unsupported unspent outputs and duplicate references") {
    val bad = raw(dict(input -> arr(bs(scriptAddress), u(4000000), V.Null)))
    assert(
      R.checkpoint(env(), bad, 0, 10, digest).left.toOption.get.isInstanceOf[R.Failure.Unsupported]
    )
    val repeated =
      raw(dict(input -> output(keyAddress(0), 4000000), input -> output(keyAddress(0), 4000000)))
    assert(
      R.checkpoint(env(), repeated, 0, 10, digest)
        .left
        .toOption
        .get
        .isInstanceOf[R.Failure.Malformed]
    )
  }
  test("checked constructors and immutable capabilities cannot be manufactured through copy") {
    assert(
      typeCheckErrors(
        "new lab.ledger.ClusterTransition.State(null,null,null,0,0,null,0,Map.empty,None)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "new lab.ledger.ClusterTransition.Candidate(null,null,null,null,0,null,None,Set.empty,Set.empty)"
      ).nonEmpty
    )
    assert(typeCheckErrors("new lab.ledger.ClusterTransition.Undo(null,null,null)").nonEmpty)
    val candidate = R.prepare(initial(), tx(), 20).toOption.get
    assert(!candidate.getClass.getMethods.exists(_.getName == "copy"))
  }
  test("null boundary inputs and null-backed bytes return checked failures rather than escaping") {
    val before = initial()
    val candidate = R.prepare(before, tx(), 20).toOption.get
    val applied = R.commit(before, candidate).toOption.get
    assert(R.environment(null, digest, 1, 0, 9, 0, 44, 155381, 16384, 4310).isLeft)
    assert(R.environment(digest, digest, 1, null, 9, 0, 44, 155381, 16384, 4310).isLeft)
    assert(R.fromContext(null, null).isLeft)
    assert(R.checkpoint(null, before.outputMap, 0, 10, digest).isLeft)
    assert(R.checkpoint(before.environment, before.outputMap, 0, 10, null).isLeft)
    assert(R.prepare(null, tx(), 20).isLeft)
    assert(R.prepare(before, null, 20).isLeft)
    assert(R.prepare(before, Bytes(null), 20).isLeft)
    assert(R.prepare(before, tx(), null).isLeft)
    assert(R.commit(before, null).isLeft)
    assert(R.commit(null, candidate).isLeft)
    assert(R.undo(applied.state, 1, null).isLeft)
    assert(R.undo(null, 1, applied.undo).isLeft)
    assert(R.undo(applied.state, null, applied.undo).isLeft)
    assert(R.compareReference(null, before.outputMap, 0).isLeft)
    assert(R.compareReference(applied, Bytes(null), 0).isLeft)
    assert(R.compareReference(applied, applied.state.outputMap, null).isLeft)
  }
  test("empty-all native predicate can advance without any key witnesses") {
    val always = raw(arr(u(1), arr()))
    val address = Bytes(Vector(0x70.toByte) ++ NativeScript.decode(always).toOption.get.hash.value)
    val before =
      R.checkpoint(env(), raw(dict(input -> output(address, 4000000))), 0, 10, digest).toOption.get
    val applied = R
      .applyTransaction(before, tx(scripts = Vector(always), signers = Vector.empty), 20)
      .toOption
      .get
    assert(applied.candidate.nativeAdmission.get.verifiedKeys.hashes.isEmpty)
    assertEquals(applied.state.fees, BigInt(200000))
  }
  test("key-only exact memo size is accepted and one byte below fails without changing state") {
    val original = tx()
    val nodes = Cbor.decode(original).toOption.get.value.asInstanceOf[V.Arr].value
    val size = BigInt(2 + nodes(0).original.size + nodes(1).original.size)
    assert(R.prepare(initial(env(max = size)), original, 20).isRight)
    val before = initial(env(max = size - 1))
    rejected(
      R.prepare(before, original, 20),
      NativeSpending.Error.TransactionTooLarge(size, size - 1)
    )
    assertEquals(before.revision, BigInt(0))
  }
  test("different original witnesses with identical resulting content have distinct undo heads") {
    val before = initial()
    val first = R.applyTransaction(before, tx(), 20).toOption.get
    val second = R.applyTransaction(before, tx(signers = Vector(0, 1)), 20).toOption.get
    assertEquals(first.state.id, second.state.id)
    assertEquals(first.state.revision, second.state.revision)
    assert(
      R.undo(second.state, second.state.revision, first.undo)
        .left
        .toOption
        .get
        .isInstanceOf[R.Failure.StaleState]
    )
    assert(R.undo(second.state, second.state.revision, second.undo).isRight)
  }
