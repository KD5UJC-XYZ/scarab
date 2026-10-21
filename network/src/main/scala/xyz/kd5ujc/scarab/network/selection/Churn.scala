// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Rational

/** The churn governor's arithmetic: which targets each step of a round asks for, how long it waits
  * for the counters to meet them, and how long the round sleeps. Pure; the effect that runs it is
  * `ChurnLoop` in `node`.
  *
  * A port of `peerChurnGovernor` (`cardano-diffusion/lib/Cardano/Network/PeerSelection/Churn.hs`,
  * ouroboros-network 1.2.0.0; source provenance is recorded in docs/design/peer-selection.md). The
  * churn governor does not move peers. It writes the *targets* the governor works towards, one set
  * at a time, and waits (for the counters to meet them, or for a timeout) before the next: decrease
  * the active peers so that the governor demotes the least useful ones (the metrics of
  * [[PeerMetrics]]), increase them again so that it promotes others, then refresh the established
  * and known sets (`Churn.hs` 117-123, 561-654).
  *
  * Differences from the reference are recorded in docs/design/peer-selection.md section 7b:
  *   - a target that would be insane (the reference's Monitor silently ignores an insane update,
  *     `Monitor.hs` 68-69) is clamped instead, so the round is not wasted: the root target is kept
  *     within the known target, a negative active target is 0, the established target never below
  *     the active one (the reference's update is ignored when the base established target equals
  *     the active one in the bootstrap regime); with the clamps no step yields an insane set for
  *     any sane base (a property test), and at the defaults none is needed;
  *   - the six big-ledger steps are left out of a round while the node has no big ledger peers
  *     ([[round]] with `bigLedger = false`): their counters are zero, so each increase would only
  *     wait out its timeout (200 s) with nothing to wait for.
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
    def reached(c: GovernorState.Counters, t: Targets): Boolean = reached(c, Proposal.from(t))

    def reached(c: GovernorState.Counters, t: Proposal): Boolean = this match
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

  /** All twelve native steps, including big-ledger targets when ledger queries are disabled. */
  def round: List[Step] =
    Step.values.toList

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
  ):
    /** The waits for connecting and for the known set at most `limit` (for tests that cannot wait
      * minutes). The waits for a hot peer to be deactivated and a connection to be closed stay as
      * they are: at the tip a hot session ends only when the peer's next block comes.
      */
    def within(limit: FiniteDuration): Timeouts =
      copy(
        establishConnection = establishConnection.min(limit),
        short = short.min(limit),
        peerRequest = peerRequest.min(limit)
      )

  /** What a step reads besides the targets in force: the regime, the hot valency of the local roots
    * (`getLocalRootHotTarget`) and the targets of the regime (the reference's `base`).
    */
  final case class Context(
      kind: Regime.Kind,
      localHot: Int,
      base: Targets,
      consensusMode: ConsensusMode = ConsensusMode.PraosMode
  )

  /** The reference target TVar can contain an insane intermediate churn proposal. The peer governor
    * rejects that update, but churn still waits against it and uses it in the next step. Keeping
    * the proposal distinct preserves the invariant of validated configuration targets.
    */
  final case class Proposal(
      root: Int,
      known: Int,
      established: Int,
      active: Int,
      knownBig: Int,
      establishedBig: Int,
      activeBig: Int
  ):
    def validated: Either[Targets.Problem, Targets] =
      Targets.validated(root, known, established, active, knownBig, establishedBig, activeBig)

  object Proposal:
    def from(t: Targets): Proposal =
      Proposal(t.root, t.known, t.established, t.active, t.knownBig, t.establishedBig, t.activeBig)

  /** `getPeerSelectionTargets` (`Cardano/Network/PeerSelection/Churn.hs` 98-107): Genesis uses its
    * sync targets only while the ledger judgement is too old.
    */
  def targetsFor(
      consensusMode: ConsensusMode,
      judgement: LedgerStateJudgement,
      deadlineTargets: Targets,
      syncTargets: Targets
  ): Targets =
    if consensusMode == ConsensusMode.GenesisMode && judgement == LedgerStateJudgement.TooOld then
      syncTargets
    else deadlineTargets

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

  /** Validate a raw proposal for consumers that require accepted governor targets. */
  def plan(
      step: Step,
      ctx: Context,
      current: Targets,
      achieved: Step => Int
  ): Either[Targets.Problem, Targets] =
    planRaw(step, ctx, Proposal.from(current), achieved).validated

  /** `updateTargets` writes these raw records; `sanePeerSelectionTargets` is checked by the
    * independent peer governor, not by the churn writer.
    */
  def planRaw(
      step: Step,
      ctx: Context,
      current: Proposal,
      achieved: Step => Int
  ): Proposal =
    import Step.*
    val base = ctx.base
    val least = step.minimumFrom.fold(1)(achieved)
    val sync = ctx.kind != Regime.Kind.Default
    val hotPlusOne = (ctx.localHot.max(1) + 1).min(base.active)
    step match
      // 285-294, 303-312: in the sync regimes the active target stays near the local roots' valency
      case DecreaseActive =>
        current.copy(active =
          if sync then ctx.localHot.max(1).min(base.active - 1) else decrease(base.active)
        )
      case IncreaseActive =>
        current.copy(active = if sync then hotPlusOne else base.active)
      // 322-336, 364-385: the bootstrap regime keeps the established target at the active one
      case IncreaseEstablished =>
        current.copy(established =
          if ctx.kind == Regime.Kind.BootstrapPraosSync then
            (current.active + 1).min(base.established)
          else base.established
        )
      case DecreaseEstablished =>
        current.copy(established =
          if ctx.kind == Regime.Kind.BootstrapPraosSync then
            current.active.min(base.established - 1)
          else decreaseWithMin(least)(base.established - base.active) + base.active
        )
      // 396-428: the sync regimes ask for at most one active big ledger peer
      case DecreaseActiveBig =>
        current.copy(activeBig = if sync then 1.min(base.activeBig) else decrease(base.activeBig))
      case IncreaseActiveBig =>
        current.copy(activeBig = if sync then 1.min(base.activeBig) else base.activeBig)
      case IncreaseEstablishedBig => current.copy(establishedBig = base.establishedBig)
      case DecreaseEstablishedBig =>
        current.copy(establishedBig =
          decreaseWithMin(least)(base.establishedBig - base.activeBig) + base.activeBig
        )
      case IncreaseKnownBig => current.copy(knownBig = base.knownBig)
      case DecreaseKnownBig =>
        current.copy(knownBig =
          decreaseWithMin(least)(base.knownBig - base.establishedBig) + base.establishedBig
        )
      // The root target never grows; native churn does not clamp it to the new known target.
      // Monitor.hs independently ignores proposals that fail sanePeerSelectionTargets.
      case DecreaseKnown =>
        val known = decreaseWithMin(least)(base.known - base.established) + base.established
        val root = base.root.min(decrease(base.root - base.established) + base.established)
        current.copy(root = root, known = known)
      case IncreaseKnown => current.copy(root = base.root, known = base.known)

  /** The first targets (`Churn.hs` 176-196): see [[Regime.initial]]. */
  def head(ctx: Context): Targets =
    Regime.initial(ctx.consensusMode, ctx.kind, ctx.base, ctx.localHot)

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
    def of(consensusMode: ConsensusMode, mode: FetchMode): (FiniteDuration, FiniteDuration) =
      if consensusMode == ConsensusMode.GenesisMode then (deadline, deadlineFuzz) else of(mode)

    def of(mode: FetchMode): (FiniteDuration, FiniteDuration) = mode match
      case FetchMode.Deadline => (deadline, deadlineFuzz)
      case FetchMode.BulkSync => (bulkSync, bulkSyncFuzz)

  object Intervals:
    /** The same interval in both modes with a tenth of it as fuzz (for tests). */
    def every(interval: FiniteDuration): Intervals =
      Intervals(interval, interval / 10, interval, interval / 10)

  /** Nanosecond presentation of the exact policy wait, for existing duration consumers. */
  def delay(
      mode: FetchMode,
      intervals: Intervals,
      elapsed: FiniteDuration,
      rng: Rng
  ): (FiniteDuration, Rng) =
    delay(ConsensusMode.PraosMode, mode, intervals, elapsed, rng)

  def delay(
      consensusMode: ConsensusMode,
      mode: FetchMode,
      intervals: Intervals,
      elapsed: FiniteDuration,
      rng: Rng
  ): (FiniteDuration, Rng) =
    val (picos, next) =
      drawDelay(consensusMode, mode, intervals, BigInt(elapsed.toNanos) * 1000, rng)
    (Rational(picos, 1000).floor.toLong.nanos, next)

  /** The native `realToFrac Double :: DiffTime` floors the exact binary rational at picoseconds.
    * Keep signed waits: an overlong round traces a negative wait and sleeps immediately.
    */
  def delayWithFuzz(
      consensusMode: ConsensusMode,
      mode: FetchMode,
      intervals: Intervals,
      elapsedPicos: BigInt,
      fuzzSeconds: Rational
  ): BigInt =
    val (interval, _) = intervals.of(consensusMode, mode)
    BigInt(interval.toNanos) * 1000 + (fuzzSeconds * Rational(
      BigInt(10).pow(12)
    )).floor - elapsedPicos

  /** Random streams are interpreter inputs; native fixtures supply the actual native Double draws
    * to [[delayWithFuzz]] rather than assuming equal PRNG seeds imply equal streams.
    */
  def drawDelay(
      consensusMode: ConsensusMode,
      mode: FetchMode,
      intervals: Intervals,
      elapsedPicos: BigInt,
      rng: Rng
  ): (BigInt, Rng) =
    val (_, fuzz) = intervals.of(consensusMode, mode)
    val (bits, next) = rng.nextLong
    val seconds = ((bits >>> 11).toDouble / 9007199254740991.0) * (fuzz.toNanos.toDouble / 1e9)
    val raw = java.lang.Double.doubleToRawLongBits(seconds)
    val mantissa = raw & 0x000fffffffffffffL
    val exponent = ((raw >>> 52) & 0x7ff).toInt
    val significand =
      if exponent == 0 then BigInt(mantissa) else BigInt(mantissa) + (BigInt(1) << 52)
    val shift = if exponent == 0 then -1074 else exponent - 1075
    val exact =
      if shift >= 0 then Rational(significand << shift)
      else Rational(significand, BigInt(1) << -shift)
    (delayWithFuzz(consensusMode, mode, intervals, elapsedPicos, exact), next)

  /** What the churn governor tells (`TraceChurnMode`, `TraceChurnAction`, `TraceChurnTimeout`,
    * `TraceChurnWait`; `Types.hs` 1843-1855, `Churn.hs` 269-276, 566): the step, how long it took
    * and by how much its counter changed.
    */
  enum Trace:
    case Mode(mode: FetchMode)
    case Action(step: Step, took: FiniteDuration, change: Int)
    case Timeout(step: Step, took: FiniteDuration, change: Int)
    case Wait(delay: FiniteDuration)
