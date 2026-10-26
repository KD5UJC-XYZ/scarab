// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.ledger.state.Coin
import xyz.kd5ujc.scarab.codec.Bytes

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
      case Certificate.RegisterDRep(c, _, _)       => script(c)
      case Certificate.UnregisterDRep(c, _)        => script(c)
      case Certificate.UpdateDRep(c, _)            => script(c)
      case Certificate.CommitteeAuthorize(cold, _) => script(cold)
      case Certificate.CommitteeResign(cold, _)    => script(cold)
      case _                                       => None

  /** Withdrawals in the ledger's order: by reward account, script credentials first. */
  def sortedWithdrawals(body: TxBody): Vector[(RewardAccount, Coin)] =
    sortedWithdrawals(body.withdrawals)

  def sortedWithdrawals(withdrawals: Map[RewardAccount, Coin]): Vector[(RewardAccount, Coin)] =
    withdrawals.toVector.sorted(using withdrawalOrder)

  /** By network, script credentials before key credentials, then the hash. */
  private val withdrawalOrder: Ordering[(RewardAccount, Coin)] = (a, b) =>
    val (x, y) = (a._1, b._1)
    val networks = Integer.compare(x.network, y.network)
    if networks != 0 then networks
    else
      val kinds = java.lang.Boolean.compare(
        x.credential.isInstanceOf[Credential.Key],
        y.credential.isInstanceOf[Credential.Key]
      )
      if kinds != 0 then kinds else x.credential.hash.compareTo(y.credential.hash)

  def sortedVoters(body: TxBody): Vector[Voter] = body.votes.map(_._1).sorted(using Voter.ordering)

  /** Every purpose of the transaction in the ledger's orders: inputs sorted by id and index,
    * policies sorted by id, certificates and proposals by position, withdrawals by account and
    * voters by `Ord Voter`. Inputs missing from `spent` are skipped.
    */
  def all(body: TxBody, spent: Map[TxIn, TxOut]): Vector[Purpose] =
    val spending = body.inputs.sorted.zipWithIndex.flatMap { case (in, ix) =>
      spent.get(in).map(Spending(ix, in, _))
    }
    val minting = body.mint.keys.toVector.sorted.zipWithIndex.map { case (p, ix) =>
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

  /** `getAlonzoScriptsNeeded` / `getConwayScriptsNeeded`: spending, withdrawing, certifying,
    * minting, voting, proposing. Before Conway a duplicate certificate reuses its first index while
    * still occupying its place in the sequence (`Alonzo/UTxO.hs:313-365`).
    */
  private[ledger] def collection(
      body: TxBody,
      spent: Map[TxIn, TxOut],
      conway: Boolean
  ): Vector[Purpose] =
    val purposes = all(body, spent)
    val certs = purposes.collect { case p: Certifying => p }
    val indexed =
      if conway then certs
      else
        certs
          .foldLeft((Map.empty[Certificate, Int], Vector.empty[Certifying])) {
            case ((seen, rest), p) =>
              val index = seen.getOrElse(p.certificate, p.index)
              (seen.updated(p.certificate, index), rest :+ Certifying(index, p.certificate))
          }
          ._2
    purposes.collect { case p: Spending => p } ++
      purposes.collect { case p: Rewarding => p } ++ indexed ++
      purposes.collect { case p: Minting => p } ++
      purposes.collect { case p: Voting => p } ++
      purposes.collect { case p: Proposing => p }
