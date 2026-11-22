// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.{LedgerFailure, TestLedger}
import xyz.kd5ujc.scarab.ledger.era.{EraCertsRun, PParams, PoolCert}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPParams
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  Build,
  CompactCoin,
  Conway,
  Credential,
  Empty,
  EpochNo,
  PState,
  PoolBlsKey,
  PoolParamsBlsKey,
  Sorted,
  StakePoolParams,
  Staking
}
import xyz.kd5ujc.scarab.protocol.StateLayout

/** The era-typed POOL keeps a pool's parameters in the layout of the state they go into: a new
  * pool's in the layout of the build the node follows, a re-registration's in the layout of the
  * pool's state. Parameters of the other layout, a certificate decoded for the other build, are
  * refused ([[LedgerFailure.PoolParamsLayout]]); the reference is one build and never meets them.
  */
class PoolParamsLayoutSuite extends FunSuite:
  private val pp = PParams[Conway](
    ConwayPParams.fromStored(TestLedger.params(10)).fold(e => fail(e.toString), identity)
  )

  private val flat = node.PoolParams(
    Bytes(Vector.fill(28)(9.toByte)),
    Bytes(Vector.fill(32)(8.toByte)),
    BigInt(0),
    BigInt(340000000),
    Rational(1, 100),
    node.RewardAccount(
      TestLedger.globals.networkId.wire,
      node.Credential.Key(Bytes(Vector.fill(28)(7.toByte)))
    ),
    Set.empty,
    Bytes.empty,
    None
  )

  private def params(layout: StateLayout): StakePoolParams =
    Build.stakePoolParams(flat, PoolParamsBlsKey.certified(layout))

  /** The pool state with the pool registered in `layout`. */
  private def registered(layout: StateLayout): PState =
    val state = Convert
      .poolState(
        flat,
        PoolBlsKey.certified(layout),
        CompactCoin.fromBigInt(BigInt(500)).fold(e => fail(e.toString), identity),
        Sorted.emptySet[Credential[Staking]]
      )
      .fold(e => fail(e.toString), identity)
    Empty.pState.copy(stakePools = Sorted.map(Vector(params(layout).id -> state)))

  private def run(state: PState, p: StakePoolParams) =
    EraCertsRun
      .pool(
        TestLedger.globals,
        PoolEnv[Conway](EpochNo.fromUnsigned(5), pp),
        state,
        PoolCert.RegPool(p)
      )
      .map(_.map(_._1))

  test("a new pool's parameters of the other build's layout are refused") {
    // the test globals follow the 11.1 build
    assertEquals(
      run(Empty.pState, params(StateLayout.Leios)),
      Left(
        LedgerFailure.PoolParamsLayout(flat.operator, StateLayout.Leios, StateLayout.Node11_1)
      )
    )
    assert(run(Empty.pState, params(StateLayout.Node11_1)).exists(_.isRight))
  }

  test("a re-registration waits in the layout of the pool's state, and another is refused") {
    assertEquals(
      run(registered(StateLayout.Leios), params(StateLayout.Node11_1)),
      Left(
        LedgerFailure.PoolParamsLayout(flat.operator, StateLayout.Node11_1, StateLayout.Leios)
      )
    )
    StateLayout.values.foreach { layout =>
      val next = run(registered(layout), params(layout))
      assertEquals(
        next.map(_.map(_.future.get(params(layout).id).map(_.blsKey))),
        Right(Right(Some(PoolParamsBlsKey.certified(layout)))),
        layout.toString
      )
    }
  }
