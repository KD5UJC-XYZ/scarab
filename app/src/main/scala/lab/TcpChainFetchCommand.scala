// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, Resource}
import cats.syntax.all.*
import java.nio.file.Path
import lab.fetcher.*
import lab.network.{NumericPeer, TcpLimits}
import scala.concurrent.duration.*

/** Explicit numeric endpoint only. Production code can connect; release tests use controlled
  * localhost.
  */
object TcpChainFetchCommand:
  val usage: String =
    "chain-fetch-tcp run --peer NUMERIC_IP --port PORT --network-magic UINT32 " +
      "--source-descriptor FILE --source-provenance FILE --after SLOT:HASH --first SLOT:HASH " +
      "--last SLOT:HASH --output DIR [--resume] [budget options]\n" +
      "Numeric IPv4/IPv6 only; no DNS, defaults for selectors, discovery, retries or fallback. " +
      "Strict NtN14 protocol 3, no KeepAlive/general mux; relay interoperability unproven. " +
      "Optional lower caps: --max-blocks 4 --max-block-bytes 1048576 --max-raw-bytes 4194304 " +
      "--max-wire-bytes 5242880 --acquisition-seconds 30 --job-seconds 45 --connect-seconds 5 " +
      "--read-seconds 10 --write-seconds 5 --handshake-seconds 5 --state-seconds 10. " +
      "Endpoints and provenance are user assertions; bodies, ledger, consensus and network are not authenticated."

  private val required = Set(
    "peer",
    "port",
    "network-magic",
    "source-descriptor",
    "source-provenance",
    "after",
    "first",
    "last",
    "output"
  )
  private val budgets = Set(
    "max-blocks",
    "max-block-bytes",
    "max-raw-bytes",
    "max-wire-bytes",
    "acquisition-seconds",
    "job-seconds",
    "connect-seconds",
    "read-seconds",
    "write-seconds",
    "handshake-seconds",
    "state-seconds"
  )
  private final case class Options(
      values: Map[String, String],
      resume: Boolean,
      limits: TcpFetchLimits,
      peer: NumericPeer,
      magic: Long,
      after: Point,
      first: Point,
      last: Point
  )

  private def parse(args: List[String]): Either[String, Options] =
    def loop(
        rest: List[String],
        values: Map[String, String],
        resume: Boolean
    ): Either[String, (Map[String, String], Boolean)] =
      rest match
        case Nil                           => Right(values -> resume)
        case "--resume" :: tail if !resume => loop(tail, values, true)
        case flag :: value :: tail if flag.startsWith("--") && !value.startsWith("--") =>
          val key = flag.drop(2)
          if !(required ++ budgets)(key) || values.contains(key) then
            Left("unknown or duplicate option")
          else loop(tail, values.updated(key, value), resume)
        case _ => Left("invalid or duplicate option")
    args match
      case "run" :: tail =>
        loop(tail, Map.empty, false).flatMap { (values, resume) =>
          def number(key: String, default: Option[Long] = None): Either[String, Long] =
            values.get(key) match
              case None => default.toRight(s"missing --$key")
              case Some(s) if s.matches("0|[1-9][0-9]{0,18}") =>
                s.toLongOption.toRight(s"invalid --$key")
              case _ => Left(s"invalid --$key")
          def integer(key: String, default: Int): Either[String, Int] =
            number(key, Some(default.toLong)).flatMap(n =>
              Either.cond(n <= Int.MaxValue, n.toInt, s"invalid --$key")
            )
          for
            _ <- Either.cond(required.subsetOf(values.keySet), (), "missing required selector")
            port <- number("port").flatMap(n =>
              Either.cond(n >= 1 && n <= 65535, n.toInt, "invalid port")
            )
            peer <- NumericPeer.checked(values("peer"), port)
            magic <- number("network-magic").flatMap(n =>
              Either.cond(n <= 4294967295L, n, "invalid UInt32 magic")
            )
            after <- Point.parse(values("after"))
            first <- Point.parse(values("first"))
            last <- Point.parse(values("last"))
            connect <- integer("connect-seconds", 5)
            read <- integer("read-seconds", 10)
            write <- integer("write-seconds", 5)
            tcp <- TcpLimits.checked(
              connect = connect.seconds,
              read = read.seconds,
              write = write.seconds
            )
            blocks <- integer("max-blocks", 4)
            blockBytes <- integer("max-block-bytes", 1048576)
            raw <- number("max-raw-bytes", Some(4194304L))
            wire <- number("max-wire-bytes", Some(5242880L))
            acquisition <- integer("acquisition-seconds", 30)
            job <- integer("job-seconds", 45)
            handshake <- integer("handshake-seconds", 5)
            state <- integer("state-seconds", 10)
            limits <- TcpFetchLimits.checked(
              tcp,
              blocks,
              blockBytes,
              raw,
              wire,
              acquisition,
              job,
              handshake,
              state
            )
          yield Options(values, resume, limits, peer, magic, after, first, last)
        }
      case _ => Left("expected run")

  def run(args: List[String]): IO[ExitCode] = runWith(args, IO.println, IO.consoleForIO.errorln)

  def runWith(args: List[String], out: String => IO[Unit], err: String => IO[Unit]): IO[ExitCode] =
    runUsing(args, out, err, d => TcpDirectRangeSource.resource[IO](d))

  /** Test seam still goes through all preflight and store-binding checks before resource
    * acquisition.
    */
  private[lab] def runUsing(
      args: List[String],
      out: String => IO[Unit],
      err: String => IO[Unit],
      source: TcpDirectRangeDescriptor => Resource[IO, DirectRangeSource[IO]]
  ): IO[ExitCode] =
    if args == List("--help") then out(usage).as(ExitCode.Success)
    else
      IO.defer {
        parse(args) match
          case Left(message) => err(s"chain-fetch-tcp error: $message\n$usage").as(ExitCode(2))
          case Right(options) =>
            val work = IO
              .blocking {
                val d = TcpDirectRangeDescriptor.load(
                  Path.of(options.values("source-descriptor")),
                  Path.of(options.values("source-provenance")),
                  options.limits
                )
                if d.peer.identity != options.peer.identity || d.data.magic != options.magic ||
                  d.batch.anchor != options.after ||
                  s"${d.batch.first.slot.value}:${d.batch.first.hash.hex}" != options.first.encoded ||
                  s"${d.batch.last.slot.value}:${d.batch.last.hash.hex}" != options.last.encoded
                then FetchError.config("CLI selectors disagree with original descriptor")
                d
              }
              .handleErrorWith {
                case e: FetchError if e.code == 2 => IO.raiseError(e)
                case e => IO.raiseError(new FetchError(2, s"preflight: ${e.getMessage}"))
              }
              .flatMap { descriptor =>
                source(descriptor)
                  .use { direct =>
                    direct.runOwned(
                      NioSegmentStore.resource[IO](
                        Path.of(options.values("output")),
                        descriptor.spec,
                        descriptor.identity,
                        options.resume
                      )
                    )
                  }
                  .flatMap { result =>
                    out(report(descriptor, result)).as(
                      if result.complete then ExitCode.Success else ExitCode(3)
                    )
                  }
              }
            work.timeoutTo(
              options.limits.fetch.maxDuration,
              err(
                "chain-fetch-tcp error: timeBudget including preflight/store/acquisition; inspect output for committed prefix"
              ).as(ExitCode(3))
            )
      }.handleErrorWith { e =>
        val code = e match
          case f: FetchError                                    => f.code
          case _: java.nio.file.AtomicMoveNotSupportedException => 6
          case _                                                => 4
        err(s"chain-fetch-tcp error: ${e.getMessage}").as(ExitCode(code))
      }

  private def report(d: TcpDirectRangeDescriptor, result: FetchResult): String =
    val loopback = d.peer.addressBytes match
      case bytes if bytes.size == 4 => (bytes.head & 255) == 127
      case bytes => bytes.dropRight(1).forall(_ == 0) && bytes.lastOption.contains(1.toByte)
    // Incomplete results may precede connect. Do not infer execution from configured address.
    val externalExecuted = if result.complete then (!loopback).toString else "null"
    s"""{"format":"tcp-direct-range-report-v1","tcpAdapterImplemented":true,"externalEndpointCapable":true,"externalEndpointExecutedHere":$externalExecuted,"selectedEndpointIsLoopback":$loopback,"relayInteropEstablished":false,"keepAliveImplemented":false,"generalMuxDispatcher":false,"profile":"${TcpDirectRangeDescriptor.Profile}","endpoint":"${d.peer.identity}","networkMagic":${d.data.magic},"negotiatedProfileConfirmed":${result.complete},"initiatorOnly":true,"peerSharing":false,"query":false,"structuralEndpointParentChecks":${result.complete},"bodyCommitmentsValidated":false,"independentBytePins":${d.bytePins.size},"independentBytePinsMatched":${result.complete && d.bytePins.nonEmpty},"result":${result.json}}"""
