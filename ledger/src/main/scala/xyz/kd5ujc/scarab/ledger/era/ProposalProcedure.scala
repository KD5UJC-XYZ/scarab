// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.ledger.{Proposal, ProposalIdentity}

/** A governance proposal (`data ProposalProcedure era`, cardano-ledger f649f975
  * `eras/conway/impl/src/Cardano/Ledger/Conway/Governance/Procedures.hs:498-504`), over the flat
  * [[Proposal]] it was read as. Two proposals are equal when they mean the same
  * ([[Proposal.identity]]), as the reference's derived `Eq` compares the decoded action: treasury
  * withdrawals as the `Map AccountAddress Coin` they are, whatever the order of their entries
  * (`Procedures.hs:826-834`), and a parameter update by the typed value of each field.
  */
final class ProposalProcedure private[era] (val value: Proposal):
  private lazy val identity: ProposalIdentity = Proposal.identity(value)

  override def equals(that: Any): Boolean = that match
    case o: ProposalProcedure => identity == o.identity
    case _                    => false
  override def hashCode(): Int = identity.hashCode
  override def toString: String = s"ProposalProcedure($value)"

object ProposalProcedure:
  given CanEqual[ProposalProcedure, ProposalProcedure] = CanEqual.derived
