// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import java.time.Instant
import munit.Assertions
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, LedgerGlobals, Ptr, TestLedger}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.state.given

/** Native inputs for the reward semantics formerly exercised through the flat rule adapter. */
object NativeRewardFixture extends Assertions:
  val Supply: BigInt = TestLedger.Supply
  def ok[A](value: Either[VocabError, A]): A = value.fold(e => fail(e.toString), identity)
  def tickOk[A](value: Either[TickFailure, A]): A = value.fold(e => fail(e.toString), identity)
  def hex(value: String): Bytes = Bytes.fromHex(value).fold(e => fail(e.toString), identity)
  def hash(n: Int): Bytes = Bytes(Vector.fill(28)(n.toByte))
  def key(n: Int): KeyHash[Staking] = ok(KeyHash.fromBytes[Staking](hash(n)))
  def cred(n: Int): Credential[Staking] = Credential.KeyHashObj(key(n))
  def pool(n: Int): KeyHash[StakePool] = ok(KeyHash.fromBytes[StakePool](hash(n)))
  def coin(n: BigInt): Coin = Coin(n)
  def compact(n: BigInt): CompactCoin = ok(CompactCoin.fromBigInt(n))

  def globals(slots: Int, active: Rational, security: Int = 1, network: Int = 0): Globals =
    Globals(
      LedgerGlobals(Supply, slots, active, network),
      EpochGeometry(
        Instant.EPOCH,
        1000,
        slots,
        security,
        BigDecimal(active.numerator) / BigDecimal(active.denominator)
      )
    )

  def stakePool(
      account: Credential[Staking],
      cost: BigInt = 0,
      margin: Rational = Rational.zero
  ): StakePoolState =
    StakePoolState(
      ok(VRFVerKeyHash.fromBytes(Bytes(Vector.fill(32)(2)))),
      coin(0),
      coin(cost),
      margin,
      account,
      Sorted.emptySet,
      Bytes.empty,
      None,
      compact(500000000),
      Sorted.emptySet
    )

  def snapshot(
      stake: Iterable[(Credential[Staking], BigInt)],
      delegation: Map[Credential[Staking], KeyHash[StakePool]],
      pools: SortedMap[KeyHash[StakePool], StakePoolState]
  ): SnapShot =
    val active = ActiveStake(Sorted.map(stake.iterator.map { (c, amount) =>
      val p = delegation.getOrElse(c, fail(s"missing fixture delegation: $c"))
      c -> StakeWithDelegation(ok(NonZero.from(compact(amount))), p)
    }))
    // Native pool state carries its delegator index; construct it from these same delegations.
    val indexed = pools.transform((id, p) =>
      p.copy(delegators = Sorted.set(active.entries.iterator.collect {
        case (c, stake) if stake.delegation == id => c
      }))
    )
    StakeDistribution.snapshot(active, indexed)

  /** Rebuild just the era's account record, retaining the rest of its certificate state. */
  def accounts[E <: EraTag](
      nes: NewEpochState[E],
      entries: Iterable[(Credential[Staking], EraLedger.AccountView)]
  )(using era: ShelleyBasedEra[E]): NewEpochState[E] =
    val cert: CertState[E] = era match
      case ShelleyBasedEra.SConway =>
        val c = nes.es.ls.cert
        c.copy(dState = c.dState.copy(accounts = ConwayAccounts(Sorted.map(entries.map { (k, a) =>
          k -> ConwayAccountState(a.balance, a.deposit, a.pool, None)
        }))))
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        val c = nes.es.ls.cert
        c.copy(dState =
          c.dState.copy(accounts =
            ShelleyAccounts(
              Sorted.map(entries.map { (k, a) =>
                k -> ShelleyAccountState(Ptr(0, 0, 0), a.balance, a.deposit, a.pool)
              }),
              Sorted.empty
            )
          )
        )
    nes.copy(es = nes.es.copy(ls = nes.es.ls.copy[E](cert = cert)))

  def registered[E <: EraTag](nes: NewEpochState[E], cs: Iterable[Credential[Staking]])(using
      ShelleyBasedEra[E]
  ): NewEpochState[E] =
    accounts(nes, cs.map(_ -> EraLedger.AccountView(compact(0), compact(0), None)))

  def state[E <: EraTag](
      major: Int,
      go: SnapShot,
      blocks: Iterable[(KeyHash[StakePool], BigInt)],
      credentials: Iterable[Credential[Staking]],
      reserves: BigInt = BigInt(10000000000000L),
      fee: BigInt = 0,
      epoch: Int = 5
  )(using ShelleyBasedEra[E]): NewEpochState[E] =
    val base = NewEpochState.empty[E](ok(EpochNo.fromBigInt(epoch)), TestLedger.params(major))
    registered(
      base.copy(
        bprev = BlocksMade(
          Sorted.map(
            blocks.map((p, n) => p -> ok(Word64.fromBigInt(VocabError.Kind.BlockCount, n)))
          )
        ),
        es = base.es.copy(
          chainAccount = ChainAccountState(coin(0), coin(reserves)),
          snapshots = SnapShots(SnapShot.empty, SnapShot.empty, go, coin(fee)),
          ls = base.es.ls.copy[E](utxo = base.es.ls.utxo.copy(fees = coin(fee)))
        )
      ),
      credentials
    )

  def complete[E <: EraTag](nes: NewEpochState[E], g: Globals)(using
      ShelleyBasedEra[E]
  ): RewardUpdate =
    ok(Rewards.start(nes.es, nes.bprev, g, nes.epoch.toBigInt).flatMap(Rewards.complete))

  def rewards[E <: EraTag](nes: NewEpochState[E], g: Globals)(using
      ShelleyBasedEra[E]
  ): Map[Credential[Staking], BigInt] =
    val update = complete(nes, g)
    Rewards
      .aggregate(EraLedger.prevPParams(nes.es.ls.utxo.gov).protocolMajor, update.rs)
      .map((c, n) => c -> n.value)
      .toMap

  def boundary[E <: EraTag](nes: NewEpochState[E], g: Globals)(using
      ShelleyBasedEra[E]
  ): NewEpochState[E] =
    tickOk(Sts(NewEpoch.sts[E])(g, (), nes, ok(EpochNo.fromBigInt(nes.epoch.toBigInt + 1))))

  def tick[E <: EraTag](nes: NewEpochState[E], slot: BigInt, g: Globals)(using
      era: ShelleyBasedEra[E]
  ): NewEpochState[E] =
    tickOk(
      Hfc.tick(g, (_, _) => EraNes.eraOf(era))(
        EraNes.InEra(era, nes),
        ok(SlotNo.fromBigInt(slot))
      )
    ) match
      case i: EraNes.InEra[e] =>
        NewEpochState.retype(i.era, era)(i.nes).getOrElse(fail("unexpected fixture era change"))

  def rupd[E <: EraTag](nes: NewEpochState[E], offset: Int, g: Globals)(using
      ShelleyBasedEra[E]
  ): NewEpochState[E] =
    val slot = ok(SlotNo.fromBigInt(g.firstSlot(nes.epoch.toBigInt) + offset))
    nes.copy(ru = tickOk(Sts(Rupd.sts[E])(g, RupdEnv(nes.bprev, nes.es), nes.ru, slot)))

  def balance[E <: EraTag](nes: NewEpochState[E], c: Credential[Staking])(using
      ShelleyBasedEra[E]
  ): BigInt =
    EraLedger
      .accounts(nes.es.ls)
      .find(_._1 == c)
      .fold(fail(s"missing fixture account: $c"))(_._2.balance.toBigInt)

  /** Supply accounting for these empty-UTxO fixtures with no donations or proposals. */
  def total[E <: EraTag](nes: NewEpochState[E])(using ShelleyBasedEra[E]): BigInt =
    nes.es.chainAccount.reserves.value + nes.es.chainAccount.treasury.value +
      nes.es.ls.utxo.fees.value + nes.es.ls.utxo.deposited.value +
      CompactCoin.sum(EraLedger.accounts(nes.es.ls).iterator.map(_._2.balance)).toBigInt
