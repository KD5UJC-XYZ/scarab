// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import scala.concurrent.duration.*
import lab.cbor.Bytes
import lab.header.PraosCertificateState.Point
import lab.submission.{AdmissionProfile, SignedTransaction, StatePin}
import PlutusMultiEndpointScenarioAdapter.*
import SequentialDevnetRunner.*

class PlutusMultiEndpointScenarioAdapterSuite extends munit.FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).toOption.get
  private def h(n: Int): Bytes = Bytes.fromArray(Array.fill[Byte](32)(n.toByte))
  private val endpoints = Vector(Endpoint("first", 31001, h(1)), Endpoint("second", 31002, h(2)))
  private val originals = Vector(bytes("84a10001a0f5f6"), bytes("84a10002a0f5f6"))
  private val txs = originals.map(raw => SignedTransaction.checked(raw).toOption.get)
  private def pin(e: Endpoint, generation: Int): StatePin = StatePin
    .checked(
      e.ownerId,
      BigInt(generation),
      Point(h(10 + generation), BigInt(10 + generation), BigInt(1 + generation)),
      h(20 + generation),
      h(30 + generation),
      h(40 + generation),
      BigInt(10 + generation),
      AdmissionProfile.PlutusV3.id
    )
    .toOption
    .get
  private val limits = Limits(4, 5.seconds, 20.seconds, 4096, 8192)

  private def factory(
      events: Ref[IO, Vector[String]],
      alter: (Endpoint, Included) => Included = (_, included) => included,
      pause: Option[Deferred[IO, Unit]] = None,
      badInitial: Boolean = false,
      failSecondAcquire: Boolean = false,
      badEnvelope: Boolean = false
  ): Endpoint => Resource[IO, Client] = e =>
    Resource
      .make(
        (if failSecondAcquire && e == endpoints(1) then
           IO.raiseError(new IllegalStateException("acquire"))
         else IO.unit) *>
          events.update(_ :+ s"open:${e.id}") *> Ref.of[IO, Boolean](false)
      )(_ => events.update(_ :+ s"close:${e.id}"))
      .map { done =>
        new Client:
          val endpoint = e
          def state = done.get.map(finished =>
            Snapshot(
              pin(
                if badInitial && e == endpoints(1) then endpoints(0) else e,
                if finished then 1 else 0
              ),
              false
            )
          )
          def submit(raw: Bytes) =
            events.update(_ :+ s"submit:${e.id}:${ClusterHeaderObservation.sha256(raw).hex}") *>
              IO.pure(
                Accepted(
                  txs(endpoints.indexOf(e)).transactionId,
                  if badEnvelope then h(99) else txs(endpoints.indexOf(e)).envelopeSHA256,
                  pin(e, 0)
                )
              )
          def awaitIncluded(id: Bytes) =
            val tx = txs(endpoints.indexOf(e))
            val value = Included(
              id,
              pin(e, 1),
              Publication(
                pin(e, 1),
                id,
                ClusterHeaderObservation.sha256(tx.originalBody),
                ClusterHeaderObservation.sha256(tx.originalWitnesses)
              )
            )
            pause.fold(IO.unit)(signal => signal.complete(()).void *> IO.never[Unit]) *>
              done.set(true) *> IO.pure(alter(e, value))
      }

  test("two independently owned endpoints bind original spans and release in reverse order") {
    (for
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      report <- run(
        Vector(Scenario.MultipleNodes),
        limits,
        owned(endpoints, originals, 4.seconds, factory(events))
      )
      log <- events.get
    yield
      assert(report.allRequestedPassed)
      assertEquals(log.take(2), Vector("open:first", "open:second"))
      assertEquals(
        log.drop(2).take(2),
        endpoints
          .zip(originals)
          .map((e, b) => s"submit:${e.id}:${ClusterHeaderObservation.sha256(b).hex}")
      )
      assertEquals(log.takeRight(2), Vector("close:second", "close:first"))
    ).unsafeToFuture()
  }

  test("duplicate ID, port, owner and unsupported topology reject before resource acquisition") {
    val bad = Vector(
      Vector(endpoints.head),
      Vector(endpoints.head, endpoints(1).copy(id = "first")),
      Vector(endpoints.head, endpoints(1).copy(loopbackPort = 31001)),
      Vector(endpoints.head, endpoints(1).copy(ownerId = h(1)))
    )
    (for
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      results <- bad.traverse(es =>
        owned(es, originals, 4.seconds, factory(events)).use(_ => IO.unit).attempt
      )
      log <- events.get
    yield
      assert(results.forall(_.isLeft))
      assertEquals(log, Vector.empty)
    ).unsafeToFuture()
  }

  test("actual owner mismatch fails capability construction and releases both leases") {
    (for
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- owned(endpoints, originals, 4.seconds, factory(events, badInitial = true))
        .use(_ => IO.unit)
        .attempt
      log <- events.get
    yield
      assert(result.isLeft)
      assertEquals(log, Vector("open:first", "open:second", "close:second", "close:first"))
    ).unsafeToFuture()
  }

  test("partial acquisition failure releases the first lease") {
    (for
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- owned(endpoints, originals, 4.seconds, factory(events, failSecondAcquire = true))
        .use(_ => IO.unit)
        .attempt
      log <- events.get
    yield
      assert(result.isLeft)
      assertEquals(log, Vector("open:first", "close:first"))
    ).unsafeToFuture()
  }

  test(
    "body, witnesses, transaction identity, publication pin and included owner substitution fail"
  ) {
    val mutations: Vector[(Endpoint, Included) => Included] = Vector(
      (_, i) => i.copy(publication = i.publication.copy(bodySHA256 = h(99))),
      (_, i) => i.copy(publication = i.publication.copy(witnessesSHA256 = h(99))),
      (_, i) => i.copy(transactionId = h(99)),
      (e, i) => i.copy(publication = i.publication.copy(pin = pin(e, 0))),
      (_, i) => i.copy(pin = pin(endpoints(1), 1))
    )
    mutations
      .traverse { mutate =>
        for
          events <- Ref.of[IO, Vector[String]](Vector.empty)
          result <- owned(endpoints, originals, 4.seconds, factory(events, alter = mutate)).use {
            adapter =>
              for
                outcome <- adapter.execute(Scenario.MultipleNodes)
                checkpoint <- adapter.observe(Scenario.MultipleNodes).attempt
              yield (outcome, checkpoint)
          }
        yield
          assert(result._1.isLeft)
          assert(result._2.isLeft)
      }
      .void
      .unsafeToFuture()
  }

  test("cancellation while waiting for inclusion releases both resources and never checkpoints") {
    (for
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      waiting <- Deferred[IO, Unit]
      fiber <- run(
        Vector(Scenario.MultipleNodes),
        limits,
        owned(endpoints, originals, 4.seconds, factory(events, pause = Some(waiting)))
      ).start
      _ <- waiting.get
      _ <- fiber.cancel
      log <- events.get
    yield
      assertEquals(log.takeRight(2), Vector("close:second", "close:first"))
      assert(!log.exists(_.startsWith("submit:second")))
    ).unsafeToFuture()
  }

  test("missing inclusion times out; lease cannot submit again after uncertain attempt") {
    TestControl
      .executeEmbed(for
        events <- Ref.of[IO, Vector[String]](Vector.empty)
        waiting <- Deferred[IO, Unit]
        result <- owned(endpoints, originals, 1.second, factory(events, pause = Some(waiting)))
          .use { adapter =>
            for
              first <- adapter.execute(Scenario.MultipleNodes)
              second <- adapter.execute(Scenario.MultipleNodes)
            yield (first, second)
          }
      yield
        assertEquals(result._1, Left(Failure.ActionDeadline))
        assertEquals(result._2, Left(Failure.ActionRejected)))
      .unsafeToFuture()
  }

  test("restart and epoch scenarios remain blocked without submission") {
    (for
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- run(
        Vector(Scenario.RestartAndRejoin, Scenario.FollowAcrossEpochs),
        limits,
        owned(endpoints, originals, 4.seconds, factory(events))
      )
      log <- events.get
    yield
      assert(!result.executedScenariosPassed)
      assert(result.rows.forall(_.verdict.isInstanceOf[Verdict.Blocked]))
      assert(!log.exists(_.startsWith("submit:")))
    ).unsafeToFuture()
  }

  test("same accepted point with newer generation cannot prove inclusion") {
    val mutate: (Endpoint, Included) => Included = (e, i) =>
      val start = pin(e, 0)
      val substituted = StatePin
        .checked(
          e.ownerId,
          BigInt(1),
          start.point,
          h(21),
          h(31),
          h(41),
          start.point.slot,
          AdmissionProfile.PlutusV3.id
        )
        .toOption
        .get
      i.copy(pin = substituted, publication = i.publication.copy(pin = substituted))
    (for
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- owned(endpoints, originals, 4.seconds, factory(events, alter = mutate)).use(
        _.execute(Scenario.MultipleNodes)
      )
    yield assert(result.isLeft)).unsafeToFuture()
  }

  test("accepted envelope substitution fails before any inclusion observation") {
    (for
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- owned(endpoints, originals, 4.seconds, factory(events, badEnvelope = true)).use(
        _.execute(Scenario.MultipleNodes)
      )
      log <- events.get
    yield
      assert(result.isLeft)
      assert(!log.exists(_.startsWith("submit:second")))
    ).unsafeToFuture()
  }

  test("one successful endpoint does not hide second endpoint substituted evidence") {
    (for
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- owned(
        endpoints,
        originals,
        4.seconds,
        factory(
          events,
          alter = (e, i) =>
            if e == endpoints(1) then
              i.copy(publication = i.publication.copy(witnessesSHA256 = h(99)))
            else i
        )
      ).use { adapter =>
        for
          outcome <- adapter.execute(Scenario.MultipleNodes)
          checkpoint <- adapter.observe(Scenario.MultipleNodes).attempt
        yield (outcome, checkpoint)
      }
    yield
      assert(result._1.isLeft)
      assert(result._2.isLeft)
    ).unsafeToFuture()
  }
