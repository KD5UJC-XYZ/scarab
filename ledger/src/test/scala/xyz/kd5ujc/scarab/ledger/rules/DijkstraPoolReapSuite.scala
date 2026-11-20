// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{PoolParams, RewardAccount, TestLedger}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.DijkstraNotYet

/** The voting key a pool holds once Dijkstra's POOLREAP makes its future parameters take effect:
  * `mkStakePoolState e (currentState ^. spsBlsKeyL)` (cardano-ledger 9df9f98c
  * `Dijkstra/Rules/PoolReap.hs:122-141`), whose `registerBlsKey <$> sppBlsKey`
  * (`State/StakePool.hs:299-320`) keeps the registration epoch of the key the pool holds, starts a
  * new key's at the epoch reaped into, and leaves a pool whose parameters carry none without one.
  * Shelley's rule passes `SNothing` (`Shelley/Rules/PoolReap.hs:144-152`): every key is new. The
  * same re-registration of the testnet's keyed pools is compared with the native tick in
  * `DijkstraEpochReferenceSuite`.
  */
class DijkstraPoolReapSuite extends FunSuite:
  private given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra

  private val owner = TestLedger.key()
  private val vrf = Bytes(Vector.fill(32)(3.toByte))
  private val params = PoolParams(
    owner.hash,
    vrf,
    0,
    1,
    Rational.zero,
    RewardAccount(0, owner.credential),
    Set(owner.hash),
    Bytes.empty,
    None
  )
  private def right[E, A](e: Either[E, A]): A = e.fold(x => fail(x.toString), identity)
  private val id = right(Convert.keyHash[StakePool](params.operator))

  private def key(n: Int) =
    BlsKey(Bytes(Vector.fill(96)(n.toByte)), Bytes(Vector.fill(48)(n.toByte)))
  private val registeredIn = EpochNo.fromUnsigned(3L)
  private val reaped = EpochNo.fromUnsigned(7L)

  /** A pool holding `held`, its future parameters carrying `future`, reaped into epoch 7. */
  private def reap(
      held: PoolBlsKey,
      future: PoolParamsBlsKey
  ): Either[TickFailure, PoolBlsKey] =
    val ls = Empty.ledgerState[Dijkstra](TestLedger.params(10))
    val pool = right(
      Convert.poolState(
        params,
        held,
        CompactCoin.fromUnsigned(5L),
        Sorted.emptySet[Credential[Staking]]
      )
    )
    val ps = Empty.pState.copy(
      vrfKeyHashes = Sorted.map(List(right(VRFVerKeyHash.fromBytes(vrf)) -> NonZero.oneWord)),
      stakePools = Sorted.map(List(id -> pool)),
      future = Sorted.map(List(id -> Build.stakePoolParams(params, future)))
    )
    val cert = EraLedger.withPState(ls, ps).cert
    Sts(DijkstraPoolReap.sts)(
      TestLedger.globals,
      (),
      PoolreapState(ls.utxo, ChainAccountState(Coin(0), Coin(0)), cert),
      reaped
    )
      .map { after =>
        val p = EraLedger.pState(LedgerState[Dijkstra](after.cert, after.utxo))
        assertEquals(p.future.size, 0)
        p.stakePools(id).blsKey
      }

  private def held(n: Int) = PoolBlsKey.Leios(Some(BlsKeyState(key(n), registeredIn)))

  test("a re-registration with the key the pool holds keeps the epoch it was registered in") {
    assertEquals(reap(held(1), PoolParamsBlsKey.Leios(Some(key(1)))), Right(held(1)))
  }

  test("a re-registration with another key registers it at the epoch reaped into") {
    assertEquals(
      reap(held(1), PoolParamsBlsKey.Leios(Some(key(2)))),
      Right(PoolBlsKey.Leios(Some(BlsKeyState(key(2), reaped))))
    )
  }

  test("a pool without a key re-registered with one holds it from the epoch reaped into") {
    assertEquals(
      reap(PoolBlsKey.Leios(None), PoolParamsBlsKey.Leios(Some(key(1)))),
      Right(PoolBlsKey.Leios(Some(BlsKeyState(key(1), reaped))))
    )
  }

  test("a pool with a key re-registered without one holds none") {
    assertEquals(reap(held(1), PoolParamsBlsKey.Leios(None)), Right(PoolBlsKey.Leios(None)))
  }

  test(
    "Shelley's adoption registers the parameters' key afresh, as `mkStakePoolState e SNothing`"
  ) {
    val pool = right(
      Convert.poolState(
        params,
        held(1),
        CompactCoin.fromUnsigned(5L),
        Sorted.emptySet[Credential[Staking]]
      )
    )
    def adopted(future: PoolParamsBlsKey) =
      PoolReap.shelleyAdopt(pool, Build.stakePoolParams(params, future), reaped)
    assertEquals(
      adopted(PoolParamsBlsKey.Leios(Some(key(1)))),
      Right(PoolBlsKey.Leios(Some(BlsKeyState(key(1), reaped))))
    )
    assertEquals(adopted(PoolParamsBlsKey.Leios(None)), Right(PoolBlsKey.Leios(None)))
    assertEquals(adopted(PoolParamsBlsKey.Node11_1), Right(PoolBlsKey.Node11_1))
  }

  test("a pool or parameters of the 11.1 layout are not held in Dijkstra") {
    val notYet = Left(TickFailure.NotYet(DijkstraNotYet.LedgerState))
    assertEquals(reap(PoolBlsKey.Node11_1, PoolParamsBlsKey.Leios(Some(key(1)))), notYet)
    assertEquals(reap(held(1), PoolParamsBlsKey.Node11_1), notYet)
    assertEquals(reap(PoolBlsKey.Node11_1, PoolParamsBlsKey.Node11_1), notYet)
  }
