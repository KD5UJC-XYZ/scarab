// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Applicative, Monad}
import cats.mtl.{Ask, Raise, Stateful}
import cats.syntax.all.*
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  GovActionId,
  GovActionState,
  LedgerFailure,
  Mismatch,
  PoolFailure,
  PoolParams,
  PredicateFailure,
  Purpose,
  ProtocolParams,
  RewardAccount,
  Tx,
  TxDefect
}
import xyz.kd5ujc.scarab.ledger.state.*

// What stays of the flat certificate rules: POOL (`Shelley/Rules/Pool.hs`), which the eras before
// Conway run under their DELEGS (`ShelleyCerts`), with its environment and certificate; the pure
// changes the certificates make to the certificate state (`CertOps`), which Shelley's DELEG and the
// genesis injection (`GenesisStart`) make; what a delegation delegates to; and the withdrawals' and
// the DReps' expiries' helpers. Conway's CERTS, CERT, DELEG and GOVCERT are the era rules'
// (`era/rules/Certs.scala`, `conwayCertsTransition`,
// `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Certs.hs:203-246`, cardano-ledger f649f975).

/** The environment of CERTS' sub-rules: `CertEnv` (`Cert.hs`) and `ConwayGovCertEnv` (`GovCert.hs`)
  * in one: the parameters in force, the current epoch, the current committee and exact committee
  * proposal map, and the transaction they are for (the failures name it).
  */
final case class CertEnv(
    id: Bytes,
    pp: ProtocolParams,
    epoch: BigInt,
    committee: Option[Committee],
    committeeProposals: Map[GovActionId, GovActionState]
)

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

/** `PoolCert` (`Shelley/TxCert.hs`). */
enum PoolCert:
  case RegPool(params: PoolParams)
  case RetirePool(pool: KeyHash[StakePool], epoch: BigInt)

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
      // With no current account, add only the new reverse delegation.
      current.fold(
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

/** POOL (`Shelley/Rules/Pool.hs:209`, `poolTransition`, the rule of every era): registration and
  * retirement of stake pools, with the VRF key hashes kept from protocol version 11
  * (`hardforkConwayDisallowDuplicatedVRFKeys`, `Pool.hs:265-305`). The map of key hashes is
  * populated by the hard fork into 11 (`HardFork.hs:75-78`, J3.3): until then, before version 11,
  * it is not kept, as in the reference.
  */
object Pool extends JudgedSts[CertEnv, PState, PoolCert, PredicateFailure, LedgerFailure]:
  def transition[F[_]: Monad](env: CertEnv, cert: PoolCert)(using
      ask: Ask[F, Globals],
      st: Stateful[F, PState],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] = transitionAt(env.id, env.pp, env.epoch, cert)

  private def transitionAt[F[_]: Monad](
      id: Bytes,
      pp: ProtocolParams,
      currentEpoch: BigInt,
      cert: PoolCert
  )(using
      ask: Ask[F, Globals],
      st: Stateful[F, PState],
      raise: Raise[F, LedgerFailure],
      judge: Judge[F, PredicateFailure]
  ): F[Unit] =
    val unique = pp.protocolMajor >= 11
    cert match
      case PoolCert.RegPool(params) =>
        for
          globals <- Sts.globals[F]
          _ <- Sts.predicate[F, PredicateFailure](
            !pp.validatesPoolRewardAccountNetwork || params.rewardAccount.network == globals.networkId.wire,
            PredicateFailure.pool(
              PoolFailure.WrongNetworkPool(
                Mismatch(params.rewardAccount.network, globals.networkId.wire),
                params.operator
              )
            )
          )
          // `Shelley/Rules/Pool.hs:245-250`: network, metadata hash, then minimum cost,
          // before either registration or re-registration can change the pool state.
          metadata <- Sts.lift[F, LedgerFailure, Option[PoolMetadata]](
            params.metadata
              .traverse(PoolMetadata.held)
              .leftMap(cause => LedgerFailure.Undecodable(id, TxDefect.PoolMetadata(cause)))
          )
          _ <- Sts.predicate[F, PredicateFailure](
            !pp.restrictsPoolMetadataHash || metadata.forall(_.hash.size <= 32),
            PredicateFailure.pool(
              PoolFailure.PoolMedataHashTooBig(params.operator, metadata.fold(0)(_.hash.size))
            )
          )
          _ <- Sts.predicate[F, PredicateFailure](
            params.cost >= pp.minPoolCost,
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
                _ <- Sts.predicate[F, PredicateFailure](
                  !unique || !s.vrfKeyHashes.contains(vrf),
                  PredicateFailure.pool(
                    PoolFailure.VRFKeyHashAlreadyRegistered(params.operator, params.vrfKeyHash)
                  )
                )
                // `ppPoolDepositCompactL` (`Shelley/Rules/Pool.hs:275`): a `CompactForm Coin` of the
                // parameters
                deposit <- Refuse.state[F, CompactCoin](id, CompactCoin.fromBigInt(pp.poolDeposit))
                layout <- Refuse.layout[F](globals.ledger.referenceBuild)
                // `mkStakePoolState cEpoch SNothing` (9df9f98c `Shelley/Rules/Pool.hs:262-266`),
                // in the layout of the build: in the Leios layout the new pool holds no voting key
                pool <- Refuse.vocab[F, StakePoolState](
                  Convert.poolState(
                    params,
                    PoolBlsKey.certified(layout),
                    deposit,
                    Sorted.emptySet[Credential[Staking]]
                  )
                )
              yield s.copy(
                stakePools = s.stakePools.updated(operator, pool),
                vrfKeyHashes =
                  if unique then s.vrfKeyHashes.updated(vrf, NonZero.oneWord) else s.vrfKeyHashes
              )
            case Some(current) =>
              for
                _ <- Sts.predicate[F, PredicateFailure](
                  !unique || current.vrf == vrf || !s.vrfKeyHashes.contains(vrf),
                  PredicateFailure.pool(
                    PoolFailure.VRFKeyHashAlreadyRegistered(params.operator, params.vrfKeyHash)
                  )
                )
                // the parameters in the layout of the pool's state: in the Leios layout a
                // certificate before Dijkstra registers no voting key
                future <- Refuse.vocab[F, StakePoolParams](
                  Convert.stakePoolParams(
                    params,
                    PoolParamsBlsKey.certified(current.blsKey.stateLayout),
                    metadata
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
                      case Some(earlier) if earlier.vrf != vrf =>
                        s.vrfKeyHashes.removed(earlier.vrf).updated(vrf, NonZero.oneWord)
                      case Some(_) => s.vrfKeyHashes
                s.copy(
                  future = s.future.updated(operator, future),
                  retiring = s.retiring.removed(operator),
                  vrfKeyHashes = vrfs
                )
          _ <- st.set(next)
        yield ()
      case PoolCert.RetirePool(pool, epoch) =>
        for
          s <- st.get
          _ <- Sts.predicate[F, PredicateFailure](
            s.stakePools.contains(pool),
            PredicateFailure.pool(PoolFailure.StakePoolNotRegisteredOnKey(pool.bytes))
          )
          limit = currentEpoch + pp.eMax
          _ <- Sts.predicate[F, PredicateFailure](
            currentEpoch < epoch && epoch <= limit,
            PredicateFailure.pool(
              PoolFailure.StakePoolRetirementWrongEpoch(
                Mismatch(epoch, currentEpoch),
                Mismatch(epoch, limit)
              )
            )
          )
          e <- Refuse.vocab[F, EpochNo](EpochNo.fromBigInt(epoch))
          _ <- st.set(s.copy(retiring = s.retiring.updated(pool, e)))
        yield ()

  /** Conway's upstream POOL environment is `[epoch, PParams]` (`Shelley/Rules/Pool.hs:74-81`). */
  private[scarab] final case class RuleEnv(caseId: Bytes, pp: ProtocolParams, epoch: BigInt)

  private[scarab] object Adapter
      extends JudgedSts[RuleEnv, PState, PoolCert, PredicateFailure, LedgerFailure]:
    def transition[F[_]: Monad](env: RuleEnv, cert: PoolCert)(using
        ask: Ask[F, Globals],
        st: Stateful[F, PState],
        raise: Raise[F, LedgerFailure],
        judge: Judge[F, PredicateFailure]
    ): F[Unit] = transitionAt(env.caseId, env.pp, env.epoch, cert)

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
      withdrawals: Map[RewardAccount, Coin],
      network: Int
  ): Either[VocabError, View] = viewOf(accounts, withdrawals, network)(_.balance.toBigInt)

  /** [[view]] over the accounts of any era, whose balance is `balance`. */
  def viewOf[A](
      accounts: SortedMap[Credential[Staking], A],
      withdrawals: Map[RewardAccount, Coin],
      network: Int
  )(balance: A => BigInt): Either[VocabError, View] =
    Purpose
      .sortedWithdrawals(withdrawals)
      .traverse { (account, amount) =>
        Convert
          .credential[Staking](account.credential)
          .map(c => (account, amount.value, accounts.get(c)))
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

  /** `testIncompleteAndMissingWithdrawals` (`Shelley/Rules/Ledger.hs:343-361`): records the
    * withdrawals of unregistered accounts (`ShelleyWithdrawalsMissingAccounts`), then those that do
    * not drain their account (`ShelleyIncompleteWithdrawals`).
    */
  def record[F[_]: Applicative](view: View)(using Judge[F, PredicateFailure]): F[Unit] =
    Sts.failOnNonEmpty[F, Vector[(RewardAccount, BigInt)], PredicateFailure](view.missing)(
      _.isEmpty
    )(m => PredicateFailure.WithdrawalsMissingAccounts(m.toMap)) *>
      Sts.failOnNonEmpty[F, Vector[(RewardAccount, Mismatch[BigInt])], PredicateFailure](
        view.incomplete
      )(_.isEmpty)(i => PredicateFailure.IncompleteWithdrawals(i.toMap))

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
