// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.ledger.era.{
  EraTxError,
  PParamsError,
  ProposalsReadError,
  ProposalsWriteError,
  ScriptVerdictUnavailable
}
import xyz.kd5ujc.scarab.ledger.era.rules.{GovDefect, LeiosClosureDefect, UtxoStateDefect}

/** Why the node cannot apply a Dijkstra block and reaches no verdict on it: a state or a store of
  * its own that the reference cannot hold or meets with `error`, or a limit of this build. None is
  * a refusal of the block, so no peer is blamed for it and nothing is recorded invalid
  * ([[ChainError.DijkstraBlock]]). The follower withholds the block for a limit of this build that
  * valid peer input can meet ([[Unavailable]], [[UnstorableProposals]], [[Projection]]) and goes
  * on; for the rest the node stops.
  */
enum DijkstraBlockDefect:
  /** The certified endorser block's closure is not in the store, or a transaction of it does not
    * decode (`LeiosClosureError`, met with `error` in `resolveAndApplyLeiosClosure`, ouroboros-
    * consensus 2416493b `Storage/LedgerDB/Forker.hs:1084-1087`).
    */
  case Closure(cause: LeiosClosureDefect)

  /** The Leios checks of the certifying block reach no verdict on a state of the node's own
    * ([[LeiosCertificationDefect]]); the native library's failure is a provider defect instead.
    */
  case Certification(cause: LeiosCertificationDefect)

  /** Praos2's header rules met a state or an epoch history of the node's own they cannot judge. */
  case Header(cause: Praos2Defect)

  /** The ticked state gives no Praos2 ledger view: a parameter outside its word. */
  case View(cause: CrossingForecastFailure)

  /** The parameters in force do not read into Dijkstra's ([[PParamsError]]). */
  case Parameters(cause: PParamsError)

  /** The proposals of the state do not read into Dijkstra's ([[ProposalsReadError]]). */
  case Proposals(cause: ProposalsReadError)

  /** The proposals the rules leave cannot be written back into the state's record
    * ([[ProposalsWriteError]]): an update of a parameter Dijkstra adds, keys 34 to 56, which the
    * record holds only once the Leios session's format change lands (#85). The public testnet's
    * ledger admitted such proposals, so a peer delivers one honestly: the follower withholds the
    * block, and the node does not follow the chain past it until the record holds them.
    */
  case UnstorableProposals(cause: ProposalsWriteError)

  /** The evaluator gave no verdict on a script of the block ([[ScriptVerdictUnavailable]]). The
    * follower withholds the block for it, as it withholds the other eras' (issue 567), rather than
    * stop.
    */
  case Unavailable(cause: ScriptVerdictUnavailable)

  /** The node's two readings of the block's bytes disagree, or a value its decoder's own bounds
    * rule out came of them ([[DijkstraBlockError.Projection]]): no fault of the bytes, so the
    * follower withholds the block, as for [[Unavailable]].
    */
  case Projection(cause: EraTxError)

  /** A stored output the era does not read ([[UtxoStateDefect.StoredOutput]]). */
  case UtxoState(cause: UtxoStateDefect)

  /** GOV's defect that is not a quantity of the state ([[GovDefect.ProposalIndexOverflow]]). */
  case Gov(cause: GovDefect)

  def message: String = this match
    case Closure(cause)             => s"the endorser block's closure cannot be applied: $cause"
    case Certification(cause)       => cause.message
    case Header(cause)              => s"Praos2 has no verdict on the header: $cause"
    case View(cause)                => s"the ticked state gives no Praos2 ledger view: $cause"
    case Parameters(cause)          => s"the parameters do not read into Dijkstra's: $cause"
    case Proposals(cause)           => s"the proposals do not read into Dijkstra's: $cause"
    case UnstorableProposals(cause) => s"the proposals cannot be written back: $cause"
    case Unavailable(cause)         => s"a script has no verdict: ${cause.reason}"
    case Projection(cause)          => s"the node's readings of the block disagree: $cause"
    case UtxoState(cause)           => s"the UTxO state cannot be changed: $cause"
    case Gov(cause)                 => s"GOV has no verdict: $cause"
