// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{Anchor, Certificate, Credential, PoolParams}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayDelegCert,
  ConwayEraTxCert,
  ShelleyEraTxCert,
  ConwayGovCert,
  Delegatee,
  EraField,
  EraTxError,
  PoolCert
}
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.protocol.Era

/** A Conway certificate (`data ConwayTxCert era`, cardano-ledger f649f975
  * `eras/conway/impl/src/Cardano/Ledger/Conway/TxCert.hs:643-647`): a staking, a pool or a
  * governance certificate. Genesis delegation and moving instantaneous rewards are not Conway
  * certificates (`ConwayTxCertUpgradeError`, `Conway/TxCert.hs:100-102`).
  */
enum ConwayTxCert:
  case ConwayTxCertDeleg(cert: ConwayDelegCert)
  case ConwayTxCertPool(cert: PoolCert)
  case ConwayTxCertGov(cert: ConwayGovCert)

object ConwayTxCert:
  given CanEqual[ConwayTxCert, ConwayTxCert] = CanEqual.derived

  /** A certificate as Conway's decoder reads it ([[Certificate.decode]] at Conway): its tags 5 and
    * 6 are refused there, before their fields are read.
    */
  def decode(n: Node): Either[EraTxError, ConwayTxCert] =
    Certificate.decode(n, Era.Conway).left.map(EraTxError.Decode(_)).flatMap(fromFlat)

  /** The Conway certificate of a flat one: refused if Conway has no such certificate, or if it
    * delegates to neither a pool nor a DRep, which no decoder reads.
    */
  private[era] def fromFlat(c: Certificate): Either[EraTxError, ConwayTxCert] = c match
    case Certificate.Register(cred, deposit) =>
      Right(ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(cred, deposit)))
    case Certificate.Unregister(cred, refund) =>
      Right(ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(cred, refund)))
    case Certificate.Delegate(cred, pool, drep, deposit) =>
      val delegatee = (pool, drep) match
        case (Some(p), Some(d)) => Right(Delegatee.DelegStakeVote(p, d))
        case (Some(p), None)    => Right(Delegatee.DelegStake(p))
        case (None, Some(d))    => Right(Delegatee.DelegVote(d))
        case (None, None)       => Left(EraTxError.NoDelegatee)
      delegatee.map(to =>
        deposit match
          case Some(d) => ConwayTxCertDeleg(ConwayDelegCert.ConwayRegDelegCert(cred, to, d))
          case None    => ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(cred, to))
      )
    case Certificate.RegisterPool(params) => Right(ConwayTxCertPool(PoolCert.RegPool(params)))
    case Certificate.RetirePool(pool, epoch) =>
      Right(ConwayTxCertPool(PoolCert.RetirePool(pool, epoch)))
    case Certificate.RegisterDRep(cred, deposit, anchor) =>
      Right(ConwayTxCertGov(ConwayGovCert.ConwayRegDRep(cred, deposit, anchor)))
    case Certificate.UnregisterDRep(cred, refund) =>
      Right(ConwayTxCertGov(ConwayGovCert.ConwayUnRegDRep(cred, refund)))
    case Certificate.UpdateDRep(cred, anchor) =>
      Right(ConwayTxCertGov(ConwayGovCert.ConwayUpdateDRep(cred, anchor)))
    case Certificate.CommitteeAuthorize(cold, hot) =>
      Right(ConwayTxCertGov(ConwayGovCert.ConwayAuthCommitteeHotKey(cold, hot)))
    case Certificate.CommitteeResign(cold, anchor) =>
      Right(ConwayTxCertGov(ConwayGovCert.ConwayResignCommitteeColdKey(cold, anchor)))
    case Certificate.GenesisDelegate(_, _, _) =>
      Left(EraTxError.NotInEra(EraField.GenesisDelegation))
    case Certificate.MoveRewards(_, _) => Left(EraTxError.NotInEra(EraField.InstantaneousRewards))

  // The credential a staking certificate is witnessed by: none for a registration without a
  // deposit, kept from Shelley through Conway (`Conway/TxCert.hs:756-761`).
  private def delegWitness(c: ConwayDelegCert): Option[Credential] = c match
    case ConwayDelegCert.ConwayRegCert(_, None)         => None
    case ConwayDelegCert.ConwayRegCert(cred, Some(_))   => Some(cred)
    case ConwayDelegCert.ConwayUnRegCert(cred, _)       => Some(cred)
    case ConwayDelegCert.ConwayDelegCert(cred, _)       => Some(cred)
    case ConwayDelegCert.ConwayRegDelegCert(cred, _, _) => Some(cred)

  private def keyHash(c: Credential): Option[Bytes] = c match
    case Credential.Key(h)    => Some(h)
    case Credential.Script(_) => None

  private def scriptHash(c: Credential): Option[Bytes] = c match
    case Credential.Script(h) => Some(h)
    case Credential.Key(_)    => None

  val instance: ConwayEraTxCert[Conway] & ShelleyEraTxCert[Conway] =
    new ConwayEraTxCert[Conway] with ShelleyEraTxCert[Conway]:
      // `getVKeyWitnessConwayTxCert`, `Conway/TxCert.hs:786-795`, cardano-ledger f649f975
      override def getVKeyWitnessTxCert(cert: ConwayTxCert): Option[Bytes] = cert match
        case ConwayTxCertDeleg(c) => delegWitness(c).flatMap(keyHash)
        case ConwayTxCertPool(c)  => Some(c.witness)
        case ConwayTxCertGov(c)   => keyHash(c.witness)
      // `getScriptWitnessConwayTxCert`, `Conway/TxCert.hs:763-784`, cardano-ledger f649f975: a pool is never a script
      override def getScriptWitnessTxCert(cert: ConwayTxCert): Option[Bytes] = cert match
        case ConwayTxCertDeleg(c) => delegWitness(c).flatMap(scriptHash)
        case ConwayTxCertPool(_)  => None
        case ConwayTxCertGov(c)   => scriptHash(c.witness)
      override def getRegPoolTxCert(cert: ConwayTxCert): Option[PoolParams] = cert match
        case ConwayTxCertPool(PoolCert.RegPool(params)) => Some(params)
        case _                                          => None
      override def getRetirePoolTxCert(cert: ConwayTxCert): Option[(Bytes, BigInt)] = cert match
        case ConwayTxCertPool(PoolCert.RetirePool(pool, epoch)) => Some((pool, epoch))
        case _                                                  => None
      override def lookupRegStakeTxCert(cert: ConwayTxCert): Option[Credential] = cert match
        case ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(c, _))         => Some(c)
        case ConwayTxCertDeleg(ConwayDelegCert.ConwayRegDelegCert(c, _, _)) => Some(c)
        case _                                                              => None
      override def lookupUnRegStakeTxCert(cert: ConwayTxCert): Option[Credential] = cert match
        case ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(c, _)) => Some(c)
        case _                                                        => None
      override def getRegTxCert(cert: ConwayTxCert): Option[Credential] = cert match
        case ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(c, None)) => Some(c)
        case _                                                         => None
      override def getUnRegTxCert(cert: ConwayTxCert): Option[Credential] = cert match
        case ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(c, None)) => Some(c)
        case _                                                           => None
      override def getDelegStakeTxCert(cert: ConwayTxCert): Option[(Credential, Bytes)] = cert match
        case ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(c, Delegatee.DelegStake(p))) =>
          Some((c, p))
        case _ => None
      override def getRegDepositTxCert(cert: ConwayTxCert): Option[(Credential, BigInt)] =
        cert match
          case ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(c, Some(d))) => Some((c, d))
          case _                                                            => None
      override def getUnRegDepositTxCert(cert: ConwayTxCert): Option[(Credential, BigInt)] =
        cert match
          case ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(c, Some(d))) => Some((c, d))
          case _                                                              => None
      override def getDelegTxCert(cert: ConwayTxCert): Option[(Credential, Delegatee)] = cert match
        case ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(c, to)) => Some((c, to))
        case _                                                         => None
      override def getRegDepositDelegTxCert(
          cert: ConwayTxCert
      ): Option[(Credential, Delegatee, BigInt)] = cert match
        case ConwayTxCertDeleg(ConwayDelegCert.ConwayRegDelegCert(c, to, d)) => Some((c, to, d))
        case _                                                               => None
      override def getRegDRepTxCert(
          cert: ConwayTxCert
      ): Option[(Credential, BigInt, Option[Anchor])] =
        cert match
          case ConwayTxCertGov(ConwayGovCert.ConwayRegDRep(c, d, a)) => Some((c, d, a))
          case _                                                     => None
      override def getUnRegDRepTxCert(cert: ConwayTxCert): Option[(Credential, BigInt)] = cert match
        case ConwayTxCertGov(ConwayGovCert.ConwayUnRegDRep(c, r)) => Some((c, r))
        case _                                                    => None
      override def getUpdateDRepTxCert(cert: ConwayTxCert): Option[(Credential, Option[Anchor])] =
        cert match
          case ConwayTxCertGov(ConwayGovCert.ConwayUpdateDRep(c, a)) => Some((c, a))
          case _                                                     => None
      override def getAuthCommitteeHotKeyTxCert(
          cert: ConwayTxCert
      ): Option[(Credential, Credential)] =
        cert match
          case ConwayTxCertGov(ConwayGovCert.ConwayAuthCommitteeHotKey(cold, hot)) =>
            Some((cold, hot))
          case _ => None
      override def getResignCommitteeColdTxCert(
          cert: ConwayTxCert
      ): Option[(Credential, Option[Anchor])] = cert match
        case ConwayTxCertGov(ConwayGovCert.ConwayResignCommitteeColdKey(cold, a)) => Some((cold, a))
        case _                                                                    => None
