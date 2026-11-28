// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  Coin,
  Credential,
  DeltaCoin,
  EraTag,
  Genesis,
  GenesisDelegate,
  KeyHash,
  StakePool,
  Staking,
  VRFVerKeyHash
}

/** The staking certificates of Shelley's form (`data ShelleyDelegCert`, cardano-ledger f649f975
  * `eras/shelley/impl/src/Cardano/Ledger/Shelley/TxCert.hs:520-527`): a registration and an
  * unregistration without a deposit, and a delegation to a pool.
  */
enum ShelleyDelegCert:
  case ShelleyRegCert(credential: Credential[Staking])
  case ShelleyUnRegCert(credential: Credential[Staking])
  case ShelleyDelegCert(credential: Credential[Staking], pool: KeyHash[StakePool])

/** A genesis key's delegation (`data GenesisDelegCert`, `Shelley/TxCert.hs:289-294`, cardano-ledger
  * f649f975): the genesis key, its delegate and the delegate's VRF key hash.
  */
final case class GenesisDelegCert(
    genesis: KeyHash[Genesis],
    delegate: KeyHash[GenesisDelegate],
    vrf: VRFVerKeyHash
)

/** The pot instantaneous rewards are moved from (`data MIRPot`, `Shelley/TxCert.hs:316-317`,
  * cardano-ledger f649f975).
  */
enum MIRPot:
  case ReservesMIR, TreasuryMIR

/** Where instantaneous rewards go (`data MIRTarget`, `Shelley/TxCert.hs:340-343`, cardano-ledger
  * f649f975): to staking credentials, each a signed change, or to the other pot.
  */
enum MIRTarget:
  case StakeAddressesMIR(rewards: Map[Credential[Staking], DeltaCoin])
  case SendToOppositePotMIR(coin: Coin)

/** A move of instantaneous rewards (`data MIRCert`, `Shelley/TxCert.hs:368-372`, cardano-ledger
  * f649f975).
  */
final case class MIRCert(pot: MIRPot, rewards: MIRTarget)

/** The certificates the reference shares from Shelley to Babbage (`data ShelleyTxCert era`,
  * `Shelley/TxCert.hs:393-398`, cardano-ledger f649f975), whose era is a phantom: a staking, a
  * pool, a genesis delegation or an instantaneous-rewards certificate. Each era's certificate is an
  * opaque type over it ([[babbage.BabbageTxCert]]), so the representation is shared and the types
  * are not; it has no `CanEqual`, so under strict equality two of its values are compared only as
  * the same era's.
  */
enum ShelleyTxCertRep:
  case ShelleyTxCertDelegCert(cert: ShelleyDelegCert)
  case ShelleyTxCertPool(cert: PoolCert)
  case ShelleyTxCertGenesisDeleg(cert: GenesisDelegCert)
  case ShelleyTxCertMir(cert: MIRCert)

object ShelleyTxCertRep:
  private def keyHash(c: Credential[Staking]): Option[Bytes] = c match
    case Credential.KeyHashObj(h)    => Some(h.bytes)
    case Credential.ScriptHashObj(_) => None

  private def scriptHash(c: Credential[Staking]): Option[Bytes] = c match
    case Credential.ScriptHashObj(h) => Some(h.bytes)
    case Credential.KeyHashObj(_)    => None

  /** `getVKeyWitnessShelleyTxCert` (`Shelley/TxCert.hs:588-598`, cardano-ledger f649f975): no
    * witness for a registration or a move of rewards, the credential's key for an unregistration or
    * a delegation, the pool's key and the genesis key.
    */
  def getVKeyWitnessShelleyTxCert(cert: ShelleyTxCertRep): Option[Bytes] = cert match
    case ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyRegCert(_))      => None
    case ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyUnRegCert(c))    => keyHash(c)
    case ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyDelegCert(c, _)) => keyHash(c)
    case ShelleyTxCertPool(c)                                            => Some(c.witness)
    case ShelleyTxCertGenesisDeleg(c)                                    => Some(c.genesis.bytes)
    case ShelleyTxCertMir(_)                                             => None

  /** `getScriptWitnessShelleyTxCert` (`Shelley/TxCert.hs:577-586`, cardano-ledger f649f975): the
    * credential's script for an unregistration or a delegation, none otherwise.
    */
  def getScriptWitnessShelleyTxCert(cert: ShelleyTxCertRep): Option[Bytes] = cert match
    case ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyRegCert(_))                => None
    case ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyUnRegCert(c))              => scriptHash(c)
    case ShelleyTxCertDelegCert(ShelleyDelegCert.ShelleyDelegCert(c, _))           => scriptHash(c)
    case ShelleyTxCertPool(_) | ShelleyTxCertGenesisDeleg(_) | ShelleyTxCertMir(_) => None

/** The evidence that era `E` 's certificate is the representation Shelley to Babbage share (`TxCert
  * era ~ ShelleyTxCert era`, which DELPL asks for, `Shelley/Rules/Delpl.hs:184`, cardano-ledger
  * f649f975). It goes one way: it reads a certificate's representation and makes no certificate of
  * one. It is made only from the compiler's proof that the era's certificate is the representation
  * ([[IsShelleyTxCert.of]]), which only the file that defines the era's opaque certificate can
  * give, and the class is final with a private constructor, so no other code makes or implements
  * one; not being a function, it converts nothing implicitly.
  */
final class IsShelleyTxCert[E <: EraTag] private (proof: TxCert[E] =:= ShelleyTxCertRep):
  def shelleyTxCert(cert: TxCert[E]): ShelleyTxCertRep = proof(cert)

/** The instances of each era, the one place that lists the eras whose certificate is Shelley's. */
object IsShelleyTxCert:
  /** The evidence of the compiler's proof `proof`. */
  def of[E <: EraTag](using proof: TxCert[E] =:= ShelleyTxCertRep): IsShelleyTxCert[E] =
    new IsShelleyTxCert[E](proof)

  given babbage: IsShelleyTxCert[Babbage] =
    xyz.kd5ujc.scarab.ledger.era.babbage.BabbageTxCert.isShelleyTxCert
