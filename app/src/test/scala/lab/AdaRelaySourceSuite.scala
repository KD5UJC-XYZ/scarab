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
