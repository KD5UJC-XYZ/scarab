// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Anchor, Credential, DRep, PoolIdentity, PoolParams}
import xyz.kd5ujc.scarab.ledger.state.Coin

/** A pool certificate (`data PoolCert`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/Core/TxCert.hs:128-133`): the reference shares it
  * between the eras' certificates, and its meaning is one in every era.
  */
enum PoolCert:
  case RegPool(params: StakePoolParams)
  case RetirePool(pool: Bytes, epoch: BigInt)

  /** The pool's key, which signs the certificate (`poolCertKeyHashWitness`). */
  def witness: Bytes = this match
    case RegPool(params)     => params.value.operator
    case RetirePool(pool, _) => pool

/** A pool's parameters as a certificate registers them (`data StakePoolParams`, cardano-ledger
  * f649f975 `libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:424-435`). The relays
  * and the metadata are kept as the bytes they were read from ([[PoolParams]]), and two parameters
  * are equal when what those bytes mean is equal ([[PoolParams.identity]]), as the reference's
  * derived `Eq` compares the decoded `StrictSeq StakePoolRelay` and `PoolMetadata`: an empty relay
  * list read as `80` and as `9800` is one list.
  */
final class StakePoolParams private[era] (val value: PoolParams):
  private lazy val identity: PoolIdentity = PoolParams.identity(value)

  override def equals(that: Any): Boolean = that match
    case o: StakePoolParams => identity == o.identity
    case _                  => false
  override def hashCode(): Int = identity.hashCode
  override def toString: String = s"StakePoolParams($value)"

object StakePoolParams:
  given CanEqual[StakePoolParams, StakePoolParams] = CanEqual.derived

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
  case ConwayRegCert(credential: Credential, deposit: Option[Coin])

  /** Tag 1 without a refund, tag 8 with one. */
  case ConwayUnRegCert(credential: Credential, refund: Option[Coin])
  case ConwayDelegCert(credential: Credential, delegatee: Delegatee)
  case ConwayRegDelegCert(credential: Credential, delegatee: Delegatee, deposit: Coin)

  def credential: Credential

/** The governance certificates from Conway (`data ConwayGovCert`, `Conway/TxCert.hs:568-574`,
  * cardano-ledger f649f975).
  */
enum ConwayGovCert:
  case ConwayRegDRep(credential: Credential, deposit: Coin, anchor: Option[Anchor])
  case ConwayUnRegDRep(credential: Credential, refund: Coin)
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
