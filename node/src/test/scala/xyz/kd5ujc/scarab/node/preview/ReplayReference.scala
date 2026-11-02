// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import cats.effect.Sync
import cats.syntax.all.*
import io.circe.{Json, JsonObject}
import java.io.IOException
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.node.StateDump
import xyz.kd5ujc.scarab.protocol.Era

/** The existing scripts/oracle/reference.py directory: index points plus independently produced
  * reduced bodies named <slot>-<hash>.json. This compares required native fields, not Scala-only
  * fields or the full native ledger. Numeric values compare exactly; there is no gap mask.
  */
object ReplayReference:
  enum Segment:
    case Field(name: String)
    case Index(value: Int)

  enum Error extends DomainError:
    case Read(path: Path, cause: IOException)
    case Input(path: Path, cause: ReplayInputs.Error)
    case JsonInput(path: Path, cause: io.circe.Error)
    case NotObject(path: Path)
    case MissingFields(path: Path, names: Set[String])
    case UnknownEra(path: Path, value: String)
    case PointSlot(path: Path, expected: BigInt, actual: BigInt)
    case PointHash(path: Path, expected: String, actual: String)
    case EraMismatch(expected: Era, actual: Era)
    case MissingValue(path: Vector[Segment], expected: Json)
    case DifferentValue(path: Vector[Segment], expected: Json, actual: Json)

  final case class Bound(
      slot: BigInt,
      hash: String,
      era: Era,
      body: Json,
      source: Path,
      sha256: String
  )

  // Snapshot.projection in scripts/oracle/ledger.py emits these fields for every supported era.
  // Refuse point-only stand-ins; every additional reference field is also compared recursively.
  private val commonFields = Set(
    "point",
    "epoch",
    "fees",
    "deposited",
    "treasury",
    "reserves",
    "blocksCurrent",
    "blocksBefore",
    "snapshots",
    "rewardUpdate",
    "stakeDistrib",
    "protocolState",
    "accounts",
    "dreps",
    "numDormantEpochs",
    "params",
    "genDelegs",
    "futureGenDelegs",
    "pools",
    "poolParams",
    "futurePools",
    "retiring",
    "utxo",
    "era"
  )

  def decode(path: Path, slot: BigInt, hash: String, bytes: Array[Byte]): Either[Error, Bound] =
    for
      lines <- ReplayInputs.lines(bytes).leftMap(Error.Input(path, _))
      body <- io.circe.parser.parse(lines.mkString("\n")).leftMap(Error.JsonInput(path, _))
      fields <- body.asObject.toRight(Error.NotObject(path))
      name <- body.hcursor.get[String]("era").leftMap(Error.JsonInput(path, _))
      era <- Era.values
        .find(_.toString.equalsIgnoreCase(name))
        .toRight(Error.UnknownEra(path, name))
      required = commonFields ++ (if era == Era.Conway then Set("gov") else Set("ppup", "mir"))
      missing = required -- fields.keys.toSet
      _ <- Either.cond(missing.isEmpty, (), Error.MissingFields(path, missing))
      actualSlot <- body.hcursor
        .downField("point")
        .get[BigInt]("slot")
        .leftMap(Error.JsonInput(path, _))
      actualHash <- body.hcursor
        .downField("point")
        .get[String]("hash")
        .leftMap(Error.JsonInput(path, _))
      _ <- Either.cond(actualSlot == slot, (), Error.PointSlot(path, slot, actualSlot))
      _ <- Either.cond(actualHash == hash, (), Error.PointHash(path, hash, actualHash))
      digest = MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .map(b => f"${b & 255}%02x")
        .mkString
    yield Bound(slot, hash, era, body, path, digest)

  def load[F[_]: Sync](directory: Path, slot: BigInt, hash: String): F[Either[Error, Bound]] =
    val path = directory.resolve(f"$slot%012d-$hash.json")
    Sync[F].blocking(Files.readAllBytes(path)).attempt.flatMap {
      case Left(cause: IOException) => Sync[F].pure(Left(Error.Read(path, cause)))
      case Left(defect)             => Sync[F].raiseError(defect)
      case Right(bytes)             => Sync[F].pure(decode(path, slot, hash, bytes))
    }

  private val digestKeys = Set("count", "digest", "buckets")
  private def isDigest(value: JsonObject): Boolean = value.keys.toSet == digestKeys
  private def digest(value: JsonObject): Json =
    if isDigest(value) then Json.fromJsonObject(value)
    else
      value.toVector
        .foldLeft(StateDump.Digest.empty)((d, e) => d.add(e._1, StateDump.canon(e._2)))
        .json

  /** Same required-field/digest relation as scripts/oracle/compare.py, with exact numbers. Native
    * `era` is validated separately; the Scala dump's format and other extra fields are not
    * represented natively and therefore are not claimed reference-checked.
    */
  def compare(reference: Bound, actual: Json, era: Era): Either[Error, Unit] =
    if reference.era != era then Left(Error.EraMismatch(reference.era, era))
    else
      @tailrec def walk(todo: List[(Vector[Segment], Json, Json)]): Either[Error, Unit] = todo match
        case Nil => Right(())
        case (path, expected, found) :: rest =>
          (expected.asObject, found.asObject, expected.asArray, found.asArray) match
            case (Some(a), Some(b), _, _) if isDigest(a) || isDigest(b) =>
              val x = digest(a)
              val y = digest(b)
              if x == y then walk(rest) else Left(Error.DifferentValue(path, x, y))
            case (Some(a), Some(b), _, _) =>
              val children = a.toVector.sortBy(_._1).traverse { (key, value) =>
                val at = path :+ Segment.Field(key)
                b(key).toRight(Error.MissingValue(at, value)).map(other => (at, value, other))
              }
              children match
                case Left(error)   => Left(error)
                case Right(values) => walk(values.toList ::: rest)
            case (_, _, Some(a), Some(b)) if a.size == b.size =>
              walk(
                a.zip(b)
                  .zipWithIndex
                  .map { case ((x, y), index) =>
                    (path :+ Segment.Index(index), x, y)
                  }
                  .toList ::: rest
              )
            case _ =>
              if expected == found then walk(rest)
              else Left(Error.DifferentValue(path, expected, found))
      walk(List((Vector.empty, reference.body.mapObject(_.remove("era")), actual)))
