// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.opcert.OperationalCertificate as O

/** Bounded immutable offline fixture adapter, not a general header decoder. */
object OpcertCommand:
  val MaxBytes: Int = 64 * 1024
  val Pin = "534d41faeb5927a12a17982054fb80a74dea2c8aa48e8dd4f45999ed884399e2"
  final case class Check(id: String, expected: String, actual: String):
    def matched: Boolean = expected == actual

  def check(data: Bytes): Either[String, Vector[Check]] =
    if data == null then Left("null fixture")
    else if data.size > MaxBytes then Left("fixture exceeds 64 KiB")
    else
      val digest = Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(data.toArray)).hex
      for
        _ <- Either.cond(digest == Pin, (), "immutable digest mismatch")
        rows = new String(data.toArray, StandardCharsets.UTF_8).linesIterator.toVector
        _ <- Either.cond(rows.size == 28, (), "expected 28 rows")
        checks <- rows.traverse { row =>
          row.split("\t", -1).toVector match
            case Vector(id, cold, hot, counter, start, signature, message, expected) =>
              for
                k <- Bytes.fromHex(cold)
                h <- Bytes.fromHex(hot)
                s <- Bytes.fromHex(signature)
                c <- O.Certificate
                  .fromBytes(h, BigInt(counter), BigInt(start), s)
                  .left
                  .map(_.toString)
                m <- O.signableBytes(c).left.map(_.toString)
                outcome <- O.verify(k, h, BigInt(counter), BigInt(start), s).left.map(_.toString)
                actual = outcome match
                  case O.Result.OperationalCertificateSignatureVerified => "VERIFIED"
                  case O.Result.SignatureRejected                       => "REJECTED"
              yield Check(id, expected, if m.hex == message then actual else "MESSAGE_MISMATCH")
            case _ => Left("invalid fixture shape")
        }
        _ <- Either.cond(checks.map(_.id).distinct.size == 28, (), "duplicate fixture identifiers")
      yield checks

  def run: IO[ExitCode] = IO
    .blocking {
      val stream = Files.newInputStream(Path.of("fixtures/opcert/certificates.tsv"))
      try Bytes.fromArray(stream.readNBytes(MaxBytes + 1))
      finally stream.close()
    }
    .flatMap(b => IO.fromEither(check(b).left.map(new IllegalArgumentException(_))))
    .flatMap { checks =>
      checks
        .filterNot(_.matched)
        .traverse_(c => IO.println(s"FAIL ${c.id}: expected=${c.expected}, actual=${c.actual}")) *>
        IO.println(
          s"${checks.count(_.matched)}/28 operational-certificate signature checks matched: four archived public positives and 24 native-rejected mutations."
        ) *>
        IO.println(
          "Experimental signature-only research. No pool registration, current-counter, KES, valid-header or historical chain-authenticity claim."
        ).as(if checks.forall(_.matched) then ExitCode.Success else ExitCode.Error)
    }
    .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
