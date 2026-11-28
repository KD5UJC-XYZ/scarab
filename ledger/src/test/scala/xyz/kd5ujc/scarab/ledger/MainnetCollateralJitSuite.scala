// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import scala.annotation.tailrec
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.state.Babbage

/** `TxChecks.collateral` once C2 has compiled it (#1521). On Temurin 25.0.4.1, with
  * `ReduceAllocationMerges` on (the default), C2 compiled the method with `Value.sum` inlined so
  * that the fee came out null; a phase-2-invalid transaction then threw in `ShelleyUtxo.update`,
  * and the node withheld its block for good. This suite runs in a JVM of its own (`build.sbt`, the
  * `c2-collateral` group) with `-Xbatch`, which compiles each method before it runs again, and two
  * processors, the options under which the method as it was gave null fees in every run measured,
  * and without `-XX:-ReduceAllocationMerges`, so that it holds on the default JIT.
  */
class MainnetCollateralJitSuite extends FunSuite:
  private val Calls = 20_000_000

  /** The calls, of the cases in turn, whose fee is null, at most `limit` of them, and the fee of
    * the last call (so that the results escape, as they do in the rules).
    */
  @tailrec
  private def nullFees(
      cases: Vector[MainnetCollateral.Case],
      i: Int,
      found: Vector[Int],
      last: BigInt
  ): (Vector[Int], BigInt) =
    if i == Calls || found.size == 8 then (found, last)
    else
      val c = cases(i % cases.size)
      val fee = TxChecks.collateral[Babbage](c.utxo, c.tx).fee
      nullFees(cases, i + 1, if fee eq null then found :+ i else found, fee)

  test(s"the collateral of the mainnet transactions has its fee in $Calls calls under C2") {
    val cases = MainnetCollateral.cases
    val (found, _) = nullFees(cases, 0, Vector.empty, BigInt(0))
    assertEquals(found, Vector.empty[Int], "calls whose collateral had a null fee")
  }
