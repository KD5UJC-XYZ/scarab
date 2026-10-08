// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, Resource}
import cats.syntax.all.*
import java.nio.ByteBuffer
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.file.attribute.BasicFileAttributes
import lab.cbor.Bytes
import lab.chain.CardanoBodyCommitment as B

/** One bounded, read-only raw disk-block file. No URLs, peers, store writes or admission policy. */
object BodyCommitmentCommand:
  val MaxBytes: Int = 1048576
  private val usage = "body-commitment <disk-block.cbor> | body-commitment --help"

  def render(o: B.Observation): String =
    val components = o.components
      .map { c =>
        s"""{"kind":"${c.kind.label}","offset":${c.offset},"length":${c.length},"hash":"${c.hash.hex}"}"""
      }
      .mkString(",")
    s"""{"schema":"body-commitment-v1","era":"${o.era.label}","rawSha256":"${o.rawSha256.hex}","headerHash":"${o.headerHash.hex}","declaredSize":${o.declaredSize},"actualSize":${o.actualSize},"declaredHash":"${o.declaredHash.hex}","actualHash":"${o.actualHash.hex}","components":[$components],"structurallyIndexed":true,"bodySizeMatched":${o.bodySizeMatched},"bodyHashMatched":${o.bodyHashMatched},"bodyCommitmentMatched":${o.bodyCommitmentMatched},"headerCryptography":"not_checked","consensus":"not_checked","ledger":"not_checked","sourceAuthentication":"not_checked"}"""

  private[lab] def read(path: Path): IO[Bytes] =
    IO.blocking {
      val attrs =
        Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
      require(attrs.isRegularFile, "input must be a regular file, not a link or special file")
      require(attrs.size <= MaxBytes, "input exceeds 1048576 bytes")
    } *> Resource
      .fromAutoCloseable(IO.blocking {
        Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
      })
      .use { channel =>
        IO.blocking {
          val buffer = ByteBuffer.allocate(MaxBytes + 1)
          var eof = false
          while buffer.hasRemaining && !eof do eof = channel.read(buffer) < 0
          require(buffer.position <= MaxBytes, "input exceeds 1048576 bytes")
          buffer.flip()
          val bytes = new Array[Byte](buffer.remaining)
          buffer.get(bytes)
          Bytes.fromArray(bytes)
        }
      }

  def run(args: List[String]): IO[ExitCode] =
    val action = args match
      case List("--help") => IO.println(usage).as(ExitCode.Success)
      case List(file) if !file.startsWith("-") =>
        IO.delay(Path.of(file)).flatMap(read).flatMap { raw =>
          B.inspect(raw) match
            case Right(observation) =>
              IO.println(render(observation))
                .as(
                  if observation.bodyCommitmentMatched then ExitCode.Success else ExitCode.Error
                )
            case Left(error) =>
              IO.consoleForIO.errorln(s"body-commitment error: $error").as(ExitCode(2))
        }
      case _ => IO.consoleForIO.errorln(usage).as(ExitCode(2))
    action.handleErrorWith(e =>
      IO.consoleForIO
        .errorln(s"body-commitment input error: ${e.getClass.getSimpleName}")
        .as(ExitCode(2))
    )
