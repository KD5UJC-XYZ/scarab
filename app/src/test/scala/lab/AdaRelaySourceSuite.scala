// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.{RelayLimits, TxSubmission2}
import lab.submission.SignedTransaction
import scala.concurrent.duration.*

class AdaRelaySourceSuite extends munit.FunSuite:
  private def transaction(body: Int, witness: Int = 0): SignedTransaction =
    val raw = Bytes.fromHex(f"84a10018$body%02xa10018$witness%02xf5f6").toOption.get
    SignedTransaction.checked(raw).toOption.get

  private def fixture(values: Vector[SignedTransaction]) = for
    entries <- Resource.eval(Ref.of[IO, Vector[SignedTransaction]](values))
    gate <- Resource.eval(Semaphore[IO](1))
    selection = new AdaRelaySource.Selection[IO]:
      def withEligible[A](take: Vector[SignedTransaction] => IO[A]): IO[A] =
        gate.permit.use(_ => entries.get.flatMap(take))
    source <- AdaRelaySource.resource(selection)
  yield (source, entries, gate)

  test("leases retain exact witness bytes across same-ID replacement and eligibility movement") {
    val first = transaction(1)
    val replacement = transaction(1, 1)
    fixture(Vector(first))
      .use { case (source, entries, gate) =>
        source.acquireBatch(RelayLimits()).use { old =>
          gate.permit.use(_ => entries.set(Vector(replacement))) *>
            source.acquireBatch(RelayLimits()).use { fresh =>
              for
                original <- old.original(first.transactionId)
                changed <- fresh.original(first.transactionId)
                _ = assertEquals(original, Some(first.original))
                _ = assertEquals(changed, Some(replacement.original))
                _ = assertEquals(
                  old.offers.head.advertisedSize,
                  TxSubmission2.advertisedSize(first.byteSize).toOption.get
                )
                _ = assertEquals(old.offers.head.transactionId, first.transactionId)
                _ <- gate.permit.use(_ => entries.set(Vector.empty))
                stillPinned <- old.original(first.transactionId)
                _ = assertEquals(stillPinned, Some(first.original))
              yield ()
            }
        } *> source
          .acquireBatch(RelayLimits())
          .use(lease => IO(assertEquals(lease.offers, Vector.empty)))
      }
      .unsafeToFuture()
  }

  test("two global slots reject the third immediately and cancellation releases one slot") {
    fixture(Vector(transaction(1)))
      .use { case (source, _, _) =>
        source.acquireBatch(RelayLimits()).use { _ =>
          for
            started <- Deferred[IO, Unit]
            fiber <- source
              .acquireBatch(RelayLimits())
              .use(_ => started.complete(()).void *> IO.never)
              .start
            _ <- started.get
            excess <- source.acquireBatch(RelayLimits()).use(_ => IO.unit).attempt
            _ = assert(excess.left.toOption.exists {
              case e: AdaRelaySource.Unavailable => e.reason == AdaRelaySource.Reason.Capacity
              case _                             => false
            })
            _ <- fiber.cancel
            _ <- source
              .acquireBatch(RelayLimits())
              .use(lease => IO(assertEquals(lease.offers.size, 1)))
          yield ()
        }
      }
      .unsafeToFuture()
  }

  test("expiry releases forgotten leases and old finalizers cannot release newer slots") {
    TestControl
      .executeEmbed {
        fixture(Vector(transaction(1))).use { case (source, _, _) =>
          for
            first <- source.acquireBatch(RelayLimits(maxLifetime = 1.second)).allocated
            second <- source.acquireBatch(RelayLimits(maxLifetime = 1.second)).allocated
            _ <- IO.sleep(2.seconds)
            expired <- first._1.original(transaction(1).transactionId)
            _ = assertEquals(expired, None)
            _ <- source.acquireBatch(RelayLimits()).use { _ =>
              source.acquireBatch(RelayLimits()).use { _ =>
                first._2 *> second._2 *>
                  source
                    .acquireBatch(RelayLimits())
                    .use(_ => IO.unit)
                    .attempt
                    .map(result => assert(result.isLeft))
              }
            }
          yield ()
        }
      }
      .unsafeToFuture()
  }

  test("unknown IDs and explicitly closed leases never retrieve originals") {
    fixture(Vector(transaction(1)))
      .use { case (source, _, _) =>
        for
          allocated <- source.acquireBatch(RelayLimits()).allocated
          unknown <- allocated._1.original(transaction(2).transactionId)
          _ = assertEquals(unknown, None)
          _ <- allocated._2
          closed <- allocated._1.original(transaction(1).transactionId)
          _ = assertEquals(closed, None)
          _ <- allocated._2
        yield ()
      }
      .unsafeToFuture()
  }

  test("source close clears outstanding originals and rejects new leases") {
    val tx = transaction(1)
    (for
      allocated <- fixture(Vector(tx)).allocated
      source = allocated._1._1
      lease <- source.acquireBatch(RelayLimits()).allocated
      _ <- allocated._2
      original <- lease._1.original(tx.transactionId)
      attempt <- source.acquireBatch(RelayLimits()).use(_ => IO.unit).attempt
      _ = assertEquals(original, None)
      _ = assert(attempt.left.toOption.exists {
        case e: AdaRelaySource.Unavailable => e.reason == AdaRelaySource.Reason.Closed
        case _                             => false
      })
      _ <- lease._2
    yield ()).unsafeToFuture()
  }

  test("selection is bounded by count and bytes without advertising skipped originals") {
    val all = (1 to 12).map(transaction(_)).toVector
    fixture(all)
      .use { case (source, _, _) =>
        source
          .acquireBatch(RelayLimits(maxTransactions = 8, maxOriginalBytes = all.head.byteSize * 3))
          .use { lease =>
            for
              _ <- IO(assertEquals(lease.offers.size, 3))
              excluded <- lease.original(all(3).transactionId)
              _ = assertEquals(excluded, None)
            yield ()
          } *> source
          .acquireBatch(RelayLimits())
          .use(lease => IO(assertEquals(lease.offers.size, 8)))
      }
      .unsafeToFuture()
  }

  test("invalid limits and failed eligibility callbacks do not consume capacity") {
    (for
      fail <- Ref.of[IO, Boolean](true)
      selection = new AdaRelaySource.Selection[IO]:
        def withEligible[A](take: Vector[SignedTransaction] => IO[A]): IO[A] =
          fail.get.flatMap {
            case true  => IO.raiseError(new IllegalStateException("selection failure"))
            case false => take(Vector(transaction(1)))
          }
      _ <- AdaRelaySource.resource(selection).use { source =>
        for
          invalid <- source
            .acquireBatch(RelayLimits(maxLifetime = 31.seconds))
            .use(_ => IO.unit)
            .attempt
          failed <- source.acquireBatch(RelayLimits()).use(_ => IO.unit).attempt
          _ = assert(invalid.isLeft && failed.isLeft)
          _ <- fail.set(false)
          _ <- (source.acquireBatch(RelayLimits()), source.acquireBatch(RelayLimits())).tupled.use(
            _ => IO.unit
          )
        yield ()
      }
    yield ()).unsafeToFuture()
  }

  test("cancellation while awaiting eligibility releases the reserved non-queuing slot") {
    (for
      entered <- Deferred[IO, Unit]
      awaitSelection <- Ref.of[IO, Boolean](true)
      selection = new AdaRelaySource.Selection[IO]:
        def withEligible[A](take: Vector[SignedTransaction] => IO[A]): IO[A] =
          awaitSelection.get.flatMap {
            case true  => entered.complete(()).void *> IO.never
            case false => take(Vector(transaction(1)))
          }
      _ <- AdaRelaySource.resource(selection).use { source =>
        for
          pending <- source.acquireBatch(RelayLimits()).use(_ => IO.unit).start
          _ <- entered.get
          _ <- pending.cancel
          _ <- awaitSelection.set(false)
          _ <- (source.acquireBatch(RelayLimits()), source.acquireBatch(RelayLimits())).tupled.use(
            _ => IO.unit
          )
        yield ()
      }
    yield ()).unsafeToFuture()
  }

  private def padded(body: Int): SignedTransaction =
    SignedTransaction
      .checked(Bytes.fromHex(f"84a10018$body%02xa1005840" + "00" * 64 + "f5f6").toOption.get)
      .toOption
      .get

  test("checked relay batches preserve stable selection, exact originals and all bounds") {
    val values =
      Vector(transaction(1), padded(2), transaction(3), transaction(4), padded(5), transaction(6))
    for
      count <- 1 to 8
      bytes <- Vector(1, 10, 20, 30, 64, 128, 524288)
    do
      val batch = RelayBatch
        .select(values, RelayLimits(maxTransactions = count, maxOriginalBytes = bytes))
        .toOption
        .get
      val ids = batch.offers.map(_.transactionId)
      assertEquals(batch.originals.keySet, ids.toSet)
      assertEquals(batch.originalBytes, batch.originals.values.map(_.size).sum)
      assert(batch.offers.size <= count && batch.originalBytes <= bytes)
      assertEquals(ids, values.filter(v => ids.contains(v.transactionId)).map(_.transactionId))
      batch.offers.foreach { offer =>
        val source = values.find(_.transactionId == offer.transactionId).get
        assert(batch.originals(offer.transactionId) eq source.original)
        assertEquals(
          offer.advertisedSize,
          TxSubmission2.advertisedSize(source.byteSize).toOption.get
        )
      }
    val skipped = RelayBatch
      .select(values, RelayLimits(maxOriginalBytes = values.head.byteSize * 2))
      .toOption
      .get
    assertEquals(
      skipped.offers.map(_.transactionId),
      Vector(values(0).transactionId, values(2).transactionId)
    )
    val limited = RelayBatch.select(values, RelayLimits(maxTransactions = 1)).toOption.get
    assertEquals(limited.offers.map(_.transactionId), Vector(values.head.transactionId))
  }

  test("relay selection validates the whole domain before count or byte truncation") {
    import RelayBatch.Failure
    val first = transaction(1)
    assertEquals(
      RelayBatch.select(null, RelayLimits()).left.toOption,
      Some(Failure.InvalidEligibleDomain)
    )
    assertEquals(
      RelayBatch.select(Vector(first, null), RelayLimits()).left.toOption,
      Some(Failure.InvalidEligibleDomain)
    )
    assertEquals(
      RelayBatch.select((1 to 65).map(transaction(_)).toVector, RelayLimits()).left.toOption,
      Some(Failure.InvalidEligibleDomain)
    )
    val duplicates = Vector(first, transaction(2), transaction(2, 1))
    assertEquals(
      RelayBatch
        .select(duplicates, RelayLimits(maxTransactions = 1, maxOriginalBytes = 1))
        .left
        .toOption,
      Some(Failure.DuplicateIdentity)
    )
    assertEquals(
      RelayBatch.select(Vector.empty, RelayLimits(maxTransactions = 0)).left.toOption,
      Some(Failure.InvalidLimits)
    )
    assertEquals(RelayBatch.select(Vector.empty, null).left.toOption, Some(Failure.InvalidLimits))
    val empty = RelayBatch.select(Vector.empty, RelayLimits()).toOption.get
    assertEquals((empty.offers.size, empty.originals.size, empty.originalBytes), (0, 0, 0))
  }

  test("relay batches cannot be constructed, copied or mutated outside checked selection") {
    assertEquals(
      compileErrors("""lab.RelayBatch.select(Vector.empty, lab.network.RelayLimits())"""),
      ""
    )
    assert(compileErrors("""new lab.RelayBatch.Batch(Vector.empty, Map.empty, 0)""").nonEmpty)
    assert(
      compileErrors(
        """lab.RelayBatch.select(Vector.empty, lab.network.RelayLimits()).toOption.get.copy(originalBytes = 1)"""
      ).nonEmpty
    )
    assert(
      compileErrors(
        """lab.RelayBatch.select(Vector.empty, lab.network.RelayLimits()).toOption.get.originalBytes = 1"""
      ).nonEmpty
    )
  }

  private def activeOwner =
    SubmissionOwner.resource[IO](EphemeralStreamingFixture.runtime).evalTap { owner =>
      owner.attach(new lab.submission.AdmissionStateObserver[IO]:
        def changed(change: lab.submission.AdmissionStateChange) = IO.unit
        def closed = IO.unit)
    }

  private def ownerSelection(
      owner: SubmissionOwner[IO],
      values: IO[Vector[SignedTransaction]],
      before: IO[Unit] = IO.unit
  ): AdaRelaySource.Selection[IO] = new AdaRelaySource.Selection[IO]:
    def withEligible[A](take: Vector[SignedTransaction] => IO[A]): IO[A] =
      owner.current
        .flatMap(view => owner.withCurrent(view.pin)(before *> values.flatMap(take)))
        .flatMap {
          case Right(value) => IO.pure(value)
          case Left(_) =>
            IO.raiseError(new IllegalStateException("unexpected fixture pin movement"))
        }

  test("invalid relay selection leaves the real owner usable and releases reserved capacity") {
    activeOwner
      .use { owner =>
        for
          values <- Ref.of[IO, Vector[SignedTransaction]](Vector(transaction(1), transaction(1, 1)))
          _ <- AdaRelaySource.resource(ownerSelection(owner, values.get)).use { source =>
            for
              failed <- source
                .acquireBatch(RelayLimits(maxTransactions = 1))
                .use(_ => IO.unit)
                .attempt
              _ = assert(
                failed.left.toOption.exists(e =>
                  e.isInstanceOf[
                    IllegalStateException
                  ] && e.getMessage == "duplicate eligible transaction identity"
                )
              )
              _ <- values.set(null)
              invalid <- source.acquireBatch(RelayLimits()).use(_ => IO.unit).attempt
              _ = assert(
                invalid.left.toOption.exists(e =>
                  e.isInstanceOf[
                    IllegalStateException
                  ] && e.getMessage == "bounded eligible pool required"
                )
              )
              view <- owner.current
              commit <- owner.withCurrent(view.pin)(IO.pure(7))
              _ = assertEquals(commit, Right(7))
              _ <- values.set(Vector(transaction(1)))
              _ <- (source.acquireBatch(RelayLimits()), source.acquireBatch(RelayLimits())).tupled
                .use { (a, b) =>
                  IO(assertEquals((a.offers.size, b.offers.size), (1, 1)))
                }
            yield ()
          }
        yield ()
      }
      .unsafeToFuture()
  }

  test(
    "relay close during selection returns Closed after the real owner callback without poisoning it"
  ) {
    activeOwner
      .use { owner =>
        for
          entered <- Deferred[IO, Unit]
          resume <- Deferred[IO, Unit]
          selection = ownerSelection(
            owner,
            IO.pure(Vector.empty),
            entered.complete(()).void *> resume.get
          )
          _ <- Resource.make(AdaRelaySource.resource(selection).allocated)(_._2).use {
            (source, close) =>
              Resource
                .make(source.acquireBatch(RelayLimits()).use(_ => IO.unit).attempt.start)(fiber =>
                  resume.complete(()).void *> fiber.cancel
                )
                .use { fiber =>
                  for
                    _ <- entered.get
                    _ <- close
                    _ <- resume.complete(())
                    result <- fiber.joinWithNever
                    _ = assert(result.left.toOption.exists {
                      case e: AdaRelaySource.Unavailable =>
                        e.reason == AdaRelaySource.Reason.Closed && e.getMessage == "relay lease unavailable: Closed"
                      case _ => false
                    })
                    view <- owner.current
                    commit <- owner.withCurrent(view.pin)(IO.pure(11))
                    _ = assertEquals(commit, Right(11))
                  yield ()
                }
          }
        yield ()
      }
      .unsafeToFuture()
  }
