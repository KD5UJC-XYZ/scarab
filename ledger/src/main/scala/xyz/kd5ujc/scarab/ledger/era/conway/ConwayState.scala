// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import monocle.Lens
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.era.{
  ConwayEraAccounts,
  ConwayEraCertState,
  EraCertState,
  Obligations
}
import xyz.kd5ujc.scarab.ledger.state.{
  CompactCoin,
  Conway,
  ConwayAccountState,
  ConwayAccounts,
  ConwayCertState,
  Credential,
  DRep,
  DState,
  KeyHash,
  PState,
  StakePool,
  Staking,
  VState
}

/** Conway's instances of the state classes, over the records the node keeps ([[ConwayAccounts]],
  * [[ConwayCertState]]).
  */
object ConwayState:
  /** `instance EraAccounts ConwayEra` and `instance ConwayEraAccounts ConwayEra`
    * (`eras/conway/impl/src/Cardano/Ledger/Conway/State/Account.hs:176-193, 296-298`,
    * cardano-ledger f649f975).
    */
  val accounts: ConwayEraAccounts[Conway] = new ConwayEraAccounts[Conway]:
    override def addAccountState(
        credential: Credential[Staking],
        accountState: ConwayAccountState,
        accounts: ConwayAccounts
    ): ConwayAccounts = accountsMapL.modify(_.updated(credential, accountState))(accounts)

    override val accountsMapL
        : Lens[ConwayAccounts, SortedMap[Credential[Staking], ConwayAccountState]] =
      ConwayAccounts.states
    override val balanceAccountStateL: Lens[ConwayAccountState, CompactCoin] =
      ConwayAccountState.balance
    override val depositAccountStateL: Lens[ConwayAccountState, CompactCoin] =
      ConwayAccountState.deposit
    override val stakePoolDelegationAccountStateL
        : Lens[ConwayAccountState, Option[KeyHash[StakePool]]] =
      ConwayAccountState.pool

    /** `unregisterAccount = unregisterConwayAccount`. */
    override def unregisterAccount(
        credential: Credential[Staking],
        accounts: ConwayAccounts
    ): (Option[ConwayAccountState], ConwayAccounts) =
      ConwayEraAccounts.unregisterConwayAccount[Conway](credential, accounts)(using this)

    /** The class's default (`Conway/State/Account.hs:283-292`, cardano-ledger f649f975). */
    override def mkConwayAccountState(deposit: CompactCoin): ConwayAccountState =
      ConwayAccountState(CompactCoin.zero, deposit, None, None)

    override val dRepDelegationAccountStateL: Lens[ConwayAccountState, Option[DRep]] =
      ConwayAccountState.drep

  /** `instance EraCertState ConwayEra` and `instance ConwayEraCertState ConwayEra`
    * (`Conway/State/CertState.hs:123-140`, cardano-ledger f649f975).
    */
  val certState: ConwayEraCertState[Conway] = new ConwayEraCertState[Conway]:
    override def accountsOf: ConwayEraAccounts[Conway] = accounts

    override val certDStateL: Lens[ConwayCertState, DState[ConwayAccounts]] =
      ConwayCertState.dState
    override val certPStateL: Lens[ConwayCertState, PState] = ConwayCertState.pState
    override val certVStateL: Lens[ConwayCertState, VState] = ConwayCertState.vState

    /** `obligationCertState = conwayObligationCertState`. */
    override def obligationCertState(certState: ConwayCertState): Obligations =
      EraCertState.conwayObligationCertState[Conway](certState)(using this)
