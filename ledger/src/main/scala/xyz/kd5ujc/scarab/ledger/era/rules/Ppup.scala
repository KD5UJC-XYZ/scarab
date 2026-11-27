// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.Monad
import cats.data.Chain
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{
  EpochGeometry,
  GenDelegPair,
  LedgerDefect,
  LedgerFailure,
  VotingPeriod
}
import xyz.kd5ujc.scarab.ledger.era.{
  EraPParams,
  EraPParamsToBabbage,
  FuturePParams,
  Identity,
  KeptProposedPPUpdates,
  KeptUpdate,
  Mismatch,
  PParams,
  PParamsUpdate,
  ProposedPPUpdates,
  ProtVer,
  Relation,
  ShelleyGovState
}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Sts}
import xyz.kd5ujc.scarab.ledger.state.{EpochNo, EraTag, Genesis, KeyHash, SlotNo}

// PPUP as the reference writes it (cardano-ledger f649f975
// `eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Ppup.hs`), the rule of the update proposals
// of the eras up to Babbage (`AtMostEra "Babbage"`, [[EraPParamsToBabbage]]), on the era types. Its
// state is the era's governance state ([[ShelleyGovState]]), whose proposals keep the bytes the
// node stores each by ([[KeptProposedPPUpdates]]), so its signal is the update proposal read with
// them ([[KeptUpdate]]).

/** `data PpupEnv era = PPUPEnv SlotNo (PParams era) GenDelegs` (`Shelley/Rules/Ppup.hs:65`,
  * cardano-ledger f649f975): the slot, the parameters in force and the genesis delegations. The
  * slot is a `SlotNo`, as the reference's is; the epoch arithmetic reads it as a number.
  */
final case class PpupEnv[E <: EraTag](
    slot: SlotNo,
    pp: PParams[E],
    genDelegs: Map[KeyHash[Genesis], GenDelegPair]
)

/** `data ShelleyPpupPredFailure era` (`Shelley/Rules/Ppup.hs:85-107`, cardano-ledger f649f975). */
enum ShelleyPpupPredFailure[E <: EraTag]:
  /** A proposer that is not a genesis key: the proposers supplied, the genesis keys expected. */
  case NonGenesisUpdatePPUP(mismatch: Mismatch[Relation.RelSubset, Set[KeyHash[Genesis]]])

  /** An update for the wrong epoch: the current epoch, the update's, and the period it was to be
    * for.
    */
  case PPUpdateWrongEpoch(current: EpochNo, target: EpochNo, period: VotingPeriod)

  /** A proposed protocol version that cannot follow the one in force. */
  case PVCannotFollowPPUP(protVer: ProtVer)

/** `newtype PpupEvent era = PpupNewEpoch EpochNo` (`Shelley/Rules/Ppup.hs:115`, cardano-ledger
  * f649f975): the epoch after the slot's. Named for the era here, as [[PpupEvent]] is the family of
  * PPUP's events (`EraRuleEvent "PPUP" era`).
  */
enum ShelleyPpupEvent[E <: EraTag]:
  case PpupNewEpoch(epoch: EpochNo)

/** The functions of Shelley's PPUP (`Shelley/Rules/Ppup.hs`, cardano-ledger f649f975) and of the
  * update proposal it checks (`Shelley/PParams.hs`), generic in the era and asking for the
  * reference's classes.
  */
object ShelleyPpup:
  /** `hasLegalProtVerUpdate` (`Shelley/PParams.hs:311-316`, cardano-ledger f649f975): an update
    * that sets no protocol version, or one that can follow the version in force (`pvCanFollow`,
    * [[ConwayGov.pvCanFollow]]).
    */
  def hasLegalProtVerUpdate[E <: EraTag](pp: PParams[E], ppu: PParamsUpdate[E])(using
      p: EraPParamsToBabbage[E]
  ): Boolean =
    EraPParamsToBabbage
      .ppuProtocolVersionL[E]
      .get(ppu)
      .forall(ConwayGov.pvCanFollow(p.ppProtocolVersionL.get(pp), _))

  /** `votedFuturePParams` (`Shelley/Rules/Ppup.hs:205-239`, cardano-ledger f649f975): the one
    * update at least `quorumN` delegates proposed with equal values (`Map (PParamsUpdate era)
    * Word64`), applied to `pp` (`applyPPUpdates`), if the new parameters' transaction and header
    * sizes are below the block body's; none where no update, or more than one, has the quorum.
    */
  def votedFuturePParams[E <: EraTag](
      proposals: ProposedPPUpdates[E],
      pp: PParams[E],
      quorumN: BigInt
  )(using p: EraPParams[E]): Option[PParams[E]] =
    val votes = proposals.updates.values.toVector.groupMapReduce(identity)(_ => BigInt(1))(_ + _)
    votes.filter((_, n) => n >= quorumN).keys.toList match
      case List(ppu) =>
        val ppNew = p.applyPPUpdates(pp, ppu)
        Option.when(
          EraPParams.ppMaxTxSizeL[E].get(ppNew).value +
            PParams.lensHKD[E].andThen(p.hkdMaxBHSizeL[Identity]).get(ppNew).value <
            PParams.lensHKD[E].andThen(p.hkdMaxBBSizeL[Identity]).get(ppNew).value
        )(ppNew)
      case _ => None

  /** `ppupTransitionNonEmpty` (`Shelley/Rules/Ppup.hs:146-200`, cardano-ledger f649f975): nothing
    * without an update; otherwise, each recording its failure and going on:
    *   - every proposer a genesis key (`NonGenesisUpdatePPUP`);
    *   - the protocol version of the first update, in the order of the proposers' key hashes, that
    *     cannot follow the one in force (`PVCannotFollowPPUP`);
    *   - the epoch, the point of no return (`getTheSlotOfNoReturn`, `Slot.hs:106-114`: two
    *     stability windows before the first slot of the next epoch,
    *     [[EpochGeometry.slotOfNoReturn]]) and the next epoch of the slot, from `Globals`, the next
    *     epoch told (`PpupNewEpoch`);
    *   - before the point of no return, an update for this epoch, its proposals put over the
    *     current ones and the prediction of the next parameters voted from them
    *     (`votedFuturePParams`, with `Globals` ' quorum); from it on, an update for the next epoch,
    *     its proposals put over the future ones (`PPUpdateWrongEpoch` otherwise).
    *
    * The state changes whatever was recorded, as the reference's does. A new proposal of a delegate
    * replaces its earlier one (`Map.union pup pupS`). An epoch outside `EpochNo` is raised
    * ([[LedgerFailure.Vocab]]).
    */
  def ppupTransitionNonEmpty[F[_]: Monad, E <: EraTag](
      env: PpupEnv[E],
      update: Option[KeptUpdate[E]]
  )(using
      p: EraPParamsToBabbage[E],
      ask: Ask[F, Globals],
      state: Stateful[F, ShelleyGovState[E]],
      judge: Judge[F, ShelleyPpupPredFailure[E]],
      events: Tell[F, Chain[ShelleyPpupEvent[E]]],
      defect: Raise[F, LedgerDefect]
  ): F[Unit] =
    update.traverse_ { kept =>
      val pup: KeptProposedPPUpdates[E] = kept.proposed
      val targetEpochNo = kept.update.epoch
      val slot = env.slot.toBigInt
      val pp = env.pp
      def epochNo(n: BigInt): F[EpochNo] =
        Sts.lift[F, LedgerDefect, EpochNo](EpochNo.fromBigInt(n).leftMap(LedgerFailure.Vocab(_)))
      val firstIllegalProtVerUpdate: Option[ProtVer] =
        pup.updates.valuesIterator
          .map(_.update)
          .find(!hasLegalProtVerUpdate[E](pp, _))
          .flatMap(EraPParamsToBabbage.ppuProtocolVersionL[E].get)
      for
        _ <- Sts.predicate[F, ShelleyPpupPredFailure[E]](
          pup.updates.keysIterator.forall(env.genDelegs.contains),
          ShelleyPpupPredFailure.NonGenesisUpdatePPUP(
            Mismatch[Relation.RelSubset, Set[KeyHash[Genesis]]](
              pup.updates.keySet,
              env.genDelegs.keySet
            )
          )
        )
        _ <- Sts.failOnJust[F, ProtVer, ShelleyPpupPredFailure[E]](firstIllegalProtVerUpdate)(
          ShelleyPpupPredFailure.PVCannotFollowPPUP(_)
        )
        globals <- ask.ask[Globals]
        epoch = globals.epochOf(slot)
        curEpochNo <- epochNo(epoch)
        nextEpochNo <- epochNo(epoch + 1)
        tooLate = EpochGeometry.slotOfNoReturn(
          globals.firstSlot(epoch + 1),
          globals.stabilityWindow
        )
        _ <- events.tell(Chain.one(ShelleyPpupEvent.PpupNewEpoch(nextEpochNo)))
        _ <-
          if slot < tooLate then
            for
              _ <- Sts.predicate[F, ShelleyPpupPredFailure[E]](
                curEpochNo.word == targetEpochNo.word,
                ShelleyPpupPredFailure
                  .PPUpdateWrongEpoch(curEpochNo, targetEpochNo, VotingPeriod.VoteForThisEpoch)
              )
              _ <- state.modify { pps =>
                val curProposals =
                  KeptProposedPPUpdates[E](pps.sgsCurProposals.updates ++ pup.updates)
                pps.copy(
                  sgsCurProposals = curProposals,
                  sgsFuturePParams = FuturePParams.PotentialPParamsUpdate(
                    votedFuturePParams[E](curProposals.proposed, pp, BigInt(globals.quorum))
                  )
                )
              }
            yield ()
          else
            for
              _ <- Sts.predicate[F, ShelleyPpupPredFailure[E]](
                nextEpochNo.word == targetEpochNo.word,
                ShelleyPpupPredFailure
                  .PPUpdateWrongEpoch(curEpochNo, targetEpochNo, VotingPeriod.VoteForNextEpoch)
              )
              _ <- state.modify(pps =>
                pps.copy(sgsFutureProposals =
                  KeptProposedPPUpdates[E](pps.sgsFutureProposals.updates ++ pup.updates)
                )
              )
            yield ()
      yield ()
    }
