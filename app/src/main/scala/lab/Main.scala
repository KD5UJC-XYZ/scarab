// SPDX-License-Identifier: Apache-2.0
package lab

import cats.MonadThrow
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes

final case class Fixture(name: String, kind: String, bytes: Bytes, expected: Bytes)
final case class Check(name: String, kind: String, actual: String, matched: Boolean)

object Fixture:
  def parse(text: String): Either[String, Vector[Fixture]] =
    text.linesIterator.zipWithIndex
      .filterNot { case (line, _) =>
        line.trim.isEmpty || line.startsWith("#")
      }
      .toVector
      .traverse { case (line, index) =>
        line.split("\t", -1).toList match
          case name :: kind :: hex :: expected :: Nil =>
            for
              bytes <- Bytes.fromHex(hex)
              hash <- Bytes.fromHex(expected)
              _ <- Either.cond(hash.size == 32, (), s"line ${index + 1}: expected 32-byte txid")
            yield Fixture(name, kind, bytes, hash)
          case _ => Left(s"line ${index + 1}: expected four tab-separated fields")
      }
      .flatMap(xs => Either.cond(xs.nonEmpty, xs, "empty fixture corpus"))

  def check(fixture: Fixture): Either[String, Check] =
    val result = fixture.kind match
      case "transaction-body"     => TransactionId.fromBody(fixture.bytes)
      case "transaction-envelope" => TransactionId.fromEnvelope(fixture.bytes)
      case other                  => Left(s"unsupported fixture kind: $other")
    result.map(hash => Check(fixture.name, fixture.kind, hash.hex, hash == fixture.expected))

trait FixtureSource[F[_]]:
  def load: F[Vector[Fixture]]
trait ReportSink[F[_]]:
  def write(checks: Vector[Check]): F[Unit]

object Verify:
  def run[F[_]: MonadThrow](source: FixtureSource[F], sink: ReportSink[F]): F[Boolean] =
    for
      fixtures <- source.load
      checks <- fixtures.traverse(f =>
        Fixture.check(f).leftMap(new IllegalArgumentException(_)).liftTo[F]
      )
      _ <- sink.write(checks)
    yield checks.forall(_.matched)

object Main extends IOApp:
  private val usage =
    "usage: app/run reference-handshake PORT PRIVATE_NETWORK_MAGIC | [fixture-index.tsv] | --help | vm [fixture-directory] | network-demo | network-selftest | ledger-demo | witness-demo | coverage-demo | vrf-demo | fee-size-demo | praos-demo | opcert-demo | sum6-demo | chain-sync-selftest | chain-sync-session-selftest | chain-fetch | chain-fetch-tcp | block-fetch-selftest | body-commitment | block-evidence | restricted-replay | restricted-replay-store"
  def run(args: List[String]): IO[ExitCode] = args match
    case "native-spending" :: rest                 => NativeSpendingCommand.run(rest)
    case "minimum-output" :: rest                  => MinimumOutputCommand.run(rest)
    case "coherent-branch" :: rest                 => CoherentBranchCommand.run(rest)
    case "cluster-transfer" :: rest                => ClusterTransferCommand.run(rest)
    case "reference-capture-check" :: rest         => ReferenceCaptureCommand.verify(rest)
    case "reference-capture" :: rest               => ReferenceCaptureCommand.run(rest)
    case "reference-handshake" :: rest             => ReferenceHandshakeCommand.run(rest)
    case "restricted-replay-store" :: rest         => DurableReplayCommand.run(rest)
    case "restricted-replay" :: rest               => RestrictedReplayCommand.run(rest)
    case "block-evidence" :: rest                  => BlockEvidenceCommand.run(rest)
    case "body-commitment" :: rest                 => BodyCommitmentCommand.run(rest)
    case List("block-fetch-selftest")              => BlockFetchCommand.run
    case "chain-fetch-tcp-keepalive" :: rest       => KeepAliveTcpChainFetchCommand.run(rest)
    case "chain-fetch-tcp" :: rest                 => TcpChainFetchCommand.run(rest)
    case "chain-fetch" :: rest                     => ChainFetchCommand.run(rest)
    case List("chain-sync-session-selftest")       => ChainSyncSessionCommand.run
    case List("chain-sync-selftest")               => ChainSyncCommand.run
    case List("sum6-demo")                         => Sum6Command.run
    case List("opcert-demo")                       => OpcertCommand.run
    case List("praos-demo")                        => PraosCommand.run
    case List("fee-size-demo")                     => FeeSizeCommand.run
    case List("vrf-demo")                          => VrfCommand.run
    case List("coverage-demo")                     => CoverageCommand.run
    case List("witness-demo")                      => WitnessCommand.run
    case List("ledger-demo")                       => LedgerCommand.run
    case List("network-demo" | "network-selftest") => NetworkCommand.run
    case List("--help")                            => IO.println(usage).as(ExitCode.Success)
    case List("vm")                                => VmCommand.run(Path.of("fixtures/plutus"))
    case List("vm", directory)                     => VmCommand.run(Path.of(directory))
    case other if other.size <= 1 && !other.exists(_.startsWith("-")) => runCodec(other)
    case _ => IO.println(usage).as(ExitCode(2))

  private def runCodec(args: List[String]): IO[ExitCode] =
    if args.size > 1 then IO.println(usage).as(ExitCode(2))
    else
      val path = Path.of(args.headOption.getOrElse("fixtures/cardano-golden.tsv"))
      val source = new FixtureSource[IO]:
        def load: IO[Vector[Fixture]] = IO
          .blocking {
            val max = 8L * 1024 * 1024
            if Files.size(path) > max then
              throw new IllegalArgumentException("fixture index exceeds 8 MiB")
            Files.readString(path)
          }
          .flatMap(text =>
            IO.fromEither(Fixture.parse(text).leftMap(new IllegalArgumentException(_)))
          )
      val sink = new ReportSink[IO]:
        def write(checks: Vector[Check]): IO[Unit] =
          checks.traverse_(c =>
            IO.println(
              s"${if c.matched then "PASS" else "FAIL"}\t${c.name}\t${c.kind}\t${c.actual}"
            )
          ) *>
            IO.println(
              s"${checks.count(_.matched)}/${checks.size} codec+hash checks matched precomputed upstream expectations. No live reference oracle or ledger validation was run."
            )
      Verify
        .run(source, sink)
        .map(ok => if ok then ExitCode.Success else ExitCode.Error)
        .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
