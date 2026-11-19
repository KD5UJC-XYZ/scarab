// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.ledger.{Anchor, Certificate, Credential as FlatCredential, DecoderVersion}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayDelegCert,
  ConwayEraTxCert,
  ShelleyEraTxCert,
  ConwayGovCert,
  Delegatee,
  EraField,
  EraTxError,
  PParams,
  PoolCert
}
import xyz.kd5ujc.scarab.ledger.state.{
  ColdCommittee,
  Coin,
  Conway,
  Credential,
  DRepRole,
  EpochNo,
  HotCommittee,
  KeyHash,
  KeyRole,
  PoolMetadata,
  PoolParamsBlsKey,
  StakePool,
  StakePoolParams,
  Staking
}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

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
    * 6 are refused there, before their fields are read. A pool registration's parameters are in
    * `layout`, the layout of the build that reads them ([[fromFlat]]).
    */
  def decode(n: Node, layout: StateLayout): Either[EraTxError, ConwayTxCert] =
    Certificate.decode(n, Era.Conway).left.map(EraTxError.Decode(_)).flatMap(fromFlat(_, layout))

  /** The Conway certificate of a flat one: refused if Conway has no such certificate, or if it
    * delegates to neither a pool nor a DRep, which no decoder reads. A pool registration's
    * parameters are the state's, in `layout`: the 11.1 build's `StakePoolParams` has no voting key,
    * and the Leios build's reads none in a Conway body, which is read at version 9
    * (`decodeStakePoolParamsFlat`, cardano-ledger 9df9f98c `State/StakePool.hs:746-762`;
    * [[PoolParamsBlsKey.certified]]). Its metadata's hash is read with it.
    */
  private[era] def fromFlat(c: Certificate, layout: StateLayout): Either[EraTxError, ConwayTxCert] =
    c match
      case Certificate.Register(cred, deposit) =>
        credential[Staking](cred).map(c =>
          ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(c, deposit.map(Coin(_))))
        )
      case Certificate.Unregister(cred, refund) =>
        credential[Staking](cred).map(c =>
          ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(c, refund.map(Coin(_))))
        )
      case Certificate.Delegate(cred, pool, drep, deposit) =>
        val delegatee =
          (
            pool.traverse(poolKey),
            drep.traverse(Convert.drep(_).leftMap(EraTxError.Vocab(_)))
          ).tupled
            .flatMap {
              case (Some(p), Some(d)) => Right(Delegatee.DelegStakeVote(p, d))
              case (Some(p), None)    => Right(Delegatee.DelegStake(p))
              case (None, Some(d))    => Right(Delegatee.DelegVote(d))
              case (None, None)       => Left(EraTxError.NoDelegatee)
            }
        (credential[Staking](cred), delegatee).mapN((c, to) =>
          deposit match
            case Some(d) => ConwayTxCertDeleg(ConwayDelegCert.ConwayRegDelegCert(c, to, Coin(d)))
            case None    => ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(c, to))
        )
      case Certificate.RegisterPool(params) =>
        for
          metadata <- params.metadata
            .traverse(PoolMetadata.read(_, DecoderVersion.of(Era.Conway), layout))
            .leftMap(EraTxError.Decode(_))
          p <- Convert
            .stakePoolParams(params, PoolParamsBlsKey.certified(layout), metadata)
            .leftMap(EraTxError.Vocab(_))
        yield ConwayTxCertPool(PoolCert.RegPool(p))
      case Certificate.RetirePool(pool, epoch) =>
        (poolKey(pool), EpochNo.fromBigInt(epoch).leftMap(EraTxError.Vocab(_)))
          .mapN((p, e) => ConwayTxCertPool(PoolCert.RetirePool(p, e)))
      case Certificate.RegisterDRep(cred, deposit, anchor) =>
        credential[DRepRole](cred).map(c =>
          ConwayTxCertGov(ConwayGovCert.ConwayRegDRep(c, Coin(deposit), anchor))
        )
      case Certificate.UnregisterDRep(cred, refund) =>
        credential[DRepRole](cred).map(c =>
          ConwayTxCertGov(ConwayGovCert.ConwayUnRegDRep(c, Coin(refund)))
        )
      case Certificate.UpdateDRep(cred, anchor) =>
        credential[DRepRole](cred).map(c =>
          ConwayTxCertGov(ConwayGovCert.ConwayUpdateDRep(c, anchor))
        )
      case Certificate.CommitteeAuthorize(cold, hot) =>
        (credential[ColdCommittee](cold), credential[HotCommittee](hot)).mapN((c, h) =>
          ConwayTxCertGov(ConwayGovCert.ConwayAuthCommitteeHotKey(c, h))
        )
      case Certificate.CommitteeResign(cold, anchor) =>
        credential[ColdCommittee](cold).map(c =>
          ConwayTxCertGov(ConwayGovCert.ConwayResignCommitteeColdKey(c, anchor))
        )
      case Certificate.GenesisDelegate(_, _, _) =>
        Left(EraTxError.NotInEra(EraField.GenesisDelegation))
      case Certificate.MoveRewards(_, _) => Left(EraTxError.NotInEra(EraField.InstantaneousRewards))

  /** The node's flat certificate of a Conway one, which the flat failures carry
    * ([[xyz.kd5ujc.scarab.ledger.Purpose.Certifying]]): glue until each era's failures are its own
    * types, not a function of the reference. It is [[fromFlat]]'s inverse.
    */
  private[ledger] def toStored(cert: ConwayTxCert): Certificate =
    def delegatee(to: Delegatee) = to match
      case Delegatee.DelegStake(pool)           => (Some(pool.bytes), None)
      case Delegatee.DelegVote(drep)            => (None, Some(Convert.drepBack(drep)))
      case Delegatee.DelegStakeVote(pool, drep) => (Some(pool.bytes), Some(Convert.drepBack(drep)))
    cert match
      case ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(c, deposit)) =>
        Certificate.Register(Convert.back(c), deposit.map(_.value))
      case ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(c, refund)) =>
        Certificate.Unregister(Convert.back(c), refund.map(_.value))
      case ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(c, to)) =>
        val (pool, drep) = delegatee(to)
        Certificate.Delegate(Convert.back(c), pool, drep, None)
      case ConwayTxCertDeleg(ConwayDelegCert.ConwayRegDelegCert(c, to, deposit)) =>
        val (pool, drep) = delegatee(to)
        Certificate.Delegate(Convert.back(c), pool, drep, Some(deposit.value))
      case ConwayTxCertPool(PoolCert.RegPool(params)) =>
        Certificate.RegisterPool(Convert.poolParamsBack(params))
      case ConwayTxCertPool(PoolCert.RetirePool(pool, epoch)) =>
        Certificate.RetirePool(pool.bytes, epoch.toBigInt)
      case ConwayTxCertGov(ConwayGovCert.ConwayRegDRep(c, deposit, anchor)) =>
        Certificate.RegisterDRep(Convert.back(c), deposit.value, anchor)
      case ConwayTxCertGov(ConwayGovCert.ConwayUnRegDRep(c, refund)) =>
        Certificate.UnregisterDRep(Convert.back(c), refund.value)
      case ConwayTxCertGov(ConwayGovCert.ConwayUpdateDRep(c, anchor)) =>
        Certificate.UpdateDRep(Convert.back(c), anchor)
      case ConwayTxCertGov(ConwayGovCert.ConwayAuthCommitteeHotKey(cold, hot)) =>
        Certificate.CommitteeAuthorize(Convert.back(cold), Convert.back(hot))
      case ConwayTxCertGov(ConwayGovCert.ConwayResignCommitteeColdKey(cold, anchor)) =>
        Certificate.CommitteeResign(Convert.back(cold), anchor)

  // A credential of role `R`: the decoder read its hash as 28 bytes, so it is one.
  private def credential[R <: KeyRole](c: FlatCredential): Either[EraTxError, Credential[R]] =
    Convert.credential[R](c).leftMap(EraTxError.Vocab(_))

  // The decoder read a pool's key hash as 28 bytes (`PoolId`), so it is one.
  private def poolKey(pool: Bytes): Either[EraTxError, KeyHash[StakePool]] =
    KeyHash.fromBytes[StakePool](pool).leftMap(EraTxError.Vocab(_))

  // The credential a staking certificate is witnessed by: none for a registration without a
  // deposit, kept from Shelley through Conway (`Conway/TxCert.hs:756-761`, cardano-ledger
  // f649f975).
  private def delegWitness(c: ConwayDelegCert): Option[Credential[Staking]] = c match
    case ConwayDelegCert.ConwayRegCert(_, None)         => None
    case ConwayDelegCert.ConwayRegCert(cred, Some(_))   => Some(cred)
    case ConwayDelegCert.ConwayUnRegCert(cred, _)       => Some(cred)
    case ConwayDelegCert.ConwayDelegCert(cred, _)       => Some(cred)
    case ConwayDelegCert.ConwayRegDelegCert(cred, _, _) => Some(cred)

  private def keyHash(c: Credential[? <: KeyRole]): Option[Bytes] = c match
    case Credential.KeyHashObj(h)    => Some(h.bytes)
    case Credential.ScriptHashObj(_) => None

  private def scriptHash(c: Credential[? <: KeyRole]): Option[Bytes] = c match
    case Credential.ScriptHashObj(h) => Some(h.bytes)
    case Credential.KeyHashObj(_)    => None

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
      override def mkRetirePoolTxCert(pool: KeyHash[StakePool], epoch: EpochNo): ConwayTxCert =
        ConwayTxCertPool(PoolCert.RetirePool(pool, epoch))
      override def getRetirePoolTxCert(cert: ConwayTxCert): Option[(KeyHash[StakePool], EpochNo)] =
        poolCert(cert).flatMap {
          case PoolCert.RetirePool(p, epoch) => Some((p, epoch))
          case PoolCert.RegPool(_)           => None
        }

      // `lookupRegStakeTxCert` and `lookupUnRegStakeTxCert`, `Conway/TxCert.hs:135-144`, cardano-ledger f649f975
      override def lookupRegStakeTxCert(cert: ConwayTxCert): Option[Credential[Staking]] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayRegCert(c, _)         => Some(c)
          case ConwayDelegCert.ConwayRegDelegCert(c, _, _) => Some(c)
          case ConwayDelegCert.ConwayUnRegCert(_, _) | ConwayDelegCert.ConwayDelegCert(_, _) => None
        }
      override def lookupUnRegStakeTxCert(cert: ConwayTxCert): Option[Credential[Staking]] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayUnRegCert(c, _) => Some(c)
          case ConwayDelegCert.ConwayRegCert(_, _) | ConwayDelegCert.ConwayDelegCert(_, _) |
              ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }

      // `getTotalRefundsTxCerts` and `getTotalDepositsTxCerts`, `Conway/TxCert.hs:145-147`, cardano-ledger f649f975
      override def getTotalDepositsTxCerts(
          pp: PParams[Conway],
          isPoolRegistered: KeyHash[StakePool] => Boolean,
          certs: Iterable[ConwayTxCert]
      ): Coin = conwayTotalDepositsTxCerts(pp, isPoolRegistered, certs)
      override def getTotalRefundsTxCerts(
          pp: PParams[Conway],
          lookupStakingDeposit: Credential[Staking] => Option[Coin],
          certs: Iterable[ConwayTxCert]
      ): Coin = conwayTotalRefundsTxCerts(pp, lookupStakingDeposit, certs)

      override def mkRegTxCert(credential: Credential[Staking]): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(credential, None))
      override def getRegTxCert(cert: ConwayTxCert): Option[Credential[Staking]] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayRegCert(c, None) => Some(c)
          case ConwayDelegCert.ConwayRegCert(_, Some(_)) | ConwayDelegCert.ConwayUnRegCert(_, _) |
              ConwayDelegCert.ConwayDelegCert(_, _) | ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }
      override def mkUnRegTxCert(credential: Credential[Staking]): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(credential, None))
      override def getUnRegTxCert(cert: ConwayTxCert): Option[Credential[Staking]] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayUnRegCert(c, None) => Some(c)
          case ConwayDelegCert.ConwayUnRegCert(_, Some(_)) | ConwayDelegCert.ConwayRegCert(_, _) |
              ConwayDelegCert.ConwayDelegCert(_, _) | ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }
      override def mkDelegStakeTxCert(
          credential: Credential[Staking],
          pool: KeyHash[StakePool]
      ): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(credential, Delegatee.DelegStake(pool)))
      override def getDelegStakeTxCert(
          cert: ConwayTxCert
      ): Option[(Credential[Staking], KeyHash[StakePool])] =
        getDelegTxCert(cert).flatMap {
          case (c, Delegatee.DelegStake(p))                                 => Some((c, p))
          case (_, Delegatee.DelegVote(_) | Delegatee.DelegStakeVote(_, _)) => None
        }

      override def mkRegDepositTxCert(
          credential: Credential[Staking],
          deposit: Coin
      ): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(credential, Some(deposit)))
      override def getRegDepositTxCert(cert: ConwayTxCert): Option[(Credential[Staking], Coin)] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayRegCert(c, Some(d)) => Some((c, d))
          case ConwayDelegCert.ConwayRegCert(_, None) | ConwayDelegCert.ConwayUnRegCert(_, _) |
              ConwayDelegCert.ConwayDelegCert(_, _) | ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }
      override def mkUnRegDepositTxCert(
          credential: Credential[Staking],
          refund: Coin
      ): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(credential, Some(refund)))
      override def getUnRegDepositTxCert(cert: ConwayTxCert): Option[(Credential[Staking], Coin)] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayUnRegCert(c, Some(r)) => Some((c, r))
          case ConwayDelegCert.ConwayUnRegCert(_, None) | ConwayDelegCert.ConwayRegCert(_, _) |
              ConwayDelegCert.ConwayDelegCert(_, _) | ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }
      override def mkDelegTxCert(
          credential: Credential[Staking],
          delegatee: Delegatee
      ): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(credential, delegatee))
      override def getDelegTxCert(cert: ConwayTxCert): Option[(Credential[Staking], Delegatee)] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayDelegCert(c, to) => Some((c, to))
          case ConwayDelegCert.ConwayRegCert(_, _) | ConwayDelegCert.ConwayUnRegCert(_, _) |
              ConwayDelegCert.ConwayRegDelegCert(_, _, _) =>
            None
        }
      override def mkRegDepositDelegTxCert(
          credential: Credential[Staking],
          delegatee: Delegatee,
          deposit: Coin
      ): ConwayTxCert =
        ConwayTxCertDeleg(ConwayDelegCert.ConwayRegDelegCert(credential, delegatee, deposit))
      override def getRegDepositDelegTxCert(
          cert: ConwayTxCert
      ): Option[(Credential[Staking], Delegatee, Coin)] =
        deleg(cert).flatMap {
          case ConwayDelegCert.ConwayRegDelegCert(c, to, d) => Some((c, to, d))
          case ConwayDelegCert.ConwayRegCert(_, _) | ConwayDelegCert.ConwayUnRegCert(_, _) |
              ConwayDelegCert.ConwayDelegCert(_, _) =>
            None
        }

      override def mkRegDRepTxCert(
          credential: Credential[DRepRole],
          deposit: Coin,
          anchor: Option[Anchor]
      ): ConwayTxCert = ConwayTxCertGov(ConwayGovCert.ConwayRegDRep(credential, deposit, anchor))
      override def getRegDRepTxCert(
          cert: ConwayTxCert
      ): Option[(Credential[DRepRole], Coin, Option[Anchor])] =
        gov(cert).flatMap {
          case ConwayGovCert.ConwayRegDRep(c, d, a) => Some((c, d, a))
          case ConwayGovCert.ConwayUnRegDRep(_, _) | ConwayGovCert.ConwayUpdateDRep(_, _) |
              ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) |
              ConwayGovCert.ConwayResignCommitteeColdKey(_, _) =>
            None
        }
      override def mkUnRegDRepTxCert(credential: Credential[DRepRole], refund: Coin): ConwayTxCert =
        ConwayTxCertGov(ConwayGovCert.ConwayUnRegDRep(credential, refund))
      override def getUnRegDRepTxCert(cert: ConwayTxCert): Option[(Credential[DRepRole], Coin)] =
        gov(cert).flatMap {
          case ConwayGovCert.ConwayUnRegDRep(c, r) => Some((c, r))
          case ConwayGovCert.ConwayRegDRep(_, _, _) | ConwayGovCert.ConwayUpdateDRep(_, _) |
              ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) |
              ConwayGovCert.ConwayResignCommitteeColdKey(_, _) =>
            None
        }
      override def mkUpdateDRepTxCert(
          credential: Credential[DRepRole],
          anchor: Option[Anchor]
      ): ConwayTxCert =
        ConwayTxCertGov(ConwayGovCert.ConwayUpdateDRep(credential, anchor))
      override def getUpdateDRepTxCert(
          cert: ConwayTxCert
      ): Option[(Credential[DRepRole], Option[Anchor])] =
        gov(cert).flatMap {
          case ConwayGovCert.ConwayUpdateDRep(c, a) => Some((c, a))
          case ConwayGovCert.ConwayRegDRep(_, _, _) | ConwayGovCert.ConwayUnRegDRep(_, _) |
              ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) |
              ConwayGovCert.ConwayResignCommitteeColdKey(_, _) =>
            None
        }
      override def mkAuthCommitteeHotKeyTxCert(
          cold: Credential[ColdCommittee],
          hot: Credential[HotCommittee]
      ): ConwayTxCert =
        ConwayTxCertGov(ConwayGovCert.ConwayAuthCommitteeHotKey(cold, hot))
      override def getAuthCommitteeHotKeyTxCert(
          cert: ConwayTxCert
      ): Option[(Credential[ColdCommittee], Credential[HotCommittee])] =
        gov(cert).flatMap {
          case ConwayGovCert.ConwayAuthCommitteeHotKey(cold, hot) => Some((cold, hot))
          case ConwayGovCert.ConwayRegDRep(_, _, _) | ConwayGovCert.ConwayUnRegDRep(_, _) |
              ConwayGovCert.ConwayUpdateDRep(_, _) |
              ConwayGovCert.ConwayResignCommitteeColdKey(_, _) =>
            None
        }
      override def mkResignCommitteeColdTxCert(
          cold: Credential[ColdCommittee],
          anchor: Option[Anchor]
      ): ConwayTxCert = ConwayTxCertGov(ConwayGovCert.ConwayResignCommitteeColdKey(cold, anchor))
      override def getResignCommitteeColdTxCert(
          cert: ConwayTxCert
      ): Option[(Credential[ColdCommittee], Option[Anchor])] =
        gov(cert).flatMap {
          case ConwayGovCert.ConwayResignCommitteeColdKey(cold, a) => Some((cold, a))
          case ConwayGovCert.ConwayRegDRep(_, _, _) | ConwayGovCert.ConwayUnRegDRep(_, _) |
              ConwayGovCert.ConwayUpdateDRep(_, _) |
              ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) =>
            None
        }
