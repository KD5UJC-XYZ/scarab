// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Anchor
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayDelegCert,
  ConwayEraTxCert,
  ConwayGovCert,
  Delegatee,
  PParams,
  PoolCert
}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxCert
import xyz.kd5ujc.scarab.ledger.state.{
  ColdCommittee,
  Coin,
  Credential,
  DRepRole,
  Dijkstra,
  EpochNo,
  HotCommittee,
  KeyHash,
  KeyRole,
  StakePool,
  StakePoolParams,
  Staking
}

// Dijkstra's certificates, at cardano-ledger 9df9f98c, the ledger of the Leios testnet's build (the
// Dijkstra Scarab implements, #54).

/** The staking certificates of Dijkstra (`data DijkstraDelegCert`,
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxCert.hs:91-96`, cardano-ledger 9df9f98c): a
  * registration always pays its deposit and an unregistration always names its refund; those
  * without (Conway's tags 0 and 1) are gone.
  */
enum DijkstraDelegCert:
  case DijkstraRegCert(credential: Credential[Staking], deposit: Coin)
  case DijkstraUnRegCert(credential: Credential[Staking], refund: Coin)
  case DijkstraDelegCert(credential: Credential[Staking], delegatee: Delegatee)
  case DijkstraRegDelegCert(credential: Credential[Staking], delegatee: Delegatee, deposit: Coin)

  def credential: Credential[Staking]

/** Why a Conway certificate has no Dijkstra one (`data DijkstraTxCertUpgradeError`,
  * `TxCert.hs:191-194`, cardano-ledger 9df9f98c): a registration or an unregistration without a
  * deposit.
  */
enum DijkstraTxCertUpgradeError:
  case RegTxCertExpunged
  case UnRegTxCertExpunged

object DijkstraTxCertUpgradeError:
  given CanEqual[DijkstraTxCertUpgradeError, DijkstraTxCertUpgradeError] = CanEqual.derived

/** A Dijkstra certificate (`data DijkstraTxCert era`, `TxCert.hs:185-189`, cardano-ledger
  * 9df9f98c): a staking, a pool or a governance certificate; the pool and governance certificates
  * are Conway's.
  */
enum DijkstraTxCert:
  case DijkstraTxCertDeleg(cert: DijkstraDelegCert)
  case DijkstraTxCertPool(cert: PoolCert)
  case DijkstraTxCertGov(cert: ConwayGovCert)

object DijkstraTxCert:
  given CanEqual[DijkstraTxCert, DijkstraTxCert] = CanEqual.derived

  /** A Conway certificate carried into Dijkstra (`upgradeTxCert`, `TxCert.hs:279-295`,
    * cardano-ledger 9df9f98c): the same certificate, but a registration or an unregistration
    * without a deposit, which Dijkstra has not.
    */
  def translate(c: ConwayTxCert): Either[DijkstraTxCertUpgradeError, DijkstraTxCert] = c match
    case ConwayTxCert.ConwayTxCertDeleg(deleg) =>
      deleg match
        case ConwayDelegCert.ConwayRegCert(_, None) =>
          Left(DijkstraTxCertUpgradeError.RegTxCertExpunged)
        case ConwayDelegCert.ConwayRegCert(cred, Some(d)) =>
          Right(DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegCert(cred, d)))
        case ConwayDelegCert.ConwayUnRegCert(_, None) =>
          Left(DijkstraTxCertUpgradeError.UnRegTxCertExpunged)
        case ConwayDelegCert.ConwayUnRegCert(cred, Some(r)) =>
          Right(DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraUnRegCert(cred, r)))
        case ConwayDelegCert.ConwayDelegCert(cred, to) =>
          Right(DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraDelegCert(cred, to)))
        case ConwayDelegCert.ConwayRegDelegCert(cred, to, d) =>
          Right(DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegDelegCert(cred, to, d)))
    case ConwayTxCert.ConwayTxCertPool(pool) => Right(DijkstraTxCertPool(pool))
    case ConwayTxCert.ConwayTxCertGov(gov)   => Right(DijkstraTxCertGov(gov))

  /** `dijkstraToConwayDelegCert` (`TxCert.hs:396-401`, cardano-ledger 9df9f98c): the Conway staking
    * certificate of the same meaning, its deposit or refund given.
    */
  def dijkstraToConwayDelegCert(c: DijkstraDelegCert): ConwayDelegCert = c match
    case DijkstraDelegCert.DijkstraRegCert(cred, deposit) =>
      ConwayDelegCert.ConwayRegCert(cred, Some(deposit))
    case DijkstraDelegCert.DijkstraUnRegCert(cred, refund) =>
      ConwayDelegCert.ConwayUnRegCert(cred, Some(refund))
    case DijkstraDelegCert.DijkstraDelegCert(cred, d) => ConwayDelegCert.ConwayDelegCert(cred, d)
    case DijkstraDelegCert.DijkstraRegDelegCert(cred, d, dep) =>
      ConwayDelegCert.ConwayRegDelegCert(cred, d, dep)

  /** `dijkstraTotalRefundsTxCerts` (`TxCert.hs:261-272`, cardano-ledger 9df9f98c): the refund each
    * staking and DRep unregistration names. Every unregistration names its refund, which the rules
    * check, so the ledger state is not read.
    */
  def dijkstraTotalRefundsTxCerts(certs: Iterable[DijkstraTxCert]): Coin =
    Coin(certs.iterator.map {
      case DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraUnRegCert(_, refund)) => refund.value
      case DijkstraTxCertGov(ConwayGovCert.ConwayUnRegDRep(_, refund))         => refund.value
      case DijkstraTxCertDeleg(
            DijkstraDelegCert.DijkstraRegCert(_, _) | DijkstraDelegCert.DijkstraDelegCert(_, _) |
            DijkstraDelegCert.DijkstraRegDelegCert(_, _, _)
          ) =>
        BigInt(0)
      case DijkstraTxCertPool(_) => BigInt(0)
      case DijkstraTxCertGov(
            ConwayGovCert.ConwayRegDRep(_, _, _) | ConwayGovCert.ConwayUpdateDRep(_, _) |
            ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) |
            ConwayGovCert.ConwayResignCommitteeColdKey(_, _)
          ) =>
        BigInt(0)
    }.sum)

  private def keyHash(c: Credential[? <: KeyRole]): Option[Bytes] = c match
    case Credential.KeyHashObj(h)    => Some(h.bytes)
    case Credential.ScriptHashObj(_) => None

  private def scriptHash(c: Credential[? <: KeyRole]): Option[Bytes] = c match
    case Credential.ScriptHashObj(h) => Some(h.bytes)
    case Credential.KeyHashObj(_)    => None

  // The certificate of each kind, so that each reader matches every case of its kind.
  private def deleg(cert: DijkstraTxCert): Option[DijkstraDelegCert] = cert match
    case DijkstraTxCertDeleg(c)                       => Some(c)
    case DijkstraTxCertPool(_) | DijkstraTxCertGov(_) => None

  private def poolCert(cert: DijkstraTxCert): Option[PoolCert] = cert match
    case DijkstraTxCertPool(c)                         => Some(c)
    case DijkstraTxCertDeleg(_) | DijkstraTxCertGov(_) => None

  private def gov(cert: DijkstraTxCert): Option[ConwayGovCert] = cert match
    case DijkstraTxCertGov(c)                           => Some(c)
    case DijkstraTxCertDeleg(_) | DijkstraTxCertPool(_) => None

  /** `EraTxCert DijkstraEra` (`TxCert.hs:274-321`) and `ConwayEraTxCert DijkstraEra`
    * (`TxCert.hs:353-394`), cardano-ledger 9df9f98c. Dijkstra has no `ShelleyEraTxCert` instance:
    * the class is of the eras up to Conway (`AtMostEra "Conway"`,
    * `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:159`).
    */
  val instance: ConwayEraTxCert[Dijkstra] = new ConwayEraTxCert[Dijkstra]:
    // `getVKeyWitnessDijkstraTxCert`, `TxCert.hs:342-351`, cardano-ledger 9df9f98c: every staking
    // certificate is witnessed by its credential
    override def getVKeyWitnessTxCert(cert: DijkstraTxCert): Option[Bytes] = cert match
      case DijkstraTxCertDeleg(c) => keyHash(c.credential)
      case DijkstraTxCertPool(c)  => Some(c.witness)
      case DijkstraTxCertGov(c)   => keyHash(c.witness)
    // `getScriptWitnessDijkstraTxCert`, `TxCert.hs:323-340`, cardano-ledger 9df9f98c: a pool is
    // never a script
    override def getScriptWitnessTxCert(cert: DijkstraTxCert): Option[Bytes] = cert match
      case DijkstraTxCertDeleg(c) => scriptHash(c.credential)
      case DijkstraTxCertPool(_)  => None
      case DijkstraTxCertGov(c)   => scriptHash(c.witness)

    override def mkRegPoolTxCert(params: StakePoolParams): DijkstraTxCert =
      DijkstraTxCertPool(PoolCert.RegPool(params))
    override def getRegPoolTxCert(cert: DijkstraTxCert): Option[StakePoolParams] =
      poolCert(cert).flatMap {
        case PoolCert.RegPool(params)  => Some(params)
        case PoolCert.RetirePool(_, _) => None
      }
    override def mkRetirePoolTxCert(pool: KeyHash[StakePool], epoch: EpochNo): DijkstraTxCert =
      DijkstraTxCertPool(PoolCert.RetirePool(pool, epoch))
    override def getRetirePoolTxCert(cert: DijkstraTxCert): Option[(KeyHash[StakePool], EpochNo)] =
      poolCert(cert).flatMap {
        case PoolCert.RetirePool(p, epoch) => Some((p, epoch))
        case PoolCert.RegPool(_)           => None
      }

    // `lookupRegStakeTxCert` and `lookupUnRegStakeTxCert`, `TxCert.hs:311-317`, cardano-ledger 9df9f98c
    override def lookupRegStakeTxCert(cert: DijkstraTxCert): Option[Credential[Staking]] =
      deleg(cert).flatMap {
        case DijkstraDelegCert.DijkstraRegCert(c, _)         => Some(c)
        case DijkstraDelegCert.DijkstraRegDelegCert(c, _, _) => Some(c)
        case DijkstraDelegCert.DijkstraUnRegCert(_, _) |
            DijkstraDelegCert.DijkstraDelegCert(_, _) =>
          None
      }
    override def lookupUnRegStakeTxCert(cert: DijkstraTxCert): Option[Credential[Staking]] =
      deleg(cert).flatMap {
        case DijkstraDelegCert.DijkstraUnRegCert(c, _) => Some(c)
        case DijkstraDelegCert.DijkstraRegCert(_, _) | DijkstraDelegCert.DijkstraDelegCert(_, _) |
            DijkstraDelegCert.DijkstraRegDelegCert(_, _, _) =>
          None
      }

    // `getTotalRefundsTxCerts` and `getTotalDepositsTxCerts`, `TxCert.hs:319-321`, cardano-ledger 9df9f98c
    override def getTotalDepositsTxCerts(
        pp: PParams[Dijkstra],
        isPoolRegistered: KeyHash[StakePool] => Boolean,
        certs: Iterable[DijkstraTxCert]
    ): Coin = conwayTotalDepositsTxCerts(pp, isPoolRegistered, certs)
    override def getTotalRefundsTxCerts(
        pp: PParams[Dijkstra],
        lookupStakingDeposit: Credential[Staking] => Option[Coin],
        certs: Iterable[DijkstraTxCert]
    ): Coin = dijkstraTotalRefundsTxCerts(certs)

    override def mkRegDepositTxCert(
        credential: Credential[Staking],
        deposit: Coin
    ): DijkstraTxCert =
      DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegCert(credential, deposit))
    override def getRegDepositTxCert(cert: DijkstraTxCert): Option[(Credential[Staking], Coin)] =
      deleg(cert).flatMap {
        case DijkstraDelegCert.DijkstraRegCert(c, d) => Some((c, d))
        case DijkstraDelegCert.DijkstraUnRegCert(_, _) | DijkstraDelegCert.DijkstraDelegCert(_, _) |
            DijkstraDelegCert.DijkstraRegDelegCert(_, _, _) =>
          None
      }
    override def mkUnRegDepositTxCert(
        credential: Credential[Staking],
        refund: Coin
    ): DijkstraTxCert =
      DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraUnRegCert(credential, refund))
    override def getUnRegDepositTxCert(cert: DijkstraTxCert): Option[(Credential[Staking], Coin)] =
      deleg(cert).flatMap {
        case DijkstraDelegCert.DijkstraUnRegCert(c, r) => Some((c, r))
        case DijkstraDelegCert.DijkstraRegCert(_, _) | DijkstraDelegCert.DijkstraDelegCert(_, _) |
            DijkstraDelegCert.DijkstraRegDelegCert(_, _, _) =>
          None
      }
    override def mkDelegTxCert(
        credential: Credential[Staking],
        delegatee: Delegatee
    ): DijkstraTxCert =
      DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraDelegCert(credential, delegatee))
    override def getDelegTxCert(cert: DijkstraTxCert): Option[(Credential[Staking], Delegatee)] =
      deleg(cert).flatMap {
        case DijkstraDelegCert.DijkstraDelegCert(c, to) => Some((c, to))
        case DijkstraDelegCert.DijkstraRegCert(_, _) | DijkstraDelegCert.DijkstraUnRegCert(_, _) |
            DijkstraDelegCert.DijkstraRegDelegCert(_, _, _) =>
          None
      }
    override def mkRegDepositDelegTxCert(
        credential: Credential[Staking],
        delegatee: Delegatee,
        deposit: Coin
    ): DijkstraTxCert =
      DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegDelegCert(credential, delegatee, deposit))
    override def getRegDepositDelegTxCert(
        cert: DijkstraTxCert
    ): Option[(Credential[Staking], Delegatee, Coin)] =
      deleg(cert).flatMap {
        case DijkstraDelegCert.DijkstraRegDelegCert(c, to, d) => Some((c, to, d))
        case DijkstraDelegCert.DijkstraRegCert(_, _) | DijkstraDelegCert.DijkstraUnRegCert(_, _) |
            DijkstraDelegCert.DijkstraDelegCert(_, _) =>
          None
      }

    override def mkAuthCommitteeHotKeyTxCert(
        cold: Credential[ColdCommittee],
        hot: Credential[HotCommittee]
    ): DijkstraTxCert = DijkstraTxCertGov(ConwayGovCert.ConwayAuthCommitteeHotKey(cold, hot))
    override def getAuthCommitteeHotKeyTxCert(
        cert: DijkstraTxCert
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
    ): DijkstraTxCert = DijkstraTxCertGov(ConwayGovCert.ConwayResignCommitteeColdKey(cold, anchor))
    override def getResignCommitteeColdTxCert(
        cert: DijkstraTxCert
    ): Option[(Credential[ColdCommittee], Option[Anchor])] =
      gov(cert).flatMap {
        case ConwayGovCert.ConwayResignCommitteeColdKey(cold, a) => Some((cold, a))
        case ConwayGovCert.ConwayRegDRep(_, _, _) | ConwayGovCert.ConwayUnRegDRep(_, _) |
            ConwayGovCert.ConwayUpdateDRep(_, _) | ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) =>
          None
      }
    override def mkRegDRepTxCert(
        credential: Credential[DRepRole],
        deposit: Coin,
        anchor: Option[Anchor]
    ): DijkstraTxCert = DijkstraTxCertGov(ConwayGovCert.ConwayRegDRep(credential, deposit, anchor))
    override def getRegDRepTxCert(
        cert: DijkstraTxCert
    ): Option[(Credential[DRepRole], Coin, Option[Anchor])] =
      gov(cert).flatMap {
        case ConwayGovCert.ConwayRegDRep(c, d, a) => Some((c, d, a))
        case ConwayGovCert.ConwayUnRegDRep(_, _) | ConwayGovCert.ConwayUpdateDRep(_, _) |
            ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) |
            ConwayGovCert.ConwayResignCommitteeColdKey(_, _) =>
          None
      }
    override def mkUnRegDRepTxCert(credential: Credential[DRepRole], refund: Coin): DijkstraTxCert =
      DijkstraTxCertGov(ConwayGovCert.ConwayUnRegDRep(credential, refund))
    override def getUnRegDRepTxCert(cert: DijkstraTxCert): Option[(Credential[DRepRole], Coin)] =
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
    ): DijkstraTxCert = DijkstraTxCertGov(ConwayGovCert.ConwayUpdateDRep(credential, anchor))
    override def getUpdateDRepTxCert(
        cert: DijkstraTxCert
    ): Option[(Credential[DRepRole], Option[Anchor])] =
      gov(cert).flatMap {
        case ConwayGovCert.ConwayUpdateDRep(c, a) => Some((c, a))
        case ConwayGovCert.ConwayRegDRep(_, _, _) | ConwayGovCert.ConwayUnRegDRep(_, _) |
            ConwayGovCert.ConwayAuthCommitteeHotKey(_, _) |
            ConwayGovCert.ConwayResignCommitteeColdKey(_, _) =>
          None
      }
