// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Anchor, Credential, DRep, PoolParams}

/** A pool certificate (`data PoolCert`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/TxCert.hs:128-133`): the reference shares it
  * between the eras' certificates, and its meaning is one in every era.
  */
enum PoolCert:
  case RegPool(params: PoolParams)
  case RetirePool(pool: Bytes, epoch: BigInt)

  /** The pool's key, which signs the certificate (`poolCertKeyHashWitness`). */
  def witness: Bytes = this match
    case RegPool(params)     => params.operator
    case RetirePool(pool, _) => pool

/** What a Conway delegation delegates to (`data Delegatee`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/TxCert.hs:379-383`, cardano-ledger f649f975): a
  * pool, a DRep, or both. A delegation to neither has no value.
  */
enum Delegatee:
  case DelegStake(pool: Bytes)
  case DelegVote(drep: DRep)
  case DelegStakeVote(pool: Bytes, drep: DRep)

/** The staking certificates from Conway (`data ConwayDelegCert`, `Conway/TxCert.hs:473-486`,
  * cardano-ledger f649f975).
  */
enum ConwayDelegCert:
  /** Tag 0 without a deposit, tag 7 with one. */
  case ConwayRegCert(credential: Credential, deposit: Option[BigInt])

  /** Tag 1 without a refund, tag 8 with one. */
  case ConwayUnRegCert(credential: Credential, refund: Option[BigInt])
  case ConwayDelegCert(credential: Credential, delegatee: Delegatee)
  case ConwayRegDelegCert(credential: Credential, delegatee: Delegatee, deposit: BigInt)

  def credential: Credential

/** The governance certificates from Conway (`data ConwayGovCert`, `Conway/TxCert.hs:568-574`,
  * cardano-ledger f649f975).
  */
enum ConwayGovCert:
  case ConwayRegDRep(credential: Credential, deposit: BigInt, anchor: Option[Anchor])
  case ConwayUnRegDRep(credential: Credential, refund: BigInt)
  case ConwayUpdateDRep(credential: Credential, anchor: Option[Anchor])
  case ConwayAuthCommitteeHotKey(cold: Credential, hot: Credential)
  case ConwayResignCommitteeColdKey(cold: Credential, anchor: Option[Anchor])

  /** The credential that signs or witnesses the certificate (`conwayGovCertVKeyWitness`,
    * `Conway/TxCert.hs:798-804`, cardano-ledger f649f975): the cold credential of a committee
    * certificate.
    */
  def witness: Credential = this match
    case ConwayRegDRep(c, _, _)                => c
    case ConwayUnRegDRep(c, _)                 => c
    case ConwayUpdateDRep(c, _)                => c
    case ConwayAuthCommitteeHotKey(cold, _)    => cold
    case ConwayResignCommitteeColdKey(cold, _) => cold
