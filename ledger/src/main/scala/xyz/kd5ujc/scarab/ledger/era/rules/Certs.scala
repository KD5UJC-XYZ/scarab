// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Monad, Order}
import cats.data.Chain
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  GovAction,
  GovActionId,
  GovActionState,
  LedgerDefect,
  LedgerFailure,
  Mismatch,
  TxEnv,
  Voter
}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayDelegCert,
  ConwayEraAccounts,
  ConwayEraCertState,
  ConwayEraPParams,
  ConwayEraTxBody,
  ConwayGovCert,
  Delegatee,
  EraAccounts,
  EraCertState,
  EraPParams,
  EraTx,
  Identity,
  PParams,
  PoolCert,
  ProtVer,
  Tx,
  TxCert,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.AccountState
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxCert
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Globals, Judge, Sts, focus}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Accounts,
  CertState,
  Coin,
  CommitteeAuthorization,
  CommitteeState,
  CompactCoin,
  Conway,
  Credential,
  DRep,
  DRepRole,
  DRepState,
  DState,
  Dijkstra,
  ColdCommittee,
  EpochInterval,
  EpochNo,
  EraTag,
  KeyHash,
  Network,
  NonZero,
  PState,
  PoolBlsKey,
  StakePoolParams,
  Sorted,
  StakePool,
  StakePoolState,
  Staking,
  VState,
  VocabError,
  Word64,
  addInterval
}
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, StateLayout}

// Conway's certificate rules as the reference writes them (cardano-ledger f649f975
// `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/{Certs,Cert,Deleg,GovCert}.hs`, and Shelley's
// POOL, which Conway runs, `eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Pool.hs`), each
// transition generic in its era over the reference's classes. Each rule's companion holds the
// reference's functions of that module under their names, and its transition records the
// reference's own failures (`CertsFailures.scala`), each rule's injected and a sub-rule's embedded
// as the reference's `InjectRuleFailure` and `Embed` instances do. A value outside the state's
// vocabulary is raised as a defect (`LedgerDefect`), as the flat rules raise it, and no decoded
// certificate holds one.

/** The protocol parameters' field `l` (`ppLensHKD . l`). */
private[rules] def field[E <: EraTag, A](pp: PParams[E])(
    l: monocle.Lens[xyz.kd5ujc.scarab.ledger.era.PParamsHKD[Identity, E], A]
): A = PParams.lensHKD[E].andThen(l).get(pp)

/** The major protocol version of `pp` (`pvMajor (pp ^. ppProtocolVersionL)`). */
private def major[E <: EraTag](pp: PParams[E])(using p: EraPParams[E]): Int =
  p.ppProtocolVersionL.get(pp).pvMajor.value

/** The parameters are of `layout`, the layout of the state they go into: a certificate decoded for
  * another build is refused ([[LedgerFailure.PoolParamsLayout]]), as the reference, one build,
  * never meets one.
  */
private[rules] def sameLayout[F[_]: Monad](params: StakePoolParams, layout: StateLayout)(using
    Raise[F, LedgerDefect]
): F[Unit] =
  val found = params.blsKey.stateLayout
  Sts.lift[F, LedgerDefect, Unit](
    Either.cond(
      found == layout,
      (),
      LedgerFailure.PoolParamsLayout(params.id.bytes, found, layout)
    )
  )

private[rules] def vocab[F[_]: Monad, A](result: Either[VocabError, A])(using
    Raise[F, LedgerDefect]
): F[A] = Sts.lift[F, LedgerDefect, A](result.leftMap(LedgerFailure.Vocab(_)))

/** The judgment of a sub-rule, whose failures `wrap` puts in the rule's (`wrapFailed`). */
private[rules] def wrapped[F[_], G, H](judge: Judge[F, H])(wrap: G => H): Judge[F, G] =
  judge.contramap(wrap)

/** The events of a sub-rule, which `wrap` puts in the rule's (`wrapEvent`). */
private[rules] def told[F[_], G, H](events: Tell[F, Chain[H]])(wrap: G => H): Tell[F, Chain[G]] =
  new Tell[F, Chain[G]]:
    val functor: Functor[F] = events.functor
    def tell(l: Chain[G]): F[Unit] = events.tell(l.map(wrap))

/** `binOpEpochNo` (`cardano-slotting/src/Cardano/Slotting/Slot.hs:153-154`, cardano-base c1e1ed8d):
  * `op` on the epochs' `Word64` s, as `+` and `-` wrap.
  */
private def binOpEpochNo(op: (Word64, Word64) => Word64)(a: EpochNo, b: EpochNo): EpochNo =
  EpochNo.fromUnsigned(op(a.word, b.word).bits)

/** `PoolEnv` (`Shelley/Rules/Pool.hs:74-76`, cardano-ledger f649f975). */
final case class PoolEnv[E <: EraTag](epoch: EpochNo, pp: PParams[E])

/** POOL (`Shelley/Rules/Pool.hs`, cardano-ledger f649f975): the registration and retirement of
  * stake pools, over the pool state.
  */
object POOL:
  /** `poolTransition` (`Shelley/Rules/Pool.hs:209-324`, cardano-ledger f649f975). A registration
    * checks the network of the reward account (from version 5), the size of the metadata hash, and
    * the cost; a new pool's VRF key must be unused from version 11, where the rule keeps the map of
    * VRF keys. A pool registers in the layout of the build the node follows, and the rule tells
    * that it registered or registered again.
    */
  def poolTransition[E <: EraTag, F[_]: Monad](env: PoolEnv[E], poolCert: PoolCert)(using
      p: EraPParams[E],
      inject: InjectPool[E],
      ask: Ask[F, Globals],
      st: Stateful[F, PState],
      defect: Raise[F, LedgerDefect],
      judge: Judge[F, PoolPredFailure[E]],
      events: Tell[F, Chain[PoolRuleEvent[E]]]
  ): F[Unit] =
    val pp = env.pp
    val pv = p.ppProtocolVersionL.get(pp)
    // `hardforkConwayDisallowDuplicatedVRFKeys` (`Shelley/Era.hs:259-262`, cardano-ledger f649f975)
    val disallowDuplicatedVRFKeys = pv.pvMajor.value > 10
    def fail(f: ShelleyPoolPredFailure[E]): PoolPredFailure[E] = inject.injectFailure(f)
    def tell(e: PoolEvent[E]): F[Unit] = events.tell(Chain.one(inject.injectEvent(e)))
    poolCert match
      case PoolCert.RegPool(stakePoolParams) =>
        val sppId = stakePoolParams.id
        val network = stakePoolParams.accountAddress.networkId
        val vrf = stakePoolParams.vrf
        val cost = stakePoolParams.cost
        for
          globals <- ask.ask[Globals]
          netId <- vocab[F, Network](
            Network
              .of(BigInt(globals.networkId))
              .toRight(VocabError.OutOfRange(VocabError.Kind.Network, BigInt(globals.networkId)))
          )
          // `hardforkAlonzoValidatePoolAccountAddressNetID` (`Shelley/Era.hs:248-251`,
          // cardano-ledger f649f975)
          _ <-
            if pv.pvMajor.value > 4 then
              Sts.predicate[F, PoolPredFailure[E]](
                netId == network,
                fail(ShelleyPoolPredFailure.WrongNetworkPOOL(Mismatch(network, netId), sppId))
              )
            else Monad[F].unit
          // `restrictPoolMetadataHash` (`Shelley/SoftForks.hs:11-14`, cardano-ledger f649f975):
          // above 4.0
          _ <-
            if pv.pvMajor.value > 4 || (pv.pvMajor.value == 4 && pv.pvMinor.value > 0) then
              stakePoolParams.metadata.traverse_(metadata =>
                Sts.predicate[F, PoolPredFailure[E]](
                  metadata.hash.size <= 32,
                  fail(ShelleyPoolPredFailure.PoolMedataHashTooBig(sppId, metadata.hash.size))
                )
              )
            else Monad[F].unit
          minPoolCost = field[E, CompactCoin](pp)(p.hkdMinPoolCostCompactL[Identity]).toCoin
          _ <- Sts.predicate[F, PoolPredFailure[E]](
            Order[Coin].gteqv(cost, minPoolCost),
            fail(ShelleyPoolPredFailure.StakePoolCostTooLowPOOL(Mismatch(cost, minPoolCost)))
          )
          ps <- st.get
          next <- ps.stakePools.get(sppId) match
            case None =>
              for
                _ <-
                  if disallowDuplicatedVRFKeys then
                    Sts.predicate[F, PoolPredFailure[E]](
                      !ps.vrfKeyHashes.contains(vrf),
                      fail(ShelleyPoolPredFailure.VRFKeyHashAlreadyRegistered(sppId, vrf))
                    )
                  else Monad[F].unit
                _ <- tell(PoolEvent.RegisterPool(sppId))
                layout <- Sts.lift[F, LedgerDefect, StateLayout](
                  StateLayout
                    .of(globals.ledger.referenceBuild)
                    .leftMap(LedgerFailure.LayoutNotYet(_))
                )
                _ <- sameLayout[F](stakePoolParams, layout)
                // `mkStakePoolState (pp ^. ppPoolDepositCompactL) mempty stakePoolParams`, in the
                // layout of the build: in the Leios layout a new pool holds no voting key
                pool <- vocab[F, StakePoolState](
                  Convert.poolState(
                    stakePoolParams,
                    PoolBlsKey.certified(layout),
                    field[E, CompactCoin](pp)(p.hkdPoolDepositCompactL[Identity]),
                    Sorted.emptySet[Credential[Staking]]
                  )
                )
              yield ps.copy(
                stakePools = ps.stakePools.updated(sppId, pool),
                vrfKeyHashes =
                  if disallowDuplicatedVRFKeys then ps.vrfKeyHashes.updated(vrf, NonZero.oneWord)
                  else ps.vrfKeyHashes
              )
            case Some(stakePoolState) =>
              for
                _ <-
                  if disallowDuplicatedVRFKeys then
                    Sts.predicate[F, PoolPredFailure[E]](
                      stakePoolState.vrf == vrf || !ps.vrfKeyHashes.contains(vrf),
                      fail(ShelleyPoolPredFailure.VRFKeyHashAlreadyRegistered(sppId, vrf))
                    )
                  else Monad[F].unit
                _ <- tell(PoolEvent.ReregisterPool(sppId))
                // the parameters wait in the layout of the pool's state
                _ <- sameLayout[F](stakePoolParams, stakePoolState.blsKey.stateLayout)
              yield
                // a re-registration with a fresh VRF key records it, and forgets the key of an
                // earlier re-registration of the epoch
                val vrfs =
                  if !disallowDuplicatedVRFKeys then ps.vrfKeyHashes
                  else
                    ps.future.get(sppId) match
                      case None => ps.vrfKeyHashes.updated(vrf, NonZero.oneWord)
                      case Some(future) if future.vrf != vrf =>
                        ps.vrfKeyHashes.removed(future.vrf).updated(vrf, NonZero.oneWord)
                      case Some(_) => ps.vrfKeyHashes
                ps.copy(
                  future = ps.future.updated(sppId, stakePoolParams),
                  retiring = ps.retiring.removed(sppId),
                  vrfKeyHashes = vrfs
                )
          _ <- st.set(next)
        yield ()
      case PoolCert.RetirePool(sppId, e) =>
        val cEpoch = env.epoch
        // `addEpochInterval cEpoch (pp ^. ppEMaxL)`, a `Word64` sum that wraps
        val limitEpoch = cEpoch.addInterval(field[E, EpochInterval](pp)(p.hkdEMaxL[Identity]))
        for
          ps <- st.get
          _ <- Sts.predicate[F, PoolPredFailure[E]](
            ps.stakePools.contains(sppId),
            fail(ShelleyPoolPredFailure.StakePoolNotRegisteredOnKeyPOOL(sppId))
          )
          _ <- Sts.predicate[F, PoolPredFailure[E]](
            Order[EpochNo].lt(cEpoch, e) && Order[EpochNo].lteqv(e, limitEpoch),
            fail(
              ShelleyPoolPredFailure
                .StakePoolRetirementWrongEpochPOOL(Mismatch(e, cEpoch), Mismatch(e, limitEpoch))
            )
          )
          _ <- st.set(ps.copy(retiring = ps.retiring.updated(sppId, e)))
        yield ()

/** `ConwayDelegEnv` (`Conway/Rules/Deleg.hs:85-89`, cardano-ledger f649f975): the parameters and
  * the registered pools.
  */
final case class ConwayDelegEnv[E <: EraTag](
    pp: PParams[E],
    pools: SortedMap[KeyHash[StakePool], StakePoolState]
)

/** DELEG (`Conway/Rules/Deleg.hs`, cardano-ledger f649f975): the registration, unregistration and
  * delegation of stake credentials, and the delegators of each pool and DRep.
  */
object DELEG:
  /** `conwayDelegTransition` (`Conway/Rules/Deleg.hs:177-301`, cardano-ledger f649f975): each check
    * records its failure and the rule goes on to the state the reference makes, so a later
    * certificate sees it; only an account that is not registered leaves the state as it was.
    */
  def conwayDelegTransition[E <: EraTag, F[_]: Monad](
      env: ConwayDelegEnv[E],
      cert: ConwayDelegCert
  )(using
      p: EraPParams[E],
      c: ConwayEraCertState[E],
      inject: InjectDeleg[E],
      st: Stateful[F, CertState[E]],
      judge: Judge[F, DelegPredFailure[E]]
  ): F[Unit] =
    given a: ConwayEraAccounts[E] = c.accountsOf
    val pp = env.pp
    val ppKeyDepositCompact = field[E, CompactCoin](pp)(p.hkdKeyDepositCompactL[Identity])
    val ppKeyDeposit = ppKeyDepositCompact.toCoin
    val pvMajor = major[E](pp)
    // `hardforkConwayDELEGIncorrectDepositsAndRefunds` (`Conway/Era.hs:266-267`, cardano-ledger
    // f649f975)
    val incorrectDepositsAndRefunds = pvMajor > 10
    def fail(f: ConwayDelegPredFailure[E]): DelegPredFailure[E] = inject.injectFailure(f)
    def accountsOf(certState: CertState[E]): Accounts[E] = c.certDStateL.get(certState).accounts
    def withAccounts(f: Accounts[E] => Accounts[E])(certState: CertState[E]): CertState[E] =
      c.certDStateL.andThen(DState.accounts[Accounts[E]]).modify(f)(certState)
    def checkDepositAgainstPParams(deposit: Coin): F[Unit] =
      Sts.predicate[F, DelegPredFailure[E]](
        Order[Coin].eqv(deposit, ppKeyDeposit),
        if incorrectDepositsAndRefunds then
          fail(ConwayDelegPredFailure.DepositIncorrectDELEG(Mismatch(deposit, ppKeyDeposit)))
        else fail(ConwayDelegPredFailure.IncorrectDepositDELEG(deposit))
      )
    def checkStakeKeyNotRegistered(certState: CertState[E], stakeCred: Credential[Staking]) =
      Sts.predicate[F, DelegPredFailure[E]](
        !EraAccounts.isAccountRegistered[E](stakeCred, accountsOf(certState)),
        fail(ConwayDelegPredFailure.StakeKeyRegisteredDELEG(stakeCred))
      )
    def checkStakeDelegateeRegistered(certState: CertState[E], delegatee: Delegatee): F[Unit] =
      def checkPoolRegistered(targetPool: KeyHash[StakePool]) =
        Sts.predicate[F, DelegPredFailure[E]](
          env.pools.contains(targetPool),
          fail(ConwayDelegPredFailure.DelegateeStakePoolNotRegisteredDELEG(targetPool))
        )
      def checkDRepRegistered(targetDRep: DRep) =
        ConwayEraCertState.dRepToCred(targetDRep).traverse_ { dRepCred =>
          // `unless (hardforkConwayBootstrapPhase ...)` (`Conway/Era.hs:257-258`, cardano-ledger
          // f649f975)
          if pvMajor == 9 then Monad[F].unit
          else
            Sts.predicate[F, DelegPredFailure[E]](
              c.certVStateL.get(certState).dreps.contains(dRepCred),
              fail(ConwayDelegPredFailure.DelegateeDRepNotRegisteredDELEG(dRepCred))
            )
        }
      delegatee match
        case Delegatee.DelegStake(targetPool) => checkPoolRegistered(targetPool)
        case Delegatee.DelegStakeVote(targetPool, targetDRep) =>
          checkPoolRegistered(targetPool) *> checkDRepRegistered(targetDRep)
        case Delegatee.DelegVote(targetDRep) => checkDRepRegistered(targetDRep)
    def notRegistered(stakeCred: Credential[Staking]): F[Unit] =
      Sts.failBecause[F, DelegPredFailure[E]](
        fail(ConwayDelegPredFailure.StakeKeyNotRegisteredDELEG(stakeCred))
      )
    st.get.flatMap { certState =>
      cert match
        case ConwayDelegCert.ConwayRegCert(stakeCred, sMayDeposit) =>
          sMayDeposit.traverse_(checkDepositAgainstPParams) *>
            checkStakeKeyNotRegistered(certState, stakeCred) *>
            st.set(
              withAccounts(
                ConwayEraAccounts.registerConwayAccount[E](stakeCred, ppKeyDepositCompact, None, _)
              )(certState)
            )
        case ConwayDelegCert.ConwayUnRegCert(stakeCred, sMayRefund) =>
          val (mAccountState, newAccounts) =
            ConwayEraAccounts.unregisterConwayAccount[E](stakeCred, accountsOf(certState))
          val checkInvalidRefund = for
            suppliedRefund <- sMayRefund
            accountState <- mAccountState
            expectedRefund = a.depositAccountStateL.get(accountState).toCoin
            if Order[Coin].neqv(suppliedRefund, expectedRefund)
          yield
            if incorrectDepositsAndRefunds then
              fail(
                ConwayDelegPredFailure
                  .RefundIncorrectDELEG(Mismatch(suppliedRefund, expectedRefund))
              )
            else fail(ConwayDelegPredFailure.IncorrectDepositDELEG(suppliedRefund))
          val checkStakeKeyHasZeroRewardBalance = for
            accountState <- mAccountState
            balanceCompact = a.balanceAccountStateL.get(accountState)
            if !balanceCompact.word.isZero
          yield balanceCompact.toCoin
          Sts.failOnJust[F, DelegPredFailure[E], DelegPredFailure[E]](checkInvalidRefund)(
            identity
          ) *>
            Sts.failOnJust[F, Coin, DelegPredFailure[E]](checkStakeKeyHasZeroRewardBalance)(b =>
              fail(ConwayDelegPredFailure.StakeKeyHasNonZeroAccountBalanceDELEG(b))
            ) *>
            mAccountState.fold(notRegistered(stakeCred)) { accountState =>
              val unregistered = withAccounts(_ => newAccounts)(certState)
              val unDRep = c.certVStateL.modify(
                ConwayEraCertState.unDelegReDelegDRep[E](stakeCred, accountState, None, _)
              )(unregistered)
              st.set(
                c.certPStateL.modify(
                  EraCertState.unDelegReDelegStakePool[E](stakeCred, accountState, None, _)
                )(unDRep)
              )
            }
        case ConwayDelegCert.ConwayDelegCert(stakeCred, delegatee) =>
          checkStakeDelegateeRegistered(certState, delegatee) *>
            EraAccounts
              .lookupAccountState[E](stakeCred, accountsOf(certState))
              .fold(notRegistered(stakeCred))(accountState =>
                st.set(
                  processDelegationInternal[E](
                    pvMajor < 10,
                    stakeCred,
                    Some(accountState),
                    delegatee,
                    certState
                  )
                )
              )
        case ConwayDelegCert.ConwayRegDelegCert(stakeCred, delegatee, deposit) =>
          checkDepositAgainstPParams(deposit) *>
            checkStakeKeyNotRegistered(certState, stakeCred) *>
            checkStakeDelegateeRegistered(certState, delegatee) *>
            st.set(
              processDelegationInternal[E](
                pvMajor < 10,
                stakeCred,
                None,
                delegatee,
                withAccounts(
                  ConwayEraAccounts
                    .registerConwayAccount[E](stakeCred, ppKeyDepositCompact, Some(delegatee), _)
                )(certState)
              )
            )
    }

  /** `processDelegationInternal` (`Conway/Rules/Deleg.hs:320-377`, cardano-ledger f649f975):
    * `stakeCred` delegates to `newDelegatee`, its earlier delegations cleaned up. `mAccountState`
    * is the account as it was, none for an account registered by the same certificate.
    * `preserveIncorrectDelegation` keeps the bootstrap phase's behaviour, where a delegation to a
    * DRep did not remove the earlier DRep's delegator (cardano-ledger issue 4772).
    */
  def processDelegationInternal[E <: EraTag](
      preserveIncorrectDelegation: Boolean,
      stakeCred: Credential[Staking],
      mAccountState: Option[AccountState[E]],
      newDelegatee: Delegatee,
      certState: CertState[E]
  )(using c: ConwayEraCertState[E]): CertState[E] =
    given a: ConwayEraAccounts[E] = c.accountsOf
    val accountsL = c.certDStateL.andThen(DState.accounts[Accounts[E]])
    def delegStake(stakePool: KeyHash[StakePool])(cState: CertState[E]): CertState[E] =
      val adjusted = accountsL.modify(
        EraAccounts.adjustAccountState[E](
          a.stakePoolDelegationAccountStateL.replace(Some(stakePool)),
          stakeCred,
          _
        )
      )(cState)
      mAccountState.fold(
        c.certPStateL.modify(
          PState.stakePools.modify(
            _.updatedWith(stakePool)(_.map(StakePoolState.delegators.modify(_ + stakeCred)))
          )
        )(adjusted)
      )(accountState =>
        c.certPStateL.modify(
          EraCertState.unDelegReDelegStakePool[E](stakeCred, accountState, Some(stakePool), _)
        )(adjusted)
      )
    def delegVote(dRep: DRep)(cState: CertState[E]): CertState[E] =
      val adjusted = accountsL.modify(
        EraAccounts
          .adjustAccountState[E](a.dRepDelegationAccountStateL.replace(Some(dRep)), stakeCred, _)
      )(cState)
      ConwayEraCertState.dRepToCred(dRep) match
        case Some(dRepCred) if mAccountState.isEmpty || preserveIncorrectDelegation =>
          c.certVStateL.modify(
            VState.dreps.modify(
              _.updatedWith(dRepCred)(_.map(DRepState.delegators.modify(_ + stakeCred)))
            )
          )(adjusted)
        case _ =>
          mAccountState.fold(adjusted)(accountState =>
            c.certVStateL.modify(
              ConwayEraCertState.unDelegReDelegDRep[E](stakeCred, accountState, Some(dRep), _)
            )(adjusted)
          )
    newDelegatee match
      case Delegatee.DelegStake(sPool)           => delegStake(sPool)(certState)
      case Delegatee.DelegVote(dRep)             => delegVote(dRep)(certState)
      case Delegatee.DelegStakeVote(sPool, dRep) => delegVote(dRep)(delegStake(sPool)(certState))

/** `ConwayGovCertEnv` (`Conway/Rules/GovCert.hs:82-90`, cardano-ledger f649f975): the parameters,
  * the epoch, the committee and the proposals to update it. The committee and the proposals are the
  * node's records, which the flat rules read too.
  */
final case class ConwayGovCertEnv[E <: EraTag](
    pp: PParams[E],
    currentEpoch: EpochNo,
    currentCommittee: Option[Committee],
    committeeProposals: Map[GovActionId, GovActionState]
)

/** GOVCERT (`Conway/Rules/GovCert.hs`, cardano-ledger f649f975): the DReps' and the committee
  * members' certificates.
  */
object GOVCERT:
  /** `conwayGovCertTransition` (`Conway/Rules/GovCert.hs:170-276`, cardano-ledger f649f975): a DRep
    * is registered, unregistered or updated, and a committee member authorizes a hot key or
    * resigns, whatever failed, except that a DRep not registered is not updated.
    */
  def conwayGovCertTransition[E <: EraTag, F[_]: Monad](
      env: ConwayGovCertEnv[E],
      cert: ConwayGovCert
  )(using
      p: ConwayEraPParams[E],
      c: ConwayEraCertState[E],
      inject: InjectGovCert[E],
      st: Stateful[F, CertState[E]],
      judge: Judge[F, GovCertPredFailure[E]]
  ): F[Unit] =
    given a: ConwayEraAccounts[E] = c.accountsOf
    val pp = env.pp
    val ppDRepDepositCompact = field[E, CompactCoin](pp)(p.hkdDRepDepositCompactL[Identity])
    val ppDRepDeposit = ppDRepDepositCompact.toCoin
    val ppDRepActivity = field[E, EpochInterval](pp)(p.hkdDRepActivityL[Identity])
    def fail(f: ConwayGovCertPredFailure[E]): GovCertPredFailure[E] = inject.injectFailure(f)
    val dRepsL = c.certVStateL.andThen(VState.dreps)
    def checkAndOverwriteCommitteeMemberState(
        certState: CertState[E],
        coldCred: Credential[ColdCommittee],
        newMemberState: CommitteeAuthorization
    ): F[Unit] =
      val back = Convert.back(coldCred)
      val committeeCredsL =
        c.certVStateL.andThen(VState.committeeState).andThen(CommitteeState.creds)
      val coldCredResigned = committeeCredsL.get(certState).get(coldCred).collect {
        case CommitteeAuthorization.CommitteeMemberResigned(_) => coldCred
      }
      val isCurrentMember = env.currentCommittee.exists(_.members.contains(back))
      val isPotentialFutureMember = env.committeeProposals.values.exists(gas =>
        gas.proposal.action match
          case GovAction.UpdateCommittee(_, _, newMembers, _) => newMembers.contains(back)
          case _                                              => false
      )
      Sts.failOnJust[F, Credential[ColdCommittee], GovCertPredFailure[E]](coldCredResigned)(cold =>
        fail(ConwayGovCertPredFailure.ConwayCommitteeHasPreviouslyResigned(cold))
      ) *>
        Sts.predicate[F, GovCertPredFailure[E]](
          isCurrentMember || isPotentialFutureMember,
          fail(ConwayGovCertPredFailure.ConwayCommitteeIsUnknown(coldCred))
        ) *>
        st.set(committeeCredsL.modify(_.updated(coldCred, newMemberState))(certState))
    st.get.flatMap { certState =>
      val dormant = c.certVStateL.get(certState).numDormantEpochs
      cert match
        case ConwayGovCert.ConwayRegDRep(cred, deposit, mAnchor) =>
          for
            _ <- Sts.predicate[F, GovCertPredFailure[E]](
              !dRepsL.get(certState).contains(cred),
              fail(ConwayGovCertPredFailure.ConwayDRepAlreadyRegistered(cred))
            )
            _ <- Sts.predicate[F, GovCertPredFailure[E]](
              Order[Coin].eqv(deposit, ppDRepDeposit),
              fail(
                ConwayGovCertPredFailure.ConwayDRepIncorrectDeposit(
                  Mismatch(deposit, ppDRepDeposit)
                )
              )
            )
            drepState = DRepState(
              computeDRepExpiryVersioned[E](pp, env.currentEpoch, dormant),
              mAnchor,
              ppDRepDepositCompact,
              Sorted.emptySet[Credential[Staking]]
            )
            _ <- st.set(dRepsL.modify(_.updated(cred, drepState))(certState))
          yield ()
        case ConwayGovCert.ConwayUnRegDRep(cred, refund) =>
          val mDRepState = dRepsL.get(certState).get(cred)
          val drepRefundMismatch =
            mDRepState.map(_.deposit.toCoin).filter(Order[Coin].neqv(_, refund))
          val certState1 = dRepsL.modify(_ - cred)(certState)
          for
            _ <- Sts.predicate[F, GovCertPredFailure[E]](
              mDRepState.isDefined,
              fail(ConwayGovCertPredFailure.ConwayDRepNotRegistered(cred))
            )
            _ <- Sts.failOnJust[F, Coin, GovCertPredFailure[E]](drepRefundMismatch)(paid =>
              fail(ConwayGovCertPredFailure.ConwayDRepIncorrectRefund(Mismatch(refund, paid)))
            )
            _ <- st.set(mDRepState.fold(certState1) { dRepState =>
              // `clearDRepDelegations`: each delegator's account forgets the DRep
              c.certDStateL
                .andThen(DState.accounts[Accounts[E]])
                .andThen(a.accountsMapL)
                .modify(accountsMap =>
                  dRepState.delegators.foldLeft(accountsMap)((m, deleg) =>
                    m.updatedWith(deleg)(_.map(a.dRepDelegationAccountStateL.replace(None)))
                  )
                )(certState1)
            })
          yield ()
        case ConwayGovCert.ConwayUpdateDRep(cred, mAnchor) =>
          Sts.predicate[F, GovCertPredFailure[E]](
            dRepsL.get(certState).contains(cred),
            fail(ConwayGovCertPredFailure.ConwayDRepNotRegistered(cred))
          ) *>
            st.set(
              dRepsL.modify(
                _.updatedWith(cred)(
                  _.map(
                    _.copy(
                      expiry = computeDRepExpiry(ppDRepActivity, env.currentEpoch, dormant),
                      anchor = mAnchor
                    )
                  )
                )
              )(certState)
            )
        case ConwayGovCert.ConwayAuthCommitteeHotKey(coldCred, hotCred) =>
          checkAndOverwriteCommitteeMemberState(
            certState,
            coldCred,
            CommitteeAuthorization.CommitteeHotCredential(hotCred)
          )
        case ConwayGovCert.ConwayResignCommitteeColdKey(coldCred, anchor) =>
          checkAndOverwriteCommitteeMemberState(
            certState,
            coldCred,
            CommitteeAuthorization.CommitteeMemberResigned(anchor)
          )
    }

  /** `computeDRepExpiryVersioned` (`Conway/Rules/GovCert.hs:278-292`, cardano-ledger f649f975): in
    * the bootstrap phase (version 9) the dormant epochs are not taken off.
    */
  def computeDRepExpiryVersioned[E <: EraTag](
      pp: PParams[E],
      currentEpoch: EpochNo,
      numDormantEpochs: EpochNo
  )(using p: ConwayEraPParams[E]): EpochNo =
    val ppDRepActivity = field[E, EpochInterval](pp)(p.hkdDRepActivityL[Identity])
    if major[E](pp) == 9 then currentEpoch.addInterval(ppDRepActivity)
    else computeDRepExpiry(ppDRepActivity, currentEpoch, numDormantEpochs)

  /** `computeDRepExpiry` (`Conway/Rules/GovCert.hs:294-306`, cardano-ledger f649f975): the epoch a
    * DRep acting now is active until, the dormant epochs taken off, in `Word64` arithmetic that
    * wraps (`binOpEpochNo (-)` of `addEpochInterval`).
    */
  def computeDRepExpiry(
      ppDRepActivity: EpochInterval,
      currentEpoch: EpochNo,
      numDormantEpochs: EpochNo
  ): EpochNo =
    binOpEpochNo(Word64.wrappingSubtract)(
      currentEpoch.addInterval(ppDRepActivity),
      numDormantEpochs
    )

/** `CertEnv` (`Conway/Rules/Cert.hs:77-83`, cardano-ledger f649f975). */
final case class CertEnv[E <: EraTag](
    pp: PParams[E],
    currentEpoch: EpochNo,
    currentCommittee: Option[Committee],
    committeeProposals: Map[GovActionId, GovActionState]
)

/** CERT (`Conway/Rules/Cert.hs`, cardano-ledger f649f975): one certificate, to the rule of its
  * kind. The era's certificate is Conway's (`TxCert era ~ ConwayTxCert era`).
  */
object CERT:
  /** `certTransition` (`Conway/Rules/Cert.hs:192-223`, cardano-ledger f649f975): DELEG and GOVCERT
    * on the certificate state, POOL on the pool state alone (`certPStateL`), each a sub-rule whose
    * failures and events CERT's embed ([[EmbedInCert]]).
    */
  def certTransition[E <: EraTag, F[_]: Monad](env: CertEnv[E], txCert: TxCert[E])(using
      ev: TxCert[E] =:= ConwayTxCert,
      p: ConwayEraPParams[E],
      c: ConwayEraCertState[E],
      embed: EmbedInCert[E],
      deleg: InjectDeleg[E],
      pool: InjectPool[E],
      govCert: InjectGovCert[E],
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[E]],
      defect: Raise[F, LedgerDefect],
      judge: Judge[F, CertPredFailure[E]],
      events: Tell[F, Chain[CertEvent[E]]]
  ): F[Unit] =
    st.get.flatMap { certState =>
      val pools = c.certPStateL.get(certState).stakePools
      ev(txCert) match
        case ConwayTxCert.ConwayTxCertDeleg(delegCert) =>
          judge.nest(
            DELEG.conwayDelegTransition[E, F](ConwayDelegEnv[E](env.pp, pools), delegCert)(using
              summon[Monad[F]],
              p,
              c,
              deleg,
              st,
              wrapped(judge)(embed.wrapDelegFailed)
            )
          )
        case ConwayTxCert.ConwayTxCertPool(poolCert) =>
          judge.nest(
            POOL.poolTransition[E, F](PoolEnv[E](env.currentEpoch, env.pp), poolCert)(using
              summon[Monad[F]],
              p,
              pool,
              ask,
              st.focus(c.certPStateL),
              defect,
              wrapped(judge)(embed.wrapPoolFailed),
              told(events)(embed.wrapPoolEvent)
            )
          )
        case ConwayTxCert.ConwayTxCertGov(govCertificate) =>
          judge.nest(
            GOVCERT.conwayGovCertTransition[E, F](
              ConwayGovCertEnv[E](
                env.pp,
                env.currentEpoch,
                env.currentCommittee,
                env.committeeProposals
              ),
              govCertificate
            )(using summon[Monad[F]], p, c, govCert, st, wrapped(judge)(embed.wrapGovCertFailed))
          )
    }

/** `CertsEnv` (`Conway/Rules/Certs.hs:87-95`, cardano-ledger f649f975). The transaction is the 11.1
  * build's: its base case reads the withdrawals and the votes. At 29a7d656 and 9df9f98c the
  * environment has no transaction, and nothing reads it there ([[WithdrawalsRule]]).
  */
final case class CertsEnv[E <: EraTag](
    tx: Tx[TxLevel.Top, E],
    pp: PParams[E],
    currentEpoch: EpochNo,
    currentCommittee: Option[Committee],
    committeeProposals: Map[GovActionId, GovActionState]
)

/** Which rule checks a transaction's withdrawals and updates its DReps' expiries, by the reference
  * build: `hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule` (`Conway/Era.hs:283-284`,
  * cardano-ledger f649f975) at 11.1, where CERTS's base case does it below protocol version 11 and
  * LEDGER from 11; LEDGER at every version at 29a7d656 (the 11.2 build) and 9df9f98c (the Leios
  * build), where the gate is gone (`Conway/Rules/Ledger.hs:397-403` and `Conway/Rules/Certs.hs:220`
  * at 29a7d656, `Conway/Rules/Certs.hs:220` at 9df9f98c). The rules derive it from the build the
  * judgment's `Globals` name ([[of]]), as POOL reads its layout, so a judgment cannot pair one
  * build with another's rule, until the builds are types (#1063). The flat rules gate at version 11
  * under every build, so under the 11.2 and Leios builds at versions 9 and 10 they refuse a
  * withdrawal that does not drain its account in CERTS where the reference refuses it in LEDGER,
  * with the same verdict and state
  * (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7055304484).
  */
enum WithdrawalsRule derives CanEqual:
  /** 11.1: LEDGER's above version 10, CERTS's base case's below. */
  case LedgerAboveVersion10

  /** 11.2 and Leios: LEDGER's at every version. */
  case Ledger

  /** `hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule` of the build: whether LEDGER checks
    * the withdrawals at `pv`, and CERTS's base case does not.
    */
  def inLedger(pv: ProtVer): Boolean = this match
    case LedgerAboveVersion10 => pv.pvMajor.value > 10
    case Ledger               => true

object WithdrawalsRule:
  /** The rule of the build `build`. */
  def of(build: ReferenceBuild): WithdrawalsRule = build match
    case ReferenceBuild.Release11_1                                => LedgerAboveVersion10
    case ReferenceBuild.Release11_2 | ReferenceBuild.LeiosB004fe0c => Ledger

/** `EraRule "CERT" era`: the CERT transition of era `E`, which CERTS runs on each certificate
  * (`trans @(EraRule "CERT" era)`, `Conway/Rules/Certs.hs:224-225`, cardano-ledger 9df9f98c):
  * Conway's own ([[CERT]], [[CertRule.conway]]), and Dijkstra's ([[DijkstraCERT]],
  * [[CertRule.dijkstra]]), whose POOL reads the validation policy the judgment runs under. `D` is
  * what the era's CERT raises where it reaches no verdict, beyond the defects of every rule
  * (`LedgerDefect`): nothing at Conway, POOL's [[PoolDefect]] at Dijkstra.
  */
trait CertRule[E <: EraTag, D]:
  def certTransition[F[_]: Monad](env: CertEnv[E], txCert: TxCert[E])(using
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[E]],
      defect: Raise[F, LedgerDefect],
      certDefect: Raise[F, D],
      judge: Judge[F, CertPredFailure[E]],
      events: Tell[F, Chain[CertEvent[E]]]
  ): F[Unit]

object CertRule:
  /** Conway's CERT for an era whose certificate is Conway's ([[CERT.certTransition]]), which raises
    * nothing of its own.
    */
  def conway[E <: EraTag](using
      ev: TxCert[E] =:= ConwayTxCert,
      p: ConwayEraPParams[E],
      c: ConwayEraCertState[E],
      embed: EmbedInCert[E],
      deleg: InjectDeleg[E],
      pool: InjectPool[E],
      govCert: InjectGovCert[E]
  ): CertRule[E, Nothing] = new CertRule[E, Nothing]:
    override def certTransition[F[_]: Monad](env: CertEnv[E], txCert: TxCert[E])(using
        ask: Ask[F, Globals],
        st: Stateful[F, CertState[E]],
        defect: Raise[F, LedgerDefect],
        certDefect: Raise[F, Nothing],
        judge: Judge[F, CertPredFailure[E]],
        events: Tell[F, Chain[CertEvent[E]]]
    ): F[Unit] = CERT.certTransition[E, F](env, txCert)

  /** `type instance EraRule "CERT" ConwayEra = CERT ConwayEra`. */
  given conwayEra: CertRule[Conway, Nothing] = conway[Conway]

  /** Dijkstra's CERT ([[DijkstraCERT.certTransition]]) under the validation policy `validation`,
    * which its POOL's static check reads (`checkFailOnJustStatic`, `Dijkstra/Rules/Pool.hs:248`,
    * cardano-ledger 9df9f98c): a value of the judgment, as the reference's is of its options. A
    * voting key its POOL cannot check is raised ([[PoolDefect]]).
    */
  def dijkstra(validation: TxEnv.Validation): CertRule[Dijkstra, PoolDefect] =
    new CertRule[Dijkstra, PoolDefect]:
      override def certTransition[F[_]: Monad](
          env: CertEnv[Dijkstra],
          txCert: TxCert[Dijkstra]
      )(using
          ask: Ask[F, Globals],
          st: Stateful[F, CertState[Dijkstra]],
          defect: Raise[F, LedgerDefect],
          certDefect: Raise[F, PoolDefect],
          judge: Judge[F, CertPredFailure[Dijkstra]],
          events: Tell[F, Chain[CertEvent[Dijkstra]]]
      ): F[Unit] = DijkstraCERT.certTransition[Dijkstra, F](env, txCert, validation)

/** CERTS's base case in era `E`, on the empty sequence of certificates: the 11.1 build's
  * (`Conway/Rules/Certs.hs:223-244`, cardano-ledger f649f975), where the withdrawals are CERTS's
  * below version 11, or the later builds' (`Empty -> pure certState`, `:220`, cardano-ledger
  * 9df9f98c), which leaves the state as it is. Conway's era types follow the 11.1 build's ledger;
  * Dijkstra's CERTS has no withdrawals' case to report ([[CertsWithdrawals]]), so its base case is
  * the later one by its type, not by its protocol version.
  */
enum CertsBaseCase[E <: EraTag]:
  /** The 11.1 build's, `withdrawals` the evidence that the era's CERTS reports the withdrawals that
    * do not drain their accounts (`WithdrawalsNotInRewardsCERTS`).
    */
  case Withdrawals(withdrawals: Map[AccountAddress, Coin] =:= CertsWithdrawals[E])

  /** The state as it is. */
  case Unchanged()

object CertsBaseCase:
  given conway: CertsBaseCase[Conway] = Withdrawals(summon)
  given dijkstra: CertsBaseCase[Dijkstra] = Unchanged()

/** CERTS (`Conway/Rules/Certs.hs`, cardano-ledger f649f975): a transaction's certificates. */
object CERTS:
  /** `conwayCertsTransition` (`Conway/Rules/Certs.hs:203-246`, cardano-ledger f649f975): by
    * recursion on the prefix (`gamma :|> txCert`), CERTS of all but the last certificate as a
    * sub-rule, then CERT of the last, so the first certificate is the deepest. The empty sequence
    * is the era's base case ([[CertsBaseCase]]): in the 11.1 build's, where the build the judgment
    * follows (`Globals`) leaves them to CERTS, the withdrawals must drain their accounts, the
    * DReps' expiries are updated and the accounts drained; otherwise that is LEDGER's
    * ([[WithdrawalsRule]]). The recursion is a fold, each step a program over the previous, which
    * the interpreter runs in constant stack. Each certificate is judged by the era's CERT
    * ([[CertRule]]), whose failures and events are embedded in CERTS's ([[EmbedCertInCerts]]), and
    * whose own defects `certDefect` raises. Dijkstra's CERTS is this transition
    * (`Dijkstra/Rules/Certs.hs:20-22`, cardano-ledger 9df9f98c), with Dijkstra's CERT and the later
    * builds' base case.
    */
  def conwayCertsTransition[E <: EraTag, D, F[_]: Monad](
      env: CertsEnv[E],
      certificates: Vector[TxCert[E]]
  )(using
      t: EraTx[E],
      b: ConwayEraTxBody[E],
      p: ConwayEraPParams[E],
      c: ConwayEraCertState[E],
      embed: EmbedCertInCerts[E],
      cert: CertRule[E, D],
      baseCase: CertsBaseCase[E],
      inject: InjectCertsFailure[ConwayCertsPredFailure[E], E],
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[E]],
      defect: Raise[F, LedgerDefect],
      certDefect: Raise[F, D],
      judge: Judge[F, CertsPredFailure[E]],
      events: Tell[F, Chain[CertsEvent[E]]]
  ): F[Unit] =
    val pp = env.pp
    val base = baseCase match
      case CertsBaseCase.Withdrawals(withdrawals) =>
        ask.ask[Globals].flatMap { globals =>
          if WithdrawalsRule
              .of(globals.ledger.referenceBuild)
              .inLedger(p.ppProtocolVersionL.get(pp))
          then Monad[F].unit
          else empty[E, F](env, withdrawals)
        }
      case CertsBaseCase.Unchanged() => Monad[F].unit
    val certEnv = CertEnv[E](pp, env.currentEpoch, env.currentCommittee, env.committeeProposals)
    certificates.foldLeft(base)((gamma, txCert) =>
      judge.nest(gamma) *>
        judge.nest(
          cert.certTransition[F](certEnv, txCert)(using
            summon[Monad[F]],
            ask,
            st,
            defect,
            certDefect,
            wrapped(judge)(embed.wrapFailed),
            told(events)(embed.wrapEvent)
          )
        )
    )

  /** The base case of the 11.1 build below version 11 (`Conway/Rules/Certs.hs:228-244`,
    * cardano-ledger f649f975), in an era whose CERTS reports the withdrawals (`withdrawals`).
    */
  private def empty[E <: EraTag, F[_]: Monad](
      env: CertsEnv[E],
      withdrawalsNotInRewards: Map[AccountAddress, Coin] =:= CertsWithdrawals[E]
  )(using
      t: EraTx[E],
      b: ConwayEraTxBody[E],
      p: ConwayEraPParams[E],
      c: ConwayEraCertState[E],
      inject: InjectCertsFailure[ConwayCertsPredFailure[E], E],
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[E]],
      defect: Raise[F, LedgerDefect],
      judge: Judge[F, CertsPredFailure[E]]
  ): F[Unit] =
    given a: ConwayEraAccounts[E] = c.accountsOf
    val body = t.body[TxLevel.Top](env.tx)(using t.bodyOf.top)
    val withdrawals = b.withdrawals[TxLevel.Top](body)(using b.top)
    val accountsL = c.certDStateL.andThen(DState.accounts[Accounts[E]])
    for
      network <- ask.ask[Globals].map(_.networkId)
      certState <- st.get
      bad = EraAccounts.withdrawalsThatDoNotDrainAccounts[E](
        withdrawals,
        network,
        accountsL.get(certState)
      )
      // `unWithdrawals invalid <> fmap mismatchSupplied incomplete`, the maps' union, whose keys
      // are disjoint (`Certs.hs:230-235`, cardano-ledger f649f975)
      notInRewards = bad.map[Map[AccountAddress, Coin]]((invalid, incomplete) =>
        invalid.unWithdrawals ++ incomplete.view.mapValues(_.supplied)
      )
      _ <- Sts.failOnJust[F, Map[AccountAddress, Coin], CertsPredFailure[E]](notInRewards)(ws =>
        inject.injectFailure(
          ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS(withdrawalsNotInRewards(ws))
        )
      )
      voted <- vocab[F, CertState[E]](
        updateVotingDRepExpiries[E, TxLevel.Top](
          env.tx,
          env.currentEpoch,
          field[E, EpochInterval](env.pp)(p.hkdDRepActivityL[Identity]),
          updateDormantDRepExpiries[E, TxLevel.Top](env.tx, env.currentEpoch, certState)(using
            t,
            b,
            c,
            t.bodyOf.top,
            b.top
          )
        )(using t, b, c, t.bodyOf.top, b.top)
      )
      _ <- st.set(accountsL.modify(EraAccounts.drainAccounts[E](withdrawals, _))(voted))
    yield ()

  /** `updateDormantDRepExpiries` (`Conway/Rules/Certs.hs:257-268`, cardano-ledger f649f975): with a
    * proposal in the transaction, the dormant epochs are added to every DRep's expiry. Of a
    * transaction of either level, as the reference's is (`Tx l era`), so that Dijkstra's
    * SUBENTITIES runs it on a sub-transaction (`Dijkstra/Rules/SubEntities.hs:267`, cardano-ledger
    * 9df9f98c).
    */
  def updateDormantDRepExpiries[E <: EraTag, L <: TxLevel](
      tx: Tx[L, E],
      currentEpoch: EpochNo,
      certState: CertState[E]
  )(using
      t: EraTx[E],
      b: ConwayEraTxBody[E],
      c: ConwayEraCertState[E],
      lt: t.bodyOf.Level[L],
      lb: b.Level[L]
  ): CertState[E] =
    val body = t.body[L](tx)
    val hasProposals = b.proposalProcedures[L](body).size > 0
    if hasProposals then c.certVStateL.modify(updateDormantDRepExpiry(currentEpoch, _))(certState)
    else certState

  /** `updateVotingDRepExpiries` (`Conway/Rules/Certs.hs:272-292`, cardano-ledger f649f975): each
    * registered DRep that votes in the transaction is active until `computeDRepExpiry` 's epoch. Of
    * a transaction of either level, as [[updateDormantDRepExpiries]].
    */
  def updateVotingDRepExpiries[E <: EraTag, L <: TxLevel](
      tx: Tx[L, E],
      currentEpoch: EpochNo,
      drepActivity: EpochInterval,
      certState: CertState[E]
  )(using
      t: EraTx[E],
      b: ConwayEraTxBody[E],
      c: ConwayEraCertState[E],
      lt: t.bodyOf.Level[L],
      lb: b.Level[L]
  ): Either[VocabError, CertState[E]] =
    val body = t.body[L](tx)
    val numDormantEpochs = c.certVStateL.get(certState).numDormantEpochs
    val dRepsL = c.certVStateL.andThen(VState.dreps)
    b.votingProcedures[L](body)
      .unVotingProcedures
      .keysIterator
      .toVector
      .foldM(dRepsL.get(certState)) { (dreps, voter) =>
        voter match
          case Voter.DRepVoter(credential) =>
            Convert.credential[DRepRole](credential).flatMap { cred =>
              dreps.get(cred) match
                case None => Right(dreps)
                case Some(d) =>
                  Right(
                    dreps.updated(
                      cred,
                      d.copy(expiry =
                        GOVCERT.computeDRepExpiry(drepActivity, currentEpoch, numDormantEpochs)
                      )
                    )
                  )
            }
          case _ => Right(dreps)
      }
      .map(dRepsL.replace(_)(certState))

  /** `updateDormantDRepExpiry` (`Conway/Rules/Certs.hs:308-328`, cardano-ledger f649f975): every
    * DRep's expiry moved up by the dormant epochs in `Word64` arithmetic that wraps (`binOpEpochNo
    * (+)`), unless that is before `currentEpoch`, and the count reset.
    */
  def updateDormantDRepExpiry(currentEpoch: EpochNo, vState: VState): VState =
    val numDormantEpochs = vState.numDormantEpochs
    if numDormantEpochs.word.isZero then vState
    else
      vState.copy(
        dreps = vState.dreps.transform { (_, d) =>
          val actualExpiry = binOpEpochNo(Word64.wrappingAdd)(numDormantEpochs, d.expiry)
          if Order[EpochNo].lt(actualExpiry, currentEpoch) then d
          else d.copy(expiry = actualExpiry)
        },
        numDormantEpochs = EpochNo.fromUnsigned(0L)
      )
