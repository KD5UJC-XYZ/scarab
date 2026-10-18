// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.IO
import com.comcast.ip4s.Host
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.ledger.PeerError

class PeerResolutionSuite extends CatsEffectSuite:
  private val host = Host.fromString("relay.example").fold(fail("fixture host"))(identity)

  test("DNS failure remains recoverable with the original operating-system cause") {
    val cause = new java.net.UnknownHostException("relay.example")
    PeerNetwork.resolve(host, IO.raiseError[Int](cause)).map { result =>
      assertEquals(
        result,
        Left(PeerError.Unavailable(PeerError.Unavailability.Resolution("relay.example", cause)))
      )
    }
  }

  test("a resolver defect reaches the worker without becoming a recoverable peer failure") {
    val cause = new IllegalStateException("broken resolver invariant")
    PeerNetwork.resolve(host, IO.raiseError[Int](cause)).attempt.map { result =>
      assertEquals(result, Left(cause))
    }
  }

  test("successful name resolution is unchanged") {
    PeerNetwork.resolve(host, IO.pure(42)).map(result => assertEquals(result, Right(42)))
  }
