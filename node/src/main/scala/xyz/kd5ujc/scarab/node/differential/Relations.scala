// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, Rewards}
import xyz.kd5ujc.scarab.ledger.state.*

/** What the old side's mirror cannot say and the new side's state does (design §2.5, `Relation =
  * Exact | NewRefines | Ignored`): the places where the Haskell-shaped rules are *more exact* than
  * the node's flat ones, so that a comparison of the two names only a real difference. All of it is
  * the harness' and goes with the old rules at J2.9.
  *
  *   - **Reward attribution** (`Gap.RewardAttribution`): the old mirror's reward update names no
  *     pool and holds no rewards (`rs` empty); what each credential is paid is in the node extras
  *     (`rewardAmounts`). The new rules apply an attributed update, so the seed gives each
  *     credential its amount as one reward of a placeholder pool ([[seed]]), and the comparison
  *     takes the new update to the old one's terms: the sums per credential must be the old
  *     amounts, and the update is then compared without `rs` ([[compare]]).
  *   - **Delegator counts of the older snapshots** (`Gap.NumDelegatorsLowerBound`): the old mirror
  *     derives `numDelegators` and `selfDelegatedOwners` of `set` and `go` from the stake-bearing
  *     delegations, so they are a lower bound of what the new rules carry from the `mark` snapshot,
  *     which is exact (a delegator, or an owner who delegates, with no stake). The new count must
  *     be at least the old one, and the new owners include the old ones; each is then taken as the
  *     old one. The `mark` snapshot of a pool that retires at the boundary is the same: the old
  *     mirror derives its delegators from the accounts, and the retirement has cleared their
  *     delegations. The `set` snapshot of a state the new side carried on, which it took from its
  *     own `mark` (exact), has these fields of the old side's where the pool's stake is the same.
  *   - **The seed holds the snapshots as they were taken** (`SnapshotBasis.AsTaken`): a state the
  *     new side is seeded with takes the delegator-derived fields of `mark` from the snapshot's own
  *     delegations, as `set` and `go` always are, not from the accounts as they are when the seed
  *     is taken (Haskell fixes them when the snapshot is taken, `mkStakePoolSnapShot`,
  *     `State/SnapShots.hs:187-217`; the accounts may have moved since, and the pledge of a pool is
  *     judged on the owners the snapshot holds). They are then a lower bound, as the old mirror's
  *     `set` and `go` are: a delegator with no stake is not in the snapshot.
  *   - **Delegator counts of a carried state** (J2.4): as above, for all three snapshots, except
  *     `mark` at the boundary that takes it.
  *   - **The reward update and the pulser's parameters of a seeded state** (J2.4): the new side's
  *     seed is attributed like the tick stage's ([[seed]]), and the old mirror keeps in the pulser
  *     the flat parameters with the fields Conway's have no place for, and a state the new rules
  *     carry would adopt them as the next epoch's parameters, so the seed is taken to the same
  *     terms the comparison takes both sides to ([[NewRules.carried]]).
  *   - **The VRF key hashes at protocol version 10**: the old mirror derives them for versions from
  *     10, where the reference populates them entering 11 (`HardFork.hs:75-78`; J3.3), so at
  *     version 10 they are not compared.
  *   - **The reward update before the rewards start**: the node makes the first epoch's update with
  *     the genesis state (`GenesisState.initial`), where the reference has none until the first
  *     block past the start of rewards (`RewardsTooEarly`); inside that window the old side's is
  *     not compared.
  *   - **Parameters the era has no field for**: the node's flat parameters keep the cost per UTxO
  *     byte before Babbage, which the earlier eras' parameters do not have; it is not compared
  *     before Babbage.
  *   - **The future parameters of the first block of a chain**: Haskell's state after the hand-over
  *     from Byron, and the genesis state of a chain that starts in a later era, have
  *     `NoPParamsUpdate` until the first epoch boundary, which the node's derived prediction does
  *     not tell apart (`LedgerMirror.futurePParams`: "the first epoch of a chain ... is not told
  *     apart"); on the block that begins a chain, or follows the hand-over, the prediction is not
  *     compared.
  *   - **Pools of no stake in the pool distribution**: it is that of the `set` snapshot, which the
  *     seed derived from the accounts as they are, so it may list a pool whose only delegators
  *     joined since the snapshot and have no stake; such a pool is not compared.
  *   - **The UTxO handle**: the node stamps the changes of a block with its number when it ticks;
  *     the tick does not change the set, so the new side's handle, equal but for the stamp, is the
  *     old one's.
  */
object Relations:

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

  /** The sums per credential of a reward update, as the node holds them. */
  private def amounts[E <: EraTag](nes: NewEpochState[E])(using
      ShelleyBasedEra[E]
  ): Either[VocabError, Option[Map[node.Credential, BigInt]]] =
    nes.ru match
      case Some(PulsingRewUpdate.Complete(update)) if update.rs.nonEmpty =>
        val major = EraLedger.prevPParams(nes.es.ls.utxo.gov).protocolMajor
        Rewards
          .aggregate(major, update.rs)
          .map(sums =>
            Some(
              sums.iterator.collect {
                case (c, n) if n.value.signum != 0 => c.back -> n.value
              }.toMap
            )
          )
      case _ => Right(None)

  extension [R <: KeyRole](c: Credential[R])
    private def back: node.Credential = xyz.kd5ujc.scarab.ledger.rules.Convert.back(c)

  /** The first difference of the old side's state and the new side's, in the old side's terms (see
    * the object).
    */
  def compare[E <: EraTag](
      old: NewEpochState[E],
      oldAmounts: Map[node.Credential, BigInt],
      neu: NewEpochState[E],
      rewardsStarted: Boolean,
      handover: Boolean,
      carried: Boolean = false,
      boundary: Boolean = true
  )(using ShelleyBasedEra[E]): Either[VocabError, Option[Difference]] =
    amounts(neu).map { sums =>
      sums
        .filter(_ != oldAmounts)
        .map(s =>
          Difference(
            Path(Vector(Step.Field("ru"), Step.Field("rs"))),
            Difference.clipped(s"rewards ${oldAmounts.size} credentials: $oldAmounts"),
            Difference.clipped(s"rewards ${s.size} credentials: $s")
          )
        )
        .orElse {
          val started = if rewardsStarted then old else old.copy(ru = None)
          val (o, n) =
            normalise(
              if handover then sameFuture(started, neu) else started,
              neu,
              carried,
              boundary
            )
          Diff[NewEpochState[E]].diff(o, n)
        }
    }

  /** `old` with the future parameters of `neu`. */
  private def sameFuture[E <: EraTag](old: NewEpochState[E], neu: NewEpochState[E])(using
      ShelleyBasedEra[E]
  ): NewEpochState[E] =
    old.copy(es =
      old.es.copy(ls =
        old.es.ls.copy[E](utxo =
          old.es.ls.utxo.copy(gov =
            EraLedger.withFuture(
              old.es.ls.utxo.gov,
              EraLedger.futurePParams(neu.es.ls.utxo.gov)
            )
          )
        )
      )
    )

  /** Both states, taken to the terms the old side can express. */
  def normalise[E <: EraTag](
      old: NewEpochState[E],
      neu: NewEpochState[E],
      carried: Boolean = false,
      boundary: Boolean = true
  )(using
      era: ShelleyBasedEra[E]
  ): (NewEpochState[E], NewEpochState[E]) =
    val noRewards = neu.copy(ru = neu.ru.map {
      case PulsingRewUpdate.Complete(u) if old.ru.forall {
            case PulsingRewUpdate.Complete(o) => o.rs.isEmpty
            case _                            => false
          } =>
        PulsingRewUpdate.Complete(u.copy(rs = Sorted.empty))
      case other => other
    })
    def lower(
        o: SnapShot,
        n: SnapShot,
        only: KeyHash[StakePool] => Boolean = _ => true
    ): SnapShot =
      n.copy(pools = Sorted.map(n.pools.iterator.map { (id, p) =>
        id -> o.pools.get(id).filter(_ => only(id)).fold(p) { q =>
          p.copy(
            numDelegators =
              if Word64.order.gteqv(p.numDelegators, q.numDelegators) then q.numDelegators
              else p.numDelegators,
            // an owner who delegates to the pool with no stake is a delegator the old snapshot
            // does not list
            selfDelegatedOwners =
              if q.selfDelegatedOwners.subsetOf(p.selfDelegatedOwners) then q.selfDelegatedOwners
              else p.selfDelegatedOwners,
            selfDelegatedOwnersStake =
              if q.selfDelegatedOwners.subsetOf(p.selfDelegatedOwners) &&
                cats.Order[Coin].gteqv(p.selfDelegatedOwnersStake, q.selfDelegatedOwnersStake)
              then q.selfDelegatedOwnersStake
              else p.selfDelegatedOwnersStake
          )
        }
      }))
    // the `set` snapshot of a state the new side carried on is its own `mark`, with the delegators
    // the pool had when it was taken (zero-stake ones included), which the old mirror's `set`
    // does not list: where the stake of the pool is the same, the delegator-derived fields are
    // the old side's
    def seeded(o: SnapShot, n: SnapShot): SnapShot =
      n.copy(pools = Sorted.map(n.pools.iterator.map { (id, p) =>
        id -> o.pools.get(id).filter(q => q.stake == p.stake).fold(p) { q =>
          p.copy(
            numDelegators = q.numDelegators,
            selfDelegatedOwners = q.selfDelegatedOwners,
            selfDelegatedOwnersStake = q.selfDelegatedOwnersStake
          )
        }
      }))
    // the `mark` snapshot of a pool that has just retired: the old mirror derives its delegators
    // from the accounts, which the retirement has cleared
    val registered = EraLedger.pState(noRewards.es.ls).stakePools
    // A state the new side carried on from block to block holds each snapshot as it was when it was
    // taken, with the delegators the pool had then (`spsDelegators`); the old mirror derives them
    // from the accounts as they are. They agree at the boundary that takes `mark`, and the
    // delegators themselves are checked against the accounts at every block (`Invariants`).
    val snapshots = noRewards.es.snapshots.copy(
      mark =
        if carried && !boundary then seeded(old.es.snapshots.mark, noRewards.es.snapshots.mark)
        else lower(old.es.snapshots.mark, noRewards.es.snapshots.mark, !registered.contains(_)),
      set = seeded(old.es.snapshots.set, noRewards.es.snapshots.set),
      go =
        if carried then seeded(old.es.snapshots.go, noRewards.es.snapshots.go)
        else lower(old.es.snapshots.go, noRewards.es.snapshots.go)
    )
    val handle: UtxoTables =
      if noRewards.es.ls.utxo.utxo.utxo.copy(stamp = old.es.ls.utxo.utxo.utxo.stamp) ==
          old.es.ls.utxo.utxo.utxo
      then old.es.ls.utxo.utxo
      else noRewards.es.ls.utxo.utxo
    // the pool distribution is that of the `set` snapshot, whose pools with delegators but no stake
    // the seed may hold (a delegation made since the snapshot was taken) and the old side does not
    val distribution = noRewards.pd.copy(distribution =
      noRewards.pd.distribution.filter((id, p) =>
        old.pd.distribution.contains(id) || p.totalPoolStake.toBigInt.signum != 0
      )
    )
    val settled = noRewards.copy(
      pd = distribution,
      es = noRewards.es.copy(
        snapshots = snapshots,
        ls = noRewards.es.ls.copy[E](utxo = noRewards.es.ls.utxo.copy(utxo = handle))
      )
    )
    val major = EraLedger.curPParams(old.es.ls.utxo.gov).protocolMajor
    val (a, b) = if major == 10 then (withoutVrf(old), withoutVrf(settled)) else (old, settled)
    (withoutDroppedFields(withoutByteCost(a)), withoutDroppedFields(withoutByteCost(b)))

  /** The parameters in the pulser of Conway, and the prediction made from them: the node holds
    * there the flat parameters, with the fields Conway's parameters have no place for, which the
    * mirror zeroes in the ones of the governance state but not in these.
    */
  private[differential] def withoutDroppedFields[E <: EraTag](nes: NewEpochState[E])(using
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

  /** The cost per UTxO byte of the parameters, for the eras that have no such field. */
  private def withoutByteCost[E <: EraTag](nes: NewEpochState[E])(using
      era: ShelleyBasedEra[E]
  ): NewEpochState[E] =
    val hasByteCost = era match
      case ShelleyBasedEra.SBabbage | ShelleyBasedEra.SConway => true
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo =>
        false
    if hasByteCost then nes
    else
      val ls = nes.es.ls
      nes.copy(es =
        nes.es.copy(ls =
          ls.copy[E](utxo =
            ls.utxo.copy(gov =
              EraLedger.mapParams(ls.utxo.gov)((p: xyz.kd5ujc.scarab.ledger.ProtocolParams) =>
                p.copy(coinsPerUTxOByte = 0)
              )
            )
          )
        )
      )

  private def withoutVrf[E <: EraTag](nes: NewEpochState[E])(using
      ShelleyBasedEra[E]
  ): NewEpochState[E] =
    val ps = EraLedger.pState(nes.es.ls)
    nes.copy(es =
      nes.es.copy(ls = EraLedger.withPState(nes.es.ls, ps.copy(vrfKeyHashes = Sorted.empty)))
    )
