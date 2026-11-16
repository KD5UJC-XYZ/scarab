// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{
  CertsFailure,
  Committee,
  DelegFailure,
  GovAction,
  GovActionId,
  GovActionState,
  GovCertFailure,
  LedgerFailure,
  Mismatch,
  PoolFailure,
  PredicateFailure,
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
  Tx,
  TxCert,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.era.AccountState
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxCert
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Embed, Globals, Judge, JudgedSts, Sts}
import xyz.kd5ujc.scarab.ledger.state.{
  Accounts,
  CertState,
  CommitteeAuthorization,
  CommitteeState,
  CompactCoin,
  Credential,
  DRep,
  DRepRole,
  DRepState,
  DState,
  ColdCommittee,
  EpochInterval,
  EpochNo,
  EraTag,
  KeyHash,
  NonZero,
  PState,
  PoolBlsKey,
  Sorted,
  StakePool,
  StakePoolState,
  Staking,
  VRFVerKeyHash,
  VState,
  VocabError
}
import xyz.kd5ujc.scarab.protocol.StateLayout

// Conway's certificate rules as the reference writes them (cardano-ledger f649f975
// `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/{Certs,Cert,Deleg,GovCert}.hs`, and Shelley's
// POOL, which Conway runs, `eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Pool.hs`), each
// transition generic in its era over the reference's classes. Each rule is a class over its era
// (the reference's `STS (DELEG era)`), and its companion holds the reference's functions of that
// module under their names. The predicates record the node's flat failures, so a judgment is the
// one the flat rules give; a value outside the state's vocabulary is raised as the flat rules raise
// it, and no decoded certificate holds one.

/** The protocol parameters' field `l` (`ppLensHKD . l`). */
private def field[E <: EraTag, A](pp: PParams[E])(
    l: monocle.Lens[xyz.kd5ujc.scarab.ledger.era.PParamsHKD[Identity, E], A]
): A = PParams.lensHKD[E].andThen(l).get(pp)

/** The major protocol version of `pp` (`pvMajor (pp ^. ppProtocolVersionL)`). */
private def major[E <: EraTag](pp: PParams[E])(using p: EraPParams[E]): Int =
  p.ppProtocolVersionL.get(pp).pvMajor.value

private def vocab[F[_]: Monad, A](result: Either[VocabError, A])(using
    Raise[F, LedgerFailure]
): F[A] = Sts.lift[F, LedgerFailure, A](result.leftMap(LedgerFailure.Vocab(_)))

/** `PoolEnv` (`Shelley/Rules/Pool.hs:74-76`, cardano-ledger f649f975). */
final case class PoolEnv[E <: EraTag](epoch: EpochNo, pp: PParams[E])

/** POOL (`Shelley/Rules/Pool.hs`, cardano-ledger f649f975): the registration and retirement of
  * stake pools, over the pool state.
  */
final class POOL[E <: EraTag](using p: EraPParams[E])
    extends JudgedSts[PoolEnv[E], PState, PoolCert, PredicateFailure, LedgerFailure]:
  def transition[F[_]: Monad](env: PoolEnv[E], signal: PoolCert)(using
      Ask[F, Globals],
      Stateful[F, PState],
      Raise[F, LedgerFailure],
      Judge[F, PredicateFailure]
  ): F[Unit] = POOL.poolTransition[E, F](env, signal)

object POOL:
  /** `poolTransition` (`Shelley/Rules/Pool.hs:209-324`, cardano-ledger f649f975). A registration
    * checks the network of the reward account (from version 5), the size of the metadata hash, and
    * the cost; a new pool's VRF key must be unused from version 11, where the rule keeps the map of
    * VRF keys. A pool registers in the layout of the build the node follows.
    */
  def poolTransition[E <: EraTag, F[_]: Monad](env: PoolEnv[E], poolCert: PoolCert)(using
      p: EraPParams[E],
      ask: Ask[F, Globals],
      st: Stateful[F, PState],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    val pp = env.pp
    val pv = p.ppProtocolVersionL.get(pp)
    // `hardforkConwayDisallowDuplicatedVRFKeys` (`Shelley/Era.hs:259-262`, cardano-ledger f649f975)
    val disallowDuplicatedVRFKeys = pv.pvMajor.value > 10
    def fail(f: PoolFailure): PredicateFailure = PredicateFailure.pool(f)
    poolCert match
      case PoolCert.RegPool(stakePoolParams) =>
        val params = stakePoolParams.value
        val sppId = params.operator
        for
          globals <- ask.ask[Globals]
          // `hardforkAlonzoValidatePoolAccountAddressNetID` (`Shelley/Era.hs:248-251`,
          // cardano-ledger f649f975)
          _ <-
            if pv.pvMajor.value > 4 then
              Sts.predicate[F, PredicateFailure](
                globals.networkId == params.rewardAccount.network,
                fail(
                  PoolFailure.WrongNetworkPool(
                    Mismatch(params.rewardAccount.network, globals.networkId),
                    sppId
                  )
                )
              )
            else Monad[F].unit
          // `restrictPoolMetadataHash` (`Shelley/SoftForks.hs:11-14`, cardano-ledger f649f975):
          // above 4.0
          _ <-
            if pv.pvMajor.value > 4 || (pv.pvMajor.value == 4 && pv.pvMinor.value > 0) then
              stakePoolParams.metadataHash.traverse_(hash =>
                Sts.predicate[F, PredicateFailure](
                  hash.size <= 32,
                  fail(PoolFailure.PoolMedataHashTooBig(sppId, hash.size))
                )
              )
            else Monad[F].unit
          minPoolCost = field[E, CompactCoin](pp)(p.hkdMinPoolCostCompactL[Identity]).toBigInt
          _ <- Sts.predicate[F, PredicateFailure](
            params.cost >= minPoolCost,
            fail(PoolFailure.StakePoolCostTooLow(Mismatch(params.cost, minPoolCost)))
          )
          operator <- vocab[F, KeyHash[StakePool]](Convert.keyHash[StakePool](sppId))
          vrf <- vocab[F, VRFVerKeyHash](VRFVerKeyHash.fromBytes(params.vrfKeyHash))
          ps <- st.get
          next <- ps.stakePools.get(operator) match
            case None =>
              for
                _ <-
                  if disallowDuplicatedVRFKeys then
                    Sts.predicate[F, PredicateFailure](
                      !ps.vrfKeyHashes.contains(vrf),
                      fail(PoolFailure.VRFKeyHashAlreadyRegistered(sppId, params.vrfKeyHash))
                    )
                  else Monad[F].unit
                layout <- Sts.lift[F, LedgerFailure, StateLayout](
                  StateLayout
                    .of(globals.ledger.referenceBuild)
                    .leftMap(LedgerFailure.LayoutNotYet(_))
                )
                // `mkStakePoolState (pp ^. ppPoolDepositCompactL) mempty stakePoolParams`, in the
                // layout of the build: in the Leios layout a new pool holds no voting key
                pool <- vocab[F, StakePoolState](
                  Convert.poolState(
                    params,
                    PoolBlsKey.certified(layout),
                    field[E, CompactCoin](pp)(p.hkdPoolDepositCompactL[Identity]),
                    Sorted.emptySet[Credential[Staking]]
                  )
                )
              yield ps.copy(
                stakePools = ps.stakePools.updated(operator, pool),
                vrfKeyHashes =
                  if disallowDuplicatedVRFKeys then ps.vrfKeyHashes.updated(vrf, NonZero.oneWord)
                  else ps.vrfKeyHashes
              )
            case Some(stakePoolState) =>
              for _ <-
                  if disallowDuplicatedVRFKeys then
                    Sts.predicate[F, PredicateFailure](
                      stakePoolState.vrf == vrf || !ps.vrfKeyHashes.contains(vrf),
                      fail(PoolFailure.VRFKeyHashAlreadyRegistered(sppId, params.vrfKeyHash))
                    )
                  else Monad[F].unit
              yield
                // a re-registration with a fresh VRF key records it, and forgets the key of an
                // earlier re-registration of the epoch
                val vrfs =
                  if !disallowDuplicatedVRFKeys then ps.vrfKeyHashes
                  else
                    ps.future.get(operator) match
                      case None => ps.vrfKeyHashes.updated(vrf, NonZero.oneWord)
                      case Some(future) if future.vrfKeyHash != params.vrfKeyHash =>
                        VRFVerKeyHash
                          .fromBytes(future.vrfKeyHash)
                          .fold(_ => ps.vrfKeyHashes, ps.vrfKeyHashes.removed(_))
                          .updated(vrf, NonZero.oneWord)
                      case Some(_) => ps.vrfKeyHashes
                ps.copy(
                  future = ps.future.updated(operator, params),
                  retiring = ps.retiring.removed(operator),
                  vrfKeyHashes = vrfs
                )
          _ <- st.set(next)
        yield ()
      case PoolCert.RetirePool(sppId, e) =>
        val cEpoch = env.epoch.toBigInt
        // `addEpochInterval cEpoch (pp ^. ppEMaxL)`
        val limitEpoch = cEpoch + field[E, EpochInterval](pp)(p.hkdEMaxL[Identity]).toLong
        for
          ps <- st.get
          _ <- Sts.predicate[F, PredicateFailure](
            ps.stakePools.contains(sppId),
            fail(PoolFailure.StakePoolNotRegisteredOnKey(sppId.bytes))
          )
          _ <- Sts.predicate[F, PredicateFailure](
            cEpoch < e.toBigInt && e.toBigInt <= limitEpoch,
            fail(
              PoolFailure.StakePoolRetirementWrongEpoch(
                Mismatch(e.toBigInt, cEpoch),
                Mismatch(e.toBigInt, limitEpoch)
              )
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
final class DELEG[E <: EraTag](using p: EraPParams[E], c: ConwayEraCertState[E])
    extends JudgedSts[ConwayDelegEnv[E], CertState[
      E
    ], ConwayDelegCert, PredicateFailure, LedgerFailure]:
  def transition[F[_]: Monad](env: ConwayDelegEnv[E], signal: ConwayDelegCert)(using
      Ask[F, Globals],
      Stateful[F, CertState[E]],
      Raise[F, LedgerFailure],
      Judge[F, PredicateFailure]
  ): F[Unit] = DELEG.conwayDelegTransition[E, F](env, signal)

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
      st: Stateful[F, CertState[E]],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    given a: ConwayEraAccounts[E] = c.accountsOf
    val pp = env.pp
    val ppKeyDepositCompact = field[E, CompactCoin](pp)(p.hkdKeyDepositCompactL[Identity])
    val ppKeyDeposit = ppKeyDepositCompact.toBigInt
    val pvMajor = major[E](pp)
    // `hardforkConwayDELEGIncorrectDepositsAndRefunds` (`Conway/Era.hs:266-267`, cardano-ledger
    // f649f975)
    val incorrectDepositsAndRefunds = pvMajor > 10
    def fail(f: DelegFailure): PredicateFailure = PredicateFailure.deleg(f)
    def accountsOf(certState: CertState[E]): Accounts[E] = c.certDStateL.get(certState).accounts
    def withAccounts(f: Accounts[E] => Accounts[E])(certState: CertState[E]): CertState[E] =
      c.certDStateL.andThen(DState.accounts[Accounts[E]]).modify(f)(certState)
    def checkDepositAgainstPParams(deposit: BigInt): F[Unit] =
      Sts.predicate[F, PredicateFailure](
        deposit == ppKeyDeposit,
        if incorrectDepositsAndRefunds then
          fail(DelegFailure.DepositIncorrect(Mismatch(deposit, ppKeyDeposit)))
        else fail(DelegFailure.IncorrectDeposit(deposit))
      )
    def checkStakeKeyNotRegistered(certState: CertState[E], stakeCred: Credential[Staking]) =
      Sts.predicate[F, PredicateFailure](
        !EraAccounts.isAccountRegistered[E](stakeCred, accountsOf(certState)),
        fail(DelegFailure.StakeKeyRegistered(Convert.back(stakeCred)))
      )
    def checkStakeDelegateeRegistered(certState: CertState[E], delegatee: Delegatee): F[Unit] =
      def checkPoolRegistered(targetPool: KeyHash[StakePool]) =
        Sts.predicate[F, PredicateFailure](
          env.pools.contains(targetPool),
          fail(DelegFailure.DelegateeStakePoolNotRegistered(targetPool.bytes))
        )
      def checkDRepRegistered(targetDRep: DRep) =
        ConwayEraCertState.dRepToCred(targetDRep).traverse_ { dRepCred =>
          // `unless (hardforkConwayBootstrapPhase ...)` (`Conway/Era.hs:257-258`, cardano-ledger
          // f649f975)
          if pvMajor == 9 then Monad[F].unit
          else
            Sts.predicate[F, PredicateFailure](
              c.certVStateL.get(certState).dreps.contains(dRepCred),
              fail(DelegFailure.DelegateeDRepNotRegistered(Convert.back(dRepCred)))
            )
        }
      delegatee match
        case Delegatee.DelegStake(targetPool) => checkPoolRegistered(targetPool)
        case Delegatee.DelegStakeVote(targetPool, targetDRep) =>
          checkPoolRegistered(targetPool) *> checkDRepRegistered(targetDRep)
        case Delegatee.DelegVote(targetDRep) => checkDRepRegistered(targetDRep)
    def notRegistered(stakeCred: Credential[Staking]): F[Unit] =
      Sts.failBecause[F, PredicateFailure](
        fail(DelegFailure.StakeKeyNotRegistered(Convert.back(stakeCred)))
      )
    st.get.flatMap { certState =>
      cert match
        case ConwayDelegCert.ConwayRegCert(stakeCred, sMayDeposit) =>
          sMayDeposit.traverse_(d => checkDepositAgainstPParams(d.value)) *>
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
            expectedRefund = a.depositAccountStateL.get(accountState).toBigInt
            if suppliedRefund.value != expectedRefund
          yield
            if incorrectDepositsAndRefunds then
              fail(DelegFailure.RefundIncorrect(Mismatch(suppliedRefund.value, expectedRefund)))
            else fail(DelegFailure.IncorrectDeposit(suppliedRefund.value))
          val checkStakeKeyHasZeroRewardBalance = for
            accountState <- mAccountState
            balanceCompact = a.balanceAccountStateL.get(accountState)
            if !balanceCompact.word.isZero
          yield balanceCompact.toBigInt
          Sts.failOnJust[F, PredicateFailure, PredicateFailure](checkInvalidRefund)(identity) *>
            Sts.failOnJust[F, BigInt, PredicateFailure](checkStakeKeyHasZeroRewardBalance)(b =>
              fail(DelegFailure.StakeKeyHasNonZeroAccountBalance(b))
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
          checkDepositAgainstPParams(deposit.value) *>
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
final class GOVCERT[E <: EraTag](using p: ConwayEraPParams[E], c: ConwayEraCertState[E])
    extends JudgedSts[ConwayGovCertEnv[E], CertState[
      E
    ], ConwayGovCert, PredicateFailure, LedgerFailure]:
  def transition[F[_]: Monad](env: ConwayGovCertEnv[E], signal: ConwayGovCert)(using
      Ask[F, Globals],
      Stateful[F, CertState[E]],
      Raise[F, LedgerFailure],
      Judge[F, PredicateFailure]
  ): F[Unit] = GOVCERT.conwayGovCertTransition[E, F](env, signal)

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
      st: Stateful[F, CertState[E]],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    given a: ConwayEraAccounts[E] = c.accountsOf
    val pp = env.pp
    val ppDRepDepositCompact = field[E, CompactCoin](pp)(p.hkdDRepDepositCompactL[Identity])
    val ppDRepDeposit = ppDRepDepositCompact.toBigInt
    val ppDRepActivity = field[E, EpochInterval](pp)(p.hkdDRepActivityL[Identity])
    def fail(f: GovCertFailure): PredicateFailure = PredicateFailure.govCert(f)
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
      Sts.failOnJust[F, Credential[ColdCommittee], PredicateFailure](coldCredResigned)(cold =>
        fail(GovCertFailure.CommitteeHasPreviouslyResigned(Convert.back(cold)))
      ) *>
        Sts.predicate[F, PredicateFailure](
          isCurrentMember || isPotentialFutureMember,
          fail(GovCertFailure.CommitteeIsUnknown(back))
        ) *>
        st.set(committeeCredsL.modify(_.updated(coldCred, newMemberState))(certState))
    st.get.flatMap { certState =>
      val dormant = c.certVStateL.get(certState).numDormantEpochs
      cert match
        case ConwayGovCert.ConwayRegDRep(cred, deposit, mAnchor) =>
          for
            _ <- Sts.predicate[F, PredicateFailure](
              !dRepsL.get(certState).contains(cred),
              fail(GovCertFailure.DRepAlreadyRegistered(Convert.back(cred)))
            )
            _ <- Sts.predicate[F, PredicateFailure](
              deposit.value == ppDRepDeposit,
              fail(GovCertFailure.DRepIncorrectDeposit(Mismatch(deposit.value, ppDRepDeposit)))
            )
            expiry <- vocab[F, EpochNo](
              computeDRepExpiryVersioned[E](pp, env.currentEpoch, dormant)
            )
            drepState = DRepState(
              expiry,
              mAnchor,
              ppDRepDepositCompact,
              Sorted.emptySet[Credential[Staking]]
            )
            _ <- st.set(dRepsL.modify(_.updated(cred, drepState))(certState))
          yield ()
        case ConwayGovCert.ConwayUnRegDRep(cred, refund) =>
          val mDRepState = dRepsL.get(certState).get(cred)
          val drepRefundMismatch =
            mDRepState.map(_.deposit.toBigInt).filter(_ != refund.value)
          val certState1 = dRepsL.modify(_ - cred)(certState)
          for
            _ <- Sts.predicate[F, PredicateFailure](
              mDRepState.isDefined,
              fail(GovCertFailure.DRepNotRegistered(Convert.back(cred)))
            )
            _ <- Sts.failOnJust[F, BigInt, PredicateFailure](drepRefundMismatch)(paid =>
              fail(GovCertFailure.DRepIncorrectRefund(Mismatch(refund.value, paid)))
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
          Sts.predicate[F, PredicateFailure](
            dRepsL.get(certState).contains(cred),
            fail(GovCertFailure.DRepNotRegistered(Convert.back(cred)))
          ) *>
            dRepsL.get(certState).get(cred).traverse_ { drepState =>
              vocab[F, EpochNo](computeDRepExpiry(ppDRepActivity, env.currentEpoch, dormant))
                .flatMap(expiry =>
                  st.set(
                    dRepsL.modify(
                      _.updated(cred, drepState.copy(expiry = expiry, anchor = mAnchor))
                    )(certState)
                  )
                )
            }
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
    * the bootstrap phase (version 9) the dormant epochs are not taken off. An expiry outside
    * `EpochNo` is refused here, where the reference's `Word64` arithmetic wraps; the flat rules do
    * the same, and no epoch reaches it.
    */
  def computeDRepExpiryVersioned[E <: EraTag](
      pp: PParams[E],
      currentEpoch: EpochNo,
      numDormantEpochs: EpochNo
  )(using p: ConwayEraPParams[E]): Either[VocabError, EpochNo] =
    val ppDRepActivity = field[E, EpochInterval](pp)(p.hkdDRepActivityL[Identity])
    if major[E](pp) == 9 then EpochNo.fromBigInt(currentEpoch.toBigInt + ppDRepActivity.toLong)
    else computeDRepExpiry(ppDRepActivity, currentEpoch, numDormantEpochs)

  /** `computeDRepExpiry` (`Conway/Rules/GovCert.hs:294-306`, cardano-ledger f649f975): the epoch a
    * DRep acting now is active until, the dormant epochs taken off.
    */
  def computeDRepExpiry(
      ppDRepActivity: EpochInterval,
      currentEpoch: EpochNo,
      numDormantEpochs: EpochNo
  ): Either[VocabError, EpochNo] =
    EpochNo.fromBigInt(
      currentEpoch.toBigInt + ppDRepActivity.toLong - numDormantEpochs.toBigInt
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
final class CERT[E <: EraTag](using
    ev: TxCert[E] =:= ConwayTxCert,
    p: ConwayEraPParams[E],
    c: ConwayEraCertState[E]
) extends JudgedSts[CertEnv[E], CertState[E], TxCert[E], PredicateFailure, LedgerFailure]:
  def transition[F[_]: Monad](env: CertEnv[E], signal: TxCert[E])(using
      Ask[F, Globals],
      Stateful[F, CertState[E]],
      Raise[F, LedgerFailure],
      Judge[F, PredicateFailure]
  ): F[Unit] = CERT.certTransition[E, F](env, signal)

object CERT:
  /** `certTransition` (`Conway/Rules/Cert.hs:192-223`, cardano-ledger f649f975): DELEG and GOVCERT
    * on the certificate state, POOL on the pool state alone (`certPStateL`), each a sub-rule.
    */
  def certTransition[E <: EraTag, F[_]: Monad](env: CertEnv[E], txCert: TxCert[E])(using
      ev: TxCert[E] =:= ConwayTxCert,
      p: ConwayEraPParams[E],
      c: ConwayEraCertState[E],
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[E]],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    st.get.flatMap { certState =>
      val pools = c.certPStateL.get(certState).stakePools
      ev(txCert) match
        case ConwayTxCert.ConwayTxCertDeleg(delegCert) =>
          JudgedSts.trans[
            F,
            ConwayDelegEnv[E],
            CertState[E],
            CertState[E],
            ConwayDelegCert,
            PredicateFailure,
            LedgerFailure,
            LedgerFailure
          ](DELEG[E], ConwayDelegEnv[E](env.pp, pools), delegCert)
        case ConwayTxCert.ConwayTxCertPool(poolCert) =>
          JudgedSts.trans[
            F,
            PoolEnv[E],
            PState,
            CertState[E],
            PoolCert,
            PredicateFailure,
            LedgerFailure,
            LedgerFailure
          ](POOL[E], PoolEnv[E](env.currentEpoch, env.pp), poolCert)(using
            Monad[F],
            Embed(c.certPStateL, identity)
          )
        case ConwayTxCert.ConwayTxCertGov(govCert) =>
          JudgedSts.trans[
            F,
            ConwayGovCertEnv[E],
            CertState[E],
            CertState[E],
            ConwayGovCert,
            PredicateFailure,
            LedgerFailure,
            LedgerFailure
          ](
            GOVCERT[E],
            ConwayGovCertEnv[E](
              env.pp,
              env.currentEpoch,
              env.currentCommittee,
              env.committeeProposals
            ),
            govCert
          )
    }

/** `CertsEnv` (`Conway/Rules/Certs.hs:87-95`, cardano-ledger f649f975). */
final case class CertsEnv[E <: EraTag](
    tx: Tx[TxLevel.Top, E],
    pp: PParams[E],
    currentEpoch: EpochNo,
    currentCommittee: Option[Committee],
    committeeProposals: Map[GovActionId, GovActionState]
)

/** CERTS (`Conway/Rules/Certs.hs`, cardano-ledger f649f975): a transaction's certificates. */
final class CERTS[E <: EraTag](using
    t: EraTx[E],
    b: ConwayEraTxBody[E],
    ev: TxCert[E] =:= ConwayTxCert,
    p: ConwayEraPParams[E],
    c: ConwayEraCertState[E]
) extends JudgedSts[CertsEnv[E], CertState[E], Vector[TxCert[E]], PredicateFailure, LedgerFailure]:
  def transition[F[_]: Monad](env: CertsEnv[E], signal: Vector[TxCert[E]])(using
      Ask[F, Globals],
      Stateful[F, CertState[E]],
      Raise[F, LedgerFailure],
      Judge[F, PredicateFailure]
  ): F[Unit] = CERTS.conwayCertsTransition[E, F](env, signal)

object CERTS:
  /** `conwayCertsTransition` (`Conway/Rules/Certs.hs:203-246`, cardano-ledger f649f975): by
    * recursion on the prefix (`gamma :|> txCert`), CERTS of all but the last certificate as a
    * sub-rule, then CERT of the last, so the first certificate is the deepest. The empty sequence
    * is the base case: before version 11 the withdrawals must drain their accounts, the DReps'
    * expiries are updated and the accounts drained; from 11 that is LEDGER's
    * (`hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule`, `Conway/Era.hs:283-284`). The
    * recursion is a fold, each step a program over the previous, which the interpreter runs in
    * constant stack.
    */
  def conwayCertsTransition[E <: EraTag, F[_]: Monad](
      env: CertsEnv[E],
      certificates: Vector[TxCert[E]]
  )(using
      t: EraTx[E],
      b: ConwayEraTxBody[E],
      ev: TxCert[E] =:= ConwayTxCert,
      p: ConwayEraPParams[E],
      c: ConwayEraCertState[E],
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[E]],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    val pp = env.pp
    val base = if major[E](pp) > 10 then Monad[F].unit else empty[E, F](env)
    val certEnv = CertEnv[E](pp, env.currentEpoch, env.currentCommittee, env.committeeProposals)
    certificates.foldLeft(base)((gamma, txCert) =>
      judge.nest(gamma) *>
        JudgedSts.trans[
          F,
          CertEnv[E],
          CertState[E],
          CertState[E],
          TxCert[E],
          PredicateFailure,
          LedgerFailure,
          LedgerFailure
        ](CERT[E], certEnv, txCert)
    )

  /** The base case before version 11 (`Conway/Rules/Certs.hs:228-244`, cardano-ledger f649f975). */
  private def empty[E <: EraTag, F[_]: Monad](env: CertsEnv[E])(using
      t: EraTx[E],
      b: ConwayEraTxBody[E],
      p: ConwayEraPParams[E],
      c: ConwayEraCertState[E],
      ask: Ask[F, Globals],
      st: Stateful[F, CertState[E]],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    given a: ConwayEraAccounts[E] = c.accountsOf
    val body = t.body[TxLevel.Top](env.tx)(using t.bodyOf.top)
    val withdrawals = b.withdrawals[TxLevel.Top](body)(using b.top)
    val accountsL = c.certDStateL.andThen(DState.accounts[Accounts[E]])
    for
      network <- ask.ask[Globals].map(_.networkId)
      certState <- st.get
      bad <- vocab[F, Option[EraAccounts.Undrained]](
        EraAccounts.withdrawalsThatDoNotDrainAccounts[E](
          withdrawals,
          network,
          accountsL.get(certState)
        )
      )
      _ <- Sts.failOnJust[F, EraAccounts.Undrained, PredicateFailure](bad) {
        (invalid, incomplete) =>
          PredicateFailure.Certs(
            CertsFailure.WithdrawalsNotInRewards(
              invalid.view.mapValues(_.value).toMap ++ incomplete.view.mapValues(_.supplied.value)
            )
          )
      }
      dormant = updateDormantDRepExpiries[E](env.tx, env.currentEpoch, certState)
      voted <- vocab[F, CertState[E]](
        dormant.flatMap(
          updateVotingDRepExpiries[E](
            env.tx,
            env.currentEpoch,
            field[E, EpochInterval](env.pp)(p.hkdDRepActivityL[Identity]),
            _
          )
        )
      )
      _ <- st.set(accountsL.modify(EraAccounts.drainAccounts[E](withdrawals, _))(voted))
    yield ()

  /** `updateDormantDRepExpiries` (`Conway/Rules/Certs.hs:257-268`, cardano-ledger f649f975): with a
    * proposal in the transaction, the dormant epochs are added to every DRep's expiry.
    */
  def updateDormantDRepExpiries[E <: EraTag](
      tx: Tx[TxLevel.Top, E],
      currentEpoch: EpochNo,
      certState: CertState[E]
  )(using
      t: EraTx[E],
      b: ConwayEraTxBody[E],
      c: ConwayEraCertState[E]
  ): Either[VocabError, CertState[E]] =
    val body = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
    val hasProposals = b.proposalProcedures[TxLevel.Top](body)(using b.top).size > 0
    if hasProposals then
      updateDormantDRepExpiry(currentEpoch, c.certVStateL.get(certState))
        .map(c.certVStateL.replace(_)(certState))
    else Right(certState)

  /** `updateVotingDRepExpiries` (`Conway/Rules/Certs.hs:272-292`, cardano-ledger f649f975): each
    * registered DRep that votes in the transaction is active until `computeDRepExpiry` 's epoch.
    */
  def updateVotingDRepExpiries[E <: EraTag](
      tx: Tx[TxLevel.Top, E],
      currentEpoch: EpochNo,
      drepActivity: EpochInterval,
      certState: CertState[E]
  )(using
      t: EraTx[E],
      b: ConwayEraTxBody[E],
      c: ConwayEraCertState[E]
  ): Either[VocabError, CertState[E]] =
    val body = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
    val numDormantEpochs = c.certVStateL.get(certState).numDormantEpochs
    val dRepsL = c.certVStateL.andThen(VState.dreps)
    b.votingProcedures[TxLevel.Top](body)(using b.top)
      .keysIterator
      .toVector
      .foldM(dRepsL.get(certState)) { (dreps, voter) =>
        voter match
          case Voter.DRepVoter(credential) =>
            Convert.credential[DRepRole](credential).flatMap { cred =>
              dreps.get(cred) match
                case None => Right(dreps)
                case Some(d) =>
                  GOVCERT
                    .computeDRepExpiry(drepActivity, currentEpoch, numDormantEpochs)
                    .map(e => dreps.updated(cred, d.copy(expiry = e)))
            }
          case _ => Right(dreps)
      }
      .map(dRepsL.replace(_)(certState))

  /** `updateDormantDRepExpiry` (`Conway/Rules/Certs.hs:308-328`, cardano-ledger f649f975): every
    * DRep's expiry moved up by the dormant epochs, unless that is still before `currentEpoch`, and
    * the count reset.
    */
  def updateDormantDRepExpiry(currentEpoch: EpochNo, vState: VState): Either[VocabError, VState] =
    val numDormantEpochs = vState.numDormantEpochs
    if numDormantEpochs.word.isZero then Right(vState)
    else
      vState.dreps.toList
        .traverse { (cred, d) =>
          val actualExpiry = d.expiry.toBigInt + numDormantEpochs.toBigInt
          if actualExpiry < currentEpoch.toBigInt then Right(cred -> d)
          else EpochNo.fromBigInt(actualExpiry).map(e => cred -> d.copy(expiry = e))
        }
        .flatMap(dreps =>
          EpochNo
            .fromBigInt(0)
            .map(zero => vState.copy(dreps = Sorted.map(dreps), numDormantEpochs = zero))
        )
