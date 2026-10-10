// SPDX-License-Identifier: Apache-2.0
package lab.ledger.storage

import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{
  ClusterTransition as L,
  ConwayStake as S,
  PlutusParameters,
  PlutusEnvironment,
  PlutusContextInput
}

class RestrictedStakeImageSuite extends munit.FunSuite:
  import RestrictedStakeImage.*
  private def get[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), identity)
  private def b(n: Int, size: Int = 32) = Bytes(Vector.fill(size)(n.toByte))
  private def n(v: V) = Node(v, Bytes.empty)
  private val cred = S.Credential(false, b(2, 28))
  private val pool = b(3, 28)
  private val source = b(4)
  private val context = get(
    S.context(
      source,
      1000,
      Map(cred -> S.Account(0, 0, Some(pool))),
      Map(pool -> S.Pool(b(5), 0, 0, S.Ratio(0, 1), cred, Set(cred.hash), Set(cred), 0))
    )
  )
  private val raw = get(
    Cbor.encode(
      V.Map(
        Vector(
          n(V.Arr(Vector(n(V.ByteString(b(6))), n(V.UInt(0))))) ->
            n(
              V.Arr(
                Vector(
                  n(V.ByteString(Bytes(Vector(0.toByte) ++ b(7, 28).value ++ cred.hash.value))),
                  n(V.UInt(3000000))
                )
              )
            )
        )
      )
    )
  )
  private val env = get(L.environment(b(8), b(9), 42, 0, 9, 0, 44, 155381, 16384, 4310))
  private def ledger = get(L.checkpoint(env, raw, 12, 10, b(10)))
  private def binding = Binding(b(11), b(12), source, 10, 3, b(13))
  private def state(l: L.State, owner: S.Owner) = get(
    S.seed(
      owner,
      context,
      l,
      source,
      get(S.recompute(l.outputMap)),
      S.Snapshots(
        get(S.fromActive(context, Map.empty)),
        get(S.snapshot(context, Map(cred -> BigInt(9)))),
        S.emptySnapshot,
        7
      )
    )
  )
  private def decoded(bytes: Bytes, expected: Binding = binding) = get(
    decode(bytes, expected, sha256(bytes))
  )
  test(
    "complete snapshot reconstruction retains registered zero-stake pool and empty sentinel separately"
  ) {
    val l = ledger; val old = S.owner(); val initial = state(l, old)
    val bytes = get(encode(initial, l, binding)); val image = decoded(bytes)
    val fresh = get(L.checkpoint(env, l.outputMap, l.fees, l.slot, b(22)))
    val owner = S.owner(); val restored = get(reconstruct(image, context, fresh, owner))
    assertEquals(restored.utxo, initial.utxo);
    assertEquals(restored.instantaneous, initial.instantaneous)
    assertEquals(restored.snapshots.mark.pools.keySet, Set(pool))
    assertEquals(restored.snapshots.mark.pools(pool).ratio, S.Ratio(0, 1))
    assertEquals(restored.snapshots.mark.distribution.keySet, Set(pool))
    assertEquals(restored.snapshots.go.pools, Map.empty)
    assertEquals(restored.snapshots.set.active, initial.snapshots.set.active)
    assertEquals(restored.snapshots.fees, BigInt(7))
    assertEquals(restored.ledgerId, fresh.id); assertNotEquals(restored.ledgerId, initial.ledgerId)
    assertEquals(restored.revision, BigInt(0))
    assert(!image.restoreAuthorized && !image.completeValidatorState && !image.fullLedgerValidated)
    val next = get(L.prepareBlock(fresh, b(23), Vector.empty, 11))
    assert(S.prepare(old, restored, fresh, next).isLeft)
    val advanced = get(S.select(owner, restored, get(S.prepare(owner, restored, fresh, next))))
    assertEquals(advanced.instantaneous, initial.instantaneous)
  }
  test("external whole-image pin and complete point/source binding reject substitutions") {
    val l = ledger; val bytes = get(encode(state(l, S.owner()), l, binding))
    assert(decode(bytes, binding, b(99)).isLeft)
    Vector(
      binding.copy(blockNo = 4),
      binding.copy(headerHash = b(98)),
      binding.copy(slot = 11),
      binding.copy(sourceJoinId = b(97)),
      binding.copy(ledgerImageSHA256 = b(96)),
      binding.copy(stakeSourceId = b(95))
    )
      .foreach(x => assert(decode(bytes, x, sha256(bytes)).isLeft))
    val changed = Bytes(bytes.value.updated(bytes.size - 1, 8.toByte))
    assert(decode(changed, binding, sha256(bytes)).isLeft)
    Vector(0, 1, 7, 64, bytes.size - 1).foreach(i => {
      val x = Bytes(bytes.value.take(i)); assert(decode(x, binding, sha256(x)).isLeft)
    })
    val extra = Bytes(bytes.value :+ 0.toByte); assert(decode(extra, binding, sha256(extra)).isLeft)
  }
  test("independently checked context, fees, slot, environment and exact UTxO are required") {
    val l = ledger; val image = decoded(get(encode(state(l, S.owner()), l, binding)))
    val changedContext = get(S.context(b(90), 1000, context.accounts, context.pools))
    assert(reconstruct(image, changedContext, l, S.owner()).isLeft)
    assert(
      reconstruct(image, context, get(L.checkpoint(env, raw, 13, 10, b(10))), S.owner()).isLeft
    )
    assert(
      reconstruct(image, context, get(L.checkpoint(env, raw, 12, 11, b(10))), S.owner()).isLeft
    )
    val changedRaw = Bytes(raw.value.dropRight(1) :+ 1.toByte)
    assert(
      reconstruct(
        image,
        context,
        get(L.checkpoint(env, changedRaw, 12, 10, b(10))),
        S.owner()
      ).isLeft
    )
    val other = get(L.environment(b(80), b(9), 42, 0, 9, 0, 44, 155381, 16384, 4310))
    assert(
      reconstruct(image, context, get(L.checkpoint(other, raw, 12, 10, b(10))), S.owner()).isLeft
    )
  }
  test("repinned snapshot corruption cannot bypass semantic reconstruction") {
    val l = ledger; val bytes = get(encode(state(l, S.owner()), l, binding))
    // Locate the unique registered pool VRF inside the encoded mark snapshot.
    val pattern = b(5).value
    val at =
      bytes.value.sliding(32).zipWithIndex.collectFirst { case (v, i) if v == pattern => i }.get
    val changed = Bytes(bytes.value.updated(at, 6.toByte))
    val image = decoded(changed)
    assert(reconstruct(image, context, l, S.owner()).isLeft)
  }
  test("checked continuation parity after an evolved state is rebuilt with a fresh owner") {
    val l = ledger; val own = S.owner(); val original = state(l, own)
    val block = get(L.prepareBlock(l, b(30), Vector.empty, 11))
    val evolved = get(S.select(own, original, get(S.prepare(own, original, l, block))))
    val nextLedger = get(L.commitBlock(l, block)).state
    val bind = binding.copy(slot = 11, blockNo = 4, headerHash = b(30))
    val image = decoded(get(encode(evolved, nextLedger, bind)), bind)
    assert(reconstruct(image, context, nextLedger, S.owner()).isLeft)
    val fresh = get(L.checkpoint(env, nextLedger.outputMap, nextLedger.fees, 11, b(31)))
    val freshOwner = S.owner(); val restored = get(reconstruct(image, context, fresh, freshOwner))
    val leftBlock = get(L.prepareBlock(nextLedger, b(32), Vector.empty, 12))
    val rightBlock = get(L.prepareBlock(fresh, b(32), Vector.empty, 12))
    val left = get(S.select(own, evolved, get(S.prepare(own, evolved, nextLedger, leftBlock))))
    val right =
      get(S.select(freshOwner, restored, get(S.prepare(freshOwner, restored, fresh, rightBlock))))
    assertEquals(left.utxo, right.utxo); assertEquals(left.instantaneous, right.instantaneous)
    assertEquals(left.snapshots.mark.pools, right.snapshots.mark.pools)
    assertNotEquals(left.revision, right.revision)
  }
  test(
    "actual opt-in datum output originals survive reconstruction; datumless environment rejects"
  ) {
    def fixture(name: String) =
      Bytes.fromArray(Files.readAllBytes(Path.of("fixtures/plutus-pv9-reference/inputs", name)))
    val model = Bytes.fromArray(
      Files.readAllBytes(Path.of("vm/src/main/resources/plutus-pv9/cost-model.json"))
    )
    def array(v: V*): V = V.Arr(v.toVector.map(n))
    def ratio(a: BigInt, b: BigInt): V = V.Tag(30, n(array(V.UInt(a), V.UInt(b))))
    val costs = new String(model.toArray, "UTF-8").trim
      .stripPrefix("[")
      .stripSuffix("]")
      .split(",")
      .toVector
      .map(x => BigInt(x.trim))
    def cost(v: BigInt): V = if v < 0 then V.NInt(v) else V.UInt(v)
    val parameterFields = Vector
      .fill[V](31)(V.UInt(0))
      .updated(0, V.UInt(44))
      .updated(1, V.UInt(155381))
      .updated(3, V.UInt(16384))
      .updated(12, array(V.UInt(9), V.UInt(0)))
      .updated(14, V.UInt(4310))
      .updated(15, V.Map(Vector(n(V.UInt(2)) -> n(V.Arr(costs.map(x => n(cost(x))))))))
      .updated(16, array(ratio(577, 10000), ratio(721, 10000000)))
      .updated(17, array(V.UInt(14000000), V.UInt(10000000000L)))
      .updated(18, array(V.UInt(62000000), V.UInt(20000000000L)))
      .updated(19, V.UInt(5000))
      .updated(20, V.UInt(150))
      .updated(21, V.UInt(3))
    val parameters = get(Cbor.encode(V.Arr(parameterFields.map(n))))
    val pp = get(PlutusParameters.decode(parameters, sha256(parameters), model))
    val base = get(L.environment(b(8), pp.sourceSHA256, 42, 0, 9, 0, 44, 155381, 16384, 4310))
    val pe = get(PlutusEnvironment.bind(base, pp, PlutusContextInput.SlotTime(0, 1000, 1, b(8)), 0))
    val plutus = get(L.withPlutus(base, pe))
    val datumRaw =
      Bytes(Vector(0xa1.toByte) ++ fixture("input-0.cbor").value ++ fixture("output-0.cbor").value)
    assert(L.checkpoint(base, datumRaw, 0, 10, b(10)).isLeft)
    val l = get(L.checkpoint(plutus, datumRaw, 0, 10, b(10)))
    val own = S.owner()
    val s = get(
      S.seed(
        own,
        context,
        l,
        source,
        get(S.recomputePlutus(l.outputMap, 0)),
        S.Snapshots(S.emptySnapshot, S.emptySnapshot, S.emptySnapshot, 0)
      )
    )
    val image = decoded(get(encode(s, l, binding)))
    val restored = get(
      reconstruct(image, context, get(L.checkpoint(plutus, l.outputMap, 0, 10, b(33))), S.owner())
    )
    assertEquals(restored.utxo.values.head.original, fixture("output-0.cbor"))
    assertEquals(restored.utxo, s.utxo)
  }

  test(
    "length, count, canonical integer and snapshot boolean guards reject repinned hostile bytes"
  ) {
    val l = ledger
    val bytes = get(encode(state(l, S.owner()), l, binding))
    def intAt(raw: Bytes, offset: Int, value: Int): Bytes =
      val a = raw.toArray
      java.nio.ByteBuffer.wrap(a).putInt(offset, value)
      Bytes.fromArray(a)
    def rejects(raw: Bytes): Unit = assert(decode(raw, binding, sha256(raw)).isLeft)
    rejects(Bytes(Vector.fill(MaxBytes + 1)(0.toByte)))
    // Fixed header and three 32-byte source identities precede the first integer blob.
    val integer = 8 + 3 * 32
    rejects(intAt(bytes, integer, -1))
    rejects(intAt(bytes, integer, 18))
    val padded =
      Bytes(bytes.value.take(integer + 4) ++ Vector(0.toByte) ++ bytes.value.drop(integer + 4))
    rejects(intAt(padded, integer, 2))
    var offset = integer
    def skipBlob(): Unit =
      val length = java.nio.ByteBuffer.wrap(bytes.toArray).getInt(offset)
      offset += 4 + length
    skipBlob(); skipBlob(); offset += 32 + 32 + 32
    skipBlob(); skipBlob(); skipBlob(); skipBlob()
    val countOffset = offset
    rejects(intAt(bytes, countOffset, 4097))
    rejects(intAt(bytes, countOffset, -1))
    offset += 4 + 29; skipBlob()
    val snapshotBoolean = offset + 4
    val nonBoolean = Bytes(bytes.value.updated(snapshotBoolean, 2.toByte))
    assert(reconstruct(decoded(nonBoolean), context, l, S.owner()).isLeft)
  }
