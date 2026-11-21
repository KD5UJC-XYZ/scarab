// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import xyz.kd5ujc.scarab.ledger.{
  CertFailure as FlatCertFailure,
  CertsFailure as FlatCertsFailure,
  DelegFailure as FlatDelegFailure,
  GovCertFailure as FlatGovCertFailure,
  Mismatch,
  PoolFailure as FlatPoolFailure,
  PredicateFailure
}
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  ColdCommittee,
  Coin,
  Conway,
  Credential,
  DRepRole,
  EpochNo,
  EraTag,
  KeyHash,
  Network,
  StakePool,
  Staking,
  VRFVerKeyHash
}

// The failures and events of Conway's certificate rules as the reference declares them
// (cardano-ledger f649f975, `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/{Certs,Cert,Deleg,
// GovCert}.hs`, and Shelley's POOL, `eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Pool.hs`),
// with their injections and the embeddings of each rule in the rule that runs it. At 29a7d656 and
// 9df9f98c the types derive `Ord` too; `ConwayCertsPredFailure` loses
// `WithdrawalsNotInRewardsCERTS` (its base case moved to LEDGER, `WithdrawalsRule`), and
// `StakeKeyRegisteredDELEG` is named `DelegAccountAlreadyRegistered`, over Shelley's
// `AccountAlreadyRegistered`, a newtype of the same credential with the same tag and bytes.

/** `data ConwayDelegPredFailure era` (`Conway/Rules/Deleg.hs:105-114`, cardano-ledger f649f975),
  * its constructors in the order of their tags (1 to 8, `:126-142`).
  */
enum ConwayDelegPredFailure[E <: EraTag]:
  case IncorrectDepositDELEG(coin: Coin)
  case StakeKeyRegisteredDELEG(credential: Credential[Staking])
  case StakeKeyNotRegisteredDELEG(credential: Credential[Staking])
  case StakeKeyHasNonZeroAccountBalanceDELEG(coin: Coin)
  case DelegateeDRepNotRegisteredDELEG(credential: Credential[DRepRole])
  case DelegateeStakePoolNotRegisteredDELEG(pool: KeyHash[StakePool])

  /** The deposit (supplied) and the parameters' (expected). */
  case DepositIncorrectDELEG(mismatch: Mismatch[Coin])

  /** The refund (supplied) and the account's deposit (expected). */
  case RefundIncorrectDELEG(mismatch: Mismatch[Coin])

/** `data ShelleyPoolPredFailure era` (`Shelley/Rules/Pool.hs:91-114`, cardano-ledger f649f975), its
  * constructors in the order of their tags (0, 1, then 3 to 6, `:154-170`: tag 2 is unused).
  */
enum ShelleyPoolPredFailure[E <: EraTag]:
  case StakePoolNotRegisteredOnKeyPOOL(pool: KeyHash[StakePool])

  /** The epoch (supplied) against the current one, which it must follow, and against the last one
    * it may be, which it must not pass (expected).
    */
  case StakePoolRetirementWrongEpochPOOL(current: Mismatch[EpochNo], latest: Mismatch[EpochNo])

  /** The cost (supplied) and the parameters' minimum (expected). */
  case StakePoolCostTooLowPOOL(mismatch: Mismatch[Coin])

  /** The reward account's network (supplied) and the node's (expected). */
  case WrongNetworkPOOL(mismatch: Mismatch[Network], pool: KeyHash[StakePool])
  case PoolMedataHashTooBig(pool: KeyHash[StakePool], size: Int)
  case VRFKeyHashAlreadyRegistered(pool: KeyHash[StakePool], vrf: VRFVerKeyHash)

/** `data ConwayGovCertPredFailure era` (`Conway/Rules/GovCert.hs:108-118`, cardano-ledger
  * f649f975), its constructors in the order of their tags (0 to 5, `:131-136`).
  */
enum ConwayGovCertPredFailure[E <: EraTag]:
  case ConwayDRepAlreadyRegistered(credential: Credential[DRepRole])
  case ConwayDRepNotRegistered(credential: Credential[DRepRole])

  /** The deposit (supplied) and the parameters' (expected). */
  case ConwayDRepIncorrectDeposit(mismatch: Mismatch[Coin])
  case ConwayCommitteeHasPreviouslyResigned(credential: Credential[ColdCommittee])

  /** The refund (supplied) and the DRep's deposit (expected). */
  case ConwayDRepIncorrectRefund(mismatch: Mismatch[Coin])
  case ConwayCommitteeIsUnknown(credential: Credential[ColdCommittee])

/** `data ConwayCertPredFailure era` (`Conway/Rules/Cert.hs:101-105`, cardano-ledger f649f975), its
  * constructors in the order of their tags (1 to 3, `:267-269`).
  */
enum ConwayCertPredFailure[E <: EraTag]:
  case DelegFailure(failure: DelegPredFailure[E])
  case PoolFailure(failure: PoolPredFailure[E])
  case GovCertFailure(failure: GovCertPredFailure[E])

/** `data ConwayCertsPredFailure era` (`Conway/Rules/Certs.hs:114-119`, cardano-ledger f649f975),
  * its constructors in the order of their tags (0 and 1, `:166-167`). The withdrawals that do not
  * drain their accounts are the reference's `Withdrawals` (`Map AccountAddress Coin`,
  * `Address.hs:980`), the amount of each incomplete one as withdrawn. Only the 11.1 build gives
  * `WithdrawalsNotInRewardsCERTS`: at 29a7d656 and 9df9f98c the type has `CertFailure` alone
  * ([[WithdrawalsRule]]).
  */
enum ConwayCertsPredFailure[E <: EraTag]:
  case WithdrawalsNotInRewardsCERTS(withdrawals: Map[AccountAddress, Coin])
  case CertFailure(failure: CertPredFailure[E])

/** `EraRuleFailure "DELEG" era`: Conway's is its own (`Conway/Rules/Deleg.hs:116`, cardano-ledger
  * f649f975). Each era adds its case with its rule.
  */
type DelegPredFailure[E <: EraTag] = E match
  case Conway => ConwayDelegPredFailure[Conway]

/** `EraRuleFailure "POOL" era`: Conway's is Shelley's (`Conway/Rules/Cert.hs`, `Embed (Shelley.POOL
  * era) (CERT era)`, `:236-246`, cardano-ledger f649f975).
  */
type PoolPredFailure[E <: EraTag] = E match
  case Conway => ShelleyPoolPredFailure[Conway]

/** `EraRuleFailure "GOVCERT" era`: Conway's is its own (`Conway/Rules/GovCert.hs:120`,
  * cardano-ledger f649f975).
  */
type GovCertPredFailure[E <: EraTag] = E match
  case Conway => ConwayGovCertPredFailure[Conway]

/** `EraRuleFailure "CERT" era`: Conway's is its own (`Conway/Rules/Cert.hs:107`, cardano-ledger
  * f649f975).
  */
type CertPredFailure[E <: EraTag] = E match
  case Conway => ConwayCertPredFailure[Conway]

/** `EraRuleFailure "CERTS" era`: Conway's is its own (`Conway/Rules/Certs.hs:121`, cardano-ledger
  * f649f975).
  */
type CertsPredFailure[E <: EraTag] = E match
  case Conway => ConwayCertsPredFailure[Conway]

/** `data PoolEvent era` (`Shelley/Rules/Pool.hs:146-149`, cardano-ledger f649f975): a pool
  * registered, or registered again.
  */
enum PoolEvent[E <: EraTag]:
  case RegisterPool(pool: KeyHash[StakePool])
  case ReregisterPool(pool: KeyHash[StakePool])

/** `EraRuleEvent "POOL" era`: Conway's is Shelley's `PoolEvent` (`Shelley/Rules/Pool.hs:120`,
  * cardano-ledger f649f975). Named apart from the data type it reduces to.
  */
type PoolRuleEvent[E <: EraTag] = E match
  case Conway => PoolEvent[Conway]

/** `EraRuleEvent "DELEG" era`: Conway's is void (`VoidEraRule`, `Conway/Rules/Deleg.hs:118`,
  * cardano-ledger f649f975): DELEG tells nothing, and the type has no value.
  */
type DelegEvent[E <: EraTag] = E match
  case Conway => Nothing

/** `EraRuleEvent "GOVCERT" era`: Conway's is void (`Conway/Rules/GovCert.hs:122`, cardano-ledger
  * f649f975).
  */
type GovCertEvent[E <: EraTag] = E match
  case Conway => Nothing

/** `data ConwayCertEvent era` (`Conway/Rules/Cert.hs:143-147`, cardano-ledger f649f975). */
enum ConwayCertEvent[E <: EraTag]:
  case DelegEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.DelegEvent[E])
  case PoolEvent(event: PoolRuleEvent[E])
  case GovCertEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.GovCertEvent[E])

/** `EraRuleEvent "CERT" era`: Conway's is its own (`Conway/Rules/Cert.hs:109`, cardano-ledger
  * f649f975).
  */
type CertEvent[E <: EraTag] = E match
  case Conway => ConwayCertEvent[Conway]

/** `newtype ConwayCertsEvent era` (`Conway/Rules/Certs.hs:151`, cardano-ledger f649f975). */
enum ConwayCertsEvent[E <: EraTag]:
  case CertEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.CertEvent[E])

/** `EraRuleEvent "CERTS" era`: Conway's is its own (`Conway/Rules/Certs.hs:123`, cardano-ledger
  * f649f975).
  */
type CertsEvent[E <: EraTag] = E match
  case Conway => ConwayCertsEvent[Conway]

/** `InjectRuleFailure "DELEG" ConwayDelegPredFailure era` (`Conway/Rules/Deleg.hs:120`,
  * cardano-ledger f649f975): how era `E` 's DELEG reports the failures of Conway's transition. Only
  * this package makes one.
  */
sealed trait InjectDeleg[E <: EraTag]:
  def injectFailure(failure: ConwayDelegPredFailure[E]): DelegPredFailure[E]

object InjectDeleg:
  /** The identity. */
  given conway: InjectDeleg[Conway] with
    def injectFailure(failure: ConwayDelegPredFailure[Conway]): DelegPredFailure[Conway] = failure

/** `InjectRuleFailure "POOL" ShelleyPoolPredFailure era` and `InjectRuleEvent "POOL" PoolEvent era`
  * (`Shelley/Rules/Pool.hs:118, 122`, cardano-ledger f649f975), at Conway through its `EraRule
  * "POOL"`, Shelley's.
  */
sealed trait InjectPool[E <: EraTag]:
  def injectFailure(failure: ShelleyPoolPredFailure[E]): PoolPredFailure[E]
  def injectEvent(event: PoolEvent[E]): PoolRuleEvent[E]

object InjectPool:
  /** The identities. */
  given conway: InjectPool[Conway] with
    def injectFailure(failure: ShelleyPoolPredFailure[Conway]): PoolPredFailure[Conway] = failure
    def injectEvent(event: PoolEvent[Conway]): PoolRuleEvent[Conway] = event

/** `InjectRuleFailure "GOVCERT" ConwayGovCertPredFailure era` (`Conway/Rules/GovCert.hs:124`,
  * cardano-ledger f649f975).
  */
sealed trait InjectGovCert[E <: EraTag]:
  def injectFailure(failure: ConwayGovCertPredFailure[E]): GovCertPredFailure[E]

object InjectGovCert:
  /** The identity. */
  given conway: InjectGovCert[Conway] with
    def injectFailure(failure: ConwayGovCertPredFailure[Conway]): GovCertPredFailure[Conway] =
      failure

/** `InjectRuleFailure "CERT" f era` (`Conway/Rules/Cert.hs:111-120`, cardano-ledger f649f975): how
  * era `E` 's CERT reports a failure `F` of its own or of a rule it runs. One instance per era and
  * failure type, in the companion.
  */
trait InjectCertFailure[F, E <: EraTag]:
  def injectFailure(failure: F): CertPredFailure[E]

object InjectCertFailure:
  /** `Conway/Rules/Cert.hs:111` (cardano-ledger f649f975): the identity. */
  given conway: InjectCertFailure[ConwayCertPredFailure[Conway], Conway] with
    def injectFailure(failure: ConwayCertPredFailure[Conway]): CertPredFailure[Conway] = failure

  /** `Conway/Rules/Cert.hs:113-114` (cardano-ledger f649f975): `DelegFailure`. */
  given conwayDeleg: InjectCertFailure[ConwayDelegPredFailure[Conway], Conway] with
    def injectFailure(failure: ConwayDelegPredFailure[Conway]): CertPredFailure[Conway] =
      ConwayCertPredFailure.DelegFailure(failure)

  /** `Conway/Rules/Cert.hs:116-117` (cardano-ledger f649f975): `PoolFailure`. */
  given conwayPool: InjectCertFailure[ShelleyPoolPredFailure[Conway], Conway] with
    def injectFailure(failure: ShelleyPoolPredFailure[Conway]): CertPredFailure[Conway] =
      ConwayCertPredFailure.PoolFailure(failure)

  /** `Conway/Rules/Cert.hs:119-120` (cardano-ledger f649f975): `GovCertFailure`. */
  given conwayGovCert: InjectCertFailure[ConwayGovCertPredFailure[Conway], Conway] with
    def injectFailure(failure: ConwayGovCertPredFailure[Conway]): CertPredFailure[Conway] =
      ConwayCertPredFailure.GovCertFailure(failure)

/** `InjectRuleFailure "CERTS" f era` (`Conway/Rules/Certs.hs:125-137`, cardano-ledger f649f975):
  * how era `E` 's CERTS reports a failure `F` of its own or of a rule below it, CERT's
  * `CertFailure . injectFailure`.
  */
trait InjectCertsFailure[F, E <: EraTag]:
  def injectFailure(failure: F): CertsPredFailure[E]

object InjectCertsFailure:
  /** `Conway/Rules/Certs.hs:125` (cardano-ledger f649f975): the identity. */
  given conway: InjectCertsFailure[ConwayCertsPredFailure[Conway], Conway] with
    def injectFailure(failure: ConwayCertsPredFailure[Conway]): CertsPredFailure[Conway] = failure

  /** `Conway/Rules/Certs.hs:127-137` (cardano-ledger f649f975): CERT's failure, and each of the
    * rules below it through CERT's injection, wrapped in `CertFailure`.
    */
  given conwayCert[F](using inject: InjectCertFailure[F, Conway]): InjectCertsFailure[F, Conway]
  with
    def injectFailure(failure: F): CertsPredFailure[Conway] =
      ConwayCertsPredFailure.CertFailure(inject.injectFailure(failure))

/** `Embed (DELEG era) (CERT era)`, `Embed (Shelley.POOL era) (CERT era)` and `Embed (GOVCERT era)
  * (CERT era)` (`Conway/Rules/Cert.hs:225-256`, cardano-ledger f649f975): how the failures and
  * events of the rules CERT runs sit in CERT's. DELEG's and GOVCERT's events are void (`absurd`).
  */
trait EmbedInCert[E <: EraTag]:
  def wrapDelegFailed(failure: DelegPredFailure[E]): CertPredFailure[E]
  def wrapPoolFailed(failure: PoolPredFailure[E]): CertPredFailure[E]
  def wrapPoolEvent(event: PoolRuleEvent[E]): CertEvent[E]
  def wrapGovCertFailed(failure: GovCertPredFailure[E]): CertPredFailure[E]

object EmbedInCert:
  /** Conway's: `DelegFailure`, `PoolFailure` with `PoolEvent`, and `GovCertFailure`. */
  given conway: EmbedInCert[Conway] with
    def wrapDelegFailed(failure: DelegPredFailure[Conway]): CertPredFailure[Conway] =
      ConwayCertPredFailure.DelegFailure(failure)
    def wrapPoolFailed(failure: PoolPredFailure[Conway]): CertPredFailure[Conway] =
      ConwayCertPredFailure.PoolFailure(failure)
    def wrapPoolEvent(event: PoolRuleEvent[Conway]): CertEvent[Conway] =
      ConwayCertEvent.PoolEvent(event)
    def wrapGovCertFailed(failure: GovCertPredFailure[Conway]): CertPredFailure[Conway] =
      ConwayCertPredFailure.GovCertFailure(failure)

/** `Embed (CERT era) (CERTS era)` (`Conway/Rules/Certs.hs:294-304`, cardano-ledger f649f975):
  * `CertFailure` and `CertEvent`.
  */
trait EmbedCertInCerts[E <: EraTag]:
  def wrapFailed(failure: CertPredFailure[E]): CertsPredFailure[E]
  def wrapEvent(event: CertEvent[E]): CertsEvent[E]

object EmbedCertInCerts:
  given conway: EmbedCertInCerts[Conway] with
    def wrapFailed(failure: CertPredFailure[Conway]): CertsPredFailure[Conway] =
      ConwayCertsPredFailure.CertFailure(failure)
    def wrapEvent(event: CertEvent[Conway]): CertsEvent[Conway] = ConwayCertsEvent.CertEvent(event)

object ConwayCertsPredFailure:
  given [E <: EraTag]: CanEqual[ConwayCertsPredFailure[E], ConwayCertsPredFailure[E]] =
    CanEqual.derived

  /** The failure in the node's flat form (`PredicateFailure`), glue the comparisons with the flat
    * rules read until the failures of the era types have their own encoders (#85). It is one-way;
    * every failure has one.
    */
  def toStored(failure: ConwayCertsPredFailure[Conway]): PredicateFailure = failure match
    case WithdrawalsNotInRewardsCERTS(ws) =>
      PredicateFailure.Certs(
        FlatCertsFailure.WithdrawalsNotInRewards(
          ws.map((account, coin) => Convert.rewardAccountBack(account) -> coin.value)
        )
      )
    case CertFailure(f) =>
      PredicateFailure.Certs(FlatCertsFailure.Cert(ConwayCertPredFailure.toStored(f)))

object ConwayCertPredFailure:
  given [E <: EraTag]: CanEqual[ConwayCertPredFailure[E], ConwayCertPredFailure[E]] =
    CanEqual.derived

  /** CERT's failure in the node's flat form ([[ConwayCertsPredFailure.toStored]]). */
  def toStored(failure: ConwayCertPredFailure[Conway]): FlatCertFailure = failure match
    case DelegFailure(f)   => FlatCertFailure.Deleg(ConwayDelegPredFailure.toStored(f))
    case PoolFailure(f)    => FlatCertFailure.Pool(ShelleyPoolPredFailure.toStored(f))
    case GovCertFailure(f) => FlatCertFailure.GovCert(ConwayGovCertPredFailure.toStored(f))

object ConwayDelegPredFailure:
  given [E <: EraTag]: CanEqual[ConwayDelegPredFailure[E], ConwayDelegPredFailure[E]] =
    CanEqual.derived

  /** DELEG's failure in the node's flat form ([[ConwayCertsPredFailure.toStored]]). */
  def toStored(failure: ConwayDelegPredFailure[Conway]): FlatDelegFailure = failure match
    case IncorrectDepositDELEG(c)      => FlatDelegFailure.IncorrectDeposit(c.value)
    case StakeKeyRegisteredDELEG(c)    => FlatDelegFailure.StakeKeyRegistered(Convert.back(c))
    case StakeKeyNotRegisteredDELEG(c) => FlatDelegFailure.StakeKeyNotRegistered(Convert.back(c))
    case StakeKeyHasNonZeroAccountBalanceDELEG(c) =>
      FlatDelegFailure.StakeKeyHasNonZeroAccountBalance(c.value)
    case DelegateeDRepNotRegisteredDELEG(c) =>
      FlatDelegFailure.DelegateeDRepNotRegistered(Convert.back(c))
    case DelegateeStakePoolNotRegisteredDELEG(p) =>
      FlatDelegFailure.DelegateeStakePoolNotRegistered(p.bytes)
    case DepositIncorrectDELEG(m) => FlatDelegFailure.DepositIncorrect(coins(m))
    case RefundIncorrectDELEG(m)  => FlatDelegFailure.RefundIncorrect(coins(m))

object ShelleyPoolPredFailure:
  given [E <: EraTag]: CanEqual[ShelleyPoolPredFailure[E], ShelleyPoolPredFailure[E]] =
    CanEqual.derived

  /** POOL's failure in the node's flat form ([[ConwayCertsPredFailure.toStored]]). */
  def toStored(failure: ShelleyPoolPredFailure[Conway]): FlatPoolFailure = failure match
    case StakePoolNotRegisteredOnKeyPOOL(p) => FlatPoolFailure.StakePoolNotRegisteredOnKey(p.bytes)
    case StakePoolRetirementWrongEpochPOOL(current, latest) =>
      FlatPoolFailure.StakePoolRetirementWrongEpoch(epochs(current), epochs(latest))
    case StakePoolCostTooLowPOOL(m) => FlatPoolFailure.StakePoolCostTooLow(coins(m))
    case WrongNetworkPOOL(m, p) =>
      FlatPoolFailure.WrongNetworkPool(Mismatch(m.supplied.wire, m.expected.wire), p.bytes)
    case PoolMedataHashTooBig(p, size) => FlatPoolFailure.PoolMedataHashTooBig(p.bytes, size)
    case VRFKeyHashAlreadyRegistered(p, v) =>
      FlatPoolFailure.VRFKeyHashAlreadyRegistered(p.bytes, v.bytes)

object ConwayGovCertPredFailure:
  given [E <: EraTag]: CanEqual[ConwayGovCertPredFailure[E], ConwayGovCertPredFailure[E]] =
    CanEqual.derived

  /** GOVCERT's failure in the node's flat form ([[ConwayCertsPredFailure.toStored]]). */
  def toStored(failure: ConwayGovCertPredFailure[Conway]): FlatGovCertFailure = failure match
    case ConwayDRepAlreadyRegistered(c) => FlatGovCertFailure.DRepAlreadyRegistered(Convert.back(c))
    case ConwayDRepNotRegistered(c)     => FlatGovCertFailure.DRepNotRegistered(Convert.back(c))
    case ConwayDRepIncorrectDeposit(m)  => FlatGovCertFailure.DRepIncorrectDeposit(coins(m))
    case ConwayCommitteeHasPreviouslyResigned(c) =>
      FlatGovCertFailure.CommitteeHasPreviouslyResigned(Convert.back(c))
    case ConwayDRepIncorrectRefund(m) => FlatGovCertFailure.DRepIncorrectRefund(coins(m))
    case ConwayCommitteeIsUnknown(c)  => FlatGovCertFailure.CommitteeIsUnknown(Convert.back(c))

private def coins(m: Mismatch[Coin]): Mismatch[BigInt] =
  Mismatch(m.supplied.value, m.expected.value)

private def epochs(m: Mismatch[EpochNo]): Mismatch[BigInt] =
  Mismatch(m.supplied.toBigInt, m.expected.toBigInt)
