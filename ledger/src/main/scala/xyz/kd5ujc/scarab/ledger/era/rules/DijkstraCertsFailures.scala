// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.data.{NonEmptyMap, NonEmptySet}
import xyz.kd5ujc.scarab.crypto.bls.BlsError
import xyz.kd5ujc.scarab.ledger.{GovCertFailure as FlatGovCertFailure, Mismatch}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraBlsKey,
  DijkstraCertFailure,
  DijkstraCertsFailure,
  DijkstraPoolFailure
}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.AccountBalanceInterval
import xyz.kd5ujc.scarab.ledger.rules.Convert
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  BlsKey,
  ColdCommittee,
  Coin,
  Credential,
  DRepRole,
  Dijkstra,
  EpochNo,
  EraTag,
  KeyHash,
  Network,
  StakePool,
  VRFVerKeyHash
}

// The failures and events of Dijkstra's certificate rules and of ENTITIES as the reference declares
// them (cardano-ledger 9df9f98c, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/{Pool,
// GovCert,Entities}.hs`), with their injections and embeddings. Dijkstra's CERTS, CERT and DELEG
// report Conway's types, whose cases at Dijkstra are in `CertsFailures.scala`.

/** `data DijkstraPoolPredFailure era` (`Dijkstra/Rules/Pool.hs:57-85`, cardano-ledger 9df9f98c),
  * its constructors in the order of their tags (0, 1, then 3 to 7, `:87-105`: tag 2 is unused):
  * Shelley's, and a voting key whose proof of possession does not verify.
  */
enum DijkstraPoolPredFailure[E <: EraTag]:
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

  /** The pool and the voting key it registers, whose proof of possession does not verify. */
  case BlsKeyInvalidProofOfPossession(pool: KeyHash[StakePool], key: BlsKey)

/** Why Dijkstra's POOL reached no verdict on a registration: what the reference's verifier has no
  * outcome for. Not a verdict of the rule.
  */
enum PoolDefect:
  /** The proof of possession of `pool` 's voting key could not be checked
    * (`verifyPossessionProofDSIGN`, `Dijkstra/Rules/Pool.hs:239-247`, cardano-ledger 9df9f98c): the
    * BLS12-381 library failed, or the key or the proof is not a point the decoder admits. A decoded
    * certificate cannot hold such a point, as the decoder refuses it before POOL, but the library
    * can still fail on one it holds. A proof that does not verify is the predicate failure
    * `BlsKeyInvalidProofOfPossession`, not this.
    */
  case BlsKeyUnverifiable(pool: KeyHash[StakePool], error: BlsError)

object PoolDefect:
  given CanEqual[PoolDefect, PoolDefect] = CanEqual.derived

object DijkstraPoolPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraPoolPredFailure[E], DijkstraPoolPredFailure[E]] =
    CanEqual.derived

  /** `injectFailure` of `InjectRuleFailure "POOL" ShelleyPoolPredFailure DijkstraEra`
    * (`Dijkstra/Rules/Pool.hs:146-153`, cardano-ledger 9df9f98c): each constructor as Dijkstra's of
    * the same name and fields.
    */
  def shelleyToDijkstraPoolPredFailure[E <: EraTag](
      failure: ShelleyPoolPredFailure[E]
  ): DijkstraPoolPredFailure[E] = failure match
    case ShelleyPoolPredFailure.StakePoolNotRegisteredOnKeyPOOL(p) =>
      StakePoolNotRegisteredOnKeyPOOL(p)
    case ShelleyPoolPredFailure.StakePoolRetirementWrongEpochPOOL(current, latest) =>
      StakePoolRetirementWrongEpochPOOL(current, latest)
    case ShelleyPoolPredFailure.StakePoolCostTooLowPOOL(m) => StakePoolCostTooLowPOOL(m)
    case ShelleyPoolPredFailure.WrongNetworkPOOL(m, p)     => WrongNetworkPOOL(m, p)
    case ShelleyPoolPredFailure.PoolMedataHashTooBig(p, s) => PoolMedataHashTooBig(p, s)
    case ShelleyPoolPredFailure.VRFKeyHashAlreadyRegistered(p, v) =>
      VRFKeyHashAlreadyRegistered(p, v)

  /** POOL's failure in the flat Dijkstra form (`DijkstraPoolFailure`), glue the comparisons with
    * the flat rules and the oracle read ([[DijkstraCertsStored]]). It is one-way.
    */
  def toStored(failure: DijkstraPoolPredFailure[Dijkstra]): DijkstraPoolFailure = failure match
    case StakePoolNotRegisteredOnKeyPOOL(p) =>
      DijkstraPoolFailure.StakePoolNotRegisteredOnKey(p.bytes)
    case StakePoolRetirementWrongEpochPOOL(current, latest) =>
      DijkstraPoolFailure.StakePoolRetirementWrongEpoch(epochs(current), epochs(latest))
    case StakePoolCostTooLowPOOL(m) => DijkstraPoolFailure.StakePoolCostTooLow(coins(m))
    case WrongNetworkPOOL(m, p) =>
      DijkstraPoolFailure.WrongNetworkPool(Mismatch(m.supplied.wire, m.expected.wire), p.bytes)
    case PoolMedataHashTooBig(p, size) => DijkstraPoolFailure.PoolMedataHashTooBig(p.bytes, size)
    case VRFKeyHashAlreadyRegistered(p, v) =>
      DijkstraPoolFailure.VRFKeyHashAlreadyRegistered(p.bytes, v.bytes)
    case BlsKeyInvalidProofOfPossession(p, key) =>
      DijkstraPoolFailure.BlsKeyInvalidProofOfPossession(
        p.bytes,
        DijkstraBlsKey(key.key, key.proofOfPossession)
      )

/** `data DijkstraGovCertPredFailure era` (`Dijkstra/Rules/GovCert.hs:53-63`, cardano-ledger
  * 9df9f98c), its constructors in the order of their tags (0 to 5, `:76-84`): Conway's, renamed,
  * which Dijkstra's encoder writes with a `Mismatch` as one item (`To mm`).
  */
enum DijkstraGovCertPredFailure[E <: EraTag]:
  case DijkstraDRepAlreadyRegistered(credential: Credential[DRepRole])
  case DijkstraDRepNotRegistered(credential: Credential[DRepRole])

  /** The deposit (supplied) and the parameters' (expected). */
  case DijkstraDRepIncorrectDeposit(mismatch: Mismatch[Coin])
  case DijkstraCommitteeHasPreviouslyResigned(credential: Credential[ColdCommittee])

  /** The refund (supplied) and the DRep's deposit (expected). */
  case DijkstraDRepIncorrectRefund(mismatch: Mismatch[Coin])
  case DijkstraCommitteeIsUnknown(credential: Credential[ColdCommittee])

object DijkstraGovCertPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraGovCertPredFailure[E], DijkstraGovCertPredFailure[E]] =
    CanEqual.derived

  /** `conwayToDijkstraGovCertPredFailure` (`Dijkstra/Rules/GovCert.hs:118-126`, cardano-ledger
    * 9df9f98c): each constructor as Dijkstra's of the same fields.
    */
  def conwayToDijkstraGovCertPredFailure[E <: EraTag](
      failure: ConwayGovCertPredFailure[E]
  ): DijkstraGovCertPredFailure[E] = failure match
    case ConwayGovCertPredFailure.ConwayDRepAlreadyRegistered(c) => DijkstraDRepAlreadyRegistered(c)
    case ConwayGovCertPredFailure.ConwayDRepNotRegistered(c)     => DijkstraDRepNotRegistered(c)
    case ConwayGovCertPredFailure.ConwayDRepIncorrectDeposit(m)  => DijkstraDRepIncorrectDeposit(m)
    case ConwayGovCertPredFailure.ConwayCommitteeHasPreviouslyResigned(c) =>
      DijkstraCommitteeHasPreviouslyResigned(c)
    case ConwayGovCertPredFailure.ConwayDRepIncorrectRefund(m) => DijkstraDRepIncorrectRefund(m)
    case ConwayGovCertPredFailure.ConwayCommitteeIsUnknown(c)  => DijkstraCommitteeIsUnknown(c)

  /** GOVCERT's failure in the flat form the flat Dijkstra rules give, Conway's
    * ([[DijkstraCertsStored]]).
    */
  def toStored(failure: DijkstraGovCertPredFailure[Dijkstra]): FlatGovCertFailure = failure match
    case DijkstraDRepAlreadyRegistered(c) =>
      FlatGovCertFailure.DRepAlreadyRegistered(Convert.back(c))
    case DijkstraDRepNotRegistered(c)    => FlatGovCertFailure.DRepNotRegistered(Convert.back(c))
    case DijkstraDRepIncorrectDeposit(m) => FlatGovCertFailure.DRepIncorrectDeposit(coins(m))
    case DijkstraCommitteeHasPreviouslyResigned(c) =>
      FlatGovCertFailure.CommitteeHasPreviouslyResigned(Convert.back(c))
    case DijkstraDRepIncorrectRefund(m) => FlatGovCertFailure.DRepIncorrectRefund(coins(m))
    case DijkstraCommitteeIsUnknown(c)  => FlatGovCertFailure.CommitteeIsUnknown(Convert.back(c))

/** `InjectRuleFailure "POOL" DijkstraPoolPredFailure era` and `InjectRuleEvent "POOL" PoolEvent
  * era` (`Dijkstra/Rules/Pool.hs:142-144`, cardano-ledger 9df9f98c): how era `E` 's POOL reports
  * the failures and events of Dijkstra's transition. Only this package makes one.
  */
sealed trait InjectDijkstraPool[E <: EraTag]:
  def injectFailure(failure: DijkstraPoolPredFailure[E]): PoolPredFailure[E]
  def injectEvent(event: PoolEvent[E]): PoolRuleEvent[E]

object InjectDijkstraPool:
  /** The identities. */
  given dijkstra: InjectDijkstraPool[Dijkstra] with
    def injectFailure(failure: DijkstraPoolPredFailure[Dijkstra]): PoolPredFailure[Dijkstra] =
      failure
    def injectEvent(event: PoolEvent[Dijkstra]): PoolRuleEvent[Dijkstra] = event

/** CERTS's failures of Dijkstra in the flat Dijkstra form (`DijkstraCertsFailure`), which the flat
  * rules give and the oracle suite writes: one-way glue for the comparisons, until the failures of
  * the era types have their own encoders (#796).
  */
object DijkstraCertsStored:
  /** The flat form of a failure of Dijkstra's CERTS. Its one constructor at 9df9f98c is
    * `CertFailure`: the 11.1 build's withdrawals' case has no value at Dijkstra
    * ([[CertsWithdrawals]]).
    */
  def toStored(failure: ConwayCertsPredFailure[Dijkstra]): DijkstraCertsFailure = failure match
    case ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS(ws) => ws
    case ConwayCertsPredFailure.CertFailure(f)                   => DijkstraCertsFailure(cert(f))

  /** CERT's failure in the flat Dijkstra form. */
  def cert(failure: ConwayCertPredFailure[Dijkstra]): DijkstraCertFailure = failure match
    case ConwayCertPredFailure.DelegFailure(f) =>
      DijkstraCertFailure.Deleg(ConwayDelegPredFailure.toStored(f))
    case ConwayCertPredFailure.PoolFailure(f) =>
      DijkstraCertFailure.Pool(DijkstraPoolPredFailure.toStored(f))
    case ConwayCertPredFailure.GovCertFailure(f) =>
      DijkstraCertFailure.GovCert(DijkstraGovCertPredFailure.toStored(f))

/** `data EntitiesPredFailure era` (`Dijkstra/Rules/Entities.hs:107-133`, cardano-ledger 9df9f98c),
  * its constructors in the order of their tags (0 to 13, `:154-169`). The withdrawals and the
  * direct deposits are the reference's `Withdrawals` and `DirectDeposits` (`Map AccountAddress
  * Coin`, `Address.hs:980, 997`): the body's withdrawals, and each account of its direct deposits
  * read into `AccountAddress`.
  */
enum EntitiesPredFailure[E <: EraTag]:
  case CertsFailure(failure: CertsPredFailure[E])

  /** The node's network, and the withdrawals' accounts on another. */
  case WithdrawalAddressesWithWrongNetwork(expected: Network, accounts: NonEmptySet[AccountAddress])
  case WithdrawalAccountsMissing(withdrawals: Map[AccountAddress, Coin])
  case WithdrawalAccountsMissingFromOriginal(withdrawals: Map[AccountAddress, Coin])

  /** Each withdrawal (supplied) and its account's balance (expected). */
  case WithdrawalAmountsInexactInLegacyMode(mismatches: NonEmptyMap[AccountAddress, Mismatch[Coin]])

  /** Each withdrawal summed over the batch (supplied) and its account's original balance
    * (expected).
    */
  case WithdrawalAmountsExceedingOriginalBalance(
      mismatches: NonEmptyMap[AccountAddress, Mismatch[Coin]]
  )

  /** The node's network, and the direct deposits' accounts on another. */
  case DirectDepositAddressesWithWrongNetwork(
      expected: Network,
      accounts: NonEmptySet[AccountAddress]
  )
  case DirectDepositAccountsMissing(deposits: Map[AccountAddress, Coin])
  case WrongNetworkInAccountBalanceIntervals(
      expected: Network,
      accounts: NonEmptySet[AccountAddress]
  )
  case MissingAccountsInAccountBalanceIntervals(
      intervals: NonEmptyMap[AccountAddress, AccountBalanceInterval]
  )

  /** Each account's balance and the interval it is outside of. */
  case BalancesOutsideAccountBalanceIntervals(
      outside: NonEmptyMap[AccountAddress, (Coin, AccountBalanceInterval)]
  )
  case WrongNetworkInStartingAccountBalanceIntervals(
      expected: Network,
      accounts: NonEmptySet[AccountAddress]
  )
  case MissingAccountsInStartingAccountBalanceIntervals(
      intervals: NonEmptyMap[AccountAddress, AccountBalanceInterval]
  )

  /** Each account's original balance and the interval it is outside of. */
  case BalancesOutsideStartingAccountBalanceIntervals(
      outside: NonEmptyMap[AccountAddress, (Coin, AccountBalanceInterval)]
  )

object EntitiesPredFailure:
  given [E <: EraTag]: CanEqual[EntitiesPredFailure[E], EntitiesPredFailure[E]] = CanEqual.derived

/** `newtype EntitiesEvent era` (`Dijkstra/Rules/Entities.hs:194`, cardano-ledger 9df9f98c). */
enum EntitiesEvent[E <: EraTag]:
  case CertsEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.CertsEvent[E])

/** `EraRuleFailure "ENTITIES" era`: Dijkstra's (`Dijkstra/Rules/Entities.hs:201`, cardano-ledger
  * 9df9f98c), the first era with the rule.
  */
type EntitiesRuleFailure[E <: EraTag] = E match
  case Dijkstra => EntitiesPredFailure[Dijkstra]

/** `EraRuleEvent "ENTITIES" era` (`Dijkstra/Rules/Entities.hs:203`, cardano-ledger 9df9f98c). */
type EntitiesRuleEvent[E <: EraTag] = E match
  case Dijkstra => EntitiesEvent[Dijkstra]

/** `InjectRuleFailure "ENTITIES" f era` (`Dijkstra/Rules/Entities.hs:205-232`, cardano-ledger
  * 9df9f98c): how era `E` 's ENTITIES reports a failure `F` of its own or of a rule below it. One
  * instance per era and failure type, in the companion.
  *
  * Two of the reference's instances are partial. Conway's LEDGER failure is impossible on every
  * constructor (`conwayToDijkstraEntitiesPredFailure`, `:413-426`), and no predicate ENTITIES runs
  * reports one, so it has no instance. Shelley's UTXO failure is impossible on every constructor
  * but `WrongNetworkWithdrawal` (`shelleyUtxoToDijkstraEntitiesPredFailure`, `:428-443`), which the
  * one predicate that reports it declares; its injection reads the accounts into the reference's
  * `AccountAddress`, so it is a function of the rule
  * ([[ENTITIES.shelleyUtxoToDijkstraEntitiesPredFailure]]), not an instance.
  */
trait InjectEntitiesFailure[F, E <: EraTag]:
  def injectFailure(failure: F): EntitiesRuleFailure[E]

object InjectEntitiesFailure:
  /** `Dijkstra/Rules/Entities.hs:205` (cardano-ledger 9df9f98c): the identity. */
  given dijkstra: InjectEntitiesFailure[EntitiesPredFailure[Dijkstra], Dijkstra] with
    def injectFailure(failure: EntitiesPredFailure[Dijkstra]): EntitiesRuleFailure[Dijkstra] =
      failure

  /** `Dijkstra/Rules/Entities.hs:207-226` (cardano-ledger 9df9f98c): CERTS's failure, and each of
    * the rules below it through CERTS's injection, wrapped in `CertsFailure`.
    */
  given dijkstraCerts[F](using
      inject: InjectCertsFailure[F, Dijkstra]
  ): InjectEntitiesFailure[F, Dijkstra] with
    def injectFailure(failure: F): EntitiesRuleFailure[Dijkstra] =
      EntitiesPredFailure.CertsFailure(inject.injectFailure(failure))

/** `Embed (Conway.CERTS era) (ENTITIES era)` (`Dijkstra/Rules/Entities.hs:445-453`, cardano-ledger
  * 9df9f98c): `CertsFailure` and `CertsEvent`.
  */
trait EmbedCertsInEntities[E <: EraTag]:
  def wrapFailed(failure: CertsPredFailure[E]): EntitiesRuleFailure[E]
  def wrapEvent(event: CertsEvent[E]): EntitiesRuleEvent[E]

object EmbedCertsInEntities:
  given dijkstra: EmbedCertsInEntities[Dijkstra] with
    def wrapFailed(failure: CertsPredFailure[Dijkstra]): EntitiesRuleFailure[Dijkstra] =
      EntitiesPredFailure.CertsFailure(failure)
    def wrapEvent(event: CertsEvent[Dijkstra]): EntitiesRuleEvent[Dijkstra] =
      EntitiesEvent.CertsEvent(event)
