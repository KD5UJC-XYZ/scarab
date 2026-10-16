// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.state

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.show.*
import monocle.{Focus, Iso, Lens}
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.ProtocolParams

// The top of the ledger-state mirror: `NewEpochState` and the `EpochState` it holds
// (`Shelley/LedgerState/Types.hs` of the pinned shelley impl 1.19.0.1, rev 4c81e90). The snapshots
// and the reward types they hold are in `SnapShots.scala` and `Rewards.scala`, the ledger state in
// `LedgerState.scala`.

/** `ChainAccountState` (`State/ChainAccount.hs:39-42`, encoding `46-48`: `array(2)`): the treasury
  * and the reserves (`AccountState` before ledger 1.21).
  */
final case class ChainAccountState(treasury: Coin, reserves: Coin) derives Eq, Show, Diff

object ChainAccountState:
  val treasury: Lens[ChainAccountState, Coin] = Focus[ChainAccountState](_.treasury)
  val reserves: Lens[ChainAccountState, Coin] = Focus[ChainAccountState](_.reserves)

  /** The record is the pair, in the order it is encoded. */
  val pair: Iso[ChainAccountState, (Coin, Coin)] =
    Iso[ChainAccountState, (Coin, Coin)](c => (c.treasury, c.reserves))(ChainAccountState(_, _))

/** `BlocksMade` (`BaseTypes.hs:910-912`, a newtype over `Map (KeyHash StakePool) Natural`): the
  * blocks each pool made in an epoch. A `Natural` is unbounded in Haskell and a count of the blocks
  * of one epoch here is far below 2^64, so the word is the value type.
  */
opaque type BlocksMade = SortedMap[KeyHash[StakePool], Word64]

object BlocksMade:
  val empty: BlocksMade = Sorted.empty

  /** The wrapper is the identity, so it is an isomorphism with the map. */
  val iso: Iso[BlocksMade, SortedMap[KeyHash[StakePool], Word64]] =
    Iso[BlocksMade, SortedMap[KeyHash[StakePool], Word64]](identity)(identity)

  def apply(blocks: SortedMap[KeyHash[StakePool], Word64]): BlocksMade = blocks

  extension (b: BlocksMade) def blocks: SortedMap[KeyHash[StakePool], Word64] = b

  // Explicit (not found from the map's instances): in this file the type is the map.
  given Eq[BlocksMade] = Eq.fromUniversalEquals
  given Diff[BlocksMade] = Diff.sortedMap[KeyHash[StakePool], Word64]

  given Show[BlocksMade] =
    Show.show(b =>
      s"BlocksMade(${b.toList.map((k, n) => s"${k.show} -> ${n.show}").mkString(", ")})"
    )

/** `EpochState era` (`LedgerState/Types.hs:66-77`, encoding `136-142`: `array(4)`, ledger state
  * before the snapshots).
  */
final case class EpochState[E <: EraTag](
    chainAccount: ChainAccountState,
    ls: LedgerState[E],
    snapshots: SnapShots,
    nonMyopic: NonMyopic
)

object EpochState:
  given [E <: EraTag](using ShelleyBasedEra[E]): Eq[EpochState[E]] = Eq.instance((a, b) =>
    Eq[ChainAccountState].eqv(a.chainAccount, b.chainAccount) &&
      Eq[LedgerState[E]].eqv(a.ls, b.ls) &&
      Eq[SnapShots].eqv(a.snapshots, b.snapshots) &&
      Eq[NonMyopic].eqv(a.nonMyopic, b.nonMyopic)
  )

  given [E <: EraTag](using ShelleyBasedEra[E]): Diff[EpochState[E]] = Diff.record(
    Diff.at("chainAccount")(_.chainAccount),
    Diff.at("ls")(_.ls),
    Diff.at("snapshots")(_.snapshots),
    Diff.at("nonMyopic")(_.nonMyopic)
  )

  given [E <: EraTag](using ShelleyBasedEra[E]): Show[EpochState[E]] = Show.show(e =>
    s"EpochState(chainAccount = ${e.chainAccount.show}, ls = ${Show[LedgerState[E]].show(e.ls)}, " +
      s"snapshots = ${e.snapshots.show}, nonMyopic = ${e.nonMyopic.show})"
  )

  def chainAccount[E <: EraTag]: Lens[EpochState[E], ChainAccountState] =
    Focus[EpochState[E]](_.chainAccount)
  def ls[E <: EraTag]: Lens[EpochState[E], LedgerState[E]] = Focus[EpochState[E]](_.ls)
  def snapshots[E <: EraTag]: Lens[EpochState[E], SnapShots] = Focus[EpochState[E]](_.snapshots)
  def nonMyopic[E <: EraTag]: Lens[EpochState[E], NonMyopic] = Focus[EpochState[E]](_.nonMyopic)

/** `NewEpochState era` (`LedgerState/Types.hs:302-330`, encoding `397-405`: `array(7)`): the whole
  * ledger state of an era. `ru` is a `StrictMaybe`, which is an `Option` here; `avvm` is the AVVM
  * addresses stashed on the Byron to Shelley boundary (the UTxO in Shelley, `()` after,
  * `Types.hs:357-359`).
  */
final case class NewEpochState[E <: EraTag](
    epoch: EpochNo,
    bprev: BlocksMade,
    bcur: BlocksMade,
    es: EpochState[E],
    ru: Option[PulsingRewUpdate],
    pd: PoolDistr,
    avvm: StashedAVVM[E]
)

object NewEpochState:
  import EraInstances.given

  given [E <: EraTag](using ShelleyBasedEra[E]): Eq[NewEpochState[E]] = Eq.instance((a, b) =>
    Eq[EpochNo].eqv(a.epoch, b.epoch) &&
      Eq[BlocksMade].eqv(a.bprev, b.bprev) &&
      Eq[BlocksMade].eqv(a.bcur, b.bcur) &&
      Eq[EpochState[E]].eqv(a.es, b.es) &&
      Eq[Option[PulsingRewUpdate]].eqv(a.ru, b.ru) &&
      Eq[PoolDistr].eqv(a.pd, b.pd) &&
      Eq[StashedAVVM[E]].eqv(a.avvm, b.avvm)
  )

  given [E <: EraTag](using ShelleyBasedEra[E]): Diff[NewEpochState[E]] = Diff.record(
    Diff.at("epoch")(_.epoch),
    Diff.at("bprev")(_.bprev),
    Diff.at("bcur")(_.bcur),
    Diff.at("es")(_.es),
    Diff.at("ru")(_.ru),
    Diff.at("pd")(_.pd),
    Diff.at("avvm")(_.avvm)
  )

  given [E <: EraTag](using ShelleyBasedEra[E]): Show[NewEpochState[E]] = Show.show(n =>
    s"NewEpochState(epoch = ${n.epoch.show}, bprev = ${n.bprev.show}, bcur = ${n.bcur.show}, " +
      s"es = ${Show[EpochState[E]].show(n.es)}, ru = ${n.ru.show}, pd = ${n.pd.show}, " +
      s"avvm = ${Show[StashedAVVM[E]].show(n.avvm)})"
  )

  /** The state of an empty chain at `epoch`, of any era (see [[Empty]]). */
  def empty[E <: EraTag](epoch: EpochNo, params: ProtocolParams)(using
      ShelleyBasedEra[E]
  ): NewEpochState[E] =
    NewEpochState[E](
      epoch,
      BlocksMade.empty,
      BlocksMade.empty,
      Empty.epochState[E](params),
      None,
      PoolDistr.empty,
      Empty.stashedAvvm[E]
    )

  def epoch[E <: EraTag]: Lens[NewEpochState[E], EpochNo] = Focus[NewEpochState[E]](_.epoch)
  def bprev[E <: EraTag]: Lens[NewEpochState[E], BlocksMade] = Focus[NewEpochState[E]](_.bprev)
  def bcur[E <: EraTag]: Lens[NewEpochState[E], BlocksMade] = Focus[NewEpochState[E]](_.bcur)
  def es[E <: EraTag]: Lens[NewEpochState[E], EpochState[E]] = Focus[NewEpochState[E]](_.es)
  def ru[E <: EraTag]: Lens[NewEpochState[E], Option[PulsingRewUpdate]] =
    Focus[NewEpochState[E]](_.ru)
  def pd[E <: EraTag]: Lens[NewEpochState[E], PoolDistr] = Focus[NewEpochState[E]](_.pd)
  def avvm[E <: EraTag]: Lens[NewEpochState[E], StashedAVVM[E]] = Focus[NewEpochState[E]](_.avvm)

  /** The ledger state of the epoch state, whatever the era. */
  def ledger[E <: EraTag]: Lens[NewEpochState[E], LedgerState[E]] =
    es[E].andThen(EpochState.ls[E])

/** Paths into a Conway epoch state, composed from the lens of each record (`andThen`), as
  * `ConwayLedgerState` does for the ledger state below it.
  */
object ConwayEpochState:
  val ls: Lens[EpochState[Conway], LedgerState[Conway]] = EpochState.ls[Conway]
  val gov: Lens[EpochState[Conway], ConwayGovState] = ls.andThen(ConwayLedgerState.gov)
  val pools: Lens[EpochState[Conway], SortedMap[KeyHash[StakePool], StakePoolState]] =
    ls.andThen(ConwayLedgerState.pools)
  val treasury: Lens[EpochState[Conway], Coin] =
    EpochState.chainAccount[Conway].andThen(ChainAccountState.treasury)
  val reserves: Lens[EpochState[Conway], Coin] =
    EpochState.chainAccount[Conway].andThen(ChainAccountState.reserves)
  val mark: Lens[EpochState[Conway], SnapShot] =
    EpochState.snapshots[Conway].andThen(SnapShots.mark)

/** Paths into a Conway `NewEpochState`. */
object ConwayNewEpochState:
  val es: Lens[NewEpochState[Conway], EpochState[Conway]] = NewEpochState.es[Conway]
  val ls: Lens[NewEpochState[Conway], LedgerState[Conway]] = es.andThen(ConwayEpochState.ls)
  val gov: Lens[NewEpochState[Conway], ConwayGovState] = es.andThen(ConwayEpochState.gov)
  val pools: Lens[NewEpochState[Conway], SortedMap[KeyHash[StakePool], StakePoolState]] =
    es.andThen(ConwayEpochState.pools)
  val dreps: Lens[NewEpochState[Conway], SortedMap[Credential[DRepRole], DRepState]] =
    ls.andThen(ConwayLedgerState.dreps)
  val accounts: Lens[NewEpochState[Conway], ConwayAccounts] =
    ls.andThen(ConwayLedgerState.accounts)
  val treasury: Lens[NewEpochState[Conway], Coin] = es.andThen(ConwayEpochState.treasury)
  val reserves: Lens[NewEpochState[Conway], Coin] = es.andThen(ConwayEpochState.reserves)
  val snapshots: Lens[NewEpochState[Conway], SnapShots] =
    es.andThen(EpochState.snapshots[Conway])
