// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.read

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.rules.{EraLedger, Rewards}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.node.read.LeaderPools.given

/** A read of the ledger state that can be refused: only a state the mirror cannot hold (a value
  * outside the range of a Haskell type) is, and only when the state is the node's flat one.
  */
type Read[A] = Either[MirrorError, A]

/** The reward update a state holds, by credential: what the node's projection and `GetRewardInfo`
  * style readers need. The mirror's attribution per pool ([[RewardUpdate.rs]]) is summed over the
  * pools.
  */
final case class RewardsDue(
    deltaT: DeltaCoin,
    deltaR: DeltaCoin,
    deltaF: DeltaCoin,
    byCredential: SortedMap[Credential[Staking], BigInt]
)

/** What the read paths ask of the ledger state of one Shelley-based era `E`, in the vocabulary of
  * the mirror (`NewEpochState`): the local state queries, the header rules' ledger-view forecast,
  * the leader schedule, the K1 projection and the follower's epoch line. [[MirrorEraRead]]
  * implements it: its members are the fields of a `NewEpochState[E]` and it converts nothing.
  * (Until J2.9 the node's flat state implemented it too, converting the part asked for; the flat
  * state went with the old rules, and the readers stayed written against this.)
  *
  * The members that are cheap on both (the scalars, the parameters, what the forecast reads) do not
  * convert the accounts, the pools or the snapshots; a reader that does not ask for those does not
  * pay for them.
  */
trait EraRead[E <: EraTag]:
  def era: ShelleyBasedEra[E]

  /** The epoch the ledger state is in. */
  def epoch: EpochNo

  /** The epoch, the blocks made, the pots and the amounts of the UTxO state. */
  def scalars: Read[LedgerScalars]

  /** The protocol parameters in force, with every field the node holds: the mirror's parameters
    * have zero in the fields the era has no place for (`GovState.curPParams`).
    */
  def params: flat.ProtocolParams

  /** The UTxO set: the handle on the diff over the backing store, never the entries. */
  def utxo: flat.Utxo

  def accountCount: Int

  def poolCount: Int

  /** The pool distribution of the leader check: the one in force this epoch (`nesPd`), or the one
    * the next boundary puts in force (the newest snapshot, `mark`).
    */
  def leaderPools(nextEpoch: Boolean): flat.PoolDistribution

  /** The protocol parameters of the next epoch as the header rules forecast them: those the pending
    * update proposals adopt at the boundary, before Conway.
    */
  def paramsNextEpoch(globals: flat.LedgerGlobals): flat.ProtocolParams

  /** The genesis delegations in force at `slot`: the current ones with those that fall due by then
    * applied, in the order of their slot and key (`LedgerView` of TPraos).
    */
  def genDelegsAt(slot: BigInt): Map[Bytes, flat.GenDelegPair]

  def cert: Read[CertState[E]]

  def gov: Read[GovState[E]]

  def instantStake: Read[InstantStake[E]]

  def snapshots: Read[SnapShots]

  /** `nesPd`. */
  def pd: Read[PoolDistr]

  /** The reward update to apply at the next boundary, if it is complete. */
  def rewardsDue: Read[Option[RewardsDue]]

  /** The proposed protocol-parameter updates, current and future, by genesis key hash: the state's,
    * in every era (none after Conway, where the mirror has no place for them).
    */
  def ppup: Read[(Map[Bytes, flat.ParamUpdate], Map[Bytes, flat.ParamUpdate])]

  /** The completed DRep pulser the state has run: none before Conway and, on a chain that entered
    * Conway, before its first epoch boundary (the reference holds the pulser of the era translation
    * then).
    */
  def pulsed: Read[Option[DRComplete]]

  /** The whole new-epoch state (`DebugNewEpochState`). */
  def nes: Read[NewEpochState[E]]

object EraRead:
  /** The credential as the node writes it. */
  def credential[R <: KeyRole](c: Credential[R]): flat.Credential = c match
    case Credential.KeyHashObj(h)    => flat.Credential.Key(h.bytes)
    case Credential.ScriptHashObj(h) => flat.Credential.Script(h.bytes)

  /** The pool distribution of the leader check from a snapshot's pool distribution
    * (`calculatePoolDistr`): each pool's stake and VRF key hash.
    */
  def leaderPools(pd: PoolDistr): flat.PoolDistribution =
    flat.PoolDistribution(
      pd.distribution.iterator.map((id, p) => id.bytes -> p.totalPoolStake.toBigInt).toMap,
      pd.distribution.iterator.map((id, p) => id.bytes -> p.vrf.bytes).toMap
    )

  /** The genesis delegations at `slot` of a certificate state (any era's): `FutureGenDeleg` s are
    * ordered by slot and key, which is the order they are applied in.
    */
  def genDelegsAt(
      current: SortedMap[KeyHash[Genesis], flat.GenDelegPair],
      future: SortedMap[FutureGenDeleg, flat.GenDelegPair],
      slot: BigInt
  ): Map[Bytes, flat.GenDelegPair] =
    val base = current.iterator.map((k, p) => k.bytes -> p).toMap
    future.iterator
      .takeWhile((f, _) => f.slot.toBigInt <= slot)
      .foldLeft(base)((m, entry) => m.updated(entry._1.genKeyHash.bytes, entry._2))

/** The read of the ledger state of an era that is a mirror: the fields of the `NewEpochState`.
  * Nothing is converted and nothing fails.
  */
final class MirrorEraRead[E <: EraTag](val state: NewEpochState[E])(using
    val era: ShelleyBasedEra[E]
) extends EraRead[E]:
  private def ls: LedgerState[E] = state.es.ls

  def epoch: EpochNo = state.epoch

  lazy val scalars: Read[LedgerScalars] = Right(
    LedgerScalars(
      state.epoch,
      state.bprev,
      state.bcur,
      state.es.chainAccount,
      ls.utxo.deposited,
      ls.utxo.fees,
      ls.utxo.donation
    )
  )

  def params: flat.ProtocolParams = EraLedger.curPParams(ls.utxo.gov)

  def utxo: flat.Utxo = ls.utxo.utxo.utxo

  lazy val accountCount: Int = EraLedger.accounts(ls).size

  lazy val poolCount: Int = EraLedger.pState(ls).stakePools.size

  def leaderPools(nextEpoch: Boolean): flat.PoolDistribution =
    (if nextEpoch then EraRead.leaderPools(PoolDistr.of(state.es.snapshots.mark))
     else state.leaderPools)

  def paramsNextEpoch(globals: flat.LedgerGlobals): flat.ProtocolParams =
    val g = ls.utxo.gov
    EraLedger.futurePParams(g).nextParams.getOrElse(EraLedger.curPParams(g))

  def genDelegsAt(slot: BigInt): Map[Bytes, flat.GenDelegPair] =
    EraRead.genDelegsAt(EraLedger.genDelegs(ls), EraLedger.futureGenDelegs(ls), slot)

  def cert: Read[CertState[E]] = Right(ls.cert)

  def gov: Read[GovState[E]] = Right(ls.utxo.gov)

  def instantStake: Read[InstantStake[E]] = Right(ls.utxo.instantStake)

  def snapshots: Read[SnapShots] = Right(state.es.snapshots)

  def pd: Read[PoolDistr] = Right(state.pd)

  /** What each credential is paid (`aggregateRewards`, `Rewards.hs:186`): before Allegra a
    * credential that earns from several sources gets only the least of its rewards, by the
    * parameters in force during the epoch the update is for (the previous ones, while the update is
    * applied); the mirror's update holds every reward.
    */
  def rewardsDue: Read[Option[RewardsDue]] =
    state.ru.flatTraverse {
      case PulsingRewUpdate.Complete(u) =>
        Rewards
          .aggregate(EraLedger.prevPParams(ls.utxo.gov).protocolMajor, u.rs)
          .left
          .map(MirrorError.Vocab(_))
          .map(paid =>
            Some(
              RewardsDue(
                u.deltaT,
                u.deltaR,
                u.deltaF,
                Sorted.map(paid.collect { case (c, n) if n.value.signum != 0 => c -> n.value })
              )
            )
          )
      case PulsingRewUpdate.Pulsing(_, _) => Right(None)
    }

  def ppup: Read[(Map[Bytes, flat.ParamUpdate], Map[Bytes, flat.ParamUpdate])] =
    Right(era match
      case ShelleyBasedEra.SConway => (Map.empty, Map.empty)
      case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
          ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
        val g = ls.utxo.gov
        (
          g.curProposals.iterator.map((k, u) => k.bytes -> u).toMap,
          g.futureProposals.iterator.map((k, u) => k.bytes -> u).toMap
        ))

  def pulsed: Read[Option[DRComplete]] = Right(era match
    case ShelleyBasedEra.SConway => Some(ls.utxo.gov.drepPulsing)
    case ShelleyBasedEra.SShelley | ShelleyBasedEra.SAllegra | ShelleyBasedEra.SMary |
        ShelleyBasedEra.SAlonzo | ShelleyBasedEra.SBabbage =>
      None)

  def nes: Read[NewEpochState[E]] = Right(state)
