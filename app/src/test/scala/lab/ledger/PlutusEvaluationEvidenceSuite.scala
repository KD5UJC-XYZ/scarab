// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.plutus.PlutusExecution as E
import lab.vm.Pv9SubmissionEvaluator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

import lab.{AdaSubmissionService, PlutusEvaluationEvidence, ReferenceJson}
import lab.submission.*
import lab.header.PraosCertificateState.Point
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class PlutusEvaluationEvidenceSuite extends munit.FunSuite:
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def n(v: V): Node = Node(v, Bytes.empty)
  private def a(v: V*): V = V.Arr(v.toVector.map(n))
  private def m(v: (Int, V)*): V = V.Map(v.toVector.map((k, v) => n(V.UInt(k)) -> n(v)))
  private def b(v: Bytes): V = V.ByteString(v)
  private def enc(v: V): Bytes = get(Cbor.encode(v))
  private def tag(v: V): V = V.Tag(258, n(v))
  private def sha(b: Bytes): Bytes =
    Bytes.fromArray(MessageDigest.getInstance("SHA-256").digest(b.toArray))
  private val model =
    Bytes.fromArray(Files.readAllBytes(Path.of("vm/src/main/resources/plutus-pv9/cost-model.json")))
  private val script = Bytes.fromArray(
    Files.readAllBytes(Path.of("vm/src/test/resources/plutus-pv9-reference/script.cbor"))
  )
  private val key = new Ed25519PrivateKeyParameters(Array.fill[Byte](32)(7), 0)
  private val publicKey = Bytes.fromArray(key.generatePublicKey().getEncoded)
  private val beneficiary = Blake2b.hash224.hash(publicKey)
  private val scriptHash = Blake2b.hash224.hash(Bytes(Vector(3.toByte) ++ script.value))
  private val scriptAddress = Bytes(Vector(0x70.toByte) ++ scriptHash.value)
  private val keyAddress = Bytes(Vector(0x60.toByte) ++ beneficiary.value)
  private val inputId = Bytes(Vector.fill(32)(1.toByte))
  private val collateralId = Bytes(Vector.fill(32)(2.toByte))
  private val input = a(b(inputId), V.UInt(0))
  private val collateral = a(b(collateralId), V.UInt(0))
  private val datum = enc(V.Tag(121, n(a(b(beneficiary), V.UInt(5000000)))))
  private val scriptOutput =
    m(0 -> b(scriptAddress), 1 -> V.UInt(20000000), 2 -> a(V.UInt(1), V.Tag(24, n(b(datum)))))
  private val collateralOutput = a(b(keyAddress), V.UInt(5000000))
  private val utxo = enc(
    V.Map(Vector(n(input) -> n(scriptOutput), n(collateral) -> n(collateralOutput)))
  )
  private val redeemers = V.Map(
    Vector(n(a(V.UInt(0), V.UInt(0))) -> n(a(V.UInt(7), a(V.UInt(100000), V.UInt(30000000)))))
  )
  private val integrity = get(
    PlutusIntegrity.commitment(enc(redeemers), get(PlutusIntegrity.languageView(model)))
  )
  private def ratio(x: Int, y: Int): V = V.Tag(30, n(a(V.UInt(x), V.UInt(y))))
  private val costs = new String(model.toArray, "UTF-8").trim
    .drop(1)
    .dropRight(1)
    .split(",")
    .toVector
    .map(x => BigInt(x.trim))
  private val pValues = Vector
    .fill[V](31)(V.UInt(0))
    .updated(0, V.UInt(44))
    .updated(1, V.UInt(155381))
    .updated(3, V.UInt(16384))
    .updated(12, a(V.UInt(9), V.UInt(0)))
    .updated(14, V.UInt(4310))
    .updated(15, m(2 -> V.Arr(costs.map(x => n(if x < 0 then V.NInt(x) else V.UInt(x))))))
    .updated(16, a(ratio(577, 10000), ratio(721, 10000000)))
    .updated(17, a(V.UInt(14000000), V.UInt(10000000000L)))
    .updated(18, a(V.UInt(62000000), V.UInt(20000000000L)))
    .updated(19, V.UInt(5000))
    .updated(20, V.UInt(150))
    .updated(21, V.UInt(3))
  private val rawParameters = enc(V.Arr(pValues.map(n)))
  private val parameters = get(PlutusParameters.decode(rawParameters, sha(rawParameters), model))
  private val genesis = Bytes(Vector.fill(32)(3.toByte))
  private val base = get(
    ClusterTransition.environment(genesis, sha(rawParameters), 42, 0, 9, 0, 44, 155381, 16384, 4310)
  )
  private val profile = get(
    PlutusEnvironment.bind(
      base,
      parameters,
      PlutusContextInput.SlotTime(1700000000000L, 100, 1, genesis),
      0
    )
  )
  private val env = get(ClusterTransition.withPlutus(base, profile))
  private def state = get(ClusterTransition.checkpoint(env, utxo, 200000, 100, genesis))

  private def transaction(
      fee: Int = 300000,
      valid: Boolean = true,
      badSignature: Boolean = false,
      lower: Int = 0,
      upper: Int = 2000,
      commitment: Bytes = integrity,
      inputChoice: V = input
  ): Bytes =
    val body = m(
      0 -> tag(a(inputChoice)),
      1 -> a(a(b(keyAddress), V.UInt(20000000 - fee))),
      2 -> V.UInt(fee),
      3 -> V.UInt(upper),
      8 -> V.UInt(lower),
      11 -> b(commitment),
      13 -> tag(a(collateral))
    )
    val signer = new Ed25519Signer()
    signer.init(true, key)
    val message = Blake2b.hash256.hash(enc(body)).toArray
    signer.update(message, 0, message.length)
    val signed = signer.generateSignature()
    if badSignature then signed(0) = (signed(0) ^ 1).toByte
    val witness = m(
      0 -> tag(a(a(b(publicKey), b(Bytes.fromArray(signed))))),
      5 -> redeemers,
      7 -> tag(a(b(script)))
    )
    enc(a(body, witness, V.Bool(valid), V.Null))

  private def admissionView(generation: Int = 0, slot: Int = 100): AdmissionView =
    val ledger = get(ClusterTransition.checkpoint(env, utxo, 200000, slot, genesis))
    val pin = get(
      StatePin.checked(
        genesis,
        generation,
        Point(genesis, slot, generation),
        sha(ledger.outputMap),
        ledger.id,
        ledger.environment.id,
        slot,
        AdmissionProfile.PlutusV3.id
      )
    )
    get(AdmissionView.checked(pin, ledger))
  private class Owner(val cell: Ref[IO, AdmissionView], val gate: Semaphore[IO], before: IO[Unit])
      extends AdmissionState[IO]:
    def current = gate.permit.use(_ => cell.get)
    def withCurrent[A](expected: StatePin)(commit: IO[A]): IO[Either[StatePin, A]] =
      before *> gate.permit.use(_ =>
        IO.uncancelable(_ =>
          cell.get.flatMap(v =>
            if v.pin == expected then commit.map(Right(_)) else IO.pure(Left(v.pin))
          )
        )
      )
    def move(service: AdaSubmissionService[IO]): IO[Unit] = gate.permit.use(_ =>
      IO.uncancelable(_ =>
        IO(admissionView(1, 101)).flatMap(next =>
          cell.set(next) *>
            service.changed(AdmissionStateChange(next, StateChangeKind.Published, Vector.empty))
        )
      )
    )
  private def owner(before: IO[Unit] = IO.unit): IO[Owner] =
    (Ref.of[IO, AdmissionView](admissionView()), Semaphore[IO](1)).mapN(new Owner(_, _, before))
  private def submit(
      service: AdaSubmissionService[IO],
      original: Bytes
  ): IO[AdaSubmissionService.Result] =
    service.request.use(_.get.submit(original))
  private def ready(service: AdaSubmissionService[IO]): IO[Unit] =
    service.snapshot
      .flatMap(s => if s.rebuilding then IO.cede *> ready(service) else IO.unit)
      .timeout(5.seconds)
  private def collect(ref: Ref[IO, Vector[PlutusEvaluationEvidence.Observation]]) =
    new PlutusEvaluationEvidence.Observer[IO]:
      def observe(value: PlutusEvaluationEvidence.Observation) = ref.update(_ :+ value)
  private def temporary[A](use: Path => IO[A]): IO[A] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("plutus-evidence-test-"))) { root =>
        IO.blocking {
          val walk = Files.walk(root)
          try
            walk.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
          finally walk.close()
        }
      }
      .use(use)

  test("evidence retains the actual evaluator result with no rerun and binds source metadata") {
    var evaluations = 0
    val evaluator = new E.Evaluator:
      def evaluate(request: E.Request) =
        evaluations += 1
        Pv9SubmissionEvaluator.evaluate(request)
    val view = admissionView()
    val candidate = get(
      AdmissionValidation.prepare(
        AdmissionProfile.PlutusV3,
        view.pin,
        view.ledger,
        transaction(),
        Some(evaluator)
      )
    )
    val observation = PlutusEvaluationEvidence.checked(candidate, "admission", "accepted").get
    assert(observation.execution eq candidate.plutusAdmission.get.execution)
    assert(observation.execution.consumed.memory > 0 && observation.execution.consumed.steps > 0)
    temporary { root =>
      PlutusEvaluationEvidence.fileObserver[IO](root.resolve("receipts"), genesis, sha(model)).use {
        store =>
          for
            _ <- store.observe(observation)
            rows <- store.records
            raw <- IO.blocking(
              Bytes.fromArray(
                Files.readAllBytes(root.resolve("receipts").resolve(rows.head.filename))
              )
            )
            parsed = ReferenceJson.parse(raw)
            _ = assertEquals(evaluations, 1)
            _ = assertEquals(rows.size, 1)
            _ = assertEquals(rows.head.sha256, sha(raw))
            _ = assertEquals(
              ReferenceJson.string(ReferenceJson.field(parsed, "sourceJoinId")),
              genesis.hex
            )
            _ = assertEquals(
              ReferenceJson.string(ReferenceJson.field(parsed, "initialManifestSHA256")),
              sha(model).hex
            )
            _ = assertEquals(
              ReferenceJson.string(ReferenceJson.field(parsed, "requestDigest")),
              observation.execution.requestDigest.hex
            )
            _ = assertEquals(
              ReferenceJson.string(ReferenceJson.field(parsed, "bodySHA256")),
              sha(candidate.transaction.originalBody).hex
            )
            _ = assertEquals(
              ReferenceJson.uint(ReferenceJson.field(parsed, "consumed", "memory")),
              observation.execution.consumed.memory
            )
            _ = assertEquals(
              ReferenceJson.uint(ReferenceJson.field(parsed, "consumed", "steps")),
              observation.execution.consumed.steps
            )
            _ = assert(!new String(raw.toArray, "UTF-8").contains("contextCbor"))
          yield ()
      }
    }.unsafeToFuture()
  }

  test("accepted duplicate and revalidation preserve separate evaluation identities and phases") {
    (for
      events <- Ref.of[IO, Vector[PlutusEvaluationEvidence.Observation]](Vector.empty)
      o <- owner()
      _ <- AdaSubmissionService.resource[IO](o, evidence = Some(collect(events))).use { s =>
        for
          accepted <- submit(s, transaction())
          duplicate <- submit(s, transaction())
          _ = assert(accepted.isInstanceOf[AdaSubmissionService.Result.Accepted])
          _ = assert(duplicate.isInstanceOf[AdaSubmissionService.Result.AlreadyPresent])
          _ <- o.move(s)
          _ <- ready(s)
          rows <- events.get
          _ = assertEquals(
            rows.map(r => (r.phase, r.outcome)),
            Vector(
              ("admission", "accepted"),
              ("admission", "already-present"),
              ("revalidation", "retained")
            )
          )
          _ = assert(rows.head.newlyAdmitted && !rows(1).newlyAdmitted && !rows(2).newlyAdmitted)
          _ = assert(!(rows.head.execution eq rows(1).execution))
          _ = assertEquals(rows.head.envelopeSHA256, rows(1).envelopeSHA256)
          _ = assertEquals(rows(2).pin.validationSlot, BigInt(101))
          _ = assert(rows(2).execution.requestDigest != rows.head.execution.requestDigest)
          _ = assert(rows.forall(r => !r.inclusionClaimed && !r.fullLedgerValidated))
        yield ()
      }
    yield ()).unsafeToFuture()
  }

  test("rejected signature creates no successful evaluation observation") {
    (for
      events <- Ref.of[IO, Vector[PlutusEvaluationEvidence.Observation]](Vector.empty)
      o <- owner()
      _ <- AdaSubmissionService.resource[IO](o, evidence = Some(collect(events))).use { s =>
        for
          result <- submit(s, transaction(badSignature = true))
          rows <- events.get
          snapshot <- s.snapshot
          _ = assert(result.isInstanceOf[AdaSubmissionService.Result.Rejected])
          _ = assert(rows.isEmpty && snapshot.eligible.isEmpty)
        yield ()
      }
    yield ()).unsafeToFuture()
  }

  test("owner movement before installation discards checked evaluation without accepted evidence") {
    (for
      calls <- Ref.of[IO, Int](0)
      entered <- Deferred[IO, Unit]
      resume <- Deferred[IO, Unit]
      events <- Ref.of[IO, Vector[PlutusEvaluationEvidence.Observation]](Vector.empty)
      o <- owner(
        calls
          .getAndUpdate(_ + 1)
          .flatMap(n => if n == 1 then entered.complete(()).void *> resume.get else IO.unit)
      )
      _ <- AdaSubmissionService.resource[IO](o, evidence = Some(collect(events))).use { s =>
        for
          fiber <- submit(s, transaction()).start
          _ <- entered.get
          _ <- o.move(s)
          _ <- resume.complete(())
          result <- fiber.joinWithNever
          _ <- ready(s)
          rows <- events.get
          snapshot <- s.snapshot
          _ = assert(result.isInstanceOf[AdaSubmissionService.Result.Retry])
          _ = assert(rows.isEmpty && snapshot.eligible.isEmpty)
        yield ()
      }
    yield ()).timeout(10.seconds).unsafeToFuture()
  }

  test("observer write failure closes pool before any successful response or eligible relay") {
    val sink = new PlutusEvaluationEvidence.Observer[IO]:
      def observe(value: PlutusEvaluationEvidence.Observation) =
        IO.raiseError(new java.io.IOException("test sink failure"))
    (for
      o <- owner()
      _ <- AdaSubmissionService.resource[IO](o, evidence = Some(sink)).use { s =>
        for
          result <- submit(s, transaction())
          snapshot <- s.snapshot
          _ = assertEquals(result, AdaSubmissionService.Result.Unavailable)
          _ = assert(snapshot.closed && snapshot.size == 0 && snapshot.eligible.isEmpty)
        yield ()
      }
    yield ()).unsafeToFuture()
  }

  test(
    "cancellation during persistence cannot release the owner fence with an eligible unrecorded entry"
  ) {
    (for
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      returned <- Ref.of[IO, Boolean](false)
      sink = new PlutusEvaluationEvidence.Observer[IO]:
        def observe(value: PlutusEvaluationEvidence.Observation) =
          entered.complete(()).void *> release.get *>
            IO.raiseError(new java.io.IOException("delayed persistence failed"))
      o <- owner()
      _ <- AdaSubmissionService.resource[IO](o, evidence = Some(sink)).use { s =>
        for
          fiber <- submit(s, transaction()).flatTap(_ => returned.set(true)).start
          _ <- entered.get
          cancelling <- fiber.cancel.start
          acquired <- o.gate.tryAcquire
          _ <- if acquired then o.gate.release else IO.unit
          _ = assert(!acquired, "relay owner fence released before evidence completed")
          _ <- release.complete(())
          _ <- cancelling.joinWithNever
          _ <- fiber.join
          snapshot <- s.snapshot
          response <- returned.get
          _ = assert(!response)
          _ = assert(snapshot.closed && snapshot.eligible.isEmpty)
        yield ()
      }
    yield ()).timeout(10.seconds).unsafeToFuture()
  }

  test(
    "store refuses overwrite and records no successful publication for failed file persistence"
  ) {
    val view = admissionView()
    val candidate = get(
      AdmissionValidation.prepare(
        AdmissionProfile.PlutusV3,
        view.pin,
        view.ledger,
        transaction(),
        Some(Pv9SubmissionEvaluator)
      )
    )
    val observation = PlutusEvaluationEvidence.checked(candidate, "admission", "accepted").get
    temporary { root =>
      val directory = root.resolve("receipts")
      PlutusEvaluationEvidence.fileObserver[IO](directory, genesis, sha(model)).use { store =>
        val target = directory.resolve("plutus-evaluation-0000.json")
        for
          _ <- IO.blocking(Files.write(target, Array[Byte](42)))
          result <- store.observe(observation).attempt
          rows <- store.records
          bytes <- IO.blocking(Files.readAllBytes(target).toVector)
          _ = assert(result.isLeft && rows.isEmpty)
          _ = assertEquals(bytes, Vector[Byte](42))
        yield ()
      }
    }.unsafeToFuture()
  }

  test("cooperative observer deadline closes pool and does not return accepted") {
    val sink = new PlutusEvaluationEvidence.Observer[IO]:
      def observe(value: PlutusEvaluationEvidence.Observation) = IO.never[Unit]
    cats.effect.testkit.TestControl
      .executeEmbed(for
        o <- owner()
        _ <- AdaSubmissionService.resource[IO](o, evidence = Some(sink)).use { s =>
          for
            result <- submit(s, transaction())
            snapshot <- s.snapshot
            _ = assertEquals(result, AdaSubmissionService.Result.Unavailable)
            _ = assert(snapshot.closed && snapshot.eligible.isEmpty)
          yield ()
        }
      yield ())
      .unsafeToFuture()
  }

  test("store record cap refuses further writes without changing retained records") {
    val view = admissionView()
    val candidate = get(
      AdmissionValidation.prepare(
        AdmissionProfile.PlutusV3,
        view.pin,
        view.ledger,
        transaction(),
        Some(Pv9SubmissionEvaluator)
      )
    )
    val observation = PlutusEvaluationEvidence.checked(candidate, "admission", "accepted").get
    temporary { root =>
      val directory = root.resolve("receipts")
      PlutusEvaluationEvidence.fileObserver[IO](directory, genesis, sha(model)).use { store =>
        for
          _ <- Vector
            .fill(PlutusEvaluationEvidence.MaxRecords)(())
            .traverse_(_ => store.observe(observation))
          before <- store.records
          failed <- store.observe(observation).attempt
          after <- store.records
          _ = assert(failed.isLeft)
          _ = assertEquals(before, after)
          _ = assertEquals(after.size, PlutusEvaluationEvidence.MaxRecords)
          _ <- IO.blocking(
            assert(!Files.exists(directory.resolve("plutus-evaluation-0128.json.part")))
          )
        yield ()
      }
    }.unsafeToFuture()
  }
