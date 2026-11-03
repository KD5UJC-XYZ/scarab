// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import io.circe.{ACursor, Decoder, HCursor, Json, parser}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.CborRead

/** A software update proposal is registered as `registerSoftwareUpdate` registers it (#748):
  * `checkSystemTag` of each system tag of its installer metadata in key order, no registered
  * proposal of the same application, `checkSoftwareVersion` of its application name, then the
  * version succession (`Cardano/Chain/Update/Validation/Registration.hs:553-585`, cardano-ledger
  * f649f975). A tag or a name is too long past 10 or 12 characters, counted in code points, and
  * then not ASCII.
  *
  * `fixtures/reference/byron-software-registration/cases.jsonl` holds each proposal's bytes, signed
  * by a throwaway key, with the verdict of the released `Registration.registerProposal` (oracle
  * `scripts/oracle/byron-update-state`, `register`): tags and names at and past each bound,
  * non-ASCII ones, and several failures at once. The node decodes each proposal and must reach the
  * same verdict, the same error and, for an accepted one, the same registered proposal.
  */
class ByronSoftwareRegistrationReferenceSuite extends FunSuite:
  private final case class Row(
      name: String,
      app: String,
      number: BigInt,
      tags: Vector[String],
      proposal: String,
      accepted: Boolean,
      rule: Option[String],
      text: Option[String],
      json: Json
  )

  private given Decoder[Row] = Decoder.instance { c =>
    for
      name <- c.get[String]("name")
      app <- c.get[String]("app")
      number <- c.get[BigInt]("number")
      tags <- c.get[Vector[String]]("tags")
      proposal <- c.get[String]("proposal")
      accepted <- c.get[Boolean]("accepted")
      rule <- c.get[Option[String]]("rule")
      text <- c.get[Option[String]]("text")
    yield Row(name, app, number, tags, proposal, accepted, rule, text, c.value)
  }

  private val rows: Vector[Row] =
    Files
      .readString(Path.of("fixtures/reference/byron-software-registration/cases.jsonl"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(l => parser.decode[Row](l).fold(e => fail(e.getMessage), identity))
      .toVector

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def field[A: Decoder](c: ACursor, name: String): A =
    c.downField(name).as[A].fold(e => fail(s"$name: ${e.getMessage}"), identity)

  private def params(c: ACursor): ByronParams =
    def n(name: String) = field[BigInt](c, name)
    val rule = field[Vector[BigInt]](c, "softforkRule")
    ByronParams(
      n("scriptVersion"),
      n("slotDuration"),
      n("maxBlockSize"),
      n("maxHeaderSize"),
      n("maxTxSize"),
      n("maxProposalSize"),
      n("mpcThd"),
      n("heavyDelThd"),
      n("updateVoteThd"),
      n("updateProposalThd"),
      n("updateProposalTtl"),
      SoftforkRule(rule(0), rule(1), rule(2)),
      TxFeePolicy(n("summand") * BigInt(1000000000), n("multiplier")),
      n("unlockStakeEpoch")
    )

  /** The proposal of a row, decoded as a block's update payload carries it. */
  private def proposal(row: Row): ByronProposal =
    val raw = hex(row.proposal)
    val payload = Bytes.fromArray(Array(0x82.toByte, 0x81.toByte)) ++ raw ++
      Bytes.fromArray(Array(0x80.toByte))
    CborRead
      .decode(payload)
      .flatMap(UpdatePayload.decode)
      .fold(e => fail(s"${row.name}: ${e.toString}"), _.proposal)
      .getOrElse(fail(s"${row.name}: no proposal"))

  /** The state of a row: its application versions and registered software proposals, the latter
    * under the ids the harness gave them (32 bytes of 1, 2, ...).
    */
  private def state(row: Row): UpdateState =
    val c: HCursor = row.json.hcursor
    val apps = field[Vector[Json]](c, "appVersions").map(_.hcursor).map { a =>
      field[String](a, "name") -> AppVersion(field[BigInt](a, "number"), 0, Map.empty)
    }
    val registered =
      field[Vector[Json]](c, "registered").map(_.hcursor).zipWithIndex.map { (a, i) =>
        Bytes(Vector.fill(32)((i + 1).toByte)) ->
          SoftwareProposal(field[String](a, "name"), field[BigInt](a, "number"), Map.empty)
      }
    ByronUpdate
      .initial(params(c.downField("adoptedParameters")))
      .copy(appVersions = apps.toMap, registeredSoftware = registered.toMap)

  private def env(p: ByronProposal) =
    ByronUpdate.Env(
      4242,
      ByronCbor.uint(4242),
      10,
      1,
      1,
      Map(Bytes(Vector.fill(28)(7)) -> ByronCbor.keyHash(p.issuer))
    )

  private def expected(row: Row): Either[UpdateError, Unit] =
    def text = row.text.getOrElse(fail(s"${row.name}: no text"))
    def name(e: ApplicationNameError) =
      UpdateError.SoftwareVersionError(SoftwareVersionError.ApplicationName(e))
    row.rule match
      case None => Right(())
      case Some("SystemTagTooLong") =>
        Left(UpdateError.SystemTagError(SystemTagError.TooLong(text)))
      case Some("SystemTagNotAscii") =>
        Left(UpdateError.SystemTagError(SystemTagError.NotAscii(text)))
      case Some("ApplicationNameTooLong")   => Left(name(ApplicationNameError.TooLong(text)))
      case Some("ApplicationNameNotAscii")  => Left(name(ApplicationNameError.NotAscii(text)))
      case Some("DuplicateSoftwareVersion") => Left(UpdateError.DuplicateSoftwareVersion(row.app))
      case Some("InvalidSoftwareVersion") =>
        Left(UpdateError.InvalidSoftwareVersion(row.app, row.number))
      case Some("NullUpdateProposal") => Left(UpdateError.NullUpdateProposal)
      case Some(other)                => fail(s"${row.name}: unexpected reference rule $other")

  test("the corpus covers each check, its bounds and their order") {
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    val rules = rows.flatMap(_.rule).toSet
    assertEquals(
      rules,
      Set(
        "SystemTagTooLong",
        "SystemTagNotAscii",
        "ApplicationNameTooLong",
        "ApplicationNameNotAscii",
        "DuplicateSoftwareVersion",
        "InvalidSoftwareVersion",
        "NullUpdateProposal"
      )
    )
    assert(rows.exists(_.accepted))
    rows.foreach(r => assertEquals(r.accepted, r.rule.isEmpty, r.name))
  }

  rows.foreach { row =>
    test(s"${row.name}: the reference's verdict") {
      val p = proposal(row)
      assertEquals(p.softwareName, row.app)
      assertEquals(p.metadata.keySet, row.tags.toSet)
      val result = ByronUpdate.registerProposal(env(p), state(row), p)
      assertEquals(result.map(_ => ()), expected(row))
      result.foreach { after =>
        // the registered software proposals after it, as the reference lists them
        val reference = field[Vector[Json]](row.json.hcursor, "registeredAfter")
          .map(_.hcursor)
          .map(r =>
            (field[String](r, "name"), field[BigInt](r, "number"), field[Set[String]](r, "tags"))
          )
          .toSet
        assertEquals(
          after.registeredSoftware.values.map(s => (s.name, s.number, s.metadata.keySet)).toSet,
          reference
        )
        // the harness's installer hash of a tag is `hashRaw` of the tag's UTF-8 bytes
        val metadata = row.tags.map { t =>
          t -> Blake2b.hash256.hash(Bytes.fromArray(t.getBytes(StandardCharsets.UTF_8)))
        }.toMap
        after.registeredSoftware
          .get(p.id)
          .foreach(s => assertEquals(s, SoftwareProposal(row.app, row.number, metadata)))
      }
    }
  }
