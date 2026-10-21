// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.genesis

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash, ShelleyBasedEra, VocabError, Word64}
import xyz.kd5ujc.scarab.protocol.ChainSync.{Point, UInt64}
import DiffusionPunishment.*

class DiffusionPunishmentSuite extends FunSuite:
  private def word(n: Long): Word64 = Word64
    .fromBigInt(VocabError.Kind.BlockNo, BigInt(n))
    .fold(e => fail(e.toString), identity)
  private def issuer(n: Byte): HotIdentity = HotIdentity(
    KeyHash.fromBytes[BlockIssuer](Bytes(Vector.fill(28)(n))).fold(e => fail(e.toString), identity),
    word(0)
  )
  private def block(slot: Long, n: Byte): Point.Block =
    Point.Block(UInt64.saturating(slot), Bytes(Vector(n)))

  test("Shelley trap identity uses height, cold issuer and operational certificate counter") {
    val a = View.Shelley(ShelleyBasedEra.SBabbage, word(10), issuer(1))
    val accepted = judge(initial, a, Invalidity.BlockItself)
    assertEquals(accepted.outcome, Outcome.Forgive)
    assertEquals(judge(accepted.state, a, Invalidity.BlockItself).outcome, Outcome.Punish)
    val b = a.copy(identity = issuer(2))
    val different = judge(accepted.state, b, Invalidity.BlockItself)
    assertEquals(different.outcome, Outcome.Forgive)
    assertEquals(judge(different.state, a, Invalidity.BlockItself).outcome, Outcome.Punish)
    val renewed = a.copy(identity = a.identity.copy(issueNo = word(1)))
    assertEquals(judge(different.state, renewed, Invalidity.BlockItself).outcome, Outcome.Forgive)
    assertEquals(
      judge(different.state, a.copy(blockNo = word(9)), Invalidity.BlockItself).outcome,
      Outcome.Punish
    )
    val higher = judge(different.state, a.copy(blockNo = word(11)), Invalidity.BlockItself)
    assertEquals(higher.outcome, Outcome.Forgive)
    assertEquals(
      judge(higher.state, b.copy(blockNo = word(11)), Invalidity.BlockItself).outcome,
      Outcome.Forgive
    )
  }

  test("Byron requires a strictly larger select view including its EBB tie breaker") {
    val regular = View.Byron(word(3), false)
    val first = judge(initial, regular, Invalidity.BlockItself)
    assertEquals(first.outcome, Outcome.Forgive)
    assertEquals(judge(first.state, regular, Invalidity.BlockItself).outcome, Outcome.Punish)
    val ebb = judge(first.state, regular.copy(isEBB = true), Invalidity.BlockItself)
    assertEquals(ebb.outcome, Outcome.Forgive)
    assertEquals(judge(ebb.state, regular, Invalidity.BlockItself).outcome, Outcome.Punish)
  }

  test("hard forks reset trap state only forwards; prefix punishment never mutates it") {
    val first = View.Shelley(ShelleyBasedEra.SShelley, word(4), issuer(1))
    val old = judge(initial, first, Invalidity.BlockItself)
    val later = View.Shelley(ShelleyBasedEra.SAllegra, word(4), issuer(1))
    val next = judge(old.state, later, Invalidity.BlockItself)
    assertEquals(next.outcome, Outcome.Forgive)
    assertEquals(
      judge(next.state, first, Invalidity.BlockItself),
      Decision(next.state, Outcome.Punish)
    )
    assertEquals(
      judge(old.state, later, Invalidity.BlockPrefix),
      Decision(old.state, Outcome.Punish)
    )
  }

  test("invalidity classification preserves suffix and same-slot EBB exceptions") {
    val added = block(5, 1)
    assertEquals(classify(added, added, Point.Origin), Some(Invalidity.BlockItself))
    assertEquals(classify(added, block(4, 2), Point.Origin), Some(Invalidity.BlockPrefix))
    assertEquals(classify(added, block(6, 2), Point.Origin), None)
    assertEquals(classify(added, block(5, 2), added), None)
    assertEquals(classify(added, block(5, 2), Point.Origin), Some(Invalidity.BlockPrefix))
  }
