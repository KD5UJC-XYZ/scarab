// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.cbor.Bytes

/** A place in a transaction a script can be asked to validate, with its position (the redeemer
  * index). The redeemer tags are spend 0, mint 1, cert 2, reward 3, vote 4 and propose 5.
  */
enum Purpose(val tag: Int):
  def index: Int

  case Spending(index: Int, input: TxIn, output: TxOut) extends Purpose(0)
  case Minting(index: Int, policy: Bytes) extends Purpose(1)
  case Certifying(index: Int, certificate: Certificate) extends Purpose(2)
  case Rewarding(index: Int, account: RewardAccount) extends Purpose(3)
  case Voting(index: Int, voter: Voter) extends Purpose(4)
  case Proposing(index: Int, proposal: Proposal) extends Purpose(5)

  /** The hash of the script that must validate this purpose, if its credential is a script. */
  def scriptHash: Option[Bytes] = this match
    case Spending(_, _, out) =>
      out.address.kind match
        case Address.Kind.Shelley(_, Credential.Script(h), _, _) => Some(h)
        case _                                                   => None
    case Minting(_, policy)  => Some(policy)
    case Certifying(_, cert) => Purpose.certificateScript(cert)
    case Rewarding(_, account) =>
      account.credential match
        case Credential.Script(h) => Some(h)
        case _                    => None
    case Voting(_, voter) =>
      voter match
        case Voter.CommitteeHot(Credential.Script(h)) => Some(h)
        case Voter.DRepVoter(Credential.Script(h))    => Some(h)
        case _                                        => None
    case Proposing(_, proposal) =>
      proposal.action match
        case GovAction.ParameterChange(_, _, policy)  => policy
        case GovAction.TreasuryWithdrawals(_, policy) => policy
        case _                                        => None

object Purpose:
  /** The script credential a certificate needs a witness from. */
  def certificateScript(cert: Certificate): Option[Bytes] =
    def script(c: Credential) = c match
      case Credential.Script(h) => Some(h)
      case _                    => None
    cert match
      case Certificate.Register(c, Some(_))        => script(c)
      case Certificate.Unregister(c, _)            => script(c)
      case Certificate.Delegate(c, _, _, _)        => script(c)
      case Certificate.RegisterDRep(c, _)          => script(c)
      case Certificate.UnregisterDRep(c, _)        => script(c)
      case Certificate.UpdateDRep(c)               => script(c)
      case Certificate.CommitteeAuthorize(cold, _) => script(cold)
      case Certificate.CommitteeResign(cold)       => script(cold)
      case _                                       => None

  /** Withdrawals in the ledger's order: by reward account, script credentials first. */
  def sortedWithdrawals(body: TxBody): Vector[(RewardAccount, BigInt)] =
    body.withdrawals.sortBy(w =>
      (w._1.network, w._1.credential.isInstanceOf[Credential.Key], w._1.credential.hash.hex)
    )

  def sortedVoters(body: TxBody): Vector[Voter] = body.votes.map(_._1).sorted(using Voter.ordering)

  /** Every purpose of the transaction in the ledger's orders: inputs sorted by id and index,
    * policies sorted by id, certificates and proposals by position, withdrawals by account and
    * voters by `Ord Voter`. Inputs missing from `spent` are skipped.
    */
  def all(body: TxBody, spent: Map[TxIn, TxOut]): Vector[Purpose] =
    val spending = body.inputs.sorted.zipWithIndex.flatMap { case (in, ix) =>
      spent.get(in).map(Spending(ix, in, _))
    }
    val minting = body.mint.keys.toVector.sortBy(_.hex).zipWithIndex.map { case (p, ix) =>
      Minting(ix, p)
    }
    val certifying = body.certificates.zipWithIndex.map { case (c, ix) => Certifying(ix, c) }
    val rewarding = sortedWithdrawals(body).zipWithIndex.map { case ((a, _), ix) =>
      Rewarding(ix, a)
    }
    val voting = sortedVoters(body).zipWithIndex.map { case (v, ix) => Voting(ix, v) }
    val proposing = body.proposals.zipWithIndex.map { case (p, ix) => Proposing(ix, p) }
    spending ++ minting ++ certifying ++ rewarding ++ voting ++ proposing

  /** The purpose a redeemer pointer names. */
  def resolve(body: TxBody, spent: Map[TxIn, TxOut], tag: Int, index: BigInt): Option[Purpose] =
    all(body, spent).find(p => p.tag == tag && BigInt(p.index) == index)
