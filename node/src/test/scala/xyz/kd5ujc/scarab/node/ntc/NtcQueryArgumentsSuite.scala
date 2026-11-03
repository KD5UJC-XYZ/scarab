// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.{Decoder, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Credential, DRep}

/** The arguments of the node-to-client ledger queries, read as the reference's query decoder reads
  * them (#709): `fromCBOR` of cardano-binary for some (a set is tag 258 around a definite list in
  * strictly ascending order, a `Maybe` and an `Either` definite lists), the ledger's decoder at
  * Conway's version 9 for the others (a set definite or indefinite, tagged or not, without two
  * elements alike) (`decodeShelleyQuery`, `Shelley/Ledger/Query.hs:916-995`, ouroboros-consensus
  * 82ecba32).
  *
  * `fixtures/reference/ntc-query-arguments/cases.jsonl` holds each argument's bytes with the
  * reference's verdict and the values it read. Each is put in its query, in Conway's
  * `QueryIfCurrent`, and the node must read the same values or refuse it.
  */
class NtcQueryArgumentsSuite extends FunSuite:
  private final case class Row(
      name: String,
      decoder: String,
      accepted: Boolean,
      values: Option[Vector[String]],
      nothing: Option[Boolean],
      bytes: String
  )

  private given Decoder[Row] =
    Decoder.forProduct6("name", "decoder", "accepted", "values", "nothing", "bytes")(Row.apply)

  private val rows: Vector[Row] =
    Files
      .readString(Path.of("fixtures/reference/ntc-query-arguments/cases.jsonl"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(l => parser.decode[Row](l).fold(e => fail(e.getMessage), identity))
      .toVector

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)
  private val emptySet = "d9010280"

  /** `[0, [0, [era, q]]]`: `q` of `era` (Conway unless given), in the hard-fork combinator's
    * `QueryIfCurrent`.
    */
  private def query(tag: Int, args: String*): Bytes = queryIn(6, tag, args*)

  private def queryIn(era: Int, tag: Int, args: String*): Bytes =
    val head = if tag < 24 then f"$tag%02x" else f"18$tag%02x"
    hex(f"8200820082$era%02x" + f"${0x80 | (1 + args.size)}%02x" + head + args.mkString)

  private def credential(c: Credential): String = c match
    case Credential.Key(h)    => s"key:${h.hex}"
    case Credential.Script(h) => s"script:${h.hex}"

  private def drep(d: DRep): String = d match
    case DRep.Registered(c)      => credential(c)
    case DRep.AlwaysAbstain      => "abstain"
    case DRep.AlwaysNoConfidence => "noconfidence"

  private val statuses = Vector("Active", "Expired", "Unrecognized")

  /** The query a row's argument is read in. */
  private def queryOf(row: Row): Bytes = row.decoder match
    case "plain-pools"       => query(17, row.bytes)
    case "plain-maybe-pools" => query(19, row.bytes)
    case "plain-staking"     => query(22, row.bytes)
    case "plain-dreps"       => query(25, row.bytes)
    case "plain-cold"        => query(27, row.bytes, emptySet, "80")
    case "plain-hot"         => query(27, emptySet, row.bytes, "80")
    case "plain-nonmyopic"   => query(2, row.bytes)
    case "era-staking"       => query(10, row.bytes)
    case "era-txins"         => query(15, row.bytes)
    case "era-dreps"         => query(26, row.bytes)
    case "era-pools"         => query(30, row.bytes)
    case "era-statuses"      => query(27, emptySet, emptySet, row.bytes)
    case "era-pool"          => query(35, row.bytes)
    case "era-addresses"     => query(6, row.bytes)
    case "babbage-txins"     => queryIn(5, 15, row.bytes)
    case "babbage-staking"   => queryIn(5, 10, row.bytes)
    case other               => fail(s"unknown decoder $other")

  /** The values the node read for a row's argument, as the corpus writes them; `None` for a `Maybe`
    * that is nothing.
    */
  private def valuesOf(row: Row, q: LedgerQuery): Option[Vector[String]] =
    import LedgerQuery.*
    (row.decoder, q) match
      case ("plain-pools", GetStakePoolParams(s))            => Some(s.toVector.map(_.hex))
      case ("plain-maybe-pools", GetPoolState(s))            => s.map(_.toVector.map(_.hex))
      case ("plain-staking", GetStakeDelegDeposits(s))       => Some(s.toVector.map(credential))
      case ("plain-dreps", GetDRepState(s))                  => Some(s.toVector.map(credential))
      case ("plain-cold", GetCommitteeMembersState(c, _, _)) => Some(c.toVector.map(credential))
      case ("plain-hot", GetCommitteeMembersState(_, h, _))  => Some(h.toVector.map(credential))
      case ("plain-nonmyopic", GetNonMyopicMemberRewards(xs)) =>
        Some(xs.map(_.fold(c => s"left:$c", c => s"right:${credential(c)}")))
      case ("era-staking", GetFilteredDelegationsAndRewardAccounts(s)) =>
        Some(s.toVector.map(credential))
      case ("era-txins", GetUTxOByTxIn(s))                     => Some(s.toVector.map(_.render))
      case ("era-dreps", GetDRepStakeDistr(s))                 => Some(s.toVector.map(drep))
      case ("era-pools", GetSPOStakeDistr(s))                  => Some(s.toVector.map(_.hex))
      case ("era-statuses", GetCommitteeMembersState(_, _, s)) => Some(s.toVector.map(statuses))
      case ("era-pool", QueryStakePoolDefaultVote(p))          => Some(Vector(p.hex))
      case ("era-addresses", GetUTxOByAddress(s))              => Some(s.toVector.map(_.hex))
      case ("babbage-txins", GetUTxOByTxIn(s))                 => Some(s.toVector.map(_.render))
      case ("babbage-staking", GetFilteredDelegationsAndRewardAccounts(s)) =>
        Some(s.toVector.map(credential))
      case _ => fail(s"${row.name}: read as $q")

  private def read(row: Row): Either[NtcQuery.Error, Option[Vector[String]]] =
    NtcQuery.decode(queryOf(row)).map {
      case NtcQuery.IfCurrent(_, q) => valuesOf(row, q).map(_.sorted)
      case other                    => fail(s"${row.name}: read as $other")
    }

  test("the corpus has each decoder, accepted and refused") {
    assert(rows.map(_.decoder).distinct.size >= 13)
    assert(rows.exists(_.accepted) && rows.exists(!_.accepted))
  }

  for row <- rows do
    test(row.name) {
      val got = read(row)
      if row.accepted then
        val expected =
          if row.nothing.contains(true) then None else Some(row.values.getOrElse(Vector.empty))
        assertEquals(got, Right(expected))
      else assert(got.isLeft, s"the reference refuses it: $got")
    }

  test("a query's envelopes are definite lists: an indefinite one is refused at each level") {
    // GetEpochNo [1] at each level, each written indefinite in turn
    Vector(
      "9f0082008206" + "8101" + "ff",
      "82009f008206" + "8101" + "ff",
      "820082009f06" + "8101" + "ff",
      "820082008206" + "9f01ff",
      // GetCBOR's own list
      "820082008206" + "9f09" + "8101" + "ff"
    ).foreach { raw =>
      assert(
        NtcQuery.decode(hex(raw)).left.exists(_.isInstanceOf[NtcQuery.Error.IndefiniteList]),
        raw
      )
    }
    assertEquals(
      NtcQuery.decode(hex("8200820082068101")),
      Right(NtcQuery.IfCurrent(6, LedgerQuery.GetEpochNo))
    )
  }

  test("a query Conway's governance guards is refused in an earlier era (#724)") {
    // GetConstitution [23]: Shelley's and Babbage's are refused, Conway's read
    assertEquals(NtcQuery.decode(queryIn(1, 23)), Left(NtcQuery.Error.ConwayOnly(23)))
    assertEquals(NtcQuery.decode(queryIn(5, 23)), Left(NtcQuery.Error.ConwayOnly(23)))
    assertEquals(
      NtcQuery.decode(queryIn(6, 23)),
      Right(NtcQuery.IfCurrent(6, LedgerQuery.GetConstitution))
    )
    // GetGovState [24] is not guarded
    assert(NtcQuery.decode(queryIn(5, 24)).isRight)
  }
