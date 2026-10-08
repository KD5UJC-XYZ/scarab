// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, Resource}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes
import lab.cbor.Bytes
import lab.ledger.RestrictedReplay as R

/** Bounded file-to-stdout replay. Rejected events leave the immutable current state untouched. No
  * state files, network, keys, tick execution or persistent adoption are involved.
  */
object RestrictedReplayCommand:
  val MaxTraceBytes = 20 * 1048576
  private val usage = "restricted-replay <trace.tsv> | restricted-replay --help"

  final class Trace private[RestrictedReplayCommand] (
      val initial: R.State,
      val transactions: Vector[Bytes]
  )
  final case class Observation(
      index: Int,
      beforeId: Bytes,
      afterId: Bytes,
      revision: BigInt,
      outcome: Either[R.Failure, Bytes]
  )
  final case class Report(initial: R.State, state: R.State, observations: Vector[Observation])

  private def hex(text: String, maximum: Int): Either[String, Bytes] =
    if text.length > maximum * 2 || text.isEmpty || text.length % 2 != 0 ||
      !text.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))
    then Left("expected bounded nonempty lowercase hex")
    else Bytes.fromHex(text)

  /** Runtime inputs contain a checkpoint plus original transactions only. No acceptance flag,
    * expected result, per-transaction resolution or default parameter/context is admitted.
    */
  def parse(raw: Bytes): Either[String, Trace] =
    if raw == null || raw.value == null || raw.size == 0 || raw.size > MaxTraceBytes then
      Left("trace byte bound")
    else if raw.value.exists(b => b < 0 || (b < 32 && b != 9 && b != 10) || b == 127) then
      Left("trace must be ASCII TSV")
    else
      val text = new String(raw.toArray, US_ASCII)
      val lines = text.linesIterator.take(R.MaxBatchTransactions + 2).toVector
      if !text.endsWith("\n") || lines.isEmpty || lines.size > R.MaxBatchTransactions + 1 then
        Left("expected LF-terminated checkpoint and at most 64 transaction rows")
      else
        for
          initial <- lines.head.split("\t", -1).toVector match
            case Vector("restricted-replay-v1", profile, slot, parameters, utxo, attribution)
                if profile == R.ProfileId && slot == R.FixedResearchSlot.toString =>
              for
                p <- hex(parameters, R.MaxStateBytes)
                u <- hex(utxo, R.MaxStateBytes)
                a <- hex(attribution, 32)
                _ <- Either.cond(a.size == 32, (), "expected 32-byte attribution digest")
                env <- R.environment(p).left.map(_.toString)
                initial <- R.initialize(env, u, a).left.map(_.toString)
              yield initial
            case _ =>
              Left(
                "expected restricted-replay-v1 checkpoint, profile, slot, parameter hex, UTxO hex and attribution SHA256"
              )
          rowHex <- lines.tail.traverse { line =>
            line.split("\t", -1).toVector match
              case Vector("tx", value) => Right(value)
              case _ =>
                Left(
                  "expected tx and original transaction hex; tick/epoch/other event kinds unsupported"
                )
          }
          _ <- Either.cond(
            rowHex.iterator.map(_.length.toLong).sum <= 2 * R.MaxBatchBytes,
            (),
            "aggregate transaction byte bound"
          )
          transactions <- rowHex.traverse(value => hex(value, R.MaxTransactionBytes))
        yield new Trace(initial, transactions)

  /** This is sequential corpus observation. Each accepted transaction is its own local commit; an
    * event rejection is observed and skipped without applying any part of that event.
    */
  def execute(trace: Trace): Report =
    val (state, observations) =
      trace.transactions.zipWithIndex.foldLeft((trace.initial, Vector.empty[Observation])) {
        case ((before, rows), (raw, index)) =>
          R.applyTransaction(before, raw) match
            case Left(failure) =>
              (
                before,
                rows :+ Observation(
                  index + 1,
                  before.stateId,
                  before.stateId,
                  before.revision.number,
                  Left(failure)
                )
              )
            case Right(applied) =>
              val transition = applied.delta.get.transitionId
              (
                applied.state,
                rows :+ Observation(
                  index + 1,
                  before.stateId,
                  applied.state.stateId,
                  applied.state.revision.number,
                  Right(transition)
                )
              )
      }
    Report(trace.initial, state, observations)

  private def label(result: Either[R.Failure, Bytes]): String = result match
    case Right(_)                           => "ProjectionApplied"
    case Left(R.Failure.Rejected(stage, _)) => s"Rejected:$stage"
    case Left(R.Failure.Unsupported(_))     => "Unsupported"
    case Left(R.Failure.Malformed(_))       => "Malformed"
    case Left(R.Failure.ResourceLimit(_))   => "ResourceLimit"
    case Left(R.Failure.StaleState(_))      => "StaleState"
    case Left(R.Failure.InternalFailure(_)) => "InternalFailure"

  def render(report: Report): Vector[String] =
    Vector(
      s"restricted-replay-v1\tprofile=${R.ProfileId}\tprofileHash=${R.ProfileHash.hex}\tcheckpoint=${report.initial.checkpoint.id.hex}\tresearchSlot=${R.FixedResearchSlot}\ttick=RecordedButNotExecuted"
    ) ++
      report.observations.map { row =>
        s"event=${row.index}\toutcome=${label(row.outcome)}\tbefore=${row.beforeId.hex}\tafter=${row.afterId.hex}\trevision=${row.revision}"
      } ++ Vector(
        s"final\tstate=${report.state.stateId.hex}\tutxoEntries=${report.state.utxo.size}\tfeesSinceCheckpoint=${report.state.feesSinceCheckpoint}\trevision=${report.state.revision.number}\tutxoCbor=${report.state.outputMap.hex}",
        "scope=UTxO/fee-projection-only\tsourceAuthenticated=false\tfullLedger=false\ttickExecuted=false\tinstantStake=false\tblockValidation=false\tconsensus=false\tpersistence=false"
      )

  def exitCode(report: Report): ExitCode =
    val failures = report.observations.flatMap(_.outcome.left.toOption)
    if failures.exists(_.isInstanceOf[R.Failure.InternalFailure]) then ExitCode(4)
    else if failures.exists(_.isInstanceOf[R.Failure.Unsupported]) then ExitCode(3)
    else if failures.exists(f =>
        f.isInstanceOf[R.Failure.Malformed] || f.isInstanceOf[R.Failure.ResourceLimit] ||
          f.isInstanceOf[R.Failure.StaleState]
      )
    then ExitCode(2)
    else if failures.nonEmpty then ExitCode.Error
    else ExitCode.Success

  private[lab] def read(path: Path): IO[Bytes] =
    IO.blocking {
      val attrs =
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      require(attrs.isRegularFile, "trace must be a regular file")
      require(attrs.size <= MaxTraceBytes, "trace byte cap exceeded")
    } *> Resource
      .fromAutoCloseable(IO.blocking {
        Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
      })
      .use { channel =>
        IO.blocking {
          val buffer = ByteBuffer.allocate(MaxTraceBytes + 1)
          var eof = false
          while buffer.hasRemaining && !eof do eof = channel.read(buffer) < 0
          require(buffer.position <= MaxTraceBytes, "trace byte cap exceeded")
          buffer.flip()
          val raw = new Array[Byte](buffer.remaining)
          buffer.get(raw)
          Bytes.fromArray(raw)
        }
      }

  def run(args: List[String]): IO[ExitCode] =
    val action = args match
      case List("--help") => IO.println(usage).as(ExitCode.Success)
      case List(file) if !file.startsWith("-") =>
        for
          raw <- IO.delay(Path.of(file)).flatMap(read)
          result <- parse(raw) match
            case Left(reason) =>
              IO.consoleForIO.errorln(s"restricted-replay input error: $reason").as(ExitCode(2))
            case Right(trace) =>
              val report = execute(trace)
              render(report).traverse_(IO.println).as(exitCode(report))
        yield result
      case _ => IO.consoleForIO.errorln(usage).as(ExitCode(2))
    action.handleErrorWith(e =>
      IO.consoleForIO
        .errorln(s"restricted-replay input error: ${e.getClass.getSimpleName}")
        .as(ExitCode(2))
    )
