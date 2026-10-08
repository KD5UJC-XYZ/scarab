// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.ledger.{Balance, BalanceResult, FeeSize, FeeSizeResult, FeePredicate, SizePredicate}

/** Bounded demo: fee/size source-derived expectations versus genuine archived ledger outcomes. */
object FeeSizeCommand:
  final case class Check(
      name: String,
      archivedLedgerSuccess: Boolean,
      expectedSize: BigInt,
      predicates: FeeSizeResult,
      balance: BalanceResult
  ):
    def matched: Boolean =
      (predicates.fee match
        case FeePredicate.Satisfied(_, _) => true
        case _                            => false
      ) &&
        (predicates.size match
          case SizePredicate.Satisfied(actual, _) => actual == expectedSize
          case _                                  => false) &&
        (balance.isInstanceOf[BalanceResult.PredicateSatisfied] == archivedLedgerSuccess)

  private def decimal(text: String): Either[String, BigInt] =
    if text.nonEmpty && text.length <= 20 && text.forall(c => c >= '0' && c <= '9') then
      Right(BigInt(text))
    else Left("expected bounded unsigned decimal")

  def parse(text: String): Either[String, Vector[Check]] =
    text.linesIterator
      .filterNot(s => s.isBlank || s.startsWith("#"))
      .toVector
      .traverse { line =>
        line.split("\t", -1).toVector match
          case Vector(name, raw, resolved, perByte, fixed, maximum, expected, archived)
              if archived == "true" || archived == "false" =>
            for
              bytes <- Bytes.fromHex(raw)
              utxo <- Bytes.fromHex(resolved)
              a <- decimal(perByte)
              b <- decimal(fixed)
              mx <- decimal(maximum)
              size <- decimal(expected)
              params <- FeeSize.Parameters.create("Conway", 9, a, b, mx).left.map(_.toString)
              context <- FeeSize.Context.decode(params, utxo).left.map(_.toString)
              tx <- FeeSize.decode(bytes).left.map(_.toString)
              predicates <- FeeSize.checkTransferFeeAndSize(context, tx).left.map(_.toString)
              body <- Balance.decode(bytes).left.map(_.toString)
              balance <- Balance
                .check("Conway", 9, context.resolved.view.mapValues(_.value).toMap, body)
                .left
                .map(_.toString)
            yield Check(name, archived == "true", size, predicates, balance)
          case _ => Left("expected eight fee/size TSV fields")
      }
      .flatMap(xs =>
        Either.cond(
          xs.size == 2 && xs.map(_.name).distinct.size == 2 && xs
            .count(_.archivedLedgerSuccess) == 1,
          xs,
          "expected two distinct archived cases, one accepted and one rejected"
        )
      )

  private val digest = "233ccf7eb33de062d8fec81d4a232d9bdc79aa2dc97eb4e75812f77f2e7047e8"
  def run: IO[ExitCode] = IO
    .blocking {
      val stream = Files.newInputStream(Path.of("fixtures/fee-size/fee-size-vectors.tsv"))
      val bytes =
        try stream.readNBytes(1048577)
        finally stream.close()
      if bytes.length > 1048576 then
        throw new IllegalArgumentException("fee/size fixture byte limit")
      val actual = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .map(b => f"${b & 0xff}%02x")
        .mkString
      if actual != digest then
        throw new IllegalArgumentException("fee/size fixture digest mismatch")
      new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    }
    .flatMap(text => IO.fromEither(parse(text).left.map(new IllegalArgumentException(_))))
    .flatMap { checks =>
      checks.traverse_(c =>
        IO.println(
          s"${if c.matched then "PASS" else "FAIL"}\t${c.name}\tfee=${c.predicates.fee}\tsize=${c.predicates.size}\tbalance=${c.balance}\tarchivedWholeLedgerSuccess=${c.archivedLedgerSuccess}"
        )
      ) *>
        IO.println(
          s"${checks.count(_.matched)}/2 fee/size source-derived expectations matched. Event 2 passes fee and size but fails balance. Archived whole-ledger outcomes are separate; no fresh Haskell predicate oracle or ledger transition executed. CLI fee estimation reserializes and is not a raw-size oracle."
        ).as(if checks.forall(_.matched) then ExitCode.Success else ExitCode.Error)
    }
    .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
