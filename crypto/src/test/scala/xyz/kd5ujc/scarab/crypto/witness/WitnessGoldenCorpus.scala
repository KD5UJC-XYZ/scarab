// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import cats.syntax.all.*
import io.circe.{Decoder, Error as JsonError}
import io.circe.parser.decode
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes

/** Test-only reader of immutable public inputs and the two independently archived observations. */
private[witness] object WitnessGoldenCorpus:
  enum Error:
    case DigestMismatch(file: String)
    case MalformedRow(file: String, row: Int)
    case MalformedField(id: String, field: String)
    case DuplicateId(file: String, id: String)
    case IdMismatch(file: String, missing: Set[String], extra: Set[String])
    case MalformedArchive(cause: JsonError)
    case InconsistentObservation(id: String)

  final case class Input(id: String, key: Bytes, signature: Bytes, message: Bytes)
  final case class Golden(input: Input, expected: Either[WitnessInputError, VerificationResult])
  private final case class Status(id: String, result: String, sodium: String, strict: String)
  private final case class Native(
      id: String,
      public_key_bytes: Int,
      signature_bytes: Int,
      message_bytes: Int,
      native_called: Boolean,
      native_rc: Option[Int],
      pinned_sodium: Option[Boolean]
  ) derives Decoder

  private val header =
    "id\tsource_expected\texpected_kind\texpected_result\tsystem_sodium\tbc_raw\tstrict_archived"

  def checked(file: String, bytes: Array[Byte], pin: String): Either[Error, String] =
    val found = Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(bytes)).hex
    Either.cond(found == pin, new String(bytes, UTF_8), Error.DigestMismatch(file))

  private def unique[A](file: String, rows: Vector[A])(
      id: A => String
  ): Either[Error, Map[String, A]] =
    rows.groupBy(id).collectFirst { case (name, values) if values.size != 1 => name } match
      case Some(name) => Left(Error.DuplicateId(file, name))
      case None       => Right(rows.map(a => id(a) -> a).toMap)

  private def sameIds(
      file: String,
      expected: Set[String],
      actual: Set[String]
  ): Either[Error, Unit] =
    Either.cond(
      expected == actual,
      (),
      Error.IdMismatch(file, expected -- actual, actual -- expected)
    )

  def inputs(text: String): Either[Error, Vector[Input]] =
    text.linesIterator.toVector.zipWithIndex.traverse { (line, index) =>
      line.split("\t", -1).toVector match
        case Vector(id, key, signature, message) if id.nonEmpty =>
          def bytes(value: String, field: String) =
            Bytes.fromHex(value).leftMap(_ => Error.MalformedField(id, field))
          (bytes(key, "key"), bytes(signature, "signature"), bytes(message, "message"))
            .mapN(Input(id, _, _, _))
        case _ => Left(Error.MalformedRow("vectors.tsv", index + 1))
    }

  private def statuses(text: String): Either[Error, Vector[Status]] =
    val lines = text.linesIterator.toVector
    if !lines.headOption.contains(header) then Left(Error.MalformedRow("status.tsv", 1))
    else
      lines.tail.zipWithIndex.traverse { (line, index) =>
        line.split("\t", -1).toVector match
          case Vector(id, source, kind, result, sodium, bc, strict)
              if id.nonEmpty && Set("true", "false", "unknown").contains(source) &&
                kind.nonEmpty && Set("true", "false", "MalformedLength").contains(bc) =>
            Right(Status(id, result, sodium, strict))
          case _ => Left(Error.MalformedRow("status.tsv", index + 2))
      }

  def read(vectors: String, status: String, archive: String): Either[Error, Vector[Golden]] =
    for
      in <- inputs(vectors)
      byId <- unique("vectors.tsv", in)(_.id)
      results <- statuses(status)
      statusById <- unique("status.tsv", results)(_.id)
      native <- decode[Vector[Native]](archive).leftMap(Error.MalformedArchive.apply)
      nativeById <- unique("results.json", native)(_.id)
      _ <- sameIds("status.tsv", byId.keySet, statusById.keySet)
      _ <- sameIds("results.json", byId.keySet, nativeById.keySet)
      goldens <- in.traverse { input =>
        val s = statusById(input.id)
        val n = nativeById(input.id)
        val lengths = n.public_key_bytes == input.key.size &&
          n.signature_bytes == input.signature.size && n.message_bytes == input.message.size
        val observed = (s.result, s.sodium, s.strict, n.native_called, n.native_rc, n.pinned_sodium)
        val expected = observed match
          case ("SignatureVerified", "true", "true", true, Some(0), Some(true))
              if input.key.size == 32 && input.signature.size == 64 =>
            Some(Right(VerificationResult.SignatureVerified))
          case ("SignatureRejected", "false", "false", true, Some(-1), Some(false))
              if input.key.size == 32 && input.signature.size == 64 =>
            Some(Right(VerificationResult.SignatureRejected))
          case (
                "MalformedSignatureLength",
                "NotRunMalformedLength",
                "MalformedLength",
                false,
                None,
                None
              ) if input.key.size == 32 && input.signature.size != 64 =>
            Some(Left(WitnessInputError.MalformedSignatureLength(input.signature.size)))
          case _ => None
        expected
          .filter(_ => lengths)
          .toRight(Error.InconsistentObservation(input.id))
          .map(Golden(input, _))
      }
    yield goldens
