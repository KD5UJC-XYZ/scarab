// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import com.comcast.ip4s.*
import fs2.io.net.Network
import xyz.kd5ujc.scarab.protocol.ConnectionIdentityError

class ConnectionIdSuite extends FailsOnUncaught:
  private def key(value: Either[ConnectionIdentityError, ConnectionId]): ConnectionId =
    value.fold(error => fail(error.message), identity)
  private def present[A](value: Option[A]): A = value.fold(fail("TCP identity is absent"))(identity)
  private def port(endpoint: ConnectionId.Endpoint): Int = endpoint match
    case ConnectionId.Endpoint.Inet4(p, _)                => p.value
    case ConnectionId.Endpoint.Inet6(p, _, _, _, _, _, _) => p.value

  for host <- Vector(ip"127.0.0.1", ip"::1") do
    test(s"actual TCP $host endpoints are retained through the mux in both directions") {
      Peers
        .typed {
          Network[IO].bind(SocketAddress(host, port"0")).use { server =>
            for
              received <- Deferred[IO, Either[ConnectionIdentityError, ConnectionId]]
              accept = server.accept
                .take(1)
                .evalMap { socket =>
                  present(Bearer.fromSocket(socket).connectionId)
                    .flatMap(value => received.complete(value).void *> IO.never[Unit])
                }
                .compile
                .drain
              _ <- accept.background.use { _ =>
                Network[IO].connect(server.address).use { socket =>
                  Mux.resource(Bearer.fromSocket(socket), Set.empty).use { mux =>
                    received.get.map { peer =>
                      val ours = key(present(mux.connectionId))
                      val theirs = key(peer)
                      assertEquals(ours.local, theirs.remote)
                      assertEquals(ours.remote, theirs.local)
                      assertEquals(port(ours.remote), server.address.asIpUnsafe.port.value)
                      assert(port(ours.local) > 0)
                    }
                  }
                }
              }
            yield ()
          }
        }
        .map(result => assertEquals(result, Right(())))
    }

  test("mux acquires transport metadata once and preserves a typed acquisition error") {
    Peers
      .typed {
        for
          read <- Ref.of[IO, Int](0)
          pair <- Bearer.inMemory[IO]()
          problem = ConnectionIdentityError.NonNumericScope("fixture-interface")
          bearer = new Bearer[IO]:
            override def connectionId = Some(read.update(_ + 1).as(Left(problem)))
            def reads = pair._1.reads
            def write(bytes: fs2.Chunk[Byte]) = pair._1.write(bytes)
            def close = pair._1.close
          _ <- Mux.resource(bearer, Set.empty).use { mux =>
            IO(assertEquals(mux.connectionId, Some(Left(problem)))) *>
              IO(assertEquals(mux.connectionId, Some(Left(problem))))
          }
          times <- read.get
        yield assertEquals(times, 1)
      }
      .map(result => assertEquals(result, Right(())))
  }

  test("numeric IPv6 scope retains all unsigned32 bits and malformed scopes stay typed") {
    val ip = Ipv6Address.fromString("fe80::1").fold(fail("IPv6 literal"))(identity)
    val address = SocketAddress(ip.withScopeId("4294967295"), port"3001")
    val converted = ConnectionId.Endpoint.fromSocketAddress(address)
    converted match
      case Right(ConnectionId.Endpoint.Inet6(_, _, _, _, _, _, scope)) =>
        assertEquals(scope.value, 0xffffffffL)
      case other => fail(other.toString)
    assertEquals(
      ConnectionId.Endpoint.fromSocketAddress(
        SocketAddress(ip.withScopeId("eth-fixture"), port"3001")
      ),
      Left(ConnectionIdentityError.NonNumericScope("eth-fixture"))
    )
    assertEquals(
      ConnectionId.Endpoint.fromSocketAddress(
        SocketAddress(ip.withScopeId("4294967296"), port"3001")
      ),
      Left(ConnectionIdentityError.ScopeOutOfRange(BigInt("4294967296")))
    )
  }
