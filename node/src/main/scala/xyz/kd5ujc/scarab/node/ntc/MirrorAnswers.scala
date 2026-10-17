// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.Show
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.{EraTag, MirrorError, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.read.InEra
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, StateNotWritable}

/** Why the state of a query cannot be answered as the Haskell state: the answer is the new-epoch
  * state of the ledger ([[xyz.kd5ujc.scarab.node.read.EraRead.nes]]) written in the Haskell
  * encoding, and either step can refuse.
  */
enum MirrorRefusal:
  /** The state of the node cannot be read as the mirror. */
  case Unmirrorable(error: MirrorError)

  /** The chain is in Byron, whose ledger state the mirror carries whole and does not model. */
  case ByronLedger

  /** The mirror holds a value outside the range of the Haskell type it is written as. */
  case Unwritable(error: StateNotWritable)

object MirrorRefusal:
  given Show[MirrorRefusal] = Show.fromToString

  /** The refusal as the reason a query has no answer. */
  def unanswered(r: MirrorRefusal): Unanswered = r match
    case Unmirrorable(e) => Unanswered.Refused(e)
    case ByronLedger     => Unanswered.Unsupported
    case Unwritable(_)   => Unanswered.Unwritable

/** `DebugEpochState` and `DebugNewEpochState` (`Shelley/Ledger/Query.hs`): the epoch state and the
  * new-epoch state of the ledger as the Haskell node writes them, the answers of
  * `cardano-cli query ledger-state`.
  *
  * The state is read through [[xyz.kd5ujc.scarab.node.read.ChainRead]] as the ledger state of its
  * era (the mirror of the Haskell state, as it is) and written with the codec of the state file at
  * the version of its era. The UTxO is not in the answer (the reference answers `map(0)` there, its
  * entries live in the tables of the ledger), as it is not in the state file.
  *
  * What the node cannot give is the [[xyz.kd5ujc.scarab.ledger.state.Gap]]s of the mirror: the
  * answer differs from the reference's only inside them, and the ones the devnet comparison masks
  * are listed in `scripts/devnet/gapmask.py`.
  */
object MirrorAnswers:
  /** The ledger state of the era the chain is in (a Shelley-based one: Byron has no new-epoch
    * state).
    */
  private def shelley[S](view: QueryView[S]): Either[MirrorRefusal, InEra] =
    view.ledger.leftMap(MirrorRefusal.Unmirrorable(_)).flatMap {
      case None     => Left(MirrorRefusal.ByronLedger)
      case Some(in) => Right(in)
    }

  private def unwritable[A](e: Either[StateNotWritable, A]): Either[MirrorRefusal, A] =
    e.leftMap(MirrorRefusal.Unwritable(_))

  /** The answer of `DebugNewEpochState`. */
  def newEpochState[S](view: QueryView[S]): Either[Unanswered, Bytes] =
    answer(view, epochOnly = false)

  /** The answer of `DebugEpochState`. */
  def epochState[S](view: QueryView[S]): Either[Unanswered, Bytes] =
    answer(view, epochOnly = true)

  private def write[E <: EraTag](
      era: ShelleyBasedEra[E],
      nes: NewEpochState[E],
      epochOnly: Boolean
  ): Either[StateNotWritable, Bytes] =
    if epochOnly then HaskellState.encodeEpochState(nes.es)(using era)
    else HaskellState.encode(nes)(using era)

  private def answer[S](view: QueryView[S], epochOnly: Boolean): Either[Unanswered, Bytes] =
    shelley(view)
      .flatMap { case InEra.Of(era, l) =>
        l.nes
          .leftMap(MirrorRefusal.Unmirrorable(_))
          .flatMap(nes => unwritable(write(era, nes, epochOnly)))
      }
      .leftMap(MirrorRefusal.unanswered)
