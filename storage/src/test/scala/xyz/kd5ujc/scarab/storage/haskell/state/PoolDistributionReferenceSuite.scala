// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell.state

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.*

class PoolDistributionReferenceSuite extends FunSuite:
  private given ProtVer = ProtVer(Word64.fromUnsigned(12), Word64.zero)

  private def hex(value: String): Bytes =
    Bytes.fromHex(value).fold(e => fail(e.toString), identity)

  private val rows = Files
    .readString(Path.of("fixtures/reference/pool-distribution/cases.jsonl"))
    .linesIterator
    .filter(_.nonEmpty)
    .map(line => parse(line).fold(e => fail(e.toString), identity).hcursor)
    .toVector

  rows.foreach { row =>
    def field(name: String): String = row.get[String](name).fold(e => fail(e.toString), identity)
    val build = field("build")
    val name = field("name")
    test(s"$build: $name agrees with the native pool distribution") {
      val codecs = if build == "11.1" then HaskellStateCodecs else LeiosStateCodecs
      val raw = hex(field("cbor"))
      val result =
        if field("kind") == "snapshot" then
          codecs.snapShot.decodeBytes(raw, HaskellState.limits).map { snapshot =>
            val distribution = PoolDistr.of(snapshot)
            assertEquals(codecs.poolDistr.check(distribution), None)
            codecs.poolDistr.encodeUnchecked(distribution)
          }
        else
          codecs.individualPoolStake.decodeBytes(raw, HaskellState.limits).map { stake =>
            assertEquals(codecs.individualPoolStake.check(stake), None)
            codecs.individualPoolStake.encodeUnchecked(stake)
          }
      val reference = row.downField("reference")
      val accepted = reference.get[Boolean]("accepted").fold(e => fail(e.toString), identity)
      assertEquals(result.isRight, accepted, s"native result: ${reference.focus}")
      if accepted then
        val expected = reference.get[String]("cbor").fold(e => fail(e.toString), identity)
        assertEquals(result.map(_.hex), Right(expected))
    }
  }

  test("the immutable snapshot reuses its distribution; copying a changed snapshot recomputes it") {
    val row = rows
      .find(r =>
        r.get[String]("build") == Right("leios-b004fe0c") &&
          r.get[String]("name") == Right("key")
      )
      .getOrElse(fail("missing native voting-key case"))
    val raw = hex(row.get[String]("cbor").fold(e => fail(e.toString), identity))
    val snapshot = LeiosStateCodecs.snapShot
      .decodeBytes(raw, HaskellState.limits)
      .fold(e => fail(e.render), identity)
    val initial = PoolDistr.of(snapshot)
    assert(initial eq PoolDistr.of(snapshot), "one distribution per immutable snapshot")
    assert(initial.distribution.values.forall(_.blsKey.nonEmpty), "voting keys retained")
    val changed = snapshot.copy(pools =
      Sorted.map(snapshot.pools.iterator.map((id, p) => id -> p.copy(numDelegators = Word64.zero)))
    )
    assertEquals(
      PoolDistr.of(changed).distribution.size,
      0,
      "changed snapshot has its own distribution"
    )
    assertEquals(PoolDistr.of(snapshot), initial, "original snapshot stays unchanged")
    val noStake = snapshot.copy(activeStake = ActiveStake.empty)
    assertEquals(
      PoolDistr.of(noStake).totalActiveStake,
      Coin.oneNonZero,
      "empty active stake keeps the reference denominator"
    )
  }

  test("the stable encoder refuses a voting key rather than silently discarding it") {
    val stake = IndividualPoolStake(
      xyz.kd5ujc.scarab.codec.Rational.one,
      CompactCoin.fromUnsigned(1),
      VRFVerKeyHash
        .fromBytes(Bytes.fromArray(Array.fill[Byte](32)(1)))
        .fold(e => fail(e.toString), identity),
      LeiosGen.keys.headOption
    )
    assert(stake.blsKey.nonEmpty)
    assert(HaskellStateCodecs.individualPoolStake.check(stake).nonEmpty)
    assertEquals(LeiosStateCodecs.individualPoolStake.check(stake), None)
  }
