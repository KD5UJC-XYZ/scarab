// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scala.concurrent.duration.*
import java.nio.file.Path
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayEpochBoundary as B, ConwayRewardStart as R, ConwayRewardPulser as P}
import lab.header.PraosNonceEvolution as N

class SyntheticRecoverySuite extends munit.FunSuite:
  private def get[E, A](v: Either[E, A]): A = v.fold(e => fail(e.toString), identity)
  private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private val pin = Bytes(Vector.fill(32)(77.toByte))
  private def array(v: V*) = get(Cbor.encode(V.Arr(v.toVector.map(Node(_, Bytes.empty)))))
  private val params = get(
    R.decodePoolParameters(
      array(
        V.Text(R.PoolParameterFormat),
        V.UInt(9),
        V.UInt(0),
        V.UInt(0),
        V.UInt(1),
        V.UInt(0),
        V.UInt(1),
        V.UInt(0),
        V.UInt(1),
        V.UInt(1)
      )
    )
  )
  private val globals = get(
    R.decodePulserGlobals(
      array(
        V.Text(R.PulserGlobalFormat),
        V.UInt(1530),
        V.UInt(1),
        V.UInt(20),
        V.UInt(BigInt("45000000000000000")),
        V.UInt(5)
      )
    )
  )
  private def profile(window: Int) = get(
    CoherentSequence.syntheticRewardProfile(
      params,
      globals,
      window,
      Some(false),
      Some(0),
      Some(false),
      Some(false),
      Some(0),
      Some(false),
      Some(false)
    )
  )

  sys.env.get("STAKE_SEQUENCE_EVIDENCE").foreach { location =>
    val dir = Path.of(location)
    val input = get(SequenceInput.load(dir))
    import ReferenceJson.Json
    def replace(j: Json, path: List[String], v: Json): Json = path match
      case Nil => v
      case key :: rest =>
        val fs = j.asInstanceOf[Json.Obj].fields
        Json.Obj(fs.updated(key, replace(fs(key), rest, v)))
    def render(j: Json): String = j match
      case Json.Obj(fs) =>
        fs.toVector.sortBy(_._1).map((k, v) => "\"" + k + "\":" + render(v)).mkString("{", ",", "}")
      case Json.Arr(xs) => xs.map(render).mkString("[", ",", "]")
      case Json.Str(s)  => "\"" + s + "\""
      case Json.Num(s)  => s
      case Json.Lit(s)  => s
    def json(name: String) = ReferenceJson.parse(input.originals(name))
    val protocol = json("pre-protocol-state.md")
    // Synthetic supplied anchor only: exact signed blocks/parent are unchanged. Shift epoch
    // geometry so the first is a successor, and derive its original verified nonce by tick.
    val files = input.originals
      .updated(
        "transfer-genesis.md",
        raw(render(replace(json("transfer-genesis.md"), List("epochLength"), Json.Num("1530"))))
      )
      .updated(
        "pre-tips.md",
        raw(
          render(
            Json.Arr(
              json("pre-tips.md")
                .asInstanceOf[Json.Arr]
                .values
                .map(j => replace(j, List("epoch"), Json.Num("0")))
            )
          )
        )
      )
      .updated(
        "pre-ledger-state.md",
        raw(render(replace(json("pre-ledger-state.md"), List("lastEpoch"), Json.Num("0"))))
      )
      .updated(
        "pre-protocol-state.md",
        raw(
          render(
            replace(
              replace(
                protocol,
                List("candidateNonce"),
                ReferenceJson.field(protocol, "epochNonce")
              ),
              List("lastEpochBlockNonce"),
              Json.Lit("null")
            )
          )
        )
      )
    val manifest = raw(
      "format\t" + SequenceInput.ProfileId + "\n" + SequenceInput.sources.toVector
        .sortBy(_._1)
        .map((k, n) => k + "\t" + ClusterHeaderObservation.sha256(files(n)).hex)
        .mkString("\n") + "\n"
    )
    val context = get(SequenceInput.bind(manifest, files))
    val utxo = get(Bytes.fromHex(new String(files("pre-utxo-cbor.md").toArray, "UTF-8").trim))
    val seed = get(
      ConwayStakeSeed.decode(
        files("pre-ledger-state.md"),
        context.sourcePins("preLedgerSha256"),
        utxo,
        ClusterHeaderObservation.sha256(utxo),
        1530
      )
    )
    val blocks = get(
      ClusterHeaderObservation.capturesBounded(dir.resolve("scala-sequence-capture.md"), 16)
    )
      .map(c => get(SequenceInput.block(BoundedChainFollower.Original(c.headerEnvelope, c.block))))
    def runtime(window: Int) = CoherentSequence
      .createWithSyntheticRewards[IO](
        context,
        seed,
        profile(window),
        B.Pots(0, 0, context.ledger.fees, globals.maxSupply),
        Map.empty,
        Map.empty,
        pin
      )
      .map(get(_))
    def candidate(r: CoherentSequence.Runtime[IO], snap: CoherentSequence.Snapshot) = for
      preview <- r
        .prepareSyntheticSuccessor(snap.fence, blocks.head.header.hash, blocks.head.header.slot)
        .map(get(_))
      candidate <- r.prepareSyntheticSuccessorBlock(snap.fence, preview, blocks.head).map(get(_))
    yield candidate
    def same(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
      assertEquals(a.id, b.id)
      assertEquals(a.ledger.id, b.ledger.id)
      assertEquals(a.ledger.environment.id, b.ledger.environment.id)
      assertEquals(a.ledger.outputMap, b.ledger.outputMap)
      assertEquals(a.ledger.fees, b.ledger.fees)
      assertEquals(a.stake.get.id, b.stake.get.id)
      assertEquals(a.stake.get.context.id, b.stake.get.context.id)
      val sa = a.stake.get.snapshots
      val sb = b.stake.get.snapshots
      Vector((sa.mark, sb.mark), (sa.set, sb.set), (sa.go, sb.go)).foreach { (x, y) =>
        assertEquals(x.active, y.active)
        assertEquals(x.pools, y.pools)
        assertEquals(x.total, y.total)
      }
      assertEquals(sa.fees, sb.fees)
      assertEquals(a.nonces.id, b.nonces.id)
      assertEquals(a.nonces.fields, b.nonces.fields)
      assertEquals(a.certificates.state.id, b.certificates.state.id)
      assertEquals(a.certificates.state.contextId, b.certificates.state.contextId)
      assertEquals(a.syntheticRewards.get.id, b.syntheticRewards.get.id)

    def authorize(e: SyntheticRecoveryModel.Envelope) =
      val recorded = e.claim
      SyntheticRecoveryModel
        .accept[IO](
          e,
          new SyntheticRecoveryModel.ControllerAuthority[IO] {
            def authorize(claim: SyntheticRecoveryModel.Claim) =
              IO.pure(Either.cond(claim == recorded, (), "unrecorded claim"))
          }
        )
        .map(get(_))
    def roundtrip(r: CoherentSequence.Runtime[IO], name: String) = for
      envelope <- r.exportSyntheticRecovery(pin).map(get(_))
      _ = println(
        s"RECOVERY-MEASURE $name entries=${envelope.claim.measurement.entries} bytes=${envelope.claim.measurement.payloadBytes}"
      )
      accepted <- authorize(envelope)
      restored <- SyntheticRecoveryModel.restore[IO](envelope, accepted).map(get(_))
    yield (envelope, restored)
    def ordinary(r: CoherentSequence.Runtime[IO], block: SequenceInput.Block) = for
      before <- r.snapshot
      pending <- r.prepareSyntheticBlock(before.fence, block).map(get(_))
      result <- r.publish(pending).map(get(_))
    yield result

    test(
      "Absent anchor and early successor restore exact identity with fresh owners and continuation"
    ) {
      (for
        r <- runtime(50)
        seed <- r.snapshot
        c <- candidate(r, seed)
        first <- r.publish(c).map(get(_))
        pair <- roundtrip(r, "absent-early-successor")
        (envelope, restored) = pair
        _ = assert(envelope.image.records.head.boundary.isDefined)
        _ = assert(first.state.syntheticRewards.get.frozen.isEmpty)
        after <- restored.snapshot
        _ = same(first.state, after.state)
        _ = assertEquals(first.state.revision, after.state.revision)
        _ = assert(!(first.state.stake.get eq after.state.stake.get))
        wrongFence <- restored.rollbackTo(seed.fence, seed.state.acquisition.tip)
        _ = assertEquals(wrongFence, Left(CoherentSequence.Failure.ForeignFence))
        sourceBefore <- r.snapshot
        foreign <- r.prepareSyntheticBlock(sourceBefore.fence, blocks(1)).map(get(_))
        rejected <- restored.publish(foreign)
        _ = assertEquals(rejected, Left(CoherentSequence.Failure.ForeignCandidate))
        a <- ordinary(r, blocks(1))
        b <- ordinary(restored, blocks(1))
        _ = same(a.state, b.state)
        last <- restored.snapshot
        undone <- restored.rollbackTo(last.fence, seed.state.acquisition.tip).map(get(_))
        _ = same(seed.state, undone.state)
      yield ()).unsafeToFuture()
    }

    test("retained ordinary freeze after undo and compaction preserves historical origin exactly") {
      (for
        r <- runtime(50)
        seed <- r.snapshot
        c <- candidate(r, seed)
        first <- r.publish(c).map(get(_))
        initialSecond <- ordinary(r, blocks(1))
        _ = assert(initialSecond.state.syntheticRewards.get.frozen.isDefined)
        beforeUndo <- r.snapshot
        _ <- r.rollbackTo(beforeUndo.fence, first.state.acquisition.tip).map(get(_))
        replayed <- ordinary(r, blocks(1))
        _ = assert(
          replayed.state.syntheticRewards.get.frozen.get.id != initialSecond.state.syntheticRewards.get.frozen.get.id
        )
        now <- r.snapshot
        compact <- r.advanceAnchor(now.fence, first.state.acquisition.tip).map(get(_))
        pair <- roundtrip(r, "retained-freeze-after-undo-compaction")
        (envelope, restored) = pair
        _ = assert(envelope.image.states.head.syntheticRewards.get.frozen.isEmpty)
        _ = assert(envelope.image.records.head.position.id != envelope.image.states.head.id)
        recovered <- restored.snapshot
        _ = same(compact.state, recovered.state)
        _ = assertEquals(compact.state.revision, recovered.state.revision)
        _ = assertEquals(
          recovered.state.syntheticRewards.get.frozen.get.id,
          replayed.state.syntheticRewards.get.frozen.get.id
        )
        _ = assertEquals(
          B.frozenRecoveryView(recovered.state.syntheticRewards.get.frozen.get).kind,
          B.FrozenRecoveryKind.Ordinary
        )
        rBack <- r.rollbackTo(compact.fence, first.state.acquisition.tip).map(get(_))
        sBack <- restored.rollbackTo(recovered.fence, first.state.acquisition.tip).map(get(_))
        _ = same(rBack.state, sBack.state)
        a <- ordinary(r, blocks(1))
        b <- ordinary(restored, blocks(1))
        _ = same(a.state, b.state)
      yield ()).unsafeToFuture()
    }

    test("post-boundary frozen anchor restores fresh work and continuation") {
      (for
        r <- runtime(10)
        seed <- r.snapshot
        c <- candidate(r, seed)
        first <- r.publish(c).map(get(_))
        now <- r.snapshot
        _ <- r.advanceAnchor(now.fence, first.state.acquisition.tip).map(get(_))
        pair <- roundtrip(r, "post-boundary-complete-anchor")
        (envelope, restored) = pair
        _ = assertEquals(envelope.image.records.size, 0)
        before <- r.snapshot
        after <- restored.snapshot
        _ = same(before.state, after.state)
        _ = assertEquals(
          B.frozenRecoveryView(after.state.syntheticRewards.get.frozen.get).kind,
          B.FrozenRecoveryKind.PostBoundary
        )
        _ = assert(
          !(before.state.syntheticRewards.get.pulser.get eq after.state.syntheticRewards.get.pulser.get)
        )
        a <- ordinary(r, blocks(1))
        b <- ordinary(restored, blocks(1))
        _ = same(a.state, b.state)
      yield ()).unsafeToFuture()
    }

    test("controller rejection, deadline and cancellation expose no recovery capability") {
      (for
        r <- runtime(50)
        envelope <- r.exportSyntheticRecovery(pin).map(get(_))
        denied <- SyntheticRecoveryModel.accept[IO](
          envelope,
          new SyntheticRecoveryModel.ControllerAuthority[IO] {
            def authorize(claim: SyntheticRecoveryModel.Claim) = IO.pure(Left("not recorded"))
          }
        )
        _ = assertEquals(denied.left.toOption, Some("not recorded"))
        timedOut <- SyntheticRecoveryModel.accept[IO](
          envelope,
          new SyntheticRecoveryModel.ControllerAuthority[IO] {
            def authorize(claim: SyntheticRecoveryModel.Claim) = IO.never
          },
          20.millis
        )
        _ = assertEquals(timedOut.left.toOption, Some("controller authorization deadline"))
        entered <- Deferred[IO, Unit]
        canceled <- Deferred[IO, Unit]
        fiber <- SyntheticRecoveryModel
          .accept[IO](
            envelope,
            new SyntheticRecoveryModel.ControllerAuthority[IO] {
              def authorize(claim: SyntheticRecoveryModel.Claim) =
                (entered.complete(()) *> IO.never[Either[String, Unit]])
                  .onCancel(canceled.complete(()).void)
            }
          )
          .start
        _ <- entered.get
        _ <- fiber.cancel
        _ <- canceled.get.timeout(1.second)
        outcome <- fiber.join
        _ = assert(outcome.isCanceled)
        refused <- SyntheticRecoveryModel.restore[IO](envelope, null)
        _ = assert(refused.isLeft)
      yield ()).unsafeToFuture()
    }

    test("controller gate and replay reject mutated, missing and substituted historical records") {
      (for
        r <- runtime(10)
        seed <- r.snapshot
        c <- candidate(r, seed)
        _ <- r.publish(c).map(get(_))
        envelope <- r.exportSyntheticRecovery(pin).map(get(_))
        accepted <- authorize(envelope)
        copy = get(SyntheticRecoveryModel.prepare(envelope.image, pin))
        substituted <- SyntheticRecoveryModel.restore[IO](copy, accepted)
        _ = assert(substituted.isLeft)
        record = envelope.image.records.head
        mutations = Vector(
          record.copy(boundary = None),
          record.copy(position = record.position.copy(id = pin)),
          record.copy(position = record.position.copy(revision = record.position.revision + 1)),
          record.copy(historicalAfterId = pin),
          record.copy(original = blocks(1).original)
        )
        _ <- mutations.traverse_ { record =>
          val bad =
            get(SyntheticRecoveryModel.prepare(envelope.image.copy(records = Vector(record)), pin))
          for
            approved <- authorize(
              bad
            ) // Even an authorized structurally inconsistent image must fail replay.
            rejected <- SyntheticRecoveryModel.restore[IO](bad, approved)
          yield assert(rejected.isLeft)
        }
        foreignRuntime <- runtime(25)
        foreignSeed <- foreignRuntime.snapshot
        foreignCandidate <- candidate(foreignRuntime, foreignSeed)
        foreignAfter <- foreignRuntime.publish(foreignCandidate).map(get(_))
        swapped = get(
          SyntheticRecoveryModel.prepare(
            envelope.image.copy(states = envelope.image.states.updated(1, foreignAfter.state)),
            pin
          )
        )
        approvedSwap <- authorize(swapped)
        refused <- SyntheticRecoveryModel.restore[IO](swapped, approvedSwap)
        _ = assert(refused.isLeft)
        _ = assert(
          P.reownForRecovery(
            envelope.image.states.last.syntheticRewards.get.pulser.get,
            foreignAfter.state.syntheticRewards.get.frozen.get
          ).isLeft
        )
      yield ()).unsafeToFuture()
    }

    test("post-boundary anchor replays a second successor with mixed certificate contexts") {
      val fs = files
        .updated(
          "transfer-genesis.md",
          raw(
            render(
              replace(
                ReferenceJson.parse(files("transfer-genesis.md")),
                List("epochLength"),
                Json.Num("61")
              )
            )
          )
        )
        .updated(
          "pre-tips.md",
          raw(
            render(
              Json.Arr(
                ReferenceJson
                  .parse(files("pre-tips.md"))
                  .asInstanceOf[Json.Arr]
                  .values
                  .map(j => replace(j, List("epoch"), Json.Num("24")))
              )
            )
          )
        )
        .updated(
          "pre-ledger-state.md",
          raw(
            render(
              replace(
                ReferenceJson.parse(files("pre-ledger-state.md")),
                List("lastEpoch"),
                Json.Num("24")
              )
            )
          )
        )
        .updated(
          "pre-protocol-state.md",
          raw(
            render(
              replace(
                ReferenceJson.parse(files("pre-protocol-state.md")),
                List("labNonce"),
                Json.Lit("null")
              )
            )
          )
        )
      val manifest = raw(
        "format\t" + SequenceInput.ProfileId + "\n" + SequenceInput.sources.toVector
          .sortBy(_._1)
          .map((k, n) => k + "\t" + ClusterHeaderObservation.sha256(fs(n)).hex)
          .mkString("\n") + "\n"
      )
      val in = get(SequenceInput.bind(manifest, fs))
      val prepared = get(
        ConwayStakeSeed.decode(
          fs("pre-ledger-state.md"),
          in.sourcePins("preLedgerSha256"),
          utxo,
          ClusterHeaderObservation.sha256(utxo),
          61
        )
      )
      val gs = get(
        R.decodePulserGlobals(
          array(
            V.Text(R.PulserGlobalFormat),
            V.UInt(61),
            V.UInt(1),
            V.UInt(20),
            V.UInt(globals.maxSupply),
            V.UInt(5)
          )
        )
      )
      val prof = get(
        CoherentSequence.syntheticRewardProfile(
          params,
          gs,
          1,
          Some(false),
          Some(0),
          Some(false),
          Some(false),
          Some(0),
          Some(false),
          Some(false)
        )
      )
      def successor(r: CoherentSequence.Runtime[IO], b: SequenceInput.Block) = for
        before <- r.snapshot
        preview <- r
          .prepareSyntheticSuccessor(before.fence, b.header.hash, b.header.slot)
          .map(get(_))
        pending <- r.prepareSyntheticSuccessorBlock(before.fence, preview, b).map(get(_))
        after <- r.publish(pending).map(get(_))
      yield after
      (for
        r <- CoherentSequence
          .createWithSyntheticRewards[IO](
            in,
            prepared,
            prof,
            B.Pots(0, 0, in.ledger.fees, gs.maxSupply),
            Map.empty,
            Map.empty,
            pin
          )
          .map(get(_))
        first <- successor(r, blocks.head)
        _ = assertEquals(first.state.ledger.environment.epoch, BigInt(25))
        second <- successor(r, blocks(1))
        _ = assertEquals(second.state.ledger.environment.epoch, BigInt(26))
        now <- r.snapshot
        compact <- r.advanceAnchor(now.fence, first.state.acquisition.tip).map(get(_))
        pair <- roundtrip(r, "post-boundary-anchor-mixed-context-successor")
        (envelope, restored) = pair
        _ = assert(envelope.image.records.head.boundary.isDefined)
        recovered <- restored.snapshot
        _ = same(compact.state, recovered.state)
        _ <- r.rollbackTo(compact.fence, first.state.acquisition.tip).map(get(_))
        _ <- restored.rollbackTo(recovered.fence, first.state.acquisition.tip).map(get(_))
        a <- successor(r, blocks(1))
        b <- successor(restored, blocks(1))
        _ = same(a.state, b.state)
      yield ()).unsafeToFuture()
    }

    test("aggregate budget charges nested pool memberships instead of only map sizes") {
      import lab.ledger.ConwayStake as S
      (for
        r <- runtime(50)
        snap <- r.snapshot
        _ = {
          val owners = (0 until 4096)
            .map(i =>
              Bytes(
                Vector.fill(24)(0.toByte) ++ Vector(
                  (i >>> 24).toByte,
                  (i >>> 16).toByte,
                  (i >>> 8).toByte,
                  i.toByte
                )
              )
            )
            .toSet
          val credential = S.Credential(false, Bytes(Vector.fill(28)(1.toByte)))
          val pools = (1 to 64)
            .map(i =>
              Bytes(Vector.fill(28)(i.toByte)) -> S
                .Pool(pin, 0, 0, S.Ratio(0, 1), credential, owners, Set.empty, 0)
            )
            .toMap
          val sc = get(S.context(pin, 1530, Map.empty, pools))
          val so = S.owner(); val bo = B.owner()
          val stake = get(
            S.seed(
              so,
              sc,
              context.ledger,
              pin,
              get(S.recompute(context.ledger.outputMap)),
              S.Snapshots(S.emptySnapshot, S.emptySnapshot, S.emptySnapshot, 0)
            )
          )
          val bc = get(
            B.context(
              bo,
              so,
              pin,
              stake,
              B.Pots(0, 0, context.ledger.fees, globals.maxSupply),
              Map.empty,
              Map.empty
            )
          )
          val signal = get(B.signal(bo, bc, pin, 1530))
          val preview =
            get(B.preview(bo, bc, signal, B.RewardPhase.Absent(get(B.suppliedAbsent(bo, bc, pin)))))
          val measured = SyntheticRecoveryBudget.measure(
            context,
            Vector(snap.state),
            Vector.empty,
            Vector(preview),
            Vector.empty
          )
          assert(measured.swap.toOption.get.contains("aggregate budget"))
          assert(
            SyntheticRecoveryModel
              .prepare(
                CoherentSequence
                  .RecoveryImage(context, 8, Vector.fill(10)(snap.state), Vector.empty),
                pin
              )
              .isLeft
          )
        }
      yield ()).unsafeToFuture()
    }
  }
