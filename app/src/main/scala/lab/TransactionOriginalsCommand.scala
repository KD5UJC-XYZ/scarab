// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import cats.syntax.all.*
import java.nio.file.{Files, Path, LinkOption, StandardOpenOption as Open}
import lab.submission.SignedTransaction

/** Offline original span digests only. This command cannot submit or sign a transaction. */
object TransactionOriginalsCommand:
  import PlutusResearchIO.{get, read, sha, record, text, num, encode}
  private[lab] def work(args: List[String]): IO[Unit] = args match
    case List(input, output) =>
      IO.blocking {
        val source = Path.of(input)
        require(
          Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS),
          "regular input file required"
        )
        val raw = read(source, SignedTransaction.MaxBytes)
        val tx = get(SignedTransaction.checked(raw))
        val value = record(
          "transactionId" -> text(tx.transactionId.hex),
          "envelopeSHA256" -> text(tx.envelopeSHA256.hex),
          "bodySHA256" -> text(sha(tx.originalBody).hex),
          "witnessesSHA256" -> text(sha(tx.originalWitnesses).hex),
          "bytes" -> num(raw.size)
        )
        val target = Path.of(output).toAbsolutePath
        val temporary = Files.createTempFile(target.getParent, ".transaction-originals-", ".part")
        try
          Files.write(temporary, encode(value).toArray, Open.WRITE, Open.TRUNCATE_EXISTING)
          Files.createLink(target, temporary)
          ()
        finally Files.deleteIfExists(temporary)
      }
    case _ =>
      IO.raiseError(new IllegalArgumentException("transaction-originals INPUT_CBOR OUTPUT_JSON"))
  def run(args: List[String]): IO[ExitCode] = IO
    .defer(work(args))
    .as(ExitCode.Success)
    .handleErrorWith(e =>
      IO.println(
        "TRANSACTION_ORIGINALS_FAILED: " + Option(e.getMessage)
          .getOrElse(e.getClass.getName)
          .take(512)
      ).as(ExitCode(2))
    )
