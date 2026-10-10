// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Deferred, Ref, Outcome}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, LinkOption, Path}
import lab.cbor.Bytes
import lab.ledger.ClusterTransition
import lab.network.ChainSync
import lab.submission.AdmissionProfile
import scala.concurrent.duration.*

class RestrictedRuntimeCheckpointSuite extends munit.FunSuite:
  import CoherentStakeImages.*
  import NativeLiveBoundaryMain.{hash, obj, read, sha}
  import ReferenceJson.{field, string, uint}
  private val R = RestrictedRuntimeCheckpoint
  private def get[A](e: Either[?, A]): A = e.fold(x => fail(x.toString), identity)
  private val dummy = Bytes(Vector.fill(32)(1.toByte))
  test("untrusted malformed checkpoints cannot create accepted authority or a runtime") {
    Vector(
      Bytes.empty,
      Bytes(Vector.fill(32)(0.toByte)),
      Bytes(Vector.fill(R.MaxBytes + 1)(0.toByte))
    )
      .foreach(b => assert(R.decode(b).isLeft))
    (for
      accepted <- R.accept[IO](null, null)
      _ = assert(accepted.isLeft)
      restored <- R.recover[IO](Bytes.empty, None, null, 1.second)
      _ = assert(restored.isLeft)
    yield ()).unsafeToFuture()
  }
  test("reward composition remains outside complete linear restore domain") {
    (for
      runtime <- SyntheticBoundaryCompositionFixture.runtime
      snapshot <- runtime.snapshot
      result = R.encode(
        snapshot,
        SyntheticBoundaryCompositionFixture.context,
        Sources(dummy, dummy, dummy),
        SourceBinding(dummy, dummy, dummy, dummy, dummy, dummy),
        dummy,
        dummy,
        0
      )
      _ = assert(result.isLeft)
    yield ()).unsafeToFuture()
  }
  test("wire parser rejects malformed lengths, history bounds, booleans and point relationships") {
    // Structural wire fixture only: the opaque blobs are deliberately not valid ledger/blocks.
    // Decoding this fixture conveys no native-source or restore authority.
    val bytes = new java.io.ByteArrayOutputStream()
    val out = new java.io.DataOutputStream(bytes)
    val positions = scala.collection.mutable.Map.empty[String, Int]
    def hash(): Unit = out.write(dummy.toArray)
    def number(name: String, n: Long): Unit = { positions(name) = bytes.size; out.writeLong(n) }
    def point(name: String, slot: Long, no: Long): Unit = {
      hash(); number(name + "Slot", slot); number(name + "Block", no)
    }
    def int(name: String, n: Int): Unit = { positions(name) = bytes.size; out.writeInt(n) }
    def blob(name: String): Unit = { int(name, 1); out.writeByte(0x80) }
    out.write("RPLREST1".getBytes("US-ASCII")); hash(); hash(); number("generation", 0)
    hash(); hash(); hash(); point("source", 1, 1); point("terminal", 2, 2); hash();
    number("revision", 1)
    int("capacity", 8); int("count", 1); hash(); hash(); positions("eligibility") = bytes.size;
    out.writeByte(1); hash()
    blob("ledger"); blob("stake"); blob("header"); blob("block")
    val raw = Bytes.fromArray(bytes.toByteArray)
    val decoded = get(R.decode(raw))
    assert(!decoded.restoreAuthorized)
    def changedInt(name: String, n: Int): Bytes =
      val a = raw.toArray; java.nio.ByteBuffer.wrap(a).putInt(positions(name), n);
      Bytes.fromArray(a)
    def changedLong(name: String, n: Long): Bytes =
      val a = raw.toArray; java.nio.ByteBuffer.wrap(a).putLong(positions(name), n);
      Bytes.fromArray(a)
    Vector(0, 7, 8, 31, positions("ledger"), raw.size - 1).foreach(n =>
      assert(R.decode(Bytes(raw.value.take(n))).isLeft)
    )
    assert(R.decode(Bytes(raw.value :+ 0.toByte)).isLeft)
    Vector("ledger", "stake", "header", "block").foreach { name =>
      assert(R.decode(changedInt(name, -1)).isLeft)
      assert(R.decode(changedInt(name, R.MaxBytes)).isLeft)
    }
    Vector(
      changedInt("capacity", 0),
      changedInt("capacity", 9),
      changedInt("count", -1),
      changedInt("count", 9),
      changedLong("revision", 2),
      changedLong("terminalBlock", 3),
      changedLong("terminalSlot", 0),
      changedLong("sourceSlot", 1000),
      changedLong("generation", -1),
      Bytes(raw.value.updated(positions("eligibility"), 2.toByte))
    )
      .foreach(b => assert(R.decode(b).isLeft))
  }

  sys.env.get("PLUTUS_LIVE_BUNDLE").foreach { directory =>
    val root = Path.of(directory).toAbsolutePath.normalize()
    lazy val pinned: Map[String, Bytes] =
      val manifest =
        read(Path.of(sys.env.getOrElse("PLUTUS_LIVE_MANIFEST", fail("manifest required"))), 65536)
      assertEquals(
        sha(manifest).hex,
        sys.env.getOrElse("PLUTUS_LIVE_MANIFEST_SHA256", fail("independent manifest pin required"))
      )
      val parsed = ReferenceJson.parse(manifest)
      assertEquals(string(field(parsed, "schema")), "plutus-retained-live-inputs-v1")
      val rows = obj(field(parsed, "inputs"))
      assert(rows.nonEmpty && rows.size <= 128)
      rows.map { (name, row) =>
        val relative = Path.of(name)
        assert(
          !relative.isAbsolute && !name.contains("\\") && relative.normalize().toString == name
        )
        val path = root.resolve(relative).normalize()
        assert(
          path.startsWith(root) && path != root && Files
            .isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        )
        assert(path.toRealPath().startsWith(root.toRealPath()))
        val bytes = read(path, 1048576)
        assertEquals(BigInt(bytes.size), uint(field(row, "bytes")), name)
        assertEquals(sha(bytes), hash(field(row, "sha256")), name)
        name -> bytes
      }
    def json(name: String) =
      ReferenceJson.parse(pinned.getOrElse(name, fail("unpinned input: " + name)))
    lazy val joined =
      val inputs = json("initial/adapter-inputs.json")
      obj(field(inputs, "inputs")).keys.foreach(n => assert(pinned.contains("initial/" + n)))
      NativeLiveBoundaryMain.initial(
        root.resolve("initial"),
        sha(pinned("initial/adapter-inputs.json")).hex,
        AdmissionProfile.PlutusV3
      )
    lazy val blocks =
      val names = pinned.keys.filter(_.matches("originals/block-[0-9]{4}\\.cbor")).toVector.sorted
      assert(names.nonEmpty && names.size <= 16)
      names.zipWithIndex.map { (name, index) =>
        assertEquals(name, f"originals/block-$index%04d.cbor")
        get(
          SequenceInput.block(
            BoundedChainFollower
              .Original(pinned(name.stripSuffix(".cbor") + "-header.cbor"), pinned(name))
          )
        )
      }

    lazy val context = get(SequenceInput.fromNativeDiagnostic(joined, joined.id))
    lazy val sources = Sources(
      joined.ledger.epochComponents.parameters.genesisOriginal,
      joined.ledger.epochComponents.parameters.current.original,
      pinned("initial/adapter-inputs.json")
    )
    lazy val binding = SourceBinding(
      context.id,
      joined.id,
      context.stakeSourceId,
      sha(sources.genesis),
      sha(sources.parameters),
      sha(sources.manifest)
    )
    def runtime = CoherentSequence
      .createPlutusDiagnosticWithStake[IO](context, joined.ledger.epochComponents.stake)
      .map(get(_))
    def publish(r: CoherentSequence.Runtime[IO], block: SequenceInput.Block) =
      r.prepare(block).map(get(_)).flatMap(c => r.publish(c).map(get(_))).void
    def verify(p: Publication) =
      CoherentStakeImages.verify(p.ledger, p.stake, p.expected, p.publicationSHA256, context)

    def checkpoint(
        snapshot: CoherentSequence.Snapshot,
        store: Bytes = dummy,
        session: Bytes = dummy,
        generation: Long = 0
    ) =
      get(R.encode(snapshot, context, sources, binding, store, session, generation))
    def controller(expected: R.Claim) = new R.ControllerAuthority[IO]:
      def authorize(claim: R.Claim) = IO.pure(
        Either.cond(claim == expected, (), "independent record/store/session/generation mismatch")
      )
    def accept(p: R.Publication) =
      R.accept[IO](get(R.decode(p.bytes)), controller(p.claim)).map(get(_))
    def restored(p: R.Publication, a: R.AcceptedAuthority) =
      R.recover[IO](p.bytes, Some(a), joined, 5.seconds).map(get(_))
    def complete = for
      r <- runtime
      _ <- blocks.traverse_(publish(r, _))
      s <- r.snapshot
    yield (r, s, checkpoint(s))

    test(
      "full checked replay creates fresh lineages and rejects old runtime fences, candidates and raw undo"
    ) {
      (for
        original <- runtime
        _ <- blocks.dropRight(1).traverse_(publish(original, _))
        oldBefore <- original.snapshot
        oldCandidate <- original.prepare(blocks.last).map(get(_))
        oldApplied <- original.publish(oldCandidate).map(get(_))
        oldAfter <- original.snapshot
        p = checkpoint(oldAfter)
        a <- accept(p)
        first <- restored(p, a)
        second <- restored(p, a)
        _ = assertNotEquals(
          first.snapshot.state.ledger.checkpointId,
          oldAfter.state.ledger.checkpointId
        )
        _ = assertNotEquals(
          first.snapshot.state.ledger.checkpointId,
          second.snapshot.state.ledger.checkpointId
        )
        _ = assertNotEquals(first.restoredStateId, second.restoredStateId)
        _ = assertNotEquals(first.restoredStateId, p.claim.historicalStateId)
        _ = assertEquals(first.snapshot.state.ledger.outputMap, oldAfter.state.ledger.outputMap)
        _ = assertEquals(first.snapshot.state.stake.get.utxo, oldAfter.state.stake.get.utxo)
        _ = assertEquals(first.snapshot.state.nonces.fields, oldAfter.state.nonces.fields)
        _ = assertEquals(first.restoredRevision, BigInt(blocks.size))
        _ = assert(!first.pendingAdmissionRestored)
        fence <- first.runtime.rollbackTo(oldBefore.fence, oldBefore.state.acquisition.tip)
        candidate <- first.runtime.publish(oldCandidate)
        _ = assertEquals(fence, Left(CoherentSequence.Failure.ForeignFence))
        _ = assertEquals(candidate, Left(CoherentSequence.Failure.ForeignCandidate))
        _ = assert(
          ClusterTransition
            .undo(
              first.snapshot.state.ledger,
              first.restoredRevision,
              oldApplied.ledgerObservation.undo
            )
            .isLeft
        )
        after <- first.runtime.snapshot
        _ = assertEquals(after.state.id, first.restoredStateId)
      yield ()).unsafeToFuture()
    }
    test("restored runtime owns new replay undo receipts and cannot roll back below source floor") {
      (for
        prepared <- complete
        (_, _, p) = prepared
        a <- accept(p)
        recovered <- restored(p, a)
        floor = ChainSync.Point.Block(
          get(ChainSync.UInt64.from(p.claim.sourcePoint.slot)),
          p.claim.sourcePoint.hash
        )
        rolled <- recovered.runtime.rollbackTo(recovered.snapshot.fence, floor).map(get(_))
        _ = assertEquals(rolled.state.ledger.outputMap, context.ledger.outputMap)
        _ = assertEquals(rolled.state.acquisition.tip, floor)
        rejected <- recovered.runtime.rollbackTo(rolled.fence, ChainSync.Point.Origin)
        _ = assertEquals(rejected, Left(CoherentSequence.Failure.OutsideRetainedWindow))
        next <- recovered.runtime.prepare(blocks.head).map(get(_))
        published <- recovered.runtime.publish(next).map(get(_))
        _ = assertEquals(published.state.certificates.state.tip.hash, blocks.head.header.hash)
      yield ()).unsafeToFuture()
    }
    test(
      "rollback histories and compacted prefixes refuse export rather than invent replay ancestry"
    ) {
      (for
        r <- runtime
        before <- r.snapshot
        _ <- publish(r, blocks.head)
        after <- r.snapshot
        rolled <- r.rollbackTo(after.fence, before.state.acquisition.tip).map(get(_))
        _ <- publish(r, blocks.head)
        replayed <- r.snapshot
        _ = assert(R.encode(replayed, context, sources, binding, dummy, dummy, 0).isLeft)
        linear <- runtime
        _ <- publish(linear, blocks.head)
        current <- linear.snapshot
        _ <- linear.advanceAnchor(current.fence, current.state.acquisition.tip).map(get(_))
        compacted <- linear.snapshot
        _ = assert(R.encode(compacted, context, sources, binding, dummy, dummy, 0).isLeft)
      yield ()).unsafeToFuture()
    }
    test(
      "exact controller claim and independently checked source join are required before runtime construction"
    ) {
      (for
        prepared <- complete
        (_, s, p) = prepared
        a <- accept(p)
        missing <- R.recover[IO](p.bytes, None, joined, 5.seconds)
        _ = assert(missing.isLeft)
        variants = Vector(
          checkpoint(s, sha(dummy)),
          checkpoint(s, session = sha(dummy)),
          checkpoint(s, generation = 1)
        )
        _ <- variants.traverse_ { changed =>
          for
            refused <- R.accept[IO](get(R.decode(changed.bytes)), controller(p.claim))
            _ = assert(refused.isLeft)
            recovered <- R.recover[IO](changed.bytes, Some(a), joined, 5.seconds)
            _ = assert(recovered.isLeft)
          yield ()
        }
        // sourceJoin is a fixed-width field after magic/store/session/generation/context.
        changed = Bytes(p.bytes.value.updated(112, (p.bytes.value(112) ^ 1).toByte))
        image = get(R.decode(changed))
        acceptedChanged <- R.accept[IO](image, controller(image.claim)).map(get(_))
        stages <- Ref.of[IO, Vector[String]](Vector.empty)
        wrongSource <- R.recoverObserved[IO](
          changed,
          Some(acceptedChanged),
          joined,
          5.seconds,
          x => stages.update(_ :+ x)
        )
        observed <- stages.get
        _ = assert(wrongSource.isLeft && !observed.contains("fresh-runtime"))
      yield ()).unsafeToFuture()
    }
    test("authorized corrupted originals fail without exposing a partially replayed runtime") {
      (for
        prepared <- complete
        (_, _, p) = prepared
        changed = Bytes(p.bytes.value.updated(p.bytes.size - 1, (p.bytes.value.last ^ 1).toByte))
        image = get(R.decode(changed))
        a <- R.accept[IO](image, controller(image.claim)).map(get(_))
        result <- R.recover[IO](changed, Some(a), joined, 5.seconds)
        _ = assert(result.isLeft)
      yield ()).unsafeToFuture()
    }
    test(
      "accepted but false terminal tuple or stake image fails after private replay without a runtime"
    ) {
      (for
        prepared <- complete
        (_, snapshot, p) = prepared
        pair = get(CoherentStakeImages.encodeSnapshot(snapshot, context, sources, binding))
        stakeOffset = p.bytes.value.indexOfSlice(pair.stake.value)
        _ = assert(stakeOffset >= 0)
        stakeLast = stakeOffset + pair.stake.size - 1
        // Terminal certificate identity follows the fixed header/points/history fields.
        falseCertificate = Bytes(p.bytes.value.updated(320, (p.bytes.value(320) ^ 1).toByte))
        falseStake = Bytes(p.bytes.value.updated(stakeLast, (p.bytes.value(stakeLast) ^ 1).toByte))
        _ <- Vector(falseCertificate, falseStake).traverse_ { changed =>
          for
            image <- IO(get(R.decode(changed)))
            a <- R.accept[IO](image, controller(image.claim)).map(get(_))
            stages <- Ref.of[IO, Vector[String]](Vector.empty)
            result <- R.recoverObserved[IO](
              changed,
              Some(a),
              joined,
              5.seconds,
              x => stages.update(_ :+ x)
            )
            observed <- stages.get
            _ = assert(result.isLeft)
            _ = assert(observed.contains("verify"))
            _ = assert(result.swap.toOption.get.contains("terminal"))
          yield ()
        }
      yield ()).unsafeToFuture()
    }

    test(
      "cancellation during private replay exposes no runtime; cooperative deadline fails closed"
    ) {
      TestControl
        .executeEmbed(for
          prepared <- complete
          (_, _, p) = prepared
          a <- accept(p)
          entered <- Deferred[IO, Unit]
          returned <- Ref.of[IO, Boolean](false)
          fiber <- R
            .recoverObserved[IO](
              p.bytes,
              Some(a),
              joined,
              5.seconds,
              stage => if stage == "publish" then entered.complete(()).void *> IO.never else IO.unit
            )
            .flatTap(_ => returned.set(true))
            .start
          _ <- entered.get
          _ <- fiber.cancel
          outcome <- fiber.join
          exposed <- returned.get
          _ = assert(
            outcome.isInstanceOf[Outcome.Canceled[IO, Throwable, Either[String, R.Restored[IO]]]]
          )
          _ = assert(!exposed)
          timed <- R.recoverObserved[IO](
            p.bytes,
            Some(a),
            joined,
            50.millis,
            stage => if stage == "publish" then IO.never else IO.unit
          )
          _ = assertEquals(timed, Left("recovery deadline"))
        yield ())
        .unsafeToFuture()
    }
  }
