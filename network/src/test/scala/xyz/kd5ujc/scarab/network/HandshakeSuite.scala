// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Handshake
import munit.CatsEffectSuite

import Peers.*

class HandshakeSuite extends FailsOnUncaught:
  private def negotiate(serverOffers: Vector[(Int, Handshake.Data)], clientMagic: Long = 42L) =
    typed {
      linked(protocols = Set(0)).use { (client, server) =>
        for
          c <- client.channel(0)
          s <- server.channel(0)
          _ <- serveHandshake(s, serverOffers).start
          agreed <- HandshakeClient.run(c, Handshake.defaultNodeToNode(clientMagic))
        yield agreed
      }
    }

  test("version 15, the highest of the latest release, is negotiated with the shared magic"):
    negotiate(Handshake.defaultNodeToNode(42L)).map(r =>
      assertEquals(r, Right(HandshakeClient.Agreed(15, Handshake.Data(42L))))
    )

  test("a peer that offers 14 only is met on 14"):
    negotiate(Vector(14 -> Handshake.Data(42L))).map(r =>
      assertEquals(r, Right(HandshakeClient.Agreed(14, Handshake.Data(42L))))
    )

  test("a different network magic is refused with the peer's reason"):
    negotiate(Handshake.defaultNodeToNode(7L)).map {
      case Left(NetworkError.HandshakeRefused(reason)) => assert(reason.contains("magic"), reason)
      case other                                       => fail(s"unexpected $other")
    }

  test("disjoint versions are a typed version mismatch"):
    negotiate(Vector(16 -> Handshake.Data(42L))).map(r =>
      assertEquals(r, Left(NetworkError.VersionMismatch(Vector(14, 15), Vector(16))))
    )

  test("a malformed reply is a decode error"):
    typed {
      linked(protocols = Set(0)).use { (client, server) =>
        for
          c <- client.channel(0)
          s <- server.channel(0)
          _ <- (s.recv(5760)(b =>
            xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult.Decoded((), b.size)
          ) *>
            s.send(Bytes(Vector(-1)))).start
          agreed <- HandshakeClient.run(c, Handshake.defaultNodeToNode(42L))
        yield agreed
      }
    }.map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.Decode]), r.toString))

  test("a silent peer times out in the confirm state"):
    import cats.effect.testkit.TestControl
    TestControl
      .executeEmbed(typed {
        linked(protocols = Set(0)).use { (client, _) =>
          client.channel(0).flatMap(HandshakeClient.run(_, Handshake.defaultNodeToNode(42L)))
        }
      })
      .map(r => assertEquals(r, Left(NetworkError.Timeout(0, "confirm"))))
