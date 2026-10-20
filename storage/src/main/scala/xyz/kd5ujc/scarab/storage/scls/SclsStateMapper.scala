// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  Constitution,
  Credential as LedgerCredential,
  GovActionId,
  GovActionState,
  PoolParams,
  Purposeful
}
import xyz.kd5ujc.scarab.ledger.state.{
  BlocksMade,
  CommitteeState,
  ConwayAccounts,
  ConwayAccountState,
  ConwayGovState,
  ConwayLedgerState,
  ConwayNewEpochState,
  Conway,
  Credential,
  DRepRole,
  DRepState,
  EpochNo,
  FuturePParams,
  KeyHash,
  NewEpochState,
  NonZero,
  Sorted,
  StakePool,
  StakePoolState,
  Staking,
  HotCommittee,
  ScriptHash,
  VRFVerKeyHash,
  VState,
  VocabError,
  Word64
}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellStateCodecs, Params}
import xyz.kd5ujc.scarab.models.runtime.ConstraintViolation
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.ledger.state.ProtVer
import xyz.kd5ujc.scarab.ledger.state as S

/** Projects and overlays the non-UTxO Conway namespace families of the pinned SCLS profile. The
  * supplied state is always a complete same-point base: this mapper preserves all state that these
  * namespaces do not represent.
  */
object SclsStateMapper:
  enum Error extends DomainError:
    case WrongEpoch(expected: BigInt, found: BigInt)
    case BlockCountOutOfRange(found: BigInt)
    case DuplicateKey(key: SclsKey.Key)
    case WrongEntry(key: SclsKey.Key, namespace: SclsRecord.Namespace)
    case UnexpectedKey(key: SclsKey.Key)
    case MissingSingleton(namespace: SclsRecord.Namespace)
    case DuplicateSingleton(namespace: SclsRecord.Namespace)
    case EmptyStakePoolValue(key: SclsKey.Key.StakePools)
    case Parameters(cause: ConstraintViolation)
    case MissingParameters(which: SclsKey.Parameters)
    case ConflictingFutureParameters(which: SclsKey.Parameters)
    case DuplicateProposalRoot(purpose: SclsKey.Purpose)
    case DReps(cause: SclsDRepMapper.Error)
    case ProposalParameters(cause: VocabError)
    case ProposalCredential(cause: VocabError)
    case ProposalEncoding(cause: ConstraintViolation)
    case ProposalProcedureDecode(cause: DecodeError)
    case ProposalProcedureMismatch(id: GovActionId)
    case ProposalOrder(expected: BigInt, found: BigInt)

  private final case class Acc(
      blocks: SortedMap[KeyHash[StakePool], Word64],
      accounts: SortedMap[Credential[Staking], ConwayAccountState],
      committeeEntities: Option[CommitteeState],
      committee: Option[Option[Committee]],
      constitution: Option[Constitution],
      stakePools: SortedMap[KeyHash[StakePool], StakePoolState],
      futurePoolParams: SortedMap[KeyHash[StakePool], PoolParams],
      retiringPools: SortedMap[KeyHash[StakePool], EpochNo],
      vrfKeyHashes: SortedMap[VRFVerKeyHash, NonZero[Word64]],
      parameters: Map[SclsKey.Parameters, Params.ConwayPParams],
      proposalRoots: Map[SclsKey.Purpose, GovActionId],
      dreps: SortedMap[Credential[DRepRole], DRepState],
      proposals: Map[GovActionId, SclsValue.ProposalValue]
  )

  /** Incremental immutable overlay state for streaming import. A failed push leaves this builder
    * unchanged, so callers can safely discard it without partially mutating the admitted base.
    */
  private[scls] final class Builder private[SclsStateMapper] (
      private[SclsStateMapper] val base: NewEpochState[Conway],
      private[SclsStateMapper] val acc: Acc
  ):
    private[scls] def push(
        key: SclsKey.Key,
        value: SclsValue.Entry
    ): Either[Error, Builder] = pushEntry(this, key, value)

    private[scls] def finish: Either[Error, NewEpochState[Conway]] = finishOverlay(this)

  /** A pull-friendly projection. The entry sorter establishes canonical raw-key order. */
  def project(
      base: NewEpochState[Conway]
  ): Either[Error, Iterator[(SclsKey.Key, SclsValue.Entry)]] =
    val blocks = BlocksMade.blocks(base.bcur).iterator.map { case (pool, count) =>
      SclsKey.Key.Blocks(pool, base.epoch) -> SclsValue.Entry.Blocks(Word64.toBigInt(count))
    }
    val accounts =
      ConwayNewEpochState.accounts.get(base).states.iterator.map { case (credential, account) =>
        SclsKey.Key.Accounts(credential) -> SclsValue.Entry.Account(account)
      }
    val pState = ConwayLedgerState.pState.get(ConwayNewEpochState.ls.get(base))
    val poolKeys = Sorted.set(
      pState.stakePools.keysIterator ++ pState.future.keysIterator ++ pState.retiring.keysIterator
    )
    val pools = poolKeys.iterator.map { pool =>
      SclsKey.Key.StakePools(pool) -> SclsValue.Entry.StakePool(
        SclsValue.StakePoolValue(
          pState.stakePools.get(pool),
          pState.retiring.get(pool),
          pState.future.get(pool)
        )
      )
    }
    val vrfHashes = pState.vrfKeyHashes.iterator.map { case (hash, count) =>
      SclsKey.Key.StakePoolVrfHashes(hash) -> SclsValue.Entry.VrfKeyCount(count)
    }
    val committeeState = ConwayLedgerState.vState
      .andThen(VState.committeeState)
      .get(ConwayNewEpochState.ls.get(base))
    val gov = ConwayNewEpochState.gov.get(base)
    val params = for
      prev <- Params.projectConway(gov.prevPParams).leftMap(Error.Parameters(_))
      curr <- Params.projectConway(gov.curPParams).leftMap(Error.Parameters(_))
      future <- gov.futurePParams match
        case FuturePParams.NoPParamsUpdate              => Right(Vector.empty)
        case FuturePParams.PotentialPParamsUpdate(None) => Right(Vector.empty)
        case FuturePParams.PotentialPParamsUpdate(Some(p)) =>
          Params
            .projectConway(p)
            .leftMap(Error.Parameters(_))
            .map(v => Vector(SclsKey.Parameters.PossibleFuture -> v))
        case FuturePParams.DefinitePParamsUpdate(p) =>
          Params
            .projectConway(p)
            .leftMap(Error.Parameters(_))
            .map(v => Vector(SclsKey.Parameters.DefiniteFuture -> v))
    yield Iterator(
      SclsKey.Key.PParams(SclsKey.Parameters.Previous) -> SclsValue.Entry.PParams(prev),
      SclsKey.Key.PParams(SclsKey.Parameters.Current) -> SclsValue.Entry.PParams(curr)
    ) ++ future.iterator.map { case (which, value) =>
      SclsKey.Key.PParams(which) -> SclsValue.Entry.PParams(value)
    }
    val roots = gov.proposals.roots
    val rootEntries = Vector(
      SclsKey.Purpose.Parameters -> Purposeful.ParamUpdate,
      SclsKey.Purpose.HardFork -> Purposeful.HardFork,
      SclsKey.Purpose.Committee -> Purposeful.Committee,
      SclsKey.Purpose.Constitution -> Purposeful.Constitution
    ).iterator.flatMap { case (purpose, lineage) =>
      roots
        .get(lineage)
        .iterator
        .map(id => SclsKey.Key.ProposalRoots(purpose) -> SclsValue.Entry.ProposalRoot(id))
    }
    params.flatMap { parameterEntries =>
      projectProposals(gov.proposals.actions).map { proposalEntries =>
        blocks ++ accounts ++ pools ++ vrfHashes ++ parameterEntries ++ rootEntries ++
          proposalEntries.iterator ++ SclsDRepMapper.project(base) ++ Iterator(
            SclsKey.Key.CommitteeEntities -> SclsValue.Entry.CommitteeEntity(committeeState),
            SclsKey.Key.Committee -> SclsValue.Entry.GovernanceCommittee(gov.committee),
            SclsKey.Key.Constitution -> SclsValue.Entry.GovernanceConstitution(gov.constitution)
          )
      }
    }

  private val conwayProtVer = ProtVer(Word64.fromUnsigned(9L), Word64.zero)

  private def projectProposals(
      actions: Vector[GovActionState]
  ): Either[Error, Vector[(SclsKey.Key, SclsValue.Entry)]] =
    actions.zipWithIndex.toVector.traverse { case (action, order) =>
      for
        canonicalOrder <- Word64
          .fromBigInt(VocabError.Kind.BlockNo, BigInt(order))
          .leftMap(Error.ProposalParameters(_))
        raw <- HaskellStateCodecs
          .proposal(using conwayProtVer)
          .write(action.proposal)
          .leftMap(Error.ProposalEncoding(_))
        drepVotes <- action.drepVotes.toVector.traverse { case (credential, vote) =>
          canonicalCredential[DRepRole](credential).map(_ -> vote)
        }
        committeeVotes <- action.committeeVotes.toVector.traverse { case (credential, vote) =>
          canonicalCredential[HotCommittee](credential).map(_ -> vote)
        }
        poolVotes <- action.poolVotes.toVector.traverse { case (hash, vote) =>
          S.KeyHash
            .fromBytes[StakePool](hash)
            .leftMap(Error.ProposalCredential(_))
            .map(_ -> vote)
        }
        proposedIn <- EpochNo.fromBigInt(action.proposedIn).leftMap(Error.ProposalParameters(_))
        expiresAfter <- EpochNo.fromBigInt(action.expiresAfter).leftMap(Error.ProposalParameters(_))
      yield SclsKey.Key.Proposals(action.id) -> SclsValue.Entry.Proposal(
        SclsValue.ProposalValue(
          canonicalOrder,
          drepVotes.toMap,
          proposedIn,
          expiresAfter,
          committeeVotes.toMap,
          poolVotes.toMap,
          action.proposal,
          raw
        )
      )
    }

  private def canonicalCredential[R <: S.KeyRole](
      credential: LedgerCredential
  ): Either[Error, S.Credential[R]] = credential match
    case LedgerCredential.Key(hash) =>
      S.KeyHash
        .fromBytes[R](hash)
        .leftMap(Error.ProposalCredential(_))
        .map(S.Credential.KeyHashObj(_))
    case LedgerCredential.Script(hash) =>
      S.ScriptHash
        .fromBytes(hash)
        .leftMap(Error.ProposalCredential(_))
        .map(S.Credential.ScriptHashObj(_))

  private def ledgerCredential[R <: S.KeyRole](
      credential: S.Credential[R]
  ): LedgerCredential = credential match
    case S.Credential.KeyHashObj(hash)    => LedgerCredential.Key(hash.bytes)
    case S.Credential.ScriptHashObj(hash) => LedgerCredential.Script(hash.bytes)

  private def rebuildProposals(
      proposals: Map[GovActionId, SclsValue.ProposalValue]
  ): Either[Error, Vector[GovActionState]] =
    val ordered = proposals.toVector.sortBy { case (_, value) => value.order.toBigInt }
    val contiguous = ordered.zipWithIndex.foldLeft[Either[Error, Unit]](Right(())) {
      case (result, ((_, value), expected)) =>
        result.flatMap(_ =>
          Either.cond(
            value.order.toBigInt == BigInt(expected),
            (),
            Error.ProposalOrder(BigInt(expected), value.order.toBigInt)
          )
        )
    }
    contiguous.flatMap { _ =>
      ordered.traverse { case (id, value) =>
        for
          decodedProcedure <- HaskellStateCodecs
            .proposal(using conwayProtVer)
            .decodeBytes(value.proposalProcedureRaw)
            .leftMap(Error.ProposalProcedureDecode(_))
          _ <- Either.cond(
            decodedProcedure == value.proposal,
            (),
            Error.ProposalProcedureMismatch(id)
          )
          committeeVotes = value.committeeVotes.iterator.map { case (credential, vote) =>
            ledgerCredential(credential) -> vote
          }.toMap
          drepVotes = value.drepVotes.iterator.map { case (credential, vote) =>
            ledgerCredential(credential) -> vote
          }.toMap
          poolVotes = value.stakePoolVotes.iterator.map { case (hash, vote) =>
            hash.bytes -> vote
          }.toMap
        yield GovActionState(
          id,
          committeeVotes,
          drepVotes,
          poolVotes,
          value.proposal,
          value.proposedIn.toBigInt,
          value.expiresAfter.toBigInt
        )
      }
    }

  /** Overlay a complete stream for the implemented namespaces onto its explicit complete base.
    * Unrepresented fields and the UTxO handle remain those of `base`.
    */
  private[scls] def overlay(
      base: NewEpochState[Conway],
      entries: Iterator[(SclsKey.Key, SclsValue.Entry)]
  ): Either[Error, NewEpochState[Conway]] =
    entries
      .foldLeft[Either[Error, Builder]](Right(begin(base))) { case (builderE, (key, value)) =>
        builderE.flatMap(_.push(key, value))
      }
      .flatMap(_.finish)

  private[scls] def begin(base: NewEpochState[Conway]): Builder =
    new Builder(
      base,
      Acc(
        Sorted.empty[KeyHash[StakePool], Word64],
        Sorted.empty[Credential[Staking], ConwayAccountState],
        None,
        None,
        None,
        Sorted.empty[KeyHash[StakePool], StakePoolState],
        Sorted.empty[KeyHash[StakePool], PoolParams],
        Sorted.empty[KeyHash[StakePool], EpochNo],
        Sorted.empty[VRFVerKeyHash, NonZero[Word64]],
        Map.empty,
        Map.empty,
        Sorted.empty[Credential[DRepRole], DRepState],
        Map.empty
      )
    )

  private def pushEntry(
      builder: Builder,
      key: SclsKey.Key,
      value: SclsValue.Entry
  ): Either[Error, Builder] =
    val base = builder.base
    val acc = builder.acc
    val updated =
      Either
        .cond(key.namespace == value.namespace, (), Error.WrongEntry(key, value.namespace))
        .flatMap { _ =>
          (key, value) match
            case (SclsKey.Key.Blocks(pool, epoch), SclsValue.Entry.Blocks(count)) =>
              if epoch.toBigInt != base.epoch.toBigInt then
                Left(Error.WrongEpoch(base.epoch.toBigInt, epoch.toBigInt))
              else if acc.blocks.contains(pool) then Left(Error.DuplicateKey(key))
              else
                Word64
                  .fromBigInt(VocabError.Kind.BlockCount, count)
                  .leftMap(_ => Error.BlockCountOutOfRange(count))
                  .map(word => acc.copy(blocks = acc.blocks.updated(pool, word)))
            case (SclsKey.Key.Accounts(credential), SclsValue.Entry.Account(account)) =>
              Either.cond(
                !acc.accounts.contains(credential),
                acc.copy(accounts = acc.accounts.updated(credential, account)),
                Error.DuplicateKey(key)
              )
            case (poolKey @ SclsKey.Key.StakePools(pool), SclsValue.Entry.StakePool(value)) =>
              val alreadySeen = acc.stakePools.contains(pool) ||
                acc.futurePoolParams.contains(pool) || acc.retiringPools.contains(pool)
              if alreadySeen then Left(Error.DuplicateKey(poolKey))
              else if value.state.isEmpty && value.retiringEpoch.isEmpty && value.futureParams.isEmpty
              then Left(Error.EmptyStakePoolValue(poolKey))
              else
                Right(
                  acc.copy(
                    stakePools = value.state.fold(acc.stakePools)(acc.stakePools.updated(pool, _)),
                    futurePoolParams = value.futureParams.fold(acc.futurePoolParams)(
                      acc.futurePoolParams.updated(pool, _)
                    ),
                    retiringPools = value.retiringEpoch.fold(acc.retiringPools)(
                      acc.retiringPools.updated(pool, _)
                    )
                  )
                )
            case (
                  poolKey @ SclsKey.Key.StakePoolVrfHashes(hash),
                  SclsValue.Entry.VrfKeyCount(count)
                ) =>
              Either.cond(
                !acc.vrfKeyHashes.contains(hash),
                acc.copy(vrfKeyHashes = acc.vrfKeyHashes.updated(hash, count)),
                Error.DuplicateKey(poolKey)
              )
            case (SclsKey.Key.PParams(which), SclsValue.Entry.PParams(params)) =>
              Either.cond(
                !acc.parameters.contains(which),
                acc.copy(parameters = acc.parameters.updated(which, params)),
                Error.DuplicateKey(key)
              )
            case (SclsKey.Key.ProposalRoots(purpose), SclsValue.Entry.ProposalRoot(id)) =>
              Either.cond(
                !acc.proposalRoots.contains(purpose),
                acc.copy(proposalRoots = acc.proposalRoots.updated(purpose, id)),
                Error.DuplicateProposalRoot(purpose)
              )
            case (SclsKey.Key.DReps(credential), SclsValue.Entry.DRep(state)) =>
              Either.cond(
                !acc.dreps.contains(credential),
                acc.copy(dreps = acc.dreps.updated(credential, state)),
                Error.DuplicateKey(key)
              )
            case (SclsKey.Key.Proposals(id), SclsValue.Entry.Proposal(proposal)) =>
              Either.cond(
                !acc.proposals.contains(id),
                acc.copy(proposals = acc.proposals.updated(id, proposal)),
                Error.DuplicateKey(key)
              )
            case (SclsKey.Key.CommitteeEntities, SclsValue.Entry.CommitteeEntity(state)) =>
              if acc.committeeEntities.nonEmpty then Left(Error.DuplicateSingleton(key.namespace))
              else Right(acc.copy(committeeEntities = Some(state)))
            case (SclsKey.Key.Committee, SclsValue.Entry.GovernanceCommittee(committee)) =>
              if acc.committee.nonEmpty then Left(Error.DuplicateSingleton(key.namespace))
              else Right(acc.copy(committee = Some(committee)))
            case (SclsKey.Key.Constitution, SclsValue.Entry.GovernanceConstitution(constitution)) =>
              if acc.constitution.nonEmpty then Left(Error.DuplicateSingleton(key.namespace))
              else Right(acc.copy(constitution = Some(constitution)))
            case _ => Left(Error.UnexpectedKey(key))
        }
    updated.map(next => new Builder(base, next))

  private def finishOverlay(builder: Builder): Either[Error, NewEpochState[Conway]] =
    val base = builder.base
    val acc = builder.acc
    {
      for
        entities <- acc.committeeEntities.toRight(
          Error.MissingSingleton(SclsRecord.Namespace.CommitteeEntities)
        )
        committee <- acc.committee.toRight(Error.MissingSingleton(SclsRecord.Namespace.Committee))
        constitution <- acc.constitution.toRight(
          Error.MissingSingleton(SclsRecord.Namespace.Constitution)
        )
        prev <- acc.parameters
          .get(SclsKey.Parameters.Previous)
          .toRight(
            Error.MissingParameters(SclsKey.Parameters.Previous)
          )
        curr <- acc.parameters
          .get(SclsKey.Parameters.Current)
          .toRight(
            Error.MissingParameters(SclsKey.Parameters.Current)
          )
        future <- checkedFuture(base, acc.parameters)
        withDReps <- SclsDRepMapper.overlay(base, acc.dreps.iterator).leftMap(Error.DReps(_))
        roots = rootsFrom(acc.proposalRoots)
        actions <- rebuildProposals(acc.proposals)
      yield
        val pStateLens = ConwayNewEpochState.ls.andThen(ConwayLedgerState.pState)
        val updatedPState = pStateLens
          .get(base)
          .copy(
            stakePools = acc.stakePools,
            future = acc.futurePoolParams,
            retiring = acc.retiringPools,
            vrfKeyHashes = acc.vrfKeyHashes
          )
        val withBlocks = NewEpochState.bcur[Conway].replace(BlocksMade(acc.blocks))(base)
        val withAccounts = ConwayNewEpochState.accounts
          .replace(ConwayAccounts(acc.accounts))(withBlocks)
        val withPools = pStateLens.replace(updatedPState)(withAccounts)
        val withEntities = ConwayNewEpochState.ls
          .andThen(ConwayLedgerState.vState)
          .andThen(VState.committeeState)
          .replace(entities)(withPools)
        val withCommittee = ConwayNewEpochState.gov
          .andThen(ConwayGovState.committee)
          .replace(committee)(withEntities)
        val withConstitution = ConwayNewEpochState.gov
          .andThen(ConwayGovState.constitution)
          .replace(constitution)(withCommittee)
        val govLens = ConwayNewEpochState.gov
        val withPrev = govLens
          .andThen(ConwayGovState.prevPParams)
          .replace(Params.overlayConway(prev, govLens.get(withConstitution).prevPParams))(
            withConstitution
          )
        val withCurr = govLens
          .andThen(ConwayGovState.curPParams)
          .replace(Params.overlayConway(curr, govLens.get(withPrev).curPParams))(withPrev)
        val withFuture = govLens.andThen(ConwayGovState.futurePParams).replace(future)(withCurr)
        val withRoots = govLens
          .andThen(ConwayGovState.proposals)
          .replace(xyz.kd5ujc.scarab.ledger.Proposals(actions, roots))(withFuture)
        ConwayNewEpochState.dreps.replace(ConwayNewEpochState.dreps.get(withDReps))(withRoots)
    }

  private def rootsFrom(
      roots: Map[SclsKey.Purpose, GovActionId]
  ): xyz.kd5ujc.scarab.ledger.PrevIds =
    Vector(
      SclsKey.Purpose.Parameters -> Purposeful.ParamUpdate,
      SclsKey.Purpose.HardFork -> Purposeful.HardFork,
      SclsKey.Purpose.Committee -> Purposeful.Committee,
      SclsKey.Purpose.Constitution -> Purposeful.Constitution
    ).foldLeft(xyz.kd5ujc.scarab.ledger.PrevIds()) { case (ids, (purpose, lineage)) =>
      ids.set(lineage, roots.get(purpose))
    }

  private def checkedFuture(
      base: NewEpochState[Conway],
      params: Map[SclsKey.Parameters, Params.ConwayPParams]
  ): Either[Error, FuturePParams] =
    val original = ConwayNewEpochState.gov.get(base).futurePParams
    original match
      case FuturePParams.NoPParamsUpdate =>
        Either.cond(
          !params.contains(SclsKey.Parameters.PossibleFuture) &&
            !params.contains(SclsKey.Parameters.DefiniteFuture),
          FuturePParams.NoPParamsUpdate,
          Error.ConflictingFutureParameters(
            if params.contains(SclsKey.Parameters.PossibleFuture)
            then SclsKey.Parameters.PossibleFuture
            else SclsKey.Parameters.DefiniteFuture
          )
        )
      case FuturePParams.PotentialPParamsUpdate(None) =>
        Either.cond(
          !params.contains(SclsKey.Parameters.PossibleFuture) &&
            !params.contains(SclsKey.Parameters.DefiniteFuture),
          FuturePParams.PotentialPParamsUpdate(None),
          Error.ConflictingFutureParameters(
            if params.contains(SclsKey.Parameters.PossibleFuture)
            then SclsKey.Parameters.PossibleFuture
            else SclsKey.Parameters.DefiniteFuture
          )
        )
      case FuturePParams.PotentialPParamsUpdate(Some(p)) =>
        params
          .get(SclsKey.Parameters.PossibleFuture)
          .toRight(
            Error.MissingParameters(SclsKey.Parameters.PossibleFuture)
          )
          .flatMap { next =>
            Either
              .cond(
                !params.contains(SclsKey.Parameters.DefiniteFuture),
                (),
                Error.ConflictingFutureParameters(SclsKey.Parameters.DefiniteFuture)
              )
              .map(_ => FuturePParams.PotentialPParamsUpdate(Some(Params.overlayConway(next, p))))
          }
      case FuturePParams.DefinitePParamsUpdate(p) =>
        params
          .get(SclsKey.Parameters.DefiniteFuture)
          .toRight(
            Error.MissingParameters(SclsKey.Parameters.DefiniteFuture)
          )
          .flatMap { next =>
            Either
              .cond(
                !params.contains(SclsKey.Parameters.PossibleFuture),
                (),
                Error.ConflictingFutureParameters(SclsKey.Parameters.PossibleFuture)
              )
              .map(_ => FuturePParams.DefinitePParamsUpdate(Params.overlayConway(next, p)))
          }
