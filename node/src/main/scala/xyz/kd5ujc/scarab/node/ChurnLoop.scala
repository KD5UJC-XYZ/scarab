// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Temporal
import cats.mtl.{Ask, Tell}
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.*

/** What the churn governor reads of the node and writes to the governor: the counters of the
  * governor, the hot valency of the local roots, the fetch mode, the targets last asked for, and
  * the request for new targets (the reference's `peerSelectionVar`, `readCounters`,
  * `getLocalRootHotTarget` and `readFetchMode`).
  */
trait ChurnWorld[F[_]]:
  def counters: F[GovernorState.Counters]
  def localHot: F[Int]
  def fetchMode: F[FetchMode]
  def judgement: F[LedgerStateJudgement]
  def requested: F[Targets]
  def request(targets: Targets): F[Unit]

/** The churn governor (`peerChurnGovernor`, `Cardano/Network/PeerSelection/Churn.hs` 125-654): it
  * does not move peers, it changes the targets the governor works towards, one set at a time in the
  * twelve steps of [[Churn]], and waits after each for the counters to meet them or for a timeout.
  * A round replaces a fifth of the active peers (the least useful by the peer metrics), then of the
  * established and the known ones, and the node sleeps until the next, 3300 s plus up to 600 s in
  * the fetch mode deadline, 900 s plus up to 60 s in bulk sync.
  *
  * The arithmetic is [[Churn]]; this is the effect that runs it. It is the one writer of the
  * governor's targets (so a change of regime during the sleep is handled here too, see
  * [[sleeping]]). Capabilities: the world through the algebra [[ChurnWorld]], the settings through
  * `Ask`, the trace through `Tell`. Randomness is the pure generator, threaded through the loop. It
  * never returns.
  */
object ChurnLoop:

  final case class Config(
      /** The targets of the node when it is not syncing: the regime's `base`. */
      base: Targets,
      /** The regime for a fetch mode (bootstrap peers configured or not). */
      regime: FetchMode => Regime.Kind,
      consensusMode: ConsensusMode = ConsensusMode.PraosMode,
      syncTargets: Targets = Targets.sync,
      intervals: Churn.Intervals = Churn.Intervals(),
      timeouts: Churn.Timeouts = Churn.Timeouts(),
      /** Whether the node has big ledger peers: the six steps for them are skipped otherwise. */
      bigLedger: Boolean = false,
      /** How often the counters and the regime are looked at while waiting. */
      poll: FiniteDuration = 500.millis,
      /** The head start for the local roots (`threadDelay 3`). */
      headStart: FiniteDuration = Regime.HeadStart,
      seed: Long = 0L
  )

  def run[F[_]: Temporal](world: ChurnWorld[F])(using
      config: Ask[F, Config],
      out: Tell[F, Churn.Trace]
  ): F[Nothing] =
    val F = Temporal[F]
    config.ask[Config].flatMap { cfg =>
      def context: F[Churn.Context] =
        config.ask[Config].flatMap { current =>
          (world.fetchMode, world.localHot, world.judgement)
            .mapN { (mode, hot, judgement) =>
              Churn.Context(
                current.regime(mode),
                hot,
                Churn.targetsFor(
                  current.consensusMode,
                  judgement,
                  current.base,
                  current.syncTargets
                )
              )
            }
        }

      /** Sleep `total`, watching the regime: when the node changes it (it left bulk sync, or was
        * far behind again) the steady targets of the new regime are asked for at once, rather than
        * at the next round, which is up to an hour away. (The reference leaves the targets of the
        * old regime until the next round; the node starts in bulk sync and leaves it once, so
        * waiting would hold it at the cut targets for the whole interval.)
        */
      def sleeping(total: FiniteDuration, from: Churn.Context): F[Unit] =
        F.tailRecM((total, from)) { (left, previous) =>
          if left <= Duration.Zero then F.pure(Right(()))
          else
            val tick = left.min(cfg.poll * 2)
            F.sleep(tick) *> context.flatMap { ctx =>
              world
                .request(Churn.steady(ctx))
                .whenA(ctx != previous)
                .as(Left((left - tick, ctx)))
            }
        }

      /** Wait until `step` reached `targets` or `timeout` passed; the counters then and whether it
        * timed out.
        */
      def waiting(
          step: Churn.Step,
          targets: Targets
      ): F[(Boolean, GovernorState.Counters)] =
        F.monotonic.flatMap { began =>
          F.tailRecM(()) { _ =>
            (world.counters, F.monotonic).flatMapN { (counters, now) =>
              if step.reached(counters, targets) then F.pure(Right((true, counters)))
              else if now - began >= step.timeout(cfg.timeouts) then
                F.pure(Right((false, counters)))
              else F.sleep(cfg.poll).as(Left(()))
            }
          }
        }

      /** One step: ask for its targets and wait for the node to meet them. Returns the absolute
        * change of the counter, which the next decrease takes as its minimum.
        */
      def step(achieved: Map[Churn.Step, Int], s: Churn.Step): F[Map[Churn.Step, Int]] =
        for
          ctx <- context
          before <- world.counters.map(s.counter)
          current <- world.requested
          targets = Churn.apply(s, ctx, current, achieved.getOrElse(_, 0))
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
          current <- config.ask[Config]
          mode <- world.fetchMode
          _ <- out.tell(Churn.Trace.Mode(mode))
          _ <- Churn.round(current.bigLedger).foldLeftM(Map.empty[Churn.Step, Int])(step).void
        yield ()

      for
        // the head start: the local roots first, the root peers after
        started <- F.monotonic
        first <- context
        _ <- world.request(Churn.head(first))
        _ <- F.sleep(cfg.headStart)
        second <- context
        _ <- world.request(Churn.steady(second))
        head <- F.monotonic.map(_ - started)
        done <- F.tailRecM[(Rng, FiniteDuration), Nothing]((Rng(cfg.seed), head)) {
          (rng, elapsed) =>
            for
              mode <- world.fetchMode
              (delay, rng1) = Churn.delay(mode, cfg.intervals, elapsed, rng)
              _ <- out.tell(Churn.Trace.Wait(delay))
              current <- context
              _ <- sleeping(delay, current)
              roundBegan <- F.monotonic
              _ <- round
              took <- F.monotonic.map(_ - roundBegan)
            yield Left((rng1, took))
        }
      yield done
    }
