// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.time.Instant
import cats.syntax.all.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Rational, Value as CborValue}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.{
  DelegFailure,
  EpochGeometry,
  GenDelegPair,
  Mismatch as FlatMismatch,
  PoolFailure,
  PreprodDeepTx,
  PredicateFailure,
  Pot,
  ProtocolParams,
  Ptr,
  TestGeometry,
  TestLedger
}
import xyz.kd5ujc.scarab.ledger.era.EraDelegsRun.{DelegsEra, Outcome}
import xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTestKit
import xyz.kd5ujc.scarab.ledger.era.rules.{
  DelegEnv,
  DelegsEnv,
  ShelleyDelegEvent,
  ShelleyDelegPredFailure
}
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Globals, RuleT}
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Babbage,
  Build,
  ChainAccountState,
  Coin,
  CompactCoin,
  Credential,
  DeltaCoin,
  Empty,
  EpochNo,
  EraTag,
  FutureGenDeleg,
  Genesis,
  GenesisDelegate,
  InstantaneousRewards,
  KeyHash,
  ShelleyBasedEra,
  ShelleyCertState,
  SlotNo,
  Sorted,
  StakePool,
  StakePoolParams,
  Staking,
  TxIx,
  VRFVerKeyHash,
  Word64
}

/** Shelley's DELEGS, DELPL, DELEG and POOL on the era types of `E` (cardano-ledger f649f975
  * `Shelley/Rules/{Delegs,Delpl,Deleg,Pool}.hs`) against the flat `ShelleyDelegs`, on crafted
  * certificates and states, for what the recorded corpora do not reach (`ShelleyDelegsCorpus` in
  * the node has those): each failure of DELEG and POOL, the state each certificate leaves for the
  * next whatever was recorded, MIR from both pots (transfers, insufficient funds, the slot too
  * late), genesis delegations (duplicates current and future, the slot window), the pointer's
  * narrowing at its boundaries and a long list of certificates on a small stack. Every judgment is
  * run both ways and compared whole: the failures in order, in the node's flat form, or the
  * certificate state after; what the era rules tell is checked against the certificates.
  */
abstract class ShelleyDelegsCases[E <: EraTag](name: String, major: Int)(using d: DelegsEra[E])
    extends FunSuite:
  private val t = d.t

  private def right[A](what: String)(e: Either[?, A]): A = e.fold(x => fail(s"$what: $x"), identity)

  private val g: Globals = TestLedger.globals
  // the parameters of a version of the era
  private val ppE: ProtocolParams = TestLedger.params(major)
  private val slot: SlotNo = SlotNo.fromUnsigned(1000L)
  private val epoch: EpochNo = right("epoch")(EpochNo.fromBigInt(g.epochOf(1000)))
  private val tooLate: BigInt = g.firstSlot(epoch.toBigInt + 1) - g.stabilityWindow
  private val account = ChainAccountState(Coin(BigInt(1000)), Coin(BigInt(5000)))
  private val empty: ShelleyCertState = Empty.certState[Babbage](using ShelleyBasedEra.SBabbage)

  private def bytes(n: Int, i: Int): Bytes = Bytes.fromArray(Array.fill(n)(i.toByte))
  private def cred(i: Int): Credential[Staking] =
    Credential.KeyHashObj[Staking](right("key")(KeyHash.fromBytes[Staking](bytes(28, i))))
  private def script(i: Int): Credential[Staking] =
    Credential.ScriptHashObj[Staking](
      right("script")(xyz.kd5ujc.scarab.ledger.state.ScriptHash.fromBytes(bytes(28, i)))
    )
  private def poolId(i: Int): KeyHash[StakePool] =
    right("pool")(KeyHash.fromBytes[StakePool](bytes(28, i)))
  private def genesis(i: Int): KeyHash[Genesis] =
    right("genesis")(KeyHash.fromBytes[Genesis](bytes(28, i)))
  private def delegate(i: Int): KeyHash[GenesisDelegate] =
    right("delegate")(KeyHash.fromBytes[GenesisDelegate](bytes(28, i + 100)))
  private def vrf(i: Int): VRFVerKeyHash = right("vrf")(VRFVerKeyHash.fromBytes(bytes(32, i)))

  private def params(
      i: Int,
      cost: BigInt = 340000000,
      network: Int = g.networkId.wire,
      vrfOf: Int = -1,
      metadataHash: Option[Int] = None
  ): StakePoolParams =
    Build.stakePoolParams(
      node.PoolParams(
        poolId(i).bytes,
        vrf(if vrfOf < 0 then i + 50 else vrfOf).bytes,
        BigInt(0),
        cost,
        Rational(1, 100),
        node.RewardAccount(network, node.Credential.Key(bytes(28, i + 60))),
        Set.empty,
        Bytes.empty,
        metadataHash.map(metadata)
      )
    )

  // a pool's metadata, its URL and its hash of `size` bytes
  private def metadata(size: Int): Bytes =
    Cbor
      .encode(
        CborValue.Arr(
          Vector(
            xyz.kd5ujc.scarab.codec.Node(CborValue.Text("u"), Bytes.empty),
            xyz.kd5ujc.scarab.codec.Node(CborValue.ByteString(bytes(size, 1)), Bytes.empty)
          )
        )
      )
      .fold(e => fail(e.toString), identity)

  private def reg(i: Int): TxCert[E] = t.mkRegTxCert(cred(i))
  private def unReg(i: Int): TxCert[E] = t.mkUnRegTxCert(cred(i))
  private def deleg(i: Int, pool: Int): TxCert[E] = t.mkDelegStakeTxCert(cred(i), poolId(pool))
  private def regPool(i: Int): TxCert[E] = t.mkRegPoolTxCert(params(i))
  private def retire(i: Int, e: BigInt): TxCert[E] =
    t.mkRetirePoolTxCert(poolId(i), right("epoch")(EpochNo.fromBigInt(e)))
  private def genDeleg(gk: Int, dk: Int, v: Int): TxCert[E] =
    t.mkGenesisDelegTxCert(GenesisDelegCert(genesis(gk), delegate(dk), vrf(v)))
  private def mir(pot: MIRPot, rewards: (Int, BigInt)*): TxCert[E] =
    t.mkMirTxCert(
      MIRCert(
        pot,
        MIRTarget.StakeAddressesMIR(rewards.map((i, n) => cred(i) -> DeltaCoin(n)).toMap)
      )
    )
  private def transfer(pot: MIRPot, n: BigInt): TxCert[E] =
    t.mkMirTxCert(MIRCert(pot, MIRTarget.SendToOppositePotMIR(Coin(n))))

  /** DELEGS both ways on `certs`, the two compared whole and what the era rules told checked. */
  private def judged(
      state: ShelleyCertState,
      certs: Vector[TxCert[E]],
      at: SlotNo = slot,
      txIx: TxIx = TxIx.minBound,
      pp: ProtocolParams = ppE,
      chain: ChainAccountState = account,
      globals: Globals = g,
      policy: RuleT.Policy = RuleT.Policy.Accumulate
  ): Outcome =
    val e = right("epoch")(EpochNo.fromBigInt(globals.epochOf(at.toBigInt)))
    val b = EraDelegsRun.both[E](globals, at, e, txIx, pp, chain, state, certs, policy)
    assertEquals(b.era, b.flat)
    b.told.foreach(told =>
      assertEquals(
        told,
        EraDelegsRun.expectedTold[E](e, state.pState.stakePools.keySet.map(_.bytes).toSet, certs)
      )
    )
    b.era

  private def accepted(o: Outcome): ShelleyCertState = o match
    case Outcome.Accepted(s) => s
    case other               => fail(s"refused: $other")

  private def refused(o: Outcome): List[PredicateFailure] = o match
    case Outcome.Refused(fs) => fs
    case other               => fail(s"accepted: $other")

  private def delegF(f: DelegFailure) = PredicateFailure.deleg(f)
  private def poolF(f: PoolFailure) = PredicateFailure.pool(f)
  private def back(c: Credential[Staking]) = Convert.back(c)

  // the state of two pools, three accounts delegating to them, built by the rules themselves
  private lazy val populated: ShelleyCertState =
    accepted(
      judged(
        empty,
        Vector(
          regPool(1),
          regPool(2),
          reg(1),
          reg(2),
          reg(3),
          deleg(1, 1),
          deleg(2, 1),
          deleg(3, 2)
        )
      )
    )

  test(s"$name: registrations and delegations, each account at its certificate's pointer") {
    val s = populated
    assertEquals(
      s.dState.accounts.ptrs.toVector,
      Vector(2, 3, 4).map(i => Ptr(1000, 0, i) -> cred(i - 1))
    )
    assertEquals(s.dState.accounts.states(cred(1)).pool, Some(poolId(1)))
    assertEquals(s.dState.accounts.states(cred(2)).deposit.toBigInt, ppE.keyDeposit)
    assertEquals(s.pState.stakePools(poolId(1)).delegators.toVector, Vector(cred(1), cred(2)))
    assertEquals(s.pState.stakePools(poolId(2)).delegators.toVector, Vector(cred(3)))
    // a delegation moves the account from its pool's delegators to the new pool's
    val moved = accepted(judged(s, Vector(deleg(1, 2), retire(1, epoch.toBigInt + 1))))
    assertEquals(moved.pState.stakePools(poolId(1)).delegators.toVector, Vector(cred(2)))
    assertEquals(moved.pState.stakePools(poolId(2)).delegators.toVector, Vector(cred(1), cred(3)))
    assertEquals(
      moved.pState.retiring.get(poolId(1)),
      Some(EpochNo.fromUnsigned(epoch.word.bits + 1))
    )
    // a script credential registers and delegates as a key's does
    accepted(
      judged(s, Vector(t.mkRegTxCert(script(9)), t.mkDelegStakeTxCert(script(9), poolId(2))))
    )
  }

  test(s"$name: each failure of DELEG and POOL, in the reference's order, the state going on") {
    val s = populated
    val failures = refused(
      judged(
        s,
        Vector(
          reg(1),
          deleg(7, 9),
          unReg(8),
          regPool(1),
          retire(9, epoch.toBigInt + 1),
          retire(2, epoch.toBigInt),
          retire(2, epoch.toBigInt + 19),
          regPool(5)
        ),
        pp = ppE.copy(minPoolCost = 340000001)
      )
    )
    // the zigzag of DELEGS's recursion on its prefix: the last certificate's block first
    assertEquals(failures.size, 9)
    assertEquals(
      failures.toSet,
      Set(
        delegF(DelegFailure.StakeKeyAlreadyRegistered(back(cred(1)))),
        delegF(DelegFailure.DelegateeNotRegistered(poolId(9).bytes)),
        delegF(DelegFailure.StakeDelegationImpossible(back(cred(7)))),
        delegF(DelegFailure.StakeKeyNotRegistered(back(cred(8)))),
        poolF(PoolFailure.StakePoolNotRegisteredOnKey(poolId(9).bytes)),
        poolF(
          PoolFailure.StakePoolRetirementWrongEpoch(
            FlatMismatch(epoch.toBigInt, epoch.toBigInt),
            FlatMismatch(epoch.toBigInt, epoch.toBigInt + 18)
          )
        ),
        poolF(
          PoolFailure.StakePoolRetirementWrongEpoch(
            FlatMismatch(epoch.toBigInt + 19, epoch.toBigInt),
            FlatMismatch(epoch.toBigInt + 19, epoch.toBigInt + 18)
          )
        ),
        poolF(PoolFailure.StakePoolCostTooLow(FlatMismatch(BigInt(340000000), BigInt(340000001))))
      )
    )
    // and the first failure alone
    val first = judged(s, Vector(reg(1), deleg(7, 9), unReg(8)), policy = RuleT.Policy.FirstFailure)
    assertEquals(refused(first).size, 1)
  }

  test(
    s"$name: POOL's checks of the network, the metadata hash and, from version 11, the VRF key"
  ) {
    val other = if g.networkId.wire == 0 then 1 else 0
    assertEquals(
      refused(judged(empty, Vector(t.mkRegPoolTxCert(params(6, network = other))))),
      List(
        poolF(PoolFailure.WrongNetworkPool(FlatMismatch(other, g.networkId.wire), poolId(6).bytes))
      )
    )
    assertEquals(
      refused(judged(empty, Vector(t.mkRegPoolTxCert(params(6, metadataHash = Some(33)))))),
      List(poolF(PoolFailure.PoolMedataHashTooBig(poolId(6).bytes, 33)))
    )
    accepted(judged(empty, Vector(t.mkRegPoolTxCert(params(6, metadataHash = Some(32))))))
    // a VRF key a pool registered earlier in the transaction, checked from version 11 only
    val twice = Vector(regPool(7), t.mkRegPoolTxCert(params(8, vrfOf = 57)))
    accepted(judged(empty, twice))
    assertEquals(
      refused(judged(empty, twice, pp = ppE.copy(protocolMajor = 11))),
      List(poolF(PoolFailure.VRFKeyHashAlreadyRegistered(poolId(8).bytes, vrf(57).bytes)))
    )
  }

  test(
    s"$name: a failed registration still registers the account again, which a later certificate sees"
  ) {
    val withBalance = populated.copy(dState =
      populated.dState.copy(accounts =
        populated.dState.accounts.copy(states =
          populated.dState.accounts.states.updatedWith(cred(1))(
            _.map(_.copy(balance = right("coin")(CompactCoin.fromBigInt(5))))
          )
        )
      )
    )
    // a balance refuses the unregistration, and the account is unregistered all the same
    assertEquals(
      refused(judged(withBalance, Vector(unReg(1)))),
      List(delegF(DelegFailure.StakeKeyHasNonZeroAccountBalance(5)))
    )
    assertEquals(
      refused(judged(withBalance, Vector(unReg(1), deleg(1, 1)))).toSet,
      Set(
        delegF(DelegFailure.StakeKeyHasNonZeroAccountBalance(5)),
        delegF(DelegFailure.StakeDelegationImpossible(back(cred(1))))
      )
    )
    // registering it again makes a fresh account at the new pointer, so the balance is gone; the
    // old pointer stays in the map
    assertEquals(
      refused(judged(withBalance, Vector(reg(1), unReg(1)))),
      List(delegF(DelegFailure.StakeKeyAlreadyRegistered(back(cred(1)))))
    )
    // the unregistration of a registered account with no balance, its pool forgetting it
    val gone = accepted(judged(populated, Vector(unReg(2))))
    assertEquals(gone.pState.stakePools(poolId(1)).delegators.toVector, Vector(cred(1)))
    assertEquals(gone.dState.accounts.ptrs.values.toSet, Set(cred(1), cred(3)))
  }

  // ---- genesis delegation

  private val withGenesis: ShelleyCertState =
    val pair = (dk: Int, v: Int) => GenDelegPair(delegate(dk).bytes, vrf(v).bytes)
    empty.copy(dState =
      empty.dState.copy(
        genDelegs = Sorted.map(
          Vector(genesis(1) -> pair(1, 1), genesis(2) -> pair(2, 2), genesis(3) -> pair(3, 3))
        ),
        futureGenDelegs = Sorted.map(
          Vector(
            FutureGenDeleg(SlotNo.fromUnsigned(1010L), genesis(3)) -> pair(13, 13),
            FutureGenDeleg(SlotNo.fromUnsigned(1011L), genesis(2)) -> pair(12, 12)
          )
        )
      )
    )

  test(
    s"$name: genesis delegations: an unknown key, a delegate or VRF key another holds now or later"
  ) {
    assertEquals(
      refused(judged(withGenesis, Vector(genDeleg(9, 20, 20)))),
      List(delegF(DelegFailure.GenesisKeyNotInMapping(genesis(9).bytes)))
    )
    assertEquals(
      refused(judged(withGenesis, Vector(genDeleg(1, 2, 20)))),
      List(delegF(DelegFailure.DuplicateGenesisDelegate(delegate(2).bytes)))
    )
    assertEquals(
      refused(judged(withGenesis, Vector(genDeleg(1, 20, 13)))),
      List(delegF(DelegFailure.DuplicateGenesisVRF(vrf(13).bytes)))
    )
    // both of a future delegation's keys, recorded in the reference's order (the flat rule's)
    assertEquals(
      refused(judged(withGenesis, Vector(genDeleg(1, 12, 12)))).toSet,
      Set(
        delegF(DelegFailure.DuplicateGenesisVRF(vrf(12).bytes)),
        delegF(DelegFailure.DuplicateGenesisDelegate(delegate(12).bytes))
      )
    )
    // a key's own pairs, current and future, are not the others': it may delegate to them again
    val again = accepted(judged(withGenesis, Vector(genDeleg(3, 13, 13), genDeleg(1, 1, 1))))
    val window = g.stabilityWindow.toLong
    assertEquals(
      again.dState.futureGenDelegs.keySet.toVector,
      Vector(
        FutureGenDeleg(SlotNo.fromUnsigned(1010L), genesis(3)),
        FutureGenDeleg(SlotNo.fromUnsigned(1011L), genesis(2)),
        FutureGenDeleg(SlotNo.fromUnsigned(1000L + window), genesis(1)),
        FutureGenDeleg(SlotNo.fromUnsigned(1000L + window), genesis(3))
      ).sortBy(f => (f.slot.toBigInt, f.genKeyHash.bytes.hex))
    )
    assertEquals(again.dState.genDelegs, withGenesis.dState.genDelegs)
  }

  test(
    s"$name: a genesis delegation at the last slot falls due where the reference's Word64 sum wraps"
  ) {
    // `slot +* Duration sp` wraps past 2^64 - 1; the flat rule refuses the slot it cannot hold. No
    // chain reaches the slot.
    val last = SlotNo.fromUnsigned(-1L)
    val e = right("epoch")(EpochNo.fromBigInt(g.epochOf(last.toBigInt)))
    val env = DelegsEnv[E](last, e, TxIx.minBound, d.pparams(ppE), d.tx(), account)
    val era = EraDelegsRun.era[E](
      g,
      env,
      d.certState.flip(withGenesis),
      Vector(genDeleg(1, 20, 20)),
      RuleT.Policy.Accumulate
    )
    val after = era match
      case Right(Right((s, _))) => d.certState(s)
      case other                => fail(s"$other")
    assert(
      after.dState.futureGenDelegs.contains(
        FutureGenDeleg(SlotNo.fromUnsigned(g.stabilityWindow.toLong - 1), genesis(1))
      )
    )
    EraDelegsRun.flat(
      g,
      EraDelegsRun.ctx(g, last, e, TxIx.minBound),
      ppE,
      account,
      withGenesis,
      Vector(BabbageTestKit.flatCertRep(d.rep.shelleyTxCert(genDeleg(1, 20, 20)))),
      RuleT.Policy.Accumulate
    ) match
      case Left(_) => ()
      case other   => fail(s"the flat rule held the slot: $other")
  }

  // ---- MIR

  private def withRewards(ir: InstantaneousRewards): ShelleyCertState =
    populated.copy(dState = populated.dState.copy(iRewards = ir))

  private val noRewards = empty.dState.iRewards

  test(
    s"$name: MIR to accounts from either pot, added to the epoch's earlier ones from Alonzo on"
  ) {
    val ir = noRewards.copy(
      reserves = Sorted.map(Vector(cred(1) -> Coin(100))),
      treasury = Sorted.map(Vector(cred(2) -> Coin(10))),
      deltaReserves = DeltaCoin(-50),
      deltaTreasury = DeltaCoin(50)
    )
    val s = withRewards(ir)
    val after = accepted(
      judged(
        s,
        Vector(
          mir(MIRPot.ReservesMIR, 1 -> 30, 2 -> 40),
          mir(MIRPot.TreasuryMIR, 2 -> -5, 3 -> 1000)
        )
      )
    )
    assertEquals(
      after.dState.iRewards.reserves,
      Sorted.map(Vector(cred(1) -> Coin(130), cred(2) -> Coin(40)))
    )
    assertEquals(
      after.dState.iRewards.treasury,
      Sorted.map(Vector(cred(2) -> Coin(5), cred(3) -> Coin(1000)))
    )
    // the reserves and their delta are what a pot may pay: 5000 - 50
    assertEquals(
      refused(judged(s, Vector(mir(MIRPot.ReservesMIR, 1 -> 4851)))),
      List(
        delegF(
          DelegFailure.InsufficientForInstantaneousRewards(Pot.Reserves, FlatMismatch(4951, 4950))
        )
      )
    )
    assert(judged(s, Vector(mir(MIRPot.ReservesMIR, 1 -> 4850))).isInstanceOf[Outcome.Accepted])
    // an entry made negative; the signed entries stay in the state for the next certificate
    val negative = refused(
      judged(s, Vector(mir(MIRPot.ReservesMIR, 1 -> -101), mir(MIRPot.ReservesMIR, 1 -> 1)))
    )
    assertEquals(negative, List(delegF(DelegFailure.MIRProducesNegativeUpdate)))
    // the pot's own when more is drawn than it has after a negative entry, both failures recorded
    assertEquals(
      refused(judged(s, Vector(mir(MIRPot.TreasuryMIR, 2 -> -20, 4 -> 2000)))).toSet,
      Set(
        delegF(DelegFailure.MIRProducesNegativeUpdate),
        delegF(
          DelegFailure.InsufficientForInstantaneousRewards(Pot.Treasury, FlatMismatch(1990, 1050))
        )
      )
    )
  }

  test(s"$name: MIR transfers between the pots, against what each has after the epoch's rewards") {
    val ir = noRewards.copy(
      reserves = Sorted.map(Vector(cred(1) -> Coin(1000))),
      deltaTreasury = DeltaCoin(7)
    )
    val s = withRewards(ir)
    val moved = accepted(
      judged(s, Vector(transfer(MIRPot.ReservesMIR, 4000), transfer(MIRPot.TreasuryMIR, 3)))
    )
    assertEquals(moved.dState.iRewards.deltaReserves, DeltaCoin(-3997))
    assertEquals(moved.dState.iRewards.deltaTreasury, DeltaCoin(4004))
    // the reserves less their rewards: 5000 - 1000
    assertEquals(
      refused(judged(s, Vector(transfer(MIRPot.ReservesMIR, 4001)))),
      List(delegF(DelegFailure.InsufficientForTransfer(Pot.Reserves, FlatMismatch(4001, 4000))))
    )
    assertEquals(
      refused(judged(s, Vector(transfer(MIRPot.TreasuryMIR, 1008)))),
      List(delegF(DelegFailure.InsufficientForTransfer(Pot.Treasury, FlatMismatch(1008, 1007))))
    )
    assertEquals(
      refused(judged(s, Vector(transfer(MIRPot.TreasuryMIR, -1)))),
      List(delegF(DelegFailure.MIRNegativeTransfer(Pot.Treasury, -1)))
    )
  }

  test(s"$name: MIR below version 5: no transfer, no negative entry, a later entry replacing one") {
    val pp4 = ppE.copy(protocolMajor = 4)
    val s = withRewards(noRewards.copy(reserves = Sorted.map(Vector(cred(1) -> Coin(100)))))
    assertEquals(
      refused(judged(s, Vector(transfer(MIRPot.ReservesMIR, 1)), pp = pp4)),
      List(delegF(DelegFailure.MIRTransferNotCurrentlyAllowed))
    )
    assertEquals(
      refused(judged(s, Vector(mir(MIRPot.ReservesMIR, 2 -> -1)), pp = pp4)),
      List(delegF(DelegFailure.MIRNegativesNotCurrentlyAllowed))
    )
    val replaced = accepted(judged(s, Vector(mir(MIRPot.ReservesMIR, 1 -> 7)), pp = pp4))
    assertEquals(replaced.dState.iRewards.reserves, Sorted.map(Vector(cred(1) -> Coin(7))))
    assertEquals(
      refused(judged(s, Vector(mir(MIRPot.ReservesMIR, 1 -> 5001)), pp = pp4)),
      List(
        delegF(
          DelegFailure.InsufficientForInstantaneousRewards(Pot.Reserves, FlatMismatch(5001, 5000))
        )
      )
    )
  }

  test(s"$name: below version 5 a pot pays from itself alone, without the epoch's transfers") {
    // `(Map.union credCoinMap' instantaneousRewards, potAmount)` (`Shelley/Rules/Deleg.hs:358-360`,
    // cardano-ledger f649f975): the delta is not counted. The flat rule counts it; no chain has a
    // delta below version 5, where no transfer is allowed and the deltas start each epoch at zero.
    val pp4 = ppE.copy(protocolMajor = 4)
    val s = withRewards(noRewards.copy(deltaReserves = DeltaCoin(100)))
    val env = DelegsEnv[E](slot, epoch, TxIx.minBound, d.pparams(pp4), d.tx(), account)
    EraDelegsRun.era[E](
      g,
      env,
      d.certState.flip(s),
      Vector(mir(MIRPot.ReservesMIR, 1 -> 5050)),
      RuleT.Policy.Accumulate
    ) match
      case Right(Left(fs)) =>
        assertEquals(
          fs.toList.map(EraDelegsRun.flatOf[E]),
          List(
            delegF(
              DelegFailure.InsufficientForInstantaneousRewards(
                Pot.Reserves,
                FlatMismatch(5050, 5000)
              )
            )
          )
        )
      case other => fail(s"$other")
  }

  test(s"$name: a MIR certificate from the stability window before the next epoch is too late") {
    val late = right("slot")(SlotNo.fromBigInt(tooLate))
    assertEquals(
      refused(judged(populated, Vector(mir(MIRPot.ReservesMIR, 1 -> 1)), at = late)),
      List(delegF(DelegFailure.MIRCertificateTooLateinEpoch(FlatMismatch(tooLate, tooLate))))
    )
    val early = right("slot")(SlotNo.fromBigInt(tooLate - 1))
    accepted(
      judged(
        populated,
        Vector(mir(MIRPot.ReservesMIR, 1 -> 1), transfer(MIRPot.TreasuryMIR, 1)),
        at = early
      )
    )
    // a window longer than the epoch: the first slot less the window is slot 0 (`*-`, monus), so
    // every slot is too late
    val short = Globals(
      g.ledger,
      EpochGeometry(Instant.EPOCH, 1000, 100, TestGeometry.k(5), TestGeometry.f("0.05"))
    )
    assertEquals(
      refused(
        judged(
          populated,
          Vector(mir(MIRPot.ReservesMIR, 1 -> 1)),
          at = SlotNo.fromUnsigned(0L),
          globals = short
        )
      ),
      List(delegF(DelegFailure.MIRCertificateTooLateinEpoch(FlatMismatch(0, 0))))
    )
  }

  test(
    s"$name: the MIR cutoff is the next epoch's first slot cut to 64 bits, less the window by monus"
  ) {
    // epochs of 16 slots and a window of 3: at slot 2^64 - 16 the next epoch's first slot is 2^64,
    // which the reference's Word64 product wraps to slot 0 (`fixedEpochInfoFirst`), so the cutoff
    // is 0 and the certificate is too late; an unbounded first slot would make it 2^64 - 3
    val geometry = EpochGeometry
      .checked(Instant.EPOCH, 1000L, 16L, TestGeometry.k(1), TestGeometry.f("1"))
      .fold(e => fail(e.message), identity)
    val wrapping = Globals(g.ledger, geometry)
    assertEquals(wrapping.stabilityWindow, BigInt(3))
    val at = right("slot")(SlotNo.fromBigInt((BigInt(1) << 64) - 16))
    val e = right("epoch")(EpochNo.fromBigInt(wrapping.epochOf(at.toBigInt)))
    assertEquals(e.toBigInt, (BigInt(1) << 60) - 1)
    val env = DelegsEnv[E](at, e, TxIx.minBound, d.pparams(ppE), d.tx(), account)
    EraDelegsRun.era[E](
      wrapping,
      env,
      d.certState.flip(populated),
      Vector(mir(MIRPot.ReservesMIR, 1 -> 1)),
      RuleT.Policy.Accumulate
    ) match
      case Right(Left(fs)) =>
        assertEquals(
          fs.toList.map(EraDelegsRun.flatOf[E]),
          List(
            delegF(
              DelegFailure.MIRCertificateTooLateinEpoch(FlatMismatch((BigInt(1) << 64) - 16, 0))
            )
          )
        )
      case other => fail(s"$other")
    // the epoch before, whose next first slot fits: the cutoff 2^64 - 19, so 2^64 - 32 is in time
    val earlier = right("slot")(SlotNo.fromBigInt((BigInt(1) << 64) - 32))
    assert(
      judged(populated, Vector(mir(MIRPot.ReservesMIR, 1 -> 1)), at = earlier, globals = wrapping)
        .isInstanceOf[Outcome.Accepted]
    )
  }

  test(s"$name: DELEG alone refuses a pool's certificate, which DELPL never gives it") {
    val env = DelegEnv[E](slot, epoch, Ptr(1000, 0, 0), account, d.pparams(ppE))
    Vector(regPool(1), retire(1, epoch.toBigInt + 1)).foreach { c =>
      assertEquals(
        EraDelegsRun.deleg[E](g, env, d.certState.flip(populated), c).map(_.left.map(_.toList)),
        Right(Left(List(ShelleyDelegPredFailure.WrongCertificateTypeDELEG[E]())))
      )
    }
    // and tells the next epoch before a MIR certificate's slot is checked
    EraDelegsRun
      .deleg[E](g, env, d.certState.flip(populated), mir(MIRPot.ReservesMIR, 1 -> 1)) match
      case Right(Right((_, told))) =>
        assertEquals(
          told.toList,
          List(ShelleyDelegEvent.DelegNewEpoch[E](EpochNo.fromUnsigned(epoch.word.bits + 1)))
        )
      case other => fail(s"$other")
  }

  // ---- the pointer

  test(
    s"$name: the pointer's slot is the low 32 bits of the slot, its index the low 16 of the position"
  ) {
    val cases = Vector(
      BigInt(0xffffffffL) -> Ptr(BigInt(0xffffffffL), 0, 0),
      (BigInt(1) << 32) -> Ptr(0, 0, 0),
      ((BigInt(1) << 32) + 7) -> Ptr(7, 0, 0),
      ((BigInt(1) << 64) - 1) -> Ptr(BigInt(0xffffffffL), 0, 0)
    )
    cases.foreach { (n, ptr) =>
      val s = right("slot")(SlotNo.fromBigInt(n))
      val after = accepted(judged(empty, Vector(reg(1)), at = s, txIx = TxIx.maxBound))
      assertEquals(after.dState.accounts.ptrs.keySet.toVector, Vector(ptr.copy(txIx = 65535)))
      assertEquals(Ptr.ofCertificate(n, 65535, 0), ptr.copy(txIx = 65535))
    }
  }

  test(
    s"$name: 65,538 certificates on a small stack, the 65,537th's index wrapping to the first's"
  ) {
    val n = 65537
    val slotOf = SlotNo.fromUnsigned((1L << 32) + 5)
    val certs = Vector.tabulate(n)(i => t.mkRegTxCert(wide(i))) :+ t.mkUnRegTxCert(wide(0))
    val after = PreprodDeepTx.onSmallStack(
      accepted(
        judged(empty, certs, at = slotOf, txIx = TxIx.txIxFromIntegral(7).getOrElse(fail("ix")))
      )
    )
    // the last registration's pointer is the first's (`Ptr 5 7 0`): it took the first's entry,
    // which the unregistration of the first then removed
    assertEquals(after.dState.accounts.states.size, n - 1)
    assertEquals(after.dState.accounts.states(wide(n - 1)).ptr, Ptr(5, 7, 0))
    assertEquals(after.dState.accounts.ptrs.size, 65535)
    assert(!after.dState.accounts.ptrs.contains(Ptr(5, 7, 0)))
    assertEquals(after.dState.accounts.ptrs.get(Ptr(5, 7, 65535)), Some(wide(65535)))
  }

  test(
    s"$name: 30,000 failing certificates on a small stack, every failure in the flat rule's order"
  ) {
    val certs = Vector.tabulate(30000)(i => t.mkUnRegTxCert(wide(i)))
    val failures = PreprodDeepTx.onSmallStack(refused(judged(empty, certs)))
    assertEquals(failures.size, 30000)
    assertEquals(failures.toSet.size, 30000)
  }

  private def wide(i: Int): Credential[Staking] =
    Credential.KeyHashObj[Staking](
      right("key")(
        KeyHash.fromBytes[Staking](
          Bytes.fromArray(
            Array.fill(24)(0.toByte) ++ java.nio.ByteBuffer.allocate(4).putInt(i).array()
          )
        )
      )
    )

/** Shelley's certificate rules on Babbage's era types. */
class BabbageDelegsSuite extends ShelleyDelegsCases[Babbage]("Babbage", 8):
  test("the evidence that a Babbage certificate is Shelley's is made in its file alone") {
    // no proof of the equality outside the file, and the class can be neither made nor implemented
    val made = compileErrors("IsShelleyTxCert.of[xyz.kd5ujc.scarab.ledger.state.Babbage]")
    assert(made.contains("Cannot prove"), made)
    val constructed =
      compileErrors("new IsShelleyTxCert[xyz.kd5ujc.scarab.ledger.state.Babbage](summon)")
    assert(constructed.contains("private"), constructed)
    val implemented = compileErrors(
      "new IsShelleyTxCert[xyz.kd5ujc.scarab.ledger.state.Babbage] { override def shelleyTxCert(c: babbage.BabbageTxCert): ShelleyTxCertRep = ??? }"
    )
    assert(implemented.contains("final"), implemented)
  }

  test(
    "Babbage's injections into DELEG, POOL, DELPL and DELEGS, each failure nested as the reference's"
  ) {
    import xyz.kd5ujc.scarab.ledger.era.rules.*
    def ok[A](e: Either[?, A]): A = e.fold(x => fail(s"$x"), identity)
    val cred = Credential.KeyHashObj[Staking](
      ok(KeyHash.fromBytes[Staking](Bytes.fromArray(Array.fill(28)(1.toByte))))
    )
    val pool = ok(KeyHash.fromBytes[StakePool](Bytes.fromArray(Array.fill(28)(2.toByte))))
    val deleg = ShelleyDelegPredFailure.StakeKeyNotRegisteredDELEG[Babbage](cred)
    val poolFailure = ShelleyPoolPredFailure.StakePoolNotRegisteredOnKeyPOOL[Babbage](pool)
    val inDelpl = ShelleyDelplPredFailure.DelegFailure[Babbage](deleg)
    val poolInDelpl = ShelleyDelplPredFailure.PoolFailure[Babbage](poolFailure)
    val delegs = ShelleyDelegsPredFailure.DelplFailure[Babbage](inDelpl)
    val registered = PoolEvent.RegisterPool[Babbage](pool)
    val newEpoch = ShelleyDelegEvent.DelegNewEpoch[Babbage](EpochNo.fromUnsigned(3L))
    // DELEG and POOL: the identities, POOL's event too
    assertEquals(
      summon[InjectDelegFailure[ShelleyDelegPredFailure[Babbage], Babbage]].injectFailure(deleg),
      deleg
    )
    assertEquals(summon[InjectPool[Babbage]].injectFailure(poolFailure), poolFailure)
    assertEquals(summon[InjectPool[Babbage]].injectEvent(registered), registered)
    // DELPL: its own, POOL's as `PoolFailure`, DELEG's as `DelegFailure`
    assertEquals(
      summon[InjectDelplFailure[ShelleyDelplPredFailure[Babbage], Babbage]].injectFailure(inDelpl),
      inDelpl
    )
    assertEquals(
      summon[InjectDelplFailure[ShelleyPoolPredFailure[Babbage], Babbage]]
        .injectFailure(poolFailure),
      poolInDelpl
    )
    assertEquals(
      summon[InjectDelplFailure[ShelleyDelegPredFailure[Babbage], Babbage]].injectFailure(deleg),
      inDelpl
    )
    // DELEGS: its own, and DELPL's and the rules' below it wrapped in `DelplFailure`
    assertEquals(
      summon[InjectDelegsFailure[ShelleyDelegsPredFailure[Babbage], Babbage]].injectFailure(delegs),
      delegs
    )
    assertEquals(
      summon[InjectDelegsFailure[ShelleyDelplPredFailure[Babbage], Babbage]].injectFailure(inDelpl),
      delegs
    )
    assertEquals(
      summon[InjectDelegsFailure[ShelleyDelegPredFailure[Babbage], Babbage]].injectFailure(deleg),
      delegs
    )
    assertEquals(
      summon[InjectDelegsFailure[ShelleyPoolPredFailure[Babbage], Babbage]]
        .injectFailure(poolFailure),
      ShelleyDelegsPredFailure.DelplFailure[Babbage](poolInDelpl)
    )
    // the embeddings the rules wrap their sub-rules' failures and events with
    assertEquals(EmbedInDelpl.babbage.wrapDelegFailed(deleg), inDelpl)
    assertEquals(EmbedInDelpl.babbage.wrapPoolFailed(poolFailure), poolInDelpl)
    assertEquals(
      EmbedInDelpl.babbage.wrapPoolEvent(registered),
      ShelleyDelplEvent.PoolEvent[Babbage](registered)
    )
    assertEquals(
      EmbedInDelpl.babbage.wrapDelegEvent(newEpoch),
      ShelleyDelplEvent.DelegEvent[Babbage](newEpoch)
    )
    assertEquals(EmbedDelplInDelegs.babbage.wrapFailed(inDelpl), delegs)
    assertEquals(
      EmbedDelplInDelegs.babbage.wrapEvent(ShelleyDelplEvent.DelegEvent[Babbage](newEpoch)),
      ShelleyDelegsEvent.DelplEvent[Babbage](ShelleyDelplEvent.DelegEvent[Babbage](newEpoch))
    )
  }

  test(
    "a Babbage certificate is not its representation outside its file, nor the representation one"
  ) {
    val toRep = compileErrors("def f(c: babbage.BabbageTxCert): ShelleyTxCertRep = c")
    assert(toRep.contains("Found:"), toRep)
    val fromRep = compileErrors("def f(c: ShelleyTxCertRep): babbage.BabbageTxCert = c")
    assert(fromRep.contains("Found:"), fromRep)
    // the evidence reads it
    assertEquals(
      compileErrors(
        "def f(c: babbage.BabbageTxCert): ShelleyTxCertRep = summon[IsShelleyTxCert[xyz.kd5ujc.scarab.ledger.state.Babbage]].shelleyTxCert(c)"
      ),
      ""
    )
  }

/** Shelley's certificate rules on Alonzo's era types. */
class AlonzoDelegsSuite extends ShelleyDelegsCases[Alonzo]("Alonzo", 6):
  test("the evidence that an Alonzo certificate is Shelley's is made in its file alone") {
    // no proof of the equality outside the file, and the class can be neither made nor implemented
    val made = compileErrors("IsShelleyTxCert.of[xyz.kd5ujc.scarab.ledger.state.Alonzo]")
    assert(made.contains("Cannot prove"), made)
    val constructed =
      compileErrors("new IsShelleyTxCert[xyz.kd5ujc.scarab.ledger.state.Alonzo](summon)")
    assert(constructed.contains("private"), constructed)
    val implemented = compileErrors(
      "new IsShelleyTxCert[xyz.kd5ujc.scarab.ledger.state.Alonzo] { override def shelleyTxCert(c: alonzo.AlonzoTxCert): ShelleyTxCertRep = ??? }"
    )
    assert(implemented.contains("final"), implemented)
  }

  test(
    "Alonzo's injections into DELEG, POOL, DELPL and DELEGS, each failure nested as the reference's"
  ) {
    import xyz.kd5ujc.scarab.ledger.era.rules.*
    def ok[A](e: Either[?, A]): A = e.fold(x => fail(s"$x"), identity)
    val cred = Credential.KeyHashObj[Staking](
      ok(KeyHash.fromBytes[Staking](Bytes.fromArray(Array.fill(28)(1.toByte))))
    )
    val pool = ok(KeyHash.fromBytes[StakePool](Bytes.fromArray(Array.fill(28)(2.toByte))))
    val deleg = ShelleyDelegPredFailure.StakeKeyNotRegisteredDELEG[Alonzo](cred)
    val poolFailure = ShelleyPoolPredFailure.StakePoolNotRegisteredOnKeyPOOL[Alonzo](pool)
    val inDelpl = ShelleyDelplPredFailure.DelegFailure[Alonzo](deleg)
    val poolInDelpl = ShelleyDelplPredFailure.PoolFailure[Alonzo](poolFailure)
    val delegs = ShelleyDelegsPredFailure.DelplFailure[Alonzo](inDelpl)
    val registered = PoolEvent.RegisterPool[Alonzo](pool)
    val newEpoch = ShelleyDelegEvent.DelegNewEpoch[Alonzo](EpochNo.fromUnsigned(3L))
    // DELEG and POOL: the identities, POOL's event too
    assertEquals(
      summon[InjectDelegFailure[ShelleyDelegPredFailure[Alonzo], Alonzo]].injectFailure(deleg),
      deleg
    )
    assertEquals(summon[InjectPool[Alonzo]].injectFailure(poolFailure), poolFailure)
    assertEquals(summon[InjectPool[Alonzo]].injectEvent(registered), registered)
    // DELPL: its own, POOL's as `PoolFailure`, DELEG's as `DelegFailure`
    assertEquals(
      summon[InjectDelplFailure[ShelleyDelplPredFailure[Alonzo], Alonzo]].injectFailure(inDelpl),
      inDelpl
    )
    assertEquals(
      summon[InjectDelplFailure[ShelleyPoolPredFailure[Alonzo], Alonzo]]
        .injectFailure(poolFailure),
      poolInDelpl
    )
    assertEquals(
      summon[InjectDelplFailure[ShelleyDelegPredFailure[Alonzo], Alonzo]].injectFailure(deleg),
      inDelpl
    )
    // DELEGS: its own, and DELPL's and the rules' below it wrapped in `DelplFailure`
    assertEquals(
      summon[InjectDelegsFailure[ShelleyDelegsPredFailure[Alonzo], Alonzo]].injectFailure(delegs),
      delegs
    )
    assertEquals(
      summon[InjectDelegsFailure[ShelleyDelplPredFailure[Alonzo], Alonzo]].injectFailure(inDelpl),
      delegs
    )
    assertEquals(
      summon[InjectDelegsFailure[ShelleyDelegPredFailure[Alonzo], Alonzo]].injectFailure(deleg),
      delegs
    )
    assertEquals(
      summon[InjectDelegsFailure[ShelleyPoolPredFailure[Alonzo], Alonzo]]
        .injectFailure(poolFailure),
      ShelleyDelegsPredFailure.DelplFailure[Alonzo](poolInDelpl)
    )
    // the embeddings the rules wrap their sub-rules' failures and events with
    assertEquals(EmbedInDelpl.alonzo.wrapDelegFailed(deleg), inDelpl)
    assertEquals(EmbedInDelpl.alonzo.wrapPoolFailed(poolFailure), poolInDelpl)
    assertEquals(
      EmbedInDelpl.alonzo.wrapPoolEvent(registered),
      ShelleyDelplEvent.PoolEvent[Alonzo](registered)
    )
    assertEquals(
      EmbedInDelpl.alonzo.wrapDelegEvent(newEpoch),
      ShelleyDelplEvent.DelegEvent[Alonzo](newEpoch)
    )
    assertEquals(EmbedDelplInDelegs.alonzo.wrapFailed(inDelpl), delegs)
    assertEquals(
      EmbedDelplInDelegs.alonzo.wrapEvent(ShelleyDelplEvent.DelegEvent[Alonzo](newEpoch)),
      ShelleyDelegsEvent.DelplEvent[Alonzo](ShelleyDelplEvent.DelegEvent[Alonzo](newEpoch))
    )
  }

  test(
    "an Alonzo certificate is not its representation outside its file, nor the representation one"
  ) {
    val toRep = compileErrors("def f(c: alonzo.AlonzoTxCert): ShelleyTxCertRep = c")
    assert(toRep.contains("Found:"), toRep)
    val fromRep = compileErrors("def f(c: ShelleyTxCertRep): alonzo.AlonzoTxCert = c")
    assert(fromRep.contains("Found:"), fromRep)
    // the evidence reads it
    assertEquals(
      compileErrors(
        "def f(c: alonzo.AlonzoTxCert): ShelleyTxCertRep = summon[IsShelleyTxCert[xyz.kd5ujc.scarab.ledger.state.Alonzo]].shelleyTxCert(c)"
      ),
      ""
    )
  }
