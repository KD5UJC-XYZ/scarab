// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import monocle.{Getter, Lens}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.Ptr
import xyz.kd5ujc.scarab.ledger.era.{
  EraCertState,
  EraTxBody,
  Obligations,
  PParams,
  ShelleyEraAccounts,
  TxBody,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Coin,
  CompactCoin,
  Credential,
  DState,
  KeyHash,
  PState,
  ShelleyAccountState,
  ShelleyAccounts,
  ShelleyCertState,
  StakePool,
  Staking
}

/** Alonzo's instances of the state classes, over the records the node keeps ([[ShelleyAccounts]],
  * [[ShelleyCertState]]), as Babbage's are.
  */
object AlonzoState:
  /** `instance EraAccounts AlonzoEra` and `instance ShelleyEraAccounts AlonzoEra`
    * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/State/Account.hs:11-29`, cardano-ledger
    * f649f975), the class's defaults over Shelley's records (`Shelley/State/Account.hs:158-193`).
    */
  val accounts: ShelleyEraAccounts[Alonzo] = new ShelleyEraAccounts[Alonzo]:
    /** `addAccountState = shelleyAddAccountState`. */
    override def addAccountState(
        credential: Credential[Staking],
        accountState: ShelleyAccountState,
        accounts: ShelleyAccounts
    ): ShelleyAccounts =
      ShelleyEraAccounts.shelleyAddAccountState[Alonzo](credential, accountState, accounts)(using
        this
      )

    override val accountsMapL
        : Lens[ShelleyAccounts, SortedMap[Credential[Staking], ShelleyAccountState]] =
      ShelleyAccounts.states
    override val balanceAccountStateL: Lens[ShelleyAccountState, CompactCoin] =
      ShelleyAccountState.balance
    override val depositAccountStateL: Lens[ShelleyAccountState, CompactCoin] =
      ShelleyAccountState.deposit
    override val stakePoolDelegationAccountStateL
        : Lens[ShelleyAccountState, Option[KeyHash[StakePool]]] =
      ShelleyAccountState.pool

    /** `unregisterAccount = unregisterShelleyAccount`. */
    override def unregisterAccount(
        credential: Credential[Staking],
        accounts: ShelleyAccounts
    ): (Option[ShelleyAccountState], ShelleyAccounts) =
      ShelleyEraAccounts.unregisterShelleyAccount[Alonzo](credential, accounts)(using this)

    override def mkShelleyAccountState(ptr: Ptr, deposit: CompactCoin): ShelleyAccountState =
      ShelleyAccountState(ptr, CompactCoin.zero, deposit, None)

    override private[era] val accountsPtrsMapL
        : Lens[ShelleyAccounts, SortedMap[Ptr, Credential[Staking]]] =
      ShelleyAccounts.ptrs

    override val ptrAccountStateG: Getter[ShelleyAccountState, Ptr] =
      ShelleyAccountState.ptr.asGetter

  /** `instance EraCertState AlonzoEra`
    * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/State/CertState.hs:11-24`, cardano-ledger
    * f649f975): Shelley's record, lenses and functions (`Shelley/State/CertState.hs:77-94`).
    */
  val certState: EraCertState[Alonzo] = new EraCertState[Alonzo]:
    override def accountsOf: ShelleyEraAccounts[Alonzo] = accounts

    /** `certDStateL = shelleyCertDStateL`. */
    override val certDStateL: Lens[ShelleyCertState, DState[ShelleyAccounts]] =
      ShelleyCertState.dState

    /** `certPStateL = shelleyCertPStateL`. */
    override val certPStateL: Lens[ShelleyCertState, PState] = ShelleyCertState.pState

    /** `obligationCertState = shelleyObligationCertState`. */
    override def obligationCertState(certState: ShelleyCertState): Obligations =
      EraCertState.shelleyObligationCertState[Alonzo](certState)(using this)

    /** `certsTotalDepositsTxBody = shelleyCertsTotalDepositsTxBody`. */
    override def certsTotalDepositsTxBody(
        pp: PParams[Alonzo],
        certState: ShelleyCertState,
        body: TxBody[TxLevel.Top, Alonzo]
    )(using EraTxBody[Alonzo]): Coin =
      EraCertState.shelleyCertsTotalDepositsTxBody[Alonzo](pp, certState, body)(using this)

    /** `certsTotalRefundsTxBody = shelleyCertsTotalRefundsTxBody`. */
    override def certsTotalRefundsTxBody[L <: TxLevel](
        pp: PParams[Alonzo],
        accounts: ShelleyAccounts,
        body: TxBody[L, Alonzo]
    )(using b: EraTxBody[Alonzo], l: b.Level[L]): Coin =
      EraCertState.shelleyCertsTotalRefundsTxBody[Alonzo, L](pp, accounts, body)(using this)
