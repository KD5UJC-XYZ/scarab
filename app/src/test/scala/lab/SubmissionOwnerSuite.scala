// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.ChainSync
import lab.submission.*

class SubmissionOwnerSuite extends munit.FunSuite:
  private val F = EphemeralStreamingFixture
  private def get[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)
  private def observer(
      changedF: AdmissionStateChange => IO[Unit] = _ => IO.unit,
      closedF: IO[Unit] = IO.unit
  ): AdmissionStateObserver[IO] = new AdmissionStateObserver[IO]:
    def changed(value: AdmissionStateChange) = changedF(value)
    def closed = closedF
  private def unavailable[A](
      result: Either[Throwable, A],
      reason: AdmissionState.UnavailableReason
  ): Unit =
    result match
      case Left(error: AdmissionState.Unavailable) => assertEquals(error.reason, reason)
      case other                                   => fail(s"expected $reason, got $other")
  private def prepare(owner: SubmissionOwner[IO], index: Int): IO[CoherentSequence.Candidate] =
    owner.snapshot.flatMap(s => owner.prepareSyntheticBlock(s.fence, F.blocks(index))).map(get(_))
  private def publish(owner: SubmissionOwner[IO], index: Int): IO[CoherentSequence.Applied] =
    prepare(owner, index).flatMap(owner.publish).map(get(_))
  private def point(index: Int): ChainSync.Point =
    ChainSync.Point.Block(
      get(ChainSync.UInt64.from(F.blocks(index).header.slot)),
      F.blocks(index).header.hash
    )

  test(
    "initializing exposes a view but refuses mutation and admission until one observer attaches"
  ) {
    SubmissionOwner
      .resource[IO](F.runtime)
      .use { owner =>
        for
          before <- owner.current
          snap <- owner.snapshot
          called <- Ref.of[IO, Boolean](false)
          admission <- owner.withCurrent(before.pin)(called.set(true)).attempt
          mutation <- owner.rollbackTo(snap.fence, snap.state.acquisition.tip).attempt
          _ = unavailable(admission, AdmissionState.UnavailableReason.Initializing)
          _ = unavailable(mutation, AdmissionState.UnavailableReason.Initializing)
          after <- owner.snapshot
          didCall <- called.get
          _ = assert(!didCall)
          _ = assertEquals(after.state.id, snap.state.id)
          _ <- owner.attach(observer())
          accepted <- owner.withCurrent(before.pin)(IO.pure(7))
          _ = assertEquals(accepted, Right(7))
          duplicate <- owner.attach(observer()).attempt
          _ = assert(duplicate.isLeft)
        yield ()
      }
      .unsafeToFuture()
  }

  test("complete pin mismatch retries without invoking the commit callback") {
    SubmissionOwner
      .resource[IO](F.runtime)
      .use { owner =>
        for
          _ <- owner.attach(observer())
          view <- owner.current
          pin = view.pin
          variants = Vector(
            get(
              StatePin.checked(
                Bytes(Vector.fill(32)(91.toByte)),
                pin.generation,
                pin.point,
                pin.coherentStateId,
                pin.ledgerStateId,
                pin.environmentId,
                pin.validationSlot,
                pin.profileId
              )
            ),
            get(
              StatePin.checked(
                pin.ownerId,
                pin.generation + 1,
                pin.point,
                pin.coherentStateId,
                pin.ledgerStateId,
                pin.environmentId,
                pin.validationSlot,
                pin.profileId
              )
            ),
            get(
              StatePin.checked(
                pin.ownerId,
                pin.generation,
                pin.point.copy(blockNo = pin.point.blockNo + 1),
                pin.coherentStateId,
                pin.ledgerStateId,
                pin.environmentId,
                pin.validationSlot,
                pin.profileId
              )
            ),
            get(
              StatePin.checked(
                pin.ownerId,
                pin.generation,
                pin.point,
                pin.coherentStateId,
                pin.ledgerStateId,
                Bytes(Vector.fill(32)(92.toByte)),
                pin.validationSlot,
                pin.profileId
              )
            )
          )
          called <- Ref.of[IO, Int](0)
          results <- variants.traverse(p => owner.withCurrent(p)(called.update(_ + 1)))
          count <- called.get
          _ = assertEquals(count, 0)
          _ = results.foreach(r => assertEquals(r, Left(pin)))
        yield ()
      }
      .unsafeToFuture()
  }

  test("ephemeral driver publication notifies exact generation point and represented inclusions") {
    SubmissionOwner
      .resource[IO](F.runtime)
      .use { owner =>
        for
          changes <- Ref.of[IO, Vector[AdmissionStateChange]](Vector.empty)
          _ <- owner.attach(observer(c => changes.update(_ :+ c)))
          before <- owner.current
          queue <- Ref.of[IO, Vector[EphemeralStreaming.Event]](
            F.blocks.take(3).map(EphemeralStreaming.Event.Block.apply)
          )
          report <- EphemeralStreaming.run(owner, EphemeralStreaming.Limits())(
            queue.modify(xs => (xs.drop(1), xs.headOption))
          )
          records <- changes.get
          current <- owner.current
          _ = assertEquals(report.stop, EphemeralStreaming.Stop.End)
          _ = assertEquals(report.counters.acceptedBlocks, 3L)
          _ = assertEquals(current.pin.generation, before.pin.generation + 3)
          _ = assertEquals(
            records.map(_.view.pin.generation),
            Vector(BigInt(1), BigInt(2), BigInt(3))
          )
          _ = records.zipWithIndex.foreach { (change, index) =>
            assertEquals(change.kind, StateChangeKind.Published)
            assertEquals(change.view.pin.point.hash, F.blocks(index).header.hash)
            assertEquals(change.view.pin.point.blockNo, F.blocks(index).header.blockNo)
            val txs = F.blocks(index).transactionMemos.map(b => get(SignedTransaction.checked(b)))
            assertEquals(
              change.included,
              txs.map(t =>
                IncludedTransaction(
                  t.transactionId,
                  Some(t.originalBody),
                  Some(t.originalWitnesses)
                )
              )
            )
          }
          stale <- owner.withCurrent(before.pin)(
            IO.raiseError[Unit](new RuntimeException("must not run"))
          )
          _ = assertEquals(stale, Left(current.pin))
        yield ()
      }
      .unsafeToFuture()
  }

  test(
    "anchor movement and no-op rollback advance generation even when ledger content stays equal"
  ) {
    SubmissionOwner
      .resource[IO](F.runtime)
      .use { owner =>
        for
          changes <- Ref.of[IO, Vector[AdmissionStateChange]](Vector.empty)
          _ <- owner.attach(observer(c => changes.update(_ :+ c)))
          _ <- publish(owner, 0)
          _ <- publish(owner, 1)
          before <- owner.current
          snap <- owner.snapshot
          moved <- owner.advanceAnchor(snap.fence, point(0)).map(get(_))
          afterAnchor <- owner.current
          _ = assertEquals(afterAnchor.ledger.id, before.ledger.id)
          _ = assertEquals(afterAnchor.pin.generation, before.pin.generation + 1)
          _ <- owner.rollbackTo(moved.fence, moved.state.acquisition.tip).map(get(_))
          afterRollback <- owner.current
          _ = assertEquals(afterRollback.ledger.id, afterAnchor.ledger.id)
          _ = assertEquals(afterRollback.pin.generation, afterAnchor.pin.generation + 1)
          records <- changes.get
          _ = assertEquals(
            records.takeRight(2).map(_.kind),
            Vector(StateChangeKind.AnchorMoved, StateChangeKind.RolledBack)
          )
          _ = assert(records.takeRight(2).forall(_.included.isEmpty))
        yield ()
      }
      .unsafeToFuture()
  }

  test("rejected stale candidate does not increment generation or notify observer") {
    SubmissionOwner
      .resource[IO](F.runtime)
      .use { owner =>
        for
          changed <- Ref.of[IO, Int](0)
          _ <- owner.attach(observer(_ => changed.update(_ + 1)))
          candidate <- prepare(owner, 0)
          _ <- owner.publish(candidate).map(get(_))
          before <- owner.current
          rejected <- owner.publish(candidate)
          after <- owner.current
          count <- changed.get
          _ = assert(rejected.isLeft)
          _ = assertEquals(after.pin, before.pin)
          _ = assertEquals(count, 1)
        yield ()
      }
      .unsafeToFuture()
  }

  test("observer notification and admission commit cannot interleave across publication") {
    SubmissionOwner
      .resource[IO](F.runtime)
      .use { owner =>
        for
          entered <- Deferred[IO, StatePin]
          release <- Deferred[IO, Unit]
          order <- Ref.of[IO, Vector[String]](Vector.empty)
          _ <- owner.attach(
            observer(c =>
              order.update(_ :+ "observer-start") *>
                entered.complete(c.view.pin).void *> release.get *> order.update(
                  _ :+ "observer-end"
                )
            )
          )
          publishing <- publish(owner, 0).start
          nextPin <- entered.get
          attempted <- Deferred[IO, Unit]
          finished <- Deferred[IO, Either[StatePin, Unit]]
          committing <- (attempted.complete(()).void *> owner
            .withCurrent(nextPin)(order.update(_ :+ "commit"))
            .flatTap(r => finished.complete(r))).start
          _ <- attempted.get
          early <- finished.tryGet
          _ = assertEquals(early, None)
          _ <- release.complete(())
          _ <- publishing.joinWithNever
          result <- committing.joinWithNever
          events <- order.get
          _ = assertEquals(result, Right(()))
          _ = assertEquals(events, Vector("observer-start", "observer-end", "commit"))
        yield ()
      }
      .unsafeToFuture()
  }

  test("canceling a gate waiter never invokes its commit or poisons the active owner") {
    SubmissionOwner
      .resource[IO](F.runtime)
      .use { owner =>
        for
          _ <- owner.attach(observer())
          pin <- owner.current.map(_.pin)
          entered <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          first <- owner.withCurrent(pin)(entered.complete(()).void *> release.get).start
          _ <- entered.get
          attempted <- Deferred[IO, Unit]
          called <- Ref.of[IO, Boolean](false)
          waiter <- (attempted.complete(()).void *> owner.withCurrent(pin)(called.set(true))).start
          _ <- attempted.get
          _ <- waiter.cancel
          result <- waiter.join
          _ = assert(result.isCanceled)
          _ <- release.complete(())
          _ <- first.joinWithNever
          didCall <- called.get
          _ = assert(!didCall)
          healthy <- owner.withCurrent(pin)(IO.pure(1))
          _ = assertEquals(healthy, Right(1))
        yield ()
      }
      .unsafeToFuture()
  }

  test("cancellation after admission linearization completes the masked commit") {
    SubmissionOwner
      .resource[IO](F.runtime)
      .use { owner =>
        for
          _ <- owner.attach(observer())
          pin <- owner.current.map(_.pin)
          entered <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          committed <- Ref.of[IO, Boolean](false)
          operation <- owner
            .withCurrent(pin)(entered.complete(()).void *> release.get *> committed.set(true))
            .start
          _ <- entered.get
          cancelStarted <- Deferred[IO, Unit]
          cancelling <- (cancelStarted.complete(()).void *> operation.cancel).start
          _ <- cancelStarted.get
          _ <- IO.cede
          _ <- release.complete(())
          _ <- cancelling.joinWithNever
          _ <- operation.join
          done <- committed.get
          current <- owner.current
          _ = assert(done)
          _ = assertEquals(current.pin, pin)
        yield ()
      }
      .unsafeToFuture()
  }

  test(
    "observer and admission callback errors poison admission but retain once-only observer cleanup"
  ) {
    Vector(false, true)
      .traverse_ { failObserver =>
        SubmissionOwner.resource[IO](F.runtime).use { owner =>
          for
            closed <- Ref.of[IO, Int](0)
            _ <- owner.attach(
              observer(
                _ =>
                  if failObserver then IO.raiseError(new RuntimeException("observer failure"))
                  else IO.unit,
                closed.update(_ + 1)
              )
            )
            before <- owner.current
            failure <- (if failObserver then publish(owner, 0).void
                        else
                          owner
                            .withCurrent(before.pin)(
                              IO.raiseError[Unit](new RuntimeException("commit failure"))
                            )
                            .void
            ).attempt
            _ = assert(failure.isLeft)
            poisoned <- owner.current.attempt
            _ = unavailable(poisoned, AdmissionState.UnavailableReason.Poisoned)
            blocked <- owner.withCurrent(before.pin)(IO.unit).attempt
            _ = unavailable(blocked, AdmissionState.UnavailableReason.Poisoned)
            snapshot <- owner.snapshot
            _ = assertEquals(
              snapshot.state.certificates.state.tip.hash,
              if failObserver then F.blocks.head.header.hash else before.pin.point.hash
            )
            _ <- owner.close *> owner.close
            count <- closed.get
            _ = assertEquals(count, 1)
            after <- owner.current.attempt
            _ = unavailable(after, AdmissionState.UnavailableReason.Closed)
          yield ()
        }
      }
      .unsafeToFuture()
  }

  test("close remains idempotent even when observer cleanup raises") {
    SubmissionOwner
      .resource[IO](F.runtime)
      .use { owner =>
        for
          calls <- Ref.of[IO, Int](0)
          _ <- owner.attach(
            observer(closedF =
              calls.update(_ + 1) *> IO.raiseError(new RuntimeException("close failure"))
            )
          )
          first <- owner.close.attempt
          second <- owner.close.attempt
          count <- calls.get
          _ = assert(first.isLeft && second.isRight)
          _ = assertEquals(count, 1)
          blocked <- owner.current.attempt
          _ = unavailable(blocked, AdmissionState.UnavailableReason.Closed)
        yield ()
      }
      .unsafeToFuture()
  }

  test("last generation increment succeeds then exhaustion refuses mutation before state change") {
    SubmissionOwner
      .resourceAt[IO](F.runtime, StatePin.MaxUInt64 - 1)
      .use { owner =>
        for
          notifications <- Ref.of[IO, Int](0)
          _ <- owner.attach(observer(_ => notifications.update(_ + 1)))
          _ <- publish(owner, 0)
          before <- owner.current
          _ = assertEquals(before.pin.generation, StatePin.MaxUInt64)
          candidate <- prepare(owner, 1)
          refused <- owner.publish(candidate).attempt
          _ = unavailable(refused, AdmissionState.UnavailableReason.GenerationExhausted)
          snapshot <- owner.snapshot
          rollback <- owner.rollbackTo(snapshot.fence, snapshot.state.acquisition.tip).attempt
          _ = unavailable(rollback, AdmissionState.UnavailableReason.GenerationExhausted)
          called <- Ref.of[IO, Boolean](false)
          admission <- owner.withCurrent(before.pin)(called.set(true)).attempt
          _ = unavailable(admission, AdmissionState.UnavailableReason.GenerationExhausted)
          after <- owner.current
          count <- notifications.get
          didCall <- called.get
          _ = assertEquals(after.pin, before.pin)
          _ = assertEquals(count, 1)
          _ = assert(!didCall)
        yield ()
      }
      .unsafeToFuture()
  }

  test("fresh owners distinguish otherwise identical initial ledger views") {
    (SubmissionOwner.resource[IO](F.runtime), SubmissionOwner.resource[IO](F.runtime)).tupled
      .use { (first, second) =>
        for
          a <- first.current
          b <- second.current
          _ = assertEquals(a.pin.point, b.pin.point)
          _ = assertEquals(a.pin.ledgerStateId, b.pin.ledgerStateId)
          _ = assertEquals(a.pin.generation, BigInt(0))
          _ = assertNotEquals(a.pin.ownerId, b.pin.ownerId)
          _ = assertNotEquals(a.pin, b.pin)
        yield ()
      }
      .unsafeToFuture()
  }

  test("opt-in native owner keeps its profile across publication and fences an ADA pin") {
    SubmissionOwner
      .resource[IO](F.runtime, AdmissionProfile.NativeScript)
      .use { owner =>
        for
          _ <- owner.attach(observer())
          before <- owner.current
          p = before.pin
          ada = get(
            StatePin.checked(
              p.ownerId,
              p.generation,
              p.point,
              p.coherentStateId,
              p.ledgerStateId,
              p.environmentId,
              p.validationSlot,
              AdmissionProfile.AdaVkey.id
            )
          )
          called <- Ref.of[IO, Boolean](false)
          stale <- owner.withCurrent(ada)(called.set(true))
          didCall <- called.get
          _ = assertEquals(stale, Left(p))
          _ = assert(!didCall)
          _ = assertEquals(owner.profile, AdmissionProfile.NativeScript)
          _ = assertEquals(p.profileId, AdmissionProfile.NativeScript.id)
          _ <- publish(owner, 0)
          after <- owner.current
          _ = assertEquals(after.pin.profileId, p.profileId)
          _ = assertEquals(after.pin.generation, p.generation + 1)
        yield ()
      }
      .unsafeToFuture()
  }
