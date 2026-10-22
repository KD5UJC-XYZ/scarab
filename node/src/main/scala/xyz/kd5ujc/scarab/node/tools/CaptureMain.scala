// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{Deferred, ExitCode, IO, IOApp}
import cats.mtl.{Handle, Raise}
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.ledger.{ByronSpan, ChainError, ChainSyncInternalProblem, Header, NodeError}
import xyz.kd5ujc.scarab.node.{Peer, SyncEvent}

/** Development tool, not part of the node: `xyz.kd5ujc.scarab.node.tools.CaptureMain`.
  *
  * `capture --peer <host>:<port> --magic M --blocks N|all --out <file> [--from <slot>.<hashhex>]
  * [--byron-epoch-slots N]` (N = 10k of the Byron genesis, needed where the chain has Byron blocks)
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
      fromBlockNo <- from match
        case ChainSync.Point.Origin => Some(None)
        case _ =>
          flags
            .get("from-block-no")
            .flatMap(value => scala.util.Try(BigInt(value)).toOption)
            .flatMap(ChainSync.UInt64.from(_).toOption)
            .map(Some(_))
      epochSlots = flags
        .get("byron-epoch-slots")
        .flatMap(_.toLongOption)
        .fold(ByronSpan.Absent.epochSlots)(BigInt(_))
    yield (peer, magic, blocks, out, from, fromBlockNo, epochSlots)
    parsed match
      case None =>
        IO.println(
          "usage: capture --peer <host>:<port> --magic M --blocks N|all --out <file> [--from <slot>.<hash> --from-block-no N] [--byron-epoch-slots N]"
        ).as(ExitCode(2))
      case Some((peer, magic, n, out, from, fromBlockNo, epochSlots)) =>
        Handle
          .allow[NodeError] {
            Deferred[IO, ChainSyncInternalProblem]
              .flatMap { failures =>
                val heights = new Peer.HeightSnapshot[IO]:
                  def capture = IO.pure(
                    Peer.HeightSnapshot.Captured(
                      Vector(from),
                      Map[ChainSync.Point, Option[BigInt]](ChainSync.Point.Origin -> None)
                        .updated(from, fromBlockNo.map(_.value))
                    )
                  )
                  def unavailable[A](cause: ChainSyncInternalProblem): IO[A] =
                    failures.complete(cause) *> IO.never[A]
                Peer
                  .nodeToNode[IO](
                    Peer.Settings(peer, magic, byronEpochSlots = epochSlots),
                    _ => IO.unit,
                    heights
                  )
                  .use(peer =>
                    IO.race(capture(peer, n, from), failures.get).flatMap {
                      case Left(blocks) => IO.pure(blocks)
                      case Right(cause) =>
                        Raise[IO, NodeError].raise[ChainSyncInternalProblem, Vector[Bytes]](cause)
                    }
                  )
              }
              .flatMap(blocks =>
                IO.blocking(Files.writeString(out, blocks.map(_.hex).mkString("", "\n", "\n")))
              )
              .as(ExitCode.Success)
          }
          .rescue(e => IO.println(e.message).as(ExitCode(1)))

  private def parsePoint(text: String): Option[ChainSync.Point] = text.split('.') match
    case Array(slot, hash) =>
      for
        s <- slot.toLongOption.flatMap(ChainSync.UInt64.from(_).toOption)
        h <- Bytes.fromHex(hash).toOption
      yield ChainSync.Point.Block(s, h)
    case _ => None

  private def capture(peer: Peer[IO], n: Int, from: ChainSync.Point)(using
      Raise[IO, NodeError]
  ): IO[Vector[Bytes]] =
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
      case None    => Raise[IO, NodeError].raise(ChainError.NoIntersection)
    }
