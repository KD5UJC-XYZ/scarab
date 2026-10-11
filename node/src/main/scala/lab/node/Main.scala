// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.mtl.{Handle, Stateful}
import cats.syntax.all.*
import io.circe.parser
import java.nio.file.{Files, Path}
import com.comcast.ip4s.{Host, SocketAddress}
import lab.Blake2b
import lab.cbor.Bytes
import lab.node.consensus.{Praos, PraosConfig}
import lab.node.ledger.{GenesisState, LedgerGlobals, Rational}
import lab.vrf.PraosLeaderThreshold
import scala.concurrent.duration.*

/** `follow --genesis-dir <dir> --peer <host>:<port> [--seconds N] [--query-port P]`
  *
  * Follows the peer from genesis, applying the full ledger rules, and serves the retained ledger
  * states on loopback for comparison against a reference node.
  */
object Main extends IOApp:
  final case class Options(
      genesisDir: Path,
      peer: SocketAddress[Host],
      seconds: Option[Long],
      queryPort: Int
  )

  def run(args: List[String]): IO[ExitCode] =
    parse(args) match
      case Left(usage) => IO.println(usage).as(ExitCode(2))
      case Right(options) =>
        Handle
          .allow[NodeError](follow(options).as(ExitCode.Success))
          .rescue(error => JsonLog.error(error).as(ExitCode(1)))

  private def follow(options: Options)(using Handle[IO, NodeError]): IO[Unit] =
    def fail[A](e: NodeError): IO[A] = Handle[IO, NodeError].raise(e)
    def read(name: String) = IO.blocking(Files.readString(options.genesisDir.resolve(name)))
    def json(name: String) =
      read(name).flatMap(t =>
        parser.parse(t).fold(e => fail(ConfigError(s"$name: ${e.message}")), IO.pure)
      )
    for
      genesis <- read("shelley-genesis.json").flatMap(t => Genesis.shelley(t).fold(fail, IO.pure))
      files <- (
        json("shelley-genesis.json"),
        json("alonzo-genesis.json"),
        json("conway-genesis.json"),
        json("byron-genesis.json")
      ).mapN(GenesisState.Files.apply)
      globals = LedgerGlobals(
        genesis.maxLovelaceSupply,
        genesis.geometry.epochLength,
        Rational.fromDecimal(genesis.geometry.activeSlotsCoeff),
        network = if genesis.networkMagic == 764824073L then 1 else 0
      )
      initial <- GenesisState.initial(files, globals).fold(fail, IO.pure)
      genesisBytes <- IO.blocking(
        Files.readAllBytes(options.genesisDir.resolve("shelley-genesis.json"))
      )
      f = genesis.geometry.activeSlotsCoeff
      active <- PraosLeaderThreshold.Fraction
        .checked(BigInt(f.bigDecimal.unscaledValue), BigInt(10).pow(f.bigDecimal.scale.max(0)))
        .fold(e => fail(ConfigError(e)), IO.pure)
      praosConfig = PraosConfig(
        genesis.geometry,
        genesis.slotsPerKesPeriod,
        genesis.maxKesEvolutions,
        active
      )
      start = NodeState(Praos.initial(Blake2b.hash256.hash(Bytes.fromArray(genesisBytes))), initial)
      ref <- Ref.of[IO, VolatileChain[NodeState]](
        VolatileChain.genesis(genesis.geometry.securityParam, start)
      )
      given Stateful[IO, VolatileChain[NodeState]] = RefStateful(ref)
      events = JsonLog.events
      rules = NodeRules[IO](praosConfig, globals)
      follower = Follower[IO, NodeState](rules, events, genesis.geometry)
      settings = Peer.Settings(options.peer, genesis.networkMagic)
      retry = Follower.Retry(maxAttempts = 30, delay = 2.seconds)
      // Peer failures are handled by the supervisor; once retries are exhausted they become fatal.
      sync = Handle
        .allow[PeerError](
          Follower.supervise(follower, Peer.nodeToNode[IO](settings), events, retry)
        )
        .rescue(error => fail[Unit](error))
      run = QueryServer.resource[IO](ChainView.fromRef(ref), options.queryPort).surround(sync)
      _ <- JsonLog.started(options.peer, genesis)
      _ <- options.seconds.fold(run)(s => run.timeoutTo(s.seconds, IO.unit))
      last <- ref.get
      _ <- JsonLog.finished(last)
    yield ()

  private def parse(args: List[String]): Either[String, Options] =
    val usage =
      "usage: follow --genesis-dir <dir> --peer <host>:<port> [--seconds N] [--query-port P]"
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
          dir <- flags.get("genesis-dir").toRight(usage).map(Path.of(_))
          peerText <- flags.get("peer").toRight(usage)
          peer <- Peer.parseAddress(peerText)
          seconds <- flags.get("seconds").traverse(_.toLongOption.toRight(usage))
          port <- flags.get("query-port").fold(Right(7071))(_.toIntOption.toRight(usage))
        yield Options(dir, peer, seconds, port)
      case _ => Left(usage)
