// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Monad, Order}
import cats.data.Chain
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{GenDelegPair, LedgerDefect, Ptr}
import xyz.kd5ujc.scarab.ledger.era.{
  EraAccounts,
  EraCertState,
  EraPParams,
  GenesisDelegCert,
  IsShelleyTxCert,
  MIRCert,
  MIRPot,
  MIRTarget,
  Mismatch,
  PParams,
  ShelleyEraAccounts,
  ShelleyEraTxCertToBabbage,
  ShelleyTxCertRep,
  Tx,
  TxCert,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Sts, focus}
import xyz.kd5ujc.scarab.ledger.state.{
  Accounts,
  CertState,
  ChainAccountState,
  Coin,
  Credential,
  DState,
  DeltaCoin,
  EpochInterval,
  EpochNo,
  EraTag,
  FutureGenDeleg,
  InstantaneousRewards,
  KeyHash,
  SlotNo,
  StakePool,
  Staking,
  TxIx,
  Word64,
  addInterval
}

// Shelley's certificate rules as the reference writes them (cardano-ledger f649f975
// `eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/{Delegs,Delpl,Deleg}.hs`), the rules of the
// certificates of every era up to Babbage, each transition generic in its era over the reference's
// classes: the certificate state's (`EraCertState`), the accounts' with their pointers
// (`ShelleyEraAccounts`) and the certificates' of the eras up to Babbage
// (`ShelleyEraTxCertToBabbage`). POOL is the rule Conway runs too (`Certs.scala`). Each rule records
// the reference's own failures (`DelegsFailures.scala`), a sub-rule's embedded in its caller's as
// the reference's `Embed` instances do. POOL raises a defect (`LedgerDefect`) for parameters of
// another layout than the state's; DELEG raises none.

/** `DelegsEnv` (`Shelley/Rules/Delegs.hs:66-74`, cardano-ledger f649f975): the slot, the epoch, the
  * transaction's position in its block, the parameters, the transaction, which the rule does not
  * read at the pin, and the chain's accounts.
  */
final case class DelegsEnv[E <: EraTag](
    slot: SlotNo,
    epoch: EpochNo,
    txIx: TxIx,
    pp: PParams[E],
    tx: Tx[TxLevel.Top, E],
    account: ChainAccountState
)

/** `DelplEnv` (`Shelley/Rules/Delpl.hs:52-58`, cardano-ledger f649f975): DELEGS' environment with
  * the pointer of the certificate.
  */
final case class DelplEnv[E <: EraTag](
    slot: SlotNo,
    epoch: EpochNo,
    ptr: Ptr,
    pp: PParams[E],
    account: ChainAccountState
)

/** `DelegEnv` (`Shelley/Rules/Deleg.hs:73-80`, cardano-ledger f649f975). */
final case class DelegEnv[E <: EraTag](
    slot: SlotNo,
    curEpochNo: EpochNo,
    ptr: Ptr,
    account: ChainAccountState,
    pp: PParams[E]
)

/** DELEGS (`Shelley/Rules/Delegs.hs`, cardano-ledger f649f975): a transaction's certificates. */
object DELEGS:
  /** `delegsTransition` (`Shelley/Rules/Delegs.hs:169-197`, cardano-ledger f649f975): by recursion
    * on the prefix (`gamma :|> txCert`), DELEGS of all but the last certificate as a sub-rule, then
    * DELPL of the last with the pointer of its position, so the first certificate is the deepest;
    * the empty sequence leaves the state as it is. The recursion is a fold, each step a program
    * over the previous, which the interpreter runs in constant stack, as Conway's CERTS is
    * ([[CERTS.conwayCertsTransition]]). The pointer is `Ptr (SlotNo32 (fromIntegral @Word64 @Word32
    * slot64)) txIx certIx` with `certIx` the prefix's length as a `Word16` (`:193-195`): the slot
    * cut to its low 32 bits and the index to its low 16, which [[Ptr.ofCertificate]] makes, the
    * flat DELEGS's pointer.
    */
  def delegsTransition[E <: EraTag, F[_]: Monad](
      env: DelegsEnv[E],
      certificates: Vector[TxCert[E]]
  )(using
      rep: IsShelleyTxCert[E],
      p: EraPParams[E],
      c: EraCertState[E],
      a: ShelleyEraAccounts[E],
      t: ShelleyEraTxCertToBabbage[E],
      embed: EmbedDelplInDelegs[E],
      embedDelpl: EmbedInDelpl[E],
      pool: InjectPool[E],
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[E]],
      defect: Raise[F, LedgerDefect],
      judge: Judge[F, ShelleyDelegsPredFailure[E]],
      events: Tell[F, Chain[ShelleyDelegsEvent[E]]]
  ): F[Unit] =
    certificates.zipWithIndex.foldLeft(Monad[F].unit) { case (gamma, (txCert, certIx)) =>
      val ptr = Ptr.ofCertificate(env.slot.toBigInt, env.txIx.index, certIx)
      judge.nest(gamma) *>
        judge.nest(
          DELPL.delplTransition[E, F](
            DelplEnv[E](env.slot, env.epoch, ptr, env.pp, env.account),
            txCert
          )(using
            summon[Monad[F]],
            rep,
            p,
            c,
            a,
            t,
            embedDelpl,
            pool,
            ask,
            st,
            defect,
            wrapped(judge)(embed.wrapFailed),
            told(events)(embed.wrapEvent)
          )
        )
    }

/** DELPL (`Shelley/Rules/Delpl.hs`, cardano-ledger f649f975): one certificate, to the rule of its
  * kind. The era's certificate is Shelley's (`TxCert era ~ ShelleyTxCert era`), which `rep` reads
  * ([[xyz.kd5ujc.scarab.ledger.era.IsShelleyTxCert]]): the era's certificate file alone gives that
  * evidence, and it goes one way, so no other code makes a certificate of the era from the
  * representation.
  */
object DELPL:
  /** `delplTransition` (`Shelley/Rules/Delpl.hs:174-203`, cardano-ledger f649f975): POOL on the
    * pool state alone (`certPStateL`) for a pool's certificate, DELEG on the certificate state for
    * the others, each a sub-rule whose failures and events DELPL's embed ([[EmbedInDelpl]]).
    */
  def delplTransition[E <: EraTag, F[_]: Monad](env: DelplEnv[E], c: TxCert[E])(using
      rep: IsShelleyTxCert[E],
      p: EraPParams[E],
      cs: EraCertState[E],
      a: ShelleyEraAccounts[E],
      t: ShelleyEraTxCertToBabbage[E],
      embed: EmbedInDelpl[E],
      pool: InjectPool[E],
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[E]],
      defect: Raise[F, LedgerDefect],
      judge: Judge[F, ShelleyDelplPredFailure[E]],
      events: Tell[F, Chain[ShelleyDelplEvent[E]]]
  ): F[Unit] =
    def deleg: F[Unit] =
      judge.nest(
        ShelleyDELEG.delegationTransition[E, F](
          DelegEnv[E](env.slot, env.epoch, env.ptr, env.account, env.pp),
          c
        )(using
          summon[Monad[F]],
          cs,
          a,
          t,
          p,
          ask,
          st,
          wrapped(judge)(embed.wrapDelegFailed),
          told(events)(embed.wrapDelegEvent)
        )
      )
    rep.shelleyTxCert(c) match
      case ShelleyTxCertRep.ShelleyTxCertPool(poolCert) =>
        judge.nest(
          POOL.poolTransition[E, F](PoolEnv[E](env.epoch, env.pp), poolCert)(using
            summon[Monad[F]],
            p,
            pool,
            ask,
            st.focus(cs.certPStateL),
            defect,
            wrapped(judge)(embed.wrapPoolFailed),
            told(events)(embed.wrapPoolEvent)
          )
        )
      case ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(_) => deleg
      case ShelleyTxCertRep.ShelleyTxCertDelegCert(_)    => deleg
      case ShelleyTxCertRep.ShelleyTxCertMir(_)          => deleg

/** DELEG (`Shelley/Rules/Deleg.hs`, cardano-ledger f649f975): the registration, unregistration and
  * delegation of stake credentials, the delegation of a genesis key and the move of instantaneous
  * rewards, the certificate read through the class's readers (the reference's patterns `RegTxCert`
  * to `getMirTxCert`, in its order). Every check records its failure and the rule goes on to the
  * state the reference makes, so a later certificate sees it. The signatures a MIR certificate
  * needs of the genesis delegates are UTXOW's (`validateMIRInsufficientGenesisSigs`), not this
  * rule's, which reads no quorum.
  */
object ShelleyDELEG:
  private type Rec[E <: EraTag] = ShelleyDelegPredFailure[E]

  /** `delegationTransition` (`Shelley/Rules/Deleg.hs:252-385`, cardano-ledger f649f975). */
  def delegationTransition[E <: EraTag, F[_]: Monad](env: DelegEnv[E], c: TxCert[E])(using
      cs: EraCertState[E],
      a: ShelleyEraAccounts[E],
      t: ShelleyEraTxCertToBabbage[E],
      p: EraPParams[E],
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[E]],
      judge: Judge[F, ShelleyDelegPredFailure[E]],
      events: Tell[F, Chain[ShelleyDelegEvent[E]]]
  ): F[Unit] =
    val pp = env.pp
    // `hardforkAlonzoAllowMIRTransfer` (`Shelley/Era.hs:241-244`, cardano-ledger f649f975)
    val allowMIRTransfer = p.ppProtocolVersionL.get(pp).pvMajor.value > 4
    val accountsL = cs.certDStateL.andThen(DState.accounts[Accounts[E]])
    def check(cond: => Boolean, failure: => Rec[E]): F[Unit] =
      Sts.predicate[F, Rec[E]](cond, failure)
    def failBecause(failure: => Rec[E]): F[Unit] = Sts.failBecause[F, Rec[E]](failure)
    st.get.flatMap { certState =>
      val ds = cs.certDStateL.get(certState)
      def regTxCert(cred: Credential[Staking]): F[Unit] =
        check(
          !EraAccounts.isAccountRegistered[E](cred, ds.accounts),
          ShelleyDelegPredFailure.StakeKeyAlreadyRegisteredDELEG(cred)
        ) *>
          // `compactCoinOrError (pp ^. ppKeyDepositL)`, the compact deposit itself
          st.set(
            accountsL.modify(
              ShelleyEraAccounts.registerShelleyAccount[E](
                cred,
                env.ptr,
                EraPParams.ppKeyDepositCompactL[E].get(pp),
                None,
                _
              )
            )(certState)
          )
      def unRegTxCert(cred: Credential[Staking]): F[Unit] =
        val (mAccountState, accounts) =
          ShelleyEraAccounts.unregisterShelleyAccount[E](cred, ds.accounts)
        val checkStakeKeyHasZeroRewardBalance = mAccountState
          .map(a.balanceAccountStateL.get)
          .filter(!_.word.isZero)
          .map(_.toCoin)
        Sts.failOnJust[F, Coin, Rec[E]](checkStakeKeyHasZeroRewardBalance)(
          ShelleyDelegPredFailure.StakeKeyNonZeroAccountBalanceDELEG(_)
        ) *>
          mAccountState.fold(
            failBecause(ShelleyDelegPredFailure.StakeKeyNotRegisteredDELEG(cred))
          ) { accountState =>
            st.set(
              cs.certPStateL.modify(
                EraCertState.unDelegReDelegStakePool[E](cred, accountState, None, _)
              )(accountsL.replace(accounts)(certState))
            )
          }
      def delegStakeTxCert(cred: Credential[Staking], stakePool: KeyHash[StakePool]): F[Unit] =
        check(
          cs.certPStateL.get(certState).stakePools.contains(stakePool),
          ShelleyDelegPredFailure.DelegateeNotRegisteredDELEG(stakePool)
        ) *>
          EraAccounts
            .lookupAccountState[E](cred, ds.accounts)
            .fold(failBecause(ShelleyDelegPredFailure.StakeDelegationImpossibleDELEG(cred))) {
              accountState =>
                st.set(
                  cs.certPStateL.modify(
                    EraCertState.unDelegReDelegStakePool[E](cred, accountState, Some(stakePool), _)
                  )(
                    accountsL.modify(
                      EraAccounts.adjustAccountState[E](
                        a.stakePoolDelegationAccountStateL.replace(Some(stakePool)),
                        cred,
                        _
                      )
                    )(certState)
                  )
                )
            }
      def genesisDelegTxCert(cert: GenesisDelegCert): F[Unit] =
        val genDelegs = ds.genDelegs
        // `cod` and `fod`: the pairs of the other genesis keys, current and future
        lazy val others: Vector[GenDelegPair] =
          genDelegs.removed(cert.genesis).values.toVector ++
            ds.futureGenDelegs.collect {
              case (FutureGenDeleg(_, g), pair) if g != cert.genesis => pair
            }.toVector
        for
          globals <- ask.ask[Globals]
          // `slot +* Duration sp` (`libs/cardano-ledger-core/src/Cardano/Ledger/Slot.hs:59-60`,
          // cardano-ledger f649f975): a `Word64` sum, which wraps
          due = SlotNo.fromUnsigned(
            Word64.wrappingAdd(env.slot.word, globals.geometry.stabilityWindow).bits
          )
          _ <- check(
            genDelegs.contains(cert.genesis),
            ShelleyDelegPredFailure.GenesisKeyNotInMappingDELEG(cert.genesis)
          )
          _ <- check(
            !others.exists(_.delegate == cert.delegate.bytes),
            ShelleyDelegPredFailure.DuplicateGenesisDelegateDELEG(cert.delegate)
          )
          _ <- check(
            !others.exists(_.vrf == cert.vrf.bytes),
            ShelleyDelegPredFailure.DuplicateGenesisVRFDELEG(cert.vrf)
          )
          _ <- st.set(
            cs.certDStateL
              .andThen(DState.futureGenDelegs[Accounts[E]])
              .modify(
                _.updated(
                  FutureGenDeleg(due, cert.genesis),
                  GenDelegPair(cert.delegate.bytes, cert.vrf.bytes)
                )
              )(certState)
          )
        yield ()
      def mirTxCert(mir: MIRCert): F[Unit] =
        val ir = ds.iRewards
        val targetPot = mir.pot
        checkSlotNotTooLate[E, F](env.slot, env.curEpochNo) *>
          (mir.rewards match
            case MIRTarget.StakeAddressesMIR(credCoinMap) =>
              val (potAmount, delta, instantaneousRewards) = targetPot match
                case MIRPot.ReservesMIR => (env.account.reserves, ir.deltaReserves, ir.reserves)
                case MIRPot.TreasuryMIR => (env.account.treasury, ir.deltaTreasury, ir.treasury)
              val credCoinMap1 = credCoinMap.view.mapValues(d => Coin(d.value)).toVector
              if allowMIRTransfer then
                // `Map.unionWith (<>) credCoinMap' instantaneousRewards`
                val cm = credCoinMap1.foldLeft(instantaneousRewards) { case (m, (cred, coin)) =>
                  m.updatedWith(cred)(o => Some(o.fold(coin)(r => Coin(coin.value + r.value))))
                }
                check(
                  cm.valuesIterator.forall(_.value.signum >= 0),
                  ShelleyDelegPredFailure.MIRProducesNegativeUpdate()
                ) *>
                  updateReservesAndTreasury[E, F](
                    targetPot,
                    cm,
                    Coin(potAmount.value + delta.value),
                    certState
                  )
              else
                // `Map.union credCoinMap' instantaneousRewards`, left-biased
                check(
                  credCoinMap.valuesIterator.forall(_.value.signum >= 0),
                  ShelleyDelegPredFailure.MIRNegativesNotCurrentlyAllowed()
                ) *>
                  updateReservesAndTreasury[E, F](
                    targetPot,
                    instantaneousRewards ++ credCoinMap1,
                    potAmount,
                    certState
                  )
            case MIRTarget.SendToOppositePotMIR(coin) =>
              if allowMIRTransfer then
                val available = availableAfterMIR(targetPot, env.account, ir)
                val iRewardsL = cs.certDStateL.andThen(DState.iRewards[Accounts[E]])
                def plus(d: DeltaCoin, by: BigInt): DeltaCoin = DeltaCoin(d.value + by)
                check(
                  coin.value.signum >= 0,
                  ShelleyDelegPredFailure.MIRNegativeTransfer(targetPot, coin)
                ) *>
                  check(
                    Order[Coin].lteqv(coin, available),
                    ShelleyDelegPredFailure
                      .InsufficientForTransferDELEG(targetPot, Mismatch(coin, available))
                  ) *>
                  st.set(
                    iRewardsL.modify(i =>
                      targetPot match
                        case MIRPot.ReservesMIR =>
                          i.copy(
                            deltaReserves = plus(i.deltaReserves, -coin.value),
                            deltaTreasury = plus(i.deltaTreasury, coin.value)
                          )
                        case MIRPot.TreasuryMIR =>
                          i.copy(
                            deltaReserves = plus(i.deltaReserves, coin.value),
                            deltaTreasury = plus(i.deltaTreasury, -coin.value)
                          )
                    )(certState)
                  )
              else failBecause(ShelleyDelegPredFailure.MIRTransferNotCurrentlyAllowed()))
      t.getRegTxCert(c)
        .map(regTxCert)
        .orElse(t.getUnRegTxCert(c).map(unRegTxCert))
        .orElse(t.getDelegStakeTxCert(c).map(delegStakeTxCert.tupled))
        .orElse(t.getGenesisDelegTxCert(c).map(genesisDelegTxCert))
        // `RegPoolTxCert _ -> failBecause WrongCertificateTypeDELEG`
        .orElse(
          t.getRegPoolTxCert(c)
            .map(_ => failBecause(ShelleyDelegPredFailure.WrongCertificateTypeDELEG()))
        )
        .orElse(t.getMirTxCert(c).map(mirTxCert))
        // the impossible case, a pool's retirement (`:382-385`)
        .getOrElse(failBecause(ShelleyDelegPredFailure.WrongCertificateTypeDELEG()))
    }

  /** `checkSlotNotTooLate` (`Shelley/Rules/Deleg.hs:387-404`, cardano-ledger f649f975): the first
    * slot of the epoch after `curEpochNo` (`addEpochInterval`, a `Word64` sum that wraps), from
    * `Globals`, as the reference's `SlotNo` (`epochInfoFirst`, whose `Word64` arithmetic wraps:
    * `fixedEpochInfoFirst`, `cardano-slotting/src/Cardano/Slotting/EpochInfo/Impl.hs:37-39`,
    * cardano-base c1e1ed8d), less the stability window by monus (`*-`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Slot.hs:63-64`, cardano-ledger f649f975): the
    * first slot is cut to 64 bits before the window is taken off. The next epoch is told
    * (`DelegNewEpoch`) before the slot is checked against it.
    */
  def checkSlotNotTooLate[E <: EraTag, F[_]: Monad](slot: SlotNo, curEpochNo: EpochNo)(using
      ask: Ask[F, Globals],
      judge: Judge[F, ShelleyDelegPredFailure[E]],
      events: Tell[F, Chain[ShelleyDelegEvent[E]]]
  ): F[Unit] =
    val newEpoch = curEpochNo.addInterval(EpochInterval.one)
    for
      globals <- ask.ask[Globals]
      firstSlot = globals.firstSlot(newEpoch.toBigInt).mod(BigInt(1) << 64)
      window = globals.stabilityWindow
      // below 2^64 and not negative, so its low 64 bits are the slot
      tooLate = SlotNo.fromUnsigned(
        (if firstSlot > window then firstSlot - window else BigInt(0)).toLong
      )
      _ <- events.tell(Chain.one(ShelleyDelegEvent.DelegNewEpoch(newEpoch)))
      _ <- Sts.predicate[F, Rec[E]](
        Order[SlotNo].lt(slot, tooLate),
        ShelleyDelegPredFailure.MIRCertificateTooLateinEpochDELEG(Mismatch(slot, tooLate))
      )
    yield ()

  /** `updateReservesAndTreasury` (`Shelley/Rules/Deleg.hs:406-426`, cardano-ledger f649f975): the
    * rewards `combinedMap` must not pass `available`; they become the pot's instantaneous rewards
    * whatever was recorded.
    */
  def updateReservesAndTreasury[E <: EraTag, F[_]: Monad](
      targetPot: MIRPot,
      combinedMap: SortedMap[Credential[Staking], Coin],
      available: Coin,
      certState: CertState[E]
  )(using
      cs: EraCertState[E],
      st: Stateful[F, CertState[E]],
      judge: Judge[F, ShelleyDelegPredFailure[E]]
  ): F[Unit] =
    val requiredForRewards = Coin(combinedMap.valuesIterator.map(_.value).sum)
    val iRewardsL = cs.certDStateL.andThen(DState.iRewards[Accounts[E]])
    Sts.predicate[F, Rec[E]](
      Order[Coin].lteqv(requiredForRewards, available),
      ShelleyDelegPredFailure.InsufficientForInstantaneousRewardsDELEG(
        targetPot,
        Mismatch(requiredForRewards, available)
      )
    ) *>
      st.set(
        targetPot match
          case MIRPot.ReservesMIR =>
            iRewardsL.andThen(InstantaneousRewards.reserves).replace(combinedMap)(certState)
          case MIRPot.TreasuryMIR =>
            iRewardsL.andThen(InstantaneousRewards.treasury).replace(combinedMap)(certState)
      )

  /** `availableAfterMIR` (`Shelley/LedgerState/NewEpochState.hs:37-41`, cardano-ledger f649f975):
    * the pot with its delta, less the rewards already drawn on it.
    */
  def availableAfterMIR(
      pot: MIRPot,
      account: ChainAccountState,
      ir: InstantaneousRewards
  ): Coin = pot match
    case MIRPot.ReservesMIR =>
      Coin(
        account.reserves.value + ir.deltaReserves.value - ir.reserves.valuesIterator
          .map(_.value)
          .sum
      )
    case MIRPot.TreasuryMIR =>
      Coin(
        account.treasury.value + ir.deltaTreasury.value - ir.treasury.valuesIterator
          .map(_.value)
          .sum
      )
