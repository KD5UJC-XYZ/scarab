// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import lab.ledger.ClusterTransition as Ledger
import scala.concurrent.duration.*

class LocalDerivedCheckpointSuite extends munit.FunSuite:
  import LocalDerivedCheckpoint as Local
  private def get[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def hash(n: Int): Bytes = Bytes(Vector.fill(32)(n.toByte))

  /** TEST ONLY: stands in for a controller-retained exact record outside the writer process.
    * Deliberately accepting mutated fixtures below tests structural checks, not prefix proof.
    */
  private final class TestOnlyController(record: Local.Claim) extends Local.ControllerAuthority[IO]:
    def authorize(claim: Local.Claim): IO[Either[String, Unit]] =
      IO.pure(Either.cond(claim == record, (), "test controller rejects unrecorded publication"))
  private def accept(raw: Bytes): IO[Local.AcceptedAuthority] =
    val envelope = get(Local.decode(raw))
    Local.accept[IO](envelope, new TestOnlyController(envelope.claim)).map(get(_))
  private def restore(raw: Bytes, context: Bytes): IO[CoherentSequence.Runtime[IO]] =
    accept(raw).flatMap(a => Local.recover[IO](raw, context, Some(a), 10.seconds)).map(get(_))
  private def read(path: Path): Bytes =
    val in = Files.newInputStream(path)
    val bytes =
      try in.readNBytes(4194305)
      finally in.close()
    assert(bytes.nonEmpty && bytes.length <= 4194304)
    Bytes.fromArray(bytes)
  private def tuple(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
    assertEquals(a.contextId, b.contextId)
    assertEquals(a.id, b.id)
    assertEquals(a.certificates.state.id, b.certificates.state.id)
    assertEquals(a.certificates.state.tip, b.certificates.state.tip)
    assertEquals(a.certificates.state.counters, b.certificates.state.counters)
    assertEquals(a.nonces.id, b.nonces.id)
    assertEquals(a.nonces.fields, b.nonces.fields)
    def eligible(s: CoherentSequence.State) = s.eligibility.map(e =>
      (
        e.contextId,
        e.headers.map(h => (h.headerHash, h.leaderValue, h.stake.numerator, h.stake.denominator))
      )
    )
    assertEquals(eligible(a), eligible(b))
    assertEquals(a.ledger.id, b.ledger.id)
    assertEquals(a.ledger.checkpointId, b.ledger.checkpointId)
    assertEquals(a.ledger.environment.id, b.ledger.environment.id)
    assertEquals(a.ledger.outputMap, b.ledger.outputMap)
    assertEquals(a.ledger.fees, b.ledger.fees)
    assertEquals(a.ledger.slot, b.ledger.slot)
    assertEquals(a.acquisition.originals, b.acquisition.originals)
    assertEquals(a.acquisition.anchor, b.acquisition.anchor)
    assertEquals(a.compactedBlocks, b.compactedBlocks)
    assertEquals(a.derivedAnchorId, b.derivedAnchorId)
    assertEquals(a.scopedAppliedTip, b.scopedAppliedTip)
    assertEquals(a.depth, b.depth)
    assert(a.trustedLocalPrefix)
    assert(!a.fullLedgerValidated && !a.consensusValidated)

  test("untrusted decoding cannot manufacture authority, publication or runtime capabilities") {
    assert(compileErrors("new lab.LocalDerivedCheckpoint.AcceptedAuthority(null)").nonEmpty)
    assert(compileErrors("new lab.LocalDerivedCheckpoint.Publication(null, null)").nonEmpty)
    assert(compileErrors("new lab.LocalDerivedCheckpoint.AuthorizedLocalImage(null)").nonEmpty)
    assert(compileErrors("new lab.CoherentSequence.OwnedLocalExport(null, null, null, 1)").nonEmpty)
    assert(Local.decode(Bytes.empty).isLeft)
    assert(Local.decode(Bytes(Vector.fill(64)(0.toByte))).isLeft)
  }

  sys.env.get("COHERENT_WINDOW_EVIDENCE").foreach { location =>
    val directory = Path.of(location)
    def context = get(SequenceInput.load(directory))
    def blocks =
      val os =
        get(CoherentSequenceCommand.captures(read(directory.resolve("scala-sequence-capture.md"))))
      assert(os.size >= 4, "four linked signed originals required for local recovery tests")
      os.map(o => get(SequenceInput.block(o)))
    def publish(runtime: CoherentSequence.Runtime[IO], block: SequenceInput.Block) =
      runtime.prepare(block).map(get(_)).flatMap(runtime.publish).map(get(_))
    def fixture =
      val c = context; val bs = blocks
      for
        runtime <- CoherentSequence.create[IO](c, 3).map(get(_))
        seed <- runtime.snapshot
        first <- publish(runtime, bs(0))
        _ <- publish(runtime, bs(1))
        _ <- publish(runtime, bs(2))
        before <- runtime.snapshot
        compact <- runtime.advanceAnchor(before.fence, first.state.acquisition.tip).map(get(_))
        publication <- runtime.exportLocalCheckpoint(hash(11), hash(12), 7).map(get(_))
      yield (c, bs, runtime, seed, compact, publication)

    test(
      "accepted prefix plus checked suffix restores two receipts with fresh ownership and repeated compaction"
    ) {
      fixture
        .flatMap { (c, bs, original, seed, compact, publication) =>
          for
            recovered <- restore(publication.bytes, c.id)
            current <- recovered.snapshot
            foreign <- recovered.rollbackTo(compact.fence, current.state.acquisition.anchor)
            pending <- original.prepare(bs(3)).map(get(_))
            foreignCandidate <- recovered.publish(pending)
            anchor <- recovered
              .rollbackTo(current.fence, current.state.acquisition.anchor)
              .map(get(_))
            below <- recovered.rollbackTo(anchor.fence, seed.state.acquisition.tip)
            second <- publish(recovered, bs(1))
            third <- publish(recovered, bs(2))
            tip <- recovered.snapshot
            next <- recovered.advanceAnchor(tip.fence, second.state.acquisition.tip).map(get(_))
            saved <- recovered.exportLocalCheckpoint(hash(11), hash(12), 8).map(get(_))
            again <- restore(saved.bytes, c.id)
            againTip <- again.snapshot
            againAnchor <- again
              .rollbackTo(againTip.fence, againTip.state.acquisition.anchor)
              .map(get(_))
            finalThird <- publish(again, bs(2))
          yield
            tuple(current.state, compact.state)
            assertEquals(current.state.revision, compact.state.revision)
            assert(current.state.eligibility.get.freshlyVerified)
            assertEquals(foreign, Left(CoherentSequence.Failure.ForeignFence))
            assertEquals(foreignCandidate, Left(CoherentSequence.Failure.ForeignCandidate))
            assertEquals(anchor.state.revision, BigInt(5))
            assert(anchor.state.eligibility.get.historicallyTrusted)
            assert(!anchor.state.eligibility.get.freshlyVerified)
            assertEquals(anchor.state.acquisition.size, 0)
            assertEquals(below, Left(CoherentSequence.Failure.OutsideRetainedWindow))
            tuple(third.state, compact.state)
            assertEquals(third.state.revision, BigInt(7))
            tuple(againTip.state, next.state)
            assertEquals(againAnchor.state.revision, BigInt(8))
            tuple(finalThird.state, next.state)
            assertEquals(finalThird.state.revision, BigInt(9))
        }
        .unsafeToFuture()
    }

    test(
      "empty suffix preserves checked anchor, historical eligibility and exact private ledger head"
    ) {
      fixture
        .flatMap { (c, bs, runtime, _, _, _) =>
          for
            before <- runtime.snapshot
            compact <- runtime.advanceAnchor(before.fence, before.state.acquisition.tip).map(get(_))
            publication <- runtime.exportLocalCheckpoint(hash(11), hash(12), 8).map(get(_))
            recovered <- restore(publication.bytes, c.id)
            current <- recovered.snapshot
            republished <- recovered.exportLocalCheckpoint(hash(11), hash(13), 9).map(get(_))
            fourth <- publish(recovered, bs(3))
          yield
            tuple(current.state, compact.state)
            assertEquals(current.state.revision, BigInt(3))
            assertEquals(current.state.acquisition.size, 0)
            assertEquals(current.state.depth, BigInt(3))
            assert(current.state.scopedAppliedTip.nonEmpty)
            assert(current.state.eligibility.get.historicallyTrusted)
            val a = get(Local.decode(publication.bytes)).image.anchor
            val b = get(Local.decode(republished.bytes)).image.anchor
            assertEquals(a, b)
            assertEquals(fourth.state.revision, BigInt(4))
            assert(fourth.state.eligibility.get.freshlyVerified && fourth.state.trustedLocalPrefix)
            assert(ValidatedCheckpoint.encode(c, current, hash(11), 0, 3).isLeft)
        }
        .unsafeToFuture()
    }

    test("missing and wrong authority reject exact images and every altered claim") {
      fixture
        .flatMap { (c, _, _, _, _, publication) =>
          val envelope = get(Local.decode(publication.bytes)); val i = envelope.image
          val changes = Vector(
            i.copy(storeId = hash(30)),
            i.copy(contextId = hash(31)),
            i.copy(sessionId = hash(32)),
            i.copy(generation = i.generation + 1),
            i.copy(provenance = hash(33)),
            i.copy(finalId = hash(34)),
            i.copy(anchor = i.anchor.copy(ledger = i.anchor.ledger.copy(head = hash(35)))),
            i.copy(anchor =
              i.anchor.copy(certificate = i.anchor.certificate.copy(counters = Map.empty))
            )
          )
          for
            authority <- accept(publication.bytes)
            missing <- Local.recover[IO](publication.bytes, c.id, None, 5.seconds)
            rejected <- Local.accept[IO](
              envelope,
              new TestOnlyController(
                envelope.claim.copy(token = envelope.claim.token.copy(sessionId = hash(99)))
              )
            )
            wrongContext <- Local.recover[IO](
              publication.bytes,
              hash(98),
              Some(authority),
              5.seconds
            )
            changed <- changes.traverse { change =>
              Local.recover[IO](
                get(Local.encodeUntrusted(change)),
                c.id,
                Some(authority),
                5.seconds
              )
            }
          yield
            assert(missing.isLeft && rejected.isLeft && wrongContext.isLeft)
            assert(changed.forall(_.isLeft))
        }
        .unsafeToFuture()
    }

    test(
      "explicit test authority cannot bypass structural binding or ordinary suffix verification"
    ) {
      fixture
        .flatMap { (c, _, _, _, _, publication) =>
          val i = get(Local.decode(publication.bytes)).image; val a = i.anchor
          val changes = Vector(
            i.copy(anchor = a.copy(ledger = a.ledger.copy(fees = a.ledger.fees + 1))),
            i.copy(anchor = a.copy(ledger = a.ledger.copy(checkpointId = hash(31)))),
            i.copy(anchor = a.copy(ledger = a.ledger.copy(environmentId = hash(32)))),
            i.copy(anchor = a.copy(nonce = a.nonce.copy(certificateStateId = hash(33)))),
            i.copy(anchor = a.copy(nonce = a.nonce.copy(id = hash(34)))),
            i.copy(anchor = a.copy(nonce = a.nonce.copy(lastSlot = a.nonce.lastSlot + 1))),
            i.copy(anchor = a.copy(certificate = a.certificate.copy(contextId = hash(35)))),
            i.copy(anchor =
              a.copy(certificate =
                a.certificate
                  .copy(tip = a.certificate.tip.copy(blockNo = a.certificate.tip.blockNo + 1))
              )
            ),
            i.copy(anchor = a.copy(eligibility = a.eligibility.copy(headerHash = hash(36)))),
            i.copy(anchor = a.copy(eligibility = a.eligibility.copy(denominator = 0))),
            i.copy(provenance = hash(37)),
            i.copy(finalId = hash(38)),
            i.copy(finalPoint = i.finalPoint.copy(hash = hash(39))),
            i.copy(originals = Vector(i.originals(1), i.originals(1)))
          )
          changes
            .traverse { changed =>
              val raw = get(Local.encodeUntrusted(changed))
              accept(raw).flatMap(a => Local.recover[IO](raw, c.id, Some(a), 5.seconds))
            }
            .map(results => assert(results.forall(_.isLeft)))
        }
        .unsafeToFuture()
    }

    test(
      "revision offset preserves semantic identity and regenerates bounded receipts up to ceiling"
    ) {
      fixture
        .flatMap { (c, _, _, _, compact, publication) =>
          val i = get(Local.decode(publication.bytes)).image
          assert(Local.encodeUntrusted(i.copy(revision = 2)).isLeft)
          assert(Local.encodeUntrusted(i.copy(revision = 4)).isLeft)
          assert(Local.encodeUntrusted(i.copy(compactedBlocks = 0)).isLeft)
          assert(Local.encodeUntrusted(i.copy(capacity = 9)).isLeft)
          assert(Local.encodeUntrusted(i.copy(revision = Ledger.MaxRevision + 1)).isLeft)
          for
            offset <- restore(get(Local.encodeUntrusted(i.copy(revision = 5))), c.id)
            current <- offset.snapshot
            undone <- offset.rollbackTo(current.fence, current.state.acquisition.anchor).map(get(_))
            ceiling <- restore(
              get(Local.encodeUntrusted(i.copy(revision = Ledger.MaxRevision))),
              c.id
            )
            max <- ceiling.snapshot
            rejected <- ceiling.rollbackTo(max.fence, max.state.acquisition.anchor)
          yield
            tuple(current.state, compact.state)
            assertEquals(current.state.revision, BigInt(5))
            assertEquals(undone.state.revision, BigInt(7))
            assertEquals(max.state.revision, Ledger.MaxRevision)
            assertEquals(rejected, Left(CoherentSequence.Failure.RevisionExhausted))
        }
        .unsafeToFuture()
    }

    test("decode bounds, checksum and cancellation never export partial authority or runtime") {
      fixture
        .flatMap { (c, _, _, _, _, publication) =>
          val raw = publication.bytes
          assert(Local.decode(Bytes(raw.value.dropRight(1))).isLeft)
          assert(Local.decode(Bytes(raw.value :+ 0.toByte)).isLeft)
          assert(Local.decode(Bytes(raw.value.updated(0, (raw.value(0) ^ 1).toByte))).isLeft)
          for
            authority <- accept(raw)
            ready <- Deferred[IO, Unit]
            fiber <- Local
              .recoverObserved[IO](
                raw,
                c.id,
                Some(authority),
                5.seconds,
                stage => if stage == "prepare" then ready.complete(()).void *> IO.never else IO.unit
              )
              .start
            _ <- ready.get; _ <- fiber.cancel
            outcome <- fiber.join
            deadline <- Local.recoverObserved[IO](
              raw,
              c.id,
              Some(authority),
              20.millis,
              stage => if stage == "trusted-anchor" then IO.never else IO.unit
            )
          yield
            assert(outcome.isCanceled)
            assertEquals(deadline, Left("recovery deadline"))
        }
        .unsafeToFuture()
    }

    test(
      "recomputed checksums do not bypass canonical integer, count, nonce tag or trailing guards"
    ) {
      fixture
        .map { (_, _, _, _, _, publication) =>
          val payload = publication.bytes.value.dropRight(32).toArray
          val b = java.nio.ByteBuffer.wrap(payload)
          def skip(): Unit =
            val n = b.getInt(); b.position(b.position() + n): Unit
          (0 until 6).foreach(_ => skip()) // fixed format/profile labels
          (0 until 3).foreach(_ => skip()) // store/context/session
          b.getLong()
          val capacityOffset = b.position(); b.getInt()
          val depthOffset = b.position() + 4; skip()
          skip(); skip(); skip() // provenance, revision, final ID
          skip(); skip(); skip() // final point
          skip() // manifest
          SequenceInput.sources.foreach(_ => skip())
          val anchorLength = b.getInt(); val anchorStart = b.position()
          (0 until 6).foreach(_ => skip()) // anchor ID, cert context/id, point hash/slot/blockNo
          val counterCountOffset = b.position(); val counters = b.getInt()
          (0 until counters).foreach { _ =>
            skip(); skip()
          }
          (0 until 4).foreach(_ => skip()) // nonce ID, context, certificate, slot
          val nonceTagOffset = b.position()
          val originalCountOffset = anchorStart + anchorLength
          def withChecksum(bytes: Array[Byte]): Bytes =
            val body = Bytes.fromArray(bytes)
            Bytes(body.value ++ ClusterHeaderObservation.sha256(body).value)
          def malformedInteger(offset: Int, value: Int): Bytes =
            val bytes = payload.clone(); java.nio.ByteBuffer.wrap(bytes).putInt(offset, value)
            withChecksum(bytes)
          def malformedByte(offset: Int, value: Int): Bytes =
            val bytes = payload.clone(); bytes(offset) = value.toByte; withChecksum(bytes)
          Vector(
            malformedInteger(capacityOffset, 9),
            malformedInteger(counterCountOffset, 10001),
            malformedInteger(originalCountOffset, 9),
            malformedByte(depthOffset, '+'),
            malformedByte(nonceTagOffset, 2),
            withChecksum(payload :+ 0.toByte)
          ).foreach(raw => assert(Local.decode(raw).isLeft))
        }
        .unsafeToFuture()
    }

    test("v2 rejects un-compacted export and v1 cannot interpret the new format") {
      val c = context
      (for
        runtime <- CoherentSequence.create[IO](c).map(get(_))
        refused <- runtime.exportLocalCheckpoint(hash(11), hash(12), 0)
        built <- fixture
      yield
        assert(refused.isLeft)
        val p = built._6
        val t = p.claim.token
        assert(
          ValidatedCheckpoint
            .decode(
              p.bytes,
              c.id,
              ValidatedCheckpoint.Token(t.storeId, t.contextId, t.generation, t.digest)
            )
            .isLeft
        )
      ).unsafeToFuture()
    }
  }
