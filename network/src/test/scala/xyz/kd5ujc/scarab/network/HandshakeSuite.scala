// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Handshake
import munit.CatsEffectSuite
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.io.Source
import scala.concurrent.duration.*

import Peers.*

class HandshakeSuite extends FailsOnUncaught:
  private val fixturePrefix = "/upstream/ouroboros-network-protocol-codecs/"

  private def fixtureRows(name: String, expectedSha256: String): Vector[Vector[String]] =
    val stream = Option(getClass.getResourceAsStream(fixturePrefix + name))
      .getOrElse(fail(s"missing fixture $name"))
    val raw =
      try stream.readAllBytes()
      finally stream.close()
    val digest = MessageDigest
      .getInstance("SHA-256")
      .digest(raw)
      .map(b => f"${b & 0xff}%02x")
      .mkString
    assertEquals(digest, expectedSha256, s"$name fixture SHA-256")
    Source
      .fromString(new String(raw, StandardCharsets.UTF_8))
      .getLines()
      .map(_.split("\\t", -1).toVector)
      .toVector

  private def fixtureBytes(hex: String): Bytes =
    Bytes.fromHex(hex).fold(error => fail(error.toString), identity)

  private def upstreamOffers(hex: String): Vector[(Int, Handshake.Data)] =
    val wire = fixtureBytes(hex)
    val message = Handshake
      .decode(Handshake.Suite.NodeToNode, Handshake.State.Propose, wire)
      .fold(error => fail(error.message), identity)
    assertEquals(
      Handshake.encode(Handshake.Suite.NodeToNode, message).fold(e => fail(e.message), identity),
      wire
    )
    message match
      case Handshake.Message.Propose(versions) =>
        versions.map { case (version, data) =>
          version -> Handshake
            .decodeData(Handshake.Suite.NodeToNode, version, data)
            .fold(error => fail(error.message), identity)
        }
      case other => fail(s"expected original upstream proposal, got $other")

  private def simultaneousClients(
      leftOffers: Vector[(Int, Handshake.Data)],
      rightOffers: Vector[(Int, Handshake.Data)]
  ): IO[Either[
    NetworkError,
    (Either[NetworkError, HandshakeClient.Agreed], Either[NetworkError, HandshakeClient.Agreed])
  ]] =
    typed {
      linked(
        protocols = Set(0),
        client = Mux.Config(duplex = true),
        server = Mux.Config(role = Mux.Role.Responder, duplex = true)
      ).use { (left, right) =>
        for
          leftChannel <- left.channel(0)
          rightChannel <- right.channel(0)
          results <- (
            typed(HandshakeClient.run(leftChannel, leftOffers, timeout = 5.seconds)),
            typed(HandshakeClient.run(rightChannel, rightOffers, timeout = 5.seconds))
          ).parTupled
        yield results
      }
    }

  private def mergeData(left: Handshake.Data, right: Handshake.Data): Handshake.Data =
    Handshake.Data(
      left.magic,
      initiatorOnly = left.initiatorOnly || right.initiatorOnly,
      peerSharing = left.peerSharing && right.peerSharing,
      query = left.query || right.query,
      peras = left.peras && right.peras
    )

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
      case Left(NetworkError.HandshakeRefused(reason)) =>
        assertEquals(
          reason,
          NetworkError.HandshakeFailure.Remote(
            Handshake.Reason.Refused(15, "network magic mismatch")
          )
        )
      case other => fail(s"unexpected $other")
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

  test("replay independent upstream simultaneous-open proposals through both production clients"):
    val input = fixtureRows(
      "cardano-handshake-simopen.tsv",
      "55b8b11d121b2061e37004114046aef55a4326c8e3d2a79bdeb9bcc126b78527"
    )
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 9)
      assertEquals(fields(0), "cardano-handshake-simopen-v1")
      val ordinal = fields(2).toInt
      assertEquals(fields(1).toInt, 502001 + ordinal)
      assertEquals(fields(3).toInt, 60 + ordinal % 31)
      assertEquals(fields(4), "pass", s"unchanged upstream property at seed ${fields(1)}")
      val leftOffers = upstreamOffers(fields(7))
      val rightOffers = upstreamOffers(fields(8))
      val common = leftOffers.map(_._1).toSet.intersect(rightOffers.map(_._1).toSet)
      val highestCommon = common.toVector.sorted.lastOption
      (fields, leftOffers, rightOffers, highestCommon)
    }
    outcomes
      .traverse { case (fields, leftOffers, rightOffers, highestCommon) =>
        simultaneousClients(leftOffers, rightOffers).map {
          case Left(error) => fail(s"mux setup failed at seed ${fields(1)}: $error")
          case Right((left, right)) =>
            val expected: (
                Either[NetworkError, HandshakeClient.Agreed],
                Either[NetworkError, HandshakeClient.Agreed]
            ) =
              highestCommon match
                case None =>
                  (
                    Left(NetworkError.VersionMismatch(leftOffers.map(_._1), leftOffers.map(_._1))),
                    Left(NetworkError.VersionMismatch(rightOffers.map(_._1), rightOffers.map(_._1)))
                  )
                case Some(version) =>
                  val local = leftOffers
                    .collectFirst { case (`version`, data) => data }
                    .getOrElse(fail(s"missing local common version $version"))
                  val remote = rightOffers
                    .collectFirst { case (`version`, data) => data }
                    .getOrElse(fail(s"missing remote common version $version"))
                  if local.magic != remote.magic then
                    val refusal = Left(
                      NetworkError.HandshakeRefused(
                        NetworkError.HandshakeFailure.Remote(
                          Handshake.Reason.Refused(version, "network magic mismatch")
                        )
                      )
                    )
                    (refusal, refusal)
                  else
                    val agreed = Right(HandshakeClient.Agreed(version, mergeData(local, remote)))
                    (agreed, agreed)
            assertEquals(left, expected._1, s"left production client at seed ${fields(1)}")
            assertEquals(right, expected._2, s"right production client at seed ${fields(1)}")
            assertEquals(fields(5), "none")
            assertEquals(fields(6), "none")
            expected._1
        }
      }
      .map { replayed =>
        assertEquals(replayed.size, 64)
        assertEquals(replayed.count(_.isLeft), 64)
        assertEquals(outcomes.count(_._4.isEmpty), 3)
        assertEquals(outcomes.count(_._4.nonEmpty), 61)
      }

  test("replay correlated upstream simultaneous-open proposals through both production clients"):
    val input = fixtureRows(
      "cardano-handshake-simopen-correlated.tsv",
      "2e5807e8f26d65667ead37afdbee7c18f40091a5c585ff0f9869a5fbcfe2fb04"
    )
    val outcomes = input.map { fields =>
      assertEquals(fields.size, 9)
      assertEquals(fields(0), "cardano-handshake-simopen-correlated-v1")
      val ordinal = fields(2).toInt
      assertEquals(fields(1).toInt, 503001 + ordinal)
      assertEquals(fields(3).toInt, 60 + ordinal % 31)
      assertEquals(fields(4), "pass", s"unchanged upstream property at seed ${fields(1)}")
      assertEquals(fields(7), fields(8), s"correlated original proposal bytes at seed ${fields(1)}")
      val offers = upstreamOffers(fields(7))
      val expectedApplication = fields(5) match
        case "none"  => None
        case "true"  => Some(true)
        case "false" => Some(false)
        case other   => fail(s"unknown upstream application outcome $other")
      assertEquals(fields(6), fields(5), s"symmetric upstream application at seed ${fields(1)}")
      (fields, offers, expectedApplication)
    }
    outcomes
      .traverse { case (fields, offers, expectedApplication) =>
        simultaneousClients(offers, offers).map {
          case Left(error) => fail(s"mux setup failed at seed ${fields(1)}: $error")
          case Right((left, right)) =>
            val expected = expectedApplication match
              case None => Left(NetworkError.VersionMismatch(Vector.empty, Vector.empty))
              case Some(_) =>
                val (version, data) = offers
                  .maxByOption(_._1)
                  .getOrElse(
                    fail(s"accepted upstream outcome with an empty offer map at seed ${fields(1)}")
                  )
                Right(HandshakeClient.Agreed(version, data))
            assertEquals(left, expected, s"left production client at seed ${fields(1)}")
            assertEquals(right, expected, s"right production client at seed ${fields(1)}")
            expectedApplication
        }
      }
      .map { replayed =>
        assertEquals(replayed.size, 64)
        assertEquals(replayed.count(_.contains(true)), 32)
        assertEquals(replayed.count(_.contains(false)), 31)
        assertEquals(replayed.count(_.isEmpty), 1)
      }
