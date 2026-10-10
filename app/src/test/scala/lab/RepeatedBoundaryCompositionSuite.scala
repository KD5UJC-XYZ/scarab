// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.ledger.{ConwayEpochBoundary as B, ConwayNativeLikelihood as N}

class RepeatedBoundaryCompositionSuite extends munit.FunSuite:
  private val F = SyntheticBoundaryCompositionFixture
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def runtime(limit: Int = 3) = CoherentSequence
    .createWithRepeatedBoundary[IO](
      F.context,
      F.stakeSeed,
      F.boundaryProfile,
      F.governance.copy(accounts = F.governance.accounts.map((c, a) => c -> a.copy(vote = None))),
      F.nonMyopic,
      F.initialPots,
      Map.empty,
      Map.empty,
      F.pin,
      limit
    )
    .map(get(_))
  private val noOracle: (B.Frozen, Bytes) => IO[N.Generated] = (_, _) =>
    IO.raiseError(new IllegalStateException("unexpected freeze"))
  private def step(r: CoherentSequence.Runtime[IO], block: SequenceInput.Block) = for
    old <- r.snapshot
    preview <-
      if block.header.slot / F.epochLength == old.state.ledger.environment.epoch then IO.pure(None)
      else
        r.prepareSyntheticSuccessor(old.fence, block.header.hash, block.header.slot)
          .map(x => Some(get(x)))
    pending <- r.prepareRepeatedBlock(old.fence, block, preview, noOracle).map(get(_))
    result <- r.publish(pending).map(get(_))
  yield result.state

  test("three absent reward boundaries complete fresh governance and roll full roles atomically") {
    (for
      r <- runtime()
      states <- F.signed(Vector(1, 40, 80, 120).map(BigInt(_))).traverse(step(r, _))
      _ = assertEquals(states.map(_.ledger.environment.epoch), Vector[BigInt](0, 1, 2, 3))
      _ = states.tail.zipWithIndex.foreach { (s, i) =>
        val c = s.syntheticBoundary.get
        assertEquals(c.transitions, i + 1)
        assertEquals(c.governanceInput.epoch, BigInt(i + 1))
        assertEquals(c.governanceAfter.get.dormant, F.governance.dormant + i + 1)
        assertEquals(c.roles.previous.original, F.parameterOriginal)
        assertEquals(c.roles.current.original, F.parameterOriginal)
        assertEquals(c.governanceInput.parameters.previous.original, c.roles.previous.original)
        assertEquals(s.ledger.fees, s.syntheticRewards.get.pots.fees)
        assertEquals(s.stake.get.ledgerId, s.ledger.id)
      }
      last <- r.snapshot
      denied <- r.prepareSyntheticSuccessor(last.fence, F.pin, 160)
      _ = assert(denied.isLeft)
      after <- r.snapshot
      _ = assertEquals(after.state.id, last.state.id)
      exported <- r.exportSyntheticRecovery(F.pin)
      _ = assert(exported.isLeft)
    yield ()).unsafeToFuture()
  }

  test(
    "repeated lane requires checked effectful preparation and rejects failed oracle atomically"
  ) {
    (for
      r <- runtime()
      blocks = F.signed(Vector[BigInt](1, 5))
      first <- step(r, blocks.head)
      old <- r.snapshot
      legacy <- r.prepareSyntheticBlock(old.fence, blocks.last)
      _ = assert(legacy.isLeft)
      rejected <- r.prepareRepeatedBlock(old.fence, blocks.last, None, noOracle)
      _ = assert(rejected.isLeft)
      after <- r.snapshot
      _ = assertEquals(after.state.id, first.id)
    yield ()).unsafeToFuture()
  }

  test("cancelled native comparison never publishes a partial monetary freeze") {
    (for
      r <- runtime()
      blocks = F.signed(Vector[BigInt](1, 5))
      _ <- step(r, blocks.head)
      old <- r.snapshot
      entered <- cats.effect.Deferred[IO, Unit]
      fiber <- r
        .prepareRepeatedBlock(
          old.fence,
          blocks.last,
          None,
          (_, _) => entered.complete(()) *> IO.never[N.Generated]
        )
        .start
      _ <- entered.get
      _ <- fiber.cancel
      after <- r.snapshot
      _ = assertEquals(after.state.id, old.state.id)
      _ = assert(after.state.syntheticBoundary.get.frozenId.isEmpty)
    yield ()).unsafeToFuture()
  }

  test("stale asynchronous response cannot overwrite a concurrently published exact state") {
    (for
      r <- runtime()
      blocks = F.signed(Vector[BigInt](1, 2, 5))
      _ <- step(r, blocks.head)
      old <- r.snapshot
      entered <- cats.effect.Deferred[IO, Unit]
      release <- cats.effect.Deferred[IO, Unit]
      // This branch has a valid direct predecessor but freezes after the competing slot2 branch.
      competing = F.signed(Vector[BigInt](1, 5)).last
      fiber <- r
        .prepareRepeatedBlock(
          old.fence,
          competing,
          None,
          (_, _) =>
            entered.complete(()) *> release.get *> IO
              .raiseError[N.Generated](new IllegalStateException("comparison failed"))
        )
        .start
      _ <- entered.get
      published <- step(r, blocks(1))
      _ <- release.complete(())
      result <- fiber.joinWithNever
      _ = assertEquals(result, Left(CoherentSequence.Failure.StaleCandidate))
      after <- r.snapshot
      _ = assertEquals(after.state.id, published.id)
    yield ()).unsafeToFuture()
  }
