// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import lab.cbor.Bytes
import lab.vrf.StrictDraft03

/** Offline pinned corpus demonstration, never a live/native verifier or arbitrary-input CLI. */
object VrfCommand:
  val MaxBytes: Int = 8 * 1024 * 1024
  val pins: Map[String, String] = Map(
    "vectors.tsv" -> "8042ffe0df97f36fbed638e480eea7f549ae2911c00a0f21e392ab2b43d69e15",
    "expected.tsv" -> "4269a648f0ab6f7c39c0e3fce3dbb614e18dcedcfd5a2b3ae4f342bfcf6047fd"
  )
  final case class Check(id: String, expected: String, actual: String):
    def matched: Boolean = expected == actual

  def admit(name: String, data: Bytes): Either[String, String] =
    if data.size > MaxBytes then Left(s"$name exceeds 8 MiB")
    else
      val digest = Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(data.toArray)).hex
      Either.cond(
        pins.get(name).contains(digest),
        new String(data.toArray, StandardCharsets.UTF_8),
        s"$name immutable digest mismatch"
      )

  def check(data: Map[String, Bytes]): Either[String, Vector[Check]] =
    for
      texts <- pins.keys.toVector.sorted
        .traverse(name =>
          data.get(name).toRight(s"missing $name").flatMap(admit(name, _)).map(name -> _)
        )
        .map(_.toMap)
      rows = texts("vectors.tsv").linesIterator.toVector
      expected = texts("expected.tsv").linesIterator.toVector
      _ <- Either.cond(rows.size == 2048 && expected.size == 2048, (), "expected 2048 rows")
      checks <- rows.zip(expected).traverse { case (row, status) =>
        (row.split("\t", -1).toVector, status.split("\t", -1).toVector) match
          case (Vector(id, pk, pi, alpha), Vector(expectedId, value)) if id == expectedId =>
            def bytes(s: String): Either[String, Bytes] =
              if s == "NULL" then Right(null) else Bytes.fromHex(s)
            for
              key <- bytes(pk)
              proof <- bytes(pi)
              message <- bytes(alpha)
              actual <- StrictDraft03.verify(key, proof, message) match
                case StrictDraft03.Result.Verified(out)        => Right("VALID:" + out.hex)
                case StrictDraft03.Result.MalformedEnvelope(_) => Right("MALFORMED")
                case StrictDraft03.Result.Rejected(_)          => Right("INVALID")
                case StrictDraft03.Result.InternalFailure(kind, detail) =>
                  Left(s"internal failure: $kind: $detail")
            yield Check(id, value, actual)
          case _ => Left("invalid vector/status shape or identity")
      }
      _ <- Either.cond(checks.map(_.id).distinct.size == 2048, (), "duplicate identifiers")
    yield checks

  private def load(name: String): IO[(String, Bytes)] = IO.blocking {
    val stream = Files.newInputStream(Path.of("fixtures/vrf", name))
    val raw =
      try stream.readNBytes(MaxBytes + 1)
      finally stream.close()
    if raw.length > MaxBytes then throw new IllegalArgumentException(s"$name exceeds 8 MiB")
    name -> Bytes.fromArray(raw)
  }

  def run: IO[ExitCode] =
    pins.keys.toVector.sorted
      .traverse(load)
      .flatMap(inputs =>
        IO.fromEither(check(inputs.toMap).left.map(new IllegalArgumentException(_)))
      )
      .flatMap { checks =>
        checks
          .filterNot(_.matched)
          .traverse_(c =>
            IO.println(s"FAIL\t${c.id}\texpected=${c.expected}\tactual=${c.actual}")
          ) *>
          IO.println(
            s"${checks.count(_.matched)}/2048 archived draft03 public-input checks matched: 3 published positives, 2018 rejected, 27 malformed."
          ) *>
          IO.println(
            "Experimental JVM research only; finite rows include duplicates. No native runtime, authentic Cardano-header positives, accepted mixed-order proofs, protocol alpha/claimed-output integration, consensus validation or security audit."
          ).as(if checks.forall(_.matched) then ExitCode.Success else ExitCode.Error)
      }
      .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
