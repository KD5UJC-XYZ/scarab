// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Certificate, DecoderVersion, Pot}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.era.{
  EraField,
  EraTxError,
  GenesisDelegCert,
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
  Babbage,
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

/** A Babbage certificate: `ShelleyTxCert BabbageEra` (`type TxCert BabbageEra = ShelleyTxCert
  * BabbageEra`, cardano-ledger f649f975
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxCert.hs:12`), the representation the reference
  * shares from Shelley ([[ShelleyTxCertRep]]), a type of its own. Made only by Babbage's body
  * decoder ([[BabbageTxBody.decode]], through [[fromFlat]]) and the builders of its classes.
  */
opaque type BabbageTxCert = ShelleyTxCertRep

object BabbageTxCert:
  given CanEqual[BabbageTxCert, BabbageTxCert] = CanEqual.derived

  private val version: DecoderVersion = DecoderVersion.of(Era.Babbage)

  extension (c: BabbageTxCert)
    /** The shared representation, read only. */
    def rep: ShelleyTxCertRep = c

  /** The Babbage certificate of a flat one that the flat certificate reader read at Babbage
    * (`DecCBOR (ShelleyTxCert era)`,
    * `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:469-488`, cardano-ledger f649f975).
    * The flat certificate holds every era's, so one Conway brought is refused, which that reader,
    * reading tags 0 to 6 alone, never gives: the case keeps the conversion total until the flat
    * type goes (stage 6a). A pool registration's parameters are the state's, in `layout`, as
    * Conway's are ([[xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxCert]]); its metadata's hash is
    * read with it. The key hashes and the epoch were read at their widths, so the vocabulary
    * refuses none of them.
    */
  private[babbage] def fromFlat(
      c: Certificate,
      layout: StateLayout
  ): Either[EraTxError, BabbageTxCert] =
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
  private def deleg(cert: BabbageTxCert): Option[ShelleyDelegCert] = cert match
    case ShelleyTxCertRep.ShelleyTxCertDelegCert(c) => Some(c)
    case ShelleyTxCertRep.ShelleyTxCertPool(_) | ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(_) |
        ShelleyTxCertRep.ShelleyTxCertMir(_) =>
      None

  private def poolCert(cert: BabbageTxCert): Option[PoolCert] = cert match
    case ShelleyTxCertRep.ShelleyTxCertPool(c) => Some(c)
    case ShelleyTxCertRep.ShelleyTxCertDelegCert(_) |
        ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(_) | ShelleyTxCertRep.ShelleyTxCertMir(_) =>
      None

  /** `EraTxCert BabbageEra` and `ShelleyEraTxCert BabbageEra` (`Babbage/TxCert.hs:11-65`,
    * cardano-ledger f649f975): Shelley's witnesses, deposits and refunds over Shelley's
    * certificates, and the genesis-delegation and instantaneous-reward ones of the eras up to
    * Babbage.
    */
  val instance: ShelleyEraTxCertToBabbage[Babbage] = new ShelleyEraTxCertToBabbage[Babbage]:
    override def getVKeyWitnessTxCert(cert: BabbageTxCert): Option[Bytes] =
      ShelleyTxCertRep.getVKeyWitnessShelleyTxCert(cert)
    override def getScriptWitnessTxCert(cert: BabbageTxCert): Option[Bytes] =
      ShelleyTxCertRep.getScriptWitnessShelleyTxCert(cert)

    override def mkRegPoolTxCert(params: StakePoolParams): BabbageTxCert =
      ShelleyTxCertRep.ShelleyTxCertPool(PoolCert.RegPool(params))
    override def getRegPoolTxCert(cert: BabbageTxCert): Option[StakePoolParams] =
      poolCert(cert).flatMap {
        case PoolCert.RegPool(params)  => Some(params)
        case PoolCert.RetirePool(_, _) => None
      }
    override def mkRetirePoolTxCert(pool: KeyHash[StakePool], epoch: EpochNo): BabbageTxCert =
      ShelleyTxCertRep.ShelleyTxCertPool(PoolCert.RetirePool(pool, epoch))
    override def getRetirePoolTxCert(cert: BabbageTxCert): Option[(KeyHash[StakePool], EpochNo)] =
      poolCert(cert).flatMap {
        case PoolCert.RetirePool(p, epoch) => Some((p, epoch))
        case PoolCert.RegPool(_)           => None
      }

    // `lookupRegStakeTxCert` and `lookupUnRegStakeTxCert`, `Babbage/TxCert.hs:30-35`,
    // cardano-ledger f649f975: the registration and unregistration without a deposit
    override def lookupRegStakeTxCert(cert: BabbageTxCert): Option[Credential[Staking]] =
      getRegTxCert(cert)
    override def lookupUnRegStakeTxCert(cert: BabbageTxCert): Option[Credential[Staking]] =
      getUnRegTxCert(cert)

    // `getTotalDepositsTxCerts` and `getTotalRefundsTxCerts`, `Babbage/TxCert.hs:37-39`,
    // cardano-ledger f649f975
    override def getTotalDepositsTxCerts(
        pp: PParams[Babbage],
        isPoolRegistered: KeyHash[StakePool] => Boolean,
        certs: Iterable[BabbageTxCert]
    ): Coin = shelleyTotalDepositsTxCerts(pp, isPoolRegistered, certs)
    override def getTotalRefundsTxCerts(
        pp: PParams[Babbage],
        lookupStakingDeposit: Credential[Staking] => Option[Coin],
        certs: Iterable[BabbageTxCert]
    ): Coin = shelleyTotalRefundsTxCerts(pp, lookupStakingDeposit, certs)

    override def mkRegTxCert(credential: Credential[Staking]): BabbageTxCert =
      ShelleyTxCertRep.ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyRegCert(credential))
    override def getRegTxCert(cert: BabbageTxCert): Option[Credential[Staking]] =
      deleg(cert).flatMap {
        case ShelleyDelegCert.ShelleyRegCert(c) => Some(c)
        case ShelleyDelegCert.ShelleyUnRegCert(_) | ShelleyDelegCert.ShelleyDelegCert(_, _) => None
      }
    override def mkUnRegTxCert(credential: Credential[Staking]): BabbageTxCert =
      ShelleyTxCertRep.ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyUnRegCert(credential))
    override def getUnRegTxCert(cert: BabbageTxCert): Option[Credential[Staking]] =
      deleg(cert).flatMap {
        case ShelleyDelegCert.ShelleyUnRegCert(c)                                         => Some(c)
        case ShelleyDelegCert.ShelleyRegCert(_) | ShelleyDelegCert.ShelleyDelegCert(_, _) => None
      }
    override def mkDelegStakeTxCert(
        credential: Credential[Staking],
        pool: KeyHash[StakePool]
    ): BabbageTxCert =
      ShelleyTxCertRep.ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyDelegCert(credential, pool))
    override def getDelegStakeTxCert(
        cert: BabbageTxCert
    ): Option[(Credential[Staking], KeyHash[StakePool])] =
      deleg(cert).flatMap {
        case ShelleyDelegCert.ShelleyDelegCert(c, p) => Some((c, p))
        case ShelleyDelegCert.ShelleyRegCert(_) | ShelleyDelegCert.ShelleyUnRegCert(_) => None
      }

    // `mkGenesisDelegTxCert` to `getMirTxCert`, `Babbage/TxCert.hs:57-65`, cardano-ledger f649f975
    override def mkGenesisDelegTxCert(cert: GenesisDelegCert): BabbageTxCert =
      ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(cert)
    override def getGenesisDelegTxCert(cert: BabbageTxCert): Option[GenesisDelegCert] =
      cert match
        case ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(c) => Some(c)
        case ShelleyTxCertRep.ShelleyTxCertDelegCert(_) | ShelleyTxCertRep.ShelleyTxCertPool(_) |
            ShelleyTxCertRep.ShelleyTxCertMir(_) =>
          None
    override def mkMirTxCert(cert: MIRCert): BabbageTxCert = ShelleyTxCertRep.ShelleyTxCertMir(cert)
    override def getMirTxCert(cert: BabbageTxCert): Option[MIRCert] = cert match
      case ShelleyTxCertRep.ShelleyTxCertMir(c) => Some(c)
      case ShelleyTxCertRep.ShelleyTxCertDelegCert(_) | ShelleyTxCertRep.ShelleyTxCertPool(_) |
          ShelleyTxCertRep.ShelleyTxCertGenesisDeleg(_) =>
        None
