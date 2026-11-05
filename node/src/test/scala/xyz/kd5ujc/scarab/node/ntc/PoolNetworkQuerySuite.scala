// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.models.runtime.Writer
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState}
import xyz.kd5ujc.scarab.ledger.Credential
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  KeyHash,
  LedgerState,
  PState,
  ShelleyBasedEra,
  StakePool
}
import xyz.kd5ujc.scarab.node.read.ChainRead.given

/** B27: a registered pool whose reward account is on another network than the node's (a
  * registration before protocol version 5, B22) is reported on the node's network, as the reference
  * reports it (`queryPoolParameters` and `mkQueryPoolStateResult`, `Api/State/Query.hs:485-525`,
  * through `stakePoolStateToStakePoolParams`); its future parameters are reported as registered.
  * The state is the mirror, which keeps the account's credential only, so the registered pool has
  * no other network to be on; the future parameters keep theirs.
  */
class PoolNetworkQuerySuite extends FunSuite:
  private val network = NtcGolden.queryEnv.mirror.network
  private val foreign = 1 - network

  private val (_, base, point, blockNo) = NtcGolden.subjects
    .find(s => NtcGolden.pools(s._2).nonEmpty)
    .getOrElse(fail("no Conway state of the capture has a pool"))
  private val (id, pool) = NtcGolden.pools(base).head
  private val moved =
    pool.params.copy(rewardAccount = pool.params.rewardAccount.copy(network = foreign))

  private val key = KeyHash.fromBytes[StakePool](id).fold(v => fail(v.toString), identity)

  /** The state with the re-registration of the pool, its account on the other network, waiting. */
  private def withFuture: MirrorState =
    changed(ps => ps.copy(future = ps.future.updated(key, moved)))

  /** The state with its pool state changed by `change`. */
  private def changed(change: PState => PState): MirrorState =
    base.ext.ledgerState.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        given ShelleyBasedEra[e] = i.era
        val ls = i.state.nes.es.ls
        val next = change(LedgerState.pState[e].get(ls))
        val nes =
          i.state.nes.copy(es = i.state.nes.es.copy(ls = LedgerState.pState[e].replace(next)(ls)))
        base.copy(ext =
          base.ext.copy(ledgerState =
            base.ext.ledgerState.withState(
              EraLedgerState.InShelleyEra(i.era, i.state.copy(nes = nes))
            )
          )
        )
      case _ => fail("the state is in Byron")

  private def ask(state: MirrorState, query: LedgerQuery): Bytes =
    QueryAnswers
      .answer(
        NtcGolden.queryEnv,
        QueryView(point, blockNo, state),
        Map.empty,
        NtcQuery.IfCurrent(QueryEra.Conway, query)
      )
      .getOrElse(fail(s"$query is not answered"))

  private def occurrences(in: Bytes, net: Int): Int =
    val header =
      (if moved.rewardAccount.credential.isInstanceOf[Credential.Script] then 0xf0 else 0xe0) | net
    val of = Vector[Byte](0x58, 0x1d, header.toByte) ++ moved.rewardAccount.credential.hash.value
    in.value.indices.count(i => in.value.slice(i, i + of.size) == of)

  test("the state has a registered pool on the node's network") {
    assertEquals(pool.params.rewardAccount.network, network)
  }

  test("GetStakePoolParams reports the registered pool's account on the node's network") {
    val answer = ask(base, LedgerQuery.GetStakePoolParams(Set(id)))
    assertEquals(occurrences(answer, network), 1)
    assertEquals(occurrences(answer, foreign), 0)
  }

  test("GetPoolState reports the registered pool on the node's network, the future as registered") {
    val answer = ask(withFuture, LedgerQuery.GetPoolState(Some(Set(id))))
    assertEquals(occurrences(answer, network), 1)
    assertEquals(occurrences(answer, foreign), 1)
  }

  // A genesis pool may hold a negative pledge or cost (#763); the reference answers with them as
  // `Integer`s (`EncCBOR Coin`, `Coin.hs:93-106`; `StakePool.hs:550-571`), cardano-ledger f649f975.
  test(
    "GetStakePoolParams and GetPoolState write a negative pledge and cost as the reference does"
  ) {
    val cost = -(BigInt(1) << 70)
    val negative = changed(ps =>
      ps.copy(stakePools =
        ps.stakePools.updatedWith(key)(_.map(_.copy(pledge = Coin(-1), cost = Coin(cost))))
      )
    )
    val coins =
      val w = new Writer
      w.integer(-1)
      w.integer(cost)
      w.result
    def holds(in: Bytes): Boolean = in.value.containsSlice(coins.value)
    assert(holds(ask(negative, LedgerQuery.GetStakePoolParams(Set(id)))), "GetStakePoolParams")
    assert(holds(ask(negative, LedgerQuery.GetPoolState(Some(Set(id))))), "GetPoolState")
  }
