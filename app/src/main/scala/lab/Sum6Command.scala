// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.kes.Sum6Kes as K

/** Bounded immutable offline fixture adapter, not a general header decoder. */
object Sum6Command:
  val MaxBytes: Int = 64 * 1024
  val Pin = "fb4925760c498bfc5569c69f848a56226698b056d11cc3f358d08ca2ecb5dde4"
  final case class Check(id: String, expected: String, actual: String):
    def matched: Boolean = expected == actual

  def check(data: Bytes): Either[String, Vector[Check]] =
    if data == null || data.value == null then Left("null fixture")
    else if data.size > MaxBytes then Left("fixture exceeds 64 KiB")
    else
      val digest = Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(data.toArray)).hex
      for
        _ <- Either.cond(digest == Pin, (), "immutable digest mismatch")
        rows = new String(data.toArray, StandardCharsets.UTF_8).linesIterator.toVector
        _ <- Either.cond(rows.size == 4, (), "expected 4 rows")
        checks <- rows.traverse { row =>
          row.split("\t", -1).toVector match
            case Vector(id, root, period, message, signature) =>
              for
                r <- Bytes.fromHex(root)
                m <- Bytes.fromHex(message)
                s <- Bytes.fromHex(signature)
                p <- period.toIntOption.toRight("invalid period")
                outcome <- K.verify(r, p, m, s).left.map(_.toString)
                actual = outcome match
                  case K.Result.SuppliedMessageSignatureVerified => "VERIFIED"
                  case K.Result.SignatureRejected                => "REJECTED"
              yield Check(id, "VERIFIED", actual)
            case _ => Left("invalid fixture shape")
        }
        _ <- Either.cond(checks.map(_.id).distinct.size == 4, (), "duplicate fixture identifiers")
      yield checks

  def run: IO[ExitCode] = IO
    .blocking {
      val stream = Files.newInputStream(Path.of("fixtures/sum6/originals.tsv"))
      try Bytes.fromArray(stream.readNBytes(MaxBytes + 1))
      finally stream.close()
    }
    .flatMap(b => IO.fromEither(check(b).left.map(new IllegalArgumentException(_))))
    .flatMap { checks =>
      checks
        .filterNot(_.matched)
        .traverse_(c => IO.println(s"FAIL ${c.id}: expected=${c.expected}, actual=${c.actual}")) *>
        IO.println(
          s"${checks.count(_.matched)}/4 supplied-message Sum6 checks matched: four archived public positives at relative periods 28, 29 and 35."
        ) *>
        IO.println(
          "Experimental supplied-message signature-only research. No header serialization, lifetime, registration, counter, valid-header or chain-authenticity claim. No independent full KES oracle."
        ).as(if checks.forall(_.matched) then ExitCode.Success else ExitCode.Error)
    }
    .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
