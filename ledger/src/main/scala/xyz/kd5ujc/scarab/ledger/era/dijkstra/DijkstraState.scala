// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import monocle.Lens
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.era.{
  BothLevels,
  ConwayEraAccounts,
  ConwayEraCertState,
  EraCertState,
  EraTxBody,
  Obligations,
  PParams,
  TxBody,
  TxCert,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  CompactCoin,
  ConwayAccountState,
  ConwayAccounts,
  ConwayCertState,
  Credential,
  Dijkstra,
  DRep,
  DState,
  KeyHash,
  PState,
  StakePool,
  Staking,
  VState
}

/** Dijkstra's instances of the state classes, over Conway's records, as the reference's are
  * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/State/Account.hs` and `State/CertState.hs`,
  * cardano-ledger 9df9f98c).
  */
object DijkstraState:
  private val b = EraTxBody.dijkstra

  /** The certificates of a top-level body and its sub-transactions, the sub-transactions' first,
    * concatenated as the reference's sequences are (`State/CertState.hs:57-59, 78-79`,
    * cardano-ledger 9df9f98c); a sub-transaction's own at the sub-transaction level.
    */
  private def batchTxCerts[L <: TxLevel](body: TxBody[L, Dijkstra])(using
      l: BothLevels[L]
  ): Vector[TxCert[Dijkstra]] =
    l match
      case BothLevels.Top =>
        b.subTransactions(body)
          .toVector
          .flatMap(sub => b.certs[TxLevel.Sub](sub.body)(using BothLevels.Sub)) ++
          b.certs[TxLevel.Top](body)
      case BothLevels.Sub => b.certs[TxLevel.Sub](body)

  /** `dijkstraCertsTotalDepositsTxBody` (`State/CertState.hs:41-60`, cardano-ledger 9df9f98c): the
    * deposits of the batch's certificates, counted together, and of the proposals of the top level
    * and of each sub-transaction, a pool registered when the state's pools have it.
    */
  def dijkstraCertsTotalDepositsTxBody(
      pp: PParams[Dijkstra],
      certState: ConwayCertState,
      topTxBody: TxBody[TxLevel.Top, Dijkstra]
  ): Coin =
    given BothLevels[TxLevel.Top] = BothLevels.Top
    val isPoolReg = certState.pState.stakePools.contains
    val proposals =
      b.subTransactions(topTxBody)
        .toVector
        .map(sub =>
          b.conwayProposalsDeposits[TxLevel.Sub](pp, sub.body)(using BothLevels.Sub).value
        )
        .sum + b.conwayProposalsDeposits[TxLevel.Top](pp, topTxBody).value
    Coin(
      b.certsOf
        .getTotalDepositsTxCerts(pp, isPoolReg, batchTxCerts[TxLevel.Top](topTxBody))
        .value + proposals
    )

  /** `dijkstraCertsTotalRefundsTxBody` (`State/CertState.hs:62-81`, cardano-ledger 9df9f98c): the
    * refunds the batch's certificates declare (no deposit looked up), the sub-transactions' added
    * at the top level.
    */
  def dijkstraCertsTotalRefundsTxBody[L <: TxLevel](
      pp: PParams[Dijkstra],
      body: TxBody[L, Dijkstra]
  )(using BothLevels[L]): Coin =
    b.certsOf.getTotalRefundsTxCerts(pp, _ => None, batchTxCerts[L](body))

  /** `instance EraAccounts DijkstraEra` and `instance ConwayEraAccounts DijkstraEra`
    * (`State/Account.hs:11-33`, cardano-ledger 9df9f98c): Conway's records and lenses.
    */
  val accounts: ConwayEraAccounts[Dijkstra] = new ConwayEraAccounts[Dijkstra]:
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
      ConwayEraAccounts.unregisterConwayAccount[Dijkstra](credential, accounts)(using this)

    /** The class's default (`Conway/State/Account.hs:283-292`, cardano-ledger f649f975). */
    override def mkConwayAccountState(deposit: CompactCoin): ConwayAccountState =
      ConwayAccountState(CompactCoin.zero, deposit, None, None)
    override val dRepDelegationAccountStateL: Lens[ConwayAccountState, Option[DRep]] =
      ConwayAccountState.drep

  /** `instance EraCertState DijkstraEra` and `instance ConwayEraCertState DijkstraEra`
    * (`State/CertState.hs:22-39`, cardano-ledger 9df9f98c).
    */
  val certState: ConwayEraCertState[Dijkstra] = new ConwayEraCertState[Dijkstra]:
    override def accountsOf: ConwayEraAccounts[Dijkstra] = accounts
    override val certDStateL: Lens[ConwayCertState, DState[ConwayAccounts]] =
      ConwayCertState.dState
    override val certPStateL: Lens[ConwayCertState, PState] = ConwayCertState.pState
    override val certVStateL: Lens[ConwayCertState, VState] = ConwayCertState.vState

    /** `obligationCertState = conwayObligationCertState`. */
    override def obligationCertState(certState: ConwayCertState): Obligations =
      EraCertState.conwayObligationCertState[Dijkstra](certState)(using this)

    /** `certsTotalDepositsTxBody = dijkstraCertsTotalDepositsTxBody`. */
    override def certsTotalDepositsTxBody(
        pp: PParams[Dijkstra],
        certState: ConwayCertState,
        body: TxBody[TxLevel.Top, Dijkstra]
    )(using EraTxBody[Dijkstra]): Coin = dijkstraCertsTotalDepositsTxBody(pp, certState, body)

    /** `certsTotalRefundsTxBody = dijkstraCertsTotalRefundsTxBody`, at the body's own level
      * (`withBothTxLevels`).
      */
    override def certsTotalRefundsTxBody[L <: TxLevel](
        pp: PParams[Dijkstra],
        accounts: ConwayAccounts,
        body: TxBody[L, Dijkstra]
    )(using b: EraTxBody[Dijkstra], l: b.Level[L]): Coin =
      dijkstraCertsTotalRefundsTxBody[L](pp, body)(using DijkstraTxBody.toSTxLevel(body))
