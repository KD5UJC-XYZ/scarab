// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.{
  Committee,
  Constitution,
  EnactState,
  PrevIds,
  Proposals,
  ProtocolParams,
  PulsingSnapshot,
  RatifyState,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.GovState as NodeGovState

/** The empty value of each era-dependent part of the state, chosen by the era witness: the type
  * families of `Eras.scala` reduce in each branch, so no cast is needed. The protocol parameters
  * are the one thing an empty state cannot invent, so they are an argument. These are the states of
  * a chain at its first block: nothing registered, nothing staked, nothing proposed.
  */
object Empty:
  val pState: PState = PState(Sorted.empty, Sorted.empty, Sorted.empty, Sorted.empty)

  private val noRewards: InstantaneousRewards =
    InstantaneousRewards(Sorted.empty, Sorted.empty, DeltaCoin(BigInt(0)), DeltaCoin(BigInt(0)))

  private val vState: VState =
    VState(Sorted.empty, CommitteeState(Sorted.empty), EpochNo.fromUnsigned(0L))

  def certState[E <: EraTag](using era: ShelleyBasedEra[E]): CertState[E] = era match
    case ShelleyBasedEra.SConway =>
      ConwayCertState(
        vState,
        pState,
        DState(ConwayAccounts(Sorted.empty), Sorted.empty, Sorted.empty, noRewards)
      )
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      ShelleyCertState(
        pState,
        DState(ShelleyAccounts(Sorted.empty, Sorted.empty), Sorted.empty, Sorted.empty, noRewards)
      )

  def instantStake[E <: EraTag](using era: ShelleyBasedEra[E]): InstantStake[E] = era match
    case ShelleyBasedEra.SConway => ConwayInstantStake(Sorted.empty)
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      ShelleyInstantStake(Sorted.empty, Sorted.empty)

  /** The governance state with `params` as the current and the previous parameters, no update
    * pending, and (Conway) no committee, an anchorless constitution and a completed empty pulser.
    */
  def govState[E <: EraTag](params: ProtocolParams)(using era: ShelleyBasedEra[E]): GovState[E] =
    era match
      case ShelleyBasedEra.SConway =>
        val constitution = Constitution(NodeGovState.noAnchor, None)
        val enact = EnactState(
          Some(Committee(Map.empty, Rational.zero)),
          constitution,
          params,
          params,
          BigInt(0),
          Map.empty,
          PrevIds()
        )
        ConwayGovState(
          Proposals.empty,
          None,
          constitution,
          params,
          params,
          FuturePParams.NoPParamsUpdate,
          DRComplete(
            PulsingSnapshot.empty,
            RatifyState(enact, Vector.empty, Set.empty, delayed = false)
          )
        )
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        ShelleyGovState(Sorted.empty, Sorted.empty, params, params, FuturePParams.NoPParamsUpdate)

  /** `StashedAVVMAddresses`: the UTxO (empty) in Shelley, `()` after. */
  def stashedAvvm[E <: EraTag](using era: ShelleyBasedEra[E]): StashedAVVM[E] = era match
    case ShelleyBasedEra.SShelley => UtxoTables(Utxo.empty)
    case ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary | ShelleyBasedEra.SAlonzo |
        ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway =>
      ()

  def ledgerState[E <: EraTag](params: ProtocolParams)(using ShelleyBasedEra[E]): LedgerState[E] =
    LedgerState[E](
      certState[E],
      UTxOState[E](
        UtxoTables(Utxo.empty),
        Coin.zero,
        Coin.zero,
        govState[E](params),
        instantStake[E],
        Coin.zero
      )
    )

  def epochState[E <: EraTag](params: ProtocolParams)(using ShelleyBasedEra[E]): EpochState[E] =
    EpochState[E](
      ChainAccountState(Coin.zero, Coin.zero),
      ledgerState[E](params),
      SnapShots.empty,
      NonMyopic.empty
    )
