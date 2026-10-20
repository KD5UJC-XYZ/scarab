// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState, ShelleyLedgerState}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  ConwayNewEpochState,
  EraTag,
  FuturePParams,
  KeyHash,
  NewEpochState,
  PulsingRewUpdate,
  Reward,
  RewardSet,
  RewardType,
  ShelleyBasedEra,
  Sorted,
  Staking,
  StakePool,
  VocabError
}

/** What a state the old rules wrote needs to be the era-indexed rules' state (design
  * `ledger-mirror-step2.md` §4, J2.8 and J2.9): the mirror of a flat state has two places the rules
  * of the reference do not leave their state in.
  *
  *   - The reward update is not attributed to pools: the mirror's `rs` is empty and what each
  *     credential is paid is in the old extras (`rewardAmounts`), so each credential is given its
  *     amount as one reward of a placeholder pool ([[seed]]); the new rules' own update, made at
  *     `RUPD`, is attributed, and pays the amounts the same.
  *   - The pulser of Conway keeps the flat parameters with the fields Conway's parameters have no
  *     place for, which a state the new rules carry would adopt as the next epoch's parameters
  *     ([[withoutDroppedFields]]).
  *
  * [[StateMirror]] applies it when converting a flat value, including genesis initialization. It
  * also defined the historical format-4-to-5 migration before those snapshot readers were retired.
  * Applying it to a state the native rules made changes nothing.
  *
  * The delegator-derived fields of the snapshots of a state written by the old rules are the old
  * mirror's (the delegators of `mark` from the accounts at the time of the write, the counts of
  * `set` and `go` as lower bounds): a replay from the previous snapshot or from genesis gives the
  * reference's (finding J2.8-1).
  */
object LegacySeed:

  private val placeholder: Either[VocabError, KeyHash[StakePool]] =
    KeyHash.fromBytes[StakePool](Bytes.fromArray(new Array[Byte](28)))

  /** The state with its reward update attributed to a placeholder pool where the old one holds
    * amounts only.
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

  /** The parameters in the pulser of Conway, and the prediction made from them: the node held there
    * the flat parameters, with the fields Conway's parameters have no place for, which the mirror
    * zeroes in the ones of the governance state but not in these.
    */
  def withoutDroppedFields[E <: EraTag](nes: NewEpochState[E])(using
      era: ShelleyBasedEra[E]
  ): NewEpochState[E] = era match
    case ShelleyBasedEra.SConway =>
      def clear(p: node.ProtocolParams) = p.copy(coinsPerUTxOWord = 0, minUTxOValue = 0)
      // the prediction takes its parameters from the pulser's
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
          futurePParams = FuturePParams.map(g.futurePParams)(clear)
        )
      )(nes)
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      nes

  /** The reward update attributed (from the amounts of `old`) and the pulser's dropped fields
    * removed, in the state of any era.
    */
  def apply(m: MirrorState, old: NodeExtras): MirrorState =
    val telescope = m.ext.ledgerState
    def carry[E <: EraTag](era: ShelleyBasedEra[E], state: ShelleyLedgerState[E]): EraLedgerState =
      given ShelleyBasedEra[E] = era
      seed[E](state.nes, old.rewardAmounts).toOption
        .fold(EraLedgerState.inEra[E](state))(nes =>
          EraLedgerState.inEra[E](state.copy(nes = withoutDroppedFields[E](nes)))
        )
    telescope.state match
      case i: EraLedgerState.InShelleyEra[e] =>
        val next = carry[e](i.era, i.state)
        m.copy(ext = m.ext.copy(ledgerState = telescope.withState(next)))
      case EraLedgerState.ByronCurrent(_) => m
