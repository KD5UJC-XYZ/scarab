// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import cats.effect.{ExitCode, IO, IOApp}
import java.io.{BufferedReader, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{LeiosHeaderChain, PraosConfig}
import xyz.kd5ujc.scarab.ledger.LeiosHeader
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.protocol.HfcHeader

/** Development tool, not part of the node: `xyz.kd5ujc.scarab.node.tools.LeiosCheckMain`.
  *
  * `check --headers <file.gz> --config <config.json> [--stop-block N]`: reads the header capture of
  * [[LeiosCaptureMain]] (no network) and runs [[LeiosHeaderChain]] over it from the genesis of the
  * node configuration (the genesis files are read from its directory, as `follow` reads them):
  * chain links, operational certificates, KES signatures, VRF proofs under the evolved epoch
  * nonces, and the leader threshold in the epochs whose stake the genesis gives. Reports the first
  * header that fails or the totals, by era index of the envelope, with how leadership was
  * established.
  */
object LeiosCheckMain extends IOApp:
  private enum DecodeProblem:
    case Hex(cause: Bytes.HexError)
    case Envelope(cause: xyz.kd5ujc.scarab.protocol.HfcHeaderError)
    case UnrecognisedEra(index: BigInt)
    case Header(cause: xyz.kd5ujc.scarab.ledger.ChainError)
    def message: String = this match
      case Hex(cause) => s"bad hex: ${cause.message}"
      case Envelope(cause) => s"envelope: $cause"
      case UnrecognisedEra(index) => s"unrecognised era index $index"
      case Header(cause) => cause.message
  def run(args: List[String]): IO[ExitCode] =
    val flags = args.drop(1).grouped(2).collect { case List(k, v) => k.drop(2) -> v }.toMap
    (flags.get("headers").map(Path.of(_)), flags.get("config").map(Path.of(_))) match
      case (Some(headers), Some(config)) =>
        IO.blocking(check(headers, config, flags.get("stop-block").flatMap(_.toIntOption)))
          .flatMap(report => IO.println(report))
          .as(ExitCode.Success)
      case _ =>
        IO.println("usage: check --headers <file.gz> --config <config.json> [--stop-block N]")
          .as(ExitCode(2))

  private def check(headers: Path, config: Path, stop: Option[Int]): String =
    NodeSetup.fromConfigFile(config) match
      case Left(error) => s"cannot set up: ${error.message}"
      case Right(setup) =>
        val reader = new BufferedReader(
          new InputStreamReader(
            new GZIPInputStream(Files.newInputStream(headers)),
            StandardCharsets.UTF_8
          )
        )
        // the pool distribution of the state the chain starts from (`nesPd`)
        val pools = setup.mirrorStart
          .ledger(None)
          .toOption
          .flatten
          .fold(xyz.kd5ujc.scarab.ledger.PoolDistribution(Map.empty, Map.empty)) {
            case InEra.Of(_, l) =>
              l.leaderPools(nextEpoch = false)
          }
        try
          loop(
            reader,
            setup.praos,
            LeiosHeaderChain.StakePolicy(pools, LeiosHeaderChain.GenesisEpochs),
            LeiosHeaderChain.initial(setup.mirrorStart.protocol),
            Map.empty,
            stop
          )
        finally reader.close()

  @tailrec
  private def loop(
      reader: BufferedReader,
      config: PraosConfig,
      policy: LeiosHeaderChain.StakePolicy,
      state: LeiosHeaderChain.State,
      kinds: Map[String, Long],
      stop: Option[Int]
  ): String =
    val line = reader.readLine()
    val done = stop.exists(n => state.length >= n)
    if line == null || done then summary(state, kinds, "complete")
    else
      val cols = line.split('\t')
      val decoded = for
        envelope <- Bytes.fromHex(cols(4)).left.map(DecodeProblem.Hex(_))
        hfc <- HfcHeader.decode(envelope).left.map(DecodeProblem.Envelope(_))
        raw <- hfc match
          case HfcHeader.Supported(h)       => Right(h.raw)
          case HfcHeader.Dijkstra(r)        => Right(r)
          case HfcHeader.Unrecognised(i, _) => Left(DecodeProblem.UnrecognisedEra(i))
        header <- LeiosHeader.decode(raw).left.map(DecodeProblem.Header(_))
      yield header
      decoded match
        case Left(reason) => summary(state, kinds, s"block ${cols(1)}: cannot decode: ${reason.message}")
        case Right(header) =>
          LeiosHeaderChain.step(config, policy)(state, header) match
            case Left(fault) =>
              summary(state, kinds, s"block ${header.blockNo} slot ${header.slot}: $fault")
            case Right(next) =>
              loop(
                reader,
                config,
                policy,
                next,
                kinds.updatedWith(cols(0))(n => Some(n.getOrElse(0L) + 1)),
                stop
              )

  private def summary(
      state: LeiosHeaderChain.State,
      kinds: Map[String, Long],
      outcome: String
  ): String =
    s"""outcome: $outcome
       |headers accepted: ${state.length} by envelope era index ${kinds.toList.sorted.mkString(
        ", "
      )}
       |leader threshold evaluated: ${state.leadership.threshold}, unchecked: ${state.leadership.unchecked}
       |tip: ${state.tip
        .map(t => s"block ${t.blockNo} slot ${t.slot} ${t.hash.hex}")
        .getOrElse("none")}
       |pools seen: ${state.registry.size}""".stripMargin
end LeiosCheckMain
