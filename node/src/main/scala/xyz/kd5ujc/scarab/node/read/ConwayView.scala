// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import cats.Show
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.ledger.era.PParamsError
import xyz.kd5ujc.scarab.ledger.rules.EraNes
import xyz.kd5ujc.scarab.ledger.state.{Conway, MirrorError, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.protocol.Era

/** The new-epoch state of Conway at the tip of a chain, for what only Conway has: the mempool and
  * the block forger (design `ledger-mirror-step2.md` §2.2: "the era is refused at the unpack point
  * by the witness"). A chain in another era has none, and says which.
  */
object ConwayView:
  /** Why a chain has no Conway ledger state. */
  enum Unavailable:
    /** The chain is in Byron, which has no new-epoch state. */
    case InByron

    /** The chain is in an earlier era than Conway. */
    case InEarlier(era: Era)

    /** The supplied [[ChainRead]] or [[EraRead]] refused the ledger state. */
    case Refused(error: MirrorError)

    /** The parameters in force of a Conway state do not read into Conway's types
      * ([[PParamsError]]), which only a damaged state gives: the mempool, which judges Conway's
      * transactions by Conway's rules, has no ledger of it
      * ([[xyz.kd5ujc.scarab.node.mempool.ConwayPoolLedger]]).
      */
    case Params(error: PParamsError)

  object Unavailable:
    given Show[Unavailable] = Show.show {
      case InByron      => "the chain is in Byron"
      case InEarlier(e) => s"the chain is in $e, before Conway"
      case Refused(e)   => s"the ledger state cannot be read: $e"
      case Params(e)    => s"the parameters in force are not Conway's: $e"
    }

  /** The Conway ledger state at the tip `at` of the chain whose state is `s`. */
  def apply[S](s: S, at: Option[BlockRef])(using
      read: ChainRead[S]
  ): Either[Unavailable, NewEpochState[Conway]] =
    s.ledger(at) match
      case Left(error) => Left(Unavailable.Refused(error))
      case Right(None) => Left(Unavailable.InByron)
      case Right(Some(InEra.Of(era, state))) =>
        era match
          case ShelleyBasedEra.SConway => state.nes.left.map(Unavailable.Refused(_))
          case other                   => Left(Unavailable.InEarlier(EraNes.eraOf(other)))

  /** The Conway state of a new-epoch state of any era, none in another. */
  def of(e: EraNes): Option[NewEpochState[Conway]] = e match
    case i: EraNes.InEra[x] =>
      i.era match
        case ShelleyBasedEra.SConway => Some(i.nes)
        case _                       => None
