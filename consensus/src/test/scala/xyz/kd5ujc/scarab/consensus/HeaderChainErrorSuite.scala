// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.Fixtures

class HeaderChainErrorSuite extends FunSuite:
  test("a disconnected header retains its hash and the candidate tip in the typed failure") {
    val base = Fixtures.chain(1).head
    val candidate = HeaderChain(base.point, Some(1), Vector.empty)
    val orphan = Fixtures.header(2, 7, None)
    assertEquals(
      candidate.extend(orphan, Some(2)),
      Left(HeaderChain.Error.DoesNotExtend(orphan.hash, base.point))
    )
    assertEquals(candidate.tipPoint, base.point)
    assertEquals(candidate.tipState, Some(1))
  }
