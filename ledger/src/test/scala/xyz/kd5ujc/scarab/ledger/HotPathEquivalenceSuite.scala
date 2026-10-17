// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes

/** The rewrites of issue 133 (the ledger's hot paths on mainnet) agree with what they replaced: the
  * orders that were built from hex strings and `Vector` s of bytes, and the arithmetic on values
  * that rebuilt every map it touched. Each property keeps the old definition as the reference.
  */
class HotPathEquivalenceSuite extends ScalaCheckSuite:
  private type Assets = Map[Bytes, Map[Bytes, BigInt]]

  // Small alphabets, so that the same policy and name meet often; some bytes are high (signed
  // bytes must not be compared as signed) and the lengths differ (a prefix sorts first).
  private val genBytes: Gen[Bytes] =
    for
      n <- Gen.choose(0, 3)
      bs <- Gen.listOfN(n, Gen.oneOf(0, 1, 127, 128, 255))
    yield Bytes.fromArray(bs.map(_.toByte).toArray)

  private val genQuantity: Gen[BigInt] = Gen.oneOf(0, 1, -1, 2, -2, 5, 1000).map(BigInt(_))

  // Not normalised on purpose: zero quantities and empty policies occur in a mint map.
  private val genAssets: Gen[Assets] =
    Gen.mapOf(
      for
        policy <- genBytes
        names <- Gen.mapOf(Gen.zip(genBytes, genQuantity))
      yield policy -> names
    )

  private val genValue: Gen[Value] =
    for
      coin <- Gen.choose(-3, 3)
      assets <- Gen.oneOf(Gen.const(Map.empty: Assets), genAssets)
    yield Value(coin, assets)

  /** The definition `Value.+` and `Value.-` had: both maps rebuilt over the union of their keys. */
  private def reference(a: Assets, b: Assets, f: (BigInt, BigInt) => BigInt): Assets =
    (a.keySet ++ b.keySet).iterator
      .map { policy =>
        val x = a.getOrElse(policy, Map.empty)
        val y = b.getOrElse(policy, Map.empty)
        policy -> (x.keySet ++ y.keySet).iterator
          .map(n => n -> f(x.getOrElse(n, 0), y.getOrElse(n, 0)))
          .filter(_._2 != 0)
          .toMap
      }
      .filter(_._2.nonEmpty)
      .toMap

  property("adding values gives what rebuilding the union gave") {
    forAll(genValue, genValue) { (a, b) =>
      (a + b) == Value(a.coin + b.coin, reference(a.assets, b.assets, _ + _))
    }
  }

  property("subtracting values gives what rebuilding the union gave") {
    forAll(genValue, genValue) { (a, b) =>
      (a - b) == Value(a.coin - b.coin, reference(a.assets, b.assets, _ - _))
    }
  }

  property("a sum over many values, as the rules take it, is the same in any grouping") {
    forAll(Gen.listOf(genValue)) { vs =>
      val summed = vs.foldLeft(Value.zero)(_ + _)
      val referenced = vs.foldLeft(Value.zero)((acc, v) =>
        Value(acc.coin + v.coin, reference(acc.assets, v.assets, _ + _))
      )
      summed == referenced && (summed - summed) == Value.zero
    }
  }

  private val genTxIn: Gen[TxIn] =
    for
      id <- Gen
        .listOfN(2, Gen.oneOf(0, 1, 15, 16, 127, 128, 255))
        .map(bs => Bytes.fromArray(bs.map(_.toByte).toArray))
      index <- Gen.oneOf(0, 1, 2, 255, 256).map(BigInt(_))
    yield TxIn(id, index)

  property("inputs sort as they did by the hex of the id, then the index") {
    forAll(Gen.listOf(genTxIn)) { ins =>
      ins.sorted == ins.sortBy(i => (i.id.hex, i.index))
    }
  }

  property("credentials sort as they did by kind, then the hex of the hash") {
    val genCredential = for
      hash <- genBytes
      script <- Gen.oneOf(true, false)
    yield if script then Credential.Script(hash) else Credential.Key(hash)
    forAll(Gen.listOf(genCredential)) { cs =>
      cs.sorted == cs.sortBy(c => (c.ordinal, c.hash.hex))
    }
  }

  /** The order `ScriptContexts` had, on the vectors of the bytes. */
  private def vectorOrder(a: Bytes, b: Bytes): Int =
    val n = a.value.length.min(b.value.length)
    var i = 0
    while i < n do
      val c = (a.value(i) & 0xff) - (b.value(i) & 0xff)
      if c != 0 then return c
      i += 1
    a.value.length - b.value.length

  property("byte strings sort unsigned, a prefix first, as the order on their vectors did") {
    forAll(Gen.listOf(genBytes)) { bs =>
      bs.sorted == bs.sorted((a, b) => vectorOrder(a, b)) &&
      bs.forall(a =>
        bs.forall(b => Integer.signum(a.compareTo(b)) == Integer.signum(vectorOrder(a, b)))
      )
    }
  }

  property("withdrawals are in the ledger's order: network, scripts first, then the hash") {
    val genAccount = for
      network <- Gen.choose(0, 1)
      hash <- genBytes
      script <- Gen.oneOf(true, false)
      amount <- Gen.choose(0, 9)
    yield (
      RewardAccount(network, if script then Credential.Script(hash) else Credential.Key(hash)),
      BigInt(amount)
    )
    forAll(Gen.listOf(genAccount)) { ws =>
      val body = TestLedger.body("w", Vector.empty, Vector.empty, 0, withdrawals = ws.toVector)
      Purpose.sortedWithdrawals(body) ==
        ws.toVector.sortBy(w =>
          (w._1.network, w._1.credential.isInstanceOf[Credential.Key], w._1.credential.hash.hex)
        )
    }
  }
