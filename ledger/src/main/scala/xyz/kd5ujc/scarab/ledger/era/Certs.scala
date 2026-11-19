// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Anchor
import xyz.kd5ujc.scarab.ledger.state.{
  ColdCommittee,
  Coin,
  Credential,
  DRep,
  DRepRole,
  EpochNo,
  HotCommittee,
  KeyHash,
  KeyRole,
  StakePool,
  StakePoolParams,
  Staking
}

/** A pool certificate (`data PoolCert`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/TxCert.hs:128-133`): the reference shares it
  * between the eras' certificates, and its meaning is one in every era.
  */
enum PoolCert:
  /** The parameters are the state's ([[xyz.kd5ujc.scarab.ledger.state.StakePoolParams]]), as the
    * reference's certificate holds the core library's `StakePoolParams`.
    */
  case RegPool(params: StakePoolParams)
  case RetirePool(pool: KeyHash[StakePool], epoch: EpochNo)

  /** The pool's key, which signs the certificate (`poolCertKeyHashWitness`). */
  def witness: Bytes = this match
    case RegPool(params)     => params.id.bytes
    case RetirePool(pool, _) => pool.bytes

/** What a Conway delegation delegates to (`data Delegatee`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/TxCert.hs:379-383`, cardano-ledger f649f975): a
  * pool, a DRep, or both. A delegation to neither has no value.
  */
enum Delegatee:
  case DelegStake(pool: KeyHash[StakePool])
  case DelegVote(drep: DRep)
  case DelegStakeVote(pool: KeyHash[StakePool], drep: DRep)

object Delegatee:
  /** `mkDelegatee` (`Conway/TxCert.hs:439-445`, cardano-ledger f649f975): what a pool and a DRep
    * delegate to, none if neither is given.
    */
  def mkDelegatee(pool: Option[KeyHash[StakePool]], drep: Option[DRep]): Option[Delegatee] =
    (pool, drep) match
      case (None, None)       => None
      case (Some(p), None)    => Some(DelegStake(p))
      case (None, Some(d))    => Some(DelegVote(d))
      case (Some(p), Some(d)) => Some(DelegStakeVote(p, d))

  /** `getStakePoolDelegatee` (`Conway/TxCert.hs:447-451`, cardano-ledger f649f975). */
  def getStakePoolDelegatee(d: Delegatee): Option[KeyHash[StakePool]] = d match
    case DelegStake(pool)        => Some(pool)
    case DelegVote(_)            => None
    case DelegStakeVote(pool, _) => Some(pool)

  /** `getDRepDelegatee` (`Conway/TxCert.hs:453-456`, cardano-ledger f649f975). */
  def getDRepDelegatee(d: Delegatee): Option[DRep] = d match
    case DelegStake(_)           => None
    case DelegVote(drep)         => Some(drep)
    case DelegStakeVote(_, drep) => Some(drep)

/** The staking certificates from Conway (`data ConwayDelegCert`, `Conway/TxCert.hs:473-486`,
  * cardano-ledger f649f975).
  */
enum ConwayDelegCert:
  /** Tag 0 without a deposit, tag 7 with one. */
  case ConwayRegCert(credential: Credential[Staking], deposit: Option[Coin])

  /** Tag 1 without a refund, tag 8 with one. */
  case ConwayUnRegCert(credential: Credential[Staking], refund: Option[Coin])
  case ConwayDelegCert(credential: Credential[Staking], delegatee: Delegatee)
  case ConwayRegDelegCert(credential: Credential[Staking], delegatee: Delegatee, deposit: Coin)

  def credential: Credential[Staking]

/** The governance certificates from Conway (`data ConwayGovCert`, `Conway/TxCert.hs:568-574`,
  * cardano-ledger f649f975), each credential of its role: a DRep's, or a committee member's cold or
  * hot one.
  */
enum ConwayGovCert:
  case ConwayRegDRep(credential: Credential[DRepRole], deposit: Coin, anchor: Option[Anchor])
  case ConwayUnRegDRep(credential: Credential[DRepRole], refund: Coin)
  case ConwayUpdateDRep(credential: Credential[DRepRole], anchor: Option[Anchor])
  case ConwayAuthCommitteeHotKey(cold: Credential[ColdCommittee], hot: Credential[HotCommittee])
  case ConwayResignCommitteeColdKey(cold: Credential[ColdCommittee], anchor: Option[Anchor])

  /** The credential that signs or witnesses the certificate (`conwayGovCertVKeyWitness`,
    * `Conway/TxCert.hs:798-804`, cardano-ledger f649f975): the cold credential of a committee
    * certificate.
    */
  def witness: Credential[? <: KeyRole] = this match
    case ConwayRegDRep(c, _, _)                => c
    case ConwayUnRegDRep(c, _)                 => c
    case ConwayUpdateDRep(c, _)                => c
    case ConwayAuthCommitteeHotKey(cold, _)    => cold
    case ConwayResignCommitteeColdKey(cold, _) => cold
