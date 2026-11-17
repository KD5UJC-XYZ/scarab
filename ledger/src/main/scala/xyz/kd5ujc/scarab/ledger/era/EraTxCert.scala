// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Anchor
import xyz.kd5ujc.scarab.ledger.state.{
  ColdCommittee,
  Coin,
  Conway,
  Credential,
  DRepRole,
  Dijkstra,
  EpochNo,
  EraTag,
  HotCommittee,
  KeyHash,
  StakePool,
  Staking
}

/** The certificates of era `E` (`EraTxCert`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/TxCert.hs:43-106`). A certificate of the
  * previous era is carried in by the era's own translation (the reference's `upgradeTxCert`). The
  * deposit and refund totals take the era's protocol parameters, and come with them. Each reader
  * has its builder (`mkRegPoolTxCert` and the others): a certificate keeps no bytes of its own, so
  * a built one is a value like a read one.
  */
trait EraTxCert[E <: EraTag]:
  /** The key hash that must sign the certificate (`getVKeyWitnessTxCert`). */
  def getVKeyWitnessTxCert(cert: TxCert[E]): Option[Bytes]

  /** The script that must witness the certificate (`getScriptWitnessTxCert`). */
  def getScriptWitnessTxCert(cert: TxCert[E]): Option[Bytes]

  def mkRegPoolTxCert(params: StakePoolParams): TxCert[E]
  def getRegPoolTxCert(cert: TxCert[E]): Option[StakePoolParams]
  def mkRetirePoolTxCert(pool: KeyHash[StakePool], epoch: EpochNo): TxCert[E]
  def getRetirePoolTxCert(cert: TxCert[E]): Option[(KeyHash[StakePool], EpochNo)]

  /** The credential a certificate registers, by any of the era's registrations. */
  def lookupRegStakeTxCert(cert: TxCert[E]): Option[Credential[Staking]]

  /** The credential a certificate unregisters, by any of the era's unregistrations. */
  def lookupUnRegStakeTxCert(cert: TxCert[E]): Option[Credential[Staking]]

  /** `getTotalDepositsTxCerts` (`Core/TxCert.hs:90-97`, cardano-ledger f649f975): the deposits the
    * certificates pay, given whether a pool is already registered.
    */
  def getTotalDepositsTxCerts(
      pp: PParams[E],
      isPoolRegistered: KeyHash[StakePool] => Boolean,
      certs: Iterable[TxCert[E]]
  ): Coin

  /** `getTotalRefundsTxCerts` (`Core/TxCert.hs:99-106`, cardano-ledger f649f975): the refunds the
    * certificates collect, given the deposit of a registered staking credential.
    */
  def getTotalRefundsTxCerts(
      pp: PParams[E],
      lookupStakingDeposit: Credential[Staking] => Option[Coin],
      certs: Iterable[TxCert[E]]
  ): Coin

  /** `shelleyTotalDepositsTxCerts`
    * (`eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:611-630`, cardano-ledger f649f975):
    * the key deposit for each registration, and the pool deposit for each pool neither registered
    * nor registered earlier in the certificates.
    */
  protected final def shelleyTotalDepositsTxCerts(
      pp: PParams[E],
      isPoolRegistered: KeyHash[StakePool] => Boolean,
      certs: Iterable[TxCert[E]]
  )(using EraPParams[E]): Coin =
    val numKeys = certs.count(lookupRegStakeTxCert(_).isDefined)
    val newRegPools = certs.foldLeft(Set.empty[KeyHash[StakePool]])((regPoolIds, cert) =>
      getRegPoolTxCert(cert) match
        case Some(params)
            if !(isPoolRegistered(params.sppId) || regPoolIds.contains(params.sppId)) =>
          regPoolIds + params.sppId
        case _ => regPoolIds
    )
    Coin(
      BigInt(numKeys) * EraPParams.ppKeyDepositCompactL[E].get(pp).toBigInt +
        BigInt(newRegPools.size) * EraPParams.ppPoolDepositCompactL[E].get(pp).toBigInt
    )

  /** `shelleyTotalRefundsTxCerts` (`Shelley/TxCert.hs:633-660`, cardano-ledger f649f975): the key
    * deposit for an unregistration of a credential registered earlier in the certificates, else the
    * deposit the credential holds.
    */
  protected final def shelleyTotalRefundsTxCerts(
      pp: PParams[E],
      lookupDeposit: Credential[Staking] => Option[Coin],
      certs: Iterable[TxCert[E]]
  )(using EraPParams[E]): Coin =
    val keyDeposit = EraPParams.ppKeyDepositCompactL[E].get(pp).toBigInt
    val (_, totalRefunds) =
      certs.foldLeft((Set.empty[Credential[Staking]], BigInt(0))) {
        case ((regCreds, total), cert) =>
          lookupRegStakeTxCert(cert) match
            case Some(k) => (regCreds + k, total)
            case None =>
              lookupUnRegStakeTxCert(cert) match
                case Some(cred) if regCreds.contains(cred) => (regCreds - cred, total + keyDeposit)
                case Some(cred) =>
                  lookupDeposit(cred).fold((regCreds, total))(d => (regCreds, total + d.value))
                case None => (regCreds, total)
      }
    Coin(totalRefunds)

/** The staking certificates of Shelley's form, up to Conway (`ShelleyEraTxCert`, `AtMostEra
  * "Conway"`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:154-169`, cardano-ledger
  * f649f975). The reference's genesis-delegation and instantaneous-reward readers are of the eras
  * up to Babbage only (`AtMostEra "Babbage"` on the method). Evidence of a range of eras is ruled
  * out (ADR 0007, amendment of 2026-11-14), so they come with those eras in a class of their own,
  * whose instances are exactly those eras'.
  */
trait ShelleyEraTxCert[E <: EraTag] extends EraTxCert[E]:
  /** A registration without a deposit (tag 0). */
  def mkRegTxCert(credential: Credential[Staking]): TxCert[E]
  def getRegTxCert(cert: TxCert[E]): Option[Credential[Staking]]

  /** An unregistration without a refund (tag 1). */
  def mkUnRegTxCert(credential: Credential[Staking]): TxCert[E]
  def getUnRegTxCert(cert: TxCert[E]): Option[Credential[Staking]]

  /** A delegation to a pool alone (tag 2). */
  def mkDelegStakeTxCert(credential: Credential[Staking], pool: KeyHash[StakePool]): TxCert[E]
  def getDelegStakeTxCert(cert: TxCert[E]): Option[(Credential[Staking], KeyHash[StakePool])]

/** The staking and governance certificates from Conway (`ConwayEraTxCert`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/TxCert.hs:171-214`, cardano-ledger f649f975).
  */
trait ConwayEraTxCert[E <: EraTag] extends EraTxCert[E]:
  /** `conwayTotalDepositsTxCerts` (`Conway/TxCert.hs:817-826`, cardano-ledger f649f975): Shelley's
    * deposits and the DRep deposits.
    */
  protected final def conwayTotalDepositsTxCerts(
      pp: PParams[E],
      isPoolRegistered: KeyHash[StakePool] => Boolean,
      certs: Iterable[TxCert[E]]
  )(using p: ConwayEraPParams[E]): Coin =
    Coin(
      shelleyTotalDepositsTxCerts(pp, isPoolRegistered, certs).value +
        conwayDRepDepositsTxCerts(pp, certs).value
    )

  /** `conwayDRepDepositsTxCerts` (`Conway/TxCert.hs:828-836`, cardano-ledger f649f975): the DRep
    * deposit of the parameters for each DRep registration.
    */
  protected final def conwayDRepDepositsTxCerts(pp: PParams[E], certs: Iterable[TxCert[E]])(using
      ConwayEraPParams[E]
  ): Coin =
    Coin(
      BigInt(certs.count(getRegDRepTxCert(_).isDefined)) *
        ConwayEraPParams.ppDRepDepositCompactL[E].get(pp).toBigInt
    )

  /** `conwayTotalRefundsTxCerts` (`Conway/TxCert.hs:839-848`, cardano-ledger f649f975): Shelley's
    * refunds and the DRep refunds.
    */
  protected final def conwayTotalRefundsTxCerts(
      pp: PParams[E],
      lookupStakingDeposit: Credential[Staking] => Option[Coin],
      certs: Iterable[TxCert[E]]
  )(using EraPParams[E]): Coin =
    Coin(
      shelleyTotalRefundsTxCerts(pp, lookupStakingDeposit, certs).value +
        conwayDRepRefundsTxCerts(certs).value
    )

  /** `conwayDRepRefundsTxCerts` (`Conway/TxCert.hs:852-858`, cardano-ledger f649f975): the refund
    * each DRep unregistration names.
    */
  protected final def conwayDRepRefundsTxCerts(certs: Iterable[TxCert[E]]): Coin =
    Coin(certs.iterator.flatMap(getUnRegDRepTxCert(_)).map(_._2.value).sum)

  def mkRegDepositTxCert(credential: Credential[Staking], deposit: Coin): TxCert[E]
  def getRegDepositTxCert(cert: TxCert[E]): Option[(Credential[Staking], Coin)]
  def mkUnRegDepositTxCert(credential: Credential[Staking], refund: Coin): TxCert[E]
  def getUnRegDepositTxCert(cert: TxCert[E]): Option[(Credential[Staking], Coin)]
  def mkDelegTxCert(credential: Credential[Staking], delegatee: Delegatee): TxCert[E]
  def getDelegTxCert(cert: TxCert[E]): Option[(Credential[Staking], Delegatee)]
  def mkRegDepositDelegTxCert(
      credential: Credential[Staking],
      delegatee: Delegatee,
      deposit: Coin
  ): TxCert[E]
  def getRegDepositDelegTxCert(cert: TxCert[E]): Option[(Credential[Staking], Delegatee, Coin)]
  def mkAuthCommitteeHotKeyTxCert(
      cold: Credential[ColdCommittee],
      hot: Credential[HotCommittee]
  ): TxCert[E]
  def getAuthCommitteeHotKeyTxCert(
      cert: TxCert[E]
  ): Option[(Credential[ColdCommittee], Credential[HotCommittee])]
  def mkResignCommitteeColdTxCert(
      cold: Credential[ColdCommittee],
      anchor: Option[Anchor]
  ): TxCert[E]
  def getResignCommitteeColdTxCert(
      cert: TxCert[E]
  ): Option[(Credential[ColdCommittee], Option[Anchor])]
  def mkRegDRepTxCert(
      credential: Credential[DRepRole],
      deposit: Coin,
      anchor: Option[Anchor]
  ): TxCert[E]
  def getRegDRepTxCert(cert: TxCert[E]): Option[(Credential[DRepRole], Coin, Option[Anchor])]
  def mkUnRegDRepTxCert(credential: Credential[DRepRole], refund: Coin): TxCert[E]
  def getUnRegDRepTxCert(cert: TxCert[E]): Option[(Credential[DRepRole], Coin)]
  def mkUpdateDRepTxCert(credential: Credential[DRepRole], anchor: Option[Anchor]): TxCert[E]
  def getUpdateDRepTxCert(cert: TxCert[E]): Option[(Credential[DRepRole], Option[Anchor])]

/** The instances of each era, the one place that lists the eras having each certificate class. */
object EraTxCert:
  given conway: (ConwayEraTxCert[Conway] & ShelleyEraTxCert[Conway]) =
    xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxCert.instance
  given dijkstra: ConwayEraTxCert[Dijkstra] =
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTxCert.instance
