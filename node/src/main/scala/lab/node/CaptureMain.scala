// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{ExitCode, IO, IOApp}
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.network.ChainSync

/** `capture --peer <host>:<port> --magic M --blocks N --out <file>`
  *
  * Records the first N blocks of a chain from genesis, one hex-encoded raw block per line, for
  * offline replay tests. Capturing continues until those blocks are k deep, so none is rolled back.
  */
object CaptureMain extends IOApp:
  private val Depth = 10

  def run(args: List[String]): IO[ExitCode] =
    val flags = args.drop(1).grouped(2).collect { case List(k, v) => k.drop(2) -> v }.toMap
    val parsed = for
      peerText <- flags.get("peer")
      peer <- Peer.parseAddress(peerText).toOption
      magic <- flags.get("magic").flatMap(_.toLongOption)
      blocks <- flags.get("blocks").flatMap(_.toIntOption)
      out <- flags.get("out").map(Path.of(_))
    yield (peer, magic, blocks, out)
    parsed match
      case None =>
        IO.println("usage: capture --peer <host>:<port> --magic M --blocks N --out <file>")
          .as(ExitCode(2))
      case Some((peer, magic, n, out)) =>
        Handle
          .allow[PeerError](Peer.nodeToNode[IO](Peer.Settings(peer, magic)).use(capture(_, n)))
          .rescue(e => IO.raiseError(new RuntimeException(e.message)))
          .flatMap(blocks =>
            IO.blocking(Files.writeString(out, blocks.map(_.hex).mkString("", "\n", "\n")))
          )
          .as(ExitCode.Success)

  private def capture(peer: Peer[IO], n: Int): IO[Vector[Bytes]] =
    def loop(chain: Vector[(Header, Bytes)]): IO[Vector[Bytes]] =
      if chain.size >= n + Depth then IO.pure(chain.take(n).map(_._2))
      else
        peer.next.flatMap {
          case SyncEvent.RollForward(h, _) => peer.fetch(h).flatMap(b => loop(chain :+ (h -> b)))
          case SyncEvent.RollBackward(ChainSync.Point.Origin, _) => loop(Vector.empty)
          case SyncEvent.RollBackward(p, _) =>
            loop(chain.take(chain.indexWhere(_._1.point == p) + 1))
        }
    peer.intersect(Vector(ChainSync.Point.Origin)) *> loop(Vector.empty)
