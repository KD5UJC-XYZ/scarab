// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor}

/** Pre-Conway protocol-parameter updates: proposals by genesis delegates (PPUP) and their adoption
  * at the epoch boundary when a quorum proposed the identical update (NEWPP/UPEC).
  */
object PpupRules:
  /** PPUP: record `update` 's proposals for the current or the next epoch. Proposals made before
    * the point of no return (`firstSlotNextEpoch - 2 * stabilityWindow`) target the current epoch.
    */
  def propose(
      state: LedgerState,
      update: Update,
      slot: BigInt,
      firstSlotNextEpoch: BigInt,
      stabilityWindow: BigInt
  ): Either[(String, String), LedgerState] =
    val epoch = state.epoch
    val tooLate = firstSlotNextEpoch - 2 * stabilityWindow
    for
      _ <- Either.cond(
        update.proposals.keys.forall(state.genDelegs.contains),
        (),
        "NonGenesisUpdatePPUP" -> update.proposals.keys.map(_.hex).mkString(",")
      )
      _ <- update.proposals.values.toVector.traverse_(u =>
        canFollow(state.params, u).leftMap("PVCannotFollowPPUP" -> _)
      )
      current = slot < tooLate
      _ <- Either.cond(
        update.epoch == (if current then epoch else epoch + 1),
        (),
        "PPUpdateWrongEpoch" -> s"update for epoch ${update.epoch} in epoch $epoch (slot $slot)"
      )
    yield
      if current then
        state.copy(ppup = state.ppup.copy(current = state.ppup.current ++ update.proposals))
      else state.copy(ppup = state.ppup.copy(future = state.ppup.future ++ update.proposals))

  /** A proposed protocol version must follow the current one: `(major + 1, 0)` or `(major, minor +
    * 1)`.
    */
  private def canFollow(params: ProtocolParams, u: ParamUpdate): Either[String, Unit] =
    u.fields.get(14) match
      case None => Right(())
      case Some(raw) =>
        for
          n <- Cbor.decode(raw)
          xs <- CborRead.arrayN(n, 2, "protocol version")
          major <- CborRead.uint(xs(0), "major")
          minor <- CborRead.uint(xs(1), "minor")
          _ <- Either.cond(
            (major == params.protocolMajor + 1 && minor == 0) ||
              (major == params.protocolMajor && minor == params.protocolMinor + 1),
            (),
            s"($major, $minor) cannot follow ${params.version}"
          )
        yield ()

  /** NEWPP: adopt the voted update (exactly one update proposed identically by at least `quorum`
    * genesis delegates) and rotate the proposals. When adoption changes the deposits, the
    * difference moves to or from the reserves, and the update is skipped if they cannot pay.
    */
  def newEpoch(state: LedgerState, globals: LedgerGlobals): LedgerState =
    val winners = state.ppup.current.values.toVector
      .groupBy(identity)
      .collect { case (u, vs) if vs.size >= globals.updateQuorum => u }
      .toVector
    val adopted: Option[ProtocolParams] = winners match
      case Vector(u) =>
        ProtocolParams
          .update(state.params, u)
          .toOption
          .filter(p => p.maxTxSize + p.maxBlockHeaderSize < p.maxBlockBodySize)
      case _ => None
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

  /** The future proposals survive only if every one has a legal protocol version successor of the
    * parameters in force next epoch.
    */
  private def legalFuture(
      state: LedgerState,
      adopted: Option[ProtocolParams]
  ): Option[Map[Bytes, ParamUpdate]] =
    val params = adopted.getOrElse(state.params)
    Option.when(state.ppup.future.values.forall(canFollow(params, _).isRight))(state.ppup.future)
