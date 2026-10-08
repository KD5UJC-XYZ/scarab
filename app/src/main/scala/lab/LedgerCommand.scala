// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.ledger.{Balance, BalanceResult}

/** IO boundary for four pinned Haskell-ledger corpus value projections. */
object LedgerCommand:
  final case class Check(name: String, expected: Boolean, result: BalanceResult):
    def satisfied: Boolean = result match
      case BalanceResult.PredicateSatisfied(_, _) => true
      case _                                      => false
    def matched: Boolean = satisfied == expected

  def parse(text: String): Either[String, Vector[Check]] =
    text.linesIterator
      .filterNot(s => s.isBlank || s.startsWith("#"))
      .toVector
      .traverse { line =>
        line.split("\t", -1).toVector match
          case Vector(name, transaction, resolved, expected)
              if expected == "true" || expected == "false" =>
            for
              tx <- Bytes.fromHex(transaction)
              u <- Bytes.fromHex(resolved)
              body <- Balance.decode(tx).left.map(_.toString)
              utxo <- Balance.decodeResolved(u).left.map(_.toString)
              result <- Balance.check("Conway", 9, utxo, body).left.map(_.toString)
            yield Check(name, expected == "true", result)
          case _ => Left("expected name, transaction CBOR, resolved-value CBOR, boolean TSV fields")
      }
      .flatMap(xs =>
        Either.cond(
          xs.size == 4 && xs.map(_.name).distinct.size == 4,
          xs,
          "expected four distinct predicate cases"
        )
      )

  def run: IO[ExitCode] = IO
    .blocking {
      val path = Path.of("fixtures/ledger/ledger-vectors.tsv")
      val stream = Files.newInputStream(path)
      val data =
        try stream.readNBytes(1048577)
        finally stream.close()
      if data.length > 1048576 then
        throw new IllegalArgumentException("ledger fixtures exceed 1 MiB")
      val expected = "edce8eead85dd11d89b6fbc4d01b7168f489f163024f9bc9a45d218c02541d5b"
      val actual = java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(data)
        .map(b => f"${b & 0xff}%02x")
        .mkString
      if actual != expected then
        throw new IllegalArgumentException("ledger projection digest mismatch")
      new String(data, java.nio.charset.StandardCharsets.UTF_8)
    }
    .flatMap(text => IO.fromEither(parse(text).left.map(new IllegalArgumentException(_))))
    .flatMap { checks =>
      checks.traverse_(c =>
        IO.println(s"${if c.matched then "PASS" else "FAIL"}\t${c.name}\t${c.result}")
      ) *>
        IO.println(
          s"${checks.count(_.matched)}/4 Conway PV9 transfer/mint value-conservation predicates matched archived Haskell expectations. PredicateSatisfied is not transaction validity. Node 11.1.3 source reviewed; no fresh target ledger oracle or full transition executed."
        ).as(if checks.forall(_.matched) then ExitCode.Success else ExitCode.Error)
    }
    .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
