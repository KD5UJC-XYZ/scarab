// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{ExitCode, IO, IOApp}
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.network.ChainSync

/** `capture --peer <host>:<port> --magic M --blocks N|all --out <file> [--from <slot>.<hashhex>]`
  *
  * Records N blocks of a chain, one hex-encoded raw block per line, for offline replay tests. It
  * starts at genesis, or with `--from` at the first block after that point. Capturing continues
  * until those blocks are 10 deep, so none is rolled back. With `--blocks all` it records the whole
  * chain up to 10 blocks behind the peer's tip (a range to select fixtures from).
  */
object CaptureMain extends IOApp:
  private val Depth = 10
  private val All = -1

  def run(args: List[String]): IO[ExitCode] =
    val flags = args.drop(1).grouped(2).collect { case List(k, v) => k.drop(2) -> v }.toMap
    val parsed = for
      peerText <- flags.get("peer")
      peer <- Peer.parseAddress(peerText).toOption
      magic <- flags.get("magic").flatMap(_.toLongOption)
      blocks <- flags.get("blocks").flatMap(b => if b == "all" then Some(All) else b.toIntOption)
      out <- flags.get("out").map(Path.of(_))
      from <- flags.get("from").fold(Option(ChainSync.Point.Origin))(parsePoint)
    yield (peer, magic, blocks, out, from)
    parsed match
      case None =>
        IO.println(
          "usage: capture --peer <host>:<port> --magic M --blocks N|all --out <file> [--from <slot>.<hash>]"
        ).as(ExitCode(2))
      case Some((peer, magic, n, out, from)) =>
        Handle
          .allow[PeerError](
            Peer.nodeToNode[IO](Peer.Settings(peer, magic)).use(capture(_, n, from))
          )
          .rescue(e => IO.raiseError(new RuntimeException(e.message)))
          .flatMap(blocks =>
            IO.blocking(Files.writeString(out, blocks.map(_.hex).mkString("", "\n", "\n")))
          )
          .as(ExitCode.Success)

  private def parsePoint(text: String): Option[ChainSync.Point] = text.split('.') match
    case Array(slot, hash) =>
      for
        s <- slot.toLongOption.flatMap(ChainSync.UInt64.from(_).toOption)
        h <- Bytes.fromHex(hash).toOption
      yield ChainSync.Point.Block(s, h)
    case _ => None

  private def capture(peer: Peer[IO], n: Int, from: ChainSync.Point): IO[Vector[Bytes]] =
    def loop(chain: Vector[(Header, Bytes)]): IO[Vector[Bytes]] =
      if n != All && chain.size >= n + Depth then IO.pure(chain.take(n).map(_._2))
      else
        peer.next.flatMap {
          case SyncEvent.RollForward(h, tip) =>
            peer.fetch(h).flatMap { b =>
              val grown = chain :+ (h -> b)
              if n == All && h.blockNo >= tip.blockNo.value then
                IO.pure(grown.dropRight(Depth).map(_._2))
              else loop(grown)
            }
          case SyncEvent.RollBackward(ChainSync.Point.Origin, _) => loop(Vector.empty)
          case SyncEvent.RollBackward(p, _) =>
            loop(chain.take(chain.indexWhere(_._1.point == p) + 1))
        }
    peer.intersect(Vector(from)).flatMap {
      case Some(_) => loop(Vector.empty)
      case None    => IO.raiseError(new RuntimeException("peer does not have the --from point"))
    }
