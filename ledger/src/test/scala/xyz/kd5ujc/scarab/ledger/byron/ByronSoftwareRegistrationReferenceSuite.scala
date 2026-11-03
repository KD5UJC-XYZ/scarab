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
  * non-ASCII and control characters, several failures at once, the protocol update refused before
  * the software update (`Registration.hs:376-390`), and block update payloads with a vote
  * (`registerUpdate`). The node decodes each proposal and must reach the same verdict, the same
  * error and, for an accepted one, the same registered proposals, the proposal under its own id
  * when its software version changes and no new one when it does not.
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

  /** The update payload of a row as a block carries it: `[[proposal], votes]`, the votes being the
    * row's vote, if it has one.
    */
  private def payload(row: Row): UpdatePayload =
    val vote = row.json.hcursor.get[Option[String]]("vote").fold(e => fail(e.getMessage), identity)
    val raw = Bytes.fromArray(Array(0x82.toByte, 0x81.toByte)) ++ hex(row.proposal) ++
      vote.fold(Bytes.fromArray(Array(0x80.toByte)))(v =>
        Bytes.fromArray(Array(0x81.toByte)) ++ hex(v)
      )
    CborRead
      .decode(raw)
      .flatMap(UpdatePayload.decode)
      .fold(e => fail(s"${row.name}: ${e.toString}"), identity)

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
      case Some("InvalidProtocolVersion") =>
        val v = field[Vector[BigInt]](row.json.hcursor, "version")
        Left(
          UpdateError.InvalidProtocolVersion(
            ByronVersion.from(v(0), v(1), v(2)).fold(e => fail(e.message), identity),
            ByronVersion.zero
          )
        )
      // `TooLarge` built positionally as (maximum, size) (`Validation/Registration.hs:511-513`)
      case Some("ProposalTooLarge") =>
        Left(
          UpdateError.ProposalTooLarge(
            field[BigInt](row.json.hcursor, "max"),
            field[BigInt](row.json.hcursor, "actual")
          )
        )
      case Some(other) => fail(s"${row.name}: unexpected reference rule $other")

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
        "NullUpdateProposal",
        "InvalidProtocolVersion",
        "ProposalTooLarge"
      )
    )
    assert(rows.exists(_.accepted))
    rows.foreach(r => assertEquals(r.accepted, r.rule.isEmpty, r.name))
  }

  /** The software proposals or application versions a reference result lists under `key`: name,
    * number and system tags.
    */
  private def listed(row: Row, key: String): Set[(String, BigInt, Set[String])] =
    field[Vector[Json]](row.json.hcursor, key)
      .map(_.hcursor)
      .map(r =>
        (field[String](r, "name"), field[BigInt](r, "number"), field[Set[String]](r, "tags"))
      )
      .toSet

  /** The installer metadata the harness gives a proposal: each tag's `hashRaw` of its UTF-8 bytes.
    */
  private def metadata(row: Row): Map[String, Bytes] =
    row.tags.map { t =>
      t -> Blake2b.hash256.hash(Bytes.fromArray(t.getBytes(StandardCharsets.UTF_8)))
    }.toMap

  rows.foreach { row =>
    test(s"${row.name}: the reference's verdict") {
      val load = payload(row)
      val p = load.proposal.getOrElse(fail(s"${row.name}: no proposal"))
      assertEquals(p.softwareName, row.app)
      assertEquals(p.metadata.keySet, row.tags.toSet)
      val before = state(row)
      if load.votes.isEmpty then
        val result = ByronUpdate.registerProposal(env(p), before, p)
        assertEquals(result.map(_ => ()), expected(row))
        result.foreach { after =>
          assertEquals(
            after.registeredSoftware.values.map(s => (s.name, s.number, s.metadata.keySet)).toSet,
            listed(row, "registeredAfter")
          )
          // the software version changes when the application has another number (or none):
          // only then is the proposal registered, under its id, with its metadata
          val changed = before.appVersions.get(row.app).forall(_.number != row.number)
          assertEquals(
            listed(row, "registeredAfter").contains((row.app, row.number, row.tags.toSet)),
            changed
          )
          if changed then
            assertEquals(
              after.registeredSoftware.get(p.id),
              Some(SoftwareProposal(row.app, row.number, metadata(row)))
            )
          else assertEquals(after.registeredSoftware, before.registeredSoftware)
        }
      else
        // the payload of a block: the proposal, its vote, then the block's endorsement
        val result = ByronUpdate.register(
          env(p),
          before,
          load,
          ByronVersion.zero,
          ByronCbor.keyHash(p.issuer)
        )
        assertEquals(result.map(_ => ()), expected(row))
        result.foreach { after =>
          assertEquals(
            after.registeredSoftware.values.map(s => (s.name, s.number, s.metadata.keySet)).toSet,
            listed(row, "registeredAfter")
          )
          assertEquals(
            after.appVersions.map((n, a) => (n, a.number, a.metadata.keySet)).toSet,
            listed(row, "appVersionsAfter")
          )
          assertEquals(after.confirmed.size, field[Int](row.json.hcursor, "confirmed"))
          // the confirmed proposal's application version carries its metadata
          assertEquals(after.appVersions.get(row.app).map(_.metadata), Some(metadata(row)))
        }
    }
  }
