// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.util.concurrent.atomic.AtomicInteger
import munit.FunSuite
import xyz.kd5ujc.scarab.uplc.flat.ScriptDecodeError
import xyz.kd5ujc.scarab.uplc.ledger.Unavailability

class BoundedCacheSuite extends FunSuite:
  test("the decoder's cache skips unavailable results and retains known malformed input") {
    type Result = Either[ScriptDecodeFailure, Int]
    val cache = BoundedCache[String, Result](1)
    val calls = AtomicInteger(0)
    val retained: Result => Boolean = {
      case Left(_: ScriptDecodeFailure.Unavailable) => false
      case _                                        => true
    }
    def lookup(key: String)(value: Result): Result =
      cache.getOrElseUpdate(key, { calls.incrementAndGet(); value })(retained)
    val unavailable = ScriptDecodeFailure.Unavailable(
      BackendFailure.Uplc(Unavailability.Thrown(new IllegalStateException("injected")))
    )
    assertEquals(lookup("valid")(Right(1)), Right(1))
    assert(lookup("retry")(Left(unavailable)).isLeft)
    // An unavailable attempt did not consume the single cache slot or evict the known program.
    assertEquals(lookup("valid")(Right(2)), Right(1))
    val malformed =
      Left(ScriptDecodeFailure.Malformed(ScriptProblem.Uplc(ScriptDecodeError.ExpectedBytes)))
    assertEquals(lookup("bad")(malformed), malformed)
    assertEquals(lookup("bad")(Right(3)), malformed)
    assertEquals(lookup("retry")(Right(4)), Right(4))
    assertEquals(lookup("retry")(Right(5)), Right(4))
    assertEquals(calls.get(), 4)
  }
