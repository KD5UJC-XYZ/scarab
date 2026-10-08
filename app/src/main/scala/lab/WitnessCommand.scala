// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import lab.cbor.{Bytes, Cbor, Node, Value}
import lab.witness.*

/** Offline, immutable-pin admitted research corpus. No runtime native oracle. */
object WitnessCommand:
  private val maxBytes = 8 * 1024 * 1024
  val pins: Map[String, String] = Map(
    "vectors.tsv" -> "f79f0a87efbd99ebe159c18211497f6b0d282e7946f1839216dc31bbf99ef918",
    "status.tsv" -> "f5b22e990b28f1bff90ef2304f3a1d19e1ba40d85e984bcf08a869cffbf44934",
    "ledger-witnesses.tsv" -> "eb8a8ae0c0731ef24e03789dcc26c314cdada4298a6555aa69f484ff544c2d3f"
  )
  final case class Check(name: String, expected: String, actual: String):
    def matched: Boolean = expected == actual
  final case class Report(checks: Vector[Check], ledgerChecks: Vector[Check]):
    def matched: Boolean = checks.forall(_.matched) && ledgerChecks.forall(_.matched)

  private def hex(value: String): Either[String, Bytes] = Bytes.fromHex(value)
  private def lines(text: String): Vector[String] = text.linesIterator.toVector

  def admit(name: String, data: Bytes): Either[String, String] =
    if data.size > maxBytes then Left(s"$name exceeds 8 MiB")
    else
      val actual = Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(data.toArray)).hex
      pins.get(name) match
        case Some(expected) if actual == expected =>
          Right(new String(data.toArray, StandardCharsets.UTF_8))
        case _ => Left(s"$name immutable projection digest mismatch")

  /** Supplied data still passes immutable admission before any fixture parsing. */
  def check(data: Map[String, Bytes]): Either[String, Report] =
    for
      text <- pins.keys.toVector.sorted
        .traverse { name =>
          data.get(name).toRight(s"missing $name").flatMap(admit(name, _)).map(name -> _)
        }
        .map(_.toMap)
      checks <- parseVectors(text("vectors.tsv"), text("status.tsv"))
      ledger <- parseLedger(text("ledger-witnesses.tsv"))
    yield Report(checks, ledger)

  private def outcome(
      result: Either[WitnessInputError | VerificationError, VerificationResult]
  ): Either[String, String] =
    result match
      case Right(value)                                        => Right(value.toString)
      case Left(WitnessInputError.MalformedSignatureLength(_)) => Right("MalformedSignatureLength")
      case Left(error: WitnessInputError) => Left(s"unexpected malformed witness input: $error")
      case Left(error: VerificationError) => Left(s"internal verification failure: $error")

  private def parseVectors(vectors: String, status: String): Either[String, Vector[Check]] =
    val rows = lines(vectors)
    val statuses = lines(status)
    for
      _ <- Either.cond(
        rows.size == 2219 && statuses.size == 2220,
        (),
        "expected 2219 vectors and statuses"
      )
      _ <- Either.cond(
        statuses.head == "id\tsource_expected\texpected_kind\texpected_result\tsystem_sodium\tbc_raw\tstrict_archived",
        (),
        "invalid status header"
      )
      checks <- rows.zip(statuses.tail).traverse { case (line, expectedLine) =>
        (line.split("\t", -1).toVector, expectedLine.split("\t", -1).toVector) match
          case (Vector(id, pk, sig, msg), Vector(expectedId, _, _, expected, _, _, _))
              if id == expectedId =>
            for
              _ <- Either.cond(
                Set("SignatureVerified", "SignatureRejected", "MalformedSignatureLength")
                  .contains(expected),
                (),
                "invalid expected status"
              )
              key <- hex(pk)
              signature <- hex(sig)
              message <- hex(msg)
              actual <- outcome(StrictEd25519.verify(key, signature, message))
            yield Check(id, expected, actual)
          case _ => Left("vector/status shape or identity mismatch")
      }
      _ <- Either.cond(checks.map(_.name).distinct.size == 2219, (), "duplicate vector identifiers")
    yield checks

  /** Only called after all immutable fixture pins pass. Some source transactions also contain
    * native scripts: project their vkey bytes without claiming those envelopes are supported by the
    * public vkey-only decoder, or that ignored scripts/other fields were validated.
    */
  private def projectPinnedLedgerEnvelope(tx: Bytes): Either[String, WitnessEnvelope] =
    Cbor.decode(tx).flatMap { node =>
      node.value match
        case Value.Arr(Vector(body, witnessMap, _, _)) =>
          for
            exactBody <- ExactBodyCbor.create(body.original).left.map(_.toString)
            vkeySet <- witnessMap.value match
              case Value.Map(entries) =>
                entries.collect { case (Node(Value.UInt(n), _), value) if n == 0 => value } match
                  case Vector(value) => Right(value)
                  case _             => Left("pinned ledger fixture requires one vkey field")
              case _ => Left("pinned witness map is malformed")
            items <- vkeySet.value match
              case Value.Arr(xs)                                    => Right(xs)
              case Value.Tag(n, Node(Value.Arr(xs), _)) if n == 258 => Right(xs)
              case _ => Left("pinned vkey set is malformed")
            witnesses <- items.traverse { item =>
              item.value match
                case Value.Arr(
                      Vector(Node(Value.ByteString(pk), _), Node(Value.ByteString(sig), _))
                    ) =>
                  for
                    key <- PublicKey32.create(pk).left.map(_.toString)
                    signature <- Signature64.create(sig).left.map(_.toString)
                  yield VKeyWitness(key, signature)
                case _ => Left("pinned vkey witness is malformed")
            }
          yield WitnessEnvelope(exactBody, witnesses)
        case _ => Left("pinned ledger transaction is malformed")
    }

  private def parseLedger(text: String): Either[String, Vector[Check]] =
    val rows = lines(text)
    for
      _ <- Either.cond(rows.size == 8, (), "expected eight ledger witness rows")
      checks <- rows.traverse { line =>
        line.split("\t", -1).toVector match
          case Vector(id, transaction, index, body, hash) =>
            for
              tx <- hex(transaction)
              expectedBody <- hex(body)
              expectedHash <- hex(hash)
              witnessIndex <- index.toIntOption.toRight("invalid witness index")
              envelope <- projectPinnedLedgerEnvelope(tx)
              _ <- Either.cond(
                envelope.body.bytes == expectedBody && envelope.body.hash.bytes == expectedHash,
                (),
                s"$id original body/hash mismatch"
              )
              witness <- envelope.witnesses.lift(witnessIndex).toRight("witness index out of range")
              actual <- outcome(CardanoWitness.verifyVKeyWitness(envelope.body, witness))
            yield Check(id, "SignatureVerified", actual)
          case _ => Left("invalid ledger witness projection")
      }
      _ <- Either.cond(
        checks.map(_.name).distinct.size == 8,
        (),
        "duplicate ledger witness identifiers"
      )
    yield checks

  private def load(name: String): IO[(String, Bytes)] = IO.blocking {
    val stream = Files.newInputStream(Path.of("fixtures/witness", name))
    val data =
      try stream.readNBytes(maxBytes + 1)
      finally stream.close()
    if data.length > maxBytes then throw new IllegalArgumentException(s"$name exceeds 8 MiB")
    name -> Bytes.fromArray(data)
  }

  def run: IO[ExitCode] =
    pins.keys.toVector.sorted
      .traverse(load)
      .flatMap { inputs =>
        IO.fromEither(check(inputs.toMap).left.map(new IllegalArgumentException(_)))
      }
      .flatMap { report =>
        val mismatches = (report.checks ++ report.ledgerChecks).filterNot(_.matched)
        mismatches.traverse_(c =>
          IO.println(s"FAIL\t${c.name}\texpected=${c.expected}\tactual=${c.actual}")
        ) *>
          IO.println(
            s"${report.checks.count(_.matched)}/2219 pinned public-input vector checks matched (2207 fixed-size, 12 malformed signature lengths)."
          ) *>
          IO.println(
            s"${report.ledgerChecks.count(_.matched)}/8 genuine ledger witnesses verified against original-byte BLAKE2b-256 body hashes."
          ) *>
          IO.println(
            "Experimental strict JVM candidate only. Regression expectations: reject BC cofactored differences 2/4/5; accept mixed-order case 3. System libsodium 1.0.18 archival differential is not exact Cardano-fork binary conformance. SignatureVerified is not transaction validity or a security audit."
          ).as(if report.matched then ExitCode.Success else ExitCode.Error)
      }
      .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
