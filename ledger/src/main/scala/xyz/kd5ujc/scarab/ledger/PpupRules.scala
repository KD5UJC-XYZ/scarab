// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}

/** Pre-Conway protocol-parameter updates: proposals by genesis delegates (PPUP) and their adoption
  * at the epoch boundary when a quorum proposed the identical update (NEWPP/UPEC).
  */
object PpupRules:
  /** Why an update proposal is refused: by PPUP, or because its protocol version is not a pair of
    * numbers (which the reference's decoder would not admit).
    */
  enum Refusal:
    case Failed(failure: PpupFailure)
    case Unreadable

    /** The refusal of transaction `id` as the ledger's failure for it. */
    def inTx(id: Bytes): LedgerFailure = this match
      case Failed(f)  => LedgerFailure.Rejected(id, PredicateFailure.utxo(UtxoFailure.Update(f)))
      case Unreadable => LedgerFailure.Undecodable(id, TxDefect.UnreadableProtocolVersion)

  /** PPUP: record `update` 's proposals for the current or the next epoch. Proposals made before
    * the point of no return (`firstSlotNextEpoch - 2 * stabilityWindow`) target the current epoch.
    */
  def propose(
      state: LedgerState,
      update: Update,
      slot: BigInt,
      firstSlotNextEpoch: BigInt,
      stabilityWindow: BigInt
  ): Either[Refusal, LedgerState] =
    val epoch = state.epoch
    val tooLate = firstSlotNextEpoch - 2 * stabilityWindow
    val current = slot < tooLate
    for
      _ <- Either.cond(
        update.proposals.keys.forall(state.genDelegs.contains),
        (),
        Refusal.Failed(
          PpupFailure.NonGenesisUpdate(
            Mismatch(update.proposals.keySet, state.genDelegs.keySet)
          )
        )
      )
      _ <- update.proposals.values.toVector.traverse_(canFollow(state.params, _))
      _ <- Either.cond(
        update.epoch == (if current then epoch else epoch + 1),
        (),
        Refusal.Failed(
          PpupFailure.PPUpdateWrongEpoch(
            epoch,
            update.epoch,
            if current then VotingPeriod.VoteForThisEpoch else VotingPeriod.VoteForNextEpoch
          )
        )
      )
    yield
      if current then
        state.copy(ppup = state.ppup.copy(current = state.ppup.current ++ update.proposals))
      else state.copy(ppup = state.ppup.copy(future = state.ppup.future ++ update.proposals))

  /** A proposed protocol version must follow the current one: `(major + 1, 0)` or `(major, minor +
    * 1)`.
    */
  private[ledger] def canFollow(params: ProtocolParams, u: ParamUpdate): Either[Refusal, Unit] =
    u.fields.get(14) match
      case None => Right(())
      case Some(raw) =>
        for
          n <- Cbor.decode(raw).leftMap(_ => Refusal.Unreadable)
          xs <- CborRead.arrayN(n, 2, "protocol version").leftMap(_ => Refusal.Unreadable)
          major <- CborRead.uint(xs(0), "major").leftMap(_ => Refusal.Unreadable)
          minor <- CborRead.uint(xs(1), "minor").leftMap(_ => Refusal.Unreadable)
          _ <- Either.cond(
            (major == params.protocolMajor + 1 && minor == 0) ||
              (major == params.protocolMajor && minor == params.protocolMinor + 1),
            (),
            Refusal.Failed(PpupFailure.PVCannotFollow(major, minor))
          )
        yield ()

  /** NEWPP: adopt the voted update (exactly one update proposed identically by at least `quorum`
    * genesis delegates) and rotate the proposals. When adoption changes the deposits, the
    * difference moves to or from the reserves, and the update is skipped if they cannot pay.
    */
  def newEpoch(state: LedgerState, globals: LedgerGlobals): LedgerState =
    val adopted = voted(state.params, state.ppup.current, globals.updateQuorum)
    val rotated = Ppup(legalFuture(state, adopted).getOrElse(Map.empty), Map.empty)
    adopted match
      case None => state.copy(ppup = rotated)
      case Some(pp) =>
        val before = state.accounts.size * state.params.keyDeposit +
          state.pools.size * state.params.poolDeposit
        val after = state.accounts.size * pp.keyDeposit + state.pools.size * pp.poolDeposit
        val diff = before - after
        if state.reserves + diff >= 0 then
          state.copy(
            params = pp,
            reserves = state.reserves + diff,
            deposited = after,
            ppup = rotated
          )
        else state.copy(ppup = rotated)

  /** The parameters the proposals of the epoch vote for: the one update that at least `quorum`
    * genesis delegates proposed identically, applied to `params` (`votedFuturePParams`,
    * `Rules/Ppup.hs:205-225`).
    */
  private[ledger] def voted(
      params: ProtocolParams,
      current: Map[Bytes, ParamUpdate],
      quorum: Int
  ): Option[ProtocolParams] =
    val winners = current.values.toVector
      .groupBy(identity)
      .collect { case (u, vs) if vs.size >= quorum => u }
      .toVector
    winners match
      case Vector(u) =>
        ProtocolParams
          .update(params, u)
          .toOption
          .filter(p => p.maxTxSize + p.maxBlockHeaderSize < p.maxBlockBodySize)
      case _ => None

  /** The future proposals survive only if every one has a legal protocol version successor of the
    * parameters in force next epoch.
    */
  private def legalFuture(
      state: LedgerState,
      adopted: Option[ProtocolParams]
  ): Option[Map[Bytes, ParamUpdate]] =
    val params = adopted.getOrElse(state.params)
    Option.when(state.ppup.future.values.forall(canFollow(params, _).isRight))(state.ppup.future)
