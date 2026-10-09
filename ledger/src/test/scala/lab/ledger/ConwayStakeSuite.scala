// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}

class ConwayStakeSuite extends munit.FunSuite:
  import ConwayStake.*
  private def get[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)
  private def b(n: Int, size: Int = 28): Bytes = Bytes(Vector.fill(size)(n.toByte))
  private def c(n: Int, script: Boolean = false) = Credential(script, b(n))
  private val p = b(10); private val q = b(11); private val pin = b(9, 32)
  private def pool(delegators: Set[Credential], owners: Set[Bytes] = Set.empty) =
    Pool(b(7, 32), 20, 2, Ratio(1, 10), c(8), owners, delegators, 0)
  private val accounts = Map(
    c(1) -> Account(5, 2, Some(p)),
    c(2) -> Account(0, 2, Some(p)),
    c(3) -> Account(7, 2, Some(q)),
    c(4, true) -> Account(9, 2, None)
  )
  private val pools =
    Map(p -> pool(Set(c(1), c(2)), Set(b(1), b(3))), q -> pool(Set(c(3))), b(12) -> pool(Set.empty))
  private val ctx = get(context(pin, 500, accounts, pools))
  private def n(v: V) = Node(v, Bytes.empty)
  private def encoded(v: V) = get(Cbor.encode(v))
  private def output(kind: Int, stake: Int, amount: V): V =
    val address = Bytes(
      Vector((kind << 4).toByte) ++ b(20).value ++ (if kind <= 3 then b(stake).value
                                                    else Vector.empty)
    )
    V.Arr(Vector(n(V.ByteString(address)), n(amount)))
  private def utxo(outputs: V*): Bytes = encoded(V.Map(outputs.toVector.zipWithIndex.map { (o, i) =>
    n(V.Arr(Vector(n(V.ByteString(b(i, 32))), n(V.UInt(0))))) -> n(o)
  }))
  test("account balances combine once, inactive credentials drop, owners must self-delegate") {
    val s =
      get(snapshot(ctx, Map(c(1) -> BigInt(100), c(99) -> BigInt(500), c(4, true) -> BigInt(3))))
    assertEquals(s.total, BigInt(112))
    assertEquals(s.active(c(1)).coin, BigInt(105))
    assertEquals(s.active(c(3)).coin, BigInt(7))
    assert(!s.active.contains(c(99))); assert(!s.active.contains(c(4, true)))
    assertEquals(s.pools(p).owners, Set(b(1)))
    assertEquals(s.pools(p).ownerCoin, BigInt(105))
    assertEquals(s.pools(p).ratio, Ratio(15, 16))
    assertEquals(s.pools(p).delegators, 2)
    assert(!s.distribution.contains(b(12)))
  }
  test("zero stake uses denominator one and keeps pool with zero-balance delegator") {
    val zero =
      get(context(pin, 500, Map(c(2) -> Account(0, 0, Some(p))), Map(p -> pool(Set(c(2))))))
    val s = get(snapshot(zero, Map.empty))
    assertEquals(s.total, BigInt(1)); assertEquals(s.pools(p).ratio, Ratio(0, 1));
    assert(s.distribution.contains(p))
    assert(context(pin, 500, accounts, pools.updated(p, pool(Set(c(1))))).isLeft)
    assert(snapshot(ctx, Map(c(1) -> ((BigInt(1) << 64) - 1))).isLeft)
  }
  test(
    "complete output projection distinguishes script stake and ignores enterprise/assets weight"
  ) {
    val assets = V.Arr(
      Vector(
        n(V.UInt(11)),
        n(
          V.Map(
            Vector(
              n(V.ByteString(b(6))) -> n(
                V.Map(Vector(n(V.ByteString(Bytes.empty)) -> n(V.UInt(900))))
              )
            )
          )
        )
      )
    )
    val raw = utxo(
      output(0, 1, V.UInt(10)),
      output(2, 1, V.UInt(20)),
      output(6, 1, V.UInt(999)),
      output(1, 1, assets)
    )
    assertEquals(get(recompute(raw)), Map(c(1) -> BigInt(21), c(1, true) -> BigInt(20)))
    assert(decodeUtxo(utxo(output(4, 1, V.UInt(1)))).isLeft)
    assert(decodeUtxo(utxo(output(8, 1, V.UInt(1)))).isLeft)
    assert(
      recompute(utxo(output(0, 1, V.UInt((BigInt(1) << 64) - 1)), output(0, 1, V.UInt(1)))).isLeft
    )
  }
  test("owner-fenced accepted block preview and SNAP rotation never publish an epoch tick") {
    val raw = utxo(output(0, 1, V.UInt(1000000)), output(6, 1, V.UInt(2000000)))
    val env =
      get(ClusterTransition.environment(pin, pin, 1082026, 0, 9, 0, 44, 155381, 16384, 4310))
    val ledger = get(ClusterTransition.checkpoint(env, raw, 12, 10, pin))
    val own = owner(); val foreign = owner()
    val mark = get(snapshot(ctx, Map(c(1) -> BigInt(40))))
    val set = get(snapshot(ctx, Map(c(1) -> BigInt(20))))
    val seedState =
      get(seed(own, ctx, ledger, pin, get(recompute(raw)), Snapshots(mark, set, emptySnapshot, 8)))
    val block = get(ClusterTransition.prepareBlock(ledger, pin, Vector.empty, 11))
    val pending = get(prepare(own, seedState, ledger, block))
    assertEquals(seedState.slot, BigInt(10)); assert(select(foreign, seedState, pending).isLeft)
    val next = get(select(own, seedState, pending))
    assertEquals(next.revision, BigInt(1));
    assertEquals(next.instantaneous, seedState.instantaneous)
    assert(select(own, next, pending).isLeft)
    val rotation = get(previewRotation(own, next, pin, 500, 12))
    assert(rotation.leadership eq mark); assert(rotation.snapshots.set eq mark);
    assert(rotation.snapshots.go eq set)
    assertEquals(rotation.snapshots.mark.active(c(1)).coin, BigInt(1000005))
    assertEquals(rotation.snapshots.fees, BigInt(12)); assert(!rotation.epochTransitionValidated)
    assertEquals(next.epoch, BigInt(0)); assert(previewRotation(foreign, next, pin, 500, 12).isLeft)
    assert(previewRotation(own, next, pin, 1000, 12).isLeft)
    val crossing = get(ClusterTransition.prepareBlock(ledger, pin, Vector.empty, 500))
    assert(prepare(own, seedState, ledger, crossing).isLeft)
  }
