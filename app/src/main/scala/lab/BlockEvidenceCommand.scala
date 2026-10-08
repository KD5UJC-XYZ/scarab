// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, Resource}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes
import lab.cbor.Bytes
import lab.chain.CardanoBlockEvidence as E

/** Reads one original disk block and an explicitly supplied context file. Writes stdout only. No
  * acquisition-store changes, adopted state, context discovery, or implicit network nonce.
  */
object BlockEvidenceCommand:
  val MaxContextBytes = 4096
  private val usage =
    "block-evidence <disk-block.cbor> <supplied-context.tsv> | block-evidence --help"
  private val ContextSchema = "block-evidence-context-v1"

  private def hex(s: String): Either[String, Bytes] =
    if !s.matches("[0-9a-f]{64}") then Left("expected lowercase 32-byte hex value")
    else Bytes.fromHex(s)
  private def uint(s: String): Either[String, BigInt] =
    if !s.matches("0|[1-9][0-9]{0,19}") then Left("expected canonical unsigned decimal")
    else Right(BigInt(s))

  /** Exactly one LF-terminated ASCII TSV row, no comments, quoting, trailing columns or defaults.
    * schema, blockSHA256, headerHash, parentHash, slot, attribution, timingSourceSHA256,
    * slotsPerPeriod, maxEvolutions, nonceHash-or-dash, nonceSourceSHA256-or-dash.
    */
  def parseContext(raw: Bytes): Either[String, E.SuppliedContext] =
    if raw == null || raw.value == null || raw.size == 0 || raw.size > MaxContextBytes then
      Left("context byte bound")
    else if raw.value.exists(b => b < 0 || (b < 32 && b != 9 && b != 10)) then
      Left("context must be ASCII TSV")
    else
      val text = new String(raw.toArray, StandardCharsets.US_ASCII)
      if !text.endsWith("\n") || text.count(_ == '\n') != 1 then
        Left("context must contain exactly one LF-terminated row")
      else
        text.dropRight(1).split("\t", -1).toList match
          case schema :: block :: header :: parent :: slot :: label :: source :: spp :: max :: nonce :: nonceSource :: Nil
              if schema == ContextSchema =>
            for
              b <- hex(block)
              h <- hex(header)
              p <- hex(parent)
              s <- uint(slot)
              src <- hex(source)
              period <- uint(spp)
              lifetime <- uint(max)
              _ <- Either.cond(lifetime <= 64, (), "maximum KES evolutions exceeds profile bound")
              n <- (nonce, nonceSource) match
                case ("-", "-") => Right(None)
                case ("-", _) | (_, "-") =>
                  Left("nonce and nonce source must both be supplied or absent")
                case (hash, origin) =>
                  for
                    v <- hex(hash)
                    o <- hex(origin)
                    checked <- E.SuppliedNonce.checked(v, o).left.map(_.toString)
                  yield Some(checked)
              context <- E.SuppliedContext
                .checked(b, h, p, s, label, src, period, lifetime.toInt, n)
                .left
                .map(_.toString)
            yield context
          case _ => Left("expected block-evidence-context-v1 with exactly eleven TSV fields")

  private[lab] def readContext(path: Path): IO[Bytes] =
    IO.blocking {
      val attrs =
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      require(attrs.isRegularFile, "context must be a regular file")
      require(attrs.size <= MaxContextBytes, "context byte cap exceeded")
    } *> Resource
      .fromAutoCloseable(IO.blocking {
        Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
      })
      .use { channel =>
        IO.blocking {
          val buffer = ByteBuffer.allocate(MaxContextBytes + 1)
          var eof = false
          while buffer.hasRemaining && !eof do eof = channel.read(buffer) < 0
          require(buffer.position <= MaxContextBytes, "context byte cap exceeded")
          buffer.flip()
          val raw = new Array[Byte](buffer.remaining)
          buffer.get(raw)
          Bytes.fromArray(raw)
        }
      }

  def render(r: E.Receipt): String =
    val vrf = r.vrf match
      case E.VrfEvidence.NotCheckedMissingNonce => "not_checked_missing_nonce"
      case E.VrfEvidence.CertificateCheckedWithSuppliedNonce =>
        "certificate_checked_with_supplied_nonce"
    s"""{"schema":"block-partial-evidence-v1","coverage":"body_opcert_supplied_timing_sum6","profileId":"${r.profileId}","profileHash":"${r.profileHash.hex}","receiptId":"${r.receiptId.hex}","originalBlockSha256":"${r.originalBlockSha256.hex}","originalHeaderSha256":"${r.originalHeaderSha256.hex}","headerHash":"${r.originalHeaderHash.hex}","parentHash":"${r.originalParentHash.hex}","era":"${r.diskEra}","slot":"${r.slot}","blockNumber":"${r.blockNumber}","protocolMajor":${r.protocolMajor},"protocolMinor":${r.protocolMinor},"messageEvidence":"source_profile_shortest_definite_candidate","messageSha256":"${r.messageDigest.hex}","contextSha256":"${r.contextDigest.hex}","contextEncoding":"${r.contextEncoding.hex}","currentKesPeriod":"${r.currentKesPeriod}","relativeKesPeriod":${r.relativeKesPeriod},"bodyCommitmentMatched":true,"bodySize":${r.body.actualSize},"bodyHash":"${r.body.actualHash.hex}","opcertSignatureChecked":true,"sum6SignatureChecked":true,"vrf":"$vrf","referenceSerializerParity":false,"authorizedIssuer":false,"registeredVrfKeyBinding":false,"opcertCounterAdmissibility":false,"nonceDerivedFromState":false,"leaderEligibility":false,"protocolVersionAdmissibility":false,"ledgerApplied":false,"selectedChain":false,"sourceAuthenticated":false}"""

  def errorCode(failure: E.Failure): ExitCode = failure match
    case E.Failure.Rejected(_, _)                                  => ExitCode.Error
    case E.Failure.Unsupported(_, _)                               => ExitCode(3)
    case E.Failure.Malformed(_, _) | E.Failure.ResourceLimit(_, _) => ExitCode(2)
    case E.Failure.InternalFailure(_, _)                           => ExitCode(4)

  def run(args: List[String]): IO[ExitCode] =
    val action = args match
      case List("--help") => IO.println(usage).as(ExitCode.Success)
      case List(blockFile, contextFile)
          if !blockFile.startsWith("-") && !contextFile.startsWith("-") =>
        for
          raw <- IO.delay(Path.of(blockFile)).flatMap(BodyCommitmentCommand.read)
          supplied <- IO.delay(Path.of(contextFile)).flatMap(readContext)
          result <- parseContext(supplied) match
            case Left(reason) =>
              IO.consoleForIO.errorln(s"block-evidence context error: $reason").as(ExitCode(2))
            case Right(context) =>
              E.inspect(raw, context) match
                case Right(receipt) => IO.println(render(receipt)).as(ExitCode.Success)
                case Left(failure) =>
                  IO.consoleForIO
                    .errorln(s"block-evidence stopped: $failure")
                    .as(errorCode(failure))
        yield result
      case _ => IO.consoleForIO.errorln(usage).as(ExitCode(2))
    action.handleErrorWith(e =>
      IO.consoleForIO
        .errorln(s"block-evidence input error: ${e.getClass.getSimpleName}")
        .as(ExitCode(2))
    )
