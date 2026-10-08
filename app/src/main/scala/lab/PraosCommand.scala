// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.vrf.PraosVrfCertificate as P

/** Pinned, bounded offline certificate fixtures only; not a header decoder. */
object PraosCommand:
  val MaxBytes: Int = 64 * 1024
  val FileName = "certificates.tsv"
  val Pin = "69e9488ad4cda6ec1cb157409449c4b97e741d2a37987ae743025041cfb36aac"
  final case class Check(id: String, expected: String, actual: String):
    def matched: Boolean = expected == actual

  def admit(data: Bytes): Either[String, String] =
    if data == null then Left("null fixture")
    else if data.size > MaxBytes then Left("fixture exceeds 64 KiB")
    else
      val hash = Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(data.toArray)).hex
      Either.cond(
        hash == Pin,
        new String(data.toArray, StandardCharsets.UTF_8),
        "immutable digest mismatch"
      )

  def check(data: Bytes): Either[String, Vector[Check]] =
    for
      text <- admit(data)
      rows = text.linesIterator.toVector
      _ <- Either.cond(rows.size == 18, (), "expected 18 rows")
      checks <- rows.traverse { row =>
        row.split("\t", -1).toVector match
          case Vector(id, slot, nonce, key, proof, claim, alpha, expected) =>
            for
              s <- P.Slot.fromBigInt(BigInt(slot)).left.map(_.toString)
              n <-
                if nonce == "NEUTRAL" then Right(P.NeutralNonce)
                else Bytes.fromHex(nonce).flatMap(b => P.Hash32.fromBytes(b).left.map(_.toString))
              input <- P.Input.create(s, n).left.map(_.toString)
              k <- Bytes.fromHex(key)
              p <- Bytes.fromHex(proof)
              c <- Bytes.fromHex(claim)
              a <- P.alpha(input).left.map(_.toString)
              actual <- P.verify(input, k, p, c) match
                case P.Result.VerifiedCertificate(out) => Right("VERIFIED:" + out.hex)
                case P.Result.OutputMismatch           => Right("OUTPUT_MISMATCH")
                case P.Result.ProofRejected(_)         => Right("PROOF_REJECTED")
                case other => Left(s"unexpected certificate outcome: $other")
            yield Check(id, expected, if a.hex == alpha then actual else "ALPHA_MISMATCH:" + a.hex)
          case _ => Left("invalid fixture shape")
      }
      _ <- Either.cond(checks.map(_.id).distinct.size == 18, (), "duplicate fixture identifiers")
    yield checks

  def run: IO[ExitCode] = IO
    .blocking {
      val stream = Files.newInputStream(Path.of("fixtures/praos", FileName))
      try Bytes.fromArray(stream.readNBytes(MaxBytes + 1))
      finally stream.close()
    }
    .flatMap(b => IO.fromEither(check(b).left.map(new IllegalArgumentException(_))))
    .flatMap { checks =>
      checks
        .filterNot(_.matched)
        .traverse_(c => IO.println(s"FAIL ${c.id}: expected=${c.expected}, actual=${c.actual}")) *>
        IO.println(
          s"${checks.count(_.matched)}/18 Praos certificate checks matched: four archived public positives, 16 original native mutation rows (including two of those positives)."
        ) *>
        IO.println(
          "Experimental certificate-only research. Supplied public epoch nonce; historical chain inclusion unestablished. No full header validity, leader threshold, pool key binding, KES, operational certificates, nonce evolution or TPraos support."
        ).as(if checks.forall(_.matched) then ExitCode.Success else ExitCode.Error)
    }
    .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
