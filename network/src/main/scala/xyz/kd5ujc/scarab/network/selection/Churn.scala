// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.concurrent.duration.*

/** The churn governor's arithmetic: which targets each step of a round asks for, how long it waits
  * for the counters to meet them, and how long the round sleeps. Pure; the effect that runs it is
  * `ChurnLoop` in `node`.
  *
  * A port of `peerChurnGovernor` (`cardano-diffusion/lib/Cardano/Network/PeerSelection/Churn.hs`,
  * ouroboros-network 1.2.0.0; the file of main in the workspace is identical at the lines cited).
  * The churn governor does not move peers. It writes the *targets* the governor works towards, one
  * set at a time, and waits (for the counters to meet them, or for a timeout) before the next:
  * decrease the active peers so that the governor demotes the least useful ones (the metrics of
  * [[PeerMetrics]]), increase them again so that it promotes others, then refresh the established
  * and known sets (`Churn.hs` 117-123, 561-654).
  *
  * Differences from the reference, both small and both written in the design document (7b):
  *   - a target that would be insane (the reference's Monitor silently ignores an insane update,
  *     `Monitor.hs` 68-69) is clamped instead, so the round is not wasted: the root target is kept
  *     within the known target, a negative active target is 0; the property test shows the clamps
  *     are never needed for sane base targets with the defaults, and that no step yields an insane
  *     set;
  *   - the six big-ledger steps are left out of a round while the node has no big ledger peers
  *     ([[round]] with `bigLedger = false`, until G1.3): their counters are zero, so each increase
  *     would only wait out its timeout (200 s) with nothing to wait for.
  */
object Churn:

  /** The twelve steps of a round, in the reference's order (`Churn.hs` 568-650). */
  enum Step:
    case DecreaseActiveBig, IncreaseActiveBig, DecreaseEstablishedBig, DecreaseKnownBig,
      IncreaseKnownBig, IncreaseEstablishedBig, DecreaseActive, IncreaseActive,
      DecreaseEstablished, DecreaseKnown, IncreaseKnown, IncreaseEstablished

    /** Whether the step is about big ledger peers. */
    def bigLedger: Boolean = this match
      case DecreaseActiveBig | IncreaseActiveBig | DecreaseEstablishedBig | DecreaseKnownBig |
          IncreaseKnownBig | IncreaseEstablishedBig =>
        true
      case _ => false

    /** The 1-based position in a round. */
    def number: Int = ordinal + 1

    /** The counter the step changes (`getCounter` of `updateTargets`). */
    def counter(c: GovernorState.Counters): Int = this match
      case DecreaseActiveBig | IncreaseActiveBig           => c.activeBigLedger
      case DecreaseEstablishedBig | IncreaseEstablishedBig => c.establishedBigLedger
      case DecreaseKnownBig | IncreaseKnownBig             => c.knownBigLedger
      case DecreaseActive | IncreaseActive                 => c.active
      case DecreaseEstablished | IncreaseEstablished       => c.established
      case DecreaseKnown | IncreaseKnown                   => c.known

    /** How long the step waits for the counters (`updateTargets`' `timeoutDelay`). */
    def timeout(t: Timeouts): FiniteDuration = this match
      case DecreaseActiveBig | DecreaseActive           => t.deactivate
      case DecreaseEstablishedBig | DecreaseEstablished => 1.second + t.closeConnection
      case IncreaseKnownBig | IncreaseKnown             => t.peerRequest * 2 + t.short
      case IncreaseEstablishedBig | IncreaseEstablished => t.establishConnection
      case IncreaseActiveBig | DecreaseKnownBig | IncreaseActive | DecreaseKnown => t.short

    /** The step whose achieved change this step takes as its minimum decrease (`decreaseWithMin`:
      * the established and known sets also lose what the set above them lost).
      */
    def minimumFrom: Option[Step] = this match
      case DecreaseEstablishedBig => Some(DecreaseActiveBig)
      case DecreaseKnownBig       => Some(DecreaseEstablishedBig)
      case DecreaseEstablished    => Some(DecreaseActive)
      case DecreaseKnown          => Some(DecreaseEstablished)
      case _                      => None

    /** The step is a decrease (the counters must fall to the target), else an increase. */
    def decreasing: Boolean = this match
      case DecreaseActiveBig | DecreaseEstablishedBig | DecreaseKnownBig | DecreaseActive |
          DecreaseEstablished | DecreaseKnown =>
        true
      case _ => false

    /** The check of `updateTargets` (`checkCounters`): have the counters met the targets? */
    def reached(c: GovernorState.Counters, t: Targets): Boolean = this match
      case DecreaseActiveBig      => c.activeBigLedger <= t.activeBig
      case IncreaseActiveBig      => c.activeBigLedger >= t.activeBig
      case DecreaseEstablishedBig => c.establishedBigLedger <= t.establishedBig
      case IncreaseEstablishedBig => c.establishedBigLedger >= t.establishedBig
      case DecreaseKnownBig       => c.knownBigLedger <= t.knownBig
      case IncreaseKnownBig       => c.knownBigLedger >= t.knownBig
      case DecreaseActive         => c.active <= t.active
      case IncreaseActive         => c.active >= t.active
      case DecreaseEstablished    => c.established <= t.established
      case IncreaseEstablished    => c.established >= t.established
      // the root target is one-sided: a decrease does not wait for the roots to fall
      case DecreaseKnown => c.known <= t.known
      case IncreaseKnown => c.root >= t.root && c.known >= t.known

  /** The twelve steps, or the six that concern ordinary peers while there are no big ledger peers.
    */
  def round(bigLedger: Boolean): List[Step] =
    Step.values.toList.filter(s => bigLedger || !s.bigLedger)

  /** The timeouts of a step (`Policies.hs` 31, 41, 49 and `Churn.hs` 688-689, 599): the reference's
    * `deactivateTimeout`, `closeConnectionTimeout`, `churnEstablishConnectionTimeout`,
    * `shortTimeout` and `pcaPeerRequestTimeout` (`policyPeerShareOverallTimeout`, `Diffusion.hs`
    * 708).
    */
  final case class Timeouts(
      deactivate: FiniteDuration = 300.seconds,
      closeConnection: FiniteDuration = 120.seconds,
      establishConnection: FiniteDuration = 60.seconds,
      short: FiniteDuration = 60.seconds,
      peerRequest: FiniteDuration = 10.seconds
  )

  /** What a step reads besides the targets in force: the regime, the hot valency of the local roots
    * (`getLocalRootHotTarget`) and the targets of the regime (the reference's `base`).
    */
  final case class Context(kind: Regime.Kind, localHot: Int, base: Targets)

  /** `decrease` (`Churn.hs` 692-693): replace 20% of a target, or at least one. */
  def decrease(n: Int): Int = decreaseWithMin(1)(n)

  /** `decreaseWithMin` (696-697): replace 20%, or at least `least` (and one), never below 0. */
  def decreaseWithMin(least: Int)(n: Int): Int = (n - least.max(1).max(n / 5)).max(0)

  /** The targets after `step` (`modifyTargets regime ltt base` applied to the targets in force,
    * `updateTargets` 238-251): only the fields the step names change. `achieved` is what the
    * earlier steps of the round achieved (the absolute change of their counter), which the
    * decreases of the established and known sets use as their minimum. An insane result keeps the
    * targets in force.
    */
  def apply(step: Step, ctx: Context, current: Targets, achieved: Step => Int): Targets =
    plan(step, ctx, current, achieved).getOrElse(current)

  /** [[apply]] without the fallback: the targets the step asks for, or why they are not sane. For
    * sane base targets it is always a `Right` (a property of the tests).
    */
  def plan(
      step: Step,
      ctx: Context,
      current: Targets,
      achieved: Step => Int
  ): Either[Targets.Problem, Targets] =
    import Step.*
    val base = ctx.base
    val least = step.minimumFrom.fold(1)(achieved)
    val sync = ctx.kind != Regime.Kind.Default
    val hotPlusOne = (ctx.localHot.max(1) + 1).min(base.active)
    step match
      // 285-294, 303-312: in the sync regimes the active target stays near the local roots' valency
      case DecreaseActive =>
        current.revised(active =
          if sync then ctx.localHot.max(1).min(base.active - 1).max(0) else decrease(base.active)
        )
      case IncreaseActive =>
        current.revised(active = if sync then hotPlusOne else base.active)
      // 322-336, 364-385: the bootstrap regime keeps the established target at the active one
      case IncreaseEstablished =>
        current.revised(established =
          if ctx.kind == Regime.Kind.BootstrapPraosSync then
            (current.active + 1).min(base.established)
          else base.established
        )
      case DecreaseEstablished =>
        current.revised(established =
          if ctx.kind == Regime.Kind.BootstrapPraosSync then
            current.active.min(base.established - 1).max(0)
          else decreaseWithMin(least)(base.established - base.active) + base.active
        )
      // 396-428: the sync regimes ask for at most one active big ledger peer
      case DecreaseActiveBig =>
        current.revised(activeBig =
          if sync then 1.min(base.activeBig) else decrease(base.activeBig)
        )
      case IncreaseActiveBig =>
        current.revised(activeBig = if sync then 1.min(base.activeBig) else base.activeBig)
      case IncreaseEstablishedBig => current.revised(establishedBig = base.establishedBig)
      case DecreaseEstablishedBig =>
        current.revised(establishedBig =
          decreaseWithMin(least)(base.establishedBig - base.activeBig) + base.activeBig
        )
      case IncreaseKnownBig => current.revised(knownBig = base.knownBig)
      case DecreaseKnownBig =>
        current.revised(knownBig =
          decreaseWithMin(least)(base.knownBig - base.establishedBig) + base.establishedBig
        )
      // 463-480: the root target is clamped from above so that it never grows, and here also to
      // the known target it must stay within
      case DecreaseKnown =>
        val known = decreaseWithMin(least)(base.known - base.established) + base.established
        val root = base.root.min(decrease(base.root - base.established) + base.established)
        current.revised(root = root.min(known), known = known)
      case IncreaseKnown => current.revised(root = base.root, known = base.known)

  /** The first targets (`Churn.hs` 176-196): see [[Regime.initial]]. */
  def head(ctx: Context): Targets = Regime.initial(ctx.kind, ctx.base, ctx.localHot)

  /** The targets after the head start (`Churn.hs` 200-211): see [[Regime.steady]]. */
  def steady(ctx: Context): Targets = Regime.steady(ctx.kind, ctx.base, ctx.localHot)

  /** How long a round sleeps: `interval + fuzz - elapsed`, with a uniform fuzz up to `fuzz`
    * (`fuzzyDelay`, `Churn.hs` 661-683). In the Praos fetch mode deadline it is the long interval,
    * in bulk sync the short one.
    */
  final case class Intervals(
      deadline: FiniteDuration = 3300.seconds,
      deadlineFuzz: FiniteDuration = 600.seconds,
      bulkSync: FiniteDuration = 900.seconds,
      bulkSyncFuzz: FiniteDuration = 60.seconds
  ):
    def of(mode: FetchMode): (FiniteDuration, FiniteDuration) = mode match
      case FetchMode.Deadline => (deadline, deadlineFuzz)
      case FetchMode.BulkSync => (bulkSync, bulkSyncFuzz)

  /** The wait before the next round, from the generator. The fuzz has a resolution of a
    * millisecond; a round that took longer than its interval waits not at all.
    */
  def delay(
      mode: FetchMode,
      intervals: Intervals,
      elapsed: FiniteDuration,
      rng: Rng
  ): (FiniteDuration, Rng) =
    val (interval, fuzz) = intervals.of(mode)
    val (millis, next) = rng.between(0L, fuzz.toMillis)
    ((interval + millis.millis - elapsed).max(Duration.Zero), next)

  /** What the churn governor tells (`TraceChurnMode`, `TraceChurnAction`, `TraceChurnTimeout`,
    * `TraceChurnWait`; `Types.hs` 1843-1855, `Churn.hs` 269-276, 566): the step, how long it took
    * and by how much its counter changed.
    */
  enum Trace:
    case Mode(mode: FetchMode)
    case Action(step: Step, took: FiniteDuration, change: Int)
    case Timeout(step: Step, took: FiniteDuration, change: Int)
    case Wait(delay: FiniteDuration)
