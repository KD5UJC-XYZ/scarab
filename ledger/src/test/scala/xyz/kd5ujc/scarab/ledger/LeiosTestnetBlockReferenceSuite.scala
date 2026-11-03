// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes

/** The Leios reference is the build the public Leios testnet runs (cardano-node b004fe0c, release
  * rc-2026w44: cardano-ledger 9df9f98c, ouroboros-consensus 2416493b; decided 2026-11-03 on #711).
  * Its Dijkstra block, its Leios header and every list in them are read by `decodeRecordNamed`,
  * definite or indefinite, and none as a detached `RecD` record; the KES signature of a Leios
  * header is over its body's original bytes.
  *
  * `fixtures/reference/leios-testnet-blocks/cases.jsonl` holds the testnet's blocks as BlockFetch
  * carries them, those blocks with each list in another form, blocks of synthetic transactions
  * whose output value is in each form, and Leios headers alone, with the verdict of that build's
  * decoders and what they read from each accepted case. Scarab must give the same verdict and read
  * the same: hashes, sizes, header fields, transaction ids and validity flags, certificates.
  */
class LeiosTestnetBlockReferenceSuite extends FunSuite:
  import LeiosTestnetBlockReferenceSuite.*

  private val rows: Vector[Row] = load

  /** What Scarab read of a block: a Dijkstra block by [[LeiosBlock.decode]], and a block of another
    * tag, which that refuses, by [[Block.decode]].
    */
  private def block(raw: Bytes): Either[ChainError, Read] =
    LeiosBlock.decode(raw) match
      case Right(b) => Right(Read.Dijkstra(b))
      case Left(ChainError.Decode(_, LedgerDecodeError.UnknownTag(DecodeField.LeiosBlockTag, t)))
          if t != 8 =>
        Block.decode(raw, BigInt(21600)).flatMap {
          case b: Block.Shelley => Right(Read.Other(b))
          case _                => Left(Header.fail(LedgerDecodeError.ByronLayout))
        }
      case Left(e) => Left(e)

  private def expect(row: Row, field: String): Json =
    row.read.getOrElse(field, fail(s"${row.name}: the reference recorded no $field"))

  private def same(row: Row, field: String, actual: Json): Unit =
    assertEquals(actual, expect(row, field), s"${row.name}: $field")

  private def header(row: Row, h: LeiosHeader): Unit =
    same(row, "hash", Json.fromString(h.hash.hex))
    same(row, "headersize", Json.fromInt(h.raw.size))
    same(row, "signable", Json.fromString(h.signable.hex))
    same(row, "blockno", Json.fromBigInt(h.blockNo))
    same(row, "slot", Json.fromBigInt(h.slot))
    same(row, "declaredbodysize", Json.fromBigInt(h.bodySize))
    same(row, "declaredbodyhash", Json.fromString(h.bodyHash.hex))
    same(
      row,
      "versioninfo",
      Json.arr(
        Json.fromBigInt(h.leios.versionInfo.highestSupportedMajor),
        Json.fromBigInt(h.leios.versionInfo.softwareTag)
      )
    )
    same(row, "containscert", Json.fromBoolean(h.leios.blockBodyContainsLeiosCert))
    same(
      row,
      "announcement",
      h.leios.announcement.fold(Json.Null)(a =>
        Json.obj("hash" -> Json.fromString(a.hash.bytes.hex), "size" -> Json.fromLong(a.size))
      )
    )

  for row <- rows do
    test(row.name) {
      row.kind match
        case Kind.Header =>
          LeiosHeader.decode(row.raw) match
            case Right(h) =>
              assert(row.accepted, s"the reference refuses this encoding: ${row.failure}")
              header(row, h)
            case Left(e) => assert(!row.accepted, s"the reference accepts this encoding: $e")
        case Kind.Block =>
          block(row.raw) match
            case Right(Read.Dijkstra(b)) =>
              assert(row.accepted, s"the reference refuses this encoding: ${row.failure}")
              same(row, "tag", Json.fromInt(8))
              header(row, b.header)
              same(row, "bodyhash", Json.fromString(b.bodyHash.hex))
              same(row, "bodysize", Json.fromLong(b.bodySize))
              same(
                row,
                "txs",
                Json.arr(
                  b.transactions.map(t =>
                    Json.obj(
                      "id" -> Json.fromString(t.id.hex),
                      "valid" -> Json.fromBoolean(t.isValid)
                    )
                  )*
                )
              )
              same(row, "leioscert", Json.fromBoolean(b.leiosCertificate.isDefined))
              same(row, "perascert", Json.fromBoolean(b.perasCertificate.isDefined))
            case Right(Read.Other(b)) =>
              assert(row.accepted, s"the reference refuses this encoding: ${row.failure}")
              same(row, "tag", Json.fromBigInt(b.era.blockTag))
              same(row, "hash", Json.fromString(b.header.hash.hex))
              same(row, "bodyhash", Json.fromString(b.bodyHash.hex))
            case Left(e) => assert(!row.accepted, s"the reference accepts this encoding: $e")
    }

  test("the corpus covers the testnet's blocks, each list's forms and the header, both verdicts") {
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    for kind <- Kind.values do
      assert(rows.exists(r => r.kind == kind && r.accepted), s"no accepted $kind case")
      assert(rows.exists(r => r.kind == kind && !r.accepted), s"no refused $kind case")
    // a detached list is refused wherever it stands, and an indefinite one accepted
    assert(rows.exists(r => r.name.contains("detached")))
    rows.filter(_.name.endsWith(" detached")).foreach(r => assert(!r.accepted, r.name))
    // blocks of the current network are among them, and every one of its blocks is read
    val current = rows.filter(_.source.startsWith("fixtures/leios/testnet/blocks-rc-2026w44.tsv"))
    assert(current.exists(r => r.kind == Kind.Block && r.accepted))
  }

  test("an accepted header's signed bytes are its body's original bytes, canonical or not") {
    val accepted = rows.filter(_.accepted).flatMap(r => r.read.get("signable").flatMap(_.asString))
    assert(accepted.nonEmpty)
    rows.filter(_.accepted).foreach { r =>
      r.read.get("signable").flatMap(_.asString).foreach(s => assert(r.raw.hex.contains(s), r.name))
    }
    // with non-minimal heads the original bytes are not the canonical encoding: the list head of
    // twelve items is written in two bytes, and the reference signs those bytes
    val nonMinimal = rows.find(_.name == "header non-minimal heads").getOrElse(fail("no such row"))
    assert(nonMinimal.accepted)
    val decoded = LeiosHeader.decode(nonMinimal.raw).getOrElse(fail("decode"))
    assert(decoded.signable.hex.startsWith("980c"), decoded.signable.hex.take(8))
  }

object LeiosTestnetBlockReferenceSuite:
  enum Kind(val wire: String):
    case Block extends Kind("block")
    case Header extends Kind("header")

  /** What Scarab read: a Dijkstra block, or a block of another tag. */
  enum Read:
    case Dijkstra(block: LeiosBlock)
    case Other(block: xyz.kd5ujc.scarab.ledger.Block.Shelley)

  /** A line of the corpus: its verdict and, for an accepted case, what the reference read. */
  final case class Row(
      name: String,
      kind: Kind,
      source: String,
      raw: Bytes,
      accepted: Boolean,
      failure: Option[String],
      read: Map[String, Json]
  )

  private val meta = Set("id", "name", "kind", "source", "accepted", "failure", "bytes")

  private given Decoder[Row] = Decoder.instance { c =>
    for
      name <- c.get[String]("name")
      kind <- c
        .get[String]("kind")
        .flatMap(k =>
          Kind.values
            .find(_.wire == k)
            .toRight(io.circe.DecodingFailure(s"unknown kind $k", c.history))
        )
      source <- c.get[String]("source")
      raw <- c
        .get[String]("bytes")
        .flatMap(h =>
          Bytes.fromHex(h).leftMap(e => io.circe.DecodingFailure(e.toString, c.history))
        )
      accepted <- c.get[Boolean]("accepted")
      failure <- c.get[Option[String]]("failure")
      all <- c.as[Map[String, Json]]
    yield Row(name, kind, source, raw, accepted, failure, all.filterNot((k, _) => meta(k)))
  }

  private def load: Vector[Row] =
    val path = Path.of("fixtures/reference/leios-testnet-blocks/cases.jsonl")
    Files
      .readAllLines(path)
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(line => parser.decode[Row](line).fold(e => sys.error(s"$path: $e"), identity))
end LeiosTestnetBlockReferenceSuite
