// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import cats.syntax.all.*
import io.circe.{Decoder, Error as JsonError}
import io.circe.parser.decode
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, StandardCharsets}
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.codec.Bytes.HexError
import xyz.kd5ujc.scarab.ledger.{Block, Header}
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.node.ParallelPlutus

/** Uses the existing captured immutable `records.jsonl` schema. Paths identify separate read-only
  * inputs: native V5 snapshots and their original RocksDB checkpoints need not share a directory.
  */
object ReplayInputs:
  enum Schedule:
    case Comparison, RotatedMeasurements

  enum Setting(val variable: String):
    case Capture extends Setting("SCARAB_PREVIEW_CAPTURE")
    case Snapshot extends Setting("SCARAB_PREVIEW_SNAPSHOT")
    case Checkpoint extends Setting("SCARAB_PREVIEW_CHECKPOINT")
    case Output extends Setting("SCARAB_PREVIEW_OUTPUT")
    case Reference extends Setting("SCARAB_PREVIEW_REFERENCE")

  enum Error extends DomainError:
    case Missing(setting: Setting)
    case InvalidPath(value: String, cause: java.nio.file.InvalidPathException)
    case NoParallelWorkers
    case InvalidSchedule(value: String)
    case ComparisonRounds(value: Int)
    case Json(line: Int, cause: JsonError)
    case Utf8(cause: CharacterCodingException)
    case UnsafeChunk(chunk: String)
    case InvalidRange(slot: BigInt, offset: Long, length: Int)
    case NonIncreasing(previous: BigInt, actual: BigInt)
    case EmptyWindow
    case InvalidTransactionId(value: String, cause: HexError)
    case TransactionIdLength(value: String)
    case DuplicateTransaction(id: Bytes)
    case ExpectedInvalid(id: Bytes)
    case DuplicateTarget(slot: BigInt)
    case EmptyReference
    case NegativeTarget(slot: BigInt)
    case ReferenceHash(value: String, cause: HexError)
    case ReferenceHashLength(value: String)
    case TargetOutsideWindow(slot: BigInt, anchor: BigInt, last: BigInt)
    case TargetAbsentFromWindow(slot: BigInt)
    case DuplicateOutput(path: Path)
    case Slot(expected: BigInt, actual: BigInt)
    case BlockNumber(expected: BigInt, actual: BigInt)
    case HeaderHash(expected: String, actual: Bytes)
    case PreviousHash(expected: Bytes, actual: Option[Bytes])
    case SlotOrder(previous: BigInt, actual: BigInt)
    case BlockHash(expected: String, actual: String)
    case Workers(cause: ParallelPlutus.InvalidWorkers)
    case Rounds(value: Int)

  final case class Record(
      chunk: String,
      offset: Long,
      length: Int,
      slot: BigInt,
      block: Option[BigInt],
      hash: Option[String],
      sha256: Option[String]
  ):
    def validate: Either[Error, Record] =
      for
        path <- pathOf(chunk)
        _ <- Either.cond(
          !path.isAbsolute && path.getNameCount == 1 &&
            path.getFileName.toString == chunk && chunk != "." && chunk != "..",
          (),
          Error.UnsafeChunk(chunk)
        )
        _ <- Either.cond(
          slot >= 0 && offset >= 0 && length > 0 && length <= Block.MaxInputBytes &&
            offset <= Long.MaxValue - length,
          (),
          Error.InvalidRange(slot, offset, length)
        )
      yield this

    def check(header: Header.Shelley, bytesSha256: String): Either[Error, Unit] =
      for
        _ <- Either.cond(slot == header.slot, (), Error.Slot(slot, header.slot))
        _ <- block.traverse_(expected =>
          Either.cond(expected == header.blockNo, (), Error.BlockNumber(expected, header.blockNo))
        )
        _ <- hash.traverse_(expected =>
          Either.cond(expected == header.hash.hex, (), Error.HeaderHash(expected, header.hash))
        )
        _ <- sha256.traverse_(expected =>
          Either.cond(expected == bytesSha256, (), Error.BlockHash(expected, bytesSha256))
        )
      yield ()

  object Record:
    given Decoder[Record] = Decoder.instance { cursor =>
      for
        chunk <- cursor.get[String]("chunk")
        offset <- cursor.get[Long]("offset")
        length <- cursor.get[Int]("length")
        slot <- cursor.get[BigInt]("slot")
        block <- cursor.get[Option[BigInt]]("block")
        hash <- cursor.get[Option[String]]("hash")
        sha256 <- cursor.get[Option[String]]("sha256")
      yield Record(chunk, offset, length, slot, block, hash, sha256)
    }

  def lines(bytes: Array[Byte]): Either[Error, Vector[String]] =
    Either
      .catchOnly[CharacterCodingException](
        StandardCharsets.UTF_8
          .newDecoder()
          .decode(ByteBuffer.wrap(bytes))
          .toString
          .linesIterator
          .toVector
      )
      .left
      .map(Error.Utf8(_))

  def records(lines: Vector[String]): Either[Error, Vector[Record]] =
    lines.zipWithIndex
      .traverse { (line, index) =>
        decode[Record](line).left.map(Error.Json(index + 1, _)).flatMap(_.validate)
      }
      .flatMap { entries =>
        entries
          .sliding(2)
          .toVector
          .traverse_ {
            case Vector(a, b) =>
              Either.cond(a.slot < b.slot, (), Error.NonIncreasing(a.slot, b.slot))
            case _ => Right(())
          }
          .as(entries)
      }
      .flatMap(entries => Either.cond(entries.nonEmpty, entries, Error.EmptyWindow))

  def link(previous: BlockRef, header: Header.Shelley): Either[Error, BlockRef] =
    for
      _ <- Either.cond(
        header.prevHash.contains(previous.hash),
        (),
        Error.PreviousHash(previous.hash, header.prevHash)
      )
      _ <- Either.cond(
        header.blockNo == previous.blockNo + 1,
        (),
        Error.BlockNumber(previous.blockNo + 1, header.blockNo)
      )
      _ <- Either.cond(header.slot > previous.slot, (), Error.SlotOrder(previous.slot, header.slot))
    yield BlockRef(header.slot, header.hash, header.blockNo)

  final case class Config(
      capture: Path,
      snapshot: Path,
      checkpoint: Path,
      output: Path,
      genesis: Path,
      workers: Vector[ParallelPlutus.Workers],
      rounds: Int,
      reference: Option[Path],
      expectedFailures: Option[Path] = None,
      trace: Boolean = true,
      legacyOutputs: Boolean = false,
      schedule: Schedule = Schedule.Comparison
  )

  private def pathOf(value: String): Either[Error, Path] =
    Either
      .catchOnly[java.nio.file.InvalidPathException](Path.of(value))
      .left
      .map(Error.InvalidPath(value, _))

  def config(environment: Map[String, String]): Either[Error, Config] =
    def required(setting: Setting): Either[Error, Path] =
      environment.get(setting.variable).toRight(Error.Missing(setting)).flatMap(pathOf)
    for
      capture <- required(Setting.Capture)
      snapshot <- required(Setting.Snapshot)
      checkpoint <- required(Setting.Checkpoint)
      output <- required(Setting.Output)
      // The environment is a presentation boundary; numeric configuration is checked before use.
      counts = environment
        .get("SCARAB_PREVIEW_WORKERS")
        .fold(Vector(4))(
          _.split(',').toVector.map(_.trim.toIntOption.getOrElse(0))
        )
      workers <- counts.distinct.traverse(n =>
        ParallelPlutus.Workers.from(n).left.map(Error.Workers(_))
      )
      _ <- Either.cond(workers.exists(_.value > 1), (), Error.NoParallelWorkers)
      genesis <- pathOf(environment.getOrElse("SCARAB_PREVIEW_GENESIS", "fixtures/preview/genesis"))
      reference <- required(Setting.Reference)
      failures <- environment.get("SCARAB_PREVIEW_EXPECTED_FAILURES").traverse(pathOf)
      rounds = environment.get("SCARAB_PREVIEW_ROUNDS").fold(1)(_.toIntOption.getOrElse(0))
      _ <- Either.cond(rounds > 0, (), Error.Rounds(rounds))
      scheduleText = environment.getOrElse("SCARAB_PREVIEW_SCHEDULE", "Comparison")
      schedule <- Schedule.values
        .find(_.toString.equalsIgnoreCase(scheduleText))
        .toRight(Error.InvalidSchedule(scheduleText))
      _ <- Either.cond(
        schedule != Schedule.Comparison || rounds == 1,
        (),
        Error.ComparisonRounds(rounds)
      )
    yield Config(
      capture,
      snapshot,
      checkpoint,
      output,
      genesis,
      workers,
      rounds,
      Some(reference),
      expectedFailures = failures,
      schedule = schedule
    )

  /** Explicit capture paths, using the same settings as a single window. Worker bounds and rounds
    * remain common to the batch; only one checkpoint/store is open at a time.
    */
  def windows(
      lines: Vector[String],
      environment: Map[String, String]
  ): Either[Error, Vector[Config]] =
    lines.zipWithIndex
      .traverse { (line, index) =>
        io.circe.parser.parse(line).left.map(Error.Json(index + 1, _)).flatMap { json =>
          val cursor = json.hcursor
          val settings = Vector(
            "capture" -> "SCARAB_PREVIEW_CAPTURE",
            "snapshot" -> "SCARAB_PREVIEW_SNAPSHOT",
            "checkpoint" -> "SCARAB_PREVIEW_CHECKPOINT",
            "output" -> "SCARAB_PREVIEW_OUTPUT",
            "genesis" -> "SCARAB_PREVIEW_GENESIS",
            "reference" -> "SCARAB_PREVIEW_REFERENCE",
            "expectedFailures" -> "SCARAB_PREVIEW_EXPECTED_FAILURES"
          )
          settings
            .traverse { (field, variable) =>
              cursor
                .get[Option[String]](field)
                .left
                .map(Error.Json(index + 1, _))
                .map(_.map(variable -> _))
            }
            .flatMap(values => config(environment ++ values.flatten.toMap))
        }
      }
      .flatMap { configs =>
        configs
          .groupBy(_.output.toAbsolutePath.normalize)
          .collectFirst {
            case (path, occurrences) if occurrences.size > 1 => path
          }
          .fold[Either[Error, Vector[Config]]](
            Either.cond(configs.nonEmpty, configs, Error.EmptyWindow)
          )(path => Left(Error.DuplicateOutput(path)))
      }

  def expectedFailures(lines: Vector[String]): Either[Error, Vector[Bytes]] =
    lines.zipWithIndex
      .traverse { (line, index) =>
        io.circe.parser.parse(line).left.map(Error.Json(index + 1, _)).flatMap { json =>
          for
            text <- json.hcursor.get[String]("txId").left.map(Error.Json(index + 1, _))
            id <- Bytes.fromHex(text).left.map(Error.InvalidTransactionId(text, _))
            _ <- Either.cond(id.size == 32, (), Error.TransactionIdLength(text))
            valid <- json.hcursor.get[Boolean]("isValid").left.map(Error.Json(index + 1, _))
            _ <- Either.cond(!valid, (), Error.ExpectedInvalid(id))
          yield id
        }
      }
      .flatMap { ids =>
        ids
          .groupBy(identity)
          .collectFirst { case (id, occurrences) if occurrences.size > 1 => id }
          .fold[Either[Error, Vector[Bytes]]](Right(ids))(id =>
            Left(Error.DuplicateTransaction(id))
          )
      }

  def referenceTargets(lines: Vector[String]): Either[Error, Map[BigInt, String]] =
    lines.zipWithIndex
      .traverse { (line, index) =>
        io.circe.parser.parse(line).left.map(Error.Json(index + 1, _)).flatMap { json =>
          for
            slot <- json.hcursor.get[BigInt]("slot").left.map(Error.Json(index + 1, _))
            hash <- json.hcursor.get[String]("hash").left.map(Error.Json(index + 1, _))
            _ <- Either.cond(slot >= 0, (), Error.NegativeTarget(slot))
            bytes <- Bytes.fromHex(hash).left.map(Error.ReferenceHash(hash, _))
            _ <- Either.cond(bytes.size == 32, (), Error.ReferenceHashLength(hash))
          yield slot -> hash
        }
      }
      .flatMap(rows => Either.cond(rows.nonEmpty, rows, Error.EmptyReference))
      .flatMap { rows =>
        rows
          .groupBy(_._1)
          .collectFirst { case (slot, occurrences) if occurrences.size > 1 => slot }
          .fold[Either[Error, Map[BigInt, String]]](Right(rows.toMap))(slot =>
            Left(Error.DuplicateTarget(slot))
          )
      }

  /** Every requested reference point must be in this exact replay, not silently filtered out. */
  def referenceWindow(
      targets: Map[BigInt, String],
      anchor: BigInt,
      records: Vector[Record]
  ): Either[Error, Map[BigInt, String]] =
    for
      last <- records.lastOption.map(_.slot).toRight(Error.EmptyWindow)
      _ <- Either.cond(targets.nonEmpty, (), Error.EmptyReference)
      slots = records.map(_.slot).toSet
      _ <- targets.keys.toVector.sorted.traverse_ { slot =>
        if slot <= anchor || slot > last then Left(Error.TargetOutsideWindow(slot, anchor, last))
        else Either.cond(slots(slot), (), Error.TargetAbsentFromWindow(slot))
      }
    yield targets
