// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Anchor, Credential, PoolParams}
import xyz.kd5ujc.scarab.ledger.state.{Conway, EraTag}

/** The certificates of era `E` (`EraTxCert`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/TxCert.hs:43-106`). A certificate of the
  * previous era is carried in by the era's own translation (the reference's `upgradeTxCert`). The
  * deposit and refund totals take the era's protocol parameters, and come with them.
  */
trait EraTxCert[E <: EraTag]:
  /** The key hash that must sign the certificate (`getVKeyWitnessTxCert`). */
  def getVKeyWitnessTxCert(cert: TxCert[E]): Option[Bytes]

  /** The script that must witness the certificate (`getScriptWitnessTxCert`). */
  def getScriptWitnessTxCert(cert: TxCert[E]): Option[Bytes]

  def getRegPoolTxCert(cert: TxCert[E]): Option[PoolParams]
  def getRetirePoolTxCert(cert: TxCert[E]): Option[(Bytes, BigInt)]

  /** The credential a certificate registers, by any of the era's registrations. */
  def lookupRegStakeTxCert(cert: TxCert[E]): Option[Credential]

  /** The credential a certificate unregisters, by any of the era's unregistrations. */
  def lookupUnRegStakeTxCert(cert: TxCert[E]): Option[Credential]

/** The staking certificates of Shelley's form, up to Conway (`ShelleyEraTxCert`, `AtMostEra
  * "Conway"`, `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:154-169`, cardano-ledger
  * f649f975). The reference's genesis-delegation and instantaneous-reward readers are of the eras
  * up to Babbage only (`AtMostEra "Babbage"` on the method). Evidence of a range of eras is ruled
  * out (ADR 0007, amendment of 2026-11-14), so they come with those eras in a class of their own,
  * whose instances are exactly those eras'.
  */
trait ShelleyEraTxCert[E <: EraTag] extends EraTxCert[E]:
  /** A registration without a deposit (tag 0). */
  def getRegTxCert(cert: TxCert[E]): Option[Credential]

  /** An unregistration without a refund (tag 1). */
  def getUnRegTxCert(cert: TxCert[E]): Option[Credential]

  /** A delegation to a pool alone (tag 2). */
  def getDelegStakeTxCert(cert: TxCert[E]): Option[(Credential, Bytes)]

/** The staking and governance certificates from Conway (`ConwayEraTxCert`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/TxCert.hs:171-214`, cardano-ledger f649f975).
  */
trait ConwayEraTxCert[E <: EraTag] extends EraTxCert[E]:
  def getRegDepositTxCert(cert: TxCert[E]): Option[(Credential, BigInt)]
  def getUnRegDepositTxCert(cert: TxCert[E]): Option[(Credential, BigInt)]
  def getDelegTxCert(cert: TxCert[E]): Option[(Credential, Delegatee)]
  def getRegDepositDelegTxCert(cert: TxCert[E]): Option[(Credential, Delegatee, BigInt)]
  def getAuthCommitteeHotKeyTxCert(cert: TxCert[E]): Option[(Credential, Credential)]
  def getResignCommitteeColdTxCert(cert: TxCert[E]): Option[(Credential, Option[Anchor])]
  def getRegDRepTxCert(cert: TxCert[E]): Option[(Credential, BigInt, Option[Anchor])]
  def getUnRegDRepTxCert(cert: TxCert[E]): Option[(Credential, BigInt)]
  def getUpdateDRepTxCert(cert: TxCert[E]): Option[(Credential, Option[Anchor])]

/** The instances of each era, the one place that lists the eras having each certificate class. */
object EraTxCert:
  given conway: (ConwayEraTxCert[Conway] & ShelleyEraTxCert[Conway]) =
    xyz.kd5ujc.scarab.ledger.era.conway.ConwayTxCert.instance
