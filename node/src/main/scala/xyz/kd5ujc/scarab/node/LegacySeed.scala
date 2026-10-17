// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{
  EraLedgerState,
  MirrorExtras,
  MirrorState,
  ShelleyLedgerState
}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.*

/** What a state the old rules wrote needs to be the era-indexed rules' state (design
  * `ledger-mirror-step2.md` §4, J2.8): the snapshots of the databases that were synced before the
  * flip hold the node's flat state through the mirror, and the mirror of a flat state has two
  * places the rules of the reference do not leave their state in.
  *
  *   - The reward update is not attributed to pools: the mirror's `rs` is empty and what each
  *     credential is paid is in the node's extras (`rewardAmounts`), so each credential is given
  *     its amount as one reward of a placeholder pool ([[seed]]); the new rules' own update, made
  *     at `RUPD`, is attributed, and pays the amounts the same.
  *   - The pulser of Conway keeps the flat parameters with the fields Conway's parameters have no
  *     place for, which a state the new rules carry would adopt as the next epoch's parameters
  *     ([[withoutDroppedFields]]).
  *
  * The old rules' bookkeeping that remains in the extras is then reset: the new rules keep none of
  * it, so what it still held (an unattributed reward draft, the amounts) must not seed the state
  * again, and the state is written from then on as the new rules' own. Applying it to a state the
  * new rules made changes nothing.
  *
  * The delegator-derived fields of the snapshots of a state written by the old rules are the old
  * mirror's (the delegators of `mark` from the accounts at the time of the write, the counts of
  * `set` and `go` as lower bounds): the harness' relations ([[differential.Relations]]) hold them
  * to be what the old mirror can say, and a replay from the previous snapshot or from genesis gives
  * the reference's (finding J2.8-1).
  */
object LegacySeed:

  private val placeholder: Either[VocabError, KeyHash[StakePool]] =
    KeyHash.fromBytes[StakePool](Bytes.fromArray(new Array[Byte](28)))

  /** The new side's pre-state from the old mirror's: its reward update attributed to a placeholder
    * pool where the old one holds amounts only.
    */
  def seed[E <: EraTag](nes: NewEpochState[E], amounts: Map[node.Credential, BigInt])(using
      ShelleyBasedEra[E]
  ): Either[VocabError, NewEpochState[E]] =
    nes.ru match
      case Some(PulsingRewUpdate.Complete(update)) if update.rs.isEmpty && amounts.nonEmpty =>
        for
          pool <- placeholder
          rewards <- amounts.toList.traverse { (c, n) =>
            (
              xyz.kd5ujc.scarab.ledger.rules.Convert.credential[Staking](c),
              Coin.fromBigInt(n).map(Reward(RewardType.MemberReward, pool, _))
            ).tupled
          }
        yield nes.copy(ru =
          Some(
            PulsingRewUpdate.Complete(
              update.copy(rs = Sorted.map(rewards.map((c, r) => c -> RewardSet(List(r)))))
            )
          )
        )
      case _ => Right(nes)

  /** The parameters in the pulser of Conway, and the prediction made from them: the node holds
    * there the flat parameters, with the fields Conway's parameters have no place for, which the
    * mirror zeroes in the ones of the governance state but not in these.
    */
  def withoutDroppedFields[E <: EraTag](nes: NewEpochState[E])(using
      era: ShelleyBasedEra[E]
  ): NewEpochState[E] = era match
    case ShelleyBasedEra.SConway =>
      def clear(p: xyz.kd5ujc.scarab.ledger.ProtocolParams) =
        p.copy(coinsPerUTxOWord = 0, minUTxOValue = 0)
      ConwayNewEpochState.gov.modify(g =>
        val ratify = g.drepPulsing.ratify
        g.copy(
          drepPulsing = g.drepPulsing.copy(ratify =
            ratify.copy(enact =
              ratify.enact
                .copy(
                  params = clear(ratify.enact.params),
                  prevParams = clear(ratify.enact.prevParams)
                )
            )
          ),
          // the prediction takes its parameters from the pulser's
          futurePParams = FuturePParams.map(g.futurePParams)(clear)
        )
      )(nes)
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      nes

  /** The reward update attributed and the pulser's dropped fields removed, in the state of any era:
    * the harness' seed of the new side.
    */
  def carried(m: MirrorState): MirrorState =
    val telescope = m.ext.ledgerState
    val amounts = m.extras match
      case MirrorExtras.InShelley(ledger, _) => ledger.rewardAmounts
      case MirrorExtras.InByron(_, _, _, _)  => Map.empty
    def carry[E <: EraTag](era: ShelleyBasedEra[E], state: ShelleyLedgerState[E]): EraLedgerState =
      given ShelleyBasedEra[E] = era
      seed[E](state.nes, amounts).toOption
        .fold(EraLedgerState.inEra[E](state))(nes =>
          EraLedgerState.inEra[E](state.copy(nes = withoutDroppedFields[E](nes)))
        )
    telescope.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        val next = carry[e](i.era, i.state)
        m.copy(ext = m.ext.copy(ledgerState = telescope.withState(next)))
      case EraLedgerState.ByronCurrent(_) => m

  /** [[carried]], and the old rules' bookkeeping of the extras reset (the phase-2 counters kept).
    */
  def apply(m: MirrorState): MirrorState =
    val seeded = carried(m)
    seeded.extras match
      case MirrorExtras.InShelley(ledger, consensus) =>
        seeded.copy(extras =
          MirrorExtras.InShelley(NodeExtras.empty.copy(plutus = ledger.plutus), consensus)
        )
      case _: MirrorExtras.InByron => seeded
