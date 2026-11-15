// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{Anchor, Certificate, Credential}
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayDelegCert,
  ConwayEraTxCert,
  ShelleyEraTxCert,
  ConwayGovCert,
  StakePoolParams,
  Delegatee,
  EraField,
  EraTxError,
  PoolCert
}
import xyz.kd5ujc.scarab.ledger.state.{Coin, Conway}
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
      Right(ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(cred, deposit.map(Coin(_)))))
    case Certificate.Unregister(cred, refund) =>
      Right(ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(cred, refund.map(Coin(_)))))
    case Certificate.Delegate(cred, pool, drep, deposit) =>
      val delegatee = (pool, drep) match
        case (Some(p), Some(d)) => Right(Delegatee.DelegStakeVote(p, d))
        case (Some(p), None)    => Right(Delegatee.DelegStake(p))
        case (None, Some(d))    => Right(Delegatee.DelegVote(d))
        case (None, None)       => Left(EraTxError.NoDelegatee)
      delegatee.map(to =>
        deposit match
          case Some(d) => ConwayTxCertDeleg(ConwayDelegCert.ConwayRegDelegCert(cred, to, Coin(d)))
          case None    => ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(cred, to))
      )
    case Certificate.RegisterPool(params) =>
      Right(ConwayTxCertPool(PoolCert.RegPool(StakePoolParams(params))))
    case Certificate.RetirePool(pool, epoch) =>
      Right(ConwayTxCertPool(PoolCert.RetirePool(pool, epoch)))
    case Certificate.RegisterDRep(cred, deposit, anchor) =>
      Right(ConwayTxCertGov(ConwayGovCert.ConwayRegDRep(cred, Coin(deposit), anchor)))
    case Certificate.UnregisterDRep(cred, refund) =>
      Right(ConwayTxCertGov(ConwayGovCert.ConwayUnRegDRep(cred, Coin(refund))))
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
  // deposit, kept from Shelley through Conway (`Conway/TxCert.hs:756-761`, cardano-ledger
  // f649f975).
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

  // The certificate of each kind, so that each reader matches every case of its kind.
  private def deleg(cert: ConwayTxCert): Option[ConwayDelegCert] = cert match
    case ConwayTxCertDeleg(c)                     => Some(c)
    case ConwayTxCertPool(_) | ConwayTxCertGov(_) => None

  private def poolCert(cert: ConwayTxCert): Option[PoolCert] = cert match
    case ConwayTxCertPool(c)                       => Some(c)
    case ConwayTxCertDeleg(_) | ConwayTxCertGov(_) => None

  private def gov(cert: ConwayTxCert): Option[ConwayGovCert] = cert match
    case ConwayTxCertGov(c)                         => Some(c)
    case ConwayTxCertDeleg(_) | ConwayTxCertPool(_) => None

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

      override def mkRegPoolTxCert(params: StakePoolParams): ConwayTxCert =
        ConwayTxCertPool(PoolCert.RegPool(params))
      override def getRegPoolTxCert(cert: ConwayTxCert): Option[StakePoolParams] =
        poolCert(cert).flatMap {
          case PoolCert.RegPool(params)  => Some(params)
          case PoolCert.RetirePool(_, _) => None
        }
      override def mkRetirePoolTxCert(pool: Bytes, epoch: BigInt): ConwayTxCert =
        ConwayTxCertPool(PoolCert.RetirePool(pool, epoch))
      override def getRetirePoolTxCert(cert: ConwayTxCert): Option[(Bytes, BigInt)] =
        poolCert(cert).flatMap {
          case PoolCert.RetirePool(p, epoch) => Some((p, epoch))
          case PoolCert.RegPool(_)           => None
        }

      // `lookupRegStakeTxCert`, `Conway/TxCert.hs:221-226`, cardano-ledger f649f975
      override def lookupRegStakeTxCert(cert: ConwayTxCert): Option[Credential] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayRegCert(c, _)         => Some(c)
          case ConwayDelegCert.ConwayRegDelegCert(c, _, _) => Some(c)
          case ConwayDelegCert.ConwayUnRegCert(_, _) | ConwayDelegCert.ConwayDelegCert(_, _) => None
        }
      override def lookupUnRegStakeTxCert(cert: ConwayTxCert): Option[Credential] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayUnRegCert(c, _) => Some(c)
          case ConwayDelegCert.ConwayRegCert(_, _) | ConwayDelegCert.ConwayDelegCert(_, _) |
              ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }

      override def mkRegTxCert(credential: Credential): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(credential, None))
      override def getRegTxCert(cert: ConwayTxCert): Option[Credential] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayRegCert(c, None) => Some(c)
          case ConwayDelegCert.ConwayRegCert(_, Some(_)) | ConwayDelegCert.ConwayUnRegCert(_, _) |
              ConwayDelegCert.ConwayDelegCert(_, _) | ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }
      override def mkUnRegTxCert(credential: Credential): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(credential, None))
      override def getUnRegTxCert(cert: ConwayTxCert): Option[Credential] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayUnRegCert(c, None) => Some(c)
          case ConwayDelegCert.ConwayUnRegCert(_, Some(_)) | ConwayDelegCert.ConwayRegCert(_, _) |
              ConwayDelegCert.ConwayDelegCert(_, _) | ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }
      override def mkDelegStakeTxCert(credential: Credential, pool: Bytes): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(credential, Delegatee.DelegStake(pool)))
      override def getDelegStakeTxCert(cert: ConwayTxCert): Option[(Credential, Bytes)] =
        getDelegTxCert(cert).flatMap {
          case (c, Delegatee.DelegStake(p))                                 => Some((c, p))
          case (_, Delegatee.DelegVote(_) | Delegatee.DelegStakeVote(_, _)) => None
        }

      override def mkRegDepositTxCert(credential: Credential, deposit: Coin): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(credential, Some(deposit)))
      override def getRegDepositTxCert(cert: ConwayTxCert): Option[(Credential, Coin)] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayRegCert(c, Some(d)) => Some((c, d))
          case ConwayDelegCert.ConwayRegCert(_, None) | ConwayDelegCert.ConwayUnRegCert(_, _) |
              ConwayDelegCert.ConwayDelegCert(_, _) | ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }
      override def mkUnRegDepositTxCert(credential: Credential, refund: Coin): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(credential, Some(refund)))
      override def getUnRegDepositTxCert(cert: ConwayTxCert): Option[(Credential, Coin)] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayUnRegCert(c, Some(r)) => Some((c, r))
          case ConwayDelegCert.ConwayUnRegCert(_, None) | ConwayDelegCert.ConwayRegCert(_, _) |
              ConwayDelegCert.ConwayDelegCert(_, _) | ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }
      override def mkDelegTxCert(credential: Credential, delegatee: Delegatee): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(credential, delegatee))
      override def getDelegTxCert(cert: ConwayTxCert): Option[(Credential, Delegatee)] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayDelegCert(c, to) => Some((c, to))
          case ConwayDelegCert.ConwayRegCert(_, _) | ConwayDelegCert.ConwayUnRegCert(_, _) |
              ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }
      override def mkRegDepositDelegTxCert(
          credential: Credential,
          delegatee: Delegatee,
          deposit: Coin
      ): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayRegDelegCert(credential, delegatee, deposit))
      override def getRegDepositDelegTxCert(
          cert: ConwayTxCert
      ): Option[(Credential, Delegatee, Coin)] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayRegDelegCert(c, to, d) => Some((c, to, d))
          case ConwayDelegCert.ConwayRegCert(_, _) | ConwayDelegCert.ConwayUnRegCert(_, _) |
              ConwayDelegCert.ConwayDelegCert(_, _) =>
            None
        }

      override def mkRegDRepTxCert(
          credential: Credential,
          deposit: Coin,
          anchor: Option[Anchor]
      ): ConwayTxCert = ConwayTxCertGov(ConwayGovCert.ConwayRegDRep(credential, deposit, anchor))
      override def getRegDRepTxCert(
          cert: ConwayTxCert
      ): Option[(Credential, Coin, Option[Anchor])] =
        gov(cert).flatMap {
          case ConwayGovCert.ConwayRegDRep(c, d, a) => Some((c, d, a))
          case ConwayGovCert.ConwayUnRegDRep(_, _) | ConwayGovCert.ConwayUpdateDRep(_, _) |
              ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) |
              ConwayGovCert.ConwayResignCommitteeColdKey(_, _) =>
            None
        }
      override def mkUnRegDRepTxCert(credential: Credential, refund: Coin): ConwayTxCert =
        ConwayTxCertGov(ConwayGovCert.ConwayUnRegDRep(credential, refund))
      override def getUnRegDRepTxCert(cert: ConwayTxCert): Option[(Credential, Coin)] =
        gov(cert).flatMap {
          case ConwayGovCert.ConwayUnRegDRep(c, r) => Some((c, r))
          case ConwayGovCert.ConwayRegDRep(_, _, _) | ConwayGovCert.ConwayUpdateDRep(_, _) |
              ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) |
              ConwayGovCert.ConwayResignCommitteeColdKey(_, _) =>
            None
        }
      override def mkUpdateDRepTxCert(
          credential: Credential,
          anchor: Option[Anchor]
      ): ConwayTxCert =
        ConwayTxCertGov(ConwayGovCert.ConwayUpdateDRep(credential, anchor))
      override def getUpdateDRepTxCert(cert: ConwayTxCert): Option[(Credential, Option[Anchor])] =
        gov(cert).flatMap {
          case ConwayGovCert.ConwayUpdateDRep(c, a) => Some((c, a))
          case ConwayGovCert.ConwayRegDRep(_, _, _) | ConwayGovCert.ConwayUnRegDRep(_, _) |
              ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) |
              ConwayGovCert.ConwayResignCommitteeColdKey(_, _) =>
            None
        }
      override def mkAuthCommitteeHotKeyTxCert(cold: Credential, hot: Credential): ConwayTxCert =
        ConwayTxCertGov(ConwayGovCert.ConwayAuthCommitteeHotKey(cold, hot))
      override def getAuthCommitteeHotKeyTxCert(
          cert: ConwayTxCert
      ): Option[(Credential, Credential)] =
        gov(cert).flatMap {
          case ConwayGovCert.ConwayAuthCommitteeHotKey(cold, hot) => Some((cold, hot))
          case ConwayGovCert.ConwayRegDRep(_, _, _) | ConwayGovCert.ConwayUnRegDRep(_, _) |
              ConwayGovCert.ConwayUpdateDRep(_, _) |
              ConwayGovCert.ConwayResignCommitteeColdKey(_, _) =>
            None
        }
      override def mkResignCommitteeColdTxCert(
          cold: Credential,
          anchor: Option[Anchor]
      ): ConwayTxCert = ConwayTxCertGov(ConwayGovCert.ConwayResignCommitteeColdKey(cold, anchor))
      override def getResignCommitteeColdTxCert(
          cert: ConwayTxCert
      ): Option[(Credential, Option[Anchor])] =
        gov(cert).flatMap {
          case ConwayGovCert.ConwayResignCommitteeColdKey(cold, a) => Some((cold, a))
          case ConwayGovCert.ConwayRegDRep(_, _, _) | ConwayGovCert.ConwayUnRegDRep(_, _) |
              ConwayGovCert.ConwayUpdateDRep(_, _) |
              ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) =>
            None
        }
