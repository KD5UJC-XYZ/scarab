// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Temporal
import cats.mtl.{Ask, Tell}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.network.genesis.PicosClock

/** What the churn governor reads of the node and writes to the governor: the counters of the
  * governor, the hot valency of the local roots, the fetch mode, the targets last asked for, and
  * the request for new targets (the reference's `peerSelectionVar`, `readCounters`,
  * `getLocalRootHotTarget` and `readFetchMode`).
  */
trait ChurnWorld[F[_]]:
  def counters: F[GovernorState.Counters]
  def awaitCounters(predicate: GovernorState.Counters => Boolean): F[GovernorState.Counters]
  def localHot: F[Int]
  def fetchMode: F[FetchMode]
  def judgement: F[LedgerStateJudgement]
  def requested: F[Churn.Proposal]
  def request(targets: Churn.Proposal): F[Unit]

/** The churn governor (`peerChurnGovernor`, `Cardano/Network/PeerSelection/Churn.hs` 125-654): it
  * does not move peers, it changes the targets the governor works towards, one set at a time in the
  * twelve steps of [[Churn]], and waits after each for the counters to meet them or for a timeout.
  * A round replaces a fifth of the active peers (the least useful by the peer metrics), then of the
  * established and the known ones, and the node sleeps until the next, 3300 s plus up to 600 s in
  * Genesis or Praos deadline mode, 900 s plus up to 60 s in Praos bulk sync.
  *
  * The arithmetic is [[Churn]]; this is the effect that runs it. It is the one writer of the
  * governor's requested targets. Like the reference, it leaves them unchanged while sleeping.
  * Capabilities: the world through the algebra [[ChurnWorld]], the settings through `Ask`, the
  * trace through `Tell`. Randomness is the pure generator, threaded through the loop. It never
  * returns.
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
      def context: F[Churn.Context] =
        config.ask[Config].flatMap { current =>
          (world.fetchMode, world.localHot, world.judgement)
            .mapN { (mode, hot, judgement) =>
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
          ctx <- context
          before <- world.counters.map(s.counter)
          current <- world.requested
          targets = Churn.planRaw(s, ctx, current, achieved.getOrElse(_, 0))
          began <- F.monotonic
          _ <- world.request(targets)
          result <- waiting(s, targets)
          (met, counters) = result
          took <- F.monotonic.map(_ - began)
          change = s.counter(counters) - before
          _ <- out.tell(
            if met then Churn.Trace.Action(s, took, change)
            else Churn.Trace.Timeout(s, took, change)
          )
        yield achieved.updated(s, change.abs)

      def round: F[Unit] =
        for
          mode <- world.fetchMode
          _ <- out.tell(Churn.Trace.Mode(mode))
          _ <- Churn.round.foldLeftM(Map.empty[Churn.Step, Int])(step).void
        yield ()

      for
        // the head start: the local roots first, the root peers after
        started <- F.monotonic
        first <- context
        _ <- world.request(Churn.Proposal.from(Churn.head(first)))
        _ <- F.sleep(cfg.headStart)
        second <- context
        current <- world.requested
        after = Regime.afterHead(second.kind, second.base, current)
        _ <- world.request(after)
        head <- F.monotonic.map(_ - started)
        done <- F.tailRecM[(Rng, FiniteDuration), Nothing]((Rng(cfg.seed), head)) {
          (rng, elapsed) =>
            for
              mode <- world.fetchMode
              (delay, rng1) = Churn.drawDelay(
                cfg.consensusMode,
                mode,
                cfg.intervals,
                BigInt(elapsed.toNanos) * 1000,
                rng
              )
              _ <- out.tell(Churn.Trace.Wait(((delay + 999) / 1000).toLong.nanos))
              _ <- clock.sleep(delay)
              roundBegan <- F.monotonic
              _ <- round
              took <- F.monotonic.map(_ - roundBegan)
            yield Left((rng1, took))
        }
      yield done
    }
