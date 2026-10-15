// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.Show
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  StateMirrorError
}
import xyz.kd5ujc.scarab.node.MirrorRun
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, StateNotWritable}

/** Why the state of a query cannot be answered as the Haskell state: the answer is the mirror
  * ([[MirrorRun]]) written in the Haskell encoding, and either step can refuse.
  */
enum MirrorRefusal:
  /** The state of the node cannot be mirrored. */
  case Unmirrorable(error: StateMirrorError)

  /** The chain is in Byron, whose ledger state the mirror carries whole and does not model. */
  case ByronLedger

  /** The mirror holds a value outside the range of the Haskell type it is written as. */
  case Unwritable(error: StateNotWritable)

object MirrorRefusal:
  given Show[MirrorRefusal] = Show.fromToString

/** `DebugEpochState` and `DebugNewEpochState` (`Shelley/Ledger/Query.hs`): the epoch state and the
  * new-epoch state of the ledger as the Haskell node writes them, the answers of
  * `cardano-cli query ledger-state`.
  *
  * The state is the node's [[xyz.kd5ujc.scarab.consensus.NodeState]] turned into the mirror of the
  * Haskell state (`StateMirror`) and written with the codec of the state file at the version of its
  * era. The UTxO is not in the answer (the reference answers `map(0)` there, its entries live in
  * the tables of the ledger), as it is not in the state file.
  *
  * What the node cannot give is the [[xyz.kd5ujc.scarab.ledger.state.Gap]]s of the mirror: the
  * answer differs from the reference's only inside them, and the ones the devnet comparison masks
  * are listed in `scripts/devnet/gapmask.py`.
  */
object MirrorAnswers:
  /** The mirror of the state acquired by a query, anchored at the point it was acquired at, and
    * the era it is in (a Shelley-based one: Byron has no new-epoch state).
    */
  private def mirrored(
      env: ConsensusMirrorEnv,
      view: QueryView
  ): Either[MirrorRefusal, EraLedgerState.InShelleyEra[?]] =
    val anchor = view.point match
      case Point.Block(slot, hash) =>
        Some(BlockRef(slot.value, hash, view.blockNo.getOrElse(BigInt(0))))
      case Point.Origin => None
    for
      m <- MirrorRun
        .run(env)(_.toMirror(anchor, view.state))
        .map(_.value)
        .leftMap(MirrorRefusal.Unmirrorable(_))
      shelley <- m.ext.ledgerState.state match
        case _: EraLedgerState.ByronCurrent    => Left(MirrorRefusal.ByronLedger)
        case i: EraLedgerState.InShelleyEra[?] => Right(i)
    yield shelley

  private def unwritable[A](e: Either[StateNotWritable, A]): Either[MirrorRefusal, A] =
    e.leftMap(MirrorRefusal.Unwritable(_))

  /** The answer of `DebugNewEpochState`. */
  def newEpochState(env: ConsensusMirrorEnv, view: QueryView): Either[MirrorRefusal, Bytes] =
    mirrored(env, view).flatMap { case i: EraLedgerState.InShelleyEra[e] =>
      unwritable(HaskellState.encode[e](i.state.nes)(using i.era))
    }

  /** The answer of `DebugEpochState`. */
  def epochState(env: ConsensusMirrorEnv, view: QueryView): Either[MirrorRefusal, Bytes] =
    mirrored(env, view).flatMap { case i: EraLedgerState.InShelleyEra[e] =>
      unwritable(HaskellState.encodeEpochState[e](i.state.nes.es)(using i.era))
    }
