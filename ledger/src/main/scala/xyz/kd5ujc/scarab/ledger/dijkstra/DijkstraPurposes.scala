// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Credential, RewardAccount}

/** A Plutus purpose of a Dijkstra transaction with its index and item (`DijkstraPlutusPurpose
  * AsIxItem`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:122-130`, cardano-ledger
  * 9df9f98c). `tag` is the redeemer tag of the purpose; the constructor order is the order of the
  * ledger's purposes. A guard is named by its script hash (`GuardsScriptHashView`).
  */
enum DijkstraPurpose(val tag: Int):
  case Spending(ix: Int, input: DijkstraTxIn) extends DijkstraPurpose(0)
  case Minting(ix: Int, policy: Bytes) extends DijkstraPurpose(1)
  case Certifying(ix: Int, cert: DijkstraCert) extends DijkstraPurpose(2)
  case Withdrawing(ix: Int, account: RewardAccount) extends DijkstraPurpose(3)
  case Voting(ix: Int, voter: DijkstraVoter) extends DijkstraPurpose(4)
  case Proposing(ix: Int, proposal: DijkstraProposal) extends DijkstraPurpose(5)
  case Guarding(ix: Int, script: Bytes) extends DijkstraPurpose(6)

  def index: Int = this match
    case Spending(i, _)    => i
    case Minting(i, _)     => i
    case Certifying(i, _)  => i
    case Withdrawing(i, _) => i
    case Voting(i, _)      => i
    case Proposing(i, _)   => i
    case Guarding(i, _)    => i

  /** The purpose as a redeemer key (`AsIx`): its tag and index. */
  def key: (Int, Int) = (tag, index)

/** The orders and collections the ledger indexes a body's purposes by. */
object DijkstraPurposes:

  /** `Ord (Credential r)`: `ScriptHashObj` before `KeyHashObj` (derived, constructor order,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Credential.hs:98-101`, 9df9f98c), then the hash.
    */
  val credentialOrder: Ordering[Credential] = (a, b) =>
    val kinds = java.lang.Boolean.compare(a.isKey, b.isKey)
    if kinds != 0 then kinds else a.hash.compareTo(b.hash)

  /** `Ord AccountAddress`: the network, then the account's credential (`Address.hs:183-191`). */
  val accountOrder: Ordering[RewardAccount] = (a, b) =>
    val networks = Integer.compare(a.network, b.network)
    if networks != 0 then networks else credentialOrder.compare(a.credential, b.credential)

  /** `Ord TxIn`: the transaction id, then the index. */
  val inputOrder: Ordering[DijkstraTxIn] = (a, b) =>
    val ids = a.id.compareTo(b.id)
    if ids != 0 then ids else Integer.compare(a.index, b.index)

  /** `Ord Voter`: committee members, DReps, then pools (`Procedures.hs`, derived). */
  val voterOrder: Ordering[DijkstraVoter] = (a, b) =>
    (a, b) match
      case (DijkstraVoter.Committee(x), DijkstraVoter.Committee(y)) => credentialOrder.compare(x, y)
      case (DijkstraVoter.DRep(x), DijkstraVoter.DRep(y))           => credentialOrder.compare(x, y)
      case (DijkstraVoter.StakePool(x), DijkstraVoter.StakePool(y)) => x.compareTo(y)
      case _ => Integer.compare(a.ordinal, b.ordinal)

  /** `Ord GovActionId`: the transaction id, then the index. */
  val govActionOrder: Ordering[DijkstraGovActionId] = (a, b) =>
    val ids = a.txId.compareTo(b.txId)
    if ids != 0 then ids else Integer.compare(a.index, b.index)

  def sortedInputs(body: DijkstraTxBody): Vector[DijkstraTxIn] =
    body.inputs.sorted(using inputOrder)

  def sortedPolicies(body: DijkstraTxBody): Vector[Bytes] = body.mint.keys.toVector.sorted

  def sortedWithdrawals(body: DijkstraTxBody): Vector[(RewardAccount, BigInt)] =
    body.withdrawals.sortBy(_._1)(using accountOrder)

  def sortedVoters(body: DijkstraTxBody): Vector[DijkstraVoter] =
    body.votingProcedures.map(_._1).sorted(using voterOrder)

  private def scriptHash(c: Credential): Option[Bytes] = c match
    case Credential.Script(h) => Some(h)
    case Credential.Key(_)    => None

  /** `getScriptWitnessDijkstraTxCert` (`Dijkstra/TxCert.hs:323-340`). */
  def certificateScript(cert: DijkstraCert): Option[Bytes] = cert match
    case DijkstraCert.Register(c, _)                 => scriptHash(c)
    case DijkstraCert.Unregister(c, _)               => scriptHash(c)
    case DijkstraCert.Delegate(c, _)                 => scriptHash(c)
    case DijkstraCert.RegisterDelegate(c, _, _)      => scriptHash(c)
    case DijkstraCert.RegisterPool(_)                => None
    case DijkstraCert.RetirePool(_, _)               => None
    case DijkstraCert.AuthorizeCommitteeHotKey(c, _) => scriptHash(c)
    case DijkstraCert.ResignCommitteeColdKey(c, _)   => scriptHash(c)
    case DijkstraCert.RegisterDRep(c, _, _)          => scriptHash(c)
    case DijkstraCert.UnregisterDRep(c, _)           => scriptHash(c)
    case DijkstraCert.UpdateDRep(c, _)               => scriptHash(c)

  private def proposalScript(p: DijkstraProposal): Option[Bytes] = p.action match
    case DijkstraGovAction.ParameterChange(_, _, policy)  => policy
    case DijkstraGovAction.TreasuryWithdrawals(_, policy) => policy
    case _                                                => None

  private def voterScript(v: DijkstraVoter): Option[Bytes] = v match
    case DijkstraVoter.Committee(c) => scriptHash(c)
    case DijkstraVoter.DRep(c)      => scriptHash(c)
    case DijkstraVoter.StakePool(_) => None

  /** The scripts a body needs, with their purposes, in the ledger's order
    * (`getDijkstraScriptsNeeded`, `Dijkstra/UTxO.hs:188-198`, after `getConwayScriptsNeeded`,
    * `Conway/UTxO.hs:62-105`): spending (the inputs whose spent output is locked by a script),
    * withdrawing, certifying, minting, voting, proposing and guarding. An input that `utxo` does
    * not resolve needs nothing here.
    */
  def scriptsNeeded(
      utxo: Map[DijkstraTxIn, DijkstraTxOut],
      body: DijkstraTxBody
  ): Vector[(DijkstraPurpose, Bytes)] =
    val spending = sortedInputs(body).zipWithIndex.flatMap { (in, ix) =>
      utxo.get(in).flatMap(out => paymentScript(out)).map(DijkstraPurpose.Spending(ix, in) -> _)
    }
    val withdrawing = sortedWithdrawals(body).zipWithIndex.flatMap { case ((a, _), ix) =>
      scriptHash(a.credential).map(DijkstraPurpose.Withdrawing(ix, a) -> _)
    }
    val certifying = body.certificates.zipWithIndex.flatMap { (c, ix) =>
      certificateScript(c).map(DijkstraPurpose.Certifying(ix, c) -> _)
    }
    val minting = sortedPolicies(body).zipWithIndex.map { (p, ix) =>
      DijkstraPurpose.Minting(ix, p) -> p
    }
    val voting = sortedVoters(body).zipWithIndex.flatMap { (v, ix) =>
      voterScript(v).map(DijkstraPurpose.Voting(ix, v) -> _)
    }
    val proposing = body.proposals.zipWithIndex.flatMap { (p, ix) =>
      proposalScript(p).map(DijkstraPurpose.Proposing(ix, p) -> _)
    }
    val guarding = body.guards.zipWithIndex.flatMap { (c, ix) =>
      scriptHash(c).map(h => DijkstraPurpose.Guarding(ix, h) -> h)
    }
    spending ++ withdrawing ++ certifying ++ minting ++ voting ++ proposing ++ guarding

  /** The script that locks an output: its address's payment credential when it is a script. */
  def paymentScript(out: DijkstraTxOut): Option[Bytes] = out.address.kind match
    case xyz.kd5ujc.scarab.ledger.Address.Kind.Shelley(_, payment, _, _) => scriptHash(payment)
    case _                                                               => None

  /** The purpose a redeemer key names (`dijkstraRedeemerPointerInverse`, `Dijkstra/TxBody.hs:
    * 1297-1316`); a guard key names only a script guard.
    */
  def inverse(body: DijkstraTxBody, tag: Int, index: Long): Option[DijkstraPurpose] =
    def at[A](xs: Vector[A]): Option[(A, Int)] =
      Option.when(index >= 0 && index < xs.size)((xs(index.toInt), index.toInt))
    tag match
      case 0 => at(sortedInputs(body)).map((in, ix) => DijkstraPurpose.Spending(ix, in))
      case 1 => at(sortedPolicies(body)).map((p, ix) => DijkstraPurpose.Minting(ix, p))
      case 2 => at(body.certificates).map((c, ix) => DijkstraPurpose.Certifying(ix, c))
      case 3 =>
        at(sortedWithdrawals(body)).map { case ((a, _), ix) => DijkstraPurpose.Withdrawing(ix, a) }
      case 4 => at(sortedVoters(body)).map((v, ix) => DijkstraPurpose.Voting(ix, v))
      case 5 => at(body.proposals).map((p, ix) => DijkstraPurpose.Proposing(ix, p))
      case 6 =>
        at(body.guards).flatMap((c, ix) => scriptHash(c).map(DijkstraPurpose.Guarding(ix, _)))
      case _ => None
