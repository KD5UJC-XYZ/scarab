// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Certificate, DecoderVersion, Pot}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.era.{
  EraField,
  EraTxError,
  GenesisDelegCert,
  IsShelleyTxCert,
  MIRCert,
  MIRPot,
  MIRTarget,
  PParams,
  PoolCert,
  ShelleyDelegCert,
  ShelleyEraTxCertToBabbage,
  ShelleyTxCertRep
}
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Coin,
  Credential,
  DeltaCoin,
  EpochNo,
  Genesis,
  GenesisDelegate,
  KeyHash,
  KeyRole,
  PoolMetadata,
  PoolParamsBlsKey,
  StakePool,
  StakePoolParams,
  Staking,
  VRFVerKeyHash
}
import xyz.kd5ujc.scarab.protocol.{Era, StateLayout}

/** An Alonzo certificate: `ShelleyTxCert AlonzoEra` (`type TxCert AlonzoEra = ShelleyTxCert
  * AlonzoEra`, cardano-ledger f649f975 `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxCert.hs:12`),
  * the representation the reference shares from Shelley ([[ShelleyTxCertRep]]), a type of its own.
  * Made only by Alonzo's body decoder ([[AlonzoTxBody.decode]], through [[fromFlat]]) and the
  * builders of its classes.
  */
opaque type AlonzoTxCert = ShelleyTxCertRep

object AlonzoTxCert:
  given CanEqual[AlonzoTxCert, AlonzoTxCert] = CanEqual.derived

  private val version: DecoderVersion = DecoderVersion.of(Era.Alonzo)

  extension (c: AlonzoTxCert)
    /** The shared representation, read only. */
    def rep: ShelleyTxCertRep = c

  /** `TxCert AlonzoEra ~ ShelleyTxCert AlonzoEra` (`Alonzo/TxCert.hs:12`, cardano-ledger f649f975),
    * which DELPL asks for (`Shelley/Rules/Delpl.hs:184`): the representation, read only, of the
    * equality the compiler proves here alone, where the opaque type is its representation.
    */
  val isShelleyTxCert: IsShelleyTxCert[Alonzo] = IsShelleyTxCert.of[Alonzo]

  /** The Alonzo certificate of a flat one that the flat certificate reader read at Alonzo (`DecCBOR
    * (ShelleyTxCert era)`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:469-488`,
    * cardano-ledger f649f975). The flat certificate holds every era's, so one Conway brought is
    * refused, which that reader, reading tags 0 to 6 alone, never gives: the case keeps the
    * conversion total until the flat type goes (stage 6a). A pool registration's parameters are the
    * state's, in `layout`, as Conway's are ([[xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxCert]]);
    * its metadata's hash is read with it. The key hashes and the epoch were read at their widths,
    * so the vocabulary refuses none of them.
    */
  private[alonzo] def fromFlat(
      c: Certificate,
      layout: StateLayout
  ): Either[EraTxError, AlonzoTxCert] =
    import ShelleyTxCertRep.*
    c match
      case Certificate.Register(cred, None) =>
        credential[Staking](cred).map(c =>
          ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyRegCert(c))
        )
      case Certificate.Unregister(cred, None) =>
        credential[Staking](cred).map(c =>
          ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyUnRegCert(c))
        )
      case Certificate.Delegate(cred, Some(pool), None, None) =>
        (credential[Staking](cred), keyHash[StakePool](pool)).mapN((c, p) =>
          ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyDelegCert(c, p))
        )
      case Certificate.RegisterPool(params) =>
        for
          metadata <- params.metadata
            .traverse(PoolMetadata.read(_, version, layout))
            .leftMap(EraTxError.Decode(_))
          p <- Convert
            .stakePoolParams(params, PoolParamsBlsKey.certified(layout), metadata)
            .leftMap(EraTxError.Vocab(_))
        yield ShelleyTxCertPool(PoolCert.RegPool(p))
      case Certificate.RetirePool(pool, epoch) =>
        (keyHash[StakePool](pool), EpochNo.fromBigInt(epoch).leftMap(EraTxError.Vocab(_)))
          .mapN((p, e) => ShelleyTxCertPool(PoolCert.RetirePool(p, e)))
      case Certificate.GenesisDelegate(genesis, delegate, vrf) =>
        (
          keyHash[Genesis](genesis),
          keyHash[GenesisDelegate](delegate),
          VRFVerKeyHash.fromBytes(vrf).leftMap(EraTxError.Vocab(_))
        ).mapN((g, d, v) => ShelleyTxCertGenesisDeleg(GenesisDelegCert(g, d, v)))
      case Certificate.MoveRewards(pot, transfer) =>
        val mirPot = pot match
          case Pot.Reserves => MIRPot.ReservesMIR
          case Pot.Treasury => MIRPot.TreasuryMIR
        transfer
          .bitraverse(
            coin => Right(MIRTarget.SendToOppositePotMIR(Coin(coin))),
            rewards =>
              rewards.toVector
                .traverse((cred, delta) => credential[Staking](cred).map(_ -> DeltaCoin(delta)))
                .map(rs => MIRTarget.StakeAddressesMIR(rs.toMap))
          )
          .map(target => ShelleyTxCertMir(MIRCert(mirPot, target.merge)))
      case Certificate.Register(_, Some(_)) | Certificate.Unregister(_, Some(_)) |
          Certificate.Delegate(_, _, _, _) | Certificate.RegisterDRep(_, _, _) |
          Certificate.UnregisterDRep(_, _) | Certificate.UpdateDRep(_, _) |
          Certificate.CommitteeAuthorize(_, _) | Certificate.CommitteeResign(_, _) =>
        Left(EraTxError.NotInEra(EraField.ConwayCertificate))

  // A credential of role `R`: the decoder read its hash as 28 bytes, so it is one.
  private def credential[R <: KeyRole](
      c: xyz.kd5ujc.scarab.ledger.Credential
  ): Either[EraTxError, Credential[R]] =
    Convert.credential[R](c).leftMap(EraTxError.Vocab(_))

  private def keyHash[R <: KeyRole](b: Bytes): Either[EraTxError, KeyHash[R]] =
    KeyHash.fromBytes[R](b).leftMap(EraTxError.Vocab(_))

  // The certificate of each kind, so that each reader matches every case of its kind.
  private def deleg(cert: AlonzoTxCert): Option[ShelleyDelegCert] = cert match
    case ShelleyTxCertRep.ShelleyTxCertDelegCert(c) => Some(c)
    case ShelleyTxCertRep.ShelleyTxCertPool(_) | ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(_) |
        ShelleyTxCertRep.ShelleyTxCertMir(_) =>
      None

  private def poolCert(cert: AlonzoTxCert): Option[PoolCert] = cert match
    case ShelleyTxCertRep.ShelleyTxCertPool(c) => Some(c)
    case ShelleyTxCertRep.ShelleyTxCertDelegCert(_) |
        ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(_) | ShelleyTxCertRep.ShelleyTxCertMir(_) =>
      None

  /** `EraTxCert AlonzoEra` and `ShelleyEraTxCert AlonzoEra` (`Alonzo/TxCert.hs:11-65`,
    * cardano-ledger f649f975): Shelley's witnesses, deposits and refunds over Shelley's
    * certificates, and the genesis-delegation and instantaneous-reward ones of the eras up to
    * Babbage.
    */
  val instance: ShelleyEraTxCertToBabbage[Alonzo] = new ShelleyEraTxCertToBabbage[Alonzo]:
    override def getVKeyWitnessTxCert(cert: AlonzoTxCert): Option[Bytes] =
      ShelleyTxCertRep.getVKeyWitnessShelleyTxCert(cert)
    override def getScriptWitnessTxCert(cert: AlonzoTxCert): Option[Bytes] =
      ShelleyTxCertRep.getScriptWitnessShelleyTxCert(cert)

    override def mkRegPoolTxCert(params: StakePoolParams): AlonzoTxCert =
      ShelleyTxCertRep.ShelleyTxCertPool(PoolCert.RegPool(params))
    override def getRegPoolTxCert(cert: AlonzoTxCert): Option[StakePoolParams] =
      poolCert(cert).flatMap {
        case PoolCert.RegPool(params)  => Some(params)
        case PoolCert.RetirePool(_, _) => None
      }
    override def mkRetirePoolTxCert(pool: KeyHash[StakePool], epoch: EpochNo): AlonzoTxCert =
      ShelleyTxCertRep.ShelleyTxCertPool(PoolCert.RetirePool(pool, epoch))
    override def getRetirePoolTxCert(cert: AlonzoTxCert): Option[(KeyHash[StakePool], EpochNo)] =
      poolCert(cert).flatMap {
        case PoolCert.RetirePool(p, epoch) => Some((p, epoch))
        case PoolCert.RegPool(_)           => None
      }

    // `lookupRegStakeTxCert` and `lookupUnRegStakeTxCert`, `Alonzo/TxCert.hs:30-35`,
    // cardano-ledger f649f975: the registration and unregistration without a deposit
    override def lookupRegStakeTxCert(cert: AlonzoTxCert): Option[Credential[Staking]] =
      getRegTxCert(cert)
    override def lookupUnRegStakeTxCert(cert: AlonzoTxCert): Option[Credential[Staking]] =
      getUnRegTxCert(cert)

    // `getTotalDepositsTxCerts` and `getTotalRefundsTxCerts`, `Alonzo/TxCert.hs:37-39`,
    // cardano-ledger f649f975
    override def getTotalDepositsTxCerts(
        pp: PParams[Alonzo],
        isPoolRegistered: KeyHash[StakePool] => Boolean,
        certs: Iterable[AlonzoTxCert]
    ): Coin = shelleyTotalDepositsTxCerts(pp, isPoolRegistered, certs)
    override def getTotalRefundsTxCerts(
        pp: PParams[Alonzo],
        lookupStakingDeposit: Credential[Staking] => Option[Coin],
        certs: Iterable[AlonzoTxCert]
    ): Coin = shelleyTotalRefundsTxCerts(pp, lookupStakingDeposit, certs)

    override def mkRegTxCert(credential: Credential[Staking]): AlonzoTxCert =
      ShelleyTxCertRep.ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyRegCert(credential))
    override def getRegTxCert(cert: AlonzoTxCert): Option[Credential[Staking]] =
      deleg(cert).flatMap {
        case ShelleyDelegCert.ShelleyRegCert(c) => Some(c)
        case ShelleyDelegCert.ShelleyUnRegCert(_) | ShelleyDelegCert.ShelleyDelegCert(_, _) => None
      }
    override def mkUnRegTxCert(credential: Credential[Staking]): AlonzoTxCert =
      ShelleyTxCertRep.ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyUnRegCert(credential))
    override def getUnRegTxCert(cert: AlonzoTxCert): Option[Credential[Staking]] =
      deleg(cert).flatMap {
        case ShelleyDelegCert.ShelleyUnRegCert(c)                                         => Some(c)
        case ShelleyDelegCert.ShelleyRegCert(_) | ShelleyDelegCert.ShelleyDelegCert(_, _) => None
      }
    override def mkDelegStakeTxCert(
        credential: Credential[Staking],
        pool: KeyHash[StakePool]
    ): AlonzoTxCert =
      ShelleyTxCertRep.ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyDelegCert(credential, pool))
    override def getDelegStakeTxCert(
        cert: AlonzoTxCert
    ): Option[(Credential[Staking], KeyHash[StakePool])] =
      deleg(cert).flatMap {
        case ShelleyDelegCert.ShelleyDelegCert(c, p) => Some((c, p))
        case ShelleyDelegCert.ShelleyRegCert(_) | ShelleyDelegCert.ShelleyUnRegCert(_) => None
      }

    // `mkGenesisDelegTxCert` to `getMirTxCert`, `Alonzo/TxCert.hs:57-65`, cardano-ledger f649f975
    override def mkGenesisDelegTxCert(cert: GenesisDelegCert): AlonzoTxCert =
      ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(cert)
    override def getGenesisDelegTxCert(cert: AlonzoTxCert): Option[GenesisDelegCert] =
      cert match
        case ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(c) => Some(c)
        case ShelleyTxCertRep.ShelleyTxCertDelegCert(_) | ShelleyTxCertRep.ShelleyTxCertPool(_) |
            ShelleyTxCertRep.ShelleyTxCertMir(_) =>
          None
    override def mkMirTxCert(cert: MIRCert): AlonzoTxCert = ShelleyTxCertRep.ShelleyTxCertMir(cert)
    override def getMirTxCert(cert: AlonzoTxCert): Option[MIRCert] = cert match
      case ShelleyTxCertRep.ShelleyTxCertMir(c) => Some(c)
      case ShelleyTxCertRep.ShelleyTxCertDelegCert(_) | ShelleyTxCertRep.ShelleyTxCertPool(_) |
          ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(_) =>
        None
