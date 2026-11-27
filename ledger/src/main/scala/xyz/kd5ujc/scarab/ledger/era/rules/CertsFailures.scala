// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  ColdCommittee,
  Coin,
  Conway,
  Credential,
  DRepRole,
  Dijkstra,
  EpochNo,
  EraTag,
  KeyHash,
  Network,
  StakePool,
  Staking,
  VRFVerKeyHash
}
import xyz.kd5ujc.scarab.ledger.era.Mismatch
import xyz.kd5ujc.scarab.ledger.era.Relation.*

// The failures and events of Conway's certificate rules as the reference declares them
// (cardano-ledger f649f975, `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/{Certs,Cert,Deleg,
// GovCert}.hs`, and Shelley's POOL, `eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Pool.hs`),
// with their injections and the embeddings of each rule in the rule that runs it. At 29a7d656 and
// 9df9f98c the types derive `Ord` too; `ConwayCertsPredFailure` loses
// `WithdrawalsNotInRewardsCERTS` (its base case moved to LEDGER, `WithdrawalsRule`), and
// `StakeKeyRegisteredDELEG` is named `DelegAccountAlreadyRegistered`, over Shelley's
// `AccountAlreadyRegistered`, a newtype of the same credential with the same tag and bytes.

/** `newtype AccountAlreadyRegistered era` (`Shelley/Rules/Deleg.hs:84-87`, cardano-ledger 29a7d656
  * and 9df9f98c): an account registered again, the credential that Conway's DELEG reports as
  * `DelegAccountAlreadyRegistered` (`StakeKeyRegisteredDELEG` here). The 11.1 line's pin has no
  * such type; the builds that do inject it into LEDGER
  * ([[InjectLedgerFailure.conwayAccountAlreadyRegistered]]), and Dijkstra's DELEG has its injection
  * ([[InjectDelegFailure.dijkstra]]).
  */
final case class AccountAlreadyRegistered[E <: EraTag](credential: Credential[Staking])

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
  case DepositIncorrectDELEG(mismatch: Mismatch[RelEQ, Coin])

  /** The refund (supplied) and the account's deposit (expected). */
  case RefundIncorrectDELEG(mismatch: Mismatch[RelEQ, Coin])

/** `data ShelleyPoolPredFailure era` (`Shelley/Rules/Pool.hs:91-114`, cardano-ledger f649f975), its
  * constructors in the order of their tags (0, 1, then 3 to 6, `:154-170`: tag 2 is unused).
  */
enum ShelleyPoolPredFailure[E <: EraTag]:
  case StakePoolNotRegisteredOnKeyPOOL(pool: KeyHash[StakePool])

  /** The epoch (supplied) against the current one, which it must follow, and against the last one
    * it may be, which it must not pass (expected).
    */
  case StakePoolRetirementWrongEpochPOOL(
      current: Mismatch[RelGT, EpochNo],
      latest: Mismatch[RelLTEQ, EpochNo]
  )

  /** The cost (supplied) and the parameters' minimum (expected). */
  case StakePoolCostTooLowPOOL(mismatch: Mismatch[RelGTEQ, Coin])

  /** The reward account's network (supplied) and the node's (expected). */
  case WrongNetworkPOOL(mismatch: Mismatch[RelEQ, Network], pool: KeyHash[StakePool])
  case PoolMedataHashTooBig(pool: KeyHash[StakePool], size: Int)
  case VRFKeyHashAlreadyRegistered(pool: KeyHash[StakePool], vrf: VRFVerKeyHash)

/** `data ConwayGovCertPredFailure era` (`Conway/Rules/GovCert.hs:108-118`, cardano-ledger
  * f649f975), its constructors in the order of their tags (0 to 5, `:131-136`).
  */
enum ConwayGovCertPredFailure[E <: EraTag]:
  case ConwayDRepAlreadyRegistered(credential: Credential[DRepRole])
  case ConwayDRepNotRegistered(credential: Credential[DRepRole])

  /** The deposit (supplied) and the parameters' (expected). */
  case ConwayDRepIncorrectDeposit(mismatch: Mismatch[RelEQ, Coin])
  case ConwayCommitteeHasPreviouslyResigned(credential: Credential[ColdCommittee])

  /** The refund (supplied) and the DRep's deposit (expected). */
  case ConwayDRepIncorrectRefund(mismatch: Mismatch[RelEQ, Coin])
  case ConwayCommitteeIsUnknown(credential: Credential[ColdCommittee])

/** `data ConwayCertPredFailure era` (`Conway/Rules/Cert.hs:101-105`, cardano-ledger f649f975), its
  * constructors in the order of their tags (1 to 3, `:267-269`).
  */
enum ConwayCertPredFailure[E <: EraTag]:
  case DelegFailure(failure: DelegPredFailure[E])
  case PoolFailure(failure: PoolPredFailure[E])
  case GovCertFailure(failure: GovCertPredFailure[E])

/** The withdrawals of `WithdrawalsNotInRewardsCERTS`, of the eras whose CERTS has the constructor:
  * the reference's `Withdrawals` (`Map AccountAddress Coin`, `Address.hs:980`, cardano-ledger
  * f649f975) at Conway, whose era types follow the 11.1 build's ledger, which has it. Dijkstra's
  * CERTS has `CertFailure` alone (`newtype ConwayCertsPredFailure era`, `Conway/Rules/Certs.hs:
  * 111-114`, cardano-ledger 9df9f98c), so its case has no value there, as [[MirSigners]] has none
  * where the era has no MIR certificates.
  */
type CertsWithdrawals[E <: EraTag] = E match
  case Conway   => Map[AccountAddress, Coin]
  case Dijkstra => Nothing

/** `data ConwayCertsPredFailure era` (`Conway/Rules/Certs.hs:114-119`, cardano-ledger f649f975),
  * its constructors in the order of their tags (0 and 1, `:166-167`). The withdrawals that do not
  * drain their accounts are the reference's `Withdrawals`, the amount of each incomplete one as
  * withdrawn. Only the 11.1 build gives `WithdrawalsNotInRewardsCERTS`: at 29a7d656 and 9df9f98c
  * the type has `CertFailure` alone ([[WithdrawalsRule]]), and at Dijkstra the case has no value
  * ([[CertsWithdrawals]]).
  */
enum ConwayCertsPredFailure[E <: EraTag]:
  case WithdrawalsNotInRewardsCERTS(withdrawals: CertsWithdrawals[E])
  case CertFailure(failure: CertPredFailure[E])

/** `EraRuleFailure "DELEG" era`: Conway's is its own (`Conway/Rules/Deleg.hs:116`, cardano-ledger
  * f649f975), and Dijkstra's is Conway's (`Dijkstra/Rules/Deleg.hs:13`, cardano-ledger 9df9f98c).
  * Each era adds its case with its rule.
  */
type DelegPredFailure[E <: EraTag] = E match
  case Conway   => ConwayDelegPredFailure[Conway]
  case Dijkstra => ConwayDelegPredFailure[Dijkstra]

/** `EraRuleFailure "POOL" era`: Conway's is Shelley's (`Conway/Rules/Cert.hs`, `Embed (Shelley.POOL
  * era) (CERT era)`, `:236-246`, cardano-ledger f649f975); Dijkstra's is its own
  * (`Dijkstra/Rules/Pool.hs:138`, cardano-ledger 9df9f98c).
  */
type PoolPredFailure[E <: EraTag] = E match
  case Conway   => ShelleyPoolPredFailure[Conway]
  case Dijkstra => DijkstraPoolPredFailure[Dijkstra]

/** `EraRuleFailure "GOVCERT" era`: Conway's is its own (`Conway/Rules/GovCert.hs:120`,
  * cardano-ledger f649f975), and so is Dijkstra's (`Dijkstra/Rules/GovCert.hs:65`, cardano-ledger
  * 9df9f98c).
  */
type GovCertPredFailure[E <: EraTag] = E match
  case Conway   => ConwayGovCertPredFailure[Conway]
  case Dijkstra => DijkstraGovCertPredFailure[Dijkstra]

/** `EraRuleFailure "CERT" era`: Conway's is its own (`Conway/Rules/Cert.hs:107`, cardano-ledger
  * f649f975), and Dijkstra's is Conway's (`Dijkstra/Rules/Cert.hs:30`, cardano-ledger 9df9f98c).
  */
type CertPredFailure[E <: EraTag] = E match
  case Conway   => ConwayCertPredFailure[Conway]
  case Dijkstra => ConwayCertPredFailure[Dijkstra]

/** `EraRuleFailure "CERTS" era`: Conway's is its own (`Conway/Rules/Certs.hs:121`, cardano-ledger
  * f649f975), and Dijkstra's is Conway's (`Dijkstra/Rules/Certs.hs:20`, cardano-ledger 9df9f98c).
  */
type CertsPredFailure[E <: EraTag] = E match
  case Conway   => ConwayCertsPredFailure[Conway]
  case Dijkstra => ConwayCertsPredFailure[Dijkstra]

/** `data PoolEvent era` (`Shelley/Rules/Pool.hs:146-149`, cardano-ledger f649f975): a pool
  * registered, or registered again.
  */
enum PoolEvent[E <: EraTag]:
  case RegisterPool(pool: KeyHash[StakePool])
  case ReregisterPool(pool: KeyHash[StakePool])

/** `EraRuleEvent "POOL" era`: Conway's is Shelley's `PoolEvent` (`Shelley/Rules/Pool.hs:120`,
  * cardano-ledger f649f975), and so is Dijkstra's (`Dijkstra/Rules/Pool.hs:140`, cardano-ledger
  * 9df9f98c). Named apart from the data type it reduces to.
  */
type PoolRuleEvent[E <: EraTag] = E match
  case Conway   => PoolEvent[Conway]
  case Dijkstra => PoolEvent[Dijkstra]

/** `EraRuleEvent "DELEG" era`: Conway's is void (`VoidEraRule`, `Conway/Rules/Deleg.hs:118`,
  * cardano-ledger f649f975), and so is Dijkstra's (`Dijkstra/Rules/Deleg.hs:15`, cardano-ledger
  * 9df9f98c): DELEG tells nothing, and the type has no value.
  */
type DelegEvent[E <: EraTag] = E match
  case Conway   => Nothing
  case Dijkstra => Nothing

/** `EraRuleEvent "GOVCERT" era`: Conway's is void (`Conway/Rules/GovCert.hs:122`, cardano-ledger
  * f649f975), and so is Dijkstra's (`Dijkstra/Rules/GovCert.hs:67`, cardano-ledger 9df9f98c).
  */
type GovCertEvent[E <: EraTag] = E match
  case Conway   => Nothing
  case Dijkstra => Nothing

/** `data ConwayCertEvent era` (`Conway/Rules/Cert.hs:143-147`, cardano-ledger f649f975). */
enum ConwayCertEvent[E <: EraTag]:
  case DelegEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.DelegEvent[E])
  case PoolEvent(event: PoolRuleEvent[E])
  case GovCertEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.GovCertEvent[E])

/** `EraRuleEvent "CERT" era`: Conway's is its own (`Conway/Rules/Cert.hs:109`, cardano-ledger
  * f649f975), and Dijkstra's is Conway's (`Dijkstra/Rules/Cert.hs:32`, cardano-ledger 9df9f98c).
  */
type CertEvent[E <: EraTag] = E match
  case Conway   => ConwayCertEvent[Conway]
  case Dijkstra => ConwayCertEvent[Dijkstra]

/** `newtype ConwayCertsEvent era` (`Conway/Rules/Certs.hs:151`, cardano-ledger f649f975). */
enum ConwayCertsEvent[E <: EraTag]:
  case CertEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.CertEvent[E])

/** `EraRuleEvent "CERTS" era`: Conway's is its own (`Conway/Rules/Certs.hs:123`, cardano-ledger
  * f649f975), and Dijkstra's is Conway's (`Dijkstra/Rules/Certs.hs:22`, cardano-ledger 9df9f98c).
  */
type CertsEvent[E <: EraTag] = E match
  case Conway   => ConwayCertsEvent[Conway]
  case Dijkstra => ConwayCertsEvent[Dijkstra]

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

  /** `Dijkstra/Rules/Deleg.hs:17` (cardano-ledger 9df9f98c): the identity. */
  given dijkstra: InjectDeleg[Dijkstra] with
    def injectFailure(failure: ConwayDelegPredFailure[Dijkstra]): DelegPredFailure[Dijkstra] =
      failure

/** `InjectRuleFailure "DELEG" f era` for a failure `F` other than Conway's own ([[InjectDeleg]]):
  * how era `E` 's DELEG reports it. One instance per era and failure type, in the companion.
  */
trait InjectDelegFailure[F, E <: EraTag]:
  def injectFailure(failure: F): DelegPredFailure[E]

object InjectDelegFailure:
  /** `Dijkstra/Rules/Deleg.hs:19-20` (cardano-ledger 9df9f98c): Shelley's
    * `AccountAlreadyRegistered` as Conway's `DelegAccountAlreadyRegistered`
    * (`StakeKeyRegisteredDELEG` here, tag 2). Conway's era types follow the 11.1 build's ledger,
    * which has no such instance.
    */
  given dijkstra: InjectDelegFailure[AccountAlreadyRegistered[Dijkstra], Dijkstra] with
    def injectFailure(failure: AccountAlreadyRegistered[Dijkstra]): DelegPredFailure[Dijkstra] =
      ConwayDelegPredFailure.StakeKeyRegisteredDELEG(failure.credential)

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

  /** `Dijkstra/Rules/Pool.hs:144-153` (cardano-ledger 9df9f98c): each of Shelley's failures as
    * Dijkstra's constructor of the same name and fields, and the event as it is.
    */
  given dijkstra: InjectPool[Dijkstra] with
    def injectFailure(failure: ShelleyPoolPredFailure[Dijkstra]): PoolPredFailure[Dijkstra] =
      DijkstraPoolPredFailure.shelleyToDijkstraPoolPredFailure(failure)
    def injectEvent(event: PoolEvent[Dijkstra]): PoolRuleEvent[Dijkstra] = event

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

  /** `Dijkstra/Rules/GovCert.hs:71-72` (cardano-ledger 9df9f98c):
    * `conwayToDijkstraGovCertPredFailure`.
    */
  given dijkstra: InjectGovCert[Dijkstra] with
    def injectFailure(failure: ConwayGovCertPredFailure[Dijkstra]): GovCertPredFailure[Dijkstra] =
      DijkstraGovCertPredFailure.conwayToDijkstraGovCertPredFailure(failure)

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

  /** `Dijkstra/Rules/Cert.hs:34` (cardano-ledger 9df9f98c): the identity. */
  given dijkstra: InjectCertFailure[ConwayCertPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ConwayCertPredFailure[Dijkstra]): CertPredFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/Cert.hs:36-37` (cardano-ledger 9df9f98c): `DelegFailure`. */
  given dijkstraDeleg: InjectCertFailure[ConwayDelegPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ConwayDelegPredFailure[Dijkstra]): CertPredFailure[Dijkstra] =
      ConwayCertPredFailure.DelegFailure(failure)

  /** `Dijkstra/Rules/Cert.hs:39-40` (cardano-ledger 9df9f98c): `PoolFailure` of POOL's injection of
    * Shelley's failure.
    */
  given dijkstraShelleyPool: InjectCertFailure[ShelleyPoolPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ShelleyPoolPredFailure[Dijkstra]): CertPredFailure[Dijkstra] =
      ConwayCertPredFailure.PoolFailure(InjectPool.dijkstra.injectFailure(failure))

  /** `Dijkstra/Rules/Cert.hs:42-43` (cardano-ledger 9df9f98c): `PoolFailure`. */
  given dijkstraPool: InjectCertFailure[DijkstraPoolPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraPoolPredFailure[Dijkstra]): CertPredFailure[Dijkstra] =
      ConwayCertPredFailure.PoolFailure(failure)

  /** `Dijkstra/Rules/Cert.hs:45-46` (cardano-ledger 9df9f98c): `GovCertFailure`. */
  given dijkstraGovCert: InjectCertFailure[DijkstraGovCertPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: DijkstraGovCertPredFailure[Dijkstra]): CertPredFailure[Dijkstra] =
      ConwayCertPredFailure.GovCertFailure(failure)

  /** `Dijkstra/Rules/Cert.hs:48-49` (cardano-ledger 9df9f98c): `GovCertFailure` of GOVCERT's
    * injection of Conway's failure.
    */
  given dijkstraConwayGovCert: InjectCertFailure[ConwayGovCertPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ConwayGovCertPredFailure[Dijkstra]): CertPredFailure[Dijkstra] =
      ConwayCertPredFailure.GovCertFailure(InjectGovCert.dijkstra.injectFailure(failure))

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

  /** `Dijkstra/Rules/Certs.hs:24` (cardano-ledger 9df9f98c): the identity. */
  given dijkstra: InjectCertsFailure[ConwayCertsPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: ConwayCertsPredFailure[Dijkstra]): CertsPredFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/Certs.hs:26-42` (cardano-ledger 9df9f98c): CERT's failure, and each of the
    * rules below it through CERT's injection, wrapped in `CertFailure`.
    */
  given dijkstraCert[F](using
      inject: InjectCertFailure[F, Dijkstra]
  ): InjectCertsFailure[F, Dijkstra] with
    def injectFailure(failure: F): CertsPredFailure[Dijkstra] =
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

  /** Dijkstra's (`Dijkstra/Rules/Certs.hs:55-81` and `Dijkstra/Rules/Cert.hs:114-121`,
    * cardano-ledger 9df9f98c): `DelegFailure`, `PoolFailure` with `PoolEvent`, and
    * `GovCertFailure`.
    */
  given dijkstra: EmbedInCert[Dijkstra] with
    def wrapDelegFailed(failure: DelegPredFailure[Dijkstra]): CertPredFailure[Dijkstra] =
      ConwayCertPredFailure.DelegFailure(failure)
    def wrapPoolFailed(failure: PoolPredFailure[Dijkstra]): CertPredFailure[Dijkstra] =
      ConwayCertPredFailure.PoolFailure(failure)
    def wrapPoolEvent(event: PoolRuleEvent[Dijkstra]): CertEvent[Dijkstra] =
      ConwayCertEvent.PoolEvent(event)
    def wrapGovCertFailed(failure: GovCertPredFailure[Dijkstra]): CertPredFailure[Dijkstra] =
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

  /** Dijkstra's (`Dijkstra/Rules/Certs.hs:44-53`, cardano-ledger 9df9f98c). */
  given dijkstra: EmbedCertInCerts[Dijkstra] with
    def wrapFailed(failure: CertPredFailure[Dijkstra]): CertsPredFailure[Dijkstra] =
      ConwayCertsPredFailure.CertFailure(failure)
    def wrapEvent(event: CertEvent[Dijkstra]): CertsEvent[Dijkstra] =
      ConwayCertsEvent.CertEvent(event)

object ConwayCertsPredFailure:
  given [E <: EraTag]: CanEqual[ConwayCertsPredFailure[E], ConwayCertsPredFailure[E]] =
    CanEqual.derived

object ConwayCertPredFailure:
  given [E <: EraTag]: CanEqual[ConwayCertPredFailure[E], ConwayCertPredFailure[E]] =
    CanEqual.derived

object ConwayDelegPredFailure:
  given [E <: EraTag]: CanEqual[ConwayDelegPredFailure[E], ConwayDelegPredFailure[E]] =
    CanEqual.derived

object ShelleyPoolPredFailure:
  given [E <: EraTag]: CanEqual[ShelleyPoolPredFailure[E], ShelleyPoolPredFailure[E]] =
    CanEqual.derived

object ConwayGovCertPredFailure:
  given [E <: EraTag]: CanEqual[ConwayGovCertPredFailure[E], ConwayGovCertPredFailure[E]] =
    CanEqual.derived
