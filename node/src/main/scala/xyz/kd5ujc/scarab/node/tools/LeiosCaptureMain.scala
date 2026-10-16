// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.mtl.Handle
import cats.syntax.all.*
import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPOutputStream
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{LeiosHeader, Header}
import xyz.kd5ujc.scarab.network.{ChainSyncClient, NetworkError, NodeToNode, NodeToNodeConnection}
import xyz.kd5ujc.scarab.protocol.{CardanoBlockFetch, ChainSync, HfcHeader}
import scala.concurrent.duration.*

/** Development tool, not part of the node: `xyz.kd5ujc.scarab.node.tools.LeiosCaptureMain`.
  *
  * Reads a chain of the Leios testnet (or any peer) read-only, over one connection:
  *
  *   - `headers --peer h:p --magic M [--from origin|<slot>.<hash>] [--headers N|all] [--batch B]
  *     [--pause-ms P] --out <file.gz>`: the ChainSync headers, pipelined `B` deep with `P`
  *     milliseconds between batches, one gzip line per header `kind TAB blockNo TAB slot TAB hash
  *     TAB raw-envelope-hex` (`kind` is the era index of the envelope), up to 12 headers behind the
  *     peer's tip;
  *   - `blocks --peer h:p --magic M --points <slot.hash,...> --out <file>`: the blocks at those
  *     points, one BlockFetch request each, one hex line per block.
  *
  * Anything the codecs cannot read is written as it is, with the kind it was announced as, so a
  * capture is never lost to a decoder that is behind the chain.
  */
object LeiosCaptureMain extends IOApp:
  private val Margin = 12

  def run(args: List[String]): IO[ExitCode] =
    val flags = args.drop(1).grouped(2).collect { case List(k, v) => k.drop(2) -> v }.toMap
    (
      args.headOption,
      flags.get("peer").flatMap(p => com.comcast.ip4s.SocketAddress.fromString(p)),
      flags.get("magic").flatMap(_.toLongOption),
      flags.get("out").map(Path.of(_))
    ) match
      case (Some(command), Some(peer), Some(magic), Some(out)) =>
        Handle
          .allow[NetworkError] {
            NodeToNode
              .connect[IO, HfcHeader](peer, magic, HfcHeader.codec)
              .use { connection =>
                command match
                  case "headers" =>
                    headers(
                      connection,
                      flags.get("from").fold(Option(ChainSync.Point.Origin))(point),
                      flags.get("headers").filterNot(_ == "all").flatMap(_.toIntOption),
                      flags.get("batch").flatMap(_.toIntOption).getOrElse(64),
                      flags.get("pause-ms").flatMap(_.toLongOption).getOrElse(25L).millis,
                      out
                    )
                  case "blocks" =>
                    blocks(
                      connection,
                      flags.get("points").toList.flatMap(_.split(',').toList).traverse(point(_)),
                      out
                    )
                  case _ => IO.println(usage)
              }
              .as(ExitCode.Success)
          }
          .rescue(e => IO.println(e.message).as(ExitCode(1)))
      case _ => IO.println(usage).as(ExitCode(2))

  private val usage =
    "usage: headers|blocks --peer <host>:<port> --magic M --out <file> " +
      "[--from origin|<slot>.<hash>] [--headers N|all] [--batch B] [--pause-ms P] [--points a.b,c.d]"

  private def point(text: String): Option[ChainSync.Point] =
    if text == "origin" then Some(ChainSync.Point.Origin)
    else
      text.split('.') match
        case Array(slot, hash) =>
          for
            s <- slot.toLongOption.flatMap(ChainSync.UInt64.from(_).toOption)
            h <- Bytes.fromHex(hash).toOption
          yield ChainSync.Point.Block(s, h)
        case _ => None

  /** What the envelope announces: its kind and, where the header decodes, block number, slot, hash.
    */
  private def describe(header: HfcHeader): (String, Option[(BigInt, BigInt, Bytes)], Bytes) =
    header match
      case HfcHeader.Dijkstra(raw) =>
        ("7", LeiosHeader.decode(raw).toOption.map(h => (h.blockNo, h.slot, h.hash)), raw)
      case HfcHeader.Supported(h) =>
        // the Leios testnet's Conway-index headers have the 12-element shape too
        val info = Header
          .fromNtN(h, BigInt(21600))
          .toOption
          .map(x => (x.blockNo, x.slot, x.hash))
          .orElse(LeiosHeader.decode(h.raw).toOption.map(x => (x.blockNo, x.slot, x.hash)))
        (h.era.index.toString, info, h.raw)
      case HfcHeader.Unrecognised(index, payload) => (index.toString, None, payload)

  private def headers(
      connection: NodeToNodeConnection[IO, HfcHeader],
      from: Option[ChainSync.Point],
      limit: Option[Int],
      batch: Int,
      pause: FiniteDuration,
      out: Path
  )(using Handle[IO, NetworkError]): IO[Unit] =
    val writer = IO.blocking(
      new BufferedWriter(
        new OutputStreamWriter(
          new GZIPOutputStream(Files.newOutputStream(out)),
          StandardCharsets.UTF_8
        )
      )
    )
    from match
      case None => IO.println("bad --from")
      case Some(start) =>
        Resource.fromAutoCloseable(writer).use { w =>
          connection.chainSync.findIntersect(Vector(start)).flatMap {
            case None => IO.println("no intersection")
            case Some((at, tip)) =>
              IO.println(s"intersect $at, tip block ${tip.blockNo.value}") *>
                loop(connection.chainSync, w, tip.blockNo.value, BigInt(-1), 0, limit, batch, pause)
          }
        }

  private def loop(
      chainSync: ChainSyncClient[IO, HfcHeader],
      w: BufferedWriter,
      tipBlock: BigInt,
      last: BigInt,
      count: Int,
      limit: Option[Int],
      batch: Int,
      pause: FiniteDuration
  ): IO[Unit] =
    val ahead = (tipBlock - last).max(0)
    val n = (ahead - Margin).min(batch).toInt
    val room = limit.fold(n)(l => n.min(l - count))
    if room <= 0 then IO.println(s"stopped after $count headers, last block $last")
    else
      chainSync.requestNextBatch(room).flatMap { updates =>
        val lines = updates.collect { case ChainSyncClient.Update.RollForward(h, t) => (h, t) }
        val seen = lines.map { (h, _) =>
          val (kind, info, raw) = describe(h)
          val (no, slot, hash) = info.getOrElse((BigInt(-1), BigInt(-1), Bytes.empty))
          val envelope = HfcHeader.encode(h).toOption.getOrElse(raw)
          s"$kind\t$no\t$slot\t${hash.hex}\t${envelope.hex}\n" -> no
        }
        val tip = lines.lastOption.fold(tipBlock)(_._2.blockNo.value)
        val newLast = seen.lastOption.fold(last)(_._2)
        IO.blocking(seen.foreach((l, _) => w.write(l))) *>
          IO.sleep(pause) *>
          loop(chainSync, w, tip, newLast, count + lines.size, limit, batch, pause)
      }

  private def blocks(
      connection: NodeToNodeConnection[IO, HfcHeader],
      points: Option[List[ChainSync.Point]],
      out: Path
  )(using Handle[IO, NetworkError]): IO[Unit] =
    points match
      case None => IO.println("bad --points")
      case Some(ps) =>
        ps.traverse { p =>
          CardanoBlockFetch.SpecificPoint.from(p) match
            case Left(reason) => IO.println(reason).as(Option.empty[Bytes])
            case Right(s) =>
              connection.blockFetch.requestRange(s.point, s.point).flatMap {
                case Some(Vector(b)) =>
                  IO.println(s"$p: ${b.size} bytes") *> IO.sleep(500.millis).as(Some(b))
                case other => IO.println(s"$p: $other").as(None)
              }
        }.flatMap(bs =>
          IO.blocking(Files.writeString(out, bs.flatten.map(_.hex).mkString("", "\n", "\n"))).void
        )
end LeiosCaptureMain
