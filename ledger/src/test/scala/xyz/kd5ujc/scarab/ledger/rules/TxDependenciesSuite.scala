// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{TestLedger, Tx, TxIn}

class TxDependenciesSuite extends FunSuite:
  private val output = TestLedger.out(TestLedger.key().address(), 10000000)
  private def tx(
      n: Int,
      inputs: Vector[TxIn] = Vector.empty,
      refs: Vector[TxIn] = Vector.empty,
      collateral: Vector[TxIn] = Vector.empty,
      valid: Boolean = true
  ): Tx =
    TestLedger.tx(
      TestLedger.body(
        s"dep-$n",
        inputs,
        Vector(output),
        0,
        referenceInputs = refs,
        collateral = collateral,
        collateralReturn = Some(output)
      ),
      Vector.empty,
      isValid = valid
    )
  private def created(t: Tx) = TxIn(t.body.id, 0)

  test("independent spends and shared reference reads share a batch") {
    val ref = TestLedger.txIn(99)
    val txs = Vector.tabulate(4)(i => tx(i, Vector(TestLedger.txIn(i)), Vector(ref)))
    assertEquals(TxDependencies.levels(txs, txs.indices.toSet), Vector(1, 1, 1, 1))
  }

  test("ordinary transactions transmit script dependencies transitively") {
    val a = tx(0)
    val b = tx(1, Vector(created(a)))
    val c = tx(2, Vector(created(b)))
    val d = tx(3)
    assertEquals(TxDependencies.levels(Vector(a, b, c, d), Set(0, 2, 3)), Vector(1, 1, 2, 1))
  }

  test("a reference reader precedes a later consumer, but never waits for later readers") {
    val ref = TestLedger.txIn(99)
    val a = tx(0, refs = Vector(ref))
    val b = tx(1, refs = Vector(ref))
    val c = tx(2, Vector(ref))
    assertEquals(TxDependencies.levels(Vector(a, b, c), Set(0, 1, 2)), Vector(1, 1, 2))
  }

  test("invalid transactions create only their collateral return and consume collateral") {
    val a =
      tx(0, Vector(TestLedger.txIn(0)), collateral = Vector(TestLedger.txIn(1)), valid = false)
    val returns = TxIn(a.body.id, a.body.outputs.size)
    val b = tx(1, Vector(returns))
    val c = tx(2, Vector(created(a)))
    val d = tx(3, refs = a.body.collateral)
    assertEquals(TxDependencies.levels(Vector(a, b, c, d), Set(0, 1, 2, 3)), Vector(1, 2, 1, 2))
  }

  test("a chain of 20,000 outside-supplied transactions is stack safe") {
    val chain = (0 until 20000).foldLeft(Vector.empty[Tx]) { (xs, i) =>
      xs :+ tx(i, xs.lastOption.toVector.map(created))
    }
    val result = new java.util.concurrent.atomic.AtomicReference[Either[Throwable, Vector[Int]]]()
    val thread = new Thread(
      null,
      () =>
        result.set(
          try Right(TxDependencies.levels(chain, chain.indices.toSet))
          catch case scala.util.control.NonFatal(e) => Left(e)
        ),
      "dependency-small-stack",
      256 * 1024
    )
    thread.start()
    thread.join()
    assertEquals(result.get().fold(throw _, identity).lastOption, Some(20000))
  }
