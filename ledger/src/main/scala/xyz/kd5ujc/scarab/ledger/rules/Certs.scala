// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Certificate,
  Committee,
  DelegFailure,
  GovCertFailure,
  GovAction,
  LedgerFailure,
  Mismatch,
  PoolFailure,
  PoolParams,
  PredicateFailure,
  ProtocolParams,
  Proposals,
  RewardAccount,
  Tx,
  TxDefect
}
import xyz.kd5ujc.scarab.ledger.state.*

// CERTS and the rules under it for Conway: `Conway/Rules/Certs.hs`, `Cert.hs`, `Deleg.hs`,
// `GovCert.hs` and (the Shelley rule, in every era) `Shelley/Rules/Pool.hs`. They run on the
// Conway certificate state (`ConwayCertState`), which is where the tick reads what they maintain:
// the delegators of each pool (`spsDelegators`: SNAP counts them, POOLREAP clears them), the
// delegators of each DRep, their expiry and the dormant epochs (the DRep pulser), the pools' VRF
// key hashes, and the accounts.

/** The environment of CERTS' sub-rules: `CertEnv` (`Cert.hs`) and `ConwayGovCertEnv` (`GovCert.hs`)
  * in one: the parameters in force, the current epoch, the current committee and the proposals of
  * the committee's lineage, and the transaction they are for (the failures name it).
  */
final case class CertEnv(
    id: Bytes,
    pp: ProtocolParams,
    epoch: BigInt,
    committee: Option[Committee],
    proposals: Proposals
)

/** `CertsEnv` (`Certs.hs:87`): the transaction too, for the withdrawals the rule drains. */
final case class CertsEnv(tx: Tx, cert: CertEnv)

/** `Delegatee` (`Conway/TxCert.hs`): what a delegation certificate delegates to. */
enum Delegatee:
  case Stake(pool: KeyHash[StakePool])
  case Vote(drep: DRep)
  case StakeVote(pool: KeyHash[StakePool], drep: DRep)

  def stakePool: Option[KeyHash[StakePool]] = this match
    case Stake(p)        => Some(p)
    case Vote(_)         => None
    case StakeVote(p, _) => Some(p)

  def votedDRep: Option[DRep] = this match
    case Stake(_)        => None
    case Vote(d)         => Some(d)
    case StakeVote(_, d) => Some(d)

/** `ConwayDelegCert` (`Conway/TxCert.hs`): the delegation certificates. The deposits are the ones
  * the certificate carries (a certificate without one is the deposit-less form of the era's
  * registration).
  */
enum DelegCert:
  case Reg(credential: Credential[Staking], deposit: Option[BigInt])
  case UnReg(credential: Credential[Staking], refund: Option[BigInt])
  case Deleg(credential: Credential[Staking], delegatee: Delegatee)
  case RegDeleg(credential: Credential[Staking], delegatee: Delegatee, deposit: BigInt)

/** `PoolCert` (`Shelley/TxCert.hs`). */
enum PoolCert:
  case RegPool(params: PoolParams)
  case RetirePool(pool: KeyHash[StakePool], epoch: BigInt)

/** `ConwayGovCert` (`Conway/TxCert.hs`). */
enum ConwayGovCert:
  case RegDRep(credential: Credential[DRepRole], deposit: BigInt, anchor: Option[Anchor])
  case UnRegDRep(credential: Credential[DRepRole], refund: BigInt)
  case UpdateDRep(credential: Credential[DRepRole], anchor: Option[Anchor])
  case AuthCommitteeHotKey(cold: Credential[ColdCommittee], hot: Credential[HotCommittee])
  case ResignCommitteeColdKey(cold: Credential[ColdCommittee], anchor: Option[Anchor])

/** `ConwayTxCert`: the three families a Conway certificate is of. */
enum ConwayCert:
  case OfDeleg(cert: DelegCert)
  case OfPool(cert: PoolCert)
  case OfGov(cert: ConwayGovCert)

object ConwayCert:
  /** The certificate of a decoded transaction in the state's vocabulary, or why it is not one: a
    * hash that is not one (the vocabulary's), or a certificate of the eras before Conway (the
    * reference's decoder refuses those in a Conway transaction, `DecoderError`).
    */
  def of(id: Bytes, cert: Certificate): Either[LedgerFailure, ConwayCert] =
    def v[A](r: Either[VocabError, A]): Either[LedgerFailure, A] = r.leftMap(LedgerFailure.Vocab(_))
    def staking(c: node.Credential) = v(Convert.credential[Staking](c))
    def delegatee(
        pool: Option[Bytes],
        drep: Option[node.DRep]
    ): Either[LedgerFailure, Delegatee] =
      (
        pool.traverse(p => v(Convert.keyHash[StakePool](p))),
        drep.traverse(d => v(Convert.drep(d)))
      ).tupled
        .flatMap {
          case (Some(p), Some(d)) => Right(Delegatee.StakeVote(p, d))
          case (Some(p), None)    => Right(Delegatee.Stake(p))
          case (None, Some(d))    => Right(Delegatee.Vote(d))
          case (None, None) =>
            Left(LedgerFailure.Undecodable(id, TxDefect.DelegationWithoutDelegatee))
        }
    cert match
      case Certificate.Register(c, d)   => staking(c).map(c => OfDeleg(DelegCert.Reg(c, d)))
      case Certificate.Unregister(c, r) => staking(c).map(c => OfDeleg(DelegCert.UnReg(c, r)))
      case Certificate.Delegate(c, pool, drep, None) =>
        (staking(c), delegatee(pool, drep)).mapN((c, d) => OfDeleg(DelegCert.Deleg(c, d)))
      case Certificate.Delegate(c, pool, drep, Some(deposit)) =>
        (staking(c), delegatee(pool, drep)).mapN((c, d) =>
          OfDeleg(DelegCert.RegDeleg(c, d, deposit))
        )
      case Certificate.RegisterPool(p) => Right(OfPool(PoolCert.RegPool(p)))
      case Certificate.RetirePool(p, e) =>
        v(Convert.keyHash[StakePool](p)).map(p => OfPool(PoolCert.RetirePool(p, e)))
      case Certificate.RegisterDRep(c, deposit, anchor) =>
        v(Convert.credential[DRepRole](c)).map(c =>
          OfGov(ConwayGovCert.RegDRep(c, deposit, anchor))
        )
      case Certificate.UnregisterDRep(c, refund) =>
        v(Convert.credential[DRepRole](c)).map(c => OfGov(ConwayGovCert.UnRegDRep(c, refund)))
      case Certificate.UpdateDRep(c, anchor) =>
        v(Convert.credential[DRepRole](c)).map(c => OfGov(ConwayGovCert.UpdateDRep(c, anchor)))
      case Certificate.CommitteeAuthorize(cold, hot) =>
        (v(Convert.credential[ColdCommittee](cold)), v(Convert.credential[HotCommittee](hot)))
          .mapN((cold, hot) => OfGov(ConwayGovCert.AuthCommitteeHotKey(cold, hot)))
      case Certificate.CommitteeResign(cold, anchor) =>
        v(Convert.credential[ColdCommittee](cold))
          .map(cold => OfGov(ConwayGovCert.ResignCommitteeColdKey(cold, anchor)))
      case Certificate.GenesisDelegate(_, _, _) | Certificate.MoveRewards(_, _) =>
        Left(LedgerFailure.Undecodable(id, TxDefect.PreConwayCertificate(cert)))

/** The pure changes the certificates make to the Conway certificate state, each the Haskell
  * function of the same name (cited), so that the rules read as the reference does and the changes
  * are tested apart from the rules.
  */
object CertOps:
  private val zeroCoin: CompactCoin = CompactCoin.fromUnsigned(0L)

  /** `dRepToCred`: the credential of a DRep that has one (a registered one, not the two
    * predefined).
    */
  def dRepCredential(d: DRep): Option[Credential[DRepRole]] = d match
    case DRep.DRepKeyHash(h)    => Some(Credential.KeyHashObj[DRepRole](h))
    case DRep.DRepScriptHash(h) => Some(Credential.ScriptHashObj[DRepRole](h))
    case DRep.DRepAlwaysAbstain | DRep.DRepAlwaysNoConfidence => None

  /** `registerConwayAccount` (`Conway/State/Account.hs:306`): a new account with no balance; a
    * delegatee given at the registration is in the account (the delegators' sets are
    * [[delegate]]'s).
    */
  def register(
      s: ConwayCertState,
      c: Credential[Staking],
      deposit: CompactCoin,
      delegatee: Option[Delegatee]
  ): ConwayCertState =
    CertLens.accounts.modify(
      _.updated(
        c,
        ConwayAccountState(
          zeroCoin,
          deposit,
          delegatee.flatMap(_.stakePool),
          delegatee.flatMap(_.votedDRep)
        )
      )
    )(s)

  /** `unDelegReDelegStakePool` (`State/CertState.hs:281`): the account `c`, whose state before was
    * `account`, stops delegating to the pool it did and (if `next` is a pool) delegates to that
    * one; no change where the pool is the same.
    */
  def reDelegatePool(
      s: PState,
      c: Credential[Staking],
      account: ConwayAccountState,
      next: Option[KeyHash[StakePool]]
  ): PState = reDelegatePool(s, c, account.pool, next)

  /** [[reDelegatePool]] for the account of any era, which is the pool it delegates to that matters
    * (Shelley's DELEG runs the same function, `Shelley/Rules/Deleg.hs`).
    */
  def reDelegatePool(
      s: PState,
      c: Credential[Staking],
      current: Option[KeyHash[StakePool]],
      next: Option[KeyHash[StakePool]]
  ): PState =
    def adjust(
        pools: SortedMap[KeyHash[StakePool], StakePoolState],
        pool: KeyHash[StakePool],
        f: SortedSet[Credential[Staking]] => SortedSet[Credential[Staking]]
    ) = pools.updatedWith(pool)(_.map(StakePoolState.delegators.modify(f)))
    def addNew(pools: SortedMap[KeyHash[StakePool], StakePoolState]) =
      next.fold(pools)(adjust(pools, _, _ + c))
    PState.stakePools.modify { pools =>
      current match
        case Some(pool) if next.contains(pool) => pools
        case Some(pool)                        => addNew(adjust(pools, pool, _ - c))
        case None                              => addNew(pools)
    }(s)

  /** `unDelegReDelegDRep` (`Conway/State/VState.hs:120`): the same for the DRep. */
  def reDelegateDRep(
      s: VState,
      c: Credential[Staking],
      account: ConwayAccountState,
      next: Option[DRep]
  ): VState =
    def adjust(
        dreps: SortedMap[Credential[DRepRole], DRepState],
        drep: Credential[DRepRole],
        f: SortedSet[Credential[Staking]] => SortedSet[Credential[Staking]]
    ) = dreps.updatedWith(drep)(_.map(DRepState.delegators.modify(f)))
    def addNew(dreps: SortedMap[Credential[DRepRole], DRepState]) =
      next.flatMap(dRepCredential).fold(dreps)(adjust(dreps, _, _ + c))
    VState.dreps.modify { dreps =>
      account.drep.flatMap(d => dRepCredential(d).map(d -> _)) match
        case Some((current, _)) if next.contains(current) => dreps
        case Some((_, credential)) => addNew(adjust(dreps, credential, _ - c))
        case None                  => addNew(dreps)
    }(s)

  /** `processDelegationInternal` (`Deleg.hs:320`): `c` delegates to `delegatee`, the old
    * delegations cleaned up. `current` is the account as it was (none for a registration in the
    * same certificate); `preserveIncorrect` keeps the behaviour of the bootstrap phase, where a
    * DRep delegation did not remove the old DRep's reverse delegation (`#4772`).
    */
  def delegate(
      s: ConwayCertState,
      c: Credential[Staking],
      current: Option[ConwayAccountState],
      delegatee: Delegatee,
      preserveIncorrect: Boolean
  ): ConwayCertState =
    val withPool = delegatee.stakePool.fold(s) { pool =>
      val moved = CertLens.accounts.modify(
        _.updatedWith(c)(_.map(ConwayAccountState.pool.replace(Some(pool))))
      )(s)
      current.fold(
        // a fresh account: only the new reverse delegation
        ConwayCertState.pState.modify(
          PState.stakePools.modify(
            _.updatedWith(pool)(_.map(StakePoolState.delegators.modify(_ + c)))
          )
        )(moved)
      )(account => ConwayCertState.pState.modify(reDelegatePool(_, c, account, Some(pool)))(moved))
    }
    delegatee.votedDRep.fold(withPool) { drep =>
      val moved =
        CertLens.accounts.modify(
          _.updatedWith(c)(_.map(ConwayAccountState.drep.replace(Some(drep))))
        )(withPool)
      dRepCredential(drep) match
        case Some(credential) if current.isEmpty || preserveIncorrect =>
          CertLens.dreps.modify(
            _.updatedWith(credential)(_.map(DRepState.delegators.modify(_ + c)))
          )(moved)
        case _ =>
          current.fold(moved)(account =>
            ConwayCertState.vState.modify(reDelegateDRep(_, c, account, Some(drep)))(moved)
          )
    }

  /** `unregisterConwayAccount` and the two un-delegations (`Deleg.hs:240-272`). */
  def unregister(
      s: ConwayCertState,
      c: Credential[Staking],
      account: ConwayAccountState
  ): ConwayCertState =
    val removed = CertLens.accounts.modify(_ - c)(s)
    val reDRep = ConwayCertState.vState.modify(reDelegateDRep(_, c, account, None))(removed)
    ConwayCertState.pState.modify(reDelegatePool(_, c, account, None))(reDRep)

/** DELEG (`Conway/Rules/Deleg.hs:177`, `conwayDelegTransition`): registration, deregistration and
  * delegation of stake credentials, and the maintenance of the pools' and DReps' delegators.
  */
object Deleg extends Sts[CertEnv, ConwayCertState, DelegCert, LedgerFailure]:
  def transition[F[_]: Monad](env: CertEnv, cert: DelegCert)(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayCertState],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    val pp = env.pp
    val id = env.id
    val v10 = pp.protocolMajor > 10
    def credential(c: Credential[Staking]): node.Credential = Convert.back(c)
    // `checkDepositAgainstPParams` (`Deleg.hs:199`)
    def depositMatches(declared: BigInt): F[Unit] =
      Refuse.unless[F](
        declared == pp.keyDeposit,
        id,
        if v10 then
          PredicateFailure.deleg(DelegFailure.DepositIncorrect(Mismatch(declared, pp.keyDeposit)))
        else PredicateFailure.deleg(DelegFailure.IncorrectDeposit(declared))
      )
    // `checkStakeKeyNotRegistered`
    def notRegistered(s: ConwayCertState, c: Credential[Staking]): F[Unit] =
      Refuse.unless[F](
        !CertLens.accounts.get(s).contains(c),
        id,
        PredicateFailure.deleg(DelegFailure.StakeKeyRegistered(credential(c)))
      )
    // `checkStakeDelegateeRegistered` (`Deleg.hs:215`): the pool, then (but not in the bootstrap
    // phase) the DRep
    def delegateeRegistered(s: ConwayCertState, d: Delegatee): F[Unit] =
      def pool(p: KeyHash[StakePool]) =
        Refuse.unless[F](
          CertLens.pools.get(s).contains(p),
          id,
          PredicateFailure.deleg(DelegFailure.DelegateeStakePoolNotRegistered(p.bytes))
        )
      def drep(d: DRep) = CertOps.dRepCredential(d).traverse_ { credential =>
        Refuse.unless[F](
          pp.protocolMajor == 9 || CertLens.dreps.get(s).contains(credential),
          id,
          PredicateFailure.deleg(DelegFailure.DelegateeDRepNotRegistered(Convert.back(credential)))
        )
      }
      d.stakePool.traverse_(pool) *> d.votedDRep.traverse_(drep)
    def deposit: F[CompactCoin] =
      Refuse.vocab[F, CompactCoin](CompactCoin.fromBigInt(pp.keyDeposit))
    val preserve = pp.protocolMajor < 10
    st.get.flatMap { s =>
      cert match
        case DelegCert.Reg(c, declared) =>
          for
            _ <- declared.traverse_(depositMatches)
            _ <- notRegistered(s, c)
            d <- deposit
            _ <- st.set(CertOps.register(s, c, d, None))
          yield ()
        case DelegCert.UnReg(c, refund) =>
          CertLens.accounts.get(s).get(c) match
            case None =>
              Refuse[F, Unit](
                id,
                PredicateFailure.deleg(DelegFailure.StakeKeyNotRegistered(credential(c)))
              )
            case Some(account) =>
              val held = account.deposit.toBigInt
              for
                _ <- refund.traverse_(supplied =>
                  Refuse.unless[F](
                    supplied == held,
                    id,
                    if v10 then
                      PredicateFailure.deleg(
                        DelegFailure.RefundIncorrect(Mismatch(supplied, held))
                      )
                    else PredicateFailure.deleg(DelegFailure.IncorrectDeposit(supplied))
                  )
                )
                _ <- Refuse.unless[F](
                  account.balance.word.isZero,
                  id,
                  PredicateFailure.deleg(
                    DelegFailure.StakeKeyHasNonZeroAccountBalance(account.balance.toBigInt)
                  )
                )
                _ <- st.set(CertOps.unregister(s, c, account))
              yield ()
        case DelegCert.Deleg(c, delegatee) =>
          for
            _ <- delegateeRegistered(s, delegatee)
            account <- CertLens.accounts.get(s).get(c) match
              case Some(a) => a.pure[F]
              case None =>
                Refuse[F, ConwayAccountState](
                  id,
                  PredicateFailure.deleg(DelegFailure.StakeKeyNotRegistered(credential(c)))
                )
            _ <- st.set(CertOps.delegate(s, c, Some(account), delegatee, preserve))
          yield ()
        case DelegCert.RegDeleg(c, delegatee, declared) =>
          for
            _ <- depositMatches(declared)
            _ <- notRegistered(s, c)
            _ <- delegateeRegistered(s, delegatee)
            d <- deposit
            registered = CertOps.register(s, c, d, Some(delegatee))
            _ <- st.set(CertOps.delegate(registered, c, None, delegatee, preserve))
          yield ()
    }

/** POOL (`Shelley/Rules/Pool.hs:209`, `poolTransition`, the rule of every era): registration and
  * retirement of stake pools, with the VRF key hashes kept from protocol version 11
  * (`hardforkConwayDisallowDuplicatedVRFKeys`, `Pool.hs:265-305`). The map of key hashes is
  * populated by the hard fork into 11 (`HardFork.hs:75-78`, J3.3): until then, before version 11,
  * it is not kept, as in the reference.
  */
object Pool extends Sts[CertEnv, PState, PoolCert, LedgerFailure]:
  def transition[F[_]: Monad](env: CertEnv, cert: PoolCert)(using
      ask: Ask[F, Globals],
      st: Stateful[F, PState],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    val pp = env.pp
    val id = env.id
    val unique = pp.protocolMajor >= 11
    cert match
      case PoolCert.RegPool(params) =>
        for
          globals <- Sts.globals[F]
          _ <- Refuse.unless[F](
            !pp.validatesPoolRewardAccountNetwork || params.rewardAccount.network == globals.networkId,
            id,
            PredicateFailure.pool(
              PoolFailure.WrongNetworkPool(
                Mismatch(params.rewardAccount.network, globals.networkId),
                params.operator
              )
            )
          )
          _ <- Refuse.unless[F](
            params.cost >= pp.minPoolCost,
            id,
            PredicateFailure.pool(
              PoolFailure.StakePoolCostTooLow(Mismatch(params.cost, pp.minPoolCost))
            )
          )
          operator <- Refuse.vocab[F, KeyHash[StakePool]](
            Convert.keyHash[StakePool](params.operator)
          )
          vrf <- Refuse.vocab[F, VRFVerKeyHash](VRFVerKeyHash.fromBytes(params.vrfKeyHash))
          s <- st.get
          next <- s.stakePools.get(operator) match
            case None =>
              for
                _ <- Refuse.unless[F](
                  !unique || !s.vrfKeyHashes.contains(vrf),
                  id,
                  PredicateFailure.pool(
                    PoolFailure.VRFKeyHashAlreadyRegistered(params.operator, params.vrfKeyHash)
                  )
                )
                deposit <- Refuse.vocab[F, CompactCoin](CompactCoin.fromBigInt(pp.poolDeposit))
                pool <- Refuse.vocab[F, StakePoolState](
                  Convert.poolState(params, deposit, Sorted.emptySet[Credential[Staking]])
                )
              yield s.copy(
                stakePools = s.stakePools.updated(operator, pool),
                vrfKeyHashes =
                  if unique then s.vrfKeyHashes.updated(vrf, NonZero.oneWord) else s.vrfKeyHashes
              )
            case Some(current) =>
              for _ <- Refuse.unless[F](
                  !unique || current.vrf == vrf || !s.vrfKeyHashes.contains(vrf),
                  id,
                  PredicateFailure.pool(
                    PoolFailure.VRFKeyHashAlreadyRegistered(params.operator, params.vrfKeyHash)
                  )
                )
              yield
                // a re-registration with another key records it, and forgets the key of an earlier
                // re-registration of the epoch
                val vrfs =
                  if !unique then s.vrfKeyHashes
                  else
                    s.future.get(operator) match
                      case None => s.vrfKeyHashes.updated(vrf, NonZero.oneWord)
                      case Some(earlier) if earlier.vrfKeyHash != params.vrfKeyHash =>
                        VRFVerKeyHash
                          .fromBytes(earlier.vrfKeyHash)
                          .fold(
                            _ => s.vrfKeyHashes,
                            e => s.vrfKeyHashes.removed(e)
                          )
                          .updated(vrf, NonZero.oneWord)
                      case Some(_) => s.vrfKeyHashes
                s.copy(
                  future = s.future.updated(operator, params),
                  retiring = s.retiring.removed(operator),
                  vrfKeyHashes = vrfs
                )
          _ <- st.set(next)
        yield ()
      case PoolCert.RetirePool(pool, epoch) =>
        for
          s <- st.get
          _ <- Refuse.unless[F](
            s.stakePools.contains(pool),
            id,
            PredicateFailure.pool(PoolFailure.StakePoolNotRegisteredOnKey(pool.bytes))
          )
          limit = env.epoch + pp.eMax
          _ <- Refuse.unless[F](
            env.epoch < epoch && epoch <= limit,
            id,
            PredicateFailure.pool(
              PoolFailure.StakePoolRetirementWrongEpoch(
                Mismatch(epoch, env.epoch),
                Mismatch(epoch, limit)
              )
            )
          )
          e <- Refuse.vocab[F, EpochNo](EpochNo.fromBigInt(epoch))
          _ <- st.set(s.copy(retiring = s.retiring.updated(pool, e)))
        yield ()

/** GOVCERT (`Conway/Rules/GovCert.hs:170`, `conwayGovCertTransition`): the DRep and the committee
  * certificates.
  */
object GovCert extends Sts[CertEnv, ConwayCertState, ConwayGovCert, LedgerFailure]:
  /** `computeDRepExpiry`: the epoch a DRep that acts now is active until, discounting the dormant
    * epochs.
    */
  private def expiry(pp: ProtocolParams, epoch: BigInt, dormant: EpochNo, versioned: Boolean) =
    EpochNo.fromBigInt(
      if versioned && pp.protocolMajor == 9 then epoch + pp.drepActivity
      else epoch + pp.drepActivity - dormant.toBigInt
    )

  def transition[F[_]: Monad](env: CertEnv, cert: ConwayGovCert)(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayCertState],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    val pp = env.pp
    val id = env.id
    def govCert(f: GovCertFailure): PredicateFailure = PredicateFailure.govCert(f)
    // `checkAndOverwriteCommitteeMemberState` (`GovCert.hs:190`)
    def committeeMember(
        s: ConwayCertState,
        cold: Credential[ColdCommittee],
        state: CommitteeAuthorization
    ): F[ConwayCertState] =
      val back = Convert.back(cold)
      val resigned = CertLens.committee.get(s).get(cold).exists {
        case CommitteeAuthorization.CommitteeMemberResigned(_) => true
        case CommitteeAuthorization.CommitteeHotCredential(_)  => false
      }
      val current = env.committee.exists(_.members.contains(back))
      val future = env.proposals.actions.exists { g =>
        g.action match
          case GovAction.UpdateCommittee(_, _, added, _) => added.contains(back)
          case _                                         => false
      }
      for
        _ <- Refuse.unless[F](
          !resigned,
          id,
          govCert(GovCertFailure.CommitteeHasPreviouslyResigned(back))
        )
        _ <- Refuse.unless[F](
          current || future,
          id,
          govCert(GovCertFailure.CommitteeIsUnknown(back))
        )
      yield CertLens.committee.modify(_.updated(cold, state))(s)
    st.get.flatMap { s =>
      val dreps = CertLens.dreps.get(s)
      val dormant = ConwayCertState.vState.andThen(VState.numDormantEpochs).get(s)
      cert match
        case ConwayGovCert.RegDRep(c, deposit, anchor) =>
          for
            _ <- Refuse.unless[F](
              !dreps.contains(c),
              id,
              govCert(GovCertFailure.DRepAlreadyRegistered(Convert.back(c)))
            )
            _ <- Refuse.unless[F](
              deposit == pp.drepDeposit,
              id,
              govCert(GovCertFailure.DRepIncorrectDeposit(Mismatch(deposit, pp.drepDeposit)))
            )
            // the bootstrap phase does not discount the dormant epochs (`computeDRepExpiryVersioned`)
            e <- Refuse.vocab[F, EpochNo](expiry(pp, env.epoch, dormant, versioned = true))
            held <- Refuse.vocab[F, CompactCoin](CompactCoin.fromBigInt(pp.drepDeposit))
            _ <- st.set(
              CertLens.dreps.modify(
                _.updated(c, DRepState(e, anchor, held, Sorted.emptySet[Credential[Staking]]))
              )(s)
            )
          yield ()
        case ConwayGovCert.UnRegDRep(c, refund) =>
          dreps.get(c) match
            case None =>
              Refuse[F, Unit](id, govCert(GovCertFailure.DRepNotRegistered(Convert.back(c))))
            case Some(d) =>
              for
                _ <- Refuse.unless[F](
                  refund == d.deposit.toBigInt,
                  id,
                  govCert(
                    GovCertFailure.DRepIncorrectRefund(Mismatch(refund, d.deposit.toBigInt))
                  )
                )
                // the reverse index decides who loses their delegation
                cleared = d.delegators.foldLeft(CertLens.dreps.modify(_ - c)(s))((cs, delegator) =>
                  CertLens.accounts.modify(
                    _.updatedWith(delegator)(_.map(ConwayAccountState.drep.replace(None)))
                  )(cs)
                )
                _ <- st.set(cleared)
              yield ()
        case ConwayGovCert.UpdateDRep(c, anchor) =>
          dreps.get(c) match
            case None =>
              Refuse[F, Unit](id, govCert(GovCertFailure.DRepNotRegistered(Convert.back(c))))
            case Some(d) =>
              Refuse.vocab[F, EpochNo](expiry(pp, env.epoch, dormant, versioned = false)).flatMap {
                e =>
                  st.set(
                    CertLens.dreps.modify(_.updated(c, d.copy(expiry = e, anchor = anchor)))(s)
                  )
              }
        case ConwayGovCert.AuthCommitteeHotKey(cold, hot) =>
          committeeMember(s, cold, CommitteeAuthorization.CommitteeHotCredential(hot))
            .flatMap(st.set)
        case ConwayGovCert.ResignCommitteeColdKey(cold, anchor) =>
          committeeMember(s, cold, CommitteeAuthorization.CommitteeMemberResigned(anchor))
            .flatMap(st.set)
    }

/** CERT (`Conway/Rules/Cert.hs:192`, `certTransition`): one certificate goes to the rule of its
  * family; POOL sees the pool state only (`Embed`'s lens).
  */
object Cert extends Sts[CertEnv, ConwayCertState, Certificate, LedgerFailure]:
  given poolEmbed: Embed[PState, ConwayCertState, LedgerFailure, LedgerFailure] =
    Embed(ConwayCertState.pState, identity)

  def transition[F[_]: Monad](env: CertEnv, cert: Certificate)(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayCertState],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    Sts.lift[F, LedgerFailure, ConwayCert](ConwayCert.of(env.id, cert)).flatMap {
      case ConwayCert.OfDeleg(c) => Deleg.transition[F](env, c)
      case ConwayCert.OfPool(c) =>
        Sts.trans[F, CertEnv, PState, ConwayCertState, PoolCert, LedgerFailure, LedgerFailure](
          Pool,
          env,
          c
        )
      case ConwayCert.OfGov(c) => GovCert.transition[F](env, c)
    }

/** CERTS (`Conway/Rules/Certs.hs:203`, `conwayCertsTransition`): the certificates of a transaction
  * in order. Its base case is where, before protocol version 11, the withdrawals are checked and
  * drained and the DReps' expiries updated; from 11 that is LEDGER's
  * (`hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule`), and the base case does nothing.
  */
object Certs extends Sts[CertsEnv, ConwayCertState, Vector[Certificate], LedgerFailure]:
  def transition[F[_]: Monad](env: CertsEnv, certs: Vector[Certificate])(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayCertState],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    val pp = env.cert.pp
    for
      _ <- if pp.protocolMajor >= 11 then Monad[F].unit else baseCase(env)
      _ <- certs.traverse_(Cert.transition[F](env.cert, _))
    yield ()

  /** The base case before version 11: the withdrawals must drain their accounts
    * (`WithdrawalsNotInRewardsCERTS`), then the DReps' expiries are updated and the accounts
    * drained.
    */
  private def baseCase[F[_]: Monad](env: CertsEnv)(using
      ask: Ask[F, Globals],
      st: Stateful[F, ConwayCertState],
      raise: Raise[F, LedgerFailure]
  ): F[Unit] =
    for
      globals <- Sts.globals[F]
      s <- st.get
      view <- Refuse.vocab[F, Withdrawals.View](
        Withdrawals.view(CertLens.accounts.get(s), env.tx.body.withdrawals, globals.networkId)
      )
      _ <- Refuse.unless[F](
        view.isEmpty,
        env.cert.id,
        PredicateFailure.Certs(
          node.CertsFailure.WithdrawalsNotInRewards(
            (view.missing ++ view.incomplete.map((a, m) => a -> m.supplied)).toMap
          )
        )
      )
      expired <- Refuse.vocab[F, ConwayCertState](
        Expiry.update(s, env.tx, env.cert.epoch, env.cert.pp).map(Withdrawals.drain(_, env.tx))
      )
      _ <- st.set(expired)
    yield ()

/** The withdrawals of a transaction against the accounts (`withdrawalsThatDoNotDrainAccounts`,
  * `Shelley/Rules/Utxo.hs`) and their draining (`drainAccounts`).
  */
object Withdrawals:
  /** The withdrawals that name an account that is not registered or is on another network
    * (`missing`), and those that do not take the whole balance (`incomplete`, with the balance).
    */
  final case class View(
      missing: Vector[(RewardAccount, BigInt)],
      incomplete: Vector[(RewardAccount, Mismatch[BigInt])]
  ):
    def isEmpty: Boolean = missing.isEmpty && incomplete.isEmpty

  def view(
      accounts: SortedMap[Credential[Staking], ConwayAccountState],
      withdrawals: Vector[(RewardAccount, BigInt)],
      network: Int
  ): Either[VocabError, View] = viewOf(accounts, withdrawals, network)(_.balance.toBigInt)

  /** [[view]] over the accounts of any era, whose balance is `balance`. */
  def viewOf[A](
      accounts: SortedMap[Credential[Staking], A],
      withdrawals: Vector[(RewardAccount, BigInt)],
      network: Int
  )(balance: A => BigInt): Either[VocabError, View] =
    withdrawals
      .traverse { (account, amount) =>
        Convert.credential[Staking](account.credential).map(c => (account, amount, accounts.get(c)))
      }
      .map { found =>
        View(
          found.collect {
            case (a, n, state) if a.network != network || state.isEmpty => a -> n
          },
          found.collect {
            case (a, n, Some(state)) if a.network == network && balance(state) != n =>
              a -> Mismatch(n, balance(state))
          }
        )
      }

  /** `drainAccounts`: the withdrawn accounts have no balance left. */
  def drain(s: ConwayCertState, tx: Tx): ConwayCertState =
    tx.body.withdrawals.foldLeft(s) { (cs, w) =>
      Convert
        .credential[Staking](w._1.credential)
        .fold(
          _ => cs,
          c =>
            CertLens.accounts.modify(
              _.updatedWith(c)(
                _.map(ConwayAccountState.balance.replace(CompactCoin.fromUnsigned(0L)))
              )
            )(cs)
        )
    }

/** `updateDormantDRepExpiries` and `updateVotingDRepExpiries` (`Certs.hs:257-316`), which the
  * transaction makes of the DReps' expiries.
  */
object Expiry:
  def update(
      s: ConwayCertState,
      tx: Tx,
      epoch: BigInt,
      pp: ProtocolParams
  ): Either[VocabError, ConwayCertState] =
    dormant(s, tx, epoch).flatMap(voting(_, tx, epoch, pp))

  /** With a proposal in the transaction and dormant epochs counted, every DRep's expiry moves up by
    * the dormant epochs (when that is not already in the past), and the count is reset.
    */
  private def dormant(
      s: ConwayCertState,
      tx: Tx,
      epoch: BigInt
  ): Either[VocabError, ConwayCertState] =
    val v = s.vState
    if tx.body.proposals.isEmpty || v.numDormantEpochs.word.isZero then Right(s)
    else
      val n = v.numDormantEpochs.toBigInt
      v.dreps.toList
        .traverse { (c, d) =>
          val actual = d.expiry.toBigInt + n
          if actual < epoch then Right(c -> d)
          else EpochNo.fromBigInt(actual).map(e => c -> d.copy(expiry = e))
        }
        .flatMap { moved =>
          EpochNo.fromBigInt(0).map { zero =>
            ConwayCertState.vState.replace(
              v.copy(dreps = Sorted.map(moved), numDormantEpochs = zero)
            )(s)
          }
        }

  /** The DReps that vote in the transaction are active until `epoch + drepActivity - dormant`. */
  private def voting(
      s: ConwayCertState,
      tx: Tx,
      epoch: BigInt,
      pp: ProtocolParams
  ): Either[VocabError, ConwayCertState] =
    val voters = tx.body.votes.collect { case (node.Voter.DRepVoter(c), _) => c }
    voters.foldM(s) { (cs, voter) =>
      Convert.credential[DRepRole](voter).flatMap { c =>
        CertLens.dreps.get(cs).get(c) match
          case None => Right(cs)
          case Some(d) =>
            EpochNo
              .fromBigInt(epoch + pp.drepActivity - cs.vState.numDormantEpochs.toBigInt)
              .map(e => CertLens.dreps.modify(_.updated(c, d.copy(expiry = e)))(cs))
      }
    }
