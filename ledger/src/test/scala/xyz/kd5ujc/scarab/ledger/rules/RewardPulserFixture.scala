// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import java.time.Instant
import munit.Assertions
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, LedgerGlobals, TestLedger}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given

/** Deterministic attributed-reward inputs shared by the tick and unchanged-reference oracle suites.
  */
object RewardPulserFixture extends Assertions:
  val Supply = BigInt("45000000000000000")

  val globals = Globals(
    LedgerGlobals(
      Supply,
      10000,
      Rational(1, 20),
      network = 0,
      startRewards = 400,
      conway = None,
      alonzoCoinsPerUTxOWord = 34482
    ),
    EpochGeometry(Instant.EPOCH, 1000, 10000, 5, BigDecimal("0.05"))
  )

  def bytes(n: Int, size: Int): Bytes =
    Bytes.fromArray(
      Array.tabulate[Byte](size)(i => if i < 4 then ((n >> (8 * i)) & 0xff).toByte else 7)
    )

  def ok[A](either: Either[VocabError, A]): A =
    either.fold(e => fail(s"vocabulary: $e"), identity)

  def credential(n: Int): Credential[Staking] =
    Credential.KeyHashObj[Staking](ok(KeyHash.fromBytes[Staking](bytes(n, 28))))

  def pool(n: Int): KeyHash[StakePool] = ok(KeyHash.fromBytes[StakePool](bytes(n, 28)))

  def coin(n: BigInt): Coin = Coin(n)
  def compact(n: BigInt): CompactCoin = ok(CompactCoin.fromBigInt(n))

  /** A Conway state in `epoch`: `accounts` stake credentials delegating to `pools` pools, each with
    * the same balance and an output of the same value, a `go` snapshot of that stake, and the
    * blocks the pools made in the previous epoch.
    */
  def conway(epoch: Int, accounts: Int, pools: Int): NewEpochState[Conway] =
    val params = TestLedger.params(10).copy(rho = Rational(3, 1000), tau = Rational(1, 5))
    val byPool: Map[Int, Vector[Credential[Staking]]] =
      Vector.range(0, accounts).groupMap(_ % pools)(credential)
    val stakePools = Sorted.map(Vector.range(0, pools).map { p =>
      pool(p) -> StakePoolState(
        ok(VRFVerKeyHash.fromBytes(bytes(p, 32))),
        coin(0),
        coin(340000000),
        Rational(1, 100),
        credential(p),
        Sorted.emptySet[KeyHash[Staking]],
        Bytes.empty,
        None,
        compact(500000000),
        Sorted.set(byPool.getOrElse(p, Vector.empty))
      )
    })
    val states = Sorted.map(Vector.range(0, accounts).map { a =>
      credential(a) -> ConwayAccountState(
        compact(1000000),
        compact(2000000),
        Some(pool(a % pools)),
        None
      )
    })
    val instant = ConwayInstantStake(Sorted.map(Vector.range(0, accounts).map { a =>
      credential(a) -> compact(5000000000L)
    }))
    val cert = ConwayCertState(
      VState(Sorted.empty, CommitteeState(Sorted.empty), EpochNo.fromUnsigned(0L)),
      PState(Sorted.empty, stakePools, Sorted.empty, Sorted.empty),
      DState(
        ConwayAccounts(states),
        Sorted.empty,
        Sorted.empty,
        InstantaneousRewards(Sorted.empty, Sorted.empty, DeltaCoin(0), DeltaCoin(0))
      )
    )
    val gov = Empty.govState[Conway](params)
    // the fees collected so far, of which the snapshot fee is the part already counted
    val ls = LedgerState[Conway](
      cert,
      UTxOState[Conway](
        UtxoTables(xyz.kd5ujc.scarab.ledger.Utxo.empty),
        coin(BigInt(accounts) * 2000000 + BigInt(pools) * 500000000),
        coin(3000000),
        gov,
        instant,
        coin(0)
      )
    )
    val active = ok(EraLedger.activeStake(ls))
    val go = ok(StakeDistribution.snapshot(active, stakePools))
    val made = BlocksMade(
      Sorted.map(Vector.range(0, pools).map(p => pool(p) -> Word64.fromUnsigned(10L)))
    )
    NewEpochState[Conway](
      ok(EpochNo.fromBigInt(epoch)),
      made,
      made,
      EpochState[Conway](
        ChainAccountState(coin(1000000000000L), coin(Supply / 2)),
        ls,
        SnapShots(go, go, go, coin(3000000)),
        NonMyopic.empty
      ),
      None,
      PoolDistr.of(go),
      ()
    )

  /** The state of `c` as a Babbage one about to adopt the parameters of Conway's first version. */
  def babbage(c: NewEpochState[Conway]): NewEpochState[Babbage] =
    val ls = c.es.ls
    NewEpochState[Babbage](
      c.epoch,
      c.bprev,
      c.bcur,
      EpochState[Babbage](
        c.es.chainAccount,
        LedgerState[Babbage](
          ShelleyCertState(
            ls.cert.pState,
            DState(
              ShelleyAccounts(
                Sorted.map(
                  ls.cert.dState.accounts.states.iterator.map((k, a) =>
                    k -> ShelleyAccountState(
                      xyz.kd5ujc.scarab.ledger.Ptr(0, 0, 0),
                      a.balance,
                      a.deposit,
                      a.pool
                    )
                  )
                ),
                Sorted.empty
              ),
              Sorted.empty,
              Sorted.empty,
              ls.cert.dState.iRewards
            )
          ),
          UTxOState[Babbage](
            ls.utxo.utxo,
            ls.utxo.deposited,
            ls.utxo.fees,
            ShelleyGovState(
              Sorted.empty,
              Sorted.empty,
              TestLedger.params(8),
              TestLedger.params(8),
              FuturePParams.DefinitePParamsUpdate(TestLedger.params(9))
            ),
            ShelleyInstantStake(ls.utxo.instantStake.credentialStake, Sorted.empty),
            ls.utxo.donation
          )
        ),
        c.es.snapshots,
        c.es.nonMyopic
      ),
      c.ru,
      c.pd,
      ()
    )

  /** A small pool cost makes member rewards nonzero even in these deliberately small snapshots. */
  def rewarding(nes: NewEpochState[Conway]): NewEpochState[Conway] =
    val go = nes.es.snapshots.go
    nes.copy(es =
      nes.es.copy(snapshots =
        nes.es.snapshots.copy(go =
          SnapShot(go.activeStake, go.pools.transform((_, pool) => pool.copy(cost = coin(1))))
        )
      )
    )
