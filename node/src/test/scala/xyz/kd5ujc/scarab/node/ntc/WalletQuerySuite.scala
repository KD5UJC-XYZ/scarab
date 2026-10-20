// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.parser
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Rational

class WalletQuerySuite extends FunSuite:
  private val expected = parser
    .parse(Files.readString(Path.of("fixtures/reference/wallet-queries/answers.json")))
    .fold(e => fail(e.message), _.asArray.getOrElse(fail("answers must be an array")))
    .map { row =>
      val c = row.hcursor
      val name = c.get[String]("name").fold(e => fail(e.message), identity)
      name -> c
    }
    .toMap

  WalletQueryCases.cases.foreach { (name, nes) =>
    test(s"$name: native reward info and member rewards match byte for byte") {
      val facts = WalletQueryCases.facts(nes)
      val snap = facts.currentStake.fold(e => fail(e.toString), identity)
      assert(snap.pools.nonEmpty)
      assert(nes.es.snapshots.mark.pools.isEmpty)
      val total = NtcGolden.queryEnv.maxSupply - nes.es.chainAccount.reserves.value
      val reference = expected.getOrElse(name, fail(s"missing native case $name"))
      val info = RewardQueries.info(facts.l.params, total, snap, nes.es.nonMyopic)
      val members = RewardQueries.members(
        facts.l.params,
        total,
        snap,
        nes.es.nonMyopic,
        WalletQueryCases.credentials.reverse ++ WalletQueryCases.credentials
      )
      import xyz.kd5ujc.scarab.node.read.ChainRead.given
      val view = QueryView(
        xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin,
        None,
        WalletQueryCases.mirror(nes)
      )
      def asked(q: LedgerQuery): xyz.kd5ujc.scarab.codec.Bytes =
        QueryAnswers
          .answerOrWhy(NtcGolden.queryEnv, view, Map.empty, NtcQuery.IfCurrent(6, q))
          .fold(e => fail(e.toString), identity)
      assertEquals(asked(LedgerQuery.GetRewardInfoPools), CborOut.build(_.array(1).raw(info)))
      assertEquals(
        asked(LedgerQuery.GetNonMyopicMemberRewards(WalletQueryCases.credentials)),
        CborOut.build(_.array(1).raw(members))
      )
      assertEquals(
        asked(LedgerQuery.GetCBOR(LedgerQuery.GetRewardInfoPools)),
        CborOut.build(_.array(1).tag(24).bytes(info))
      )
      val widePot = xyz.kd5ujc.scarab.ledger.state.Coin
        .fromBigInt(BigInt(1) << 80)
        .fold(e => fail(e.toString), identity)
      val wide = nes.es.nonMyopic.copy(rewardPot = widePot)
      assertEquals(
        RewardQueries.info(facts.l.params, total, snap, wide).hex,
        reference.get[String]("wideRewardInfo").fold(e => fail(e.message), identity)
      )
      assertEquals(
        RewardQueries.members(facts.l.params, total, snap, wide, WalletQueryCases.credentials).hex,
        reference.get[String]("wideMemberRewards").fold(e => fail(e.message), identity)
      )
      assertEquals(
        info.hex,
        reference.get[String]("rewardInfo").fold(e => fail(e.message), identity)
      )
      assertEquals(
        members.hex,
        reference.get[String]("memberRewards").fold(e => fail(e.message), identity)
      )
    }
  }

  test("rational conversion preserves signed subnormals and the normal boundary") {
    val two53 = BigInt(1) << 53
    val sub = BigInt(1) << 1075
    def bits(n: BigInt, d: BigInt): Long =
      java.lang.Double.doubleToRawLongBits(RewardQueries.asDouble(Rational(n, d)))
    assertEquals(bits(-1, sub), Long.MinValue)
    assertEquals(bits(1, sub / 2), 1L)
    assertEquals(bits(two53 - 1, sub), 0x0010000000000000L)
    assertEquals(bits(-two53 - 1, two53), 0xbff0000000000000L)
    assertEquals(bits(-two53 - 3, two53), 0xbff0000000000002L)
  }

  test("rational conversion rounds binary64 halfway cases to even without decimal approximation") {
    val two53 = BigInt(1) << 53
    assertEquals(RewardQueries.asDouble(Rational(two53 + 1, two53)), 1.0)
    assertEquals(RewardQueries.asDouble(Rational(two53 + 3, two53)), math.nextUp(math.nextUp(1.0)))
    assertEquals(RewardQueries.asDouble(Rational(1, BigInt(1) << 1075)), 0.0)
    assertEquals(
      RewardQueries.asDouble(Rational(3, BigInt(1) << 1075)),
      2 * java.lang.Double.MIN_VALUE
    )
    assertEquals(RewardQueries.asDouble(Rational(-1, 3)), -1.0 / 3.0)
    assertEquals(RewardQueries.asDouble(Rational(BigInt(1) << 1024)), Double.PositiveInfinity)
  }
