// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.AcceptedConnectionsLimit.RateLimitDelay.*

/** The accepted-connection limits against the reference's `Server/RateLimiting.hs`
  * (`getRateLimitDecision`, `runConnectionRateLimits`; ouroboros-network 2fb69829).
  */
class AcceptedConnectionsLimitSuite extends FailsOnUncaught:
  private val reference = AcceptedConnectionsLimit.default

  private def peer(n: Int): SocketAddress[IpAddress] =
    SocketAddress(IpAddress.fromString(s"10.0.${n / 250}.${n % 250}").get, Port.fromInt(3001).get)

  test("the defaults are the reference's: hard 512, soft 384, delay 5 s") {
    assertEquals(reference, AcceptedConnectionsLimit(512, 384, 5.seconds))
    assertEquals(ConnectionManager.Limits().accepted, reference)
    assertEquals(ConnectionManager.Limits().maxInboundPerAddress, 64)
  }

  test("below the soft limit nothing, then a delay linear up to the hard limit, then a wait") {
    def at(n: Int) = AcceptedConnectionsLimit.decision(n, reference)
    assertEquals(at(0), NoRateLimiting)
    assertEquals(at(383), NoRateLimiting)
    assertEquals(at(384), SoftDelay(Duration.Zero))
    assertEquals(at(448), SoftDelay(2500.millis))
    assertEquals(at(511), SoftDelay(5.seconds * 127 / 128))
    assertEquals(at(512), HardLimit(512))
    assertEquals(at(900), HardLimit(512))
  }

  test("a soft limit at or above the hard limit does not divide by zero") {
    val odd = AcceptedConnectionsLimit(4, 4, 5.seconds)
    assertEquals(AcceptedConnectionsLimit.decision(3, odd), NoRateLimiting)
    assertEquals(AcceptedConnectionsLimit.decision(4, odd), HardLimit(4))
  }

  private val small = AcceptedConnectionsLimit(4, 2, 4.seconds)

  /** Hold `held` inbound connections and run the accept loop's rate limit; after `releaseAfter` the
    * first one is released. The result is how long the rate limit took and what it traced.
    */
  private def waited(
      held: Int,
      releaseAfter: Option[FiniteDuration]
  ): IO[(FiniteDuration, List[ConnectionEvent])] =
    TestControl.executeEmbed {
      Ref.of[IO, List[ConnectionEvent]](Nil).flatMap { seen =>
        val sink: ConnectionEvents[IO] = e => seen.update(_ :+ e)
        Handle
          .allow[ConnectionError] {
            ConnectionManager.create[IO](ConnectionManager.Limits(small)).flatMap { cm =>
              (0 until held).toList.traverse(i => cm.inbound(peer(i)).allocated).flatMap { open =>
                val release = releaseAfter.traverse_(d => IO.sleep(d) *> open.head._2)
                for
                  start <- IO.monotonic
                  _ <- release.start
                  _ <- AcceptedConnectionsLimit.rateLimit(cm, small, sink)
                  end <- IO.monotonic
                  traced <- seen.get
                yield (end - start, traced).asRight[ConnectionError]
              }
            }
          }
          .rescue(e => IO.pure(e.asLeft[(FiniteDuration, List[ConnectionEvent])]))
          .map(_.fold(e => fail(e.message), identity))
      }
    }

  test("below the soft limit the accept is not delayed and nothing is traced") {
    waited(1, None).map(assertEquals(_, (Duration.Zero, Nil)))
  }

  test("between the limits the next accept is delayed in proportion to the excess") {
    // 3 held, soft 2, hard 4: 4 s * (3 - 2) / (4 - 2)
    waited(3, None).map(
      assertEquals(_, (2.seconds, List(ConnectionEvent.AcceptRateLimited(2.seconds, 3))))
    )
  }

  test("at the hard limit the loop waits for a drop, and at least the delay from its start") {
    // dropped after 1 s: it still pauses for the full delay
    waited(4, Some(1.second)).map(
      assertEquals(
        _,
        (4.seconds, List(ConnectionEvent.AcceptHardLimit(4), ConnectionEvent.AcceptResumed(3)))
      )
    )
  }

  test("at the hard limit the loop resumes as soon as the drop comes after the delay") {
    waited(4, Some(6.seconds)).map(
      assertEquals(
        _,
        (6.seconds, List(ConnectionEvent.AcceptHardLimit(4), ConnectionEvent.AcceptResumed(3)))
      )
    )
  }
