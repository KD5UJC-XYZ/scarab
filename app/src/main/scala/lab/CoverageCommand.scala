// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.ledger.{Balance, BalanceResult, Coverage, CoverageResult}
import lab.witness.{CardanoWitness, VerificationResult}

/** IO boundary for two archived Conway PV9 transfer cases. Three predicates, no validity Boolean.
  */
object CoverageCommand:
  final case class Check(
      name: String,
      expectedCoverage: Boolean,
      coverage: CoverageResult,
      signatures: Vector[VerificationResult],
      balance: BalanceResult
  ):
    def matched: Boolean = coverage.covered == expectedCoverage &&
      signatures.nonEmpty && signatures.forall(_ == VerificationResult.SignatureVerified) &&
      (balance match
        case BalanceResult.PredicateSatisfied(_, _) => true
        case _                                      => false)

  def parse(text: String): Either[String, Vector[Check]] =
    text.linesIterator
      .filterNot(s => s.isBlank || s.startsWith("#"))
      .toVector
      .traverse { line =>
        line.split("\t", -1).toVector match
          case Vector(name, transaction, resolved, expected)
              if expected == "true" || expected == "false" =>
            for
              raw <- Bytes.fromHex(transaction)
              u <- Bytes.fromHex(resolved)
              projection <- Coverage.decode(raw).left.map(_.toString)
              utxo <- Coverage.decodeResolved(u).left.map(_.toString)
              coverage <- Coverage.check("Conway", 9, utxo, projection).left.map(_.toString)
              signatures <- projection.witnesses.traverse(w =>
                CardanoWitness.verifyVKeyWitness(projection.body, w).left.map(_.toString)
              )
              body <- Balance.decode(raw).left.map(_.toString)
              balance <- Balance
                .check("Conway", 9, utxo.view.mapValues(_.value).toMap, body)
                .left
                .map(_.toString)
            yield Check(name, expected == "true", coverage, signatures, balance)
          case _ =>
            Left("expected name, transaction, resolved full outputs, coverage boolean TSV fields")
      }
      .flatMap { xs =>
        Either.cond(
          xs.size == 2 && xs.map(_.name).distinct.size == 2 && xs.count(_.expectedCoverage) == 1,
          xs,
          "expected two distinct cases, one accepted and one missing-key rejection"
        )
      }

  private val digest = "a876e7ffd7bd00881a8e001a4a73151680f4d604ec426910ac9434dc3ecec4b4"
  def run: IO[ExitCode] = IO
    .blocking {
      val stream = Files.newInputStream(Path.of("fixtures/coverage/coverage-vectors.tsv"))
      val data =
        try stream.readNBytes(1048577)
        finally stream.close()
      if data.length > 1048576 then
        throw new IllegalArgumentException("coverage fixtures exceed 1 MiB")
      val actual = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(data)
        .map(b => f"${b & 0xff}%02x")
        .mkString
      if actual != digest then
        throw new IllegalArgumentException("coverage projection digest mismatch")
      new String(data, java.nio.charset.StandardCharsets.UTF_8)
    }
    .flatMap(text => IO.fromEither(parse(text).left.map(new IllegalArgumentException(_))))
    .flatMap { checks =>
      checks.traverse_(c =>
        IO.println(
          s"${if c.matched then "PASS" else "FAIL"}\t${c.name}\t${c.coverage}\tsignatures=${c.signatures.mkString(",")}\tbalance=${c.balance}"
        )
      ) *>
        IO.println(
          s"${checks.count(_.matched)}/2 archived Conway PV9 key-coverage expectations matched. Both cases have valid provided signatures and conserved value; one lacks a required payment key. These separate predicates do not establish transaction validity or a ledger transition. No fresh Haskell ledger oracle executed."
        ).as(if checks.forall(_.matched) then ExitCode.Success else ExitCode.Error)
    }
    .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
