// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.Monoid
import monocle.Lens
import xyz.kd5ujc.scarab.ledger.state.{
  Accounts,
  CertState,
  Coin,
  CompactCoin,
  Conway,
  Credential,
  Dijkstra,
  DRep,
  DRepRole,
  DRepState,
  DState,
  EraTag,
  KeyHash,
  PState,
  StakePool,
  StakePoolState,
  Staking,
  VState
}

/** The deposits the system must pay back one day (`data Obligations`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/State/CertState.hs:427-433`): the stake keys', the
  * pools', the DReps' and the proposals'. Its monoid adds each field (`CertState.hs:440-450`).
  */
final case class Obligations(oblStake: Coin, oblPool: Coin, oblDRep: Coin, oblProposal: Coin)

object Obligations:
  given CanEqual[Obligations, Obligations] = CanEqual.derived

  given Monoid[Obligations] with
    val empty: Obligations = Obligations(Coin.zero, Coin.zero, Coin.zero, Coin.zero)
    def combine(x: Obligations, y: Obligations): Obligations =
      Obligations(
        Coin(x.oblStake.value + y.oblStake.value),
        Coin(x.oblPool.value + y.oblPool.value),
        Coin(x.oblDRep.value + y.oblDRep.value),
        Coin(x.oblProposal.value + y.oblProposal.value)
      )

  /** `sumObligation` (`CertState.hs:437-438`, cardano-ledger f649f975). */
  def sumObligation(x: Obligations): Coin =
    Coin(x.oblStake.value + x.oblPool.value + x.oblDRep.value + x.oblProposal.value)

/** The certificate state of era `E`, which DELEG and POOL keep (`class EraCertState`,
  * `State/CertState.hs:358-401`, cardano-ledger f649f975). Its superclass `EraAccounts era` is a
  * member ([[accountsOf]]), so an era has one instance of each class. The record is the state's
  * family ([[xyz.kd5ujc.scarab.ledger.state.CertState]]).
  */
trait EraCertState[E <: EraTag]:
  /** The accounts' class (the superclass `EraAccounts era`). */
  def accountsOf: EraAccounts[E]

  def certDStateL: Lens[CertState[E], DState[Accounts[E]]]
  def certPStateL: Lens[CertState[E], PState]

  /** `obligationCertState`: the deposits the state holds, whatever the parameters now ask. */
  def obligationCertState(certState: CertState[E]): Obligations

  /** `certsTotalDepositsTxBody`: the deposits the certificates of a top-level body pay, its
    * contribution to the deposit pot.
    */
  def certsTotalDepositsTxBody(
      pp: PParams[E],
      certState: CertState[E],
      body: TxBody[TxLevel.Top, E]
  )(using EraTxBody[E]): Coin

  /** `certsTotalRefundsTxBody`: the refunds the certificates of a body collect, given the accounts
    * that hold their deposits.
    */
  def certsTotalRefundsTxBody[L <: TxLevel](
      pp: PParams[E],
      accounts: Accounts[E],
      body: TxBody[L, E]
  )(using b: EraTxBody[E], l: b.Level[L]): Coin

/** The certificate state from Conway, with the voting state (`class ConwayEraCertState`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/State/CertState.hs:83-84`, cardano-ledger f649f975),
  * whose accounts are Conway's (the superclass `ConwayEraAccounts era`).
  */
trait ConwayEraCertState[E <: EraTag] extends EraCertState[E]:
  override def accountsOf: ConwayEraAccounts[E]

  def certVStateL: Lens[CertState[E], VState]

/** The instances of each era, the one place that lists the eras having each certificate-state
  * class, and the functions of each era's `State/CertState.hs` (cardano-ledger f649f975) over the
  * class.
  */
object EraCertState:
  given conway: ConwayEraCertState[Conway] =
    xyz.kd5ujc.scarab.ledger.era.conway.ConwayState.certState
  given dijkstra: ConwayEraCertState[Dijkstra] =
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraState.certState

  /** `shelleyObligationCertState` (`eras/shelley/impl/src/Cardano/Ledger/Shelley/State/
    * CertState.hs:77-84`, cardano-ledger f649f975): the accounts' deposits and the pools', each
    * summed as `CompactForm Coin`.
    */
  def shelleyObligationCertState[E <: EraTag](certState: CertState[E])(using
      c: EraCertState[E]
  ): Obligations =
    Obligations(
      EraAccounts.sumDepositsAccounts[E](c.certDStateL.get(certState).accounts)(using c.accountsOf),
      CompactCoin.sum(c.certPStateL.get(certState).stakePools.valuesIterator.map(_.deposit)).toCoin,
      Coin.zero,
      Coin.zero
    )

  /** `conwayObligationCertState` (`Conway/State/CertState.hs:111-116`, cardano-ledger f649f975):
    * Shelley's, with the DReps' deposits.
    */
  def conwayObligationCertState[E <: EraTag](certState: CertState[E])(using
      c: ConwayEraCertState[E]
  ): Obligations =
    shelleyObligationCertState[E](certState).copy(oblDRep =
      CompactCoin.sum(c.certVStateL.get(certState).dreps.valuesIterator.map(_.deposit)).toCoin
    )

  /** `shelleyCertsTotalRefundsTxBody` (`eras/shelley/impl/src/Cardano/Ledger/Shelley/State/
    * CertState.hs:91-94`, cardano-ledger f649f975): the body's refunds, each credential's deposit
    * looked up in `accounts`.
    */
  def shelleyCertsTotalRefundsTxBody[E <: EraTag, L <: TxLevel](
      pp: PParams[E],
      accounts: Accounts[E],
      body: TxBody[L, E]
  )(using c: EraCertState[E], b: EraTxBody[E], l: b.Level[L]): Coin =
    b.getTotalRefundsTxBody(
      pp,
      EraAccounts.lookupAccountDeposit[E](_, accounts)(using c.accountsOf),
      body
    )

  /** `conwayCertsTotalDepositsTxBody` (`Conway/State/CertState.hs:118-121`, cardano-ledger
    * f649f975): the body's deposits, a pool registered when the state's pools have it.
    */
  def conwayCertsTotalDepositsTxBody[E <: EraTag](
      pp: PParams[E],
      certState: CertState[E],
      body: TxBody[TxLevel.Top, E]
  )(using c: ConwayEraCertState[E], b: EraTxBody[E]): Coin =
    b.getTotalDepositsTxBody(pp, c.certPStateL.get(certState).stakePools.contains, body)

  /** `unDelegReDelegStakePool` (`State/CertState.hs:281-300`, cardano-ledger f649f975): the account
    * of `credential`, as `accountState` was, leaves the pool it delegated to and joins `newPool` 's
    * delegators; nothing changes where the pool is the same.
    */
  def unDelegReDelegStakePool[E <: EraTag](
      credential: Credential[Staking],
      accountState: AccountState[E],
      newPool: Option[KeyHash[StakePool]],
      pState: PState
  )(using a: EraAccounts[E]): PState =
    def addNewDelegation(
        pools: scala.collection.immutable.SortedMap[KeyHash[StakePool], StakePoolState]
    ) =
      newPool.fold(pools)(pool =>
        pools.updatedWith(pool)(_.map(StakePoolState.delegators.modify(_ + credential)))
      )
    PState.stakePools.modify { pools =>
      a.stakePoolDelegationAccountStateL.get(accountState) match
        case None                                       => addNewDelegation(pools)
        case Some(current) if newPool.contains(current) => pools
        case Some(current) =>
          addNewDelegation(
            pools.updatedWith(current)(_.map(StakePoolState.delegators.modify(_ - credential)))
          )
    }(pState)

/** The functions of Conway's voting state (`eras/conway/impl/src/Cardano/Ledger/Conway/State/`,
  * cardano-ledger f649f975) over the classes.
  */
object ConwayEraCertState:
  /** `dRepToCred` (`libs/cardano-ledger-core/src/Cardano/Ledger/DRep.hs:121-124`, cardano-ledger
    * f649f975): the credential of a DRep that has one, not of the two predefined.
    */
  def dRepToCred(drep: DRep): Option[Credential[DRepRole]] = drep match
    case DRep.DRepKeyHash(h)    => Some(Credential.KeyHashObj[DRepRole](h))
    case DRep.DRepScriptHash(h) => Some(Credential.ScriptHashObj[DRepRole](h))
    case DRep.DRepAlwaysAbstain | DRep.DRepAlwaysNoConfidence => None

  /** `unDelegReDelegDRep` (`Conway/State/VState.hs:120-143`, cardano-ledger f649f975): the account
    * of `credential`, as `accountState` was, leaves the DRep it delegated to and joins `newDRep` 's
    * delegators; nothing changes where the DRep is the same. A predefined DRep has no delegators.
    */
  def unDelegReDelegDRep[E <: EraTag](
      credential: Credential[Staking],
      accountState: AccountState[E],
      newDRep: Option[DRep],
      vState: VState
  )(using a: ConwayEraAccounts[E]): VState =
    def addNewDelegation(
        dreps: scala.collection.immutable.SortedMap[Credential[DRepRole], DRepState]
    ) =
      newDRep
        .flatMap(dRepToCred)
        .fold(dreps)(c => dreps.updatedWith(c)(_.map(DRepState.delegators.modify(_ + credential))))
    VState.dreps.modify { dreps =>
      a.dRepDelegationAccountStateL
        .get(accountState)
        .flatMap(d => dRepToCred(d).map(d -> _)) match
        case None                                            => addNewDelegation(dreps)
        case Some((current, _)) if newDRep.contains(current) => dreps
        case Some((_, c)) =>
          addNewDelegation(dreps.updatedWith(c)(_.map(DRepState.delegators.modify(_ - credential))))
    }(vState)
