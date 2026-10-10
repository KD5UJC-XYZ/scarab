// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.network.NumericPeer
import scala.concurrent.duration.*

/** `follow --genesis <shelley-genesis.json> --peer <ip>:<port> [--seconds N]` */
object Main extends IOApp:
  final case class Options(genesis: Path, peer: NumericPeer, seconds: Option[Long])

  def run(args: List[String]): IO[ExitCode] =
    parse(args) match
      case Left(usage) => IO.println(usage).as(ExitCode(2))
      case Right(options) =>
        Handle
          .allow[NodeError](follow(options).as(ExitCode.Success))
          .rescue(error => JsonLog.error(error).as(ExitCode(1)))

  private def follow(options: Options)(using Handle[IO, NodeError]): IO[Unit] =
    for
      text <- IO.blocking(Files.readString(options.genesis))
      genesis <- Genesis.shelley(text).fold(Handle[IO, NodeError].raise, IO.pure)
      ref <- Ref.of[IO, VolatileChain[FollowState]](
        VolatileChain.genesis(genesis.geometry.securityParam, FollowState.empty)
      )
      given cats.mtl.Stateful[IO, VolatileChain[FollowState]] = RefStateful(ref)
      events = JsonLog.events
      follower = Follower[IO, FollowState](StructuralRules[IO], events, genesis.geometry)
      settings = Peer.Settings(options.peer, genesis.networkMagic)
      retry = Follower.Retry(maxAttempts = 30, delay = 2.seconds)
      // Peer failures are handled by the supervisor; once retries are exhausted they become fatal.
      run = Handle
        .allow[PeerError](
          Follower.supervise(follower, Peer.nodeToNode[IO](settings), events, retry)
        )
        .rescue(error => Handle[IO, NodeError].raise(error))
      _ <- JsonLog.started(options.peer, genesis)
      _ <- options.seconds.fold(run)(s => run.timeoutTo(s.seconds, IO.unit))
      last <- ref.get
      _ <- JsonLog.finished(last)
    yield ()

  private def parse(args: List[String]): Either[String, Options] =
    val usage = "usage: follow --genesis <shelley-genesis.json> --peer <ip>:<port> [--seconds N]"
    def go(rest: List[String], acc: Map[String, String]): Either[String, Map[String, String]] =
      rest match
        case Nil => Right(acc)
        case flag :: value :: tail if flag.startsWith("--") =>
          go(tail, acc + (flag.drop(2) -> value))
        case _ => Left(usage)
    args match
      case "follow" :: rest =>
        for
          flags <- go(rest, Map.empty)
          genesis <- flags.get("genesis").toRight(usage).map(Path.of(_))
          peerText <- flags.get("peer").toRight(usage)
          split <- peerText.lastIndexOf(':') match
            case -1 => Left(usage)
            case i =>
              peerText.drop(i + 1).toIntOption.toRight(usage).map(p => peerText.take(i) -> p)
          peer <- NumericPeer.checked(split._1, split._2)
          seconds <- flags.get("seconds").traverse(_.toLongOption.toRight(usage))
        yield Options(genesis, peer, seconds)
      case _ => Left(usage)
