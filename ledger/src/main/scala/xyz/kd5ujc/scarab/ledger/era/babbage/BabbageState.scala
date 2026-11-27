// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import monocle.{Getter, Lens}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.Ptr
import xyz.kd5ujc.scarab.ledger.era.ShelleyEraAccounts
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  CompactCoin,
  Credential,
  KeyHash,
  ShelleyAccountState,
  ShelleyAccounts,
  StakePool,
  Staking
}

/** Babbage's instances of the state classes, over the records the node keeps ([[ShelleyAccounts]]).
  * Its `EraCertState` instance names Babbage's body (`certsTotalDepositsTxBody`), so it comes with
  * Babbage's transaction (#85).
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
