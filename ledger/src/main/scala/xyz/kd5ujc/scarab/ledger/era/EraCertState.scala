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
  DState,
  EraTag,
  PState,
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
  * family ([[xyz.kd5ujc.scarab.ledger.state.CertState]]). The deposits and refunds of a body's
  * certificates (`certsTotalDepositsTxBody`, `certsTotalRefundsTxBody`) come with the body's own
  * totals (`getTotalDepositsTxBody`, `getTotalRefundsTxBody`).
  */
trait EraCertState[E <: EraTag]:
  /** The accounts' class (the superclass `EraAccounts era`). */
  def accountsOf: EraAccounts[E]

  def certDStateL: Lens[CertState[E], DState[Accounts[E]]]
  def certPStateL: Lens[CertState[E], PState]

  /** `obligationCertState`: the deposits the state holds, whatever the parameters now ask. */
  def obligationCertState(certState: CertState[E]): Obligations

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
