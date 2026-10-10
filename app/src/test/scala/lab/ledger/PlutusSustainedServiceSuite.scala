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

import lab.{AdaSubmissionService, AdaHttpHandler, ReferenceJson}
import lab.submission.*
import lab.header.PraosCertificateState.Point
import cats.effect.{IO, Ref}
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scala.concurrent.duration.*

class PlutusSustainedServiceSuite extends munit.FunSuite:
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
      inputChoice: V = input,
      collateralChoice: V = collateral
  ): Bytes =
    val body = m(
      0 -> tag(a(inputChoice)),
      1 -> a(a(b(keyAddress), V.UInt(20000000 - fee))),
      2 -> V.UInt(fee),
      3 -> V.UInt(upper),
      8 -> V.UInt(lower),
      11 -> b(commitment),
      13 -> tag(a(collateralChoice))
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

  private val secondInput = a(b(Bytes(Vector.fill(32)(4.toByte))), V.UInt(0))
  private val secondCollateral = a(b(Bytes(Vector.fill(32)(5.toByte))), V.UInt(0))
  private val whole = enc(
    V.Map(
      Vector(
        n(input) -> n(scriptOutput),
        n(collateral) -> n(collateralOutput),
        n(secondInput) -> n(scriptOutput),
        n(secondCollateral) -> n(collateralOutput)
      )
    )
  )
  private def view(ledger: ClusterTransition.State, generation: Int): AdmissionView =
    val pin = get(
      StatePin.checked(
        genesis,
        generation,
        Point(sha(ledger.outputMap), ledger.slot, generation),
        sha(ledger.outputMap),
        ledger.id,
        ledger.environment.id,
        ledger.slot,
        AdmissionProfile.PlutusV3.id
      )
    )
    get(AdmissionView.checked(pin, ledger))
  // This fixture owns checked ledger publication, not header/consensus authority. Real
  // SubmissionOwner fencing is covered separately by SubmissionOwnerSuite.
  private class Owner(val cell: Ref[IO, AdmissionView], gate: Semaphore[IO])
      extends AdmissionState[IO]:
    def current = gate.permit.use(_ => cell.get)
    def withCurrent[A](pin: StatePin)(action: IO[A]) = gate.permit.use(_ =>
      IO.uncancelable(_ =>
        cell.get.flatMap(v => if v.pin == pin then action.map(Right(_)) else IO.pure(Left(v.pin)))
      )
    )
    def include(service: AdaSubmissionService[IO], original: Bytes): IO[AdmissionView] =
      gate.permit.use(_ =>
        IO.uncancelable(_ =>
          cell.get.flatMap { before =>
            IO {
              val prepared = get(
                ClusterTransition.prepareBlock(
                  before.ledger,
                  sha(original),
                  Vector(original),
                  before.ledger.slot + 1,
                  Some(Pv9SubmissionEvaluator)
                )
              )
              val committed = get(ClusterTransition.commitBlock(before.ledger, prepared))
              view(committed.state, before.pin.generation.toInt + 1)
            }.flatMap { after =>
              val tx = get(SignedTransaction.checked(original))
              cell.set(after) *> service
                .changed(
                  AdmissionStateChange(
                    after,
                    StateChangeKind.Published,
                    Vector(
                      IncludedTransaction(
                        tx.transactionId,
                        Some(tx.originalBody),
                        Some(tx.originalWitnesses)
                      )
                    )
                  )
                )
                .as(after)
            }
          }
        )
      )
  private def owner =
    val initial = view(get(ClusterTransition.checkpoint(env, whole, 200000, 100, genesis)), 0)
    (Ref.of[IO, AdmissionView](initial), Semaphore[IO](1)).mapN(new Owner(_, _))
  private def second = transaction(inputChoice = secondInput, collateralChoice = secondCollateral)
  private def submit(s: AdaSubmissionService[IO], raw: Bytes) = s.request.use(_.get.submit(raw))
  private def ready(s: AdaSubmissionService[IO]): IO[Unit] =
    s.snapshot
      .flatMap(x => if x.rebuilding then IO.cede *> ready(s) else IO.unit)
      .timeout(5.seconds)
  private def accepted(result: AdaSubmissionService.Result) = result match
    case AdaSubmissionService.Result.Accepted(receipt) => receipt
    case other                                         => fail(other.toString)

  test(
    "two disjoint pending spends survive sequential checked inclusions with one owner and fresh pins"
  ) {
    (for
      o <- owner
      _ <- AdaSubmissionService.resource[IO](o).use { s =>
        val first = transaction()
        val next = second
        for
          before <- o.current
          firstAccepted <- submit(s, first).map(accepted)
          secondAccepted <- submit(s, next).map(accepted)
          duplicate <- submit(s, first)
          conflict <- submit(s, transaction(fee = 300001))
          untouched <- o.current
          _ = assert(untouched.ledger eq before.ledger)
          _ = assertEquals(untouched.pin, before.pin)
          _ = assert(duplicate.isInstanceOf[AdaSubmissionService.Result.AlreadyPresent])
          _ = assert(conflict.isInstanceOf[AdaSubmissionService.Result.PoolRejected])
          afterFirst <- o.include(s, first)
          _ <- ready(s)
          pending <- s.status(secondAccepted.transactionId)
          _ = pending match
            case Some(AdaPool.Status.Pending(receipt, true)) =>
              assertEquals(receipt.pin, afterFirst.pin)
              assert(receipt.pin != secondAccepted.pin)
            case other => fail(other.toString)
          invoked <- Ref.of[IO, Boolean](false)
          stale <- o.withCurrent(firstAccepted.pin)(invoked.set(true))
          didInvoke <- invoked.get
          _ = assert(stale.isLeft && !didInvoke)
          stillPending <- s.snapshot
          _ = assertEquals(
            stillPending.eligible.map(_.transactionId),
            Vector(secondAccepted.transactionId)
          )
          afterSecond <- o.include(s, next)
          _ <- ready(s)
          drained <- s.snapshot
          firstStatus <- s.status(firstAccepted.transactionId)
          secondStatus <- s.status(secondAccepted.transactionId)
          _ = assertEquals(firstStatus, Some(AdaPool.Status.Included(afterFirst.pin)))
          _ = assertEquals(secondStatus, Some(AdaPool.Status.Included(afterSecond.pin)))
          _ = assert(drained.eligible.isEmpty && !drained.closed)
          _ = assertEquals(afterSecond.pin.ownerId, before.pin.ownerId)
          _ = assertEquals(afterSecond.pin.profileId, before.pin.profileId)
          _ = assertEquals(afterSecond.pin.generation, BigInt(2))
          _ = assertEquals(afterSecond.ledger.fees, BigInt(800000))
          outputs = get(PlutusOutput.snapshot(afterSecond.ledger.outputMap, 0)).outputs
          _ = assertEquals(
            outputs(get(TxIn.create(collateralId, 0))).original,
            enc(collateralOutput)
          )
          _ = assertEquals(
            outputs(get(TxIn.create(Bytes(Vector.fill(32)(5.toByte)), 0))).original,
            enc(collateralOutput)
          )
        yield ()
      }
    yield ()).timeout(15.seconds).unsafeToFuture()
  }

  test("HTTP adapter stays available after first inclusion and cannot mutate confirmed state") {
    (for
      o <- owner
      _ <- AdaSubmissionService.resource[IO](o).use { s =>
        val http = AdaHttpHandler(s)
        def post(raw: Bytes) = http.request.use(_.get.submit(raw))
        for
          before <- o.current
          one <- post(transaction())
          unchanged <- o.current
          _ = assertEquals(one.status, 202)
          _ = assert(unchanged.ledger eq before.ledger)
          afterOne <- o.include(s, transaction())
          _ <- ready(s)
          alive <- http.request.use(_.get.state)
          two <- post(second)
          duplicate <- post(second)
          malformed <- post(Bytes(Vector(0x84.toByte)))
          same <- o.current
          _ = assertEquals(alive.status, 200)
          _ = assertEquals(two.status, 202)
          _ = assertEquals(duplicate.status, 200)
          _ = assertEquals(malformed.status, 400)
          _ = assert(same.ledger eq afterOne.ledger)
          response = ReferenceJson.parse(Bytes.fromArray(two.json.getBytes("UTF-8")))
          _ = assertEquals(
            ReferenceJson.uint(ReferenceJson.field(response, "receipt", "pin", "generation")),
            BigInt(1)
          )
          afterTwo <- o.include(s, second)
          _ <- ready(s)
          replay <- post(transaction())
          finalView <- o.current
          _ = assertEquals(replay.status, 422)
          _ = assert(finalView.ledger eq afterTwo.ledger)
          finalPool <- s.snapshot
          _ = assert(!finalPool.closed && finalPool.size == 0)
        yield ()
      }
    yield ()).timeout(15.seconds).unsafeToFuture()
  }

  test("exhausted evidence store closes admission and relay while preserving confirmed ledger") {
    (for
      o <- owner
      before <- o.current
      checked <- IO(
        get(
          AdmissionValidation.prepare(
            AdmissionProfile.PlutusV3,
            before.pin,
            before.ledger,
            transaction(),
            Some(Pv9SubmissionEvaluator)
          )
        )
      )
      observation = lab.PlutusEvaluationEvidence.checked(checked, "admission", "accepted").get
      _ <- cats.effect.Resource
        .make(IO.blocking(Files.createTempDirectory("sustained-cap-test-"))) { root =>
          IO.blocking {
            import scala.jdk.CollectionConverters.*
            val files = Files.walk(root)
            try
              files
                .iterator()
                .asScala
                .toVector
                .sortBy(_.getNameCount)
                .reverse
                .foreach(Files.delete(_))
            finally files.close()
          }
        }
        .use { root =>
          lab.PlutusEvaluationEvidence
            .fileObserver[IO](root.resolve("receipts"), genesis, sha(model))
            .use { store =>
              Vector
                .fill(lab.PlutusEvaluationEvidence.MaxRecords)(())
                .traverse_(_ => store.observe(observation)) *>
                AdaSubmissionService.resource[IO](o, evidence = Some(store)).use { service =>
                  for
                    result <- submit(service, second)
                    pool <- service.snapshot
                    after <- o.current
                    events <- store.records
                    _ = assertEquals(result, AdaSubmissionService.Result.Unavailable)
                    _ = assert(pool.closed && pool.eligible.isEmpty && pool.size == 0)
                    _ = assert(after.ledger eq before.ledger)
                    _ = assertEquals(after.pin, before.pin)
                    _ = assertEquals(events.size, lab.PlutusEvaluationEvidence.MaxRecords)
                    _ = assert(
                      !events.exists(
                        _.observation.transactionId == get(
                          SignedTransaction.checked(second)
                        ).transactionId
                      )
                    )
                  yield ()
                }
            }
        }
    yield ()).timeout(20.seconds).unsafeToFuture()
  }
