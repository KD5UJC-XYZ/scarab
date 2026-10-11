// SPDX-License-Identifier: Apache-2.0
package lab.node

import lab.node.ledger.{ExUnits, TestLedger}
import munit.FunSuite

class BlockExUnitsSuite extends FunSuite:
  private val limit = ExUnits(1000, 5000)
  private def params(major: Int) = TestLedger.params(major).copy(maxBlockExUnits = limit)

  test("units within the block limit pass, at the limit too") {
    assertEquals(NodeRules.blockExUnits(params(9), ExUnits(1000, 5000)), Right(()))
    assertEquals(NodeRules.blockExUnits(params(9), ExUnits(1, 1)), Right(()))
  }

  test("either dimension over the limit rejects the block") {
    assert(NodeRules.blockExUnits(params(9), ExUnits(1001, 1)).isLeft)
    assert(NodeRules.blockExUnits(params(9), ExUnits(1, 5001)).isLeft)
  }

  test("before Alonzo there are no execution units to bound") {
    assertEquals(NodeRules.blockExUnits(params(4), ExUnits(9999, 9999)), Right(()))
  }
