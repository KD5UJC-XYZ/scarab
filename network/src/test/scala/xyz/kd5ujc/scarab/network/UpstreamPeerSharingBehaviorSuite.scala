// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Ref, Resource}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.protocol.{KeepAlive, PeerSharing, PeerSharingEngine, ProtocolError}
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult

import Peers.*

/** Exact finite Int payloads through the production conversation and real in-memory mux pair. The
  * Int item codec instantiates the same production wire engine as the Cardano Address facade.
  */
class UpstreamPeerSharingBehaviorSuite extends FailsOnUncaught:
  private val base = Path.of("network/src/test/resources/upstream/peer-acceptance")
  private val expectedHashes = Map(
    "keepalive-byte-limits.tsv" -> "11d71ea7be554c86a6491ec87acfd6d1a727663f8fa5da0dbde03e0a98ba3c6a",
    "peersharing-behavior.tsv" -> "79be3b5848a582eef99dee2434adc3016008369cf56e9504610780e471da4771",
    "peersharing-byte-limits.tsv" -> "3010380bbaad94643973f15ab0f1bc76219942348ac60ccfdc5516887e69a06b"
  )

  private def rows(name: String): Vector[Vector[String]] =
    val bytes = Files.readAllBytes(base.resolve(name))
    val sha =
      MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString
    assertEquals(expectedHashes.get(name), Some(sha))
    new String(bytes, java.nio.charset.StandardCharsets.UTF_8).linesIterator
      .map(_.split("\t", -1).toVector)
      .toVector

  private def integers(text: String): Vector[Int] =
    assert(text.startsWith("[") && text.endsWith("]"))
    val values = text.drop(1).dropRight(1)
    if values.isEmpty then Vector.empty
    else
      values
        .split(",", -1)
        .toVector
        .map(value => value.trim.toIntOption.getOrElse(fail(s"integer $value")))
  private def amount(value: Int): PeerSharing.Amount =
    PeerSharing.Amount.from(value).getOrElse(fail(s"amount $value"))

  private def send(channel: MuxChannel[IO], state: PeerSharing.State, message: PeerSharing.Message)(
      using Net
  ): IO[Unit] =
    PeerSharing
      .encode(state, PeerSharing.Role.Client, message)
      .fold(
        e => NetworkError.IllegalTransition(10, ProtocolError.PeerSharing(e)).raise[IO, Unit],
        channel.send
      )

  private val intPeer = PeerSharingEngine.peerCodec[Int](
    n =>
      Cbor
        .encode(if n >= 0 then Value.UInt(n) else Value.NInt(n))
        .left
        .map(ProtocolError.Codec.apply),
    item =>
      Cbor.decode(item).left.map(ProtocolError.Codec.apply).flatMap { node =>
        node.value match
          case Value.UInt(value) if value.isValidInt => Right(value.toInt)
          case Value.NInt(value) if value.isValidInt => Right(value.toInt)
          case _                                     => Left(ProtocolError.ExpectedUint64)
      }
  )

  private def intReply(bytes: Bytes): DecodeResult[Vector[Int]] =
    PeerSharingEngine.decodePrefix(
      PeerSharing.State.Busy,
      PeerSharing.Role.Server,
      bytes,
      intPeer
    ) match
      case DecodeResult.NeedMore      => DecodeResult.NeedMore
      case DecodeResult.Failed(error) => DecodeResult.Failed(error)
      case DecodeResult.Decoded(PeerSharingEngine.Message.SharePeers(peers), used) =>
        DecodeResult.Decoded(peers, used)
      case DecodeResult.Decoded(_, _) => DecodeResult.Failed(ProtocolError.ExpectedCandidateArray)

  private def reply(values: Vector[Int]): Bytes =
    PeerSharingEngine
      .encode(
        PeerSharing.State.Busy,
        PeerSharing.Role.Server,
        PeerSharingEngine.Message.SharePeers(values),
        intPeer
      )
      .fold(error => fail(error.message), identity)

  test("original finite PeerSharing results survive the production generic conversation") {
    val inputs = rows("peersharing-behavior.tsv")
    assertEquals(inputs.size, 24)
    inputs.traverse_ { row =>
      assertEquals(row.size, 11)
      assertEquals(row.head, "peersharing-behavior-v1")
      assertEquals(row.drop(7), Vector.fill(4)("pass"))
      val requests = integers(row(4)).map(amount)
      val evaluations = integers(row(5))
      val expected = integers(row(6))
      assertEquals(requests.size, evaluations.size)
      typed {
        linked(protocols = Set(10)).use { (clientMux, serverMux) =>
          for
            c <- clientMux.channel(10)
            s <- serverMux.channel(10)
            position <- Ref.of[IO, Int](0)
            seen <- Ref.of[IO, Vector[PeerSharing.Amount]](Vector.empty)
            clientWire = new PeerSharingRuntime.ClientWire[IO, Int]:
              type Reply = Vector[Int]
              def request(state: PeerSharing.State, n: PeerSharing.Amount)(using
                  Raise[IO, NetworkError]
              ) =
                send(c, state, PeerSharing.Message.ShareRequest(n))
              def receive(using Raise[IO, NetworkError]) =
                c.recv(PeerSharing.UpstreamMessageBytes)(intReply)
              def peers(n: PeerSharing.Amount, values: Reply)(using Raise[IO, NetworkError]) =
                IO.pure(values)
              def done(state: PeerSharing.State)(using Raise[IO, NetworkError]) =
                send(c, state, PeerSharing.Message.Done) *> c.flush
            serverWire = new PeerSharingRuntime.ServerWire[IO, Int]:
              def command(using Raise[IO, NetworkError]) =
                s.recv(PeerSharing.UpstreamMessageBytes)(
                  PeerSharing.decoder(PeerSharing.State.Idle, PeerSharing.Role.Client)
                ).flatMap {
                  case PeerSharing.Message.ShareRequest(n) =>
                    IO.pure(PeerSharingRuntime.Command.Request(n))
                  case PeerSharing.Message.Done => IO.pure(PeerSharingRuntime.Command.Done)
                  case other =>
                    NetworkError
                      .IllegalTransition(
                        10,
                        NetworkError.RuntimeViolation.PeerSharingUnexpected(other)
                      )
                      .raise[IO, PeerSharingRuntime.Command]
                }
              def peers(n: PeerSharing.Amount, values: Vector[Int])(using Raise[IO, NetworkError]) =
                s.send(reply(values))
            source = (n: PeerSharing.Amount) =>
              seen.update(_ :+ n) *> position.modify(i => (i + 1, i)).map { i =>
                assert(i < evaluations.size)
                Vector.fill(math.max(0, evaluations(i)))(i)
              }
            api <- PeerSharingRuntime.client(clientWire, 60.seconds)
            result <- Resource
              .make(
                Fibers
                  .scoped[IO, NetworkError, Unit](NetworkError.Defect(_)) {
                    PeerSharingRuntime.server(serverWire, source)
                  }
                  .start
              )(_.cancel)
              .use { serving =>
                for
                  collected <- requests.foldLeftM(Vector.empty[Int])((acc, n) =>
                    api.request(n).map(_ ++ acc)
                  )
                  _ <- api.done
                  _ <- serving.joinWithNever.flatMap(Fibers.land(_))
                  observed <- seen.get
                yield (collected, observed)
              }
          yield result
        }
      }.map { result =>
        assertEquals(result, Right((expected, requests)), s"seed ${row(1)}")
      }
    }
  }

  test("the original byteLimits properties use Scarab's exact per-state ceilings") {
    Vector(
      "peersharing" -> PeerSharing.UpstreamMessageBytes,
      "keepalive" -> KeepAlive.UpstreamMessageBytes
    ).foreach { (protocol, maximum) =>
      val input = rows(s"$protocol-byte-limits.tsv")
      assertEquals(input.size, 24)
      input.foreach { row =>
        assertEquals(row.size, 9)
        assertEquals(row.head, s"$protocol-byte-limits-v1")
        val bytes = Bytes.fromHex(row(5)).fold(e => fail(e.message), identity)
        assertEquals(bytes.size, row(6).toInt)
        assertEquals(row(7).toInt, maximum)
        assert(bytes.size <= maximum)
        assertEquals(row(8), "pass")
        protocol match
          case "keepalive" =>
            val (state, role) = row(4) match
              case "client" => (KeepAlive.State.Client, KeepAlive.Role.Client)
              case "server" => (KeepAlive.State.Server, KeepAlive.Role.Server)
              case other    => fail(s"unexpected KeepAlive state $other")
            val message = KeepAlive.decode(state, role, bytes).fold(e => fail(e.message), identity)
            assertEquals(KeepAlive.encode(state, role, message), Right(bytes))
          case "peersharing" =>
            row(4) match
              case "idle" =>
                val message = PeerSharing
                  .decode(PeerSharing.State.Idle, PeerSharing.Role.Client, bytes)
                  .fold(e => fail(e.message), identity)
                assertEquals(
                  PeerSharing.encode(PeerSharing.State.Idle, PeerSharing.Role.Client, message),
                  Right(bytes)
                )
              case "busy" =>
                // The size ceiling is payload independent; preserve the original generic Int list.
                // It is not accepted as a Cardano Address payload.
                intReply(bytes) match
                  case DecodeResult.Decoded(_, consumed) => assertEquals(consumed, bytes.size)
                  case other => fail(s"original Int reply did not decode: $other")
              case other => fail(s"unexpected PeerSharing state $other")
          case other => fail(s"unexpected protocol $other")
      }
    }
  }
