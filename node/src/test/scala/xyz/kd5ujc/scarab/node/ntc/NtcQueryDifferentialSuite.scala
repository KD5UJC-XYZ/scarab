// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import scala.annotation.tailrec
import scala.util.Random
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{DetachedRecords, DetachedRecordsReferenceSuite}
import xyz.kd5ujc.scarab.ledger.DetachedRecordsReferenceSuite.Kind
import xyz.kd5ujc.scarab.protocol.{
  BaseDecoders,
  ChainSync,
  LocalStateQuery,
  LocalStateQueryEngine,
  Paced
}
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Role}

/** `MsgQuery` read by the node's reader of queries against the codec and the reader of the base of
  * the change that made them resume (`BaseDecoders.localStateQuery`, [[BaseNtcQueryPrefix]], and
  * the walk of `DetachedRecords.prefix` from the input's start): on the queries of the
  * detached-record corpus, the prefixes the judge refuses, long queries and copies with bytes
  * flipped, every prefix gets the base's verdict, the new codec started afresh on it and resumed
  * over the cuts alike.
  */
class NtcQueryDifferentialSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  /** `DetachedRecords.mayHold` as it was at the base. */
  private def mayHold(raw: Bytes): Boolean =
    @tailrec def from(i: Int): Boolean =
      if i + 1 >= raw.size then false
      else if raw.byteOr(i)(0)(_ & 0xff) == 0x9f && raw.byteOr(i + 1)(0)(_ & 0xff) == 0xff then true
      else from(i + 1)
    from(0)

  /** `NtcQuery.readerFor(table).span` as it was at the base. */
  private def span(input: Bytes): LocalStateQuery.QuerySpan =
    if !mayHold(input) then LocalStateQuery.QuerySpan.Item
    else
      DetachedRecords.prefix(input, NtcQuery.shape) match
        case DetachedRecords.Prefix.Item(end, records) if !records.isEmpty =>
          LocalStateQuery.QuerySpan.Prefix(end)
        case DetachedRecords.Prefix.Short      => LocalStateQuery.QuerySpan.Short
        case DetachedRecords.Prefix.Refused    => LocalStateQuery.QuerySpan.Refused
        case DetachedRecords.Prefix.Item(_, _) => LocalStateQuery.QuerySpan.Item

  private def inConway(q: Bytes): Bytes = hex("8203820082008206") ++ q

  private val queries: Vector[Bytes] =
    val rows = DetachedRecordsReferenceSuite.load.filter(_.kind == Kind.GovActionIds)
    rows.map(row => inConway(hex("82181f") ++ row.raw)) ++
      rows.map(row => inConway(hex("820982181f") ++ row.raw)) ++
      Vector(
        "8203820f",
        "820382008203",
        "82038200820082081f",
        "8203820082008206820601",
        "82038200820082068103",
        "8203820082008206820d81",
        "82038201820082008200"
      ).map(hex) ++
      Vector(
        inConway(hex("82069f429fff") ++ hex("01" * 900) ++ hex("ff")),
        inConway(hex("8209" * 400) ++ hex("820601")),
        inConway(hex("82181f" + "99003c" + ("9fff" + "5820" + "77" * 32 + "01") * 60))
      )

  private def differences(table: QueryTable, seed: Long): Vector[String] =
    val random = new Random(seed)
    val fresh = LocalStateQueryEngine.incremental(
      LocalStateQuery.State.Acquired,
      Role.Client,
      ChainSync.enginePointCodec,
      NtcQuery.readerFor(table)
    )
    def base(bytes: Bytes) =
      BaseDecoders.localStateQuery(
        LocalStateQuery.State.Acquired,
        Role.Client,
        bytes,
        span,
        BaseNtcQueryPrefix.prefix(_)(using table)
      )
    val inputs = queries.flatMap(q => q +: (1 to 3).map(k => Paced.flipped(q, k, random)))
    inputs.zipWithIndex.flatMap { (input, i) =>
      val cuts =
        if input.size <= 1500 then (0 to input.size).toVector
        else (Vector.fill(300)(random.nextInt(input.size + 1)) :+ input.size).distinct.sorted
      val expected = cuts.map(at => base(input.take(at)))
      val afresh = cuts.map(at => fresh.decodePrefix(input.take(at)))
      val untilVerdict = expected.indexWhere(_ != DecodeResult.NeedMore) match
        case -1 => expected
        case at => expected.take(at + 1)
      Vector(
        cuts.indices
          .find(k => afresh(k) != expected(k))
          .map(k => s"$table input $i prefix ${cuts(k)}: afresh ${afresh(k)}, base ${expected(k)}"),
        Option.when(Paced.resumed(fresh, input, cuts) != untilVerdict)(
          s"$table input $i: resumed differs from the base"
        )
      ).flatten
    }

  test("the node's reader of queries answers every prefix as the base's, in both query tables") {
    assertEquals(
      differences(QueryTable.Node11_1, 31L) ++ differences(QueryTable.Leios, 32L),
      Vector.empty
    )
  }
