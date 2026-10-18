// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.protocol.ProtocolError

import cats.effect.{ExitCode, IO, IOApp}
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.protocol.{ChainSync, EbHash, EbPoint, Handshake, LeiosFetch}
import scala.concurrent.duration.*

/** Read-only probe of a public Leios relay, over one connection, one request in flight: the
  * handshake, ChainSync from a known point until the relay's tip, then, with `notify`, one
  * LeiosNotify pull (protocol 18), and with `fetch`, one LeiosFetch request (protocol 19) for the
  * endorser block the newest announcing header names. What comes back, or how the relay ends the
  * connection, is printed. The ChainSync payload is kept as bytes: the announcement is read out of
  * the header body by position (`dijkstra.cddl`: element 1 is the slot, element 11 the
  * `eb_references_announcement`), so this needs no ledger decoder.
  *
  * `network/Test/runMain xyz.kd5ujc.scarab.network.LeiosChainProbe <host> <port> <magic> <version>
  * <slot>.<hash> notify|fetch|none`. Throwaway tool: not part of the node, not run by `check`.
  */
object LeiosChainProbe extends IOApp:
  /** The payload as it arrives: a single bounded CBOR item the codec layer already checked. */
  private val raw: ChainSync.PayloadCodec[Bytes] = new ChainSync.PayloadCodec[Bytes]:
    def encode(value: Bytes): Either[ProtocolError, Bytes] = Right(value)
    def decode(bytes: Bytes): Either[ProtocolError, Bytes] = Right(bytes)

  private val limits = Cbor.Limits(70000, 16, 256, 70000)

  private def point(text: String): Option[ChainSync.Point] = text.split('.') match
    case Array(slot, hash) =>
      for
        s <- slot.toLongOption.flatMap(ChainSync.UInt64.from(_).toOption)
        h <- Bytes.fromHex(hash).toOption
      yield ChainSync.Point.Block(s, h)
    case _ => None

  def run(args: List[String]): IO[ExitCode] = args match
    case host :: port :: magic :: version :: from :: step :: Nil =>
      (point(from), Host.fromString(host), Port.fromInt(port.toInt)).tupled match
        case Some((start, h, p)) =>
          probe(SocketAddress(h, p), magic.toLong, version.toInt, start, step).as(ExitCode.Success)
        case None => IO.println("bad arguments").as(ExitCode.Error)
    case _ =>
      IO.println("usage: LeiosChainProbe host port magic version slot.hash notify|fetch|none")
        .as(ExitCode.Error)

  /** The endorser block a header envelope `[era, tag24(header)]` announces, if it does. */
  private def announcement(envelope: Bytes): Option[EbPoint] =
    for
      root <- Cbor.decode(envelope, limits).toOption
      payload <- root.value match
        case Value.Arr(Vector(_, p)) => Some(p)
        case _                       => None
      inner <- payload.value match
        case Value.Tag(_, n) => Some(n)
        case _               => None
      header <- inner.value match
        case Value.ByteString(b) => Cbor.decode(b, limits).toOption
        case _                   => None
      body <- header.value match
        case Value.Arr(Vector(b, _)) => Some(b)
        case _                       => None
      fields <- body.value match
        case Value.Arr(xs) if xs.size == 12 => Some(xs)
        case _                              => None
      slot <- fields(1).value match
        case Value.UInt(s) => Some(s.toLong)
        case _             => None
      eb <- fields(11).value match
        case Value.Arr(Vector(h, _)) =>
          h.value match
            case Value.ByteString(b) => EbHash.fromBytes(b)
            case _                   => None
        case _ => None
    yield EbPoint(slot, eb)

  private def probe(
      addr: SocketAddress[Host],
      magic: Long,
      version: Int,
      from: ChainSync.Point,
      step: String
  ): IO[Unit] =
    val protocols = Set(
      Protocols.Handshake,
      Protocols.ChainSync,
      Protocols.BlockFetch,
      Protocols.KeepAlive,
      Protocols.LeiosNotify,
      Protocols.LeiosFetch
    )
    Handle
      .allow[NetworkError] {
        Bearer.tcp[IO](addr, 10.seconds).flatMap(Mux.resource[IO](_, protocols)).use { mux =>
          for
            agreed <- mux
              .channel(Protocols.Handshake)
              .flatMap(
                HandshakeClient
                  .run(_, Vector(version -> Handshake.Data(magic, initiatorOnly = true)))
              )
            _ <- IO.println(s"handshake: version ${agreed.version}")
            _ <- NodeToNode.clients[IO, Bytes](mux, Mux.Role.Initiator, agreed.version, raw).use {
              conn =>
                conn.chainSync.findIntersect(Vector(from)).flatMap {
                  case None => IO.println("no intersection")
                  case Some((_, tip)) =>
                    for
                      _ <- IO.println(s"tip: block ${tip.blockNo.value}")
                      updates <- conn.chainSync.requestNextBatch(8)
                      announced = updates.collect { case ChainSyncClient.Update.RollForward(h, _) =>
                        announcement(h)
                      }.flatten
                      _ <- IO.println(s"${updates.size} headers, ${announced.size} announcements")
                      _ <- step match
                        case "notify" => notifyStep(mux)
                        case "fetch"  => announced.lastOption.traverse_(fetchStep(mux, _))
                        case _        => IO.unit
                    yield ()
                }
            }
          yield ()
        }
      }
      .rescue(e => IO.println(s"ERROR ${e.message}"))

  private def notifyStep(mux: Mux[IO])(using Handle[IO, NetworkError]): IO[Unit] =
    for
      notify <- mux
        .channel(Protocols.LeiosNotify)
        .map(LeiosNotifyClient[IO](_, LeiosNotifyClient.Settings(busyTimeout = 30.seconds)))
      r <- Handle[IO, NetworkError].attempt(notify.requestNext)
      _ <- r match
        case Right(o) => IO.println(s"notify: ${o.message.toString.take(120)}")
        case Left(e)  => IO.println(s"notify failed: ${e.message}")
    yield ()

  private def fetchStep(mux: Mux[IO], eb: EbPoint)(using Handle[IO, NetworkError]): IO[Unit] =
    for
      fetch <- mux
        .channel(Protocols.LeiosFetch)
        .map(LeiosFetchClient[IO](_, LeiosFetchClient.Settings(timeout = 30.seconds)))
      _ <- IO.println(s"fetching the endorser block at slot ${eb.slot} ${eb.hash.bytes.hex}")
      r <- Handle[IO, NetworkError].attempt(fetch.block(eb))
      _ <- r match
        case Right(o) =>
          o.message match
            case LeiosFetch.Message.Block(txs) =>
              IO.println(s"endorser block: ${txs.size} entries, ${o.raw.size} bytes")
            case other => IO.println(s"fetch: ${other.toString.take(120)}")
        case Left(e) => IO.println(s"fetch failed: ${e.message}")
    yield ()
end LeiosChainProbe
