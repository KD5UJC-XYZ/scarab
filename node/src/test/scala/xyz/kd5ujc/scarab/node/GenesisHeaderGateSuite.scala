// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import cats.mtl.{Ask, Stateful, Tell}
import cats.syntax.all.*
import scala.annotation.unused
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{
  BlockRef,
  ChainSelection,
  Compute,
  HeaderRules,
  SelectView,
  VolatileChain
}
import xyz.kd5ujc.scarab.consensus.HeaderRules.Outcome
import xyz.kd5ujc.scarab.consensus.state.ProtocolState
import xyz.kd5ujc.scarab.ledger.{Fixtures, GenesisInternalProblem, Header}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.genesis.{LeakyBucket, LimitOnPatience}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}

class GenesisHeaderGateSuite extends FailsOnUncaught:
  private given Tell[IO, GenesisInternalProblem] with
    val functor = summon[cats.Functor[IO]]
    def tell(problem: GenesisInternalProblem): IO[Unit] =
      IO.raiseError(new AssertionError(problem.message))
  private def at(height: BigInt): NodeHeaderState = NodeHeaderState.Shelley(
    ProtocolState.initial(Era.Babbage, Bytes.empty),
    None,
    Some(height),
    None
  )
  private def model(
      horizon: Int,
      timeHorizon: Option[Int] = None
  ): HeaderRules[BigInt, NodeHeaderState] & HeaderRules.Timing[BigInt, NodeHeaderState] =
    new HeaderRules[BigInt, NodeHeaderState] with HeaderRules.Timing[BigInt, NodeHeaderState]:
      def tiebreaker(@unused anchor: BigInt): ChainSelection.Tiebreaker =
        ChainSelection.Tiebreaker.Unrestricted
      def prepareValidation(
          anchor: BigInt,
          tip: Option[BlockRef],
          previous: NodeHeaderState,
          header: Header
      ): HeaderRules.Prepared[NodeHeaderState] =
        if header.blockNo > anchor + horizon then
          HeaderRules.Prepared.Unknown(HeaderRules.ForecastReason.Horizon(header.slot, anchor))
        else HeaderRules.Prepared.Check(() => validate(anchor, tip, previous, header))
      def forecastTime(
          anchor: BigInt,
          @unused tip: Option[BlockRef],
          @unused previous: NodeHeaderState,
          header: Header
      ): HeaderRules.TimeForecast =
        if timeHorizon.exists(window => header.blockNo > anchor + window) then
          HeaderRules.TimeForecast.Unknown(HeaderRules.ForecastReason.Horizon(header.slot, anchor))
        else HeaderRules.TimeForecast.Known(header.slot * BigInt(10).pow(12))
      def at(state: BigInt): NodeHeaderState = GenesisHeaderGateSuite.this.at(state)
      def validate(
          anchor: BigInt,
          @unused tip: Option[BlockRef],
          @unused previous: NodeHeaderState,
          header: Header
      ): Outcome[NodeHeaderState] =
        if header.blockNo > anchor + horizon then
          Outcome.Later(HeaderRules.ForecastReason.Horizon(header.slot, anchor))
        else Outcome.Valid(at(header.blockNo))
  private def selected(
      state: Ref[IO, VolatileChain[BigInt]],
      headers: Vector[Header]
  ): Ask[IO, ChainSelection.Local] =
    new Ask[IO, ChainSelection.Local]:
      val applicative = summon[cats.Applicative[IO]]
      def ask[E2 >: ChainSelection.Local]: IO[E2] = state.get.map { c =>
        def view(ref: BlockRef): SelectView = headers
          .find(_.point == ref.point)
          .fold(fail(s"missing selection fixture ${ref.point}"))(SelectView.of)
        ChainSelection.Local(
          c.anchor.point,
          c.anchor.block.fold(SelectView(-1, 0, None, 0, None))(view),
          c.blocks.map((ref, _) => ChainSelection.LocalBlock(ref.point, view(ref)))
        )
      }

  private def chain(headers: Vector[Header], k: Int): IO[VolatileChain[BigInt]] =
    headers.foldLeftM(VolatileChain.genesis[BigInt](k, -1)) { (before, header) =>
      IO.fromEither(
        before.extend(header, header.blockNo).leftMap(e => new AssertionError(e.message))
      ).map(_._1)
    }

  test("canonical snapshots include and can graft the local immutable-to-intersection prefix") {
    val headers = Fixtures.chain(5)
    for
      applied <- chain(headers.take(4), 50)
      state <- Ref.of[IO, VolatileChain[BigInt]](applied)
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      given Ask[IO, ChainSelection.Local] = selected(state, headers)
      check = HeaderCheck.forGenesisChain[IO, BigInt](model(100), Compute.inline[IO], 100)
      gate <- check.start(applied.tip)
      original <- gate.snapshot
      snapshot <- IO.fromOption(original)(new AssertionError("canonical snapshot missing"))
      _ <- IO(assertEquals(snapshot.points, ChainSync.Point.Origin +: headers.take(4).map(_.point)))
      local <- summon[Ask[IO, ChainSelection.Local]].ask[ChainSelection.Local]
      result <- gate.graft(snapshot, headers(1).point, local)
      _ <- IO(assertEquals(result, Right(())))
      restored <- gate.snapshot
      _ <- IO {
        assertEquals(
          restored.map(_.points),
          Some(ChainSync.Point.Origin +: headers.take(2).map(_.point))
        )
        assertEquals(restored.flatMap(_.tipState), Some(at(headers(1).blockNo)))
      }
      accepted <- Fault.fiber(
        gate.acceptOrRestart(Vector(SyncEvent.RollForward(headers(2), ChainSync.Tip.Origin)))
      )
      _ <- IO(assertEquals(accepted, Right(HeaderGate.Acceptance.Accepted)))
      counts <- gate.counts
      _ <- IO(assertEquals(counts, HeaderGate.Counts(1, 0, 0)))
      missing = Fixtures.chain(1, salt = 9).head.point
      refused <- gate.graft(snapshot, missing, local)
      _ <- IO(assertEquals(refused, Left(HeaderCheck.GraftError.MissingPoint(missing))))
      noState <- gate.graft(snapshot.copy(baseState = None), snapshot.base, local)
      _ <- IO(assertEquals(noState, Left(HeaderCheck.GraftError.MissingState(snapshot.base))))
      noView <- gate.graft(snapshot.copy(baseView = None), snapshot.base, local)
      _ <- IO(assertEquals(noView, Left(HeaderCheck.GraftError.MissingSelection(snapshot.base))))
    yield ()
  }

  test(
    "a forecast wait ends with typed reintersection when the retained common ledger disappears"
  ) {
    TestControl.executeEmbed {
      val headers = Fixtures.chain(4)
      for
        applied <- chain(headers.take(2), 50)
        replacement <- chain(Fixtures.chain(5, salt = 9), 2)
        state <- Ref.of[IO, VolatileChain[BigInt]](applied)
        given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
        given Ask[IO, ChainSelection.Local] = selected(
          state,
          headers ++ Fixtures.chain(5, salt = 9)
        )
        _ <- LimitOnPatience.resource[IO](LeakyBucket.Config.disabled, IO.unit, error => IO(fail(error.message))).use { patience =>
          val check = HeaderCheck
            .forGenesisChain[IO, BigInt](model(1), Compute.inline[IO], 100, poll = 1.millis)
            .withPatience(patience)
          for
            gate <- check.start(applied.tip)
            _ <- Fault.fiber(
              gate.accept(Vector(SyncEvent.RollForward(headers(2), ChainSync.Tip.Origin)))
            )
            waiting <- Fault
              .fiber(
                gate
                  .acceptOrRestart(Vector(SyncEvent.RollForward(headers(3), ChainSync.Tip.Origin)))
              )
              .start
            _ <- IO.sleep(2.millis)
            _ <- state.set(replacement)
            outcome <- waiting.joinWithNever
            _ <- IO(assertEquals(outcome, Right(HeaderGate.Acceptance.Restart(headers(2).point))))
            bucket <- patience.snapshot
            _ <- IO(assert(!bucket.paused))
            counts <- gate.counts
            _ <- IO(assertEquals(counts.validated, 1L))
          yield ()
        }
      yield ()
    }
  }

  test("forecast refusal compares the validated candidate, excluding the incoming header") {
    TestControl.executeEmbed {
      val headers = Fixtures.chain(4)
      for
        applied <- chain(headers.take(2), 50)
        state <- Ref.of[IO, VolatileChain[BigInt]](applied)
        given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
        given Ask[IO, ChainSelection.Local] = selected(state, headers)
        gate <- HeaderCheck
          .forGenesisChain[IO, BigInt](model(0), Compute.inline[IO], 100)
          .start(applied.tip)
        result <- Fault.fiber(
          gate.acceptOrRestart(Vector(SyncEvent.RollForward(headers(2), ChainSync.Tip.Origin)))
        )
        _ <- IO(
          assertEquals(
            result,
            Left(
              Fault.Failed(
                xyz.kd5ujc.scarab.ledger.PeerError.Genesis(
                  xyz.kd5ujc.scarab.ledger.PeerError.GenesisProblem
                    .CandidateTooSparse(applied.tip, applied.tip, applied.tip)
                )
              )
            )
          )
        )
      yield ()
    }
  }

  test("an unavailable time forecast applies the same native sparse-candidate refusal") {
    TestControl.executeEmbed {
      val headers = Fixtures.chain(4)
      for
        applied <- chain(headers.take(2), 50)
        state <- Ref.of[IO, VolatileChain[BigInt]](applied)
        given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
        given Ask[IO, ChainSelection.Local] = selected(state, headers)
        gate <- HeaderCheck
          .forGenesisChain[IO, BigInt](model(100, Some(0)), Compute.inline[IO], 100)
          .start(applied.tip)
        result <- gate.forecastTime(headers(2))
        _ <- IO(
          assertEquals(
            result,
            Some(
              HeaderGate.TimeForecast.Refused(
                xyz.kd5ujc.scarab.ledger.PeerError.GenesisProblem
                  .CandidateTooSparse(applied.tip, applied.tip, applied.tip)
              )
            )
          )
        )
      yield ()
    }
  }

  test(
    "FindIntersect uses the fragment and exact selection views captured before the local chain switches forks"
  ) {
    val offered = Fixtures.chain(5)
    val replacement = Fixtures.chain(5, salt = 19)
    for
      original <- chain(offered.take(4), 50)
      state <- Ref.of[IO, VolatileChain[BigInt]](original)
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      given Ask[IO, ChainSelection.Local] = selected(state, offered ++ replacement)
      check = HeaderCheck.forGenesisChain[IO, BigInt](model(100), Compute.inline[IO], 100)
      captured <- check.capture.flatMap(c =>
        IO.fromOption(c)(new AssertionError("missing canonical capture"))
      )
      _ = assertEquals(
        captured.points,
        offered.take(4).reverse.map(_.point) :+ ChainSync.Point.Origin
      )
      switched <- chain(replacement.take(4), 50)
      _ <- state.set(switched)
      gate <- captured.start(offered(1).point)
      snapshot <- gate.snapshot.flatMap(c =>
        IO.fromOption(c)(new AssertionError("missing snapshot"))
      )
      _ = assertEquals(snapshot.points, ChainSync.Point.Origin +: offered.take(2).map(_.point))
      _ = assertEquals(snapshot.baseView.map(_.value), Some(SelectView.of(offered(1))))
      _ = assert(snapshot.prefix.forall(_.view.nonEmpty))
      capturedSelection <- gate.intersectionSelection
      _ = assert(capturedSelection.exists(_.contains(offered(3).point)))
      _ = assert(!capturedSelection.exists(_.contains(replacement(3).point)))
      accepted <- Fault.fiber(
        gate.acceptOrRestart(Vector(SyncEvent.RollForward(offered(2), ChainSync.Tip.Origin)))
      )
      _ = assertEquals(accepted, Right(HeaderGate.Acceptance.Accepted))
      refreshed <- gate.intersectionSelection
      _ = assert(refreshed.exists(_.contains(replacement(3).point)))
    yield ()
  }

  test(
    "rollback outside the captured candidate ends normally even if the new local fork contains it"
  ) {
    val offered = Fixtures.chain(5)
    val replacement = Fixtures.chain(5, salt = 19)
    for
      original <- chain(offered.take(4), 50)
      state <- Ref.of[IO, VolatileChain[BigInt]](original)
      given Stateful[IO, VolatileChain[BigInt]] = RefStateful(state)
      given Ask[IO, ChainSelection.Local] = selected(state, offered ++ replacement)
      check = HeaderCheck.forGenesisChain[IO, BigInt](model(100), Compute.inline[IO], 100)
      captured <- check.capture.flatMap(c =>
        IO.fromOption(c)(new AssertionError("missing canonical capture"))
      )
      switched <- chain(replacement.take(4), 50)
      _ <- state.set(switched)
      gate <- captured.start(offered(1).point)
      outcome <- Fault.fiber(
        gate.acceptOrRestart(
          Vector(SyncEvent.RollBackward(replacement(1).point, ChainSync.Tip.Origin))
        )
      )
      _ = assertEquals(outcome, Right(HeaderGate.Acceptance.End(replacement(1).point)))
      snapshot <- gate.snapshot
      _ = assertEquals(snapshot.map(_.tipPoint), Some(offered(1).point))
    yield ()
  }
