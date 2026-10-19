// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.data.{Kleisli, StateT}
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import munit.FunSuite
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TestLedger
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.protocol.Era

/** The tick rules (J2.3: TICK, NEWEPOCH, EPOCH, SNAP, POOLREAP, MIR, RUPD, the translations of the
  * hard-fork combinator) on states built here: what each does at an epoch boundary, that the pots
  * of lovelace are conserved, and that a state of a hundred thousand accounts costs no stack (ADR
  * 0011: every test of width runs on a 256 KiB thread).
  *
  * The agreement of the rules with the node's own (`NodeRules`, `Epoch`) is the differential
  * harness's: `DifferentialErasSuite` on the eras capture and `PreviewDifferentialMain` on the
  * Preview chain.
  */
class TickRulesSuite extends FunSuite:
  import RewardPulserFixture.*

  // the first slot of an epoch, and a slot past the start of the rewards in it
  private def first(epoch: Int): Long = epoch.toLong * 10000
  private def late(epoch: Int): Long = first(epoch) + 5000

  private val noEra: Hfc.EraAt = (_, _) => Era.Conway

  private def tick(nes: NewEpochState[Conway], slot: Long): NewEpochState[Conway] =
    Hfc
      .tick(globals, noEra)(EraNes.InEra(ShelleyBasedEra.SConway, nes), ok(SlotNo.fromBigInt(slot)))
      .fold(e => fail(s"tick refused: $e"), identity) match
      case i: EraNes.InEra[e] =>
        NewEpochState
          .retype(i.era, ShelleyBasedEra.SConway)(i.nes)
          .getOrElse(fail("the tick left Conway"))

  /** Everything the ledger holds, in lovelace but the UTxO (which a tick leaves alone). */
  private def pots(nes: NewEpochState[Conway]): BigInt =
    val es = nes.es
    es.chainAccount.treasury.value + es.chainAccount.reserves.value + es.ls.utxo.fees.value +
      es.ls.utxo.deposited.value +
      es.ls.cert.dState.accounts.states.valuesIterator.foldLeft(BigInt(0))(_ + _.balance.toBigInt)

  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.getOrElse(fail("the thread did not finish")).fold(throw _, identity)

  test("RUPD starts without member work, pulses in the normal window, and forces after 8k/f") {
    val before = conway(5, 41, 4)
    val at = first(5) + globals.randomnessStabilisationWindow.toLong
    assertEquals(tick(before, at).ru, None)
    val started = tick(before, at + 1)
    started.ru match
      case Some(PulsingRewUpdate.Pulsing(_, p)) =>
        assertEquals(p.remaining.size, 41)
        assertEquals(p.answer, RewardAns.empty)
      case other => fail(s"not started: $other")
    val advanced = tick(started, at + 2)
    advanced.ru match
      case Some(PulsingRewUpdate.Pulsing(_, p)) => assertEquals(p.remaining.size, 38)
      case other                                => fail(s"not pulsing: $other")
    val force = at + globals.randomnessStabilisationWindow.toLong
    assert(tick(started, force).ru.exists(_.isInstanceOf[PulsingRewUpdate.Pulsing]))
    val completed = tick(advanced, force + 1)
    assertEquals(
      completed.ru,
      Some(
        PulsingRewUpdate.Complete(
          Rewards
            .update(before.es, before.bprev, globals, 5)
            .fold(e => fail(s"update: $e"), identity)
        )
      )
    )
    assertEquals(tick(before, force + 1).ru, completed.ru)
  }

  test("a boundary completes an unfinished reward prefix before applying it") {
    val before = conway(5, 41, 4)
    val start = first(5) + globals.randomnessStabilisationWindow.toLong + 1
    val pending = tick(tick(before, start), start + 1)
    val eager =
      Rewards.update(before.es, before.bprev, globals, 5).fold(e => fail(s"update: $e"), identity)
    val control = before.copy(ru = Some(PulsingRewUpdate.Complete(eager)))
    assertEquals(tick(pending, first(6) + 10), tick(control, first(6) + 10))
  }

  test(
    "a tick inside an epoch changes nothing but the reward update, which starts after the window"
  ) {
    val before = conway(5, 20, 4)
    val early = tick(before, first(5) + 100)
    assertEquals(early, before)
    val started = tick(before, late(5))
    assert(started.ru.isDefined, "the reward update starts past 4k/f slots into the epoch")
    assertEquals(started.copy(ru = None), before)
    // once started it stays, and is not computed again
    assertEquals(tick(started, late(5) + 7).ru, started.ru)
  }

  test(
    "the epoch boundary: rewards applied, snapshots rotated, blocks moved, pools redistributed"
  ) {
    val before = tick(conway(5, 20, 4), late(5))
    val after = tick(before, first(6) + 10)
    assertEquals(after.epoch, ok(EpochNo.fromBigInt(6)))
    assertEquals(after.bprev, before.bcur)
    assertEquals(after.bcur, BlocksMade.empty)
    assertEquals(after.ru, None)
    assertEquals(after.es.snapshots.set, before.es.snapshots.mark)
    assertEquals(after.es.snapshots.go, before.es.snapshots.set)
    assertEquals(after.pd, before.es.snapshots.markPoolDistr)
    // the new mark is the stake of the accounts now: output and balance, each delegated
    assertEquals(after.es.snapshots.mark.activeStake.entries.size, 20)
    assert(after.es.snapshots.mark.pools.valuesIterator.forall(_.numDelegators.toBigInt == 5))
    // the fees the update took into the rewards are not the snapshot fee of the new epoch
    assertEquals(after.es.snapshots.fee, after.es.ls.utxo.fees)
    // the pools made blocks and the accounts were given rewards
    val balances = (s: NewEpochState[Conway]) =>
      s.es.ls.cert.dState.accounts.states.valuesIterator.map(_.balance.toBigInt).sum
    assert(balances(after) > balances(before))
    assertEquals(pots(after), pots(before))
  }

  test("an epoch with no block is crossed boundary by boundary") {
    val before = tick(conway(5, 20, 4), late(5))
    val after = tick(before, first(8) + 10)
    assertEquals(after.epoch, ok(EpochNo.fromBigInt(8)))
    // three boundaries: the reward update of the epoch before the gap is applied once
    assertEquals(pots(after), pots(before))
    assert(after.bprev == BlocksMade.empty, "the epoch before the last had no blocks")
  }

  test("a retiring pool is refunded to its reward account, or to the treasury if it is gone") {
    val before = conway(5, 20, 4)
    val ps = before.es.ls.cert.pState
    val retiring = ps.copy(retiring =
      Sorted.map(List(pool(0) -> ok(EpochNo.fromBigInt(6)), pool(1) -> ok(EpochNo.fromBigInt(6))))
    )
    // pool 1's reward account (credential 1) is deregistered: it is account 1 in this state, so
    // move the pool's reward account to one that is not registered
    val gone = credential(99999)
    val withGone = retiring.copy(stakePools =
      retiring.stakePools.updated(pool(1), retiring.stakePools(pool(1)).copy(accountId = gone))
    )
    val staged = before.copy(es = before.es.copy(ls = EraLedger.withPState(before.es.ls, withGone)))
    val after = tick(staged, first(6) + 10)
    val accounts = after.es.ls.cert.dState.accounts.states
    assert(!after.es.ls.cert.pState.stakePools.contains(pool(0)))
    assert(!after.es.ls.cert.pState.stakePools.contains(pool(1)))
    // pool 0 refunded to credential 0; pool 1's deposit to the treasury
    assertEquals(
      accounts(credential(0)).balance.toBigInt - staged.es.ls.cert.dState.accounts
        .states(credential(0))
        .balance
        .toBigInt >= 500000000,
      true
    )
    assert(
      after.es.chainAccount.treasury.value >=
        staged.es.chainAccount.treasury.value + 500000000
    )
    // their delegators no longer delegate
    assert(accounts.valuesIterator.forall(a => !a.pool.exists(p => p == pool(0) || p == pool(1))))
    assertEquals(
      after.es.ls.utxo.deposited.value,
      BigInt(20) * 2000000 + BigInt(2) * 500000000
    )
  }

  test("a tick over 150,000 accounts runs on a 256 KiB stack") {
    val big = conway(5, 150000, 300)
    onSmallStack {
      val started = tick(big, late(5))
      val ru = started.ru.getOrElse(fail("no reward update"))
      ru match
        case PulsingRewUpdate.Complete(u) => assert(u.rs.nonEmpty)
        case other                        => fail(s"unexpected $other")
      val after = tick(started, first(6) + 10)
      assertEquals(after.epoch, ok(EpochNo.fromBigInt(6)))
      assertEquals(after.es.snapshots.mark.activeStake.entries.size, 150000)
      assertEquals(pots(after), pots(started))
    }
  }

  /** Conway enters at epoch 6 when the parameters adopted there are of major version 9. */
  private val schedule: Hfc.EraAt = (epoch, major) =>
    if epoch >= 6 && major >= 9 then Era.Conway else Era.Babbage

  private def enterConway(from: NewEpochState[Babbage]): NewEpochState[Conway] =
    Hfc
      .tick(globals, schedule)(
        EraNes.InEra(ShelleyBasedEra.SBabbage, from),
        ok(SlotNo.fromBigInt(first(6) + 10))
      )
      .fold(e => fail(s"refused: $e"), identity) match
      case i: EraNes.InEra[e] =>
        NewEpochState
          .retype(i.era, ShelleyBasedEra.SConway)(i.nes)
          .getOrElse(fail(s"the state is in ${EraNes.eraOf(i.era)}, not Conway"))

  test("a tick over several eras translates the state, one era at a time") {
    // a Babbage state about to enter Conway: the era schedule asks for it at the next epoch
    val nes = enterConway(babbage(conway(5, 10, 2)))
    // the accounts have no DRep and no pointer, the parameters of the next epoch are in force
    assertEquals(nes.epoch, ok(EpochNo.fromBigInt(6)))
    assertEquals(EraLedger.curPParams(nes.es.ls.utxo.gov).protocolMajor, BigInt(9))
    assertEquals(EraLedger.prevPParams(nes.es.ls.utxo.gov).protocolMajor, BigInt(8))
    assertEquals(nes.es.ls.cert.dState.accounts.states.size, 10)
    assert(nes.es.ls.cert.dState.accounts.states.valuesIterator.forall(_.drep.isEmpty))
    // the first Conway epoch is dormant: no proposals
    assertEquals(nes.es.ls.cert.vState.numDormantEpochs, EpochNo.fromUnsigned(1L))
  }

  test("the translation into Conway of 150,000 accounts runs on a 256 KiB stack") {
    val big = babbage(conway(5, 150000, 300))
    onSmallStack {
      val nes = enterConway(big)
      assertEquals(nes.es.ls.cert.dState.accounts.states.size, 150000)
      assertEquals(nes.es.snapshots.mark.activeStake.entries.size, 150000)
    }
  }

  test(
    "Shelley to Allegra removes the stashed AVVM outputs from the UTxO and returns them to the reserves"
  ) {
    import xyz.kd5ujc.scarab.ledger as node
    def output(n: Int, lovelace: BigInt): (node.TxIn, node.TxOut) =
      val address = node.Address
        .decode(Bytes.fromArray(0x61.toByte +: Array.fill[Byte](28)(n.toByte)))
        .fold(e => fail(e.toString), identity)
      node.TxIn(bytes(n, 32), BigInt(0)) -> TestLedger.out(address, lovelace)
    val redeem = Vector(output(1, 1000), output(2, 2500))
    val kept = output(3, 7)
    val shelley = NewEpochState.empty[Shelley](ok(EpochNo.fromBigInt(3)), TestLedger.params(2))
    val before = shelley.copy(
      es = shelley.es.copy(
        chainAccount = ChainAccountState(coin(0), coin(1000000)),
        ls = shelley.es.ls.copy[Shelley](utxo =
          shelley.es.ls.utxo.copy(utxo = UtxoTables(node.Utxo.fromMap((redeem :+ kept).toMap)))
        )
      ),
      avvm = UtxoTables(node.Utxo.fromMap(redeem.toMap))
    )
    val after = Translate.shelleyToAllegra(before).fold(e => fail(s"refused: $e"), identity)
    assertEquals(after.es.chainAccount.reserves, coin(1000000 + 3500))
    assertEquals(after.es.ls.utxo.utxo.utxo.toMap, Map(kept))
    assertEquals(after.avvm, ())
  }

  test("the hand-over from Byron: the UTxO without the empty outputs, the redeem outputs stashed") {
    import xyz.kd5ujc.scarab.ledger as node
    import xyz.kd5ujc.scarab.ledger.byron.{ByronAddress, ByronOut}
    val params = TestLedger.params(2)
    def in(n: Int) = node.TxIn(bytes(n, 32), BigInt(0))
    val key = Bytes.fromArray(Array.fill[Byte](32)(5))
    val redeem = ByronOut(ByronAddress.redeem(key, None), 3000)
    val ordinary = ByronOut(
      ByronAddress.make(ByronAddress.VerKey, Bytes.fromArray(Array.fill[Byte](64)(9)), None),
      500
    )
    val empty = ByronOut(ordinary.address, 0)
    val delegate = node.GenDelegPair(bytes(1, 28), bytes(2, 32))
    val context = FromByron(Map(bytes(7, 28) -> delegate), params, Supply)
    val nes = Handover
      .toShelley(
        context,
        ok(EpochNo.fromBigInt(1)),
        Map(in(1) -> redeem, in(2) -> ordinary, in(3) -> empty)
      )
      .fold(e => fail(s"refused: $e"), identity)
    assertEquals(nes.epoch, ok(EpochNo.fromBigInt(1)))
    assertEquals(nes.es.ls.utxo.utxo.utxo.keys.toSet, Set(in(1), in(2)))
    assertEquals(nes.avvm.utxo.keys.toSet, Set(in(1)))
    assertEquals(nes.es.chainAccount.reserves, coin(Supply - 3500))
    assertEquals(nes.es.chainAccount.treasury, coin(0))
    assertEquals(nes.es.ls.cert.dState.genDelegs.size, 1)
    assertEquals(nes.es.ls.cert.dState.accounts.states.size, 0)
    assertEquals(nes.es.snapshots, SnapShots.empty)
    // the parameters are the Shelley genesis': the cost per UTxO word is Alonzo's
    assertEquals(EraLedger.curPParams(nes.es.ls.utxo.gov).coinsPerUTxOWord, BigInt(0))
    assertEquals(nes.ru, None)
  }

  test("only the future parameters that are certain are adopted at the boundary") {
    val before = conway(5, 10, 2)
    val next = TestLedger.params(10).copy(maxTxSize = 20000)
    def withFuture(f: FuturePParams): NewEpochState[Conway] =
      before.copy(es =
        before.es.copy(ls =
          before.es.ls.copy[Conway](utxo =
            before.es.ls.utxo.copy(gov = before.es.ls.utxo.gov.copy(futurePParams = f))
          )
        )
      )
    def maxTx(nes: NewEpochState[Conway]) = nes.es.ls.utxo.gov.curPParams.maxTxSize
    val certain = tick(withFuture(FuturePParams.DefinitePParamsUpdate(next)), first(6) + 10)
    assertEquals(maxTx(certain), BigInt(20000))
    assertEquals(
      certain.es.ls.utxo.gov.prevPParams.maxTxSize,
      before.es.ls.utxo.gov.curPParams.maxTxSize
    )
    // a `Potential` update is solidified by a tick past the point of no return of its epoch
    val solid = tick(withFuture(FuturePParams.PotentialPParamsUpdate(Some(next))), first(6) - 100)
    assertEquals(
      solid.es.ls.utxo.gov.futurePParams,
      FuturePParams.DefinitePParamsUpdate(next)
    )
    assertEquals(maxTx(tick(solid, first(6) + 10)), BigInt(20000))
    // one that was not is not adopted: no block fell in the last two stability windows
    val unsure = tick(withFuture(FuturePParams.PotentialPParamsUpdate(Some(next))), first(6) + 10)
    assertEquals(maxTx(unsure), before.es.ls.utxo.gov.curPParams.maxTxSize)
  }

  test(
    "the rule is its own: RuleT and the reference stack (Kleisli over StateT over Either) agree"
  ) {
    // the interpreter is not part of the rule (design 2.2): Haskell's `ReaderT Globals` over
    // `StateT` over `Either` runs the same algebra
    type R[A] = Either[TickFailure, A]
    type S[A] = StateT[R, NewEpochState[Conway], A]
    type M[A] = Kleisli[S, Globals, A]
    given Monad[M] = Kleisli.catsDataMonadForKleisli[S, Globals]
    val start = conway(5, 30, 3)
    List(first(5) + 100, late(5), first(6) + 10).foldLeft(start) { (before, slot) =>
      val at = ok(SlotNo.fromBigInt(slot))
      val onRuleT = Sts(Tick.sts[Conway])(globals, (), before, at)
      val onStack = Tick
        .sts[Conway]
        .transition[M]((), at)(using summon, summon, summon, summon)
        .run(globals)
        .run(before)
        .map(_._1)
      assertEquals(onRuleT, onStack)
      onRuleT.getOrElse(fail("refused"))
    }
  }

  test(
    "MIR pays the registered accounts when both pots can pay, and clears the rewards either way"
  ) {
    val before = conway(5, 4, 2)
    val ls = before.es.ls
    def withMir(reserves: BigInt, deltaR: BigInt): NewEpochState[Conway] =
      val ir = InstantaneousRewards(
        Sorted.map(List(credential(0) -> coin(reserves), credential(77777) -> coin(1))),
        Sorted.empty,
        DeltaCoin(deltaR),
        DeltaCoin(0)
      )
      before.copy(es = before.es.copy(ls = EraLedger.withIRewards(ls, ir)))
    // Conway has no MIR rule; the Shelley rule is the same function over any era's state
    def mir(nes: NewEpochState[Conway]): EpochState[Conway] =
      Mir.step(nes.es).fold(e => fail(s"mir: $e"), identity)
    val paid = mir(withMir(1000, 0))
    assertEquals(
      paid.ls.cert.dState.accounts.states(credential(0)).balance.toBigInt,
      BigInt(1001000)
    )
    assertEquals(paid.chainAccount.reserves.value, before.es.chainAccount.reserves.value - 1000)
    assertEquals(paid.ls.cert.dState.iRewards.reserves.size, 0)
    // more than the reserves hold: nothing is paid, the request is cleared
    val tooMuch = mir(withMir(Supply, 0))
    assertEquals(tooMuch.chainAccount, before.es.chainAccount)
    assertEquals(tooMuch.ls.cert.dState.accounts, before.es.ls.cert.dState.accounts)
    assertEquals(tooMuch.ls.cert.dState.iRewards.reserves.size, 0)
  }
