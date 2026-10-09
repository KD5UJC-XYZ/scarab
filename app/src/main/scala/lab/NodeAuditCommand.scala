// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import ReferenceJson.Json
import scala.concurrent.duration.*

/** Offline audit only: checked forward replay precedes all reference post-state interpretation. */
object NodeAuditCommand:
  private[lab] val MaxBlocks = 12
  private val MaxBytes = 20 * 1024 * 1024
  private def raw(s: String): Bytes = Bytes.fromArray(s.getBytes(StandardCharsets.UTF_8))
  private def text(b: Bytes): String =
    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(b.toArray)).toString
  private def checked[E, A](value: Either[E, A]): IO[A] =
    IO.fromEither(value.left.map(e => new IllegalArgumentException(e.toString)))
  private def number(n: BigInt): Json = Json.Str(n.toString)
  private[lab] def stateProjection(state: CoherentSequence.State): Json = Json.Obj(
    Map(
      "record" -> Json.Str("node-state"),
      "projection" -> ValidatedRestartCapture.projection(state),
      "revision" -> number(state.revision),
      "depth" -> number(state.depth),
      "compactedBlocks" -> number(state.compactedBlocks),
      "derivedAnchorId" -> state.derivedAnchorId.fold[Json](Json.Lit("null"))(b => Json.Str(b.hex))
    )
  )
  private[lab] def stateRecord(state: CoherentSequence.State): String =
    ValidatedRestartCapture.canonical(stateProjection(state))

  private[lab] def records(bytes: Bytes): Vector[Json] =
    require(bytes.size > 0 && bytes.size <= MaxBytes, "node stdout byte bound")
    val lines = text(bytes).linesIterator.filter(_.nonEmpty).take(1025).toVector
    require(lines.size <= 1024, "node stdout record bound")
    lines.map(line => ReferenceJson.parse(raw(line)))
  private def named(json: Json, key: String, name: String): Boolean = json match
    case Json.Obj(fields) => fields.get(key).contains(Json.Str(name))
    case _                => false
  private[lab] def checkProjection(logs: Vector[Json], state: CoherentSequence.State): Unit =
    val states = logs.filter(named(_, "record", "node-state"))
    require(
      states == Vector(stateProjection(state)),
      "exact unique online node-state projection required"
    )

  private def read(path: Path): Bytes =
    val stream = Files.newInputStream(path)
    val bytes =
      try stream.readNBytes(MaxBytes + 1)
      finally stream.close()
    require(bytes.nonEmpty && bytes.length <= MaxBytes, "nonempty bounded audit file required")
    Bytes.fromArray(bytes)

  private[lab] def replay(
      context: SequenceInput.Context,
      blocks: Vector[SequenceInput.Block],
      capacity: Int
  ): IO[Vector[CoherentSequence.Applied]] =
    for
      _ <- IO(
        require(
          capacity >= 1 && capacity <= 8 && blocks.size >= 2 && blocks.size <= MaxBlocks,
          "bounded audit capacity/target required"
        )
      )
      runtime <- CoherentSequence.create[IO](context, capacity).flatMap(checked)
      receipts <- blocks.foldLeft(IO.pure(Vector.empty[CoherentSequence.Applied])) {
        (prior, block) =>
          for
            done <- prior
            before <- runtime.snapshot
            _ <-
              if before.state.acquisition.size == capacity then
                runtime
                  .advanceAnchor(
                    before.fence,
                    before.state.acquisition.candidates.dropRight(1).last
                  )
                  .flatMap(checked)
                  .void
              else IO.unit
            candidate <- runtime.prepare(block).flatMap(checked)
            applied <- runtime.publish(candidate).flatMap(checked)
          yield done :+ applied
      }
    yield receipts

  private[lab] def assess(
      context: SequenceInput.Context,
      owned: CoherentSequenceCommand.OwnedOracle,
      stdout: Bytes,
      capacity: Int,
      target: Int
  ): IO[Json] =
    for
      _ <- IO(
        require(
          target >= 2 && target <= MaxBlocks && capacity >= 1 && capacity <= 8,
          "bounded audit arguments required"
        )
      )
      logs <- IO(records(stdout))
      originals <- checked(
        CoherentSequenceCommand.captures(owned.originals("scala-sequence-capture.md"), MaxBlocks)
      )
      onlineOriginals <- checked(CoherentSequenceCommand.captures(stdout, MaxBlocks))
      _ <- IO(
        require(
          originals == onlineOriginals && originals.size == target,
          "exact online/oracle capture binding required"
        )
      )
      blocks <- originals.traverse(o => checked(SequenceInput.block(o)))
      _ <- IO {
        require(
          blocks.map(_.header.hash).distinct.size == target,
          "distinct forward hashes required"
        )
        require(
          blocks.forall(_.header.slot / context.nonces.context.epochLength == context.epoch),
          "all originals must remain in supplied epoch"
        )
      }
      grouping <- checked(
        CoherentSequenceCommand.checkGrouping(
          blocks,
          Vector(
            owned.originals("signed-transaction-0-cbor.md"),
            owned.originals("signed-transaction-1-cbor.md")
          ),
          MaxBlocks
        )
      )
      receipts <- replay(context, blocks, capacity)
      state = receipts.last.state
      _ <- IO {
        require(
          state.depth == target && state.revision == target && state.compactedBlocks == (target - capacity)
            .max(0),
          "final replay depth/revision/compaction mismatch"
        )
        checkProjection(logs, state)
      }
      // This is the first interpretation of the pinned reference post-state bytes.
      previousCompared <- checked(
        CoherentSequenceCommand.compareOracle(context, receipts, owned, MaxBlocks)
      )
    yield Json.Obj(
      Map(
        "scope" -> Json.Str("node-audit"),
        "passed" -> Json.Lit("true"),
        "capturedBlocks" -> Json.Num(target.toString),
        "transactionCount" -> Json.Num("2"),
        "emptyBlocks" -> Json.Num(grouping.emptyBlocks.toString),
        "transactionBlockIndex" -> Json.Num(grouping.blockIndex.toString),
        "transactionIdsInBlockOrder" -> Json.Arr(grouping.transactionIds.map(b => Json.Str(b.hex))),
        "completeProjectionMatched" -> Json.Lit("true"),
        "referencePostStateMatched" -> Json.Lit("true"),
        "sameEpoch" -> Json.Lit("true"),
        "distinctForwardHashes" -> Json.Lit("true"),
        "previousEpochNonceCompared" -> Json.Lit(previousCompared.toString),
        "contextId" -> Json.Str(context.id.hex),
        "finalStateId" -> Json.Str(state.id.hex),
        "revision" -> number(state.revision),
        "depth" -> number(state.depth),
        "compactedBlocks" -> number(state.compactedBlocks),
        "derivedAnchorId" -> state.derivedAnchorId.fold[Json](Json.Lit("null"))(b =>
          Json.Str(b.hex)
        ),
        "captureSha256" -> Json.Str(owned.pins("captureSha256").hex),
        "oracleManifestSha256" -> Json.Str(owned.manifestDigest.hex),
        "fullLedgerValidated" -> Json.Lit("false"),
        "consensusValidated" -> Json.Lit("false"),
        "liveForkClaim" -> Json.Lit("false"),
        "durableClaim" -> Json.Lit("false")
      )
    )
  def run(args: List[String]): IO[ExitCode] =
    val work = args match
      case List(context, oracle, stdout, capacity, target) =>
        for
          limits <- IO {
            require(
              capacity.matches("[1-8]") && target.matches("[1-9][0-9]?"),
              "canonical bounded audit integers required"
            )
            require(
              target.toInt >= 2 && target.toInt <= MaxBlocks,
              "audit target must be 2 through 12"
            )
            (capacity.toInt, target.toInt)
          }
          source <- IO.blocking(SequenceInput.load(Path.of(context))).flatMap(checked)
          owned <- IO.blocking(CoherentSequenceCommand.loadOracle(Path.of(oracle))).flatMap(checked)
          bytes <- IO.blocking(read(Path.of(stdout)))
          report <- assess(source, owned, bytes, limits._1, limits._2)
          _ <- IO.println(ValidatedRestartCapture.canonical(report))
        yield ExitCode.Success
      case _ =>
        IO.raiseError(
          new IllegalArgumentException(
            "node-audit CONTEXT_DIR ORACLE_DIR NODE_STDOUT CAPACITY TARGET"
          )
        )
    work.timeout(120.seconds).handleErrorWith { error =>
      IO.println(
        ValidatedRestartCapture.canonical(
          Json.Obj(
            Map(
              "scope" -> Json.Str("node-audit"),
              "passed" -> Json.Lit("false"),
              "outcome" -> Json.Str("Rejected"),
              "detail" -> Json.Str(Option(error.getMessage).getOrElse(error.getClass.getName))
            )
          )
        )
      ).as(ExitCode(2))
    }
