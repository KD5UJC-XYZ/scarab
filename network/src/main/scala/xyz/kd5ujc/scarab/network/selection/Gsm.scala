// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.concurrent.duration.*

/** The Genesis State Machine: whether the node is caught up (`Ouroboros.Consensus.Node.GSM`,
  * ouroboros-consensus 4.2.1.0, `ouroboros-consensus-diffusion/src/ouroboros-consensus-diffusion/
  * Ouroboros/Consensus/Node/GSM.hs`, the release cardano-node 11.1.3 pins). It has three states:
  *
  *   - `PreSyncing`: the node is behind and the Honest Availability Assumption does not hold yet.
  *   - `Syncing`: the assumption holds (the node is connected only to peers it trusts, with a
  *     bootstrap peer active); the node waits to be caught up.
  *   - `CaughtUp`: every upstream peer is idle and none has a better chain than ours. It stays so
  *     for at least `minCaughtUpDuration`, then until the tip is older than `maxCaughtUpAge` (plus
  *     a random bonus of up to five minutes, so that the nodes of a network that stopped producing
  *     blocks do not all return to their bootstrap peers at the same moment).
  *
  * [[gsmStateToLedgerJudgement]] gives the judgement the governor follows: `PreSyncing` and
  * `Syncing` are `TooOld`, `CaughtUp` is `YoungEnough`.
  *
  * The reference blocks in STM until a condition holds; this is a pure transition function
  * ([[step]]) that the effect loop calls with a reading of the conditions whenever any of them may
  * have changed and at least every second, which is the one difference (the latency of a change is
  * the polling interval, not zero).
  */
object Gsm:
  enum Phase:
    case PreSyncing, Syncing, CaughtUp

  /** `gsmStateToLedgerJudgement` (GSM.hs 205-209). */
  def judgement(phase: Phase): LedgerStateJudgement = phase match
    case Phase.CaughtUp => LedgerStateJudgement.YoungEnough
    case _              => LedgerStateJudgement.TooOld

  /** How long until the node's tip is too old (`DurationFromNow`, GSM.hs 80-86). */
  enum DurationFromNow:
    case After(duration: FiniteDuration)
    case Already

  /** What a ChainSync client says of its peer (`ChainSyncState`): whether the last message was
    * `MsgAwaitReply` (`csIdling`), and how the peer's candidate compares to our selection
    * (`CandidateVersusSelection`, GSM.hs 88-98).
    */
  final case class PeerReading(idle: Boolean, candidate: Candidate)

  enum Candidate:
    /** The candidate shares no point with our selection; the GSM assumes this is ephemeral. */
    case DoesNotIntersect

    /** Whether the candidate is preferred to our selection. */
    case Better(preferred: Boolean)

  /** What the effect loop reads. */
  final case class Reading(
      /** The Honest Availability Assumption (`isHaaSatisfied`): the governor's
        * [[OutboundConnectionsState]] is trusted.
        */
      honestAvailability: Boolean,
      peers: Vector[PeerReading],
      /** An identity of the node's selection (its tip), to tell that it changed. */
      selection: Long,
      /** How long until that selection is too old; none when it can never be (no age limit). */
      untilTooOld: Option[DurationFromNow]
  )

  final case class Config(
      /** `minCaughtUpDuration`: how long the node stays caught up whatever the age of its tip; the
        * reference sets it to `maxCaughtUpAge` (`Node.hs` 953).
        */
      minCaughtUp: FiniteDuration = 20.minutes,
      /** The largest random bonus added to the age limit (`blockWhileCaughtUp`, GSM.hs 314-336). */
      maxBonus: FiniteDuration = 300.seconds
  )

  /** The state of the machine between two readings. */
  final case class State(
      phase: Phase,
      /** In `CaughtUp`: no leaving before this (`threadDelay minCaughtUpDuration`). */
      lockedUntil: Time,
      /** In `CaughtUp`: the selection the deadline was computed for, and the deadline. */
      watching: Option[(Long, Time)],
      /** The random bonus of the current stay in `CaughtUp`. */
      bonus: FiniteDuration,
      rng: Rng
  )

  /** What changed in a step, for the log and the persistent mark (the marker file). */
  enum Event:
    case EnterCaughtUp(peers: Int)
    case LeaveCaughtUp(tooOld: DurationFromNow)
    case PreSyncingToSyncing
    case SyncingToPreSyncing

  final case class Result(state: State, events: List[Event]):
    /** The persistent mark to write: true when the node entered `CaughtUp`, false when it left. */
    def mark: Option[Boolean] = events.collectFirst {
      case Event.EnterCaughtUp(_) => true
      case Event.LeaveCaughtUp(_) => false
    }

  /** `initializationGsmState` (GSM.hs 175-201): caught up if the mark was left and the tip is not
    * too old, else pre-syncing. `markerPresent` is the file, `untilTooOld` the age of the tip.
    */
  def initial(
      markerPresent: Boolean,
      untilTooOld: Option[DurationFromNow],
      now: Time,
      seed: Long,
      config: Config = Config()
  ): State =
    val (bonus, rng) = drawBonus(Rng(seed), config)
    val caughtUp =
      markerPresent && untilTooOld.forall {
        case DurationFromNow.After(_) => true
        case DurationFromNow.Already  => false
      }
    State(
      if caughtUp then Phase.CaughtUp else Phase.PreSyncing,
      lockedUntil = now,
      watching = None,
      bonus = bonus,
      rng = rng
    )

  /** One transition, given a reading at `now`. Each phase changes at most once per step, as the
    * reference's blocking transitions do one after the other; a machine in `PreSyncing` whose
    * assumption holds goes to `Syncing` and, if the node is also caught up, to `CaughtUp` at the
    * next reading.
    */
  def step(st: State, reading: Reading, now: Time, config: Config): Result =
    st.phase match
      case Phase.PreSyncing =>
        if reading.honestAvailability then
          Result(st.copy(phase = Phase.Syncing), List(Event.PreSyncingToSyncing))
        else Result(st, Nil)

      case Phase.Syncing =>
        if !reading.honestAvailability then
          Result(st.copy(phase = Phase.PreSyncing), List(Event.SyncingToPreSyncing))
        else if caughtUp(reading) then
          val (bonus, rng) = drawBonus(st.rng, config)
          Result(
            st.copy(
              phase = Phase.CaughtUp,
              lockedUntil = now + config.minCaughtUp,
              watching = None,
              bonus = bonus,
              rng = rng
            ),
            List(Event.EnterCaughtUp(reading.peers.size))
          )
        else Result(st, Nil)

      case Phase.CaughtUp =>
        if now < st.lockedUntil then Result(st, Nil)
        else
          reading.untilTooOld match
            case None                          => Result(st, Nil)
            case Some(DurationFromNow.Already) => leave(st, DurationFromNow.Already)
            case Some(after @ DurationFromNow.After(duration)) =>
              st.watching match
                // the selection is the one the deadline was computed for: leave at the deadline
                case Some((selection, deadline)) if selection == reading.selection =>
                  if now >= deadline then leave(st, after) else Result(st, Nil)
                // a new selection: count down again for it
                case _ =>
                  Result(
                    st.copy(watching = Some(reading.selection -> (now + duration + st.bonus))),
                    Nil
                  )

  /** The random bonus of a stay in `CaughtUp`, drawn when it begins (`blockWhileCaughtUp`). */
  private def drawBonus(rng: Rng, config: Config): (FiniteDuration, Rng) =
    val (seconds, next) = rng.between(0, config.maxBonus.toSeconds)
    (seconds.seconds, next)

  private def leave(st: State, why: DurationFromNow): Result =
    Result(
      st.copy(phase = Phase.PreSyncing, watching = None),
      List(Event.LeaveCaughtUp(why))
    )

  /** STAGE 1 and 2 of `blockUntilCaughtUp` (GSM.hs 371-401): there is at least one peer, all of
    * them are idle, and no candidate is preferred to our selection. A candidate that does not
    * intersect our selection holds the node back (`WhetherCandidateIsBetter False` is required).
    */
  def caughtUp(reading: Reading): Boolean =
    reading.peers.nonEmpty && reading.peers.forall(p =>
      p.idle && p.candidate == Candidate.Better(false)
    )

/** The fetch mode (`FetchMode`): bulk sync while the node is far behind, deadline once it is close,
  * and what the reference derives it from (`readFetchModeDefault`,
  * `Ouroboros/Consensus/MiniProtocol/BlockFetch/ClientInterface.hs` 86-129, Praos mode). The block
  * fetch of Scarab takes no decision from it yet; it selects the churn regime (G1.2) and is
  * published as a gauge.
  */
enum FetchMode:
  case BulkSync, Deadline

object FetchMode:
  /** Slots behind the wall clock below which the node uses deadline mode (`maxSlotsBehind`). */
  val MaxSlotsBehind: Long = 1000

  /** When the node requires bootstrap peers it is always in bulk sync; otherwise it is in deadline
    * mode when its chain is less than 1000 slots behind the current slot, and in bulk sync when it
    * is further behind or the current slot is unknown. `chainSlot` is none for an empty chain.
    */
  def of(
      sensitive: Boolean,
      currentSlot: Option[BigInt],
      chainSlot: Option[BigInt]
  ): FetchMode =
    if sensitive then BulkSync
    else
      currentSlot match
        case None => BulkSync
        case Some(now) =>
          val behind = chainSlot.fold(now + 1)(now - _)
          if behind < MaxSlotsBehind then Deadline else BulkSync
