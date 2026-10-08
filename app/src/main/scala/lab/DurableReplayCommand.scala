// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.Path
import lab.cbor.Bytes
import lab.ledger.RestrictedReplay as R
import lab.ledger.runtime.{NioReplayStore, ReplayStore}
import ReplayStore.*

/** Local research-store commands. Mutations require an explicit persisted head AND revision. */
object DurableReplayCommand:
  private val usage =
    "restricted-replay-store init <directory> <checkpoint-only.trace.tsv> | inspect <directory> | commit <directory> <revision> <head-sha256> <batch.trace.tsv> | rollback <directory> <revision> <head-sha256> <transition-sha256>"
  private def invalid(message: String): IO[Nothing] =
    IO.raiseError(new IllegalArgumentException(message))
  private def digest(text: String): IO[Bytes] =
    if text.length != 64 || !text.forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) then
      invalid("expected 64 lowercase hexadecimal digits")
    else IO.fromEither(Bytes.fromHex(text).left.map(new IllegalArgumentException(_)))
  private def revision(text: String): IO[BigInt] =
    if text.isEmpty || text.length > 20 || !text.forall(c => c >= '0' && c <= '9') ||
      (text.length > 1 && text.head == '0')
    then invalid("expected canonical uint64 revision")
    else
      val n = BigInt(text)
      if n.bitLength > 64 then invalid("revision exceeds uint64") else IO.pure(n)
  private def trace(file: String): IO[(Bytes, RestrictedReplayCommand.Trace)] =
    for
      raw <- RestrictedReplayCommand.read(Path.of(file))
      parsed <- IO.fromEither(
        RestrictedReplayCommand.parse(raw).left.map(new IllegalArgumentException(_))
      )
    yield (raw, parsed)
  private def render(snapshot: Snapshot): IO[Unit] =
    IO.println(
      s"restricted-replay-store-v1\tprofile=${R.ProfileId}\tprofileHash=${R.ProfileHash.hex}\tcheckpoint=${snapshot.state.checkpoint.id.hex}\thead=${snapshot.version.head.hex}\trevision=${snapshot.version.revision}\thistory=${snapshot.historyLength}\tundo=${snapshot.undo.map(_.transitionId.hex).getOrElse("-")}"
    ) *>
      IO.println(
        s"state=${snapshot.state.stateId.hex}\tfeesSinceCheckpoint=${snapshot.state.feesSinceCheckpoint}\tutxoEntries=${snapshot.state.utxo.size}\tutxoCbor=${snapshot.state.outputMap.hex}"
      ) *>
      IO.println(
        "scope=UTxO/fee-projection-only\tsourceAuthenticated=false\tfullLedger=false\ttickExecuted=false\tinstantStake=false\tblockValidation=false\tconsensus=false\tlocalPersistence=true\thardwarePowerLossGuarantee=false"
      )
  private def outcome(result: Either[Rejection, Snapshot]): IO[ExitCode] = result match
    case Right(snapshot) => render(snapshot).as(ExitCode.Success)
    case Left(reason) =>
      val code = reason match
        case Rejection.Ledger(R.Failure.Rejected(_, _))     => 1
        case Rejection.Ledger(R.Failure.Unsupported(_))     => 3
        case Rejection.Ledger(R.Failure.InternalFailure(_)) => 4
        case _                                              => 2
      IO.consoleForIO.errorln(s"restricted-replay-store rejected: $reason").as(ExitCode(code))
  private def matches(snapshot: Snapshot, expectedRevision: BigInt, expectedHead: Bytes): Boolean =
    snapshot.version.revision == expectedRevision && snapshot.version.head == expectedHead

  def run(args: List[String]): IO[ExitCode] =
    val action = args match
      case List("--help") => IO.println(usage).as(ExitCode.Success)
      case List("init", directory, file) =>
        for
          input <- trace(file)
          (raw, parsed) = input
          _ <-
            if parsed.transactions.nonEmpty then
              invalid("init requires a checkpoint-only trace with zero transaction rows")
            else IO.unit
          parameters = Bytes
            .fromHex(new String(raw.toArray, US_ASCII).linesIterator.next().split("\t", -1)(3))
            .toOption
            .get
          checkpoint = Checkpoint(
            parameters,
            parsed.initial.checkpoint.originalUtxo,
            parsed.initial.checkpoint.attributionDigest
          )
          code <- NioReplayStore
            .create[IO](Path.of(directory), checkpoint)
            .use(_.snapshot.flatMap(s => outcome(Right(s))))
        yield code
      case List("inspect", directory) =>
        NioReplayStore.open[IO](Path.of(directory)).use(_.snapshot.flatMap(s => outcome(Right(s))))
      case List("commit", directory, rev, head, file) =>
        for
          expectedRevision <- revision(rev)
          expectedHead <- digest(head)
          input <- trace(file)
          parsed = input._2
          code <- NioReplayStore.open[IO](Path.of(directory)).use { store =>
            store.snapshot.flatMap { before =>
              if !matches(before, expectedRevision, expectedHead) then
                outcome(Left(Rejection.StaleVersion))
              else if before.state.checkpoint.id != parsed.initial.checkpoint.id then
                invalid("batch checkpoint differs from owned store")
              else store.commit(before.version, parsed.transactions).flatMap(outcome)
            }
          }
        yield code
      case List("rollback", directory, rev, head, transition) =>
        for
          expectedRevision <- revision(rev)
          expectedHead <- digest(head)
          target <- digest(transition)
          code <- NioReplayStore.open[IO](Path.of(directory)).use { store =>
            store.snapshot.flatMap { before =>
              if !matches(before, expectedRevision, expectedHead) then
                outcome(Left(Rejection.StaleVersion))
              else store.rollback(before.version, target).flatMap(outcome)
            }
          }
        yield code
      case _ => IO.consoleForIO.errorln(usage).as(ExitCode(2))
    action.handleErrorWith {
      case e: IllegalArgumentException =>
        IO.consoleForIO
          .errorln(s"restricted-replay-store input error: ${e.getMessage}")
          .as(ExitCode(2))
      case e =>
        IO.consoleForIO
          .errorln(
            s"restricted-replay-store storage error: ${e.getClass.getSimpleName}: ${e.getMessage}"
          )
          .as(ExitCode(5))
    }
