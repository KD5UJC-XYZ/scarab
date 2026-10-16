// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.ledger.Credential
import xyz.kd5ujc.scarab.protocol.Era

/** B27: a registered pool whose reward account is on another network than the node's (a
  * registration before protocol version 5, B22) is reported on the node's network, as the reference
  * reports it (`queryPoolParameters` and `mkQueryPoolStateResult`, `Api/State/Query.hs:485-525`,
  * through `stakePoolStateToStakePoolParams`); its future parameters are reported as registered.
  * The answers do not depend on whether the state was restored from a snapshot, which keeps the
  * account's credential only.
  */
class PoolNetworkQuerySuite extends FunSuite:
  private val network = NtcGolden.queryEnv.mirror.network
  private val foreign = 1 - network

  private val (_, base, point, blockNo) = NtcGolden.subjects
    .find(_._2.ledger.pools.nonEmpty)
    .getOrElse(fail("no Conway state of the capture has a pool"))
  private val (id, pool) = base.ledger.pools.head
  private val moved =
    pool.params.copy(rewardAccount = pool.params.rewardAccount.copy(network = foreign))

  /** The state with the pool's account moved to the other network, re-registered with it if
    * `future`.
    */
  private def withPool(future: Boolean): NodeState =
    val l = base.ledger
    base.copy(ledger =
      l.copy(
        pools = l.pools.updated(id, pool.copy(params = moved)),
        futurePools = if future then l.futurePools.updated(id, moved) else l.futurePools
      )
    )

  private def ask(state: NodeState, query: LedgerQuery): Bytes =
    QueryAnswers
      .answer(
        NtcGolden.queryEnv,
        QueryView(point, blockNo, state),
        Map.empty,
        NtcQuery.IfCurrent(Era.Conway.index, query)
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
    val ids = Set(id)
    assertEquals(
      ask(withPool(future = false), LedgerQuery.GetStakePoolParams(ids)),
      ask(base, LedgerQuery.GetStakePoolParams(ids))
    )
  }

  test("GetPoolState reports the registered pool on the node's network, the future as registered") {
    val answer =
      ask(withPool(future = true), LedgerQuery.GetPoolState(Some(Set(id))))
    assertEquals(occurrences(answer, network), 1)
    assertEquals(occurrences(answer, foreign), 1)
  }
