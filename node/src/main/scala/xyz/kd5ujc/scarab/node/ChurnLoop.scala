// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Temporal
import cats.mtl.{Ask, Tell}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.network.genesis.PicosClock

/** What the churn governor reads of the node and writes to the governor: the counters of the
  * governor, the hot valency of the local roots, the fetch mode, the targets last asked for, the
  * request for new targets and the churn mode the hot-demotion pick ranks by (the reference's
  * `peerSelectionVar`, `readCounters`, `getLocalRootHotTarget`, `readFetchMode` and
  * `churnModeVar`).
  */
trait ChurnWorld[F[_]]:
  def counters: F[GovernorState.Counters]
  def awaitCounters(predicate: GovernorState.Counters => Boolean): F[GovernorState.Counters]
  def localHot: F[Int]
  def fetchMode: F[FetchMode]
  def judgement: F[LedgerStateJudgement]
  def requested: F[Churn.Proposal]

  /** Ask for `targets`, and write the churn mode they were computed with, at once. */
  def request(mode: ChurnMode, targets: Churn.Proposal): F[Unit]

  /** Change the targets asked for and write the churn mode, at once (`updateTargets`, `Churn.hs`
    * 241-251: `updateChurnMode` and the targets in one transaction).
    */
  def updateTargets(
      mode: ChurnMode,
      change: Churn.Proposal => Churn.Proposal
  ): F[(GovernorState.Counters, Churn.Proposal)]

  /** `writeTVar churnModeVar` alone, as a round begins (`Churn.hs` 565): the governor ranks hot
    * peers by this mode until the next write.
    */
  def setChurnMode(mode: ChurnMode): F[Unit]

/** The churn governor (`peerChurnGovernor`, `Cardano/Network/PeerSelection/Churn.hs` 125-654): it
  * does not move peers, it changes the targets the governor works towards, one set at a time in the
  * twelve steps of [[Churn]], and waits after each for the counters to meet them or for a timeout.
  * A round replaces a fifth of the active peers (the least useful by the peer metrics), then of the
  * established and the known ones, and the node sleeps until the next, 3300 s plus up to 600 s in
  * Genesis or Praos deadline mode, 900 s plus up to 60 s in Praos bulk sync.
  *
  * The arithmetic is [[Churn]]; this is the effect that runs it. It is the one writer of the
  * governor's requested targets. Like the reference, it leaves them unchanged while sleeping. It is
  * also the one writer of the churn mode: where the reference calls `updateChurnMode` (`Churn.hs`
  * 177, 201, 242, 565), together with each set of targets it asks for and alone at the start of
  * each round, never while it sleeps, so that a change of the fetch mode during the sleep reaches
  * the hot-demotion pick only with the next round. Capabilities: the world through the algebra
  * [[ChurnWorld]], the settings through `Ask`, the trace through `Tell`. Randomness is the pure
  * generator, threaded through the loop. It never returns.
  */
object ChurnLoop:

  /** @param base
    *   The targets of the node when it is not syncing: the regime's `base`.
    * @param regime
    *   The regime for a fetch mode (bootstrap peers configured or not).
    * @param headStart
    *   The head start for the local roots (`threadDelay 3`).
    */
  final case class Config(
      base: Targets,
      regime: FetchMode => Regime.Kind,
      consensusMode: ConsensusMode = ConsensusMode.PraosMode,
      syncTargets: Targets = Targets.sync,
      intervals: Churn.Intervals = Churn.Intervals(),
      timeouts: Churn.Timeouts = Churn.Timeouts(),
      headStart: FiniteDuration = Regime.HeadStart,
      seed: Long = 0L
  )

  def run[F[_]: Temporal](world: ChurnWorld[F])(using
      config: Ask[F, Config],
      out: Tell[F, Churn.Trace]
  ): F[Nothing] =
    val F = Temporal[F]
    val clock = PicosClock.system[F]
    config.ask[Config].flatMap { cfg =>
      /** What a set of targets is computed from, and the churn mode `updateChurnMode` reads with it
        * (`Churn.hs` 217-221), which is written with those targets (176-196, 200-211, 241-251).
        */
      def context: F[(Churn.Context, ChurnMode)] =
        config.ask[Config].flatMap { current =>
          (world.fetchMode, world.localHot, world.judgement).mapN { (mode, hot, judgement) =>
            (
              Churn.Context(
                if current.consensusMode == ConsensusMode.GenesisMode then Regime.Kind.Default
                else current.regime(mode),
                hot,
                Churn.targetsFor(
                  current.consensusMode,
                  judgement,
                  current.base,
                  current.syncTargets
                ),
                current.consensusMode
              ),
              ChurnMode.read(current.consensusMode, judgement, mode)
            )
          }
        }

      /** Wait until `step` reached `targets` or `timeout` passed; the counters then and whether it
        * timed out.
        */
      def waiting(
          step: Churn.Step,
          targets: Churn.Proposal
      ): F[(Boolean, GovernorState.Counters)] =
        F.race(world.awaitCounters(step.reached(_, targets)), F.sleep(step.timeout(cfg.timeouts)))
          .flatMap {
            case Left(counters) => F.pure((true, counters))
            case Right(()) =>
              world.counters.map(counters => (step.reached(counters, targets), counters))
          }

      /** One step: ask for its targets and wait for the node to meet them. Returns the absolute
        * change of the counter, which the next decrease takes as its minimum.
        */
      def step(achieved: Map[Churn.Step, Int], s: Churn.Step): F[Map[Churn.Step, Int]] =
        for
          began <- F.monotonic
          read <- context
          (ctx, churnMode) = read
          update <- world.updateTargets(
            churnMode,
            current => Churn.planRaw(s, ctx, current, achieved.getOrElse(_, 0))
          )
          (beforeCounters, targets) = update
          before = s.counter(beforeCounters)
          result <- waiting(s, targets)
          (met, counters) = result
          took <- F.monotonic.map(_ - began)
          change = s.counter(counters) - before
          _ <- out.tell(
            if met then Churn.Trace.Action(s, took, change)
            else Churn.Trace.Timeout(s, took, change)
          )
        yield achieved.updated(s, change.abs)

      // a round begins with `updateChurnMode` and its trace (`Churn.hs` 565-566)
      def round: F[Unit] =
        for
          current <- config.ask[Config]
          read <- (world.fetchMode, world.judgement).tupled
          (mode, judgement) = read
          churnMode = ChurnMode.read(current.consensusMode, judgement, mode)
          _ <- world.setChurnMode(churnMode)
          _ <- out.tell(Churn.Trace.Mode(churnMode))
          _ <- Churn.round.foldLeftM(Map.empty[Churn.Step, Int])(step).void
        yield ()

      for
        // the head start: the local roots first, the root peers after
        started <- F.monotonic
        first <- context
        _ <- world.request(first._2, Churn.Proposal.from(Churn.head(first._1)))
        _ <- F.sleep(cfg.headStart)
        second <- context
        current <- world.requested
        after = Regime.afterHead(second._1.kind, second._1.base, current)
        _ <- world.request(second._2, after)
        head <- F.monotonic.map(_ - started)
        done <- F.tailRecM[(Rng, FiniteDuration), Nothing]((Rng(cfg.seed), head)) {
          (rng, elapsed) =>
            for
              // `fuzzyDelay` reads the fetch mode and writes no churn mode (`Churn.hs` 661-668)
              mode <- world.fetchMode
              (delay, rng1) = Churn.drawDelay(
                cfg.consensusMode,
                mode,
                cfg.intervals,
                BigInt(elapsed.toNanos) * 1000,
                rng
              )
              _ <- out.tell(Churn.Trace.Wait(Rational(delay, 1000).ceil.toLong.nanos))
              _ <- clock.sleep(delay)
              roundBegan <- F.monotonic
              _ <- round
              took <- F.monotonic.map(_ - roundBegan)
            yield Left((rng1, took))
        }
      yield done
    }
