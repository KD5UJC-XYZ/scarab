// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayEmptyGovernance as G, ConwayStake as S}

/** Synthetic shape/identity mutations only, not native endpoint conformance evidence. */
class NativeEndpointLedgerSuite extends munit.FunSuite:
  private val E = NativeEndpointLedger
  private def get[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), identity)
  private def n(v: V): Node = Node(v, Bytes.empty)
  private def a(xs: V*): V = V.Arr(xs.toVector.map(n))
  private def m(xs: (V, V)*): V = V.Map(xs.toVector.map((k, v) => n(k) -> n(v)))
  private def encoded(v: V): Bytes = get(Cbor.encode(v))
  private def hash(size: Int, value: Int): Bytes = Bytes(Vector.fill(size)(value.toByte))
  private def cred(c: S.Credential): V = a(V.UInt(if c.script then 1 else 0), V.ByteString(c.hash))
  private def ratio(r: S.Ratio): V = V.Tag(30, n(a(V.UInt(r.numerator), V.UInt(r.denominator))))
  private def set(xs: Vector[V]): V = V.Tag(258, n(V.Arr(xs.map(n))))
  private val pool = hash(28, 1)
  private val who = S.Credential(false, hash(28, 2))
  private val owner = hash(28, 2)
  private val application = get(
    S.context(
      hash(32, 9),
      BigInt(1000),
      Map(who -> S.Account(0, 0, Some(pool))),
      Map(pool -> S.Pool(hash(32, 3), 0, 0, S.Ratio(0, 1), who, Set(owner), Set(who), 0))
    )
  )
  private val snapshot = get(S.fromActive(application, Map(who -> S.Active(7, pool))))
  private def poolFields(p: S.PoolSnapshot): Vector[V] = Vector(
    V.UInt(p.coin),
    ratio(p.ratio),
    set(p.owners.toVector.map(V.ByteString.apply)),
    V.UInt(p.ownerCoin),
    V.ByteString(p.vrf),
    V.UInt(p.pledge),
    V.UInt(p.cost),
    ratio(p.margin),
    V.UInt(p.delegators),
    cred(p.rewardAccount)
  )
  private def snapshotBytes(fields: Vector[V]): Bytes = encoded(
    a(
      m(cred(who) -> a(V.UInt(7), V.ByteString(pool))),
      m(V.ByteString(pool) -> V.Arr(fields.map(n)))
    )
  )

  test("snapshot equality checks all active and pool metadata fields") {
    val fields = poolFields(snapshot.pools(pool))
    assert(E.checkSnapshot(snapshotBytes(fields), snapshot).isRight)
    val changes = Map[Int, V](
      0 -> V.UInt(8),
      1 -> ratio(S.Ratio(0, 1)),
      2 -> set(Vector.empty),
      3 -> V.UInt(8),
      4 -> V.ByteString(hash(32, 4)),
      5 -> V.UInt(1),
      6 -> V.UInt(1),
      7 -> ratio(S.Ratio(1, 1)),
      8 -> V.UInt(2),
      9 -> cred(S.Credential(false, hash(28, 5)))
    )
    changes.foreach((index, changed) =>
      assert(
        E.checkSnapshot(snapshotBytes(fields.updated(index, changed)), snapshot).isLeft,
        index.toString
      )
    )
    assert(E.checkSnapshot(encoded(a(m(), m())), snapshot).isLeft)
    assert(E.checkSnapshot(encoded(a(m(), m())), S.emptySnapshot).isRight)
  }

  private def utxo(address: Bytes, coin: BigInt, mapped: Boolean): Bytes =
    val output =
      if mapped then m(V.UInt(0) -> V.ByteString(address), V.UInt(1) -> V.UInt(coin))
      else a(V.ByteString(address), V.UInt(coin))
    encoded(m(a(V.ByteString(hash(32, 1)), V.UInt(0)) -> output))

  test("whole UTxO semantics preserve complete address and value across array/map forms") {
    val address = Bytes(Vector(0x60.toByte) ++ hash(28, 3).value)
    val a = get(E.utxoSemantics(utxo(address, 7, false)))
    assertEquals(a, get(E.utxoSemantics(utxo(address, 7, true))))
    assertNotEquals(a, get(E.utxoSemantics(utxo(address, 8, false))))
    val other = Bytes(Vector(0x60.toByte) ++ hash(28, 4).value)
    assertNotEquals(a, get(E.utxoSemantics(utxo(other, 7, false))))
  }

  private def seed(utxo: V, epoch: BigInt = 1): Bytes = encoded(
    a(
      V.UInt(epoch),
      m(),
      m(),
      a(
        a(V.UInt(0), V.UInt(10)),
        a(a(m(), m(), m()), a(utxo, V.UInt(0), V.UInt(0), m(), m(), V.UInt(0))),
        a(m(), m(), m(), V.UInt(0)),
        a(m(), V.UInt(0))
      ),
      a(),
      m(),
      V.Null
    )
  )

  test("debug-to-seed derivation allows only the original UTxO replacement") {
    val empty = encoded(m())
    val same = seed(m())
    assert(E.checkReplacement(same, same, empty).isRight)
    assert(E.checkReplacement(seed(m(), 2), same, empty).isLeft)
    assert(E.checkReplacement(same, seed(m(V.UInt(0) -> V.UInt(1))), empty).isLeft)
    val address = Bytes(Vector(0x60.toByte) ++ hash(28, 3).value)
    assert(E.checkReplacement(same, same, utxo(address, 7, false)).isLeft)
    assert(E.checkReplacement(Bytes.empty, same, empty).isLeft)
  }

  test("historical VRF index checks exact original bytes without reconstructing multiplicity") {
    val key = V.ByteString(hash(32, 7))
    val original = encoded(m(key -> V.UInt(3)))
    assert(E.checkHistoricalVrfIndex(original, original).isRight)
    assert(E.checkHistoricalVrfIndex(encoded(m(key -> V.UInt(4))), original).isLeft)
    assert(E.checkHistoricalVrfIndex(encoded(m()), original).isLeft)
    val zero = encoded(m(key -> V.UInt(0)))
    assert(E.checkHistoricalVrfIndex(zero, zero).isLeft)
    val narrow = encoded(m(V.ByteString(hash(28, 7)) -> V.UInt(3)))
    assert(E.checkHistoricalVrfIndex(narrow, narrow).isLeft)
    val duplicate = encoded(m(key -> V.UInt(3), key -> V.UInt(3)))
    assert(E.checkHistoricalVrfIndex(duplicate, duplicate).isLeft)
  }

  test("historical genesis delegations require exact original bytes and checked hash shapes") {
    val key = V.ByteString(hash(28, 7))
    val pair = a(V.ByteString(hash(28, 8)), V.ByteString(hash(32, 9)))
    val original = encoded(m(key -> pair))
    assert(E.checkHistoricalGenesisDelegations(original, original).isRight)
    assert(E.checkHistoricalGenesisDelegations(encoded(m()), original).isLeft)
    val changed = encoded(m(key -> a(V.ByteString(hash(28, 8)), V.ByteString(hash(32, 10)))))
    assert(E.checkHistoricalGenesisDelegations(changed, original).isLeft)
    val bad = encoded(m(key -> a(V.ByteString(hash(28, 8)), V.ByteString(hash(28, 9)))))
    assert(E.checkHistoricalGenesisDelegations(bad, bad).isLeft)
    val duplicate = encoded(m(key -> pair, key -> pair))
    assert(E.checkHistoricalGenesisDelegations(duplicate, duplicate).isLeft)
  }

  test(
    "endpoint parameter equality uses installed rollover roles and preserves initial provenance"
  ) {
    val oldPrevious = get(G.payload(encoded(a(V.UInt(1)))))
    val oldCurrent = get(G.payload(encoded(a(V.UInt(2)))))
    val initial = G.Parameters(oldCurrent, oldPrevious, G.FutureParameters.NoUpdate)
    // The finite empty-governance transition installs current into both endpoint roles.
    val installed = G.Parameters(initial.current, initial.current, G.FutureParameters.PotentialNone)
    assertNotEquals(initial.previous.original, installed.previous.original)
    assertEquals(initial.previous.original, oldPrevious.original)
    assert(E.checkInstalledParameters(oldCurrent.original, oldCurrent.original, installed).isRight)
    assert(E.checkInstalledParameters(oldCurrent.original, oldPrevious.original, installed).isLeft)
    assert(E.checkInstalledParameters(oldPrevious.original, oldCurrent.original, installed).isLeft)
    assert(E.checkInstalledParameters(oldCurrent.original, oldCurrent.original, initial).isLeft)
    // Equal decoded CBOR values with a different original integer envelope are still rejected.
    val noncanonicalCurrent = Bytes(Vector(0x81.toByte, 0x18.toByte, 0x02.toByte))
    assert(E.checkInstalledParameters(noncanonicalCurrent, oldCurrent.original, installed).isLeft)
    assert(E.checkInstalledParameters(oldCurrent.original, noncanonicalCurrent, installed).isLeft)
  }

  test("unbound endpoint inputs cannot produce an equality report") {
    assert(E.compare(null, null, null, null, null).isLeft)
  }
