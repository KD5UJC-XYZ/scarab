// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Counted, InstantStakeOps, ShelleyInstantStakeOps}

/** Builders of the era-indexed state for a rule test that starts from it rather than from the
  * node's flat state (J2.6): a state the flat state cannot say, because the reference holds more
  * than the node's does (the VRF key hashes of the pools, an exact snapshot, a pulser). Most rule
  * suites start from a flat state and go through the mirror ([[xyz.kd5ujc.scarab.ledger.RuleSet]]);
  * these are for the ones that do not.
  *
  * Every builder takes the era by its witness, as the rules do, so one test body can be written for
  * a family of eras.
  */
object Build:
  private def fail(why: String): Nothing = throw new AssertionError(why)

  /** The state of an empty chain in `epoch` with `params` in force (`NewEpochState.empty`). */
  def nes[E <: EraTag](epoch: Long, params: node.ProtocolParams)(using
      ShelleyBasedEra[E]
  ): NewEpochState[E] =
    NewEpochState.empty[E](EpochNo.fromUnsigned(epoch), params)

  /** The ledger state of `nes` with the node's phase-2 counters at zero: what a LEDGER judgment is
    * run on.
    */
  def counted[E <: EraTag](nes: NewEpochState[E]): Counted[LedgerState[E]] =
    Counted(nes.es.ls, node.PlutusStats.zero)

  /** `nes` holding `funds` as its UTxO set, and the instant stake they make. */
  def funded[E <: EraTag](nes: NewEpochState[E], funds: Map[node.TxIn, node.TxOut])(using
      era: ShelleyBasedEra[E]
  ): NewEpochState[E] =
    val ls = nes.es.ls
    val stake: InstantStake[E] = era match
      case ShelleyBasedEra.SConway =>
        InstantStakeOps
          .update(Empty.instantStake[Conway], funds.values, Nil)
          .fold(e => fail(s"$e"), identity)
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        ShelleyInstantStakeOps
          .update(Empty.instantStake[E], funds.values, Nil)
          .fold(e => fail(s"$e"), identity)
    nes.copy(es =
      nes.es.copy(ls =
        ls.copy[E](utxo =
          ls.utxo.copy[E](utxo = UtxoTables(node.Utxo(funds)), instantStake = stake)
        )
      )
    )

  /** `nes` with the pool registered, as the registration rule leaves it: its state, and, where the
    * era keeps them (from protocol version 11), its VRF key hash in the map of key hashes in use.
    * `future` is a re-registration that is waiting for the next epoch.
    */
  def pool[E <: EraTag](
      nes: NewEpochState[E],
      params: node.PoolParams,
      deposit: BigInt,
      future: Option[node.PoolParams] = None
  )(using ShelleyBasedEra[E]): NewEpochState[E] =
    val operator = Convert.keyHash[StakePool](params.operator).fold(e => fail(s"$e"), identity)
    val state = Convert
      .poolState(
        params,
        CompactCoin.fromBigInt(deposit).fold(e => fail(s"$e"), identity),
        Sorted.emptySet[Credential[Staking]]
      )
      .fold(e => fail(s"$e"), identity)
    val keys =
      (params +: future.toVector).flatMap(p => VRFVerKeyHash.fromBytes(p.vrfKeyHash).toOption)
    val ls = nes.es.ls
    val ps = LedgerState.pState[E].get(ls)
    val next = ps.copy(
      stakePools = ps.stakePools.updated(operator, state),
      future = future.fold(ps.future)(f => ps.future.updated(operator, f)),
      vrfKeyHashes = keys.foldLeft(ps.vrfKeyHashes)((m, k) => m.updated(k, NonZero.oneWord))
    )
    nes.copy(es = nes.es.copy(ls = LedgerState.pState[E].replace(next)(ls)))
