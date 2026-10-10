// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.ledger.{
  ConwayNativeLikelihood as N,
  ConwayNonMyopic as NM,
  ConwayRewardStart as R,
  ConwayRewardPulser as P
}
import java.nio.file.{Files, Path, LinkOption}
import lab.cbor.Bytes
import lab.ledger.{
  ClusterTransition as L,
  ConwayStake as S,
  ConwayEpochBoundary as B,
  PlutusSuccessorBinding,
  PlutusOutput
}
import lab.submission.{AdmissionProfile, SignedTransaction}

/** Executes exact retained signed originals at a supplied synthetic successor. This is not a native
  * boundary/inclusion claim and does not modify or resign the transaction.
  */
class PlutusSuccessorExecutionSuite extends munit.FunSuite:
  import NativeLiveBoundaryMain.{read, sha, obj, hash}
  import ReferenceJson.{field, string, uint}
  private def get[A](v: Either[?, A]): A = v.fold(e => fail(e.toString), identity)
  sys.env.get("PLUTUS_LIVE_BUNDLE").foreach { directory =>
    val root = Path.of(directory).toAbsolutePath.normalize()
    lazy val inputs =
      val manifest = read(Path.of(sys.env("PLUTUS_LIVE_MANIFEST")), 65536)
      assertEquals(sha(manifest).hex, sys.env("PLUTUS_LIVE_MANIFEST_SHA256"))
      val json = ReferenceJson.parse(manifest)
      assertEquals(string(field(json, "schema")), "plutus-retained-live-inputs-v1")
      val rows = obj(field(json, "inputs")); assert(rows.nonEmpty && rows.size <= 128)
      rows.map { (name, row) =>
        val path = root.resolve(name).normalize()
        assert(
          !Path.of(name).isAbsolute && !name.contains("\\") && path.startsWith(root) &&
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path
              .toRealPath()
              .startsWith(root.toRealPath())
        )
        val bytes = read(path, 1048576)
        assertEquals(BigInt(bytes.size), uint(field(row, "bytes")))
        assertEquals(sha(bytes), hash(field(row, "sha256")))
        name -> bytes
      }
    lazy val joined = NativeLiveBoundaryMain.initial(
      root.resolve("initial"),
      sha(inputs("initial/adapter-inputs.json")).hex,
      AdmissionProfile.PlutusV3
    )
    test("actual registered Plutus spend executes against a checked successor environment") {
      val source = joined.ledger.epochComponents
      val context = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
      val before = context.ledger
      val transaction = get(SignedTransaction.checked(inputs("submission/transaction.cbor")))
      val descriptor = ReferenceJson.parse(inputs("submission/descriptor.json"))
      assertEquals(transaction.transactionId, hash(field(descriptor, "transactionId")))
      assertEquals(sha(transaction.originalBody), hash(field(descriptor, "bodySHA256")))
      assertEquals(sha(transaction.originalWitnesses), hash(field(descriptor, "witnessesSHA256")))
      val collateral = get(PlutusEndpointLedger.input(string(field(descriptor, "collateralInput"))))
      val spent = get(PlutusEndpointLedger.input(string(field(descriptor, "spentInput"))))
      // Explicit synthetic 500-slot epoch keeps the original signed validity interval unchanged.
      val owner = S.owner()
      val stakeContext = get(
        S.context(
          sha(Bytes.fromArray("successor-execution-test".getBytes("UTF-8"))),
          500,
          source.stake.context.accounts,
          source.stake.context.pools
        )
      )
      val stake = get(
        S.seed(
          owner,
          stakeContext,
          before,
          source.id,
          source.stake.instantaneous,
          source.stake.snapshots
        )
      )
      val boundaryOwner = B.owner()
      val boundary = get(
        B.context(
          boundaryOwner,
          owner,
          before.id,
          stake,
          source.pots,
          source.previousBlocks,
          source.currentBlocks
        )
      )
      val header = sha(transaction.original)
      val signal = get(B.signal(boundaryOwner, boundary, header, 500))
      val preview = get(
        B.preview(
          boundaryOwner,
          boundary,
          signal,
          B.RewardPhase
            .Absent(get(B.suppliedAbsent(boundaryOwner, boundary, source.reward.componentSHA256)))
        )
      )
      val parameters = before.environment.plutus.get.parameters
      val binding = get(PlutusSuccessorBinding.prepare(before, preview, parameters))
      val block = get(
        L.preparePlutusSuccessorBlock(
          before,
          binding,
          header,
          Vector(transaction.original),
          500,
          Some(lab.vm.Pv9SubmissionEvaluator)
        )
      )
      val stakeCandidate =
        get(S.preparePlutusSuccessor(owner, stake, before, block, preview, binding))
      val applied = get(L.commitBlock(before, block))
      val selected = get(S.select(owner, stake, stakeCandidate))
      assertEquals(applied.state.environment.epoch, BigInt(1))
      assert(applied.state.environment.plutus.get eq binding.environment.plutus.get)
      assertEquals(applied.state.fees - before.fees, BigInt(300000))
      assertEquals(selected.ledgerId, applied.state.id)
      val initial = get(PlutusOutput.snapshot(before.outputMap, 0)).outputs
      val terminal = get(PlutusOutput.snapshot(applied.state.outputMap, 0)).outputs
      assert(!terminal.contains(spent))
      assertEquals(terminal(collateral).original, initial(collateral).original)
      assertEquals(terminal.size, initial.size)
      assertEquals(applied.state.revision, before.revision + 1)
      assert(
        L.preparePlutusSuccessorBlock(
          before,
          binding,
          header,
          Vector(transaction.original),
          500,
          None
        ).isLeft
      )
    }

    // Explicit empty-domain oracle test double: no native process or numerical parity claim.
    val emptyOracle = new NativeLikelihoodOracle.Oracle[IO]:
      def generate(frozen: B.Frozen, id: Bytes): IO[N.Generated] = IO {
        assert(frozen.go.pools.isEmpty)
        val request = get(N.request(frozen, id))
        get(
          N.acceptTrustedNative(
            request,
            Bytes(
              request.original.value ++
                Bytes.fromArray("--native--\n".getBytes("US-ASCII")).value
            )
          )
        )
      }
    def start = for
      early <- PlutusServiceCheckpoint.start(
        None,
        joined,
        sha(inputs("initial/adapter-inputs.json"))
      )
      repeated <- RepeatedPlutusBootstrap.start(joined, early, emptyOracle)
    yield repeated
    lazy val firstBlock =
      val name =
        inputs.keys.filter(_.matches("originals/block-[0-9]{4}\\.cbor")).toVector.sorted.head
      get(
        SequenceInput.block(
          BoundedChainFollower
            .Original(inputs(name.stripSuffix(".cbor") + "-header.cbor"), inputs(name))
        )
      )

    test("actual source bootstrap and checked first freeze retain exact pre-block capture") {
      (for
        started <- start
        old = started.snapshot
        candidate <- started.runtime
          .prepareRepeatedBlock(old.fence, firstBlock, None, emptyOracle.generate)
          .map(get(_))
        applied <- started.runtime.publish(candidate).map(get(_))
        frozen = applied.state.syntheticRewards.get.frozen.get
        component = applied.state.syntheticBoundary.get
        _ = assert(component.checkedLikelihood.get.request.frozen eq frozen)
        _ = assertEquals(component.checkedLikelihood.get.mode, N.Mode.CheckedJvm)
        _ = assertEquals(frozen.go, old.state.stake.get.snapshots.go)
        _ = assertEquals(
          component.frozenHistory.get.id,
          old.state.syntheticBoundary.get.nonMyopic.id
        )
        _ = assertEquals(applied.state.stake.get.ledgerId, applied.state.ledger.id)
        _ = assertEquals(applied.state.certificates.state.tip.slot, firstBlock.header.slot)
      yield ()).unsafeToFuture()
    }

    test("successful checked oracle reply arriving after another publication is stale") {
      (for
        started <- start
        old = started.snapshot
        entered <- cats.effect.Deferred[IO, Unit]
        release <- cats.effect.Deferred[IO, Unit]
        slow = (f: B.Frozen, id: Bytes) =>
          emptyOracle.generate(f, id).flatTap(_ => entered.complete(()) *> release.get)
        fiber <- started.runtime.prepareRepeatedBlock(old.fence, firstBlock, None, slow).start
        _ <- entered.get
        winning <- started.runtime
          .prepareRepeatedBlock(old.fence, firstBlock, None, emptyOracle.generate)
          .map(get(_))
        applied <- started.runtime.publish(winning).map(get(_))
        _ <- release.complete(())
        stale <- fiber.joinWithNever
        _ = assertEquals(stale, Left(CoherentSequence.Failure.StaleCandidate))
        after <- started.runtime.snapshot
        _ = assertEquals(after.state.id, applied.state.id)
      yield ()).unsafeToFuture()
    }

    test("late successor freezes pre-tick history and reuses captured likelihood at boundary") {
      (for
        started <- start
        state = started.snapshot.state
        source = joined.ledger.epochComponents
        stakeOwner = S.owner()
        seedStake = get(source.stake.attach(stakeOwner, state.ledger))
        advance = get(
          L.prepareBlock(
            state.ledger,
            sha(Bytes.fromArray("freeze-point".getBytes("UTF-8"))),
            Vector.empty,
            400,
            Some(lab.vm.Pv9SubmissionEvaluator)
          )
        )
        ledger = get(L.commitBlock(state.ledger, advance)).state
        stake = get(
          S.select(
            stakeOwner,
            seedStake,
            get(S.prepare(stakeOwner, seedStake, state.ledger, advance))
          )
        )
        boundaryOwner = B.owner()
        context = get(
          B.context(
            boundaryOwner,
            stakeOwner,
            ledger.id,
            stake,
            source.pots,
            source.previousBlocks,
            source.currentBlocks
          )
        )
        profile = state.syntheticRewards.get.profile
        frozen = get(
          B.freezeForAllocation(
            boundaryOwner,
            context,
            400,
            profile.window,
            profile.parameters,
            profile.globals
          )
        )
        generated <- emptyOracle.generate(frozen, frozen.id)
        allocation = get(R.calculate(frozen, frozen.id))
        pulser = get(P.start(frozen, frozen.id, allocation, allocation.id, Map.empty))
        bound = get(
          SyntheticBoundaryState.advanceFreeze(
            get(SyntheticBoundaryState.attachLikelihood(state.syntheticBoundary.get, generated)),
            Some(frozen),
            Some(pulser)
          )
        )
        completed = get(P.completeAtBoundary(pulser, pulser.id, 1700))
        effect = get(
          B.completeFromFrozen(boundaryOwner, context, completed.completion.get.completed)
        )
        header = sha(Bytes.fromArray("late-successor-capture-test".getBytes("UTF-8")))
        preview = get(
          B.preview(
            boundaryOwner,
            context,
            get(B.signal(boundaryOwner, context, header, 1700)),
            B.RewardPhase.Completed(effect)
          )
        )
        binding = get(
          PlutusSuccessorBinding
            .prepare(ledger, preview, ledger.environment.plutus.get.parameters)
        )
        block = get(
          L.preparePlutusSuccessorBlock(
            ledger,
            binding,
            header,
            Vector.empty,
            1700,
            Some(lab.vm.Pv9SubmissionEvaluator)
          )
        )
        pending = get(
          S.preparePlutusSuccessor(stakeOwner, stake, ledger, block, preview, binding)
        )
        selected = get(S.select(stakeOwner, stake, pending))
        next = get(
          SyntheticBoundaryState
            .atBoundary(bound, Some(frozen), Some(completed), preview, Some(effect))
        )
        late = get(
          B.freezeAfterBoundary(
            boundaryOwner,
            preview,
            selected,
            profile.window,
            profile.parameters,
            profile.globals
          )
        )
        lateGenerated <- emptyOracle.generate(late, late.id)
        lateAllocation = get(R.calculate(late, late.id))
        latePulser = get(P.start(late, late.id, lateAllocation, lateAllocation.id, Map.empty))
        result = get(
          SyntheticBoundaryState.afterBoundaryFreeze(
            bound,
            get(SyntheticBoundaryState.attachLikelihood(next, lateGenerated)),
            Some(late),
            Some(latePulser)
          )
        )
        _ = assertEquals(result.frozenHistory.get.id, bound.nonMyopic.id)
        _ = assert(result.checkedLikelihood.get.request.frozen eq late)
        _ = assertEquals(late.go, stake.snapshots.go)
        _ = assertEquals(result.governanceInput.epoch, BigInt(1))
        _ = assertEquals(result.roles.previous.original, result.roles.current.original)
      yield ()).unsafeToFuture()
    }
  }
