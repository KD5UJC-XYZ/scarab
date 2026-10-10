// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayRewardPulser as P, ConwayEmptyGovernance as G}
import lab.network.ChainSync

class SyntheticBoundaryCompositionSuite extends munit.FunSuite:
  private val F = SyntheticBoundaryCompositionFixture
  private val D = EphemeralStreaming
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def point(b: SequenceInput.Block): ChainSync.Point =
    ChainSync.Point.Block(get(ChainSync.UInt64.from(b.header.slot)), b.header.hash)
  private def run(r: CoherentSequence.Runtime[IO], blocks: Vector[SequenceInput.Block]) =
    Ref
      .of[IO, Vector[D.Event]](blocks.map(D.Event.Block.apply))
      .flatMap(q => D.run(r, D.Limits())(q.modify(xs => (xs.drop(1), xs.headOption))))
  private def component(s: CoherentSequence.State) = s.syntheticBoundary.get
  private def sameSemantic(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
    assertEquals(a.acquisition.tip, b.acquisition.tip)
    assertEquals(a.ledger.outputMap, b.ledger.outputMap)
    assertEquals(a.ledger.fees, b.ledger.fees)
    assertEquals(a.ledger.environment.epoch, b.ledger.environment.epoch)
    assertEquals(a.nonces.fields, b.nonces.fields)
    assertEquals(a.certificates.state.tip, b.certificates.state.tip)
    assertEquals(a.stake.get.context.accounts, b.stake.get.context.accounts)
    assertEquals(a.stake.get.snapshots.mark.active, b.stake.get.snapshots.mark.active)
    assertEquals(a.stake.get.snapshots.set.active, b.stake.get.snapshots.set.active)
    assertEquals(a.stake.get.snapshots.go.active, b.stake.get.snapshots.go.active)
    assertEquals(a.syntheticRewards.get.pots, b.syntheticRewards.get.pots)
    assertEquals(a.syntheticRewards.get.previousBlocks, b.syntheticRewards.get.previousBlocks)
    assertEquals(a.syntheticRewards.get.currentBlocks, b.syntheticRewards.get.currentBlocks)
    val x = component(a); val y = component(b)
    assertEquals(x.nonMyopic.id, y.nonMyopic.id)
    assertEquals(x.governanceAfter.map(_.fresh), y.governanceAfter.map(_.fresh))
    assertEquals(x.governanceAfter.map(_.dormant), y.governanceAfter.map(_.dormant))
    assertEquals(x.boundaryApplied, y.boundaryApplied)

  test("source-bound reward timing keeps strict runtime start and force edges") {
    assertEquals(F.typedGlobals.stabilityWindow, BigInt(3))
    assertEquals(F.typedGlobals.randomnessStabilisationWindow, BigInt(4))
    Vector[BigInt](5, 8, 9)
      .traverse_ { firstRewardSlot =>
        val requested = Vector[BigInt](3, 4, firstRewardSlot)
        val blocks = F.signed(requested)
        for
          runtime <- F.boundaryRuntime
          before <- run(runtime, blocks.init)
          _ = assertEquals(before.stop, D.Stop.End)
          rewards = before.snapshot.state.syntheticRewards.get
          _ = assertEquals(rewards.profile.window, F.typedGlobals.randomnessStabilisationWindow)
          _ = assert(rewards.frozen.isEmpty && rewards.pulser.isEmpty)
          _ = assert(component(before.snapshot.state).frozenId.isEmpty)
          started <- run(runtime, blocks.takeRight(1))
          _ = assertEquals(started.stop, D.Stop.End)
          after = started.snapshot.state.syntheticRewards.get
          _ = assertEquals(after.frozen.map(_.observedSlot), Some(firstRewardSlot))
          _ = assertEquals(after.frozen.map(_.window), Some(BigInt(4)))
          _ = assertEquals(
            after.pulser.map(_.phase),
            Some(if firstRewardSlot == 9 then P.Phase.Complete else P.Phase.Pulsing)
          )
          _ <-
            if firstRewardSlot == 8 then
              val forceBlock = F.signed(requested :+ BigInt(9)).last
              run(runtime, Vector(forceBlock)).map { forced =>
                assertEquals(forced.stop, D.Stop.End)
                assertEquals(
                  forced.snapshot.state.syntheticRewards.get.pulser.map(_.phase),
                  Some(P.Phase.Complete)
                )
              }
            else IO.unit
        yield ()
      }
      .unsafeToFuture()
  }

  test("post-boundary runtime remains absent through the source-bound reward start edge") {
    (for
      runtime <- F.boundaryRuntime
      before <- run(runtime, F.signed(Vector[BigInt](40, 43, 44)))
      _ = assertEquals(before.stop, D.Stop.End)
      _ = assertEquals(before.snapshot.state.ledger.environment.epoch, BigInt(1))
      rewards = before.snapshot.state.syntheticRewards.get
      _ = assertEquals(rewards.profile.window, F.typedGlobals.randomnessStabilisationWindow)
      _ = assert(rewards.frozen.isEmpty && rewards.pulser.isEmpty)
      _ = assert(component(before.snapshot.state).frozenId.isEmpty)
    yield ()).unsafeToFuture()
  }

  test("one atomic boundary handles absent, pulsing and completed old monetary state") {
    Vector(Vector(1, 40), Vector(1, 5, 40), Vector(1, 5, 6, 7, 40))
      .traverse_ { slots =>
        val blocks = F.signed(slots.map(BigInt(_)))
        for
          r <- F.boundaryRuntime
          old <- run(r, blocks.init)
          _ = assertEquals(old.stop, D.Stop.End)
          _ = assertEquals(
            old.snapshot.state.syntheticRewards.get.pulser.map(_.phase),
            if slots.size == 2 then None
            else Some(if slots.size == 3 then P.Phase.Pulsing else P.Phase.Complete)
          )
          before = component(old.snapshot.state)
          _ = assertEquals(before.nonMyopic.id, F.nonMyopic.id)
          _ = if slots.size > 2 then
            assertEquals(before.frozenHistory.map(_.id), Some(F.nonMyopic.id))
          next <- run(r, blocks.takeRight(1))
          _ = assertEquals(next.stop, D.Stop.End)
          s = next.snapshot.state
          c = component(s)
          g = c.governanceAfter.get
          _ = assert(c.boundaryApplied)
          _ = assertEquals(s.ledger.environment.epoch, BigInt(1))
          _ = assertEquals(s.revision, old.snapshot.state.revision + 1)
          _ = assertEquals(g.dormant, BigInt(5))
          _ = assertNotEquals(
            g.before.parameters.previous.original,
            g.before.parameters.current.original
          )
          _ = assertEquals(g.parameters.previous.original, F.parameterOriginal)
          _ = assertEquals(g.parameters.current.original, F.parameterOriginal)
          _ = assertEquals(g.parameters.future, G.FutureParameters.PotentialNone)
          _ = assert(g.before.committeeState.nonEmpty)
          _ = assertEquals(g.committeeState, Map.empty)
          _ = assertEquals(g.fresh.stakePoolDistribution.total, BigInt(150))
          _ = assertEquals(g.fresh.stakePoolDistribution.pools.keySet, Set(F.issuer, F.secondPool))
          // Empty go has no member credits even though the snapshot fee/reward pot is nonzero.
          _ = assertEquals(g.fresh.accounts.values.map(_.rewards).sum, BigInt(150))
          _ = assertEquals(
            s.syntheticRewards.get.pots.fees,
            BigInt(if slots.size == 2 then 10 else 0)
          )
          _ = assertEquals(s.ledger.fees, BigInt(if slots.size == 2 then 10 else 0))
          _ = assertEquals(
            s.syntheticRewards.get.pots.treasury,
            BigInt(if slots.size == 2 then 0 else 2)
          )
          _ = assertEquals(
            s.syntheticRewards.get.pots.reserves,
            BigInt(if slots.size == 2 then 999840 else 999848)
          )
          _ = assertEquals(g.treasury, s.syntheticRewards.get.pots.treasury)
          _ = assertEquals(g.fresh.enact.treasury, g.treasury)
          // NM records the available reward pot: gross fees 10 minus treasury share 2.
          _ = assertEquals(c.nonMyopic.rewardPot, BigInt(if slots.size == 2 then 9 else 8))
          _ = assertEquals(g.fresh.drepDistribution, Map.empty)
          _ = assertEquals(g.fresh.index, 0)
          _ = assertEquals(s.stake.get.snapshots.set.total, BigInt(100))
          _ = assertEquals(s.stake.get.snapshots.set.pools.keySet, Set(F.issuer, F.secondPool))
          _ = assertEquals(s.stake.get.snapshots.set.pools(F.secondPool).coin, BigInt(0))
          _ = assertEquals(s.eligibility.get.headers.last.stake.numerator, BigInt(1))
          _ = assertEquals(s.eligibility.get.headers.last.stake.denominator, BigInt(1))
          _ = assertEquals(
            g.fresh.stakePoolDistribution.pools(F.issuer).fraction,
            lab.ledger.ConwayStake.Ratio(2, 3)
          )
          _ = assertEquals(s.stake.get.snapshots.mark.total, BigInt(150))
          _ =
            if slots.size == 2 then assertEquals(c.nonMyopic.id, F.nonMyopic.id)
            else {
              assertNotEquals(c.nonMyopic.id, F.nonMyopic.id);
              assertEquals(c.nonMyopic.orderedPools, Vector.empty)
            }
        yield ()
      }
      .unsafeToFuture()
  }

  test("invalid successor body leaves the entire already compacted tuple unchanged") {
    (for
      r <- F.boundaryRuntime
      old <- run(r, F.blocks.take(20))
      _ = assertEquals(old.stop, D.Stop.End)
      compacted <- r
        .advanceAnchor(old.snapshot.fence, old.snapshot.state.acquisition.candidates(7))
        .map(get(_))
      invalid = F.invalidBody(F.blocks(20))
      _ = assertEquals(invalid.header.hash, F.blocks(20).header.hash)
      rejected <- run(r, Vector(invalid))
      _ = assert(rejected.stop.isInstanceOf[D.Stop.Rejected])
      _ = assertEquals(rejected.counters.compactions, 0L)
      _ = assertEquals(rejected.snapshot.state.id, compacted.state.id)
      _ = assertEquals(component(rejected.snapshot.state).id, component(compacted.state).id)
      _ = assertEquals(rejected.snapshot.state.revision, compacted.state.revision)
      _ = sameSemantic(rejected.snapshot.state, compacted.state)
    yield ()).unsafeToFuture()
  }

  test("whole boundary rolls back and replays after repeated bounded compaction") {
    (for
      r <- F.boundaryRuntime
      old <- run(r, F.blocks.take(20))
      oldComponent = component(old.snapshot.state)
      next <- run(r, F.blocks.drop(20))
      _ = assertEquals(next.stop, D.Stop.End)
      _ = assert(old.counters.compactions > 1)
      _ = assert(next.snapshot.state.acquisition.size <= 8)
      rolled <- r.rollbackTo(next.snapshot.fence, point(F.blocks(19))).map(get(_))
      _ = assertEquals(component(rolled.state).id, oldComponent.id)
      _ = assertEquals(component(rolled.state).nonMyopic.id, F.nonMyopic.id)
      _ = assert(!component(rolled.state).boundaryApplied)
      _ = assert(component(rolled.state).governanceAfter.isEmpty)
      replay <- run(r, F.blocks.drop(20))
      _ = assertEquals(replay.stop, D.Stop.End)
      _ = sameSemantic(replay.snapshot.state, next.snapshot.state)
      _ = assert(replay.snapshot.state.revision > next.snapshot.state.revision)
      outside <- r.rollbackTo(replay.snapshot.fence, point(F.blocks.head))
      _ = assertEquals(outside, Left(CoherentSequence.Failure.OutsideRetainedWindow))
      after <- r.snapshot
      _ = assertEquals(after.state.id, replay.snapshot.state.id)
    yield ()).unsafeToFuture()
  }

  test("second boundary and recovery export reject without changing the composed state") {
    (for
      r <- F.boundaryRuntime
      seed <- r.snapshot
      initialExport <- r.exportSyntheticRecovery(F.pin)
      _ = assert(initialExport.isLeft)
      done <- run(r, F.signed(Vector(BigInt(1), BigInt(40))))
      _ = assertEquals(done.stop, D.Stop.End)
      second <- r.prepareSyntheticSuccessor(done.snapshot.fence, F.pin, 80)
      _ = assert(second.isLeft)
      exported <- r.exportSyntheticRecovery(F.pin)
      _ = assert(exported.isLeft)
      after <- r.snapshot
      _ = assertEquals(after.state.id, done.snapshot.state.id)
      _ = assert(!component(seed.state).boundaryApplied)
    yield ()).unsafeToFuture()
  }

  test("composition profile cannot escape through legacy no-effect factory") {
    (for
      r <- F.boundaryRuntime
      s <- r.snapshot
      result <- CoherentSequence.createWithSyntheticRewards[IO](
        F.context,
        F.stakeSeed,
        s.state.syntheticRewards.get.profile,
        F.initialPots,
        Map.empty,
        Map.empty,
        F.pin
      )
      _ = assert(result.isLeft)
    yield ()).unsafeToFuture()
  }

  test("foreign decoded pool bindings and inconsistent full supply or deposits reject at seed") {
    def create(
        input: G.Input = F.governance,
        pots: lab.ledger.ConwayEpochBoundary.Pots = F.initialPots,
        profile: CoherentSequence.SyntheticBoundaryProfile = F.boundaryProfile
    ) =
      CoherentSequence.createWithSyntheticBoundary[IO](
        F.context,
        F.stakeSeed,
        profile,
        input,
        F.nonMyopic,
        pots,
        Map.empty,
        Map.empty,
        F.pin
      )
    val swapped = F.checkedPools.map((id, _) =>
      id -> F.checkedPools(if id == F.issuer then F.secondPool else F.issuer)
    )
    val foreign = get(
      CoherentSequence.syntheticBoundaryProfile(F.roles, swapped, F.typedGlobals)
    )
    (for
      a <- create(profile = foreign)
      b <- create(pots = F.initialPots.copy(reserves = F.initialPots.reserves + 1))
      c <- create(input = F.governance.copy(deposits = F.governance.deposits.copy(total = 1)))
      d <- create(input = F.governance.copy(treasury = 1))
      e <- create(input = F.governance.copy(accounts = F.governance.accounts - F.secondAccount))
      f <- create(input = F.governance.copy(globals = get(G.suppliedFixedGlobals(1, F.pin))))
      _ = Vector(a, b, c, d, e, f).foreach(x => assert(x.isLeft))
    yield ()).unsafeToFuture()
  }

  test("late first successor captures old non-myopic history while selecting empty replacement") {
    (for
      r <- F.boundaryRuntime
      blocks = F.signed(Vector(1, 5, 6, 7, 49).map(BigInt(_)))
      old <- run(r, blocks.init)
      _ = assertEquals(old.stop, D.Stop.End)
      next <- run(r, blocks.takeRight(1))
      _ = assertEquals(next.stop, D.Stop.End)
      c = component(next.snapshot.state)
      _ = assertEquals(c.nonMyopic.orderedPools, Vector.empty)
      _ = assertEquals(c.frozenHistory.map(_.id), Some(F.nonMyopic.id))
      _ = assertEquals(
        next.snapshot.state.syntheticRewards.get.frozen.map(_.epoch),
        Some(BigInt(1))
      )
      _ = assertEquals(
        next.snapshot.state.syntheticRewards.get.frozen.map(_.snapshotFees),
        Some(BigInt(10))
      )
      _ = assertEquals(c.governanceAfter.get.fresh.enact.treasury, BigInt(2))
    yield ()).unsafeToFuture()
  }

  test("first new-epoch freeze with nonempty rotated go rejects without state change") {
    (for
      r <- F.boundaryRuntime
      blocks = F.signed(Vector(1, 5, 6, 7, 40, 45).map(BigInt(_)))
      before <- run(r, blocks.init)
      _ = assertEquals(before.stop, D.Stop.End)
      _ = assert(before.snapshot.state.stake.get.snapshots.go.pools.nonEmpty)
      rejected <- run(r, blocks.takeRight(1))
      _ = assert(rejected.stop.isInstanceOf[D.Stop.Rejected])
      _ = assertEquals(rejected.snapshot.state.id, before.snapshot.state.id)
      _ = assertEquals(component(rejected.snapshot.state).id, component(before.snapshot.state).id)
    yield ()).unsafeToFuture()
  }

  test("all existing checkpoint and raw recovery codecs reject the additional state") {
    (for
      r <- F.boundaryRuntime
      s <- r.snapshot
      _ = assert(ValidatedCheckpoint.encode(F.context, s, F.pin, 0, 8).isLeft)
      local <- r.exportLocalCheckpoint(F.pin, F.pin, 0)
      _ = assert(local.isLeft)
      _ = assert(
        SyntheticRecoveryModel
          .prepare(
            CoherentSequence.RecoveryImage(F.context, 8, Vector(s.state), Vector.empty),
            F.pin
          )
          .isLeft
      )
      after <- r.snapshot
      _ = assertEquals(after.state.id, s.state.id)
    yield ()).unsafeToFuture()
  }

  test("unequal checked previous/current reward roles cannot form a composition profile") {
    def n(v: V) = Node(v, Bytes.empty)
    val fields = get(Cbor.decode(F.parameterOriginal)).value match
      case V.Arr(xs) => xs
      case _         => fail("parameter array")
    val changed = get(
      Cbor.encode(
        V.Arr(fields.updated(10, n(V.Tag(30, n(V.Arr(Vector(n(V.UInt(1)), n(V.UInt(1000)))))))))
      )
    )
    val current =
      get(GovernanceParameterPayload.decode(changed, ClusterHeaderObservation.sha256(changed)))
    val roles = get(
      GovernanceParameterPayload.bindRoles(
        F.checkedParameters,
        current,
        F.checkedParameters.sha256,
        current.sha256,
        F.parameters,
        current.rewards,
        F.context.ledger.environment.feeParameters,
        F.context.ledger.environment.minimumOutputParameters
      )
    )
    assert(
      CoherentSequence
        .syntheticBoundaryProfile(roles, F.checkedPools, F.typedGlobals)
        .isLeft
    )
  }

  test("new composition runtimes reject one another's fences and candidates") {
    (for
      a <- F.boundaryRuntime
      b <- F.boundaryRuntime
      sa <- a.snapshot
      sb <- b.snapshot
      wrongFence <- b.prepareSyntheticBlock(sa.fence, F.blocks.head)
      _ = assertEquals(wrongFence, Left(CoherentSequence.Failure.ForeignFence))
      candidate <- a.prepareSyntheticBlock(sa.fence, F.blocks.head).map(get(_))
      foreign <- b.publish(candidate)
      _ = assertEquals(foreign, Left(CoherentSequence.Failure.ForeignCandidate))
      afterA <- a.snapshot
      afterB <- b.snapshot
      _ = assertEquals(afterA.state.id, sa.state.id)
      _ = assertEquals(afterB.state.id, sb.state.id)
    yield ()).unsafeToFuture()
  }

  test(
    "ordered full parameter provenance rejects previous and current splices with equal projections"
  ) {
    Vector(false, true)
      .traverse_ { previousRole =>
        val original = if previousRole then F.checkedPrevious else F.checkedParameters
        val fields = get(Cbor.decode(original.original)).value match
          case V.Arr(xs) => xs
          case _         => fail("parameter array")
        // Field 13 is retained in the complete original but unused by the scoped predicates.
        val changed =
          get(Cbor.encode(V.Arr(fields.updated(13, Node(V.UInt(170000001), Bytes.empty)))))
        val decoded =
          get(GovernanceParameterPayload.decode(changed, ClusterHeaderObservation.sha256(changed)))
        assertNotEquals(decoded.sha256, original.sha256)
        assertEquals(decoded.rewards.original, original.rewards.original)
        assertEquals(decoded.feePerByte, original.feePerByte)
        assertEquals(decoded.feeFixed, original.feeFixed)
        assertEquals(decoded.maxTxSize, original.maxTxSize)
        assertEquals(decoded.coinsPerUTxOByte, original.coinsPerUTxOByte)
        val previous = if previousRole then decoded else F.checkedPrevious
        val current = if previousRole then F.checkedParameters else decoded
        val roles = get(
          GovernanceParameterPayload.bindRoles(
            previous,
            current,
            previous.sha256,
            current.sha256,
            F.parameters,
            current.rewards,
            F.context.ledger.environment.feeParameters,
            F.context.ledger.environment.minimumOutputParameters
          )
        )
        val old = F.governance.oldDRep match
          case G.OldDRep.Complete(snapshot, ratify) =>
            G.OldDRep.Complete(
              snapshot,
              ratify.copy(enact =
                ratify.enact.copy(current = current.payload, previous = previous.payload)
              )
            )
          case _ => fail("complete governance fixture")
        val input = F.governance.copy(
          parameters =
            F.governance.parameters.copy(current = current.payload, previous = previous.payload),
          oldDRep = old
        )
        assert(G.applyBoundary(input, 1).isRight)
        val spliced = get(
          CoherentSequence
            .syntheticBoundaryProfile(roles, F.checkedPools, F.typedGlobals)
        )
        val prepared = get(
          NativeSeedParameters.decode(
            previous.original,
            current.original,
            F.prepared.genesisOriginal,
            F.prepared.genesisSHA256,
            0,
            0,
            1082026
          )
        )
        val matchingGlobals =
          get(GovernanceGlobals.bind(prepared, prepared.bindingId, 0, 0, 1082026))
        assertEquals(matchingGlobals.genesisSHA256, F.typedGlobals.genesisSHA256)
        assertEquals(matchingGlobals.rewardGlobals.id, F.typedGlobals.rewardGlobals.id)
        val coherent = get(
          CoherentSequence
            .syntheticBoundaryProfile(roles, F.checkedPools, matchingGlobals)
        )
        for
          rejected <- CoherentSequence.createWithSyntheticBoundary[IO](
            F.context,
            F.stakeSeed,
            spliced,
            input,
            F.nonMyopic,
            F.initialPots,
            Map.empty,
            Map.empty,
            F.pin
          )
          _ = assert(
            rejected.left.toOption.exists(
              _.toString.contains("composition parameter roles differ from globals source binding")
            )
          )
          accepted <- CoherentSequence.createWithSyntheticBoundary[IO](
            F.context,
            F.stakeSeed,
            coherent,
            input.copy(globals = get(SyntheticBoundaryState.typedGlobals(matchingGlobals))),
            F.nonMyopic,
            F.initialPots,
            Map.empty,
            Map.empty,
            F.pin
          )
          _ = assert(accepted.isRight)
        yield ()
      }
      .unsafeToFuture()
  }
