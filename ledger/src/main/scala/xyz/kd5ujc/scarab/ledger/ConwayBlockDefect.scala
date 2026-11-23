// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.ledger.era.{
  EraTxError,
  PParamsError,
  ProposalsReadError,
  ProposalsWriteError,
  ScriptVerdictUnavailable
}
import xyz.kd5ujc.scarab.ledger.era.rules.{GovDefect, UtxoStateDefect}

/** Why the node reaches no verdict on a Conway block its era rules apply (#85): a state or a store
  * of its own that the reference cannot hold or meets with an exception, or a limit of this build.
  * None is a refusal of the block, so no peer is blamed for it, nothing is recorded invalid
  * ([[ChainError.ConwayBlock]]), and the follower withholds the block and goes on (issue 567). The
  * defects the era rules raise that the flat rules' routes already place are not here: a script
  * named by its transaction, a verifier's failure and a quantity of the state out of range keep
  * those routes, and the defects of a transaction the flat rules refuse the block for stay a
  * refusal.
  */
enum ConwayBlockDefect:
  /** The evaluator gave no verdict on a script of the block that the rules could not name by its
    * transaction and redeemer ([[ScriptVerdictUnavailable]]).
    */
  case Unavailable(cause: ScriptVerdictUnavailable)

  /** The node's two readings of the block's bytes disagree, or a value its decoder's own bounds
    * rule out came of them (`era.conway.ConwayBlockError.Projection`): no fault of the bytes.
    */
  case Projection(cause: EraTxError)

  /** The parameters in force do not read into Conway's ([[PParamsError]]), which only a damaged
    * state gives.
    */
  case Params(cause: PParamsError)

  /** A stored output the era does not read ([[UtxoStateDefect.StoredOutput]]). */
  case UtxoState(cause: UtxoStateDefect.StoredOutput)

  /** More proposals than a `GovActionIx` numbers ([[GovDefect.ProposalIndexOverflow]]). */
  case Gov(cause: GovDefect.ProposalIndexOverflow)

  /** The node's record of the proposals does not read into the era's ([[ProposalsReadError]]). */
  case ProposalsRead(cause: ProposalsReadError)

  /** The era's proposals do not write into the node's record ([[ProposalsWriteError]]). */
  case ProposalsWrite(cause: ProposalsWriteError)

  def message: String = this match
    case Unavailable(cause)    => s"a script has no verdict: ${cause.reason}"
    case Projection(cause)     => s"the node's readings of the block disagree: $cause"
    case Params(cause)         => s"the parameters in force are not Conway's: $cause"
    case UtxoState(cause)      => s"the UTxO state cannot be changed: $cause"
    case Gov(cause)            => s"GOV has no verdict: $cause"
    case ProposalsRead(cause)  => s"the proposals cannot be read: $cause"
    case ProposalsWrite(cause) => s"the proposals cannot be written: $cause"
