// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import lab.network.{Handshake, LeiosFetch, LeiosNotify}
import scala.concurrent.duration.*

/** Read-only probe of a public Leios relay: handshake, KeepAlive, then LeiosNotify (18) pulls for a
  * fixed time and, for the first few offered endorser blocks, one LeiosFetch (19) block request
  * each. Every raw message is appended to a file as `ms<TAB>protocol<TAB>kind<TAB>hex`.
  *
  * Run, inside the build container:
  * `networkRuntime/Test/runMain lab.network.runtime.LeiosProbe <host> <port> <magic> <seconds> <outfile>`.
  * Throwaway tool: not part of the node, not run by `check`.
  */
object LeiosProbe extends IOApp:
  private final case class Tally(counts: Map[String, Int], fetched: Int)

  def run(args: List[String]): IO[ExitCode] = args match
    case host :: port :: magic :: seconds :: out :: rest if rest.contains("listen") =>
      listen(host, port.toInt, magic.toLong, seconds.toInt.seconds).as(ExitCode.Success)
    case host :: port :: magic :: seconds :: out :: rest =>
      probe(
        host,
        port.toInt,
        magic.toLong,
        seconds.toInt.seconds,
        Path.of(out),
        rest.contains("ping-only"),
        rest.contains("peras")
      )
        .as(ExitCode.Success)
    case _ => IO.println("usage: LeiosProbe host port magic seconds outfile").as(ExitCode.Error)

  /** Duplex connection that only listens: does the relay start its own LeiosNotify client toward
    * us?
    */
  private def listen(host: String, port: Int, magic: Long, duration: FiniteDuration): IO[Unit] =
    Handle
      .allow[NetworkError] {
        val addr = SocketAddress(Host.fromString(host).get, Port.fromInt(port).get)
        val protocols =
          Set(Protocols.Handshake, Protocols.KeepAlive, Protocols.LeiosNotify, Protocols.LeiosFetch)
        (for
          bearer <- Bearer.tcp[IO](addr, 10.seconds)
          mux <- Mux.resource[IO](bearer, protocols, Mux.Config(duplex = true))
        yield mux).use { mux =>
          for
            hs <- mux.channel(Protocols.Handshake, Mux.Role.Initiator)
            agreed <- HandshakeClient.run(
              hs,
              Vector(14, 15, 16).map(_ -> Handshake.Data(magic, initiatorOnly = false))
            )
            _ <- IO.println(s"duplex handshake: version ${agreed.version} data ${agreed.data}")
            ka <- mux.channel(Protocols.KeepAlive, Mux.Role.Initiator)
            n <- mux.channel(Protocols.LeiosNotify, Mux.Role.Responder)
            f <- mux.channel(Protocols.LeiosFetch, Mux.Role.Responder)
            first = n.recv(2500000)(bytes =>
              LeiosDecode.result(LeiosNotify.decodePrefix(LeiosNotify.State.Idle, bytes), bytes)
            )
            _ <- KeepAliveClient.background[IO](ka, mux).use { _ =>
              IO.race(IO.sleep(duration), first).flatMap {
                case Left(_) => IO.println("listen: nothing received on 18")
                case Right(o) =>
                  IO.println(s"listen: received on 18: ${o.message} ${o.raw.hex.take(120)}")
              }
            }
            _ <- IO.println(f.protocol.toString)
          yield ()
        }
      }
      .rescue(e => IO.println(s"ERROR ${e.message}"))

  private def probe(
      host: String,
      port: Int,
      magic: Long,
      duration: FiniteDuration,
      out: Path,
      pingOnly: Boolean,
      peras: Boolean
  ): IO[Unit] =
    Handle
      .allow[NetworkError] {
        val addr = SocketAddress(Host.fromString(host).get, Port.fromInt(port).get)
        val protocols =
          Set(Protocols.Handshake, Protocols.KeepAlive, Protocols.LeiosNotify, Protocols.LeiosFetch)
        val resources =
          for
            bearer <- Bearer.tcp[IO](addr, 10.seconds)
            mux <- Mux.resource[IO](bearer, protocols)
          yield mux
        resources.use { mux =>
          for
            start <- IO.monotonic
            log = (protocol: Int, kind: String, hex: String) =>
              IO.monotonic.flatMap { now =>
                val line = s"${(now - start).toMillis}\t$protocol\t$kind\t$hex\n"
                IO.blocking(
                  Files.write(
                    out,
                    line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                  )
                ).void
              }
            hs <- mux.channel(Protocols.Handshake)
            offers = (if peras then Vector(16) else Vector(14, 15, 16)).map(
              _ -> Handshake.Data(magic, initiatorOnly = true, peras = peras)
            )
            agreed <- HandshakeClient.run(hs, offers)
            _ <- IO.println(s"handshake: version ${agreed.version}")
            ka <- mux.channel(Protocols.KeepAlive)
            tally <- Ref.of[IO, Tally](Tally(Map.empty, 0))
            notify <- mux.channel(Protocols.LeiosNotify).map(LeiosNotifyClient[IO](_))
            fetch <- mux.channel(Protocols.LeiosFetch).map(LeiosFetchClient[IO](_))
            count = (k: String) =>
              tally.update(t => t.copy(counts = t.counts.updated(k, t.counts.getOrElse(k, 0) + 1)))
            pullOnce: IO[Unit] =
              notify.requestNext.flatMap { obs =>
                val kind = obs.message match
                  case LeiosNotify.Message.Canceled             => "canceled"
                  case _: LeiosNotify.Message.BlockAnnouncement => "announcement"
                  case _: LeiosNotify.Message.BlockOffer        => "block-offer"
                  case _: LeiosNotify.Message.BlockTxsOffer     => "txs-offer"
                  case _: LeiosNotify.Message.Votes             => "votes"
                  case _                                        => "other"
                for
                  _ <- log(Protocols.LeiosNotify, kind, obs.raw.hex)
                  _ <- count(kind)
                  _ <- IO.println(s"notify: $kind (${obs.raw.size} bytes)")
                  _ <- obs.message match
                    case LeiosNotify.Message.BlockOffer(point, size) =>
                      tally.get.flatMap { t =>
                        if t.fetched >= 3 then IO.unit
                        else
                          tally.update(x => x.copy(fetched = x.fetched + 1)) *>
                            fetch.block(point).flatMap { b =>
                              val k = b.message match
                                case LeiosFetch.Message.Block(txs) =>
                                  s"block(${txs.size} txs, offered $size bytes)"
                                case _ => "other"
                              log(Protocols.LeiosFetch, k, b.raw.hex) *> IO.println(
                                s"fetch: $k (${b.raw.size} bytes)"
                              )
                            }
                      }
                    case _ => IO.unit
                yield ()
              }
            pings = KeepAliveClient
              .background[IO](ka, mux)
              .use(_ =>
                IO.race(IO.sleep(duration), if pingOnly then IO.never else pullOnce.foreverM).void
              )
            _ <- pings
            t <- tally.get
            _ <- IO.println(s"done after $duration: ${t.counts}, fetched ${t.fetched}")
          yield ()
        }
      }
      .rescue(e => IO.println(s"ERROR ${e.message}"))
end LeiosProbe
