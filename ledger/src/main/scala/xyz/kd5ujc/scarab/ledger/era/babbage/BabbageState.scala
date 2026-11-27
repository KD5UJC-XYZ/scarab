// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

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
  Babbage,
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

/** Babbage's instances of the state classes, over the records the node keeps ([[ShelleyAccounts]],
  * [[ShelleyCertState]]).
  */
object BabbageState:
  /** `instance EraAccounts BabbageEra` and `instance ShelleyEraAccounts BabbageEra`
    * (`eras/babbage/impl/src/Cardano/Ledger/Babbage/State/Account.hs:11-29`, cardano-ledger
    * f649f975), the class's defaults over Shelley's records (`Shelley/State/Account.hs:158-193`).
    */
  val accounts: ShelleyEraAccounts[Babbage] = new ShelleyEraAccounts[Babbage]:
    /** `addAccountState = shelleyAddAccountState`. */
    override def addAccountState(
        credential: Credential[Staking],
        accountState: ShelleyAccountState,
        accounts: ShelleyAccounts
    ): ShelleyAccounts =
      ShelleyEraAccounts.shelleyAddAccountState[Babbage](credential, accountState, accounts)(using
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
      ShelleyEraAccounts.unregisterShelleyAccount[Babbage](credential, accounts)(using this)

    override def mkShelleyAccountState(ptr: Ptr, deposit: CompactCoin): ShelleyAccountState =
      ShelleyAccountState(ptr, CompactCoin.zero, deposit, None)

    override private[era] val accountsPtrsMapL
        : Lens[ShelleyAccounts, SortedMap[Ptr, Credential[Staking]]] =
      ShelleyAccounts.ptrs

    override val ptrAccountStateG: Getter[ShelleyAccountState, Ptr] =
      ShelleyAccountState.ptr.asGetter

  /** `instance EraCertState BabbageEra`
    * (`eras/babbage/impl/src/Cardano/Ledger/Babbage/State/CertState.hs:11-24`, cardano-ledger
    * f649f975): Shelley's record, lenses and functions (`Shelley/State/CertState.hs:77-94`).
    */
  val certState: EraCertState[Babbage] = new EraCertState[Babbage]:
    override def accountsOf: ShelleyEraAccounts[Babbage] = accounts

    /** `certDStateL = shelleyCertDStateL`. */
    override val certDStateL: Lens[ShelleyCertState, DState[ShelleyAccounts]] =
      ShelleyCertState.dState

    /** `certPStateL = shelleyCertPStateL`. */
    override val certPStateL: Lens[ShelleyCertState, PState] = ShelleyCertState.pState

    /** `obligationCertState = shelleyObligationCertState`. */
    override def obligationCertState(certState: ShelleyCertState): Obligations =
      EraCertState.shelleyObligationCertState[Babbage](certState)(using this)

    /** `certsTotalDepositsTxBody = shelleyCertsTotalDepositsTxBody`. */
    override def certsTotalDepositsTxBody(
        pp: PParams[Babbage],
        certState: ShelleyCertState,
        body: TxBody[TxLevel.Top, Babbage]
    )(using EraTxBody[Babbage]): Coin =
      EraCertState.shelleyCertsTotalDepositsTxBody[Babbage](pp, certState, body)(using this)

    /** `certsTotalRefundsTxBody = shelleyCertsTotalRefundsTxBody`. */
    override def certsTotalRefundsTxBody[L <: TxLevel](
        pp: PParams[Babbage],
        accounts: ShelleyAccounts,
        body: TxBody[L, Babbage]
    )(using b: EraTxBody[Babbage], l: b.Level[L]): Coin =
      EraCertState.shelleyCertsTotalRefundsTxBody[Babbage, L](pp, accounts, body)(using this)
